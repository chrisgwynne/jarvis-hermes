package com.jarvis.hermes

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hermes API client.
 *
 *  - HTTP/2 with a small connection pool so the TCP+TLS handshake amortises.
 *  - prewarm() does a HEAD on /v1/models in a background thread so the first
 *    real request is hot.
 *  - sendMessageStream() streams SSE chunks; on failure we retry with
 *    exponential backoff (max ~30s of attempts) before surfacing the error.
 *  - cachedMessagesJson holds the rolling conversation; if a streamed request
 *    fails before completing, we strip the trailing user message so the next
 *    send doesn't duplicate it.
 */
class HermesApi(private val baseUrl: String, private val apiKey: String = "") {

    private var context: Context? = null
    private var prefsListener: ((String) -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    private var cachedMessagesJson: JSONArray? = null
    private var lastAssistantMessage: String = ""
    private var messagesInitialized = false

    private val retryCount = AtomicInteger(0)
    private val inFlight = AtomicBoolean(false)

    interface StreamListener {
        fun onChunk(text: String)
        fun onComplete(fullText: String)
        fun onError(error: String)
        fun onReconnecting()
    }

    fun setContext(ctx: Context) { context = ctx.applicationContext }
    fun setPrefsListener(listener: (String) -> Unit) { prefsListener = listener }

    private fun updateConnectionState(state: String) {
        prefsListener?.invoke(state)
        context?.getSharedPreferences("jarvis_hermes", Context.MODE_PRIVATE)
            ?.edit()
            ?.putString("connection_state", state)
            ?.apply()
    }

    fun initConversation(systemPrompt: String) {
        cachedMessagesJson = JSONArray().apply {
            if (systemPrompt.isNotBlank()) {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
            }
        }
        messagesInitialized = true
        lastAssistantMessage = ""
        prewarm()
        updateConnectionState("connected")
    }

    fun resetConversation() {
        cachedMessagesJson = null
        lastAssistantMessage = ""
        messagesInitialized = false
        retryCount.set(0)
        inFlight.set(false)
    }

    private fun prewarm() {
        if (baseUrl.isBlank()) return
        Thread {
            try {
                val req = Request.Builder()
                    .url("$baseUrl/v1/models")
                    .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey") }
                    .head()
                    .build()
                client.newCall(req).execute().close()
            } catch (_: Exception) { /* prewarm best-effort */ }
        }.apply { isDaemon = true }.start()
    }

    private fun networkAvailable(): Boolean {
        val ctx = context ?: return true // assume yes if we can't tell
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun sendMessageStream(message: String, listener: StreamListener, systemPrompt: String? = null) {
        if (baseUrl.isBlank()) {
            listener.onError("Hermes server not configured.")
            return
        }
        if (!networkAvailable()) {
            updateConnectionState("offline")
            listener.onError("No network.")
            return
        }
        if (!messagesInitialized || cachedMessagesJson == null) {
            initConversation(systemPrompt ?: "")
        }

        val msgs = cachedMessagesJson ?: JSONArray().also { cachedMessagesJson = it }
        msgs.put(JSONObject().put("role", "user").put("content", message))

        executeStream(message, listener, attempt = 0)
    }

    private fun executeStream(message: String, listener: StreamListener, attempt: Int) {
        val payload = JSONObject()
            .put("model", "hermes")
            .put("messages", cachedMessagesJson)
            .put("stream", true)

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .apply {
                if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey")
                addHeader("Accept", "text/event-stream")
            }
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        if (!inFlight.compareAndSet(false, true)) {
            listener.onError("Another request is in flight.")
            return
        }

        client.newCall(request).enqueue(object : Callback {
            private val textBuffer = StringBuilder()
            private val sentenceEnd = Regex("[.!?]\\s+")
            private val fullText = StringBuilder()

            override fun onFailure(call: Call, e: IOException) {
                inFlight.set(false)
                retryOrFail(e.message ?: "Connection failed", attempt, message, listener)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        val err = "HTTP ${response.code}"
                        inFlight.set(false)
                        // 4xx is unlikely to recover; don't retry.
                        if (response.code in 400..499) {
                            stripPendingUserMessage()
                            listener.onError(err)
                        } else {
                            retryOrFail(err, attempt, message, listener)
                        }
                        return
                    }
                    val body = response.body ?: run {
                        inFlight.set(false)
                        retryOrFail("Empty response", attempt, message, listener)
                        return
                    }
                    val source = body.source()
                    updateConnectionState("connected")
                    retryCount.set(0)
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: continue
                        if (!line.startsWith("data: ")) continue
                        val data = line.substring(6).trim()
                        if (data == "[DONE]") {
                            if (textBuffer.isNotEmpty()) {
                                listener.onChunk(textBuffer.toString())
                                fullText.append(textBuffer)
                                textBuffer.setLength(0)
                            }
                            inFlight.set(false)
                            listener.onComplete(fullText.toString())
                            return
                        }
                        parseChunk(data)?.let { text ->
                            if (text.isNotBlank()) accumulateAndSpeak(text, listener)
                        }
                    }
                    // Stream ended without [DONE] — treat as complete.
                    if (textBuffer.isNotEmpty()) {
                        listener.onChunk(textBuffer.toString())
                        fullText.append(textBuffer)
                    }
                    inFlight.set(false)
                    listener.onComplete(fullText.toString())
                } catch (e: Exception) {
                    inFlight.set(false)
                    retryOrFail(e.message ?: "Stream error", attempt, message, listener)
                } finally {
                    try { response.close() } catch (_: Exception) {}
                }
            }

            private fun accumulateAndSpeak(text: String, listener: StreamListener) {
                textBuffer.append(text)
                fullText.append(text)
                var match = sentenceEnd.find(textBuffer.toString())
                while (match != null) {
                    val end = match.range.last + 1
                    val toSpeak = textBuffer.substring(0, end)
                    textBuffer.delete(0, end)
                    listener.onChunk(toSpeak)
                    match = sentenceEnd.find(textBuffer.toString())
                }
            }
        })
    }

    private fun retryOrFail(err: String, attempt: Int, message: String, listener: StreamListener) {
        val delayMs = when (attempt) {
            0 -> 1_000L
            1 -> 2_000L
            2 -> 4_000L
            3 -> 8_000L
            else -> -1L
        }
        if (delayMs < 0) {
            stripPendingUserMessage()
            updateConnectionState("disconnected")
            listener.onError(err)
            return
        }
        updateConnectionState("reconnecting")
        listener.onReconnecting()
        Thread {
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return@Thread }
            executeStream(message, listener, attempt + 1)
        }.apply { isDaemon = true }.start()
    }

    /** Remove the last user message so retries don't duplicate it. */
    private fun stripPendingUserMessage() {
        val msgs = cachedMessagesJson ?: return
        if (msgs.length() == 0) return
        val last = msgs.optJSONObject(msgs.length() - 1) ?: return
        if (last.optString("role") == "user") {
            msgs.remove(msgs.length() - 1)
        }
    }

    fun cacheAssistantMessage(message: String) {
        cachedMessagesJson?.put(JSONObject().put("role", "assistant").put("content", message))
        lastAssistantMessage = message
    }

    private fun parseChunk(data: String): String? {
        return try {
            val json = JSONObject(data)
            val choices = json.optJSONArray("choices") ?: return null
            val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: return null
            val content = delta.optString("content")
            content.ifBlank { null }
        } catch (_: Exception) { null }
    }

    suspend fun sendMessage(message: String, systemPrompt: String? = null): String? {
        if (!messagesInitialized) initConversation(systemPrompt ?: "")
        cachedMessagesJson?.put(JSONObject().put("role", "user").put("content", message))

        val payload = JSONObject()
            .put("model", "hermes")
            .put("messages", cachedMessagesJson)
            .put("stream", false)

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey") }
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    stripPendingUserMessage()
                    return null
                }
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val choices = json.optJSONArray("choices") ?: return null
                val first = choices.optJSONObject(0) ?: return null
                val messageObj = first.optJSONObject("message") ?: return null
                messageObj.optString("content").ifBlank { null }
            }
        } catch (_: Exception) {
            stripPendingUserMessage()
            null
        }
    }
}
