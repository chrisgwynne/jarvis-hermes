package com.jarvis.hermes.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.jarvis.hermes.MainActivity
import com.jarvis.hermes.HermesApi
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class VoiceService : Service(), TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var hermesApi: HermesApi? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    private var state = State.IDLE
    private var isSpeaking = false
    private var isTtsReady = false
    private var conversationActive = false
    private var isPaused = false

    // Session tracking
    private val userMessages = mutableListOf<String>()
    private val jarvisMessages = mutableListOf<String>()
    private val jarvisBuilder = StringBuilder()

    // Config
    private var hermesBaseUrl = ""
    private var apiKey = ""

    private val serviceChannelId = "jarvis_hermes_service"
    private val notificationId = 1

    enum class State { IDLE, LISTENING, PROCESSING, PAUSED }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        hermesBaseUrl = prefs.getString("hermes_url", "") ?: ""
        apiKey = prefs.getString("api_key", "") ?: ""

        hermesApi = HermesApi(hermesBaseUrl, apiKey)
        tts = TextToSpeech(this, this)
        initSpeechRecognizer()

        acquireWakeLock()
        createNotificationChannel()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JarvisHermes::VoiceWakeLock").apply {
            acquire(60 * 60 * 1000L) // 1 hour max
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            serviceChannelId,
            "Jarvis Hermes",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Voice assistant running in background"
            setShowBadge(false)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceService::class.java).apply { action = "END" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, VoiceService::class.java).apply { action = if (isPaused) "RESUME" else "PAUSE" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseLabel = if (isPaused) "Resume" else "Mic Off"
        val pauseAction = if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause

        return NotificationCompat.Builder(this, serviceChannelId)
            .setContentTitle("Jarvis Hermes")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(pauseAction, pauseLabel, pauseIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End", endIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startConversation()
            "END" -> endConversation()
            "PAUSE" -> pauseListening()
            "RESUME" -> resumeListening()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_LONG).show()
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
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
        when {
            conversationActive && isPaused && lower == "mic on" -> { resumeListening(); return }
            conversationActive && !isPaused && lower == "mic off" -> { pauseListening(); return }
            lower == "end conversation" -> { endConversation(); return }
            conversationActive && !isPaused -> {
                userMessages.add(text)
                jarvisBuilder.clear()
                sendToHermes(text)
            }
        }
    }

    private fun startConversation() {
        if (speechRecognizer == null) {
            Toast.makeText(this, "Speech recognizer not ready", Toast.LENGTH_SHORT).show()
            return
        }

        userMessages.clear()
        jarvisMessages.clear()
        jarvisBuilder.clear()

        getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
            .edit().putBoolean("conversation_active", true).apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, buildNotification("Starting..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(notificationId, buildNotification("Starting..."))
        }

        conversationActive = true
        isPaused = false
        state = State.LISTENING
        speak("Yes?", sync = true)
        restartListeningLoop()
        updateNotification("Listening...")
    }

    private fun pauseListening() {
        isPaused = true
        state = State.PAUSED
        speechRecognizer?.stopListening()
        speak("Mic off. Say mic on to resume.", sync = true)
        updateNotification("Paused — say mic on")
    }

    private fun resumeListening() {
        isPaused = false
        state = State.LISTENING
        speak("Mic on.", sync = true)
        updateNotification("Listening...")
        restartListeningLoop()
    }

    private fun endConversation() {
        conversationActive = false
        isPaused = false
        speechRecognizer?.stopListening()
        state = State.IDLE
        updateNotification("Ended")

        if (userMessages.isNotEmpty()) {
            saveSession()
        }

        getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
            .edit().putBoolean("conversation_active", false).apply()

        scope.launch {
            delay(1000)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun restartListeningLoop() {
        if (!conversationActive || isPaused || state == State.PROCESSING || isSpeaking) return
        scope.launch {
            delay(300)
            if (conversationActive && !isPaused && state != State.PROCESSING) {
                state = State.LISTENING
                updateNotification("Listening...")
                startListeningOnce()
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

    private fun sendToHermes(text: String) {
        state = State.PROCESSING
        updateNotification("Thinking...")
        jarvisBuilder.clear()

        scope.launch {
            hermesApi?.sendMessageStream(text, object : HermesApi.StreamListener {
                override fun onChunk(text: String) {
                    jarvisBuilder.append(text)
                    if (isTtsReady) speak(text, sync = false)
                }
                override fun onComplete(fullText: String) {
                    jarvisMessages.add(jarvisBuilder.toString())
                    state = if (isPaused) State.PAUSED else State.LISTENING
                    updateNotification(if (isPaused) "Paused" else "Listening...")
                    if (!isPaused) restartListeningLoop()
                }
                override fun onError(error: String) {
                    jarvisMessages.add("[Error: $error]")
                    speak("Connection error.", sync = true)
                    state = if (isPaused) State.PAUSED else State.LISTENING
                    updateNotification("Listening...")
                    if (!isPaused) restartListeningLoop()
                }
            })
        }
    }

    private fun speak(text: String, sync: Boolean = false) {
        if (!isTtsReady || text.isBlank()) return
        isSpeaking = true
        val id = "utterance_${UUID.randomUUID()}"
        if (sync) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            Thread.sleep(text.length * 80L)
            isSpeaking = false
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    private fun saveSession() {
        if (userMessages.isEmpty()) return

        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)

        // Build transcript
        val lines = mutableListOf<String>()
        val count = minOf(userMessages.size, jarvisMessages.size)
        for (i in 0 until count) {
            lines.add("You: ${userMessages[i]}")
            lines.add("Jarvis: ${jarvisMessages[i]}")
        }

        val transcript = lines.joinToString("\n")
        val preview = lines.takeLast(4).joinToString("\n").take(200)
        val title = userMessages.first().take(40)

        val session = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("title", title)
            put("preview", preview)
            put("transcript", transcript)
            put("timestamp", System.currentTimeMillis())
            put("messageCount", count * 2)
        }

        // Load existing sessions
        val sessionsJson = prefs.getString("sessions", "[]") ?: "[]"
        val sessions = JSONArray()
        try {
            val existing = JSONArray(sessionsJson)
            for (i in 0 until existing.length()) sessions.put(existing.getJSONObject(i))
        } catch (e: Exception) { /* ignore */ }

        sessions.put(0, session)

        // Keep max 50 sessions
        val trimmed = JSONArray()
        for (i in 0 until minOf(sessions.length(), 50)) trimmed.put(sessions.get(i))
        prefs.edit().putString("sessions", trimmed.toString()).apply()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.UK
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { isSpeaking = true }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    if (state == State.PAUSED) updateNotification("Paused — say mic on")
                }
                override fun onError(utteranceId: String?) { isSpeaking = false }
            })
            isTtsReady = true
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.shutdown()
        wakeLock?.release()
        scope.cancel()
        super.onDestroy()
    }
}