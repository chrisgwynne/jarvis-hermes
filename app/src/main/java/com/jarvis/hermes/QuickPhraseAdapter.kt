package com.jarvis.hermes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter for quick phrases list.
 */
class QuickPhraseAdapter(
    private val phrases: List<QuickPhraseManager.QuickPhrase>,
    private val onEditClick: (QuickPhraseManager.QuickPhrase) -> Unit,
    private val onDeleteClick: (QuickPhraseManager.QuickPhrase) -> Unit
) : RecyclerView.Adapter<QuickPhraseAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textPhrase: TextView = view.findViewById(R.id.textPhrase)
        val textCommands: TextView = view.findViewById(R.id.textCommands)
        val btnEdit: Button = view.findViewById(R.id.btnEdit)
        val btnDelete: Button = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_quick_phrase, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val phrase = phrases[position]

        holder.textPhrase.text = phrase.phrase
        holder.textCommands.text = phrase.commands.joinToString(" → ")

        holder.btnEdit.setOnClickListener {
            onEditClick(phrase)
        }

        holder.btnDelete.setOnClickListener {
            onDeleteClick(phrase)
        }
    }

    override fun getItemCount(): Int = phrases.size
}