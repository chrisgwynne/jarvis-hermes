package com.jarvis.hermes

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.hermes.databinding.ActivitySettingsBinding

/**
 * Settings activity for Jarvis Hermes.
 * Handles all configuration options including battery optimization,
 * Bluetooth auto-start, call screening, notification reading, and quick phrases.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val batteryOptimizationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.intent.action.BATTERY_OPTIMIZATION_STATE_CHANGED") {
                // Check if we're now exempt
                if (BatteryOptimizationHelper.isBatteryExempt(this@SettingsActivity)) {
                    BatteryOptimizationHelper.setBatteryExempt(this@SettingsActivity, true)
                    Toast.makeText(this@SettingsActivity, "Jarvis will stay alive in the background", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Handle permission results
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()
        setupUI()
        registerBatteryReceiver()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)

        // Load values into UI elements
        binding.inputHermesIp.setText(prefs.getString("hermes_ip", ""))
        binding.inputApiKey.setText(prefs.getString("api_key", ""))
        binding.sliderSilenceDelay.value = prefs.getLong("silence_delay", 1500L).toFloat()
        binding.switchOfflineStt.isChecked = prefs.getBoolean("use_offline_stt", true)
        binding.switchWakeWord.isChecked = prefs.getBoolean("wake_word_mode", false)
        binding.switchRespectDnd.isChecked = prefs.getBoolean("respect_dnd", true)
        binding.switchReadNotifications.isChecked = prefs.getBoolean("read_notifications", false)
        binding.switchBluetoothAuto.isChecked = prefs.getBoolean("bluetooth_auto_enabled", false)
        binding.switchCallScreening.isChecked = prefs.getBoolean("call_screening_enabled", true)

        val wakePhrase = prefs.getString("wake_phrase", "okay jarvis") ?: "okay jarvis"
        binding.inputWakePhrase.setText(wakePhrase)

        // Load system prompt
        val systemPrompt = prefs.getString(SystemPromptBuilder.PREFS_KEY_SYSTEM_PROMPT, SystemPromptBuilder.getDefault())
        binding.inputSystemPrompt.setText(systemPrompt)

        // Setup Bluetooth device type spinner
        setupBluetoothDeviceSpinner()
    }

    private fun setupBluetoothDeviceSpinner() {
        val deviceTypes = arrayOf("All", "Car kit", "Headphones")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceTypes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerBtDeviceType.adapter = adapter

        val currentType = BluetoothAutoManager.getDeviceTypes(this)
        val selection = when (currentType) {
            BluetoothAutoManager.DEVICE_TYPE_CAR -> 1
            BluetoothAutoManager.DEVICE_TYPE_HEADPHONES -> 2
            else -> 0
        }
        binding.spinnerBtDeviceType.setSelection(selection)
    }

    private fun setupUI() {
        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnCancel.setOnClickListener { finish() }
        binding.btnResetPrompt.setOnClickListener {
            val defaultPrompt = SystemPromptBuilder.getDefault()
            binding.inputSystemPrompt.setText(defaultPrompt)
        }

        binding.btnKeepAlive.setOnClickListener {
            BatteryOptimizationHelper.openBatteryOptimizationSettings(this, 1001)
        }

        binding.btnQuickPhrases.setOnClickListener {
            startActivity(Intent(this, QuickPhrasesActivity::class.java))
        }

        // Update silence delay label when slider changes
        binding.sliderSilenceDelay.addOnChangeListener { _, value, _ ->
            // Label updates handled in layout or we could add a TextView
        }
    }

    private fun saveSettings() {
        val ip = binding.inputHermesIp.text.toString().trim()
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

        if (ip.isBlank()) {
            Toast.makeText(this, "Tailscale IP required", Toast.LENGTH_SHORT).show()
            return
        }

        if (!ip.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) && !ip.startsWith("100.")) {
            Toast.makeText(this, "Enter a valid Tailscale IP (e.g. 100.x.x.x)", Toast.LENGTH_LONG).show()
            return
        }

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

        // Save Bluetooth device type
        val deviceTypeIndex = binding.spinnerBtDeviceType.selectedItemPosition
        val deviceType = when (deviceTypeIndex) {
            1 -> BluetoothAutoManager.DEVICE_TYPE_CAR
            2 -> BluetoothAutoManager.DEVICE_TYPE_HEADPHONES
            else -> BluetoothAutoManager.DEVICE_TYPE_ALL
        }
        BluetoothAutoManager.setDeviceTypes(this, deviceType)

        // Sync to VoiceService via broadcast
        val serviceIntent = Intent(this, service.VoiceService::class.java)
        serviceIntent.action = "SETTINGS_UPDATED"
        startService(serviceIntent)

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun registerBatteryReceiver() {
        BatteryOptimizationHelper.registerBatteryOptimizationReceiver(this, batteryOptimizationReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        BatteryOptimizationHelper.unregisterBatteryOptimizationReceiver(this, batteryOptimizationReceiver)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            // Check if exemption was granted
            if (BatteryOptimizationHelper.isBatteryExempt(this)) {
                BatteryOptimizationHelper.setBatteryExempt(this, true)
                Toast.makeText(this, "Jarvis will stay alive in the background", Toast.LENGTH_LONG).show()
            }
        }
    }
}