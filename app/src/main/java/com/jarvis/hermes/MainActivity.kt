package com.jarvis.hermes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    private var hermesBaseUrl = ""
    private var apiKey = ""
    private var conversationActive = false

    enum class ConnectionStatus { CONNECTED, DISCONNECTED, UNKNOWN }
    private var connectionStatus = ConnectionStatus.UNKNOWN

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            results[Manifest.permission.POST_NOTIFICATIONS] == true
        } else true

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
        hermesBaseUrl = prefs.getString("hermes_url", "") ?: ""
        apiKey = prefs.getString("api_key", "") ?: ""
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
        if (hermesBaseUrl.isBlank()) {
            connectionStatus = ConnectionStatus.UNKNOWN
            updateConnectionIndicator()
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .build()
                    val request = Request.Builder()
                        .url("$hermesBaseUrl/health")
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

        settingsBinding.inputHermesUrl.setText(hermesBaseUrl)
        settingsBinding.inputApiKey.setText(apiKey)

        settingsBinding.btnSave.setOnClickListener {
            val url = settingsBinding.inputHermesUrl.text.toString().trim()
            val key = settingsBinding.inputApiKey.text.toString().trim()
            if (url.isNotBlank()) {
                getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
                    .edit()
                    .putString("hermes_url", url)
                    .putString("api_key", key)
                    .apply()
                hermesBaseUrl = url
                apiKey = key
                testConnection()
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
                setContentView(binding.root)
                setupUi()
            } else {
                Toast.makeText(this, "Hermes URL required", Toast.LENGTH_SHORT).show()
            }
        }

        settingsBinding.btnCancel.setOnClickListener {
            setContentView(binding.root)
            setupUi()
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
                    messageCount = obj.getInt("messageCount")
                ))
            }
        } catch (e: Exception) { /* ignore */ }

        val adapter = SessionAdapter(this, sessions.reversed())
        binding.sessionList.adapter = adapter
        binding.sessionList.setOnItemClickListener { _, _, position, _ ->
            // Show session detail or transcript
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
        // Check if service is running
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
    val messageCount: Int
)