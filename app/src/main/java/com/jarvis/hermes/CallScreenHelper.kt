package com.jarvis.hermes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Call screening helper.
 * Monitors incoming calls and announces caller via TTS.
 * Handles "answer", "reject", "voicemail" voice commands.
 */
object CallScreenHelper {

    private const val PREFS_NAME = "jarvis_hermes"
    private const val KEY_CALL_SCREENING_ENABLED = "call_screening_enabled"
    private const val KEY_LAST_INCOMING_CALL = "last_incoming_call"
    private const val KEY_CALL_ACTIVE = "call_active"
    private const val KEY_CALL_NUMBER = "call_number"
    private const val KEY_CALL_NAME = "call_name"

    private var context: Context? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyManager: TelephonyManager? = null
    private var callReceiver: BroadcastReceiver? = null
    private var isInitialized = false

    /**
     * Initialize call screening. Call from VoiceService.onCreate().
     */
    fun init(ctx: Context) {
        context = ctx.applicationContext
        registerCallReceiver()
        isInitialized = true
    }

    /**
     * Cleanup. Call from VoiceService.onDestroy().
     */
    fun cleanup() {
        unregisterCallReceiver()
        unregisterPhoneStateListener()
        isInitialized = false
    }

    /**
     * Check if call screening is enabled.
     */
    fun isCallScreeningEnabled(ctx: Context): Boolean {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CALL_SCREENING_ENABLED, true)
    }

    /**
     * Enable or disable call screening.
     */
    fun setCallScreeningEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CALL_SCREENING_ENABLED, enabled)
            .apply()
    }

    /**
     * Check if there's an active incoming call.
     */
    fun isCallActive(ctx: Context): Boolean {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CALL_ACTIVE, false)
    }

    /**
     * Answer the incoming call.
     */
    fun answerCall(ctx: Context) {
        try {
            val telecomManager = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ANSWER_PHONE_CALLS) ==
                PackageManager.PERMISSION_GRANTED) {
                telecomManager.acceptRingingCall()
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, "Could not answer call", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Reject the incoming call.
     */
    fun rejectCall(ctx: Context) {
        try {
            val telecomManager = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ANSWER_PHONE_CALLS) ==
                PackageManager.PERMISSION_GRANTED) {
                telecomManager.endCall()
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, "Could not reject call", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Send to voicemail.
     */
    fun sendToVoicemail(ctx: Context) {
        try {
            val telecomManager = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ANSWER_PHONE_CALLS) ==
                PackageManager.PERMISSION_GRANTED) {
                telecomManager.endCall()
            }
        } catch (e: Exception) {
            // Fallback: just reject
            rejectCall(ctx)
        }
    }

    /**
     * Get caller name from contacts.
     */
    fun getCallerName(ctx: Context, phoneNumber: String): String? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED) {
            return null
        }

        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val cursor: Cursor? = ctx.contentResolver.query(uri, arrayOf(
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.NUMBER
            ), null, null, null)

            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    return if (nameIndex >= 0) it.getString(nameIndex) else null
                }
            }
        } catch (e: Exception) {
            // Permission denied or other error
        }
        return null
    }

    private fun registerCallReceiver() {
        val ctx = context ?: return
        if (callReceiver != null) return

        callReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                    val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                    val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

                    when (state) {
                        TelephonyManager.EXTRA_STATE_RINGING -> {
                            handleIncomingCall(context ?: return, number)
                        }
                        TelephonyManager.EXTRA_STATE_IDLE -> {
                            handleCallEnded(context ?: return)
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        ctx.registerReceiver(callReceiver, filter)
    }

    private fun unregisterCallReceiver() {
        val ctx = context ?: return
        callReceiver?.let {
            try {
                ctx.unregisterReceiver(it)
            } catch (e: Exception) {
                // Not registered
            }
            callReceiver = null
        }
    }

    private fun registerPhoneStateListener() {
        val ctx = context ?: return
        if (phoneStateListener != null) return

        telephonyManager = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }

    private fun unregisterPhoneStateListener() {
        phoneStateListener?.let {
            telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
            phoneStateListener = null
        }
    }

    private fun handleIncomingCall(ctx: Context, number: String?) {
        if (!isCallScreeningEnabled(ctx)) return

        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_CALL_ACTIVE, true)
            .putString(KEY_CALL_NUMBER, number ?: "unknown")
            .apply()

        val callerName = number?.let { getCallerName(ctx, it) }
        val displayName = callerName ?: number ?: "Unknown caller"

        val callerInfo = if (callerName != null) {
            "$displayName"
        } else {
            displayName
        }

        // Store caller info for VoiceService to speak
        prefs.edit()
            .putString(KEY_CALL_NAME, callerInfo)
            .putString("incoming_call_announcement", "Incoming call from $callerInfo. Say answer to pick up or reject to send to voicemail.")
            .apply()
    }

    private fun handleCallEnded(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_CALL_ACTIVE, false)
            .remove(KEY_CALL_NUMBER)
            .remove(KEY_CALL_NAME)
            .remove("incoming_call_announcement")
            .apply()
    }

    /**
     * Check if we have required permissions for call screening.
     */
    fun hasRequiredPermissions(ctx: Context): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }

        return permissions.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Get pending call announcement text.
     */
    fun getPendingAnnouncement(ctx: Context): String? {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("incoming_call_announcement", null)
    }

    /**
     * Clear pending announcement after it's been spoken.
     */
    fun clearPendingAnnouncement(ctx: Context) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("incoming_call_announcement")
            .apply()
    }
}