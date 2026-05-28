package com.jarvis.hermes

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.hermes.databinding.ActivitySettingsBinding
import com.jarvis.hermes.service.VoiceService
import java.util.Locale

/**
 * Settings activity for Jarvis Hermes.
 *
 * API key + sensitive prefs are stored in EncryptedSharedPreferences and
 * migrated on first run.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var languageSpinner: Spinner
    private lateinit var languageLabels: List<Pair<String, Locale>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EncryptedPrefs.migrateFromPlain(this, "jarvis_hermes", listOf("api_key"))
        injectExtras()
        loadSettings()
        setupUI()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        val secure = EncryptedPrefs.get(this)
        val storedUrl = prefs.getString("hermes_ip", "") ?: ""
        val displayUrl = when {
            storedUrl.isBlank() -> ""
            storedUrl.startsWith("http://") || storedUrl.startsWith("https://") -> storedUrl
            else -> "https://$storedUrl"
        }
        binding.inputHermesIp.setText(displayUrl)
        binding.inputApiKey.setText(secure.getString("api_key", ""))
        binding.sliderSilenceDelay.value = prefs.getLong("silence_delay", 1500L).toFloat()
        binding.switchOfflineStt.isChecked = prefs.getBoolean("use_offline_stt", true)
        binding.switchWakeWord.isChecked = prefs.getBoolean("wake_word_mode", false)
        binding.switchRespectDnd.isChecked = prefs.getBoolean("respect_dnd", true)
        binding.switchReadNotifications.isChecked = prefs.getBoolean("read_notifications", false)
        binding.switchBluetoothAuto.isChecked = prefs.getBoolean("bluetooth_auto_enabled", false)
        binding.switchCallScreening.isChecked = prefs.getBoolean("call_screening_enabled", true)

        binding.inputWakePhrase.setText(prefs.getString("wake_phrase", "okay jarvis") ?: "okay jarvis")
        binding.inputSystemPrompt.setText(
            prefs.getString(SystemPromptBuilder.PREFS_KEY_SYSTEM_PROMPT, SystemPromptBuilder.getDefault())
        )
        setupBluetoothDeviceSpinner()
        applySavedLanguage()
    }

    private fun setupBluetoothDeviceSpinner() {
        val deviceTypes = arrayOf("All", "Car kit", "Headphones")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceTypes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerBtDeviceType.adapter = adapter
        binding.spinnerBtDeviceType.setSelection(
            when (BluetoothAutoManager.getDeviceTypes(this)) {
                BluetoothAutoManager.DEVICE_TYPE_CAR -> 1
                BluetoothAutoManager.DEVICE_TYPE_HEADPHONES -> 2
                else -> 0
            }
        )
    }

    /**
     * The XML layout is fixed — append the extras (language picker,
     * driving mode toggle, metrics link, onboarding link) programmatically
     * so we don't have to touch the resource layout.
     */
    private fun injectExtras() {
        val scroll = binding.root as android.view.ViewGroup
        val container = scroll.getChildAt(0) as LinearLayout

        // ---- TTS language ----
        container.addView(sectionLabel("TTS Language"))
        val langRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        langRow.addView(rowLabel("Voice"))
        languageLabels = listOf(
            "System default" to Locale.getDefault(),
            "English (UK)" to Locale.UK,
            "English (US)" to Locale.US,
            "English (Australia)" to Locale("en", "AU"),
            "English (India)" to Locale("en", "IN"),
            "German" to Locale.GERMAN,
            "French" to Locale.FRENCH,
            "Spanish" to Locale("es", "ES"),
            "Italian" to Locale.ITALIAN,
            "Dutch" to Locale("nl", "NL")
        )
        languageSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity,
                android.R.layout.simple_spinner_item,
                languageLabels.map { it.first }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        langRow.addView(languageSpinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        container.addView(langRow)

        // ---- Driving mode (forced) ----
        container.addView(sectionLabel("Driving Mode"))
        val drivingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        drivingRow.addView(rowLabel("Force driving mode"))
        val drivingSwitch = com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            isChecked = DrivingModeManager.isActive(this@SettingsActivity)
        }
        drivingRow.addView(drivingSwitch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        container.addView(drivingRow)
        drivingSwitch.setOnCheckedChangeListener { _, checked ->
            DrivingModeManager.setForced(this, checked)
        }

        // ---- Metrics + Onboarding links ----
        container.addView(sectionLabel("Diagnostics"))
        val metricsBtn = android.widget.Button(this).apply {
            text = "Show metrics"
            setOnClickListener { startActivity(Intent(this@SettingsActivity, MetricsActivity::class.java)) }
        }
        container.addView(metricsBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 12 })

        val onboardingBtn = android.widget.Button(this).apply {
            text = "Replay onboarding"
            setOnClickListener { startActivity(Intent(this@SettingsActivity, OnboardingActivity::class.java)) }
        }
        container.addView(onboardingBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 12 })
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xFF8B949E.toInt())
        textSize = 13f
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 24; bottomMargin = 8 }
        layoutParams = lp
    }

    private fun rowLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xFFE6EDF3.toInt())
        textSize = 14f
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        layoutParams = lp
    }

    private fun applySavedLanguage() {
        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        val saved = prefs.getString("tts_locale", "default")
        val idx = languageLabels.indexOfFirst {
            when (saved) {
                "default" -> it.first == "System default"
                else -> it.second.toLanguageTag() == saved
            }
        }.coerceAtLeast(0)
        languageSpinner.setSelection(idx)
    }

    private fun setupUI() {
        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnCancel.setOnClickListener { finish() }
        binding.btnResetPrompt.setOnClickListener {
            binding.inputSystemPrompt.setText(SystemPromptBuilder.getDefault())
        }
        binding.btnKeepAlive.setOnClickListener {
            BatteryOptimizationHelper.openBatteryOptimizationSettings(this, 1001)
        }
        binding.btnQuickPhrases.setOnClickListener {
            startActivity(Intent(this, QuickPhrasesActivity::class.java))
        }
    }

    private fun isValidBaseUrl(url: String): Boolean {
        if (url.isBlank()) return true
        return try {
            val parsed = java.net.URL(url)
            parsed.protocol in listOf("http", "https") && parsed.host.isNotBlank()
        } catch (_: Exception) { false }
    }

    private fun saveSettings() {
        val ip = binding.inputHermesIp.text.toString().trim().trimEnd('/')
        if (!isValidBaseUrl(ip)) {
            binding.inputHermesIp.error = "Enter a full URL, e.g. https://openclaw.tail48466.ts.net"
            Toast.makeText(this, "Invalid URL — other settings saved", Toast.LENGTH_LONG).show()
            // Don't return — save everything else and just skip the URL
        }
        val key = binding.inputApiKey.text.toString().trim()
        val silenceDelayVal = binding.sliderSilenceDelay.value.toLong()
        val useOffline = binding.switchOfflineStt.isChecked
        val wakeWord = binding.switchWakeWord.isChecked
        val respectDnd = binding.switchRespectDnd.isChecked
        val readNotifications = binding.switchReadNotifications.isChecked
        val bluetoothAuto = binding.switchBluetoothAuto.isChecked
        val callScreening = binding.switchCallScreening.isChecked
        val wakePhraseText = binding.inputWakePhrase.text.toString().trim().ifBlank { "okay jarvis" }
        val systemPrompt = binding.inputSystemPrompt.text.toString().trim()

        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        prefs.edit().apply {
            if (isValidBaseUrl(ip)) putString("hermes_ip", ip)
            putLong("silence_delay", silenceDelayVal)
            putBoolean("use_offline_stt", useOffline)
            putBoolean("wake_word_mode", wakeWord)
            putBoolean("respect_dnd", respectDnd)
            putBoolean("read_notifications", readNotifications)
            putBoolean("bluetooth_auto_enabled", bluetoothAuto)
            putBoolean("call_screening_enabled", callScreening)
            putString("wake_phrase", wakePhraseText)
            putString(SystemPromptBuilder.PREFS_KEY_SYSTEM_PROMPT, systemPrompt)
            putString("tts_locale", selectedTtsLocaleTag())
            apply()
        }

        // Persist API key encrypted, never in plain prefs.
        EncryptedPrefs.get(this).edit().putString("api_key", key).apply()

        val deviceType = when (binding.spinnerBtDeviceType.selectedItemPosition) {
            1 -> BluetoothAutoManager.DEVICE_TYPE_CAR
            2 -> BluetoothAutoManager.DEVICE_TYPE_HEADPHONES
            else -> BluetoothAutoManager.DEVICE_TYPE_ALL
        }
        BluetoothAutoManager.setDeviceTypes(this, deviceType)

        val svc = Intent(this, VoiceService::class.java).apply { action = "SETTINGS_UPDATED" }
        try { startService(svc) } catch (_: Exception) {}

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun selectedTtsLocaleTag(): String {
        val idx = languageSpinner.selectedItemPosition.coerceAtLeast(0)
        val (label, locale) = languageLabels[idx]
        return if (label == "System default") "default" else locale.toLanguageTag()
    }
}
