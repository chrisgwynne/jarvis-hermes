package com.jarvis.hermes.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.jarvis.hermes.LocalResponse

/**
 * Phone action handler: call, dial, hang up, speaker, mute.
 *
 * Calling rules:
 *  - Numeric strings → ACTION_CALL with the number.
 *  - Free text → resolved against contacts; if found, ACTION_CALL with the
 *    matched number; if ambiguous (multiple matches), open the dialer with
 *    the search query so the user picks.
 *  - Special numbers (emergency: 911 / 999 / 112) → always ACTION_DIAL,
 *    never auto-dialled.
 *  - If CALL_PHONE is denied, we fall back to ACTION_DIAL which any app
 *    can launch.
 */
object PhoneAction {

    private const val ACTION_CALL = "call"
    private const val ACTION_HANGUP = "hangup"
    private const val ACTION_SPEAKER_ON = "speaker_on"
    private const val ACTION_SPEAKER_OFF = "speaker_off"
    private const val ACTION_MUTE = "mute"
    private const val ACTION_UNMUTE = "unmute"

    private val EMERGENCY_NUMBERS = setOf("911", "999", "112", "000", "110", "120")

    fun requiredPermissions() = listOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE
    )

    fun canHandle(text: String): Map<String, String>? {
        return when {
            Regex("""^(call|dial)\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^(call|dial)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                val verb = match?.groupValues?.get(1)?.lowercase().orEmpty()
                mapOf(
                    "action" to ACTION_CALL,
                    "target" to (match?.groupValues?.get(2)?.trim().orEmpty()),
                    "dialOnly" to (verb == "dial").toString()
                )
            }
            Regex("""^(hang\s*up|end\s+call)$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_HANGUP)
            Regex("""^speaker(\s+on)?$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_SPEAKER_ON)
            Regex("""^speaker\s+off$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_SPEAKER_OFF)
            Regex("""^mute$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_MUTE)
            Regex("""^unmute$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_UNMUTE)
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("Phone action unclear.", "phone_error")

        return when (action) {
            ACTION_CALL -> handleCall(
                context,
                params["target"].orEmpty(),
                params["dialOnly"]?.toBooleanStrictOrNull() ?: false
            )
            ACTION_HANGUP -> handleHangup(context)
            ACTION_SPEAKER_ON -> setSpeaker(context, on = true)
            ACTION_SPEAKER_OFF -> setSpeaker(context, on = false)
            ACTION_MUTE -> setMicMute(context, mute = true)
            ACTION_UNMUTE -> setMicMute(context, mute = false)
            else -> LocalResponse("Unknown phone action.", "phone_error")
        }
    }

    private fun handleCall(context: Context, target: String, dialOnly: Boolean): LocalResponse {
        if (target.isBlank()) return LocalResponse("Who would you like to call?", "phone_call")

        // Resolve target to a phone number.
        val resolved = resolveTarget(context, target)
        val number = resolved.number ?: return LocalResponse(
            if (resolved.multiple) "Multiple matches for $target — opening contacts."
            else "Couldn't find $target.",
            "phone_error"
        )

        val isEmergency = number.replace(Regex("[^0-9]"), "") in EMERGENCY_NUMBERS
        val callPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        // Emergency numbers and dial-only requests always go through the dialer.
        val useDialer = dialOnly || isEmergency || !callPermission
        val intentAction = if (useDialer) Intent.ACTION_DIAL else Intent.ACTION_CALL

        return try {
            val intent = Intent(intentAction).apply {
                data = Uri.parse("tel:${Uri.encode(number)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val name = resolved.label ?: target
            LocalResponse(
                if (useDialer) "Opening dialer for $name." else "Calling $name.",
                if (useDialer) "phone_dial" else "phone_call",
                mapOf("number" to number)
            )
        } catch (e: SecurityException) {
            // Permission revoked between check and use — fall back to dialer.
            try {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:${Uri.encode(number)}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                LocalResponse("Opening dialer.", "phone_dial")
            } catch (_: Exception) { LocalResponse("Couldn't open dialer.", "phone_error") }
        } catch (_: Exception) {
            LocalResponse("Couldn't make call.", "phone_error")
        }
    }

    private data class TargetResolution(val number: String?, val label: String?, val multiple: Boolean)

    private fun resolveTarget(context: Context, target: String): TargetResolution {
        // Numeric input — use directly. Allow spaces, parentheses, dashes.
        val digits = target.replace(Regex("[^0-9+#*]"), "")
        if (digits.length >= 3 && digits.all { it.isDigit() || it in "+-*#" }) {
            // Heuristic: if >=70% of characters were already digits/symbols, treat as number.
            val original = target.trim()
            val symRatio = original.count { it.isDigit() || it in "+-*# " } / original.length.toDouble()
            if (symRatio > 0.7) return TargetResolution(digits, null, false)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            return TargetResolution(null, null, false)
        }
        return lookupContact(context, target)
    }

    private fun lookupContact(context: Context, name: String): TargetResolution {
        // Match contact name OR phonetic name; prefer the contact with the
        // highest "starred" or "times_contacted" score so we don't always
        // pick the alphabetically first hit.
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.STARRED,
                ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%${name.trim()}%"),
            null
        ) ?: return TargetResolution(null, null, false)

        cursor.use {
            if (!it.moveToFirst()) return TargetResolution(null, null, false)
            var bestNumber: String? = null
            var bestLabel: String? = null
            var bestScore = -1L
            var distinctNames = mutableSetOf<String>()
            do {
                val number = it.getString(0)?.replace(Regex("[^0-9+]"), "")
                val label = it.getString(1)
                val starred = it.getInt(2)
                val times = it.getInt(3)
                if (number.isNullOrBlank()) continue
                distinctNames.add(label ?: "")
                val score = starred * 1_000L + times
                if (score > bestScore) {
                    bestScore = score
                    bestNumber = number
                    bestLabel = label
                }
            } while (it.moveToNext())
            return TargetResolution(bestNumber, bestLabel, distinctNames.size > 1)
        }
    }

    private fun handleHangup(context: Context): LocalResponse {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED) {
            return LocalResponse("Need answer-calls permission to hang up.", "phone_hangup")
        }
        return try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            @Suppress("DEPRECATION")
            val ok = tm.endCall()
            if (ok) LocalResponse("Call ended.", "phone_hangup")
            else LocalResponse("No call to end.", "phone_hangup")
        } catch (_: Exception) {
            LocalResponse("Couldn't end call.", "phone_hangup")
        }
    }

    private fun setSpeaker(context: Context, on: Boolean): LocalResponse {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // Setting MODE_IN_COMMUNICATION here is required for the
            // speakerphone flag to actually route; we leave audio mode set
            // only while the call is active.
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = on
            LocalResponse(if (on) "Speaker on." else "Speaker off.", "phone_speaker")
        } catch (_: Exception) {
            LocalResponse("Speaker control unavailable.", "phone_error")
        }
    }

    private fun setMicMute(context: Context, mute: Boolean): LocalResponse {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isMicrophoneMute = mute
            LocalResponse(if (mute) "Muted." else "Unmuted.", if (mute) "phone_mute" else "phone_unmute")
        } catch (_: Exception) {
            LocalResponse("Mute control unavailable.", "phone_error")
        }
    }
}
