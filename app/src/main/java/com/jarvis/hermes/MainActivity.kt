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

    private var state = State.SLEEPING
    private var isSpeaking = false
    private var isTtsReady = false
    private var pendingText = ""
    private var ttsStarted = false

    // Config — change these to point at your Hermes instance
    private val hermesBaseUrl = "http://YOUR_HERMES_IP:8642"
    private val apiKey = "YOUR_API_KEY"

    // Wake word — change to whatever you want to use
    private val wakePhrase = "okay jarvis"
    private var lastPartial = ""

    enum class State { SLEEPING, WAKE_DETECTED, LISTENING, PROCESSING, SPEAKING }

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
                    if (state == State.LISTENING) {
                        binding.voiceLevel.progress = (rmsdB * 5 + 20).toInt().coerceIn(0, 100)
                    }
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (state == State.WAKE_DETECTED || state == State.LISTENING) {
                        restartListening()
                    }
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    handleRecognizedText(text)
                    restartListening()
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    lastPartial = partial.lowercase()
                    if (state == State.SLEEPING && wakePhrase in lastPartial) {
                        runOnUiThread { wake() }
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        startListening()
    }

    private fun handleRecognizedText(text: String) {
        if (text.isBlank()) return

        when (state) {
            State.SLEEPING -> {
                if (wakePhrase in text.lowercase()) wake()
            }
            State.LISTENING -> {
                binding.transcriptText.append("You: $text\n")
                sendToHermes(text)
            }
            State.SPEAKING -> {
                // If user interrupts while Jarvis is talking
                tts?.stop()
                binding.transcriptText.append("You: $text\n")
                state = State.LISTENING
                sendToHermes(text)
            }
            State.PROCESSING -> {
                binding.transcriptText.append("You: $text\n")
                // Queue it — we'll send after current response
            }
            else -> {}
        }
    }

    private fun wake() {
        state = State.WAKE_DETECTED
        updateUi()
        tts?.speak("Yes?", TextToSpeech.QUEUE_ADD, null, "wake_confirm")
    }

    private fun sendToHermes(text: String) {
        state = State.PROCESSING
        updateUi()
        pendingText = ""

        scope.launch {
            val streamListener = object : HermesApi.StreamListener {
                override fun onChunk(text: String) {
                    pendingText += text
                    runOnUiThread {
                        binding.transcriptText.append(text)
                        // Stream to TTS immediately
                        if (isTtsReady && !isSpeaking) {
                            speakChunk(text)
                        }
                    }
                }
                override fun onComplete(fullText: String) {
                    state = State.LISTENING
                    updateUi()
                    binding.voiceLevel.progress = 0
                    restartListening()
                }
                override fun onError(error: String) {
                    runOnUiThread {
                        binding.transcriptText.append("Error: $error\n")
                    }
                    state = State.LISTENING
                    updateUi()
                    restartListening()
                }
            }

            hermesApi?.sendMessageStream(text, streamListener)
        }
    }

    private fun speakChunk(text: String) {
        if (!isTtsReady || text.isBlank()) return
        isSpeaking = true
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "chunk_${UUID.randomUUID()}")
    }

    private fun restartListening() {
        if (speechRecognizer == null || state == State.SLEEPING || state == State.SPEAKING) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun updateUi() {
        runOnUiThread {
            binding.voiceLevel.progress = 0
            binding.statusText.text = when (state) {
                State.SLEEPING -> "Say \"$wakePhrase\" to wake"
                State.WAKE_DETECTED -> "Listening..."
                State.LISTENING -> "Listening..."
                State.PROCESSING -> "Thinking..."
                State.SPEAKING -> "Speaking..."
            }
            binding.wakeIndicator.text = when (state) {
                State.SLEEPING -> "💤"
                else -> "👂"
            }
            binding.wakeIndicator.textSize = when (state) {
                State.SLEEPING -> 32f
                else -> 48f
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
                    // After TTS done, auto-resume listening if not SLEEPING
                    if (state != State.SLEEPING && !isSpeaking) {
                        runOnUiThread {
                            state = State.LISTENING
                            updateUi()
                            restartListening()
                        }
                    }
                }
                override fun onError(utteranceId: String?) { isSpeaking = false }
            })
            isTtsReady = true
            binding.statusText.text = "Say \"$wakePhrase\" to wake"
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