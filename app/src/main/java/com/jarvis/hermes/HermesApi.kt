package com.jarvis.hermes

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class HermesApi(private val baseUrl: String, private val apiKey: String = "") {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    suspend fun sendMessage(message: String): String? {
        val payload = JSONObject().apply {
            put("model", "hermes")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", message)
                })
            })
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