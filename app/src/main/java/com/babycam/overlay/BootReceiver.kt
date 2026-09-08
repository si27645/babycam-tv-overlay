package com.babycam.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Restarts the overlay after the TV box reboots (power cut, etc.), but only if the user
 * explicitly opted in via the "Auto-start on boot" switch, a stream URL is configured, and
 * the overlay permission is still granted.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val settings = SettingsStore(context)
        if (!settings.autoStartOnBoot) return
        if (settings.enabledCameras().isEmpty()) return
        if (!canDrawOverlaysCompat(context)) return

        val serviceIntent = Intent(context, OverlayService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
