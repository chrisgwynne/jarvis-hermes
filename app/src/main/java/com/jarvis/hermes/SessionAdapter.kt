package com.jarvis.hermes

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for the sessions list. Each row shows title, time
 * ago, message count, a snippet preview, and an export button.
 */
class SessionAdapter(
    private val context: Context,
    private val sessions: List<Session>
) : RecyclerView.Adapter<SessionAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.sessionTitle)
        val preview: TextView = view.findViewById(R.id.sessionPreview)
        val time: TextView = view.findViewById(R.id.sessionTime)
        val count: TextView = view.findViewById(R.id.sessionCount)
        val exportBtn: ImageButton = view.findViewById(R.id.exportBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = sessions.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val session = sessions[position]
        holder.title.text = session.title
        holder.preview.text = session.preview.replace("You: ", "").replace("Jarvis: ", "").take(100)
        holder.time.text = formatTime(session.timestamp)
        holder.count.text = "${session.messageCount} messages"
        holder.exportBtn.setOnClickListener { exportSession(session) }
    }

    private fun exportSession(session: Session) {
        val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        val dateStr = dateFormat.format(Date(session.timestamp))

        val markdown = buildString {
            appendLine("# Conversation")
            appendLine()
            appendLine("**Date:** $dateStr")
            appendLine()
            appendLine("---")
            appendLine()
            for (line in session.transcript.split("\n")) {
                when {
                    line.startsWith("You: ") -> appendLine("**You:** ${line.substring(5)}")
                    line.startsWith("Jarvis: ") -> appendLine("**Jarvis:** ${line.substring(8)}")
                    line.isBlank() -> appendLine()
                    else -> appendLine(line)
                }
            }
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Jarvis Conversation: ${session.title}")
            putExtra(Intent.EXTRA_TEXT, markdown)
        }
        try {
            context.startActivity(
                Intent.createChooser(shareIntent, "Export session")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) { /* user cancelled or no share targets */ }
    }

    private fun formatTime(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000 -> "Just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            else -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
