package com.jarvis.hermes

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.hermes.databinding.ActivitySessionsBinding

/**
 * Activity for browsing and managing conversation sessions. Search and
 * favourites are added on top of the original list view.
 */
class SessionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionsBinding
    private var allSessions: List<Session> = emptyList()
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        injectSearchBar()
        setupUI()
        loadSessions()
    }

    /**
     * Sessions layout was originally fixed — inject a search EditText
     * above the list so we don't need a layout edit. Programmatic-only.
     */
    private fun injectSearchBar() {
        val root = binding.root as LinearLayout
        val search = EditText(this).apply {
            hint = "Search sessions"
            setHintTextColor(0xFF6E7681.toInt())
            setTextColor(0xFFE6EDF3.toInt())
            setBackgroundColor(0xFF161B22.toInt())
            setPadding(24, 16, 24, 16)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    query = s?.toString().orEmpty()
                    applyFilter()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        // Insert search field just before the RecyclerView (index 2 keeps
        // it under the title + count).
        root.addView(search, 2, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 12 })
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnClearSessions.setOnClickListener {
            getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
                .edit().putString("sessions", "[]").apply()
            loadSessions()
            Toast.makeText(this, "Sessions cleared", Toast.LENGTH_SHORT).show()
        }
        binding.recyclerSessions.layoutManager = LinearLayoutManager(this)
    }

    private fun loadSessions() {
        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        val sessionsJson = prefs.getString("sessions", "[]") ?: "[]"
        val favourites = prefs.getStringSet("session_favourites", emptySet()).orEmpty()

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
                    transcript = obj.getString("transcript"),
                    favourite = favourites.contains(obj.getString("id"))
                ))
            }
        } catch (_: Exception) { /* ignore */ }

        // Favourites first, then by recency.
        allSessions = sessions.sortedWith(
            compareByDescending<Session> { it.favourite }
                .thenByDescending { it.timestamp }
        )
        applyFilter()
        binding.textCount.text = "${allSessions.size} session${if (allSessions.size != 1) "s" else ""}"
    }

    private fun applyFilter() {
        val filtered = if (query.isBlank()) allSessions
        else allSessions.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.preview.contains(query, ignoreCase = true) ||
            it.transcript.contains(query, ignoreCase = true)
        }

        if (filtered.isEmpty()) {
            binding.textEmpty.visibility = View.VISIBLE
            binding.recyclerSessions.visibility = View.GONE
        } else {
            binding.textEmpty.visibility = View.GONE
            binding.recyclerSessions.visibility = View.VISIBLE
            binding.recyclerSessions.adapter = SessionAdapter(this, filtered) { session ->
                toggleFavourite(session.id)
            }
        }
    }

    private fun toggleFavourite(id: String) {
        val prefs = getSharedPreferences("jarvis_hermes", MODE_PRIVATE)
        val favourites = prefs.getStringSet("session_favourites", emptySet()).orEmpty().toMutableSet()
        if (id in favourites) favourites.remove(id) else favourites.add(id)
        prefs.edit().putStringSet("session_favourites", favourites).apply()
        loadSessions()
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
    val transcript: String = "",
    val favourite: Boolean = false
)
