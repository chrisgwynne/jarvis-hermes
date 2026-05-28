package com.jarvis.hermes

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Call screening helper.
 *
 * Listens for incoming-call state. On API <31 we use the legacy PHONE_STATE
 * broadcast. On API 31+ that broadcast is deprecated and only the dialer app
 * receives EXTRA_INCOMING_NUMBER, so we switch to TelephonyCallback.
 *
 * When an incoming call rings, we record the caller (looked up in contacts if
 * possible) into SharedPreferences. VoiceService picks it up and speaks it.
 *
 * Accepting/rejecting calls requires:
 *  - API 26+: ANSWER_PHONE_CALLS runtime permission for acceptRingingCall.
 *  - API 28+: ANSWER_PHONE_CALLS for endCall.
 */
object CallScreenHelper {

    private const val PREFS_NAME = "jarvis_hermes"
    private const val KEY_CALL_SCREENING_ENABLED = "call_screening_enabled"
    private const val KEY_CALL_ACTIVE = "call_active"
    private const val KEY_CALL_NUMBER = "call_number"
    private const val KEY_CALL_NAME = "call_name"
    private const val KEY_INCOMING_ANNOUNCEMENT = "incoming_call_announcement"

    private var context: Context? = null
    private var callReceiver: BroadcastReceiver? = null
    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: Any? = null // TelephonyCallback at runtime on S+
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null
    private val executor: Executor = Executors.newSingleThreadExecutor()

    fun init(ctx: Context) {
        context = ctx.applicationContext
        telephonyManager = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        registerCallListening()
    }

    fun cleanup() {
        unregisterCallListening()
    }

    fun isCallScreeningEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CALL_SCREENING_ENABLED, true)

    fun setCallScreeningEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CALL_SCREENING_ENABLED, enabled).apply()
    }

    fun isCallActive(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CALL_ACTIVE, false)

    fun answerCall(ctx: Context) {
        if (!hasPermission(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) return
        try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.acceptRingingCall()
        } catch (_: Exception) {}
    }

    fun rejectCall(ctx: Context) {
        if (!hasPermission(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) return
        try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            @Suppress("DEPRECATION")
            tm.endCall()
        } catch (_: Exception) {}
    }

    fun sendToVoicemail(ctx: Context) { rejectCall(ctx) }

    fun getPendingAnnouncement(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_INCOMING_ANNOUNCEMENT, null)

    fun clearPendingAnnouncement(ctx: Context) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_INCOMING_ANNOUNCEMENT).apply()
    }

    fun hasRequiredPermissions(ctx: Context): Boolean {
        val needed = mutableListOf(Manifest.permission.READ_PHONE_STATE)
        needed.add(Manifest.permission.ANSWER_PHONE_CALLS)
        return needed.all { hasPermission(ctx, it) }
    }

    private fun hasPermission(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    private fun registerCallListening() {
        val ctx = context ?: return
        if (!hasPermission(ctx, Manifest.permission.READ_PHONE_STATE)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallState(state, null)
                    }
                }
                telephonyCallback = cb
                telephonyManager?.registerTelephonyCallback(executor, cb)
            } catch (_: Exception) { /* permission revoked */ }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state, phoneNumber)
                }
            }
            phoneStateListener = listener
            @Suppress("DEPRECATION")
            telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }

        // Legacy broadcast also fires on older devices and gives us the number.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                        when (state) {
                            TelephonyManager.EXTRA_STATE_RINGING -> handleIncomingCall(ctx, number)
                            TelephonyManager.EXTRA_STATE_IDLE -> handleCallEnded(ctx)
                        }
                    }
                }
            }
            callReceiver = receiver
            try {
                ctx.registerReceiver(receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
            } catch (_: Exception) {}
        }
    }

    private fun unregisterCallListening() {
        val ctx = context
        callReceiver?.let {
            try { ctx?.unregisterReceiver(it) } catch (_: Exception) {}
            callReceiver = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (telephonyCallback as? TelephonyCallback)?.let {
                try { telephonyManager?.unregisterTelephonyCallback(it) } catch (_: Exception) {}
            }
            telephonyCallback = null
        } else {
            phoneStateListener?.let {
                @Suppress("DEPRECATION")
                try { telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE) } catch (_: Exception) {}
            }
            phoneStateListener = null
        }
    }

    private fun handleCallState(state: Int, phoneNumber: String?) {
        val ctx = context ?: return
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> handleIncomingCall(ctx, phoneNumber)
            TelephonyManager.CALL_STATE_IDLE -> handleCallEnded(ctx)
        }
    }

    private fun handleIncomingCall(ctx: Context, number: String?) {
        if (!isCallScreeningEnabled(ctx)) return
        val callerName = number?.let { getCallerName(ctx, it) }
        val display = callerName ?: number ?: "Unknown caller"

        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CALL_ACTIVE, true)
            .putString(KEY_CALL_NUMBER, number ?: "")
            .putString(KEY_CALL_NAME, display)
            .putString(KEY_INCOMING_ANNOUNCEMENT, "Incoming call from $display. Say answer, reject, or voicemail.")
            .apply()
    }

    private fun handleCallEnded(ctx: Context) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CALL_ACTIVE, false)
            .remove(KEY_CALL_NUMBER)
            .remove(KEY_CALL_NAME)
            .remove(KEY_INCOMING_ANNOUNCEMENT)
            .apply()
    }

    private fun getCallerName(ctx: Context, phoneNumber: String): String? {
        if (!hasPermission(ctx, Manifest.permission.READ_CONTACTS)) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val cursor: Cursor? = ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null }
    }
}
