package com.jarvis.hermes

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic activity — shows latency for the last N turns.
 * Plain code-built UI so we don't need another layout file.
 */
class MetricsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(0xFF0D1117.toInt())
        }

        val title = TextView(this).apply {
            text = "Metrics"
            setTextColor(0xFF00D4FF.toInt())
            textSize = 28f
        }
        root.addView(title)

        val summary = TextView(this).apply {
            text = MetricsTracker.summary()
            setTextColor(0xFFE6EDF3.toInt())
            textSize = 14f
        }
        root.addView(summary, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 24 })

        val turns = MetricsTracker.snapshot()
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val list = TextView(this).apply {
            text = if (turns.isEmpty()) "No turns recorded yet." else turns.joinToString("\n") { t ->
                "${fmt.format(Date(t.timestamp))}  STT ${t.sttMs}ms · LLM ${t.llmFirstTokenMs}ms · total ${t.totalMs}ms"
            }
            setTextColor(0xFF8B949E.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 24 })

        val back = Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        }
        root.addView(back, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 48 })

        setContentView(root)
    }
}
