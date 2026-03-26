package com.example.albumlight

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * NotificationListenerService — the Android system binds this service automatically
 * once the user grants "Notification Access" in system settings.
 *
 * Its sole job here is to act as the *security token* that allows
 * [LightSyncService] to call MediaSessionManager.getActiveSessions().
 * Without a bound NLS the MediaSession API rejects the call with a
 * SecurityException, regardless of any other permissions.
 *
 * When the listener becomes connected/disconnected we fire intents to
 * [LightSyncService] so it can register/unregister its MediaSession callbacks.
 */
class AlbumLightNLS : NotificationListenerService() {

    companion object {
        private const val TAG = "AlbumLightNLS"
        const val ACTION_NLS_CONNECTED    = "com.example.albumlight.NLS_CONNECTED"
        const val ACTION_NLS_DISCONNECTED = "com.example.albumlight.NLS_DISCONNECTED"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
        notifyService(ACTION_NLS_CONNECTED)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Notification listener disconnected")
        notifyService(ACTION_NLS_DISCONNECTED)
    }

    private fun notifyService(action: String) {
        // Only forward NLS events when the user has explicitly started the service.
        if (!Prefs(this).serviceEnabled) return

        val intent = Intent(this, LightSyncService::class.java).apply {
            this.action = action
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start LightSyncService: ${e.message}")
        }
    }
}
