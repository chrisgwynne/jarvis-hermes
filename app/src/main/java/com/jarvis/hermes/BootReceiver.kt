package com.jarvis.hermes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jarvis.hermes.service.VoiceService

/**
 * Re-arms wake-word listening after a reboot or app upgrade so the user
 * doesn't have to manually re-enable the assistant.
 *
 * We only auto-start the service if wake-word mode is enabled, otherwise the
 * user expects to launch it from the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val receivedAction = intent.action ?: return
        if (receivedAction != Intent.ACTION_BOOT_COMPLETED &&
            receivedAction != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            receivedAction != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = context.getSharedPreferences("jarvis_hermes", Context.MODE_PRIVATE)
        val wakeWord = prefs.getBoolean("wake_word_mode", false)
        if (!wakeWord) return

        val svc = Intent(context, VoiceService::class.java).apply { action = "START" }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (_: Exception) { /* ignored — user can re-arm manually */ }
    }
}
