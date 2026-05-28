package com.jarvis.hermes.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import com.jarvis.hermes.LocalResponse

/**
 * Phone action handler: call, dial, hang up, end call, speaker, mute.
 */
object PhoneAction {

    private const val ACTION_CALL = "call"
    private const val ACTION_DIAL = "dial"
    private const val ACTION_HANGUP = "hangup"
    private const val ACTION_SPEAKER_ON = "speaker_on"
    private const val ACTION_SPEAKER_OFF = "speaker_off"
    private const val ACTION_MUTE = "mute"
    private const val ACTION_UNMUTE = "unmute"

    fun requiredPermissions() = listOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE
    )

    fun canHandle(text: String): Map<String, String>? {
        return when {
            // Call or dial a number/contact
            Regex("""^(call|dial)\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^(call|dial)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_CALL, "target" to (match?.groupValues?.get(2) ?: ""))
            }
            // Hang up / end call
            Regex("""^(hang\s*up|end\s*call|reject\s*call)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_HANGUP)
            }
            // Speaker on
            Regex("""^speaker\s*(on|off)?$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^speaker\s*(on|off)?$""", RegexOption.IGNORE_CASE).find(text)
                val toggle = match?.groupValues?.get(1)?.lowercase() ?: "toggle"
                if (toggle == "on") mapOf("action" to ACTION_SPEAKER_ON)
                else if (toggle == "off") mapOf("action" to ACTION_SPEAKER_OFF)
                else mapOf("action" to ACTION_SPEAKER_ON) // default toggle
            }
            // Mute
            Regex("""^(mute|unmute| silence)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to if (text.contains("un")) ACTION_UNMUTE else ACTION_MUTE)
            }
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("Phone action unclear.", "phone_error")

        // Check permissions first
        if (requiredPermissions().any {
            ActivityCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }) {
            return LocalResponse("Phone permission not granted.", "phone_permission")
        }

        return when (action) {
            ACTION_CALL -> {
                val target = params["target"] ?: ""
                if (target.isBlank()) {
                    LocalResponse("Who would you like to call?", "phone_call")
                } else {
                    val number = target.replace(Regex("[^0-9+]"), "")
                    try {
                        val intent = Intent(Intent.ACTION_CALL).apply {
                            data = Uri.parse("tel:$number")
                        }
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                        LocalResponse("Calling $target.", "phone_call", mapOf("number" to number))
                    } catch (e: Exception) {
                        // Fall back to dial (doesn't require CALL_PHONE permission)
                        try {
                            val intent = Intent(Intent.ACTION_DIAL).apply {
                                data = Uri.parse("tel:$number")
                            }
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                            LocalResponse("Opening dialer for $target.", "phone_dial", mapOf("number" to number))
                        } catch (e2: Exception) {
                            LocalResponse("Couldn't make call.", "phone_error")
                        }
                    }
                }
            }
            ACTION_HANGUP -> {
                try {
                    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        telecomManager.endCall()
                        LocalResponse("Call ended.", "phone_hangup")
                    } else {
                        LocalResponse("End call not supported on this Android version.", "phone_hangup")
                    }
                } catch (e: Exception) {
                    LocalResponse("Couldn't end call.", "phone_hangup")
                }
            }
            ACTION_SPEAKER_ON, ACTION_SPEAKER_OFF -> {
                // Speaker toggle requires AudioManager
                try {
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    if (action == ACTION_SPEAKER_ON) {
                        audioManager.setSpeakerphoneOn(true)
                    } else {
                        audioManager.setSpeakerphoneOn(false)
                    }
                    LocalResponse(
                        if (action == ACTION_SPEAKER_ON) "Speaker on." else "Speaker off.",
                        "phone_speaker"
                    )
                } catch (e: Exception) {
                    LocalResponse("Speaker control unavailable.", "phone_error")
                }
            }
            ACTION_MUTE -> {
                try {
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.isMicrophoneMute()
                    audioManager.setMicrophoneMute(true)
                    LocalResponse("Muted.", "phone_mute")
                } catch (e: Exception) {
                    LocalResponse("Mute unavailable.", "phone_error")
                }
            }
            ACTION_UNMUTE -> {
                try {
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.setMicrophoneMute(false)
                    LocalResponse("Unmuted.", "phone_unmute")
                } catch (e: Exception) {
                    LocalResponse("Unmute unavailable.", "phone_error")
                }
            }
            else -> LocalResponse("Unknown phone action.", "phone_error")
        }
    }
}
