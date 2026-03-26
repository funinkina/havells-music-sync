package com.example.albumlight

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.albumlight.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        populateFields()
        attachFieldListeners()
        attachButtonListeners()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshNlsBanner()
        refreshServiceButtons()

        // Attach live-update callbacks from the running service
        (application as App).onStatusChanged = { status ->
            binding.tvStatus.text = status
        }
        (application as App).onTrackChanged = { title, artist, colorHex ->
            binding.tvCurrentTrack.text = if (artist.isNotBlank()) "$title\n$artist" else title
            try {
                binding.colorSwatch.setBackgroundColor(Color.parseColor(colorHex))
                binding.colorSwatch.visibility = View.VISIBLE
            } catch (_: Exception) {}
        }
    }

    override fun onPause() {
        super.onPause()
        // Detach callbacks so the service doesn't try to update a dead Activity
        (application as App).onStatusChanged = null
        (application as App).onTrackChanged  = null
    }

    // ── Field population ──────────────────────────────────────────────────────

    private fun populateFields() {
        binding.etDeviceIp.setText(prefs.deviceIp)
        binding.etDeviceId.setText(prefs.deviceId)
        binding.etLocalKey.setText(prefs.deviceLocalKey)
        binding.etVersion.setText(prefs.deviceVersion)
        binding.etBrightness.setText(prefs.brightnessFixed.toString())
        binding.etMinSaturation.setText(prefs.minSaturation.toString())
        binding.etTransitionSecs.setText(prefs.colorTransitionSeconds.toString())
        binding.etTransitionSteps.setText(prefs.colorTransitionSteps.toString())
    }

    // ── Auto-save on text change ──────────────────────────────────────────────

    private fun attachFieldListeners() {
        binding.etDeviceIp.onChanged       { prefs.deviceIp           = it }
        binding.etDeviceId.onChanged       { prefs.deviceId           = it }
        binding.etLocalKey.onChanged       { prefs.deviceLocalKey     = it }
        binding.etVersion.onChanged        { prefs.deviceVersion      = it }
        binding.etBrightness.onChanged     { it.toIntOrNull()?.let    { v -> prefs.brightnessFixed         = v } }
        binding.etMinSaturation.onChanged  { it.toIntOrNull()?.let    { v -> prefs.minSaturation           = v } }
        binding.etTransitionSecs.onChanged { it.toFloatOrNull()?.let  { v -> prefs.colorTransitionSeconds  = v } }
        binding.etTransitionSteps.onChanged{ it.toIntOrNull()?.let    { v -> prefs.colorTransitionSteps    = v } }
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun attachButtonListeners() {
        binding.btnGrantAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.btnStart.setOnClickListener {
            if (!isNotificationListenerEnabled()) {
                Toast.makeText(this, "Grant Notification Access first", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                return@setOnClickListener
            }
            if (prefs.deviceIp.isBlank() || prefs.deviceId.isBlank() || prefs.deviceLocalKey.isBlank()) {
                Toast.makeText(this, "Fill in Device IP, ID, and Local Key first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            ContextCompat.startForegroundService(this, LightSyncService.startIntent(this))
            refreshServiceButtons()
            binding.tvStatus.text = "Starting…"
        }

        binding.btnStop.setOnClickListener {
            startService(LightSyncService.stopIntent(this))
            refreshServiceButtons()
            binding.tvStatus.text = "Stopped"
            binding.colorSwatch.visibility = View.INVISIBLE
        }
    }

    // ── NLS / permission helpers ──────────────────────────────────────────────

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val component = ComponentName(this, AlbumLightNLS::class.java).flattenToString()
        return flat?.contains(component) == true
    }

    private fun refreshNlsBanner() {
        if (isNotificationListenerEnabled()) {
            binding.bannerNlsRequired.visibility = View.GONE
        } else {
            binding.bannerNlsRequired.visibility = View.VISIBLE
        }
    }

    private fun refreshServiceButtons() {
        val enabled = prefs.serviceEnabled
        binding.btnStart.isEnabled = !enabled
        binding.btnStop.isEnabled  = enabled
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42)
        }
    }

    // ── TextWatcher convenience extension ────────────────────────────────────

    private fun android.widget.EditText.onChanged(block: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun afterTextChanged(s: Editable?) { block(s?.toString() ?: "") }
        })
    }
}
