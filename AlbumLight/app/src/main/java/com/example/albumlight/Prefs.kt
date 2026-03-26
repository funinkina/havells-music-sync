package com.example.albumlight

import android.content.Context
import android.content.SharedPreferences

/**
 * Typed wrapper around SharedPreferences.
 * All settings visible in the config screen are stored here.
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("albumlight_prefs", Context.MODE_PRIVATE)

    // ── Device ──────────────────────────────────────────────────────────────

    var deviceIp: String
        get() = prefs.getString(KEY_DEVICE_IP, "192.168.0.104") ?: "192.168.0.104"
        set(v) = prefs.edit().putString(KEY_DEVICE_IP, v).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_DEVICE_ID, v).apply()

    var deviceLocalKey: String
        get() = prefs.getString(KEY_DEVICE_LOCAL_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_DEVICE_LOCAL_KEY, v).apply()

    /** Protocol version as a string, e.g. "3.3", "3.4", "3.5" */
    var deviceVersion: String
        get() = prefs.getString(KEY_DEVICE_VERSION, "3.5") ?: "3.5"
        set(v) = prefs.edit().putString(KEY_DEVICE_VERSION, v).apply()

    val deviceVersionDouble: Double
        get() = deviceVersion.toDoubleOrNull() ?: 3.3

    // ── Light ────────────────────────────────────────────────────────────────

    /** Fixed brightness value sent to bulb (10–1000) */
    var brightnessFixed: Int
        get() = prefs.getInt(KEY_BRIGHTNESS_FIXED, 900)
        set(v) = prefs.edit().putInt(KEY_BRIGHTNESS_FIXED, v.coerceIn(10, 1000)).apply()

    /** Minimum saturation value (0–1000). Keeps colors vivid even on low-sat art. */
    var minSaturation: Int
        get() = prefs.getInt(KEY_MIN_SATURATION, 180)
        set(v) = prefs.edit().putInt(KEY_MIN_SATURATION, v.coerceIn(0, 1000)).apply()

    // ── Transition ───────────────────────────────────────────────────────────

    /** Total time (seconds) spent transitioning to the new color */
    var colorTransitionSeconds: Float
        get() = prefs.getFloat(KEY_TRANSITION_SECS, 1.6f)
        set(v) = prefs.edit().putFloat(KEY_TRANSITION_SECS, v.coerceAtLeast(0f)).apply()

    /** Number of intermediate steps in the transition */
    var colorTransitionSteps: Int
        get() = prefs.getInt(KEY_TRANSITION_STEPS, 4)
        set(v) = prefs.edit().putInt(KEY_TRANSITION_STEPS, v.coerceIn(1, 20)).apply()

    // ── Service state ────────────────────────────────────────────────────────

    /** Whether the user has explicitly started the service */
    var serviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, v).apply()

    companion object {
        private const val KEY_DEVICE_IP          = "device_ip"
        private const val KEY_DEVICE_ID          = "device_id"
        private const val KEY_DEVICE_LOCAL_KEY   = "device_local_key"
        private const val KEY_DEVICE_VERSION     = "device_version"
        private const val KEY_BRIGHTNESS_FIXED   = "brightness_fixed"
        private const val KEY_MIN_SATURATION     = "min_saturation"
        private const val KEY_TRANSITION_SECS    = "transition_secs"
        private const val KEY_TRANSITION_STEPS   = "transition_steps"
        private const val KEY_SERVICE_ENABLED    = "service_enabled"
    }
}
