package com.jarvis.hermes.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.jarvis.hermes.MainActivity
import com.jarvis.hermes.HermesApi
import com.jarvis.hermes.LocalCommandClassifier
import com.jarvis.hermes.LocalResponse
import com.jarvis.hermes.SystemPromptBuilder
import com.jarvis.hermes.widget.JarvisWidget
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
    private var hermesIp = ""
    private var apiKey = ""
    private var silenceDelay = 1500L
    private var wakeWordMode = false
    private var wakePhrase = "okay jarvis"
    private var useOfflineStt = true
    private var respectDnd = true

    // DND state
    private var isDndActive = false
    private val dndFilter = IntentFilter(NotificationManager.INTERRUPTION_FILTER_CHANGED_ACTION)

    // Wake word state
    private var isInWakeWordListening = false
    private var continuousRecognizer: SpeechRecognizer? = null

    private val serviceChannelId = "jarvis_hermes_service"
    private val notificationId = 1

    enum class State { IDLE, LISTENING, PROCESSING, PAUSED, WAKE_WORD }

    private val dndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            checkDndState()
        }
    }

    private fun hermesBaseUrl(): String {
        return if (hermesIp.isNotBlank()) "http://$hermesIp:8642" else ""
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        hermesIp = prefs.getString("hermes_ip", "") ?: ""
        apiKey = prefs.getString("api_key", "") ?: ""
        silenceDelay = prefs.getLong("silence_delay", 1500L)
        wakeWordMode = prefs.getBoolean("wake_word_mode", false)
        wakePhrase = prefs.getString("wake_phrase", "okay jarvis") ?: "okay jarvis"
        useOfflineStt = prefs.getBoolean("use_offline_stt", true)
        respectDnd = prefs.getBoolean("respect_dnd", true)

        hermesApi = HermesApi(hermesBaseUrl(), apiKey).apply {
            setContext(this@VoiceService)
            setPrefsListener { state ->
                prefs.edit().putString("connection_state", state).apply()
                JarvisWidget.broadcastStateUpdate(this@VoiceService)
            }
        }
        tts = TextToSpeech(this, this)
        initSpeechRecognizer()
        checkDndState()
        registerReceiver(dndReceiver, dndFilter)

        acquireWakeLock()
        createNotificationChannel()

        // Pre-warm Hermes connection if wake word mode is on
        if (wakeWordMode && hermesIp.isNotBlank()) {
            hermesApi?.initConversation(prefs.getString(SystemPromptBuilder.PREFS_KEY_SYSTEM_PROMPT, SystemPromptBuilder.getDefault()) ?: "")
        }
    }

    private fun checkDndState() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        isDndActive = nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
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
        JarvisWidget.broadcastStateUpdate(this)
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
            setRecognitionListener(createRecognitionListener())
        }
    }

    private fun createRecognitionListener(wakeWordCheck: Boolean = false): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (wakeWordCheck) {
                    // In wake word mode, restart continuous listening
                    if (isInWakeWordListening) {
                        restartWakeWordListening()
                    }
                    return
                }
                if (conversationActive && state == State.LISTENING && !isPaused) {
                    restartListeningLoop(immediate = true)
                }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    if (wakeWordCheck) {
                        handleWakeWordResult(text)
                    } else {
                        handleRecognizedText(text)
                    }
                }
                if (wakeWordCheck) {
                    restartWakeWordListening()
                } else if (conversationActive && !isPaused) {
                    restartListeningLoop(immediate = false)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (wakeWordCheck) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    if (text.isNotBlank()) {
                        checkForWakePhrase(text)
                    }
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun startWakeWordListening() {
        if (continuousRecognizer == null) {
            continuousRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(createRecognitionListener(wakeWordCheck = true))
            }
        }
        isInWakeWordListening = true
        state = State.WAKE_WORD

        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        prefs.edit().putString("widget_state", "wake_word").apply()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceDelay)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
            // Keep listening continuously
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        continuousRecognizer?.startListening(intent)
        updateNotification("Wake word mode — say \"$wakePhrase\"")
        JarvisWidget.broadcastStateUpdate(this)
    }

    private fun restartWakeWordListening() {
        if (!isInWakeWordListening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceDelay)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        continuousRecognizer?.startListening(intent)
    }

    private fun checkForWakePhrase(text: String) {
        val lower = text.lowercase().trim()
        if (lower.contains(wakePhrase.lowercase())) {
            // Wake phrase detected
            isInWakeWordListening = false
            continuousRecognizer?.stopListening()
            speakResponseSafely("Yes?")
            startConversationFromWakeWord()
        }
    }

    private fun handleWakeWordResult(text: String) {
        val lower = text.lowercase().trim()
        if (lower.contains(wakePhrase.lowercase())) {
            isInWakeWordListening = false
            continuousRecognizer?.stopListening()
            speakResponseSafely("Yes?")
            startConversationFromWakeWord()
        }
    }

    private fun startConversationFromWakeWord() {
        userMessages.clear()
        jarvisMessages.clear()
        jarvisBuilder.clear()

        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        val systemPrompt = prefs.getString(SystemPromptBuilder.PREFS_KEY_SYSTEM_PROMPT, SystemPromptBuilder.getDefault())
            ?: SystemPromptBuilder.getDefault()
        hermesApi?.initConversation(systemPrompt)

        prefs.edit().putBoolean("conversation_active", true).apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, buildNotification("Listening..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(notificationId, buildNotification("Listening..."))
        }

        conversationActive = true
        isPaused = false
        state = State.LISTENING
        JarvisWidget.broadcastStateUpdate(this)
        restartListeningLoop(immediate = true)
    }

    private fun handleRecognizedText(text: String) {
        val lower = text.lowercase().trim()
        when {
            conversationActive && isPaused && lower == "mic on" -> { resumeListening(); return }
            conversationActive && !isPaused && lower == "mic off" -> { pauseListening(); return }
            lower == "end conversation" -> {
                if (wakeWordMode) {
                    endConversation(returnToWakeWord = true)
                } else {
                    endConversation()
                }
                return
            }
            conversationActive && !isPaused -> {
                // First, try to handle locally — instant, no network
                val localResponse = LocalCommandClassifier.handle(this, text)
                if (localResponse != null) {
                    speakResponse(localResponse)
                    return
                }

                // Not a local command — send to Hermes
                userMessages.add(text)
                jarvisBuilder.clear()
                sendToHermes(text)
            }
        }
    }

    private fun speakResponse(response: LocalResponse) {
        if (response.text.isNotBlank()) {
            speak(response.text, sync = true)
        }
        state = if (isPaused) State.PAUSED else State.LISTENING
        updateNotification(if (isPaused) "Paused" else "Listening...")
        if (!isPaused) restartListeningLoop(immediate = true)
    }

    private fun startConversation() {
        if (speechRecognizer == null) {
            Toast.makeText(this, "Speech recognizer not ready", Toast.LENGTH_SHORT).show()
            return
        }

        userMessages.clear()
        jarvisMessages.clear()
        jarvisBuilder.clear()

        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        val systemPrompt = prefs.getString(SystemPromptBuilder.PREFS_KEY_SYSTEM_PROMPT, SystemPromptBuilder.getDefault())
            ?: SystemPromptBuilder.getDefault()
        hermesApi?.initConversation(systemPrompt)

        prefs.edit().putBoolean("conversation_active", true).apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, buildNotification("Starting..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(notificationId, buildNotification("Starting..."))
        }

        conversationActive = true
        isPaused = false
        state = State.LISTENING
        speak("Yes?", sync = true)
        restartListeningLoop(immediate = true)
        updateNotification("Listening...")
    }

    private fun pauseListening() {
        isPaused = true
        state = State.PAUSED
        speechRecognizer?.stopListening()
        speakResponseSafely("Mic off. Say mic on to resume.")
        updateNotification("Paused — say mic on")
    }

    private fun resumeListening() {
        isPaused = false
        state = State.LISTENING
        speakResponseSafely("Mic on.")
        updateNotification("Listening...")
        restartListeningLoop(immediate = true)
    }

    private fun endConversation(returnToWakeWord: Boolean = false) {
        conversationActive = false
        isPaused = false
        speechRecognizer?.stopListening()
        state = State.IDLE
        updateNotification("Ended")

        if (userMessages.isNotEmpty()) {
            saveSession()
        }

        hermesApi?.resetConversation()

        getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
            .edit().putBoolean("conversation_active", false).apply()

        scope.launch {
            delay(1000)
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (returnToWakeWord && wakeWordMode) {
                // Return to wake word listening instead of stopping
                startWakeWordListening()
            } else {
                stopSelf()
            }
        }
    }

    /**
     * Restart listening. When immediate=true (post-command), jump straight in.
     * When immediate=false (post-response), add small buffer so TTS finishes first.
     */
    private fun restartListeningLoop(immediate: Boolean) {
        if (!conversationActive || isPaused || state == State.PROCESSING || isSpeaking) return
        scope.launch {
            val delayMs = if (immediate) 50L else 400L
            delay(delayMs)
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceDelay)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun sendToHermes(text: String) {
        state = State.PROCESSING
        updateNotification("Thinking...")

        // System prompt already sent in initConversation
        scope.launch {
            hermesApi?.sendMessageStream(
                text,
                object : HermesApi.StreamListener {
                    override fun onChunk(text: String) {
                        jarvisBuilder.append(text)
                        if (isTtsReady) speak(text, sync = false)
                    }
                    override fun onComplete(fullText: String) {
                        hermesApi?.cacheAssistantMessage(fullText)
                        jarvisMessages.add(fullText)
                        state = if (isPaused) State.PAUSED else State.LISTENING
                        updateNotification(if (isPaused) "Paused" else "Listening...")
                        if (!isPaused) restartListeningLoop(immediate = false)
                    }
                    override fun onError(error: String) {
                        jarvisMessages.add("[Error: $error]")
                        speakResponseSafely("Connection error.")
                        state = if (isPaused) State.PAUSED else State.LISTENING
                        updateNotification("Listening...")
                        if (!isPaused) restartListeningLoop(immediate = false)
                    }
                    override fun onReconnecting() {
                        // Connection lost, will retry
                    }
                }
            )
        }
    }

    private fun speakResponseSafely(text: String) {
        if (respectDnd && isDndActive) {
            // Vibrate instead of speaking
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(200L)
            }
            // Still show in notification
            updateNotification(text)
            return
        }
        speak(text, sync = true)
    }

    private fun speak(text: String, sync: Boolean = false) {
        if (!isTtsReady || text.isBlank()) return

        if (respectDnd && isDndActive) {
            // Vibrate instead of speaking
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(200L)
            }
            return
        }

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

        val sessionsJson = prefs.getString("sessions", "[]") ?: "[]"
        val sessions = JSONArray()
        try {
            val existing = JSONArray(sessionsJson)
            for (i in 0 until existing.length()) sessions.put(existing.getJSONObject(i))
        } catch (e: Exception) { /* ignore */ }

        sessions.put(0, session)

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

            // If wake word mode is on, start listening immediately
            if (wakeWordMode && hermesIp.isNotBlank()) {
                startWakeWordListening()
            }
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(dndReceiver)
        } catch (e: Exception) { /* ignore if not registered */ }
        speechRecognizer?.destroy()
        continuousRecognizer?.destroy()
        tts?.shutdown()
        wakeLock?.release()
        scope.cancel()
        super.onDestroy()
    }
}