package com.example.albumlight

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {

    companion object {
        const val CHANNEL_ID = "albumlight_service"
        const val NOTIF_ID = 1
    }

    // Callbacks registered by MainActivity to receive live UI updates from the service.
    // Null when the UI is not visible — the service just skips UI updates.
    var onStatusChanged: ((status: String) -> Unit)? = null
    var onTrackChanged: ((title: String, artist: String, colorHex: String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Album Light Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps AlbumLight running in the background"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
