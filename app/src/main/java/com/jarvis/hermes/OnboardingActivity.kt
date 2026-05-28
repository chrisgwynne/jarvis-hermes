package com.jarvis.hermes

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * One-time onboarding flow.
 *
 * Walks through:
 *  1. Mic + notifications + location permissions
 *  2. Battery optimisation exemption
 *  3. Hermes URL + API key
 *  4. Optional: accessibility service for UI commands
 *  5. Optional: notification listener for in-line reply
 *  6. Optional: Vosk wake-word model
 *
 * Launched by MainActivity on first run; can be re-launched via Settings.
 */
class OnboardingActivity : AppCompatActivity() {

    private val perms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op — UI refreshes onResume */ }

    private val openSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* refresh */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(0xFF0D1117.toInt())
        }

        val title = TextView(this).apply {
            text = "Welcome to Jarvis"
            setTextColor(0xFF00D4FF.toInt())
            textSize = 28f
        }
        root.addView(title)

        // Step 1: permissions
        root.addStep(
            heading = "1. Grant core permissions",
            description = "Microphone, contacts, location, phone — Jarvis needs these to handle voice commands locally.",
            actionLabel = "Grant",
            isDone = { hasCorePermissions() }
        ) {
            val needed = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                needed += Manifest.permission.BLUETOOTH_CONNECT
            }
            perms.launch(needed.toTypedArray())
        }

        // Step 2: battery exemption
        root.addStep(
            heading = "2. Allow background operation",
            description = "Without this, Android will kill Jarvis after a few minutes in the background.",
            actionLabel = "Open",
            isDone = { BatteryOptimizationHelper.isBatteryExempt(this) }
        ) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                openSettings.launch(intent)
            } catch (_: Exception) {
                openSettings.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        // Step 3: Hermes URL
        root.addStep(
            heading = "3. Connect to Hermes",
            description = "Enter your Tailscale IP and (optional) API key in Settings.",
            actionLabel = "Settings",
            isDone = { getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
                        .getString("hermes_ip", "").orEmpty().isNotBlank() }
        ) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Step 4: accessibility service
        root.addStep(
            heading = "4. (Optional) Enable accessibility service",
            description = "Lets Jarvis perform back / home / lock / power gestures by voice.",
            actionLabel = "Enable",
            isDone = { NotificationInterceptorService.instance != null }
        ) {
            openSettings.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Step 5: notification listener
        root.addStep(
            heading = "5. (Optional) Enable notification access",
            description = "Lets Jarvis read messages and reply by voice (WhatsApp, Signal, Messages).",
            actionLabel = "Enable",
            isDone = { JarvisNotificationListener.instance != null }
        ) {
            openSettings.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // Done button
        val done = Button(this).apply {
            text = "Done"
            setOnClickListener {
                getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
                    .edit().putBoolean("onboarding_complete", true).apply()
                finish()
            }
        }
        root.addView(done, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 48 })

        setContentView(root)
    }

    private fun hasCorePermissions(): Boolean {
        val core = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        return core.all {
            ContextCompat.checkSelfPermission(this, it) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun LinearLayout.addStep(
        heading: String,
        description: String,
        actionLabel: String,
        isDone: () -> Boolean,
        onClick: () -> Unit
    ) {
        val container = LinearLayout(this@OnboardingActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 24, 16, 24)
            setBackgroundColor(0xFF161B22.toInt())
        }
        val h = TextView(this@OnboardingActivity).apply {
            text = heading
            setTextColor(0xFFE6EDF3.toInt())
            textSize = 16f
        }
        val d = TextView(this@OnboardingActivity).apply {
            text = description
            setTextColor(0xFF8B949E.toInt())
            textSize = 13f
        }
        val b = Button(this@OnboardingActivity).apply {
            text = if (isDone()) "Done ✓" else actionLabel
            isEnabled = !isDone()
            setOnClickListener { onClick() }
        }
        container.addView(h)
        container.addView(d)
        container.addView(b)
        addView(container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 24 })
    }

    override fun onResume() {
        super.onResume()
        buildUi()
    }
}
