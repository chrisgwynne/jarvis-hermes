package com.jarvis.hermes

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionAdapter(private val context: Context, private val sessions: List<Session>) : BaseAdapter() {

    override fun getCount() = sessions.size
    override fun getItem(position: Int) = sessions[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_session, parent, false)
        val session = sessions[position]

        val titleText = view.findViewById<TextView>(R.id.sessionTitle)
        val previewText = view.findViewById<TextView>(R.id.sessionPreview)
        val timeText = view.findViewById<TextView>(R.id.sessionTime)
        val countText = view.findViewById<TextView>(R.id.sessionCount)

        titleText.text = session.title
        previewText.text = session.preview.replace("You: ", "").replace("Jarvis: ", "").take(100)
        timeText.text = formatTime(session.timestamp)
        countText.text = "${session.messageCount} messages"

        view.setOnClickListener {
            // Load session transcript into main activity
            // For now, just show preview
        }

        return view
    }

    private fun formatTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        return when {
            diff < 60_000 -> "Just now"
            diff < 3600_000 -> "${diff / 60_000}m ago"
            diff < 86400_000 -> "${diff / 3600_000}h ago"
            else -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(timestamp))
        }
    }
}