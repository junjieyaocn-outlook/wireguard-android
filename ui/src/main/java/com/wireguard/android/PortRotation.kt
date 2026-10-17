/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.wireguard.android.model.TunnelManager
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.Peer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Client-side follow-up for the server's daily listening-port rotation.
 *
 * The server switches its listening port every day at 04:00 (server-local time) to
 * `4 + zero-padded month + zero-padded day-of-month`, e.g. November 3rd -> 41103,
 * January 3rd -> 40103. This object keeps every tunnel whose name starts with
 * [TUNNEL_NAME_PREFIX] usable by rewriting all of its peers' endpoint ports to the
 * port of the current day (hosts are left untouched) and reconnecting tunnels that
 * are currently up. The `[Interface]` section -- including any `ListenPort` -- is
 * never modified; this device is a client.
 */
object PortRotation {
    const val TUNNEL_NAME_PREFIX = "wg_"
    const val ROTATION_HOUR = 4
    const val ROTATION_MINUTE = 0
    const val ACTION_ROTATE_PORTS = "com.wireguard.android.action.ROTATE_PORTS"

    /**
     * The port the server listens on for [date]: 40000 + month * 100 + day-of-month.
     * Always a valid port (40101..41231).
     */
    fun rotationPort(date: LocalDate = LocalDate.now()): Int =
        40000 + date.monthValue * 100 + date.dayOfMonth

    /**
     * Returns a copy of [config] with every peer endpoint port rewritten to [port],
     * or null when no peer would change (so callers can skip no-op saves/reconnects).
     */
    fun withRotatedPort(config: Config, port: Int): Config? {
        var changed = false
        val builder = Config.Builder().setInterface(config.getInterface())
        for (peer in config.peers) {
            val endpoint = peer.endpoint.orElse(null)
            if (endpoint == null || endpoint.port == port) {
                builder.addPeer(peer)
                continue
            }
            changed = true
            val peerBuilder = Peer.Builder()
                .addAllowedIps(peer.allowedIps)
                .setPublicKey(peer.publicKey)
                .setEndpoint(InetEndpoint.parse(withPort(endpoint.host, port)))
            peer.persistentKeepalive.ifPresent { peerBuilder.setPersistentKeepalive(it) }
            peer.preSharedKey.ifPresent { peerBuilder.setPreSharedKey(it) }
            builder.addPeer(peerBuilder.build())
        }
        return if (changed) builder.build() else null
    }

    private fun withPort(host: String, port: Int): String =
        // InetEndpoint.getHost() keeps the brackets on IPv6 literals (e.g. "[2001:db8::1]"),
        // so only add them when missing.
        (if (':' in host && !host.startsWith("[")) "[$host]" else host) + ":$port"

    /**
     * Schedules the next rotation at the coming 04:00 local time using an exact
     * alarm that also fires in Doze. Falls back to an inexact alarm when the
     * SCHEDULE_EXACT_ALARM permission is unavailable.
     */
    fun scheduleNext(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, PortRotationReceiver::class.java).setAction(ACTION_ROTATE_PORTS)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val now = LocalDateTime.now()
        var next = now.withHour(ROTATION_HOUR).withMinute(ROTATION_MINUTE).withSecond(0).withNano(0)
        if (!next.isAfter(now))
            next = next.plusDays(1)
        val triggerAtMillis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarmManager.cancel(pendingIntent)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                Log.w(TAG, "Exact alarms not permitted; falling back to an inexact alarm")
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm denied; falling back to an inexact alarm", e)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
        Log.i(TAG, "Next endpoint-port rotation scheduled at $next (port ${rotationPort(next.toLocalDate())})")
    }

    /**
     * Rewrites the peer endpoint ports of every `wg_`-prefixed tunnel to today's
     * [rotationPort] and persists the change. Tunnels that are currently UP are
     * reconnected with the new configuration; DOWN tunnels are left down and only
     * their stored configuration is updated.
     */
    suspend fun rotateAll() {
        val manager: TunnelManager = Application.getTunnelManager()
        val port = rotationPort()
        for (tunnel in manager.getTunnels()) {
            if (!tunnel.name.startsWith(TUNNEL_NAME_PREFIX))
                continue
            try {
                val rotated = withRotatedPort(manager.getTunnelConfig(tunnel), port) ?: continue
                // setTunnelConfig persists the config and re-applies it to the backend
                // with the tunnel's current state, which reconnects UP tunnels.
                manager.setTunnelConfig(tunnel, rotated)
                Log.i(TAG, "Rotated endpoint port of tunnel \"${tunnel.name}\" to $port")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to rotate endpoint port of tunnel \"${tunnel.name}\"", e)
            }
        }
    }

    private const val TAG = "WireGuard/PortRotation"
}
