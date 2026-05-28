package com.jarvis.hermes

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.hermes.databinding.ActivitySettingsBinding
import com.jarvis.hermes.service.VoiceService

/**
 * Settings activity for Jarvis Hermes.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        loadSettings()
        setupUI()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        binding.inputHermesIp.setText(prefs.getString("hermes_ip", ""))
        binding.inputApiKey.setText(prefs.getString("api_key", ""))
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

    private fun isValidHost(host: String): Boolean {
        if (host.isBlank()) return false
        // Accept IPv4 (any range, not just Tailscale) and a basic hostname.
        if (host.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$"""))) {
            return host.split(".").all { it.toIntOrNull() in 0..255 }
        }
        return host.matches(Regex("""^[A-Za-z0-9][A-Za-z0-9.\-]{0,253}$"""))
    }

    private fun saveSettings() {
        val ip = binding.inputHermesIp.text.toString().trim()
        if (!isValidHost(ip)) {
            binding.inputHermesIp.error = "Enter an IP or hostname"
            Toast.makeText(this, "Invalid host", Toast.LENGTH_SHORT).show()
            return
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
        prefs.edit()
            .putString("hermes_ip", ip)
            .putString("api_key", key)
            .putLong("silence_delay", silenceDelayVal)
            .putBoolean("use_offline_stt", useOffline)
            .putBoolean("wake_word_mode", wakeWord)
            .putBoolean("respect_dnd", respectDnd)
            .putBoolean("read_notifications", readNotifications)
            .putBoolean("bluetooth_auto_enabled", bluetoothAuto)
            .putBoolean("call_screening_enabled", callScreening)
            .putString("wake_phrase", wakePhraseText)
            .putString(SystemPromptBuilder.PREFS_KEY_SYSTEM_PROMPT, systemPrompt)
            .apply()

        val deviceType = when (binding.spinnerBtDeviceType.selectedItemPosition) {
            1 -> BluetoothAutoManager.DEVICE_TYPE_CAR
            2 -> BluetoothAutoManager.DEVICE_TYPE_HEADPHONES
            else -> BluetoothAutoManager.DEVICE_TYPE_ALL
        }
        BluetoothAutoManager.setDeviceTypes(this, deviceType)

        // Notify VoiceService to reload its config (no-op if not running).
        val svc = Intent(this, VoiceService::class.java).apply { action = "SETTINGS_UPDATED" }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startService(svc) // safe even though Foreground rules
            else startService(svc)
        } catch (_: Exception) {}

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
