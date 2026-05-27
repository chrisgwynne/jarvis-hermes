package com.jarvis.hermes

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class HermesApi(private val baseUrl: String, private val apiKey: String = "") {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    interface StreamListener {
        fun onChunk(text: String)
        fun onComplete(fullText: String)
        fun onError(error: String)
    }

    /**
     * Send message with full context including system prompt and conversation history.
     */
    fun sendMessageStream(
        message: String,
        listener: StreamListener,
        systemPrompt: String? = null,
        conversationHistory: List<Pair<String, String>> = emptyList()
    ) {
        val messages = JSONArray()

        // Add system prompt if provided
        if (!systemPrompt.isNullOrBlank()) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }

        // Add conversation history
        for ((userMsg, jarvisMsg) in conversationHistory.takeLast(10)) {
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userMsg)
            })
            messages.put(JSONObject().apply {
                put("role", "assistant")
                put("content", jarvisMsg)
            })
        }

        // Add current message
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", message)
        })

        val payload = JSONObject().apply {
            put("model", "hermes")
            put("messages", messages)
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
            private val buffer = StringBuilder()
            private val fullText = StringBuilder()

            override fun onFailure(call: Call, e: IOException) {
                listener.onError(e.message ?: "Connection failed")
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.let { body ->
                    val source = body.source()
                    try {
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: continue
                            when {
                                line.startsWith("data: ") -> {
                                    val data = line.substring(6).trim()
                                    if (data == "[DONE]") {
                                        listener.onComplete(fullText.toString())
                                        return
                                    }
                                    parseChunk(data)?.let { text ->
                                        fullText.append(text)
                                        listener.onChunk(text)
                                    }
                                }
                                line.startsWith("event: ") -> {
                                    // Could handle named events here
                                }
                                else -> {
                                    // SSE comment or blank line — ignore
                                }
                            }
                        }
                        listener.onComplete(fullText.toString())
                    } catch (e: Exception) {
                        listener.onError(e.message ?: "Stream error")
                    }
                } ?: run {
                    listener.onError("Empty response")
                }
            }
        })
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
     * Send message with context (convenience method without streaming).
     */
    suspend fun sendMessage(
        message: String,
        systemPrompt: String? = null,
        conversationHistory: List<Pair<String, String>> = emptyList()
    ): String? {
        val messages = JSONArray()

        if (!systemPrompt.isNullOrBlank()) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }

        for ((userMsg, jarvisMsg) in conversationHistory.takeLast(10)) {
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userMsg)
            })
            messages.put(JSONObject().apply {
                put("role", "assistant")
                put("content", jarvisMsg)
            })
        }

        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", message)
        })

        val payload = JSONObject().apply {
            put("model", "hermes")
            put("messages", messages)
            put("stream", false)
        }

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .apply {
                if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey")
            }
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    val choices = json.optJSONArray("choices") ?: return@withContext null
                    val first = choices.optJSONObject(0) ?: return@withContext null
                    val messageObj = first.optJSONObject("message") ?: return@withContext null
                    messageObj.optString("content")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private suspend fun <T> withContext(dispatcher: kotlinx.coroutines.CoroutineDispatcher, block: () -> T): T {
        return kotlinx.coroutines.withContext(dispatcher) { block() }
    }
}