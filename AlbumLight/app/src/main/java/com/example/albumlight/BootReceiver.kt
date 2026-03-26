package com.example.albumlight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Restarts [LightSyncService] after a device reboot if the user had it enabled.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs(context).serviceEnabled) return

        ContextCompat.startForegroundService(context, LightSyncService.startIntent(context))
    }
}
