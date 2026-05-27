package com.jarvis.hermes.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.InputMethodManager
import com.jarvis.hermes.LocalResponse

/**
 * Clipboard action handler: copy to clipboard, what's on clipboard.
 */
object ClipboardAction {

    private const val ACTION_COPY = "copy"
    private const val ACTION_READ = "read"
    private const val ACTION_CUT = "cut"
    private const val ACTION_PASTE = "paste"

    fun requiredPermissions() = listOf<String>()

    fun canHandle(text: String): Map<String, String>? {
        return when {
            // Copy to clipboard
            Regex("""^copy\s+(.+)\s+to\s+clipboard$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^copy\s+(.+)\s+to\s+clipboard$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_COPY, "text" to (match?.groupValues?.get(1) ?: ""))
            }
            Regex("""^copy\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^copy\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_COPY, "text" to (match?.groupValues?.get(1) ?: ""))
            }
            // What's on clipboard
            Regex("""^(what('?s| is)\s+)?on\s+(my\s+)?clipboard$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_READ)
            }
            Regex("""^read\s+clipboard$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_READ)
            }
            // Cut
            Regex("""^cut\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^cut\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_CUT, "text" to (match?.groupValues?.get(1) ?: ""))
            }
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("Clipboard action unclear.", "clipboard_error")

        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        return when (action) {
            ACTION_COPY -> {
                val text = params["text"] ?: ""
                if (text.isBlank()) {
                    return LocalResponse("What would you like to copy?", "clipboard_copy")
                }
                copyToClipboard(context, text)
                LocalResponse("Copied to clipboard.", "clipboard_copy", mapOf("text" to text))
            }
            ACTION_READ -> {
                readClipboard(context)
            }
            ACTION_CUT -> {
                val text = params["text"] ?: ""
                if (text.isBlank()) {
                    return LocalResponse("What would you like to cut?", "clipboard_cut")
                }
                copyToClipboard(context, text)
                LocalResponse("Cut to clipboard.", "clipboard_cut", mapOf("text" to text))
            }
            else -> LocalResponse("Unknown clipboard action.", "clipboard_error")
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Jarvis", text)
        clipboardManager.setPrimaryClip(clip)
    }

    private fun readClipboard(context: Context): LocalResponse {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        return try {
            if (clipboardManager.hasPrimaryClip()) {
                val item = clipboardManager.primaryClip?.getItemAt(0)
                val text = item?.text?.toString()
                if (!text.isNullOrBlank()) {
                    LocalResponse("Clipboard contains: $text", "clipboard_read", mapOf("text" to text))
                } else {
                    val uri = item?.uri?.toString()
                    if (!uri.isNullOrBlank()) {
                        LocalResponse("Clipboard contains a link: $uri", "clipboard_read")
                    } else {
                        LocalResponse("Clipboard is empty.", "clipboard_read")
                    }
                }
            } else {
                LocalResponse("Clipboard is empty.", "clipboard_read")
            }
        } catch (e: Exception) {
            LocalResponse("Couldn't read clipboard.", "clipboard_error")
        }
    }
}