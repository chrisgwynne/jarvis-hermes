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
import androidx.core.app.ActivityCompat
import com.jarvis.hermes.LocalResponse

/**
 * SMS action handler: send SMS, text, reply.
 */
object SmsAction {

    private const val ACTION_SEND = "send"
    private const val ACTION_READ = "read"
    private const val ACTION_REPLY = "reply"

    fun requiredPermissions() = listOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CONTACTS
    )

    fun canHandle(text: String): Map<String, String>? {
        return when {
            // "text John hello" or "send message to John hello"
            Regex("""^text\s+(\w+)\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^text\s+(\w+)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_SEND, "contact" to (match?.groupValues?.get(1) ?: ""), "message" to (match?.groupValues?.get(2) ?: ""))
            }
            Regex("""^send\s+(?:message\s+)?to\s+(\w+)\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^send\s+(?:message\s+)?to\s+(\w+)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_SEND, "contact" to (match?.groupValues?.get(1) ?: ""), "message" to (match?.groupValues?.get(2) ?: ""))
            }
            Regex("""^send\s+sms\s+to\s+(\w+)\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^send\s+sms\s+to\s+(\w+)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_SEND, "contact" to (match?.groupValues?.get(1) ?: ""), "message" to (match?.groupValues?.get(2) ?: ""))
            }
            // "reply to John hi"
            Regex("""^reply\s+to\s+(\w+)\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^reply\s+to\s+(\w+)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_REPLY, "contact" to (match?.groupValues?.get(1) ?: ""), "message" to (match?.groupValues?.get(2) ?: ""))
            }
            // "reply hi" (reply to last sender)
            Regex("""^reply\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^reply\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_REPLY, "contact" to "", "message" to (match?.groupValues?.get(1) ?: ""))
            }
            // "read my messages" / "show my texts"
            Regex("""^(read|show|check)\s*(my\s*)?(messages|texts|sms)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_READ)
            }
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("SMS action unclear.", "sms_error")

        val missingPerms = requiredPermissions().filter {
            ActivityCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPerms.isNotEmpty()) {
            return LocalResponse("SMS permission not granted.", "sms_permission")
        }

        return when (action) {
            ACTION_SEND, ACTION_REPLY -> {
                val contactName = params["contact"] ?: ""
                val message = params["message"] ?: ""
                
                if (message.isBlank()) {
                    return LocalResponse("What would you like to say?", "sms_send")
                }

                val phoneNumber = resolveContactToNumber(context, contactName)
                if (phoneNumber == null && contactName.isNotBlank()) {
                    return LocalResponse("Couldn't find $contactName's number.", "sms_error")
                }

                if (phoneNumber == null) {
                    return LocalResponse("Which contact should I message?", "sms_send")
                }

                try {
                    val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.getSystemService(SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }

                    val parts = smsManager.divideMessage(message)
                    if (parts.size == 1) {
                        smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                    } else {
                        smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
                    }
                    LocalResponse("Message sent to ${contactName.ifBlank { phoneNumber }}.", "sms_sent", 
                        mapOf("to" to phoneNumber, "message" to message))
                } catch (e: Exception) {
                    // Fall back to opening SMS app
                    try {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("smsto:${phoneNumber}")
                            putExtra("sms_body", message)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                        LocalResponse("Opening messaging app.", "sms_opened")
                    } catch (e2: Exception) {
                        LocalResponse("Couldn't send message.", "sms_error")
                    }
                }
            }
            ACTION_READ -> {
                // Open default SMS app
                try {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        Intent(Intent.ACTION_MAIN).apply {
                            setPackage(Telephony.Sms.getDefaultSmsPackage(context))
                        }
                    } else {
                        Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("sms:")
                        }
                    }
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    LocalResponse("Opening messages.", "sms_read")
                } catch (e: Exception) {
                    LocalResponse("Couldn't open messages.", "sms_error")
                }
            }
            else -> LocalResponse("Unknown SMS action.", "sms_error")
        }
    }

    private fun resolveContactToNumber(context: Context, name: String): String? {
        if (name.isBlank()) return null
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getString(0)?.replace(Regex("[^0-9+]"), "")
            }
        }
        return null
    }
}
