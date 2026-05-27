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
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var hermesApi: HermesApi? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var state = State.IDLE
    private var isSpeaking = false
    private var isTtsReady = false
    private var pendingText = ""

    // Config — change these to point at your Hermes instance
    private val hermesBaseUrl = "http://YOUR_HERMES_IP:8642"
    private val apiKey = "YOUR_API_KEY"

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

        hermesApi = HermesApi(hermesBaseUrl, apiKey)
        tts = TextToSpeech(this, this)

        binding.btnStart.setOnClickListener {
            when (state) {
                State.IDLE -> startListening()
                State.LISTENING -> stopListening()
                State.PROCESSING -> stopListening()
            }
        }

        updateUi()
        checkMicPermission()
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
                    if (state == State.LISTENING) {
                        // Don't restart on error — let user push again
                        state = State.IDLE
                        updateUi()
                    }
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    if (text.isNotBlank()) {
                        binding.transcriptText.append("You: $text\n")
                        sendToHermes(text)
                    }
                    state = State.IDLE
                    updateUi()
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        if (speechRecognizer == null) {
            Toast.makeText(this, "Speech recognizer not ready", Toast.LENGTH_SHORT).show()
            return
        }
        if (state == State.PROCESSING) {
            Toast.makeText(this, "Wait for response", Toast.LENGTH_SHORT).show()
            return
        }

        state = State.LISTENING
        updateUi()
        binding.voiceLevel.progress = 0

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        state = State.IDLE
        updateUi()
        binding.voiceLevel.progress = 0
    }

    private fun sendToHermes(text: String) {
        state = State.PROCESSING
        updateUi()
        pendingText = ""

        scope.launch {
            hermesApi?.sendMessageStream(text, object : HermesApi.StreamListener {
                override fun onChunk(text: String) {
                    pendingText += text
                    runOnUiThread {
                        binding.transcriptText.append(text)
                        if (isTtsReady && !isSpeaking) {
                            speakChunk(text)
                        }
                    }
                }
                override fun onComplete(fullText: String) {
                    runOnUiThread {
                        binding.transcriptText.append("\n")
                        state = State.IDLE
                        updateUi()
                        binding.voiceLevel.progress = 0
                    }
                }
                override fun onError(error: String) {
                    runOnUiThread {
                        binding.transcriptText.append("\nError: $error\n")
                        state = State.IDLE
                        updateUi()
                        binding.voiceLevel.progress = 0
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
                State.LISTENING -> "Listening..."
                State.PROCESSING -> "Thinking..."
            }
            binding.btnStart.text = when (state) {
                State.IDLE -> "Start"
                State.LISTENING -> "Stop"
                State.PROCESSING -> "Stop"
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
                    if (state == State.IDLE) {
                        runOnUiThread {
                            binding.statusText.text = "Press Start to talk"
                        }
                    }
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