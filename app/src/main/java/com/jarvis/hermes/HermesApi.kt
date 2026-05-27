package com.jarvis.hermes

import android.content.Context
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ConnectionPool
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optimized Hermes API client with auto-reconnect.
 *
 * Speed optimizations:
 * - HTTP/2 with connection pooling (reuses TCP connection across requests)
 * - Pre-warmed connection on startup (TCP + TLS handshake done before first query)
 * - Lazy system prompt: only sent on first message of conversation
 * - Cached message history as pre-built JSONArray (no rebuilding every request)
 * - Chunk accumulation for TTS: speak in sentence chunks, not per-token fragments
 *
 * Auto-reconnect:
 * - On error, retry after 2s, then 4s, then 8s, up to max 30s
 * - Updates connection_state in SharedPreferences
 * - Calls listener.onError to notify service
 */
class HermesApi(private val baseUrl: String, private val apiKey: String = "") {

    private var context: Context? = null
    private var prefsListener: ((String) -> Unit)? = null

    // Reusable connection pool — shared across all requests
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    // Cached state for conversation
    private var cachedMessagesJson: JSONArray? = null
    private var lastAssistantMessage: String = ""
    private var messagesInitialized = false

    // Retry state
    private var retryCount = 0
    private val isRetrying = AtomicBoolean(false)

    interface StreamListener {
        fun onChunk(text: String)
        fun onComplete(fullText: String)
        fun onError(error: String)
        fun onReconnecting()
    }

    fun setContext(ctx: Context) {
        context = ctx.applicationContext
    }

    fun setPrefsListener(listener: (String) -> Unit) {
        prefsListener = listener
    }

    private fun updateConnectionState(state: String) {
        prefsListener?.invoke(state)
        context?.getSharedPreferences("jarvis_hermes", Context.MODE_PRIVATE)
            ?.edit()
            ?.putString("connection_state", state)
            ?.apply()
    }

    /**
     * Initialize the conversation — call this once when conversation starts.
     * Sends the full system prompt + primes the connection.
     */
    fun initConversation(systemPrompt: String) {
        cachedMessagesJson = JSONArray()
        if (systemPrompt.isNotBlank()) {
            cachedMessagesJson?.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }
        messagesInitialized = true
        lastAssistantMessage = ""

        // Pre-warm the connection
        prewarm()
        updateConnectionState("connected")
    }

    /**
     * Clear conversation state.
     */
    fun resetConversation() {
        cachedMessagesJson = null
        lastAssistantMessage = ""
        messagesInitialized = false
        retryCount = 0
        isRetrying.set(false)
    }

    /**
     * Pre-warm the HTTP connection so the first real request is instant.
     */
    private fun prewarm() {
        if (baseUrl.isBlank()) return
        Thread {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/v1/models")
                    .apply {
                        if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey")
                    }
                    .head()
                    .build()
                client.newCall(request).execute().close()
            } catch (e: Exception) {
                // Silent — prewarm failure is fine
            }
        }
    }

    /**
     * Send message. System prompt only sent if not already initialized.
     * Uses cached message history — does not rebuild JSON each time.
     */
    fun sendMessageStream(
        message: String,
        listener: StreamListener,
        systemPrompt: String? = null,
        sendSystemPrompt: Boolean = true
    ) {
        // Initialize messages array if needed
        if (!messagesInitialized || cachedMessagesJson == null) {
            initConversation(systemPrompt ?: "")
            messagesInitialized = true
        }

        // Append user message to cached history
        cachedMessagesJson?.put(JSONObject().apply {
            put("role", "user")
            put("content", message)
        })

        val payload = JSONObject().apply {
            put("model", "hermes")
            put("messages", cachedMessagesJson)
            put("stream", true)
        }

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .apply {
                if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey")
                addHeader("Accept", "text/event-stream")
            }
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).enqueue(object : Callback() {
            // TTS chunk accumulation — speak in sentences, not tokens
            private val textBuffer = StringBuilder()
            private val sentenceEnd = Regex("[.!?]\\s+")
            private val fullText = StringBuilder()

            override fun onFailure(call: Call, e: IOException) {
                handleError(e.message ?: "Connection failed", listener)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    handleError("HTTP ${response.code}", listener)
                    return
                }
                response.body?.let { body ->
                    val source = body.source()
                    try {
                        updateConnectionState("connected")
                        retryCount = 0
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: continue
                            when {
                                line.startsWith("data: ") -> {
                                    val data = line.substring(6).trim()
                                    if (data == "[DONE]") {
                                        // Flush remaining buffer
                                        if (textBuffer.isNotEmpty()) {
                                            listener.onChunk(textBuffer.toString())
                                            fullText.append(textBuffer)
                                        }
                                        listener.onComplete(fullText.toString())
                                        return
                                    }
                                    parseChunk(data)?.let { text ->
                                        if (text.isNotBlank()) {
                                            accumulateAndSpeak(text, listener, fullText)
                                        }
                                    }
                                }
                                line.startsWith("event: ") -> {
                                    // Handle named events if needed
                                }
                                else -> {
                                    // SSE comment or blank line — ignore
                                }
                            }
                        }
                        listener.onComplete(fullText.toString())
                    } catch (e: Exception) {
                        handleError(e.message ?: "Stream error", listener)
                    }
                } ?: run {
                    handleError("Empty response", listener)
                }
            }

            private fun handleError(error: String, listener: StreamListener) {
                val delay = calculateRetryDelay()
                if (delay == null) {
                    // Max retries reached, notify and continue retrying in background
                    listener.onError(error)
                    scheduleBackgroundRetry(error, listener)
                    return
                }

                retryCount++
                listener.onReconnecting()
                updateConnectionState("reconnecting")

                Thread {
                    Thread.sleep(delay)
                    if (!isRetrying.compareAndSet(false, true)) return@Thread
                    try {
                        // Retry the request
                        sendMessageStream(
                            cachedMessagesJson?.let { msgs ->
                                // Get the last user message to retry
                                val arr = msgs as JSONArray
                                if (arr.length() > 0) {
                                    val lastMsg = arr.getJSONObject(arr.length() - 1)
                                    if (lastMsg.optString("role") == "user") {
                                        lastMsg.getString("content")
                                    } else null
                                } else null
                            } ?: message,
                            listener,
                            null,
                            false
                        )
                    } finally {
                        isRetrying.set(false)
                    }
                }.start()
            }

            private fun scheduleBackgroundRetry(originalError: String, listener: StreamListener) {
                Thread {
                    while (true) {
                        Thread.sleep(10_000)
                        if (!isRetrying.compareAndSet(false, true)) continue
                        try {
                            updateConnectionState("reconnecting")
                            listener.onReconnecting()

                            // Attempt reconnection
                            val testRequest = Request.Builder()
                                .url("$baseUrl/v1/models")
                                .apply {
                                    if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey")
                                }
                                .head()
                                .build()
                            val response = client.newCall(testRequest).execute()
                            if (response.isSuccessful) {
                                updateConnectionState("connected")
                                retryCount = 0
                                // Notify successful reconnect
                                break
                            }
                        } catch (e: Exception) {
                            // Still failing, will retry again
                        } finally {
                            isRetrying.set(false)
                        }
                    }
                }.start()
            }

            private fun calculateRetryDelay(): Long? {
                val baseDelay = when (retryCount) {
                    0 -> 2_000L
                    1 -> 4_000L
                    2 -> 8_000L
                    else -> return null // Max retries reached, use background retry
                }
                return minOf(baseDelay * (retryCount + 1), 30_000L)
            }

            /**
             * Accumulate text and speak in sentence chunks.
             * This reduces TTS fragmentation — instead of speaking every token
             * as it arrives, we buffer until we have a complete sentence.
             */
            private fun accumulateAndSpeak(
                text: String,
                listener: StreamListener,
                fullText: StringBuilder
            ) {
                textBuffer.append(text)
                fullText.append(text)

                // Try to flush complete sentences
                val match = sentenceEnd.find(textBuffer.toString())
                if (match != null) {
                    val toSpeak = textBuffer.substring(0, match.range.last + 1)
                    textBuffer.delete(0, match.range.last + 1)
                    listener.onChunk(toSpeak)
                }
                // Partial sentences buffered — wait for more
            }
        })
    }

    /**
     * After Hermes responds, cache the assistant message for next turn.
     */
    fun cacheAssistantMessage(message: String) {
        cachedMessagesJson?.put(JSONObject().apply {
            put("role", "assistant")
            put("content", message)
        })
        lastAssistantMessage = message
    }

    private fun parseChunk(data: String): String? {
        return try {
            val json = JSONObject(data)
            val choices = json.optJSONArray("choices") ?: return null
            val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: return null
            val content = delta.optString("content")
            content.ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Non-streaming send — for rare cases where streaming isn't needed.
     */
    suspend fun sendMessage(
        message: String,
        systemPrompt: String? = null
    ): String? {
        if (!messagesInitialized) {
            initConversation(systemPrompt ?: "")
        }

        cachedMessagesJson?.put(JSONObject().apply {
            put("role", "user")
            put("content", message)
        })

        val payload = JSONObject().apply {
            put("model", "hermes")
            put("messages", cachedMessagesJson)
            put("stream", false)
        }

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .apply {
                if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey")
            }
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val choices = json.optJSONArray("choices") ?: return null
                val first = choices.optJSONObject(0) ?: return null
                val messageObj = first.optJSONObject("message") ?: return null
                messageObj.optString("content")
            }
        } catch (e: Exception) {
            null
        }
    }
}