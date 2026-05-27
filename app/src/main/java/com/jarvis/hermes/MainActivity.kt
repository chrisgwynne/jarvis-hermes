package com.jarvis.hermes

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.hermes.databinding.ActivityMainBinding
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

    private val batteryOptimizationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.intent.action.BATTERY_OPTIMIZATION_STATE_CHANGED") {
                if (BatteryOptimizationHelper.isBatteryExempt(this@MainActivity)) {
                    BatteryOptimizationHelper.setBatteryExempt(this@MainActivity, true)
                    updateBatteryBanner()
                    Toast.makeText(this@MainActivity, "Jarvis will stay alive in the background", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

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
        registerBatteryReceiver()
        updateBatteryBanner()
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
        binding.btnMemos.setOnClickListener { showMemos() }
        binding.btnQuickPhrases.setOnClickListener { showQuickPhrases() }

        binding.btnBatteryExempt.setOnClickListener {
            BatteryOptimizationHelper.openBatteryOptimizationSettings(this, 1001)
        }

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
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun showSessions() {
        startActivity(Intent(this, SessionsActivity::class.java))
    }

    private fun showMemos() {
        startActivity(Intent(this, MemoesActivity::class.java))
    }

    private fun showQuickPhrases() {
        startActivity(Intent(this, QuickPhrasesActivity::class.java))
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

    private fun registerBatteryReceiver() {
        val filter = IntentFilter("android.intent.action.BATTERY_OPTIMIZATION_STATE_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryOptimizationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryOptimizationReceiver, filter)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        try {
            unregisterReceiver(batteryOptimizationReceiver)
        } catch (e: Exception) {
            // Not registered
        }
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            if (BatteryOptimizationHelper.isBatteryExempt(this)) {
                BatteryOptimizationHelper.setBatteryExempt(this, true)
                updateBatteryBanner()
                Toast.makeText(this, "Jarvis will stay alive in the background", Toast.LENGTH_LONG).show()
            }
        }
    }
}