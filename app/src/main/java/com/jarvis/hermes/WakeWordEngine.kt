package com.jarvis.hermes

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

/**
 * Vosk-driven on-device wake word engine.
 *
 * Replaces SpeechRecognizer for "okay jarvis" detection — runs entirely
 * on-device, doesn't burn battery as hard, doesn't ship audio to Google.
 *
 * Model files are not bundled in the APK. The user (or onboarding flow)
 * unpacks a model into `<filesDir>/vosk-model/`. If the model is missing,
 * `start()` returns false and the caller falls back to SpeechRecognizer.
 *
 * Grammar is restricted to {wake phrase, "[unk]"} so the recogniser only
 * fires on close matches — false positives drop dramatically vs free-form
 * STT.
 */
class WakeWordEngine(
    private val context: Context,
    private val wakePhrase: String,
    private val onWake: () -> Unit
) {

    private var speechService: SpeechService? = null
    private var model: Model? = null
    @Volatile private var running = false

    fun isModelAvailable(): Boolean {
        val dir = modelDir()
        return dir.exists() && dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
    }

    fun modelDir(): File = File(context.filesDir, "vosk-model")

    /**
     * Returns true if the engine started; false if the model is missing
     * or initialisation failed (caller falls back to SpeechRecognizer).
     */
    fun start(): Boolean {
        if (running) return true
        if (!isModelAvailable()) {
            android.util.Log.i("WakeWordEngine",
                "Vosk model missing at ${modelDir().absolutePath} — falling back to SpeechRecognizer")
            return false
        }
        return try {
            val m = Model(modelDir().absolutePath)
            model = m
            val grammarJson = grammarJson(wakePhrase)
            val recognizer = Recognizer(m, 16_000f, grammarJson)
            val svc = SpeechService(recognizer, 16_000f)
            speechService = svc
            svc.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    if (matches(hypothesis)) fire()
                }
                override fun onResult(hypothesis: String?) {
                    if (matches(hypothesis)) fire()
                }
                override fun onFinalResult(hypothesis: String?) {
                    if (matches(hypothesis)) fire()
                }
                override fun onError(e: Exception?) {
                    android.util.Log.w("WakeWordEngine", "Vosk error: ${e?.message}")
                }
                override fun onTimeout() { /* ignored — we restart on stop */ }
            })
            running = true
            true
        } catch (e: Exception) {
            android.util.Log.w("WakeWordEngine", "Vosk init failed: ${e.message}")
            cleanup()
            false
        }
    }

    fun stop() {
        running = false
        try { speechService?.stop() } catch (_: Exception) {}
        try { speechService?.shutdown() } catch (_: Exception) {}
        speechService = null
        try { model?.close() } catch (_: Exception) {}
        model = null
    }

    private fun cleanup() {
        try { speechService?.shutdown() } catch (_: Exception) {}
        try { model?.close() } catch (_: Exception) {}
        speechService = null
        model = null
    }

    private fun matches(hypothesisJson: String?): Boolean {
        if (hypothesisJson.isNullOrBlank()) return false
        val text = try {
            val obj = JSONObject(hypothesisJson)
            obj.optString("partial").ifBlank { obj.optString("text") }
        } catch (_: Exception) {
            hypothesisJson
        }.lowercase()
        return text.contains(wakePhrase.lowercase())
    }

    private fun fire() {
        if (!running) return
        // Don't stop here — caller decides. We do guard against immediate
        // re-fire by suppressing further callbacks until caller stops us.
        running = false
        onWake()
    }

    private fun grammarJson(phrase: String): String {
        // Vosk grammar JSON: list of allowed phrases plus "[unk]" for OOV.
        return "[\"${phrase.lowercase()}\", \"[unk]\"]"
    }
}
