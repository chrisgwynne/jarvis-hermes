package com.jarvis.hermes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Quick phrase / macro manager.
 * Stores and retrieves custom voice shortcuts.
 */
object QuickPhraseManager {

    private const val PREFS_NAME = "jarvis_hermes"
    private const val KEY_QUICK_PHRASES = "quick_phrases"

    data class QuickPhrase(
        val id: String,
        val phrase: String,
        val commands: List<String>
    )

    /**
     * Get all quick phrases.
     */
    fun getQuickPhrases(ctx: Context): List<QuickPhrase> {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_QUICK_PHRASES, "[]") ?: "[]"

        val phrases = mutableListOf<QuickPhrase>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val commandsArray = obj.getJSONArray("commands")
                val commands = mutableListOf<String>()
                for (j in 0 until commandsArray.length()) {
                    commands.add(commandsArray.getString(j))
                }
                phrases.add(QuickPhrase(
                    id = obj.getString("id"),
                    phrase = obj.getString("phrase"),
                    commands = commands
                ))
            }
        } catch (e: Exception) {
            // Ignore
        }

        return phrases
    }

    /**
     * Get quick phrase by trigger phrase (case-insensitive).
     */
    fun getQuickPhrase(ctx: Context, triggerText: String): QuickPhrase? {
        val lowerTrigger = triggerText.lowercase().trim()
        return getQuickPhrases(ctx).find { it.phrase.lowercase() == lowerTrigger }
    }

    /**
     * Add a new quick phrase.
     */
    fun addQuickPhrase(ctx: Context, phrase: String, commands: List<String>): QuickPhrase {
        val quickPhrase = QuickPhrase(
            id = java.util.UUID.randomUUID().toString(),
            phrase = phrase.trim(),
            commands = commands.map { it.trim() }.filter { it.isNotBlank() }
        )

        val phrases = getQuickPhrases(ctx).toMutableList()
        phrases.add(quickPhrase)
        saveQuickPhrases(ctx, phrases)

        return quickPhrase
    }

    /**
     * Update an existing quick phrase.
     */
    fun updateQuickPhrase(ctx: Context, id: String, phrase: String, commands: List<String>): Boolean {
        val phrases = getQuickPhrases(ctx).toMutableList()
        val index = phrases.indexOfFirst { it.id == id }
        if (index < 0) return false

        phrases[index] = QuickPhrase(
            id = id,
            phrase = phrase.trim(),
            commands = commands.map { it.trim() }.filter { it.isNotBlank() }
        )
        saveQuickPhrases(ctx, phrases)
        return true
    }

    /**
     * Delete a quick phrase by ID.
     */
    fun deleteQuickPhrase(ctx: Context, id: String): Boolean {
        val phrases = getQuickPhrases(ctx).toMutableList()
        val removed = phrases.removeAll { it.id == id }
        if (removed) {
            saveQuickPhrases(ctx, phrases)
        }
        return removed
    }

    /**
     * Check if a text matches any quick phrase trigger.
     * Returns the QuickPhrase if matched, null otherwise.
     */
    fun matchPhrase(ctx: Context, text: String): QuickPhrase? {
        val lowerText = text.lowercase().trim()
        return getQuickPhrases(ctx).find { qp ->
            qp.phrase.lowercase() == lowerText ||
            lowerText.startsWith(qp.phrase.lowercase() + " ") ||
            lowerText.contains(qp.phrase.lowercase())
        }
    }

    private fun saveQuickPhrases(ctx: Context, phrases: List<QuickPhrase>) {
        val array = JSONArray()
        phrases.forEach { qp ->
            val obj = JSONObject().apply {
                put("id", qp.id)
                put("phrase", qp.phrase)
                put("commands", JSONArray(qp.commands))
            }
            array.put(obj)
        }

        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUICK_PHRASES, array.toString())
            .apply()
    }

    /**
     * Parse commands from macro text.
     * Supports comma or semicolon separated commands.
     */
    fun parseCommands(macroText: String): List<String> {
        return macroText.split(Regex("[,;]"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    /**
     * Expand a quick phrase into individual commands.
     */
    fun expandPhrase(ctx: Context, text: String): List<String>? {
        val matched = matchPhrase(ctx, text) ?: return null
        return matched.commands
    }
}