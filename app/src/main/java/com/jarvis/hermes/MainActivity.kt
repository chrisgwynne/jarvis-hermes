package com.jarvis.hermes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
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
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var hermesApi: HermesApi? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isListening = false
    private var isSpeaking = false
    private var lastRecognizedText = ""

    // Config — change these to point at your Hermes instance
    private val hermesBaseUrl = "http://YOUR_HERMES_IP:8642"
    private val apiKey = "YOUR_API_KEY" // Optional, set in Hermes API server config

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

        binding.btnPushToTalk.setOnClickListener {
            if (!isListening && !isSpeaking) startListening()
        }

        binding.btnPushToTalk.setOnLongClickListener {
            stopListening()
            true
        }

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
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    updateStatus("Listening...")
                    binding.btnPushToTalk.text = "Listening"
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    binding.voiceLevel.progress = (rmsdB * 5 + 20).toInt().coerceIn(0, 100)
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                    binding.btnPushToTalk.text = "Push to Talk"
                }
                override fun onError(error: Int) {
                    isListening = false
                    binding.btnPushToTalk.text = "Push to Talk"
                    binding.voiceLevel.progress = 0
                    if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                        error != SpeechRecognizer.ERROR_SILENCE_TIMEOUT) {
                        updateStatus("Error: $error")
                    }
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    lastRecognizedText = matches?.firstOrNull() ?: ""
                    if (lastRecognizedText.isNotBlank()) {
                        binding.transcriptText.append("You: $lastRecognizedText\n")
                        sendToHermes(lastRecognizedText)
                    }
                    binding.voiceLevel.progress = 0
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!partial.isNullOrBlank()) updateStatus("Heard: $partial")
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        if (speechRecognizer == null || isSpeaking) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
        binding.btnPushToTalk.text = "Push to Talk"
        binding.voiceLevel.progress = 0
    }

    private fun sendToHermes(text: String) {
        scope.launch {
            updateStatus("Thinking...")
            val response = hermesApi?.sendMessage(text)
            if (response != null) {
                binding.transcriptText.append("Jarvis: $response\n")
                speak(response)
            } else {
                updateStatus("Error: No response from Hermes")
            }
        }
    }

    private fun speak(text: String) {
        isSpeaking = true
        binding.btnPushToTalk.isEnabled = false
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_utterance")
    }

    private fun updateStatus(msg: String) {
        runOnUiThread { binding.statusText.text = msg }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.UK
            updateStatus("Ready")
        } else {
            updateStatus("TTS init failed")
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }
}
