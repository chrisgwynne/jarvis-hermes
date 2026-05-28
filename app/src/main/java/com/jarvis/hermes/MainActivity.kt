package com.jarvis.hermes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.hermes.databinding.ActivityMainBinding
import com.jarvis.hermes.service.VoiceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var hermesIp = ""
    private var apiKey = ""
    private var conversationActive = false

    enum class ConnectionStatus { CONNECTED, DISCONNECTED, UNKNOWN }
    private var connectionStatus = ConnectionStatus.UNKNOWN

    /**
     * Permissions we request up-front. Special permissions
     * (WRITE_SETTINGS, SYSTEM_ALERT_WINDOW, NotificationListener access,
     * battery exemption) need their own settings panels and are handled
     * elsewhere.
     */
    private val basePermissions: Array<String>
        get() {
            val perms = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms += Manifest.permission.POST_NOTIFICATIONS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms += Manifest.permission.BLUETOOTH_CONNECT
            }
            return perms.toTypedArray()
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] != false
        if (!micGranted) {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
        }
    }

    private val batteryExemptLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateBatteryBanner()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()
        setupUi()
        checkPermissions()
        testConnection()
        updateBatteryBanner()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        hermesIp = prefs.getString("hermes_ip", "") ?: ""
        apiKey = prefs.getString("api_key", "") ?: ""
    }

    private fun hermesBaseUrl(): String =
        if (hermesIp.isNotBlank()) "http://$hermesIp:8642" else ""

    private fun setupUi() {
        binding.btnStart.setOnClickListener {
            val serviceIntent = Intent(this, VoiceService::class.java)
            if (conversationActive) {
                serviceIntent.action = "END"
                startService(serviceIntent)
            } else {
                serviceIntent.action = "START"
                try {
                    startForegroundService(serviceIntent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not start service: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.btnSessions.setOnClickListener { startActivity(Intent(this, SessionsActivity::class.java)) }
        binding.btnMemos.setOnClickListener { startActivity(Intent(this, MemoesActivity::class.java)) }
        binding.btnQuickPhrases.setOnClickListener { startActivity(Intent(this, QuickPhrasesActivity::class.java)) }

        binding.btnBatteryExempt.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                batteryExemptLauncher.launch(intent)
            } catch (e: Exception) {
                batteryExemptLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        updateConnectionIndicator()
    }

    private fun checkPermissions() {
        val notGranted = basePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) permissionLauncher.launch(notGranted.toTypedArray())
    }

    private fun testConnection() {
        val url = hermesBaseUrl()
        if (url.isBlank()) {
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
                        .url("$url/health")
                        .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey") }
                        .get()
                        .build()
                    client.newCall(request).execute().use { it.isSuccessful }
                } catch (_: Exception) { false }
            }
            connectionStatus = if (ok) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED
            updateConnectionIndicator()
        }
    }

    private fun updateConnectionIndicator() {
        val (color, glyph) = when (connectionStatus) {
            ConnectionStatus.CONNECTED -> "#00D4FF" to "●"
            ConnectionStatus.DISCONNECTED -> "#F85149" to "●"
            ConnectionStatus.UNKNOWN -> "#8B949E" to "○"
        }
        binding.connectionDot.setTextColor(android.graphics.Color.parseColor(color))
        binding.connectionDot.text = glyph
    }

    private fun updateBatteryBanner() {
        val isExempt = BatteryOptimizationHelper.isBatteryExempt(this)
        binding.batteryBanner.visibility = if (isExempt) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        testConnection()
        updateUi()
        updateBatteryBanner()
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
