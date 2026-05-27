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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.hermes.databinding.ActivityMainBinding
import com.jarvis.hermes.databinding.ActivitySettingsBinding
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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

    // Config loaded from SharedPreferences
    private var hermesBaseUrl = ""
    private var apiKey = ""

    enum class State { IDLE, LISTENING, PROCESSING }

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

        loadSettings()
        hermesApi = HermesApi(hermesBaseUrl, apiKey)
        tts = TextToSpeech(this, this)

        binding.btnStart.setOnClickListener {
            when (state) {
                State.IDLE -> startConversation()
                State.LISTENING -> stopConversation()
                State.PROCESSING -> stopConversation()
            }
        }

        binding.btnSettings.setOnClickListener {
            showSettings()
        }

        updateUi()
        checkMicPermission()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        hermesBaseUrl = prefs.getString("hermes_url", "http://YOUR_TAILSCALE_IP:8642") ?: ""
        apiKey = prefs.getString("api_key", "") ?: ""
        if (hermesBaseUrl.isBlank()) hermesBaseUrl = "http://YOUR_TAILSCALE_IP:8642"
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
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
                setContentView(binding.root)
            } else {
                Toast.makeText(this, "Hermes URL required", Toast.LENGTH_SHORT).show()
            }
        }

        settingsBinding.btnCancel.setOnClickListener {
            setContentView(binding.root)
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
                    binding.voiceLevel.progress = (rmsdB * 5 + 20).toInt().coerceIn(0, 100)
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (conversationActive && state == State.LISTENING) {
                        // On error or silence, re-start listening for continuous conversation
                        restartListeningLoop()
                    }
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    if (text.isNotBlank() && conversationActive) {
                        binding.transcriptText.append("You: $text\n")
                        sendToHermes(text)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startConversation() {
        if (speechRecognizer == null) {
            Toast.makeText(this, "Speech recognizer not ready", Toast.LENGTH_SHORT).show()
            return
        }
        if (hermesBaseUrl.isBlank() || hermesBaseUrl == "http://YOUR_TAILSCALE_IP:8642") {
            Toast.makeText(this, "Configure Hermes URL in Settings first", Toast.LENGTH_LONG).show()
            showSettings()
            return
        }

        conversationActive = true
        state = State.LISTENING
        updateUi()
        restartListeningLoop()

        // Greet user
        speakChunk("Yes?")
    }

    private fun restartListeningLoop() {
        if (!conversationActive || state == State.PROCESSING) return

        // Small delay before restarting to avoid rapid re-triggering
        scope.launch {
            delay(300)
            if (conversationActive && state != State.PROCESSING && !isSpeaking) {
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

    private fun stopConversation() {
        conversationActive = false
        speechRecognizer?.stopListening()
        state = State.IDLE
        updateUi()
        binding.voiceLevel.progress = 0
    }

    private fun sendToHermes(text: String) {
        state = State.PROCESSING
        updateUi()

        scope.launch {
            hermesApi?.sendMessageStream(text, object : HermesApi.StreamListener {
                override fun onChunk(text: String) {
                    runOnUiThread {
                        binding.transcriptText.append(text)
                        if (isTtsReady) speakChunk(text)
                    }
                }
                override fun onComplete(fullText: String) {
                    runOnUiThread {
                        binding.transcriptText.append("\n")
                        state = State.LISTENING
                        updateUi()
                        binding.voiceLevel.progress = 0
                        // Auto-resume listening for next thing user says
                        restartListeningLoop()
                    }
                }
                override fun onError(error: String) {
                    runOnUiThread {
                        binding.transcriptText.append("Error: $error\n")
                        state = State.LISTENING
                        updateUi()
                        binding.voiceLevel.progress = 0
                        restartListeningLoop()
                    }
                }
            })
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
                State.IDLE -> "Press Start to talk"
                State.LISTENING -> if (conversationActive) "Listening..." else "Listening..."
                State.PROCESSING -> "Thinking..."
            }
            binding.btnStart.text = when {
                conversationActive -> "End Conversation"
                else -> "Start Conversation"
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.UK
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { isSpeaking = true }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                }
                override fun onError(utteranceId: String?) { isSpeaking = false }
            })
            isTtsReady = true
            binding.statusText.text = "Press Start to talk"
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