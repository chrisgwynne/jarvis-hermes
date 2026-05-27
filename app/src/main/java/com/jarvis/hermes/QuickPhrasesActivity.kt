package com.jarvis.hermes

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.hermes.databinding.ActivityQuickPhrasesBinding

/**
 * Activity for managing quick phrases / macros.
 */
class QuickPhrasesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuickPhrasesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuickPhrasesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        loadPhrases()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.fabAdd.setOnClickListener {
            showAddEditDialog(null)
        }

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
}