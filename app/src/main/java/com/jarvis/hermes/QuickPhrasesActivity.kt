package com.jarvis.hermes

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.hermes.databinding.ActivityQuickPhrasesBinding
import org.json.JSONArray
import org.json.JSONObject

/**
 * Activity for managing quick phrases / macros.
 *
 * Adds import/export via the system clipboard so users don't lose their
 * macros when they reinstall.
 */
class QuickPhrasesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuickPhrasesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuickPhrasesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        injectImportExportRow()
        setupUI()
        loadPhrases()
    }

    private fun injectImportExportRow() {
        val root = binding.root as LinearLayout
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }
        val export = android.widget.Button(this).apply {
            text = "Export"
            setTextColor(0xFFE6EDF3.toInt())
            setBackgroundColor(0xFF21262D.toInt())
            setOnClickListener { exportToClipboard() }
        }
        val import = android.widget.Button(this).apply {
            text = "Import"
            setTextColor(0xFFE6EDF3.toInt())
            setBackgroundColor(0xFF21262D.toInt())
            setOnClickListener { importFromClipboard() }
        }
        row.addView(export, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = 8 })
        row.addView(import, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // Insert above the Back button (last child).
        val insertAt = (root.childCount - 1).coerceAtLeast(0)
        root.addView(row, insertAt)
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }
        binding.fabAdd.setOnClickListener { showAddEditDialog(null) }
        binding.recyclerPhrases.layoutManager = LinearLayoutManager(this)
    }

    private fun loadPhrases() {
        val phrases = QuickPhraseManager.getQuickPhrases(this)
        if (phrases.isEmpty()) {
            binding.textEmpty.visibility = View.VISIBLE
            binding.recyclerPhrases.visibility = View.GONE
        } else {
            binding.textEmpty.visibility = View.GONE
            binding.recyclerPhrases.visibility = View.VISIBLE
            binding.recyclerPhrases.adapter = QuickPhraseAdapter(
                phrases,
                onEditClick = { phrase -> showAddEditDialog(phrase) },
                onDeleteClick = { phrase -> showDeleteConfirmation(phrase) }
            )
        }
        binding.textCount.text = "${phrases.size} phrase${if (phrases.size != 1) "s" else ""}"
    }

    private fun showAddEditDialog(phrase: QuickPhraseManager.QuickPhrase?) {
        val isEdit = phrase != null
        val title = if (isEdit) "Edit Quick Phrase" else "Add Quick Phrase"

        val dialogView = layoutInflater.inflate(R.layout.dialog_quick_phrase, null)
        val inputPhrase = dialogView.findViewById<EditText>(R.id.inputPhrase)
        val inputCommands = dialogView.findViewById<EditText>(R.id.inputCommands)

        if (isEdit) {
            inputPhrase.setText(phrase!!.phrase)
            inputCommands.setText(phrase.commands.joinToString(", "))
        }

        AlertDialog.Builder(this, R.style.JarvisDarkDialog)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(if (isEdit) "Update" else "Add") { _, _ ->
                val phraseText = inputPhrase.text.toString().trim()
                val commandsText = inputCommands.text.toString().trim()
                if (phraseText.isBlank()) {
                    Toast.makeText(this, "Phrase required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (commandsText.isBlank()) {
                    Toast.makeText(this, "Commands required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val commands = QuickPhraseManager.parseCommands(commandsText)
                if (isEdit) {
                    QuickPhraseManager.updateQuickPhrase(this, phrase!!.id, phraseText, commands)
                    Toast.makeText(this, "Updated", Toast.LENGTH_SHORT).show()
                } else {
                    QuickPhraseManager.addQuickPhrase(this, phraseText, commands)
                    Toast.makeText(this, "Added", Toast.LENGTH_SHORT).show()
                }
                loadPhrases()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmation(phrase: QuickPhraseManager.QuickPhrase) {
        AlertDialog.Builder(this, R.style.JarvisDarkDialog)
            .setTitle("Delete Phrase")
            .setMessage("Delete \"${phrase.phrase}\"?")
            .setPositiveButton("Delete") { _, _ ->
                QuickPhraseManager.deleteQuickPhrase(this, phrase.id)
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                loadPhrases()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportToClipboard() {
        val phrases = QuickPhraseManager.getQuickPhrases(this)
        val arr = JSONArray()
        phrases.forEach { qp ->
            arr.put(JSONObject().apply {
                put("phrase", qp.phrase)
                put("commands", JSONArray(qp.commands))
            })
        }
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("Jarvis Quick Phrases", arr.toString(2)))
        Toast.makeText(this, "Copied ${phrases.size} phrase${if (phrases.size != 1) "s" else ""} to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun importFromClipboard() {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cb.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        var imported = 0
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val phrase = obj.getString("phrase")
                val commands = mutableListOf<String>()
                val cmds = obj.getJSONArray("commands")
                for (j in 0 until cmds.length()) commands.add(cmds.getString(j))
                if (phrase.isNotBlank() && commands.isNotEmpty()) {
                    QuickPhraseManager.addQuickPhrase(this, phrase, commands)
                    imported++
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid clipboard content", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Imported $imported phrase${if (imported != 1) "s" else ""}", Toast.LENGTH_SHORT).show()
        loadPhrases()
    }
}
