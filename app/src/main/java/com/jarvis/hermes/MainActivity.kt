package com.jarvis.hermes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.hermes.databinding.ActivityMainBinding
import com.jarvis.hermes.databinding.ActivitySettingsBinding
import com.jarvis.hermes.databinding.ActivitySessionBinding
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsBinding: ActivitySettingsBinding
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var hermesApi: HermesApi? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var state = State.IDLE
    private var isSpeaking = false
    private var isTtsReady = false
    private var conversationActive = false
    private var isPaused = false

    // Config
    private var hermesBaseUrl = ""
    private var apiKey = ""

    enum class State { IDLE, LISTENING, PROCESSING, PAUSED }
    enum class ConnectionStatus { CONNECTED, DISCONNECTED, UNKNOWN }

    private var connectionStatus = ConnectionStatus.UNKNOWN

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) initSpeechRecognizer()
        else Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen awake during conversation
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        loadSettings()
        hermesApi = HermesApi(hermesBaseUrl, apiKey)
        tts = TextToSpeech(this, this)

        binding.btnStart.setOnClickListener {
            when {
                state == State.PAUSED -> resumeListening()
                state == State.IDLE -> startConversation()
                else -> endConversation()
            }
        }

        binding.btnSettings.setOnClickListener { showSettings() }
        binding.btnSessions.setOnClickListener { showSessions() }

        updateUi()
        checkMicPermission()
        testConnection()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        hermesBaseUrl = prefs.getString("hermes_url", "") ?: ""
        apiKey = prefs.getString("api_key", "") ?: ""
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
            runOnUiThread { updateConnectionIndicator() }
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
            val array = JSONArray(sessionsJson)
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

        binding.btnBack.setOnClickListener { setContentView(findViewById(android.R.id.content)) }
        binding.btnClearSessions.setOnClickListener {
            prefs.edit().putString("sessions", "[]").apply()
            loadSessions(binding)
        }
    }

    private fun showSettings() {
        settingsBinding = ActivitySettingsBinding.inflate(layoutInflater)
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
                hermesApi = HermesApi(hermesBaseUrl, apiKey)
                testConnection()
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
                setContentView(findViewById(android.R.id.content))
            } else {
                Toast.makeText(this, "Hermes URL required", Toast.LENGTH_SHORT).show()
            }
        }

        settingsBinding.btnCancel.setOnClickListener {
            setContentView(findViewById(android.R.id.content))
        }
    }

    private fun checkMicPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED -> initSpeechRecognizer()
            else -> requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_LONG).show()
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    if (state == State.LISTENING) {
                        binding.voiceLevel.progress = (rmsdB * 5 + 20).toInt().coerceIn(0, 100)
                    }
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (conversationActive && state == State.LISTENING && !isPaused) {
                        restartListeningLoop()
                    }
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    if (text.isNotBlank()) handleRecognizedText(text)
                    if (conversationActive && !isPaused) restartListeningLoop()
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun handleRecognizedText(text: String) {
        val lower = text.lowercase().trim()

        // Check for voice commands
        when {
            conversationActive && isPaused && lower == "mic on" -> {
                resumeListening()
                return
            }
            conversationActive && !isPaused && lower == "mic off" -> {
                pauseListening()
                return
            }
            lower == "end conversation" -> {
                endConversation()
                return
            }
            lower == "settings" -> {
                showSettings()
                return
            }
        }

        // Normal conversation
        if (conversationActive && !isPaused) {
            appendToTranscript("You: $text\n")
            sendToHermes(text)
        }
    }

    private fun pauseListening() {
        isPaused = true
        state = State.PAUSED
        speechRecognizer?.stopListening()
        binding.voiceLevel.progress = 0
        tts?.speak("Mic off. Say mic on to resume.", TextToSpeech.QUEUE_FLUSH, null, "mic_off")
        updateUi()
    }

    private fun resumeListening() {
        isPaused = false
        state = State.LISTENING
        tts?.speak("Mic on.", TextToSpeech.QUEUE_FLUSH, null, "mic_on")
        updateUi()
        restartListeningLoop()
    }

    private fun startConversation() {
        if (speechRecognizer == null) {
            Toast.makeText(this, "Speech recognizer not ready", Toast.LENGTH_SHORT).show()
            return
        }
        if (hermesBaseUrl.isBlank()) {
            Toast.makeText(this, "Configure Hermes URL in Settings first", Toast.LENGTH_LONG).show()
            showSettings()
            return
        }

        conversationActive = true
        isPaused = false
        state = State.LISTENING
        updateUi()
        tts?.speak("Yes?", TextToSpeech.QUEUE_FLUSH, null, "wake_confirm")
        restartListeningLoop()
    }

    private fun endConversation() {
        conversationActive = false
        isPaused = false
        speechRecognizer?.stopListening()
        state = State.IDLE
        saveCurrentSession()
        binding.transcriptText.text = ""
        updateUi()
        binding.voiceLevel.progress = 0
    }

    private fun restartListeningLoop() {
        if (!conversationActive || isPaused || state == State.PROCESSING || isSpeaking) return
        scope.launch {
            delay(300)
            if (conversationActive && !isPaused && state != State.PROCESSING) {
                runOnUiThread {
                    state = State.LISTENING
                    updateUi()
                    binding.voiceLevel.progress = 0
                    startListeningOnce()
                }
            }
        }
    }

    private fun startListeningOnce() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun appendToTranscript(text: String) {
        runOnUiThread {
            binding.transcriptText.append(text)
        }
    }

    private fun sendToHermes(text: String) {
        state = State.PROCESSING
        updateUi()

        scope.launch {
            hermesApi?.sendMessageStream(text, object : HermesApi.StreamListener {
                override fun onChunk(text: String) {
                    runOnUiThread {
                        appendToTranscript(text)
                        if (isTtsReady) speakChunk(text)
                    }
                }
                override fun onComplete(fullText: String) {
                    runOnUiThread {
                        appendToTranscript("\n")
                        saveCurrentSession()
                        if (!isPaused) {
                            state = State.LISTENING
                            updateUi()
                            binding.voiceLevel.progress = 0
                            restartListeningLoop()
                        }
                    }
                }
                override fun onError(error: String) {
                    runOnUiThread {
                        appendToTranscript("Error: $error\n")
                        connectionStatus = ConnectionStatus.DISCONNECTED
                        updateConnectionIndicator()
                    }
                    state = State.LISTENING
                    updateUi()
                    binding.voiceLevel.progress = 0
                    if (!isPaused) restartListeningLoop()
                }
            })
        }
    }

    private fun saveCurrentSession() {
        val transcript = binding.transcriptText.text.toString()
        if (transcript.isBlank()) return

        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        val sessionsJson = prefs.getString("sessions", "[]") ?: "[]"
        val sessions = mutableListOf<org.json.JSONObject>()

        try {
            val array = JSONArray(sessionsJson)
            for (i in 0 until array.length()) sessions.add(array.getJSONObject(i))
        } catch (e: Exception) { /* ignore */ }

        val lines = transcript.trim().split("\n")
        val preview = lines.filter { it.startsWith("You:") || it.startsWith("Jarvis:") }
            .takeLast(4)
            .joinToString("\n")
            .take(200)

        val title = lines.find { it.startsWith("You:") }?.substringAfter("You: ")?.take(40) ?: "Session"

        val session = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("title", title)
            put("preview", preview)
            put("timestamp", System.currentTimeMillis())
            put("messageCount", lines.count { it.startsWith("You:") || it.startsWith("Jarvis:") })
        }

        sessions.add(0, session)
        if (sessions.length() > 50) {
            val trimmed = JSONArray()
            for (i in 0 until 50) trimmed.put(sessions.get(i))
            prefs.edit().putString("sessions", trimmed.toString()).apply()
        } else {
            prefs.edit().putString("sessions", JSONArray(sessions).toString()).apply()
        }
    }

    private fun speakChunk(text: String) {
        if (!isTtsReady || text.isBlank()) return
        isSpeaking = true
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "chunk_${UUID.randomUUID()}")
    }

    private fun updateUi() {
        runOnUiThread {
            binding.voiceLevel.progress = 0
            binding.statusText.text = when (state) {
                State.IDLE -> "Press to talk"
                State.LISTENING -> "Listening..."
                State.PROCESSING -> "Thinking..."
                State.PAUSED -> "Paused — say mic on"
            }
            binding.btnStart.text = when {
                state == State.PAUSED -> "Resume"
                conversationActive -> "End"
                else -> "Start"
            }
            binding.btnStart.isEnabled = true
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.UK
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { isSpeaking = true }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    if (state == State.PAUSED) {
                        runOnUiThread { binding.statusText.text = "Paused — say mic on" }
                    }
                }
                override fun onError(utteranceId: String?) { isSpeaking = false }
            })
            isTtsReady = true
            binding.statusText.text = "Press to talk"
        } else {
            binding.statusText.text = "TTS init failed"
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.shutdown()
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