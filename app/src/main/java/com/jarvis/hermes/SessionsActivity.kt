package com.jarvis.hermes

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.hermes.databinding.ActivitySessionsBinding

/**
 * Activity for browsing and managing conversation sessions.
 */
class SessionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        loadSessions()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnClearSessions.setOnClickListener {
            getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
                .edit()
                .putString("sessions", "[]")
                .apply()
            loadSessions()
            Toast.makeText(this, "Sessions cleared", Toast.LENGTH_SHORT).show()
        }

        binding.recyclerSessions.layoutManager = LinearLayoutManager(this)
    }

    private fun loadSessions() {
        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        val sessionsJson = prefs.getString("sessions", "[]") ?: "[]"
        val sessions = mutableListOf<Session>()

        try {
            val array = org.json.JSONArray(sessionsJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                sessions.add(Session(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    preview = obj.getString("preview"),
                    timestamp = obj.getLong("timestamp"),
                    messageCount = obj.getInt("messageCount"),
                    transcript = obj.getString("transcript")
                ))
            }
        } catch (e: Exception) {
            // Ignore
        }

        if (sessions.isEmpty()) {
            binding.textEmpty.visibility = View.VISIBLE
            binding.recyclerSessions.visibility = View.GONE
        } else {
            binding.textEmpty.visibility = View.GONE
            binding.recyclerSessions.visibility = View.VISIBLE

            binding.recyclerSessions.adapter = SessionAdapter(this, sessions.reversed())
        }

        binding.textCount.text = "${sessions.size} session${if (sessions.size != 1) "s" else ""}"
    }
}

/**
 * Data class for session information.
 */
data class Session(
    val id: String,
    val title: String,
    val preview: String,
    val timestamp: Long,
    val messageCount: Int,
    val transcript: String = ""
)