package com.example.albumlight

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt

/**
 * Foreground service that:
 *  1. Registers a [MediaSessionManager.OnActiveSessionsChangedListener] (requires
 *     [AlbumLightNLS] to be connected).
 *  2. Attaches [MediaController.Callback] to every active session to receive
 *     real-time metadata / playback-state changes.
 *  3. On a track change, extracts the dominant colour from the album art and
 *     smoothly transitions the Tuya bulb to that colour.
 */
class LightSyncService : LifecycleService() {

    companion object {
        private const val TAG = "LightSyncService"

        const val ACTION_START            = "com.example.albumlight.START"
        const val ACTION_STOP             = "com.example.albumlight.STOP"

        fun startIntent(ctx: Context)  = Intent(ctx, LightSyncService::class.java).apply { action = ACTION_START }
        fun stopIntent(ctx: Context)   = Intent(ctx, LightSyncService::class.java).apply { action = ACTION_STOP }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private lateinit var prefs: Prefs
    private var mediaSessionManager: MediaSessionManager? = null

    /** One callback per active MediaSession token (token.toString() → callback). */
    private val controllerCallbacks = mutableMapOf<String, MediaController.Callback>()
    private val activeControllers   = mutableMapOf<String, MediaController>()

    /** Colour state of the bulb — kept for smooth transitions. */
    @Volatile private var currentH: Int? = null
    @Volatile private var currentS: Int? = null
    @Volatile private var currentV: Int? = null

    /** Signature of the last processed track — avoids duplicate work. */
    @Volatile private var lastTrackSig = ""

    /** Single-threaded executor; all Tuya I/O runs here. */
    private val bulbExecutor = Executors.newSingleThreadExecutor()
    private var currentBulbTask: Future<*>? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Session-changed listener (registered once NLS is ready) ───────────────

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            mainHandler.post { updateControllers(controllers ?: emptyList()) }
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                prefs.serviceEnabled = false
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            AlbumLightNLS.ACTION_NLS_CONNECTED -> {
                if (prefs.serviceEnabled) registerMediaSessions()
            }
            AlbumLightNLS.ACTION_NLS_DISCONNECTED -> unregisterMediaSessions()
            else -> {
                // ACTION_START or auto-restart — try to attach if NLS already connected
                prefs.serviceEnabled = true
                tryRegisterMediaSessions()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        teardown()
        bulbExecutor.shutdownNow()
        super.onDestroy()
    }

    // ── Foreground notification ───────────────────────────────────────────────

    private fun startForegroundNotification(track: String = "Waiting for music…") {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("AlbumLight")
            .setContentText(track)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()

        startForeground(App.NOTIF_ID, notification)
    }

    private fun updateNotification(track: String) {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("AlbumLight")
            .setContentText(track)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()

        getSystemService(android.app.NotificationManager::class.java)
            .notify(App.NOTIF_ID, notification)
    }

    // ── MediaSession registration ─────────────────────────────────────────────

    private fun tryRegisterMediaSessions() {
        try {
            registerMediaSessions()
        } catch (e: SecurityException) {
            // NLS not yet connected — we'll receive ACTION_NLS_CONNECTED later
            Log.d(TAG, "NLS not ready yet, will register when connected")
            broadcastStatus("Waiting for Notification Access…")
        }
    }

    private fun registerMediaSessions() {
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        mediaSessionManager = msm

        try {
            val nlsComponent = ComponentName(this, AlbumLightNLS::class.java)
            msm.addOnActiveSessionsChangedListener(sessionsChangedListener, nlsComponent)
            updateControllers(msm.getActiveSessions(nlsComponent))
            broadcastStatus("Running — listening for music")
            Log.i(TAG, "MediaSession listener registered")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to register MediaSession listener: ${e.message}")
            broadcastStatus("Grant Notification Access to detect music")
        }
    }

    private fun unregisterMediaSessions() {
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Exception) {}
        clearControllers()
        broadcastStatus("Notification Access revoked")
    }

    private fun teardown() {
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Exception) {}
        clearControllers()
    }

    // ── Controller management ─────────────────────────────────────────────────

    private fun updateControllers(controllers: List<MediaController>) {
        clearControllers()

        for (controller in controllers) {
            val key = controller.sessionToken.toString()
            val cb = buildCallback(controller)
            controller.registerCallback(cb)
            controllerCallbacks[key] = cb
            activeControllers[key] = controller

            // Immediately check if something is already playing
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                controller.metadata?.let { onMetadata(it) }
            }
        }
        Log.d(TAG, "Tracking ${controllers.size} active session(s)")
    }

    private fun clearControllers() {
        for ((key, cb) in controllerCallbacks) {
            activeControllers[key]?.unregisterCallback(cb)
        }
        controllerCallbacks.clear()
        activeControllers.clear()
    }

    private fun buildCallback(controller: MediaController) = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            // Only act if this session is currently playing
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                metadata?.let { onMetadata(it) }
            }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state?.state == PlaybackState.STATE_PLAYING) {
                controller.metadata?.let { onMetadata(it) }
            }
        }
    }

    // ── Track handling ────────────────────────────────────────────────────────

    private fun onMetadata(metadata: MediaMetadata) {
        val title  = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)  ?: return
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album  = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)  ?: ""

        val sig = "$title|$artist|$album"
        if (sig == lastTrackSig) return
        lastTrackSig = sig

        val displayTrack = if (artist.isNotBlank()) "$title — $artist" else title
        Log.i(TAG, "Track changed: $displayTrack")
        updateNotification(displayTrack)

        // Prefer ALBUM_ART, fall back to ART
        val artwork: Bitmap? =
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        if (artwork == null) {
            Log.d(TAG, "No artwork for '$title', skipping colour change")
            broadcastTrack(displayTrack, "#888888")
            return
        }

        // Cancel any in-progress colour transition before starting a new one
        currentBulbTask?.cancel(false)
        currentBulbTask = bulbExecutor.submit {
            processArtwork(displayTrack, artwork)
        }
    }

    // ── Colour extraction + Tuya transition ───────────────────────────────────

    private fun processArtwork(displayTrack: String, artwork: Bitmap) {
        try {
            val p = prefs  // snapshot prefs on the worker thread

            val rgb      = ColorExtractor.pickDominantRgb(artwork)
            val tuyaHsv  = ColorExtractor.rgbToTuyaHsv(rgb, p.brightnessFixed, p.minSaturation)
            val colorHex = "#%02x%02x%02x".format(rgb.r, rgb.g, rgb.b)

            Log.i(TAG, "Colour for '$displayTrack': RGB(${rgb.r},${rgb.g},${rgb.b}) → H=${tuyaHsv.h} S=${tuyaHsv.s} V=${tuyaHsv.v}")
            broadcastTrack(displayTrack, colorHex)

            val device = TuyaLocalDevice(
                ip       = p.deviceIp,
                devId    = p.deviceId,
                localKey = p.deviceLocalKey,
                version  = p.deviceVersionDouble
            )

            transitionBulb(device, tuyaHsv.h, tuyaHsv.s, tuyaHsv.v, p)

        } catch (e: InterruptedException) {
            Log.d(TAG, "Colour transition interrupted (new track arrived)")
        } catch (e: Exception) {
            Log.e(TAG, "processArtwork error: ${e.message}")
            broadcastStatus("Error: ${e.message}")
        }
    }

    private fun transitionBulb(
        device: TuyaLocalDevice,
        targetH: Int, targetS: Int, targetV: Int,
        p: Prefs
    ) {
        // Open a persistent connection for the entire transition so we don't
        // re-negotiate TCP + v3.5 session for every intermediate colour step.
        device.connect()
        try {
            val startH = currentH
            val startS = currentS
            val startV = currentV

            if (startH == null || startS == null || startV == null) {
                // No previous state — jump directly
                device.setColor(targetH, targetS, targetV)
                Log.d(TAG, "Direct set → H=$targetH S=$targetS V=$targetV")
            } else {
                if (startH == targetH && startS == targetS && startV == targetV) {
                    Log.d(TAG, "Already at target colour, skipping transition")
                    return
                }

                val steps     = p.colorTransitionSteps.coerceIn(1, 20)
                val stepDelayMs = ((p.colorTransitionSeconds * 1000f) / steps).toLong().coerceAtLeast(10L)

                Log.d(TAG, "Transition H:$startH→$targetH over ${p.colorTransitionSeconds}s ($steps steps)")

                for (i in 1..steps) {
                    if (Thread.currentThread().isInterrupted) return
                    val t = i.toFloat() / steps
                    val h = ColorExtractor.interpolateHue(startH, targetH, t)
                    val s = (startS + (targetS - startS) * t).roundToInt()
                    val v = (startV + (targetV - startV) * t).roundToInt()
                    device.setColor(h, s, v)
                    if (i < steps) Thread.sleep(stepDelayMs)
                }
                Log.d(TAG, "Transition complete → H=$targetH S=$targetS V=$targetV")
            }

            currentH = targetH
            currentS = targetS
            currentV = targetV
        } finally {
            device.disconnect()
        }
    }

    // ── UI broadcast helpers ──────────────────────────────────────────────────

    private fun broadcastStatus(status: String) {
        (applicationContext as App).onStatusChanged?.let { cb ->
            mainHandler.post { cb(status) }
        }
    }

    private fun broadcastTrack(track: String, colorHex: String) {
        val app = applicationContext as App
        mainHandler.post {
            app.onTrackChanged?.invoke(
                track.substringBefore(" — "),
                track.substringAfter(" — ", ""),
                colorHex
            )
        }
    }
}
