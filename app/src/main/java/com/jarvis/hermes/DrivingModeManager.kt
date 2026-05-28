package com.jarvis.hermes

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Context

/**
 * Driving-mode manager.
 *
 * Driving mode is entered when:
 *  - A car-class Bluetooth device connects (HFP, car audio), OR
 *  - The user explicitly says "driving mode on".
 *
 * Effects when active:
 *  - Notification filter is *more* restrictive (skip more apps).
 *  - Speakerphone forced on for calls.
 *  - TTS speech rate is normalised (always 1.0 — no slow-down for clarity).
 *  - Sensitive notifications never read aloud (regardless of PrivacyFilter).
 *  - Confirmation prompts are spoken louder (uses TTS volume not media).
 *
 * Detection is event-driven — `notifyDeviceConnected` is called from
 * BluetoothAutoManager whenever an ACL_CONNECTED arrives.
 */
object DrivingModeManager {

    private const val PREFS = "jarvis_hermes"
    private const val KEY_DRIVING_MODE_ACTIVE = "driving_mode_active"
    private const val KEY_DRIVING_MODE_FORCED = "driving_mode_forced"

    fun isActive(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DRIVING_MODE_ACTIVE, false) ||
            prefs.getBoolean(KEY_DRIVING_MODE_FORCED, false)
    }

    fun setForced(ctx: Context, forced: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DRIVING_MODE_FORCED, forced).apply()
    }

    /** Called when a Bluetooth device connects/disconnects. */
    fun notifyDeviceConnected(ctx: Context, btClass: BluetoothClass?, connected: Boolean) {
        if (btClass == null) return
        val isCar = btClass.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                    btClass.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
        if (!isCar) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DRIVING_MODE_ACTIVE, connected).apply()
    }

    /**
     * "Safe" notification policy: in driving mode, only read aloud
     * sender names, never message bodies — and skip anything matching
     * PrivacyFilter or known noisy apps.
     */
    fun sanitiseNotification(ctx: Context, packageName: String, sender: String, message: String): String? {
        if (PrivacyFilter.isSensitive(packageName, sender, message)) return null
        if (!isActive(ctx)) return "$sender: $message"
        // In driving mode: name only, no body.
        return "Message from $sender."
    }
}
