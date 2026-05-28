package com.jarvis.hermes.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.hermes.BluetoothAutoManager
import com.jarvis.hermes.BatteryOptimizationHelper
import com.jarvis.hermes.CallScreenHelper
import com.jarvis.hermes.HermesApi
import com.jarvis.hermes.LocalCommandClassifier
import com.jarvis.hermes.LocalResponse
import com.jarvis.hermes.MainActivity
import com.jarvis.hermes.QuickPhraseManager
import com.jarvis.hermes.SystemPromptBuilder
import com.jarvis.hermes.VoiceMemoManager
import com.jarvis.hermes.widget.JarvisWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class VoiceService : Service(), TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var continuousRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var hermesApi: HermesApi? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    private var state = State.IDLE
    @Volatile private var isSpeaking = false
    @Volatile private var isTtsReady = false
    @Volatile private var conversationActive = false
    @Volatile private var isPaused = false
    @Volatile private var isInWakeWordListening = false

    private val userMessages = mutableListOf<String>()
    private val jarvisMessages = mutableListOf<String>()
    private val jarvisBuilder = StringBuilder()

    private var hermesIp = ""
    private var apiKey = ""
    private var silenceDelay = 1500L
    private var wakeWordMode = false
    private var wakePhrase = "okay jarvis"
    private var respectDnd = true
    private var readNotifications = false
    private var callScreeningEnabled = true

    private var isDndActive = false
    private val dndFilter = IntentFilter(NotificationManager.INTERRUPTION_FILTER_CHANGED_ACTION)

    private var notificationPollJob: Job? = null
    private var callPollJob: Job? = null

    private val serviceChannelId = "jarvis_hermes_service"
    private val notificationId = 1

    enum class State { IDLE, LISTENING, PROCESSING, PAUSED, WAKE_WORD }

    private val dndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { checkDndState() }
    }

    private fun hermesBaseUrl(): String =
        if (hermesIp.isNotBlank()) "http://$hermesIp:8642" else ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Make sure we are foregrounded ASAP — Android 12+ kills us within 5s
        // otherwise.
        startForegroundCompat(buildNotification("Starting…"))

        loadPrefs()

        hermesApi = HermesApi(hermesBaseUrl(), apiKey).apply {
            setContext(this@VoiceService)
            setPrefsListener { state ->
                getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
                    .edit().putString("connection_state", state).apply()
                JarvisWidget.broadcastStateUpdate(this@VoiceService)
            }
        }
        tts = TextToSpeech(this, this)

        if (hasMicPermission()) initSpeechRecognizer()

        checkDndState()
        registerReceiverSafe(dndReceiver, dndFilter)

        BluetoothAutoManager.init(this)
        CallScreenHelper.init(this)

        acquireWakeLock()

        if (!BatteryOptimizationHelper.isBatteryExempt(this)) {
            android.util.Log.w("VoiceService", "App is battery optimized — may be killed in background")
        }

        if (readNotifications) startNotificationPolling()
        startCallPolling()
    }

    private fun registerReceiverSafe(receiver: BroadcastReceiver, filter: IntentFilter) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        } catch (_: Exception) {}
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        hermesIp = prefs.getString("hermes_ip", "") ?: ""
        apiKey = prefs.getString("api_key", "") ?: ""
        silenceDelay = prefs.getLong("silence_delay", 1500L)
        wakeWordMode = prefs.getBoolean("wake_word_mode", false)
        wakePhrase = prefs.getString("wake_phrase", "okay jarvis") ?: "okay jarvis"
        respectDnd = prefs.getBoolean("respect_dnd", true)
        readNotifications = prefs.getBoolean("read_notifications", false)
        callScreeningEnabled = prefs.getBoolean("call_screening_enabled", true)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startConversation()
            "END" -> endConversation()
            "PAUSE" -> pauseListening()
            "RESUME" -> resumeListening()
            "SETTINGS_UPDATED" -> reloadSettings()
            else -> { /* fresh start — wait for explicit command */ }
        }
        return START_STICKY
    }

    private fun reloadSettings() {
        loadPrefs()
        if (readNotifications) startNotificationPolling() else stopNotificationPolling()
        hermesApi = HermesApi(hermesBaseUrl(), apiKey).apply {
            setContext(this@VoiceService)
            setPrefsListener { state ->
                getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
                    .edit().putString("connection_state", state).apply()
                JarvisWidget.broadcastStateUpdate(this@VoiceService)
            }
        }
    }

    private fun checkDndState() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        isDndActive = nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JarvisHermes::VoiceWakeLock").apply {
            try { acquire(60 * 60 * 1000L) } catch (_: Exception) {}
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(serviceChannelId) != null) return
        val channel = NotificationChannel(
            serviceChannelId,
            "Jarvis Hermes",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Voice assistant running in background"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endIntent = PendingIntent.getService(
            this, 1,
            Intent(this, VoiceService::class.java).apply { action = "END" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = PendingIntent.getService(
            this, 2,
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
        try { nm.notify(notificationId, notification) } catch (_: Exception) {}
        JarvisWidget.broadcastStateUpdate(this)
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Exception) {
            // ForegroundServiceTypeException on Android 14 if mic perm not granted, etc.
            try { startForeground(notificationId, notification) } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?) = null

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_LONG).show()
            return
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(createRecognitionListener(wakeWordCheck = false))
            }
        }
    }

    private fun createRecognitionListener(wakeWordCheck: Boolean): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                // ERROR_RECOGNIZER_BUSY / ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT
                // are all transient — restart the right loop with a small delay.
                if (wakeWordCheck) {
                    if (isInWakeWordListening) restartWakeWordListening()
                } else if (conversationActive && state == State.LISTENING && !isPaused) {
                    restartListeningLoop(immediate = false)
                }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    if (wakeWordCheck) handleWakeWordResult(text)
                    else handleRecognizedText(text)
                }
                if (wakeWordCheck) restartWakeWordListening()
                else if (conversationActive && !isPaused && state != State.PROCESSING) {
                    restartListeningLoop(immediate = false)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (wakeWordCheck) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) checkForWakePhrase(text)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun recognizerIntent(partial: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partial)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceDelay)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }

    private fun startWakeWordListening() {
        if (!hasMicPermission()) {
            updateNotification("Mic permission required.")
            return
        }
        if (continuousRecognizer == null) {
            continuousRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(createRecognitionListener(wakeWordCheck = true))
            }
        }
        isInWakeWordListening = true
        state = State.WAKE_WORD
        try { continuousRecognizer?.startListening(recognizerIntent(partial = true)) } catch (_: Exception) {}
        updateNotification("Wake word — say \"$wakePhrase\"")
    }

    private fun restartWakeWordListening() {
        if (!isInWakeWordListening) return
        try {
            continuousRecognizer?.cancel()
            continuousRecognizer?.startListening(recognizerIntent(partial = true))
        } catch (_: Exception) {}
    }

    private fun checkForWakePhrase(text: String) {
        val lower = text.lowercase().trim()
        if (lower.contains(wakePhrase.lowercase())) {
            isInWakeWordListening = false
            try { continuousRecognizer?.stopListening() } catch (_: Exception) {}
            speakAsync("Yes?")
            startConversationFromWakeWord()
        }
    }

    private fun handleWakeWordResult(text: String) = checkForWakePhrase(text)

    private fun startConversationFromWakeWord() {
        userMessages.clear(); jarvisMessages.clear(); jarvisBuilder.clear()
        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        val systemPrompt = prefs.getString(SystemPromptBuilder.PREFS_KEY_SYSTEM_PROMPT, SystemPromptBuilder.getDefault())
            ?: SystemPromptBuilder.getDefault()
        hermesApi?.initConversation(systemPrompt)
        prefs.edit().putBoolean("conversation_active", true).apply()
        startForegroundCompat(buildNotification("Listening…"))
        conversationActive = true; isPaused = false; state = State.LISTENING
        JarvisWidget.broadcastStateUpdate(this)
        restartListeningLoop(immediate = true)
    }

    private fun handleRecognizedText(text: String) {
        val lower = text.lowercase().trim()

        // Call screening
        if (callScreeningEnabled && CallScreenHelper.isCallActive(this)) {
            when {
                lower == "answer" || lower == "answer call" -> {
                    CallScreenHelper.answerCall(this); speakAsync("Answered."); return
                }
                lower == "reject" || lower == "reject call" || lower == "ignore call" -> {
                    CallScreenHelper.rejectCall(this); speakAsync("Rejected."); return
                }
                lower == "voicemail" -> {
                    CallScreenHelper.sendToVoicemail(this); speakAsync("Voicemail."); return
                }
            }
        }

        // Quick phrases (macros)
        val expandedCommands = QuickPhraseManager.expandPhrase(this, text)
        if (expandedCommands != null) {
            for (command in expandedCommands) {
                val response = LocalCommandClassifier.handle(this, command)
                if (response != null) speakAsync(response.text)
                else sendToHermes(command)
            }
            return
        }

        // Service commands
        when {
            conversationActive && isPaused && lower == "mic on" -> { resumeListening(); return }
            conversationActive && !isPaused && lower == "mic off" -> { pauseListening(); return }
            lower == "end conversation" || lower == "stop conversation" || lower == "end session" -> {
                if (wakeWordMode) endConversation(returnToWakeWord = true) else endConversation()
                return
            }
            lower in setOf("note this", "save that", "voice memo", "remember this", "memo") -> {
                startVoiceMemo(); return
            }
            lower.startsWith("play my memos") || lower == "play memos" -> { playMemos(); return }
            lower.startsWith("delete memo") -> { deleteMemo(lower); return }
        }

        if (!conversationActive || isPaused) return

        // Local commands
        val localResponse = LocalCommandClassifier.handle(this, text)
        if (localResponse != null) {
            speakResponse(localResponse)
            return
        }

        // Otherwise hand off to Hermes
        userMessages.add(text)
        jarvisBuilder.clear()
        sendToHermes(text)
    }

    private fun startVoiceMemo() {
        if (VoiceMemoManager.isCurrentlyRecording()) {
            val memo = VoiceMemoManager.stopRecording(this)
            speakAsync(if (memo != null) "Memo saved." else "Couldn't save memo.")
        } else {
            speakAsync(if (VoiceMemoManager.startRecording(this)) "Recording. Say note this to stop."
                       else "Couldn't start recording.")
        }
    }

    private fun playMemos() {
        val memos = VoiceMemoManager.getMemos(this)
        if (memos.isEmpty()) { speakAsync("No memos yet."); return }
        val recent = memos.take(5)
        val list = recent.mapIndexed { i, m -> "${i + 1}. ${VoiceMemoManager.formatTimestamp(m.timestamp)}" }
            .joinToString(", ")
        speakAsync("Your recent memos: $list. Say play memo followed by a number.")
    }

    private fun deleteMemo(text: String) {
        val match = Regex("(\\d+)").find(text)
        if (match == null) { speakAsync("Which memo?"); return }
        val n = match.groupValues[1].toIntOrNull() ?: return
        val memos = VoiceMemoManager.getMemos(this)
        if (n < 1 || n > memos.size) { speakAsync("Invalid memo number."); return }
        speakAsync(if (VoiceMemoManager.deleteMemo(this, memos[n - 1].id)) "Memo deleted." else "Couldn't delete.")
    }

    private fun speakResponse(response: LocalResponse) {
        if (response.text.isNotBlank()) speakAsync(response.text)
        state = if (isPaused) State.PAUSED else State.LISTENING
        updateNotification(if (isPaused) "Paused" else "Listening…")
        if (!isPaused) restartListeningLoop(immediate = false)
    }

    private fun startConversation() {
        if (!hasMicPermission()) {
            Toast.makeText(this, "Microphone permission needed", Toast.LENGTH_LONG).show()
            updateNotification("Mic permission needed")
            return
        }
        if (hermesIp.isBlank()) {
            Toast.makeText(this, "Configure Hermes IP in Settings", Toast.LENGTH_LONG).show()
            updateNotification("Hermes not configured")
            return
        }
        if (speechRecognizer == null) initSpeechRecognizer()
        if (speechRecognizer == null) {
            Toast.makeText(this, "Speech recognizer unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        userMessages.clear(); jarvisMessages.clear(); jarvisBuilder.clear()

        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        val systemPrompt = prefs.getString(SystemPromptBuilder.PREFS_KEY_SYSTEM_PROMPT, SystemPromptBuilder.getDefault())
            ?: SystemPromptBuilder.getDefault()
        hermesApi?.initConversation(systemPrompt)
        prefs.edit().putBoolean("conversation_active", true).apply()

        startForegroundCompat(buildNotification("Starting…"))

        conversationActive = true; isPaused = false; state = State.LISTENING
        speakAsync("Yes?")
        restartListeningLoop(immediate = true)
        updateNotification("Listening…")
    }

    private fun pauseListening() {
        isPaused = true; state = State.PAUSED
        try { speechRecognizer?.stopListening() } catch (_: Exception) {}
        speakAsync("Mic off. Say mic on to resume.")
        updateNotification("Paused — say mic on")
    }

    private fun resumeListening() {
        isPaused = false; state = State.LISTENING
        speakAsync("Mic on.")
        updateNotification("Listening…")
        restartListeningLoop(immediate = true)
    }

    private fun endConversation(returnToWakeWord: Boolean = false) {
        conversationActive = false; isPaused = false
        try { speechRecognizer?.stopListening() } catch (_: Exception) {}
        state = State.IDLE
        updateNotification("Ended")
        if (userMessages.isNotEmpty()) saveSession()
        hermesApi?.resetConversation()
        getSharedPreferences("jarvis_hermes", MODE_PRIVATE).edit().putBoolean("conversation_active", false).apply()

        scope.launch {
            delay(800)
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            if (returnToWakeWord && wakeWordMode) startWakeWordListening() else stopSelf()
        }
    }

    private fun restartListeningLoop(immediate: Boolean) {
        if (!conversationActive || isPaused || state == State.PROCESSING || isSpeaking) return
        scope.launch {
            delay(if (immediate) 50L else 400L)
            if (conversationActive && !isPaused && state != State.PROCESSING && !isSpeaking) {
                state = State.LISTENING
                updateNotification("Listening…")
                try {
                    speechRecognizer?.cancel()
                    speechRecognizer?.startListening(recognizerIntent(partial = false))
                } catch (_: Exception) {}
            }
        }
    }

    private fun sendToHermes(text: String) {
        state = State.PROCESSING
        updateNotification("Thinking…")

        hermesApi?.sendMessageStream(
            text,
            object : HermesApi.StreamListener {
                override fun onChunk(chunk: String) {
                    jarvisBuilder.append(chunk)
                    if (isTtsReady) speakAsync(chunk, queueAdd = true)
                }
                override fun onComplete(fullText: String) {
                    hermesApi?.cacheAssistantMessage(fullText)
                    jarvisMessages.add(fullText)
                    state = if (isPaused) State.PAUSED else State.LISTENING
                    updateNotification(if (isPaused) "Paused" else "Listening…")
                    if (!isPaused) restartListeningLoop(immediate = false)
                }
                override fun onError(error: String) {
                    jarvisMessages.add("[Error: $error]")
                    speakAsync("Connection error.")
                    state = if (isPaused) State.PAUSED else State.LISTENING
                    updateNotification("Listening…")
                    if (!isPaused) restartListeningLoop(immediate = false)
                }
                override fun onReconnecting() {
                    updateNotification("Reconnecting…")
                }
            }
        )
    }

    private fun vibrateOnce() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(200L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(200L)
            }
        } catch (_: Exception) {}
    }

    /**
     * Speak without blocking the main thread. Marks isSpeaking via the
     * UtteranceProgressListener (set in onInit) so the listening loop knows
     * to wait.
     */
    private fun speakAsync(text: String, queueAdd: Boolean = false) {
        if (text.isBlank()) return
        if (respectDnd && isDndActive) {
            vibrateOnce()
            updateNotification(text.take(40))
            return
        }
        if (!isTtsReady) return
        val mode = if (queueAdd) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        val id = "utt_${UUID.randomUUID()}"
        try { tts?.speak(text, mode, null, id) } catch (_: Exception) {}
    }

    private fun startNotificationPolling() {
        notificationPollJob?.cancel()
        notificationPollJob = scope.launch {
            while (isActive) {
                delay(3_000)
                if (conversationActive && readNotifications && !isSpeaking && !isPaused) {
                    drainNotification()
                }
            }
        }
    }

    private fun stopNotificationPolling() {
        notificationPollJob?.cancel(); notificationPollJob = null
    }

    private fun drainNotification() {
        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        val data = prefs.getString("latest_notification", null) ?: return
        val ts = prefs.getLong("notification_timestamp", 0L)
        if (System.currentTimeMillis() - ts > 30_000) {
            prefs.edit().remove("latest_notification").remove("notification_timestamp").apply()
            return
        }
        val parts = data.split("|", limit = 2)
        if (parts.size != 2) return
        prefs.edit().remove("latest_notification").remove("notification_timestamp").apply()
        val sender = parts[0]; val message = parts[1]
        speakAsync("Notification from $sender: $message")
    }

    private fun startCallPolling() {
        callPollJob?.cancel()
        callPollJob = scope.launch {
            while (isActive) {
                delay(1_500)
                if (!conversationActive || isSpeaking) continue
                val msg = CallScreenHelper.getPendingAnnouncement(this@VoiceService) ?: continue
                CallScreenHelper.clearPendingAnnouncement(this@VoiceService)
                speakAsync(msg)
            }
        }
    }

    private fun stopCallPolling() {
        callPollJob?.cancel(); callPollJob = null
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
        sessions.put(session)
        try {
            val existing = JSONArray(sessionsJson)
            for (i in 0 until existing.length()) sessions.put(existing.getJSONObject(i))
        } catch (_: Exception) {}
        val trimmed = JSONArray()
        for (i in 0 until minOf(sessions.length(), 50)) trimmed.put(sessions.get(i))
        prefs.edit().putString("sessions", trimmed.toString()).apply()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        try { tts?.language = Locale.getDefault() } catch (_: Exception) {}
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { isSpeaking = true }
            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                // After TTS finishes, restart listening if we were in a turn.
                if (conversationActive && !isPaused && state != State.PROCESSING) {
                    restartListeningLoop(immediate = false)
                }
            }
            @Deprecated("Required override")
            override fun onError(utteranceId: String?) { isSpeaking = false }
            override fun onError(utteranceId: String?, errorCode: Int) { isSpeaking = false }
        })
        isTtsReady = true

        if (wakeWordMode && hermesIp.isNotBlank()) startWakeWordListening()
    }

    override fun onDestroy() {
        try { unregisterReceiver(dndReceiver) } catch (_: Exception) {}
        BluetoothAutoManager.cleanup()
        CallScreenHelper.cleanup()
        stopNotificationPolling()
        stopCallPolling()
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        try { continuousRecognizer?.destroy() } catch (_: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }
}
