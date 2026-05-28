package com.jarvis.hermes.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.jarvis.hermes.LocalResponse

/**
 * SMS action handler.
 *
 * Contact resolution prefers contacts with the highest "starred + times
 * contacted" score so common contacts win over alphabetically-first matches.
 * For ambiguous resolutions we open the default SMS app pre-populated with
 * the contact, rather than guessing.
 */
object SmsAction {

    private const val ACTION_SEND = "send"
    private const val ACTION_READ = "read"
    private const val ACTION_REPLY = "reply"

    fun requiredPermissions() = listOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS
    )

    fun canHandle(text: String): Map<String, String>? {
        // Greedy contact match — supports "text John Smith hello", "text mum
        // running late". We split at "saying", "that", "to say" if present,
        // otherwise take the last word as the start of the message.
        return when {
            Regex("""^(text|send\s+(?:sms|message)\s+to|message)\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^(text|send\s+(?:sms|message)\s+to|message)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                val rest = m?.groupValues?.get(2)?.trim().orEmpty()
                val (contact, message) = splitContactAndMessage(rest)
                mapOf("action" to ACTION_SEND, "contact" to contact, "message" to message)
            }
            Regex("""^reply\s+to\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^reply\s+to\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                val rest = m?.groupValues?.get(1)?.trim().orEmpty()
                val (contact, message) = splitContactAndMessage(rest)
                mapOf("action" to ACTION_REPLY, "contact" to contact, "message" to message)
            }
            Regex("""^reply\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^reply\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_REPLY, "contact" to "", "message" to (m?.groupValues?.get(1) ?: ""))
            }
            Regex("""^(read|show|check)\s*(my\s*)?(messages?|texts?|sms)$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_READ)
            else -> null
        }
    }

    /**
     * Heuristic: split "John Smith saying running late" → contact, body. We
     * look for keywords ("saying", "that", "to say", ":") and fall back to
     * the first 1-3 words being the contact name.
     */
    private fun splitContactAndMessage(text: String): Pair<String, String> {
        if (text.isBlank()) return "" to ""
        val keywords = listOf(" saying ", " saying:", " that ", " to say ", ": ", " - ")
        for (kw in keywords) {
            val idx = text.indexOf(kw, ignoreCase = true)
            if (idx > 0) {
                return text.substring(0, idx).trim() to text.substring(idx + kw.length).trim()
            }
        }
        // No keyword — assume first 1-3 words is the contact, rest is message.
        val tokens = text.split(" ")
        if (tokens.size <= 1) return text to ""
        // Use the longest leading run that's all "name-like" (alpha/space).
        var contactEnd = 1
        for (i in 1 until minOf(tokens.size, 4)) {
            if (tokens[i].all { it.isLetter() || it == '\'' || it == '-' }) contactEnd = i + 1
            else break
        }
        val contact = tokens.take(contactEnd).joinToString(" ")
        val message = tokens.drop(contactEnd).joinToString(" ")
        return contact to message
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("SMS action unclear.", "sms_error")

        // SEND requires SEND_SMS. READ doesn't (it opens the SMS app).
        if (action == ACTION_SEND || action == ACTION_REPLY) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
                // Fall back to opening the SMS composer.
                return openComposer(context, params["contact"].orEmpty(), params["message"].orEmpty())
            }
        }

        return when (action) {
            ACTION_SEND, ACTION_REPLY -> sendSms(
                context,
                params["contact"].orEmpty(),
                params["message"].orEmpty()
            )
            ACTION_READ -> openMessagesApp(context)
            else -> LocalResponse("Unknown SMS action.", "sms_error")
        }
    }

    private fun sendSms(context: Context, contactName: String, message: String): LocalResponse {
        if (message.isBlank()) return LocalResponse("What's the message?", "sms_send")

        val phone = resolveContactToNumber(context, contactName)
        if (phone == null) {
            return if (contactName.isNotBlank()) openComposer(context, contactName, message)
            else LocalResponse("Which contact?", "sms_send")
        }

        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) smsManager.sendTextMessage(phone, null, message, null, null)
            else smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            LocalResponse(
                "Message sent to ${contactName.ifBlank { phone }}.",
                "sms_sent",
                mapOf("to" to phone, "message" to message)
            )
        } catch (_: SecurityException) {
            openComposer(context, contactName, message)
        } catch (_: Exception) {
            openComposer(context, contactName, message)
        }
    }

    private fun openComposer(context: Context, contact: String, message: String): LocalResponse {
        return try {
            val phone = resolveContactToNumber(context, contact) ?: ""
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$phone")
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening messages — review then send.", "sms_opened")
        } catch (_: Exception) {
            LocalResponse("Couldn't open messages.", "sms_error")
        }
    }

    private fun openMessagesApp(context: Context): LocalResponse {
        return try {
            val pkg = Telephony.Sms.getDefaultSmsPackage(context)
            val intent = if (pkg != null) {
                context.packageManager.getLaunchIntentForPackage(pkg)
            } else {
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
            } ?: return LocalResponse("No SMS app installed.", "sms_error")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            LocalResponse("Opening messages.", "sms_read")
        } catch (_: Exception) {
            LocalResponse("Couldn't open messages.", "sms_error")
        }
    }

    private fun resolveContactToNumber(context: Context, name: String): String? {
        if (name.isBlank()) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return null
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.STARRED,
                ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%${name.trim()}%"),
            null
        ) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            var bestNumber: String? = null
            var bestScore = -1L
            do {
                val n = it.getString(0)?.replace(Regex("[^0-9+]"), "") ?: continue
                val score = it.getInt(1) * 1_000L + it.getInt(2)
                if (score > bestScore) { bestScore = score; bestNumber = n }
            } while (it.moveToNext())
            return bestNumber
        }
    }
}
