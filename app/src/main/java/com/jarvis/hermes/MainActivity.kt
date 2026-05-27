package com.jarvis.hermes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.hermes.databinding.ActivityMainBinding
import com.jarvis.hermes.databinding.ActivitySettingsBinding
import com.jarvis.hermes.databinding.ActivitySessionBinding
import com.jarvis.hermes.service.VoiceService
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var hermesIp = ""
    private var apiKey = ""
    private var conversationActive = false

    enum class ConnectionStatus { CONNECTED, DISCONNECTED, UNKNOWN }
    private var connectionStatus = ConnectionStatus.UNKNOWN

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true
        if (!micGranted) {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()
        setupUi()
        checkPermissions()
        testConnection()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        hermesIp = prefs.getString("hermes_ip", "") ?: ""
        apiKey = prefs.getString("api_key", "") ?: ""
    }

    private fun hermesBaseUrl(): String {
        return if (hermesIp.isNotBlank()) "http://$hermesIp:8642" else ""
    }

    private fun setupUi() {
        binding.btnStart.setOnClickListener {
            val serviceIntent = Intent(this, VoiceService::class.java)
            if (conversationActive) {
                serviceIntent.action = "END"
                startService(serviceIntent)
            } else {
                serviceIntent.action = "START"
                startForegroundService(serviceIntent)
            }
        }

        binding.btnSettings.setOnClickListener { showSettings() }
        binding.btnSessions.setOnClickListener { showSessions() }

        updateConnectionIndicator()
    }

    private fun checkPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun testConnection() {
        val url = hermesBaseUrl()
        if (url.isBlank()) {
            connectionStatus = ConnectionStatus.UNKNOWN
            updateConnectionIndicator()
            return
        }

        // Check SharedPreferences for connection state first
        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        val savedState = prefs.getString("connection_state", "")
        if (savedState == "reconnecting") {
            connectionStatus = ConnectionStatus.DISCONNECTED
            updateConnectionIndicator()
        }

        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .build()
                    val request = Request.Builder()
                        .url("$url/health")
                        .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey") }
                        .get()
                        .build()
                    client.newCall(request).execute().isSuccessful
                } catch (e: Exception) { false }
            }
            connectionStatus = if (ok) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED
            updateConnectionIndicator()
        }
    }

    private fun updateConnectionIndicator() {
        val (color, text) = when (connectionStatus) {
            ConnectionStatus.CONNECTED -> "#00D4FF" to "●"
            ConnectionStatus.DISCONNECTED -> "#F85149" to "●"
            ConnectionStatus.UNKNOWN -> "#8B949E" to "○"
        }
        runOnUiThread {
            binding.connectionDot.setTextColor(android.graphics.Color.parseColor(color))
            binding.connectionDot.text = text
        }
    }

    private fun showSettings() {
        val settingsBinding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(settingsBinding.root)

        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)

        // Load current values
        settingsBinding.inputHermesIp.setText(hermesIp)
        settingsBinding.inputApiKey.setText(apiKey)
        settingsBinding.sliderSilenceDelay.value = prefs.getLong("silence_delay", 1500L).toFloat()
        settingsBinding.switchOfflineStt.isChecked = prefs.getBoolean("use_offline_stt", true)
        settingsBinding.switchWakeWord.isChecked = prefs.getBoolean("wake_word_mode", false)
        settingsBinding.switchRespectDnd.isChecked = prefs.getBoolean("respect_dnd", true)

        val wakePhrase = prefs.getString("wake_phrase", "okay jarvis") ?: "okay jarvis"
        settingsBinding.inputWakePhrase.setText(wakePhrase)

        // Update silence delay label
        settingsBinding.sliderSilenceDelay.addOnChangeListener { _, value, _ ->
            // Note: Slider doesn't have direct text, would need a separate TextView
            // The layout has the text as a static label showing default
        }

        settingsBinding.btnSave.setOnClickListener {
            val ip = settingsBinding.inputHermesIp.text.toString().trim()
            val key = settingsBinding.inputApiKey.text.toString().trim()
            val silenceDelayVal = settingsBinding.sliderSilenceDelay.value.toLong()
            val useOffline = settingsBinding.switchOfflineStt.isChecked
            val wakeWord = settingsBinding.switchWakeWord.isChecked
            val respectDnd = settingsBinding.switchRespectDnd.isChecked
            val wakePhraseText = settingsBinding.inputWakePhrase.text.toString().trim().ifBlank { "okay jarvis" }

            if (ip.isBlank()) {
                Toast.makeText(this, "Tailscale IP required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!ip.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) && !ip.startsWith("100.")) {
                Toast.makeText(this, "Enter a valid Tailscale IP (e.g. 100.x.x.x)", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("hermes_ip", ip)
                .putString("api_key", key)
                .putLong("silence_delay", silenceDelayVal)
                .putBoolean("use_offline_stt", useOffline)
                .putBoolean("wake_word_mode", wakeWord)
                .putBoolean("respect_dnd", respectDnd)
                .putString("wake_phrase", wakePhraseText)
                .apply()

            hermesIp = ip
            apiKey = key
            testConnection()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            setContentView(binding.root)
            setupUi()
        }

        settingsBinding.btnCancel.setOnClickListener {
            setContentView(binding.root)
            setupUi()
        }

        settingsBinding.btnResetPrompt.setOnClickListener {
            val defaultPrompt = SystemPromptBuilder.getDefault()
            settingsBinding.inputSystemPrompt.setText(defaultPrompt)
            prefs.edit().putString(SystemPromptBuilder.PREFS_KEY_SYSTEM_PROMPT, defaultPrompt).apply()
        }
    }

    private fun showSessions() {
        val sessionBinding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(sessionBinding.root)
        loadSessions(sessionBinding)
    }

    private fun loadSessions(binding: ActivitySessionBinding) {
        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        val sessionsJson = prefs.getString("sessions", "[]") ?: "[]"
        val sessions = mutableListOf<Session>()
        try {
            val array = org.json.JSONArray(sessionsJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                sessions.add(Session(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    preview = obj.getString("preview"),
                    timestamp = obj.getLong("timestamp"),
                    messageCount = obj.getInt("messageCount"),
                    transcript = obj.getString("transcript")
                ))
            }
        } catch (e: Exception) { /* ignore */ }

        val adapter = SessionAdapter(this, sessions.reversed())
        binding.sessionList.adapter = adapter
        binding.sessionList.setOnItemClickListener { _, _, position, _ ->
            val session = sessions.reversed()[position]
            Toast.makeText(this, session.preview.take(100), Toast.LENGTH_SHORT).show()
        }

        binding.btnBack.setOnClickListener {
            setContentView(binding.root)
            setupUi()
        }

        binding.btnClearSessions.setOnClickListener {
            prefs.edit().putString("sessions", "[]").apply()
            loadSessions(binding)
        }
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        testConnection()
        updateUi()
    }

    private fun updateUi() {
        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        conversationActive = prefs.getBoolean("conversation_active", false)

        binding.statusText.text = if (conversationActive) "Conversation active" else "Press Start"
        binding.btnStart.text = if (conversationActive) "End" else "Start"
        updateConnectionIndicator()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

data class Session(
    val id: String,
    val title: String,
    val preview: String,
    val timestamp: Long,
    val messageCount: Int,
    val transcript: String = ""
)