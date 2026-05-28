package com.jarvis.hermes

import android.content.Context

/**
 * Confirmation manager for irreversible actions.
 *
 * Flow:
 *  1. VoiceService recognises an irreversible action (SMS send, contact
 *     delete, call to non-contact number, calendar event).
 *  2. Instead of executing, it stores a pending action and speaks a
 *     confirmation prompt.
 *  3. Next utterance is matched against confirm/cancel synonyms.
 *  4. On confirm → execute. On cancel or timeout (15s) → discard.
 *
 * Stored only in memory — confirmations don't survive a service kill,
 * which is the safe default.
 */
class ConfirmationManager {

    /**
     * A pending action is described by:
     *  - prompt: what the user hears
     *  - execute: () -> Unit that runs on confirm
     *  - timestamp: when it was queued
     */
    data class Pending(
        val prompt: String,
        val execute: () -> Unit,
        val timestamp: Long = System.currentTimeMillis()
    )

    private var pending: Pending? = null
    private val timeoutMs = 15_000L

    fun queue(prompt: String, execute: () -> Unit): String {
        pending = Pending(prompt, execute)
        return prompt
    }

    fun hasPending(): Boolean {
        val p = pending ?: return false
        if (System.currentTimeMillis() - p.timestamp > timeoutMs) {
            pending = null
            return false
        }
        return true
    }

    fun pendingPrompt(): String? = pending?.prompt

    /**
     * Match user text against confirm/cancel synonyms.
     * Returns:
     *  - "confirmed" if executed
     *  - "cancelled" if user declined
     *  - "ignored" if no pending action or no match (caller falls through
     *    to normal processing).
     */
    fun handleResponse(text: String): Response {
        val p = pending ?: return Response.IGNORED
        if (System.currentTimeMillis() - p.timestamp > timeoutMs) {
            pending = null
            return Response.IGNORED
        }
        val lower = text.lowercase().trim()
        return when {
            lower in CONFIRM || lower.startsWith("yes ") || lower.startsWith("confirm ") -> {
                pending = null
                try { p.execute() } catch (_: Exception) { /* swallow — already confirmed */ }
                Response.CONFIRMED
            }
            lower in CANCEL || lower.startsWith("no ") || lower.startsWith("cancel ") -> {
                pending = null
                Response.CANCELLED
            }
            else -> Response.IGNORED
        }
    }

    fun clear() { pending = null }

    enum class Response { CONFIRMED, CANCELLED, IGNORED }

    companion object {
        private val CONFIRM = setOf(
            "yes", "yeah", "yep", "yup", "confirm", "confirmed", "do it",
            "go ahead", "send it", "send", "okay", "ok", "sure", "affirmative"
        )
        private val CANCEL = setOf(
            "no", "nope", "cancel", "stop", "abort", "don't", "do not",
            "wait", "hold on", "never mind", "nevermind", "scratch that"
        )
    }
}
