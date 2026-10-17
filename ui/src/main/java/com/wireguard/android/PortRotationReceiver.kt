/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wireguard.android.util.applicationScope
import kotlinx.coroutines.launch

/**
 * Runs the daily client-side endpoint-port rotation (see [PortRotation]) when the
 * scheduled alarm fires, and re-arms the schedule after a device reboot so a
 * rotation missed while the device was off is caught up on the next trigger.
 */
class PortRotationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                when (intent.action) {
                    PortRotation.ACTION_ROTATE_PORTS, Intent.ACTION_BOOT_COMPLETED -> {
                        Log.i(TAG, "Running endpoint-port rotation (${intent.action})")
                        PortRotation.rotateAll()
                        PortRotation.scheduleNext(context)
                    }
                    else -> Log.w(TAG, "Ignoring unexpected action: ${intent.action}")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Endpoint-port rotation failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "WireGuard/PortRotationReceiver"
    }
}
