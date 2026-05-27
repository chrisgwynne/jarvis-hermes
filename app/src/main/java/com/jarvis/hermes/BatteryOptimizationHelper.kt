package com.jarvis.hermes

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Battery optimization whitelist helper.
 * Detects if app is battery-optimized and guides user to disable it.
 */
object BatteryOptimizationHelper {

    private const val PREFS_NAME = "jarvis_hermes"
    private const val KEY_BATTERY_EXEMPT = "battery_exempt"
    private const val ACTION_BATTERY_OPTIMIZATION_CHANGED = "android.intent.action.BATTERY_OPTIMIZATION_STATE_CHANGED"

    /**
     * Check if the app is battery-exempt (ignoring battery optimization).
     */
    fun isBatteryExempt(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BATTERY_EXEMPT, false)) return true

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Save that battery exemption was granted.
     */
    fun setBatteryExempt(context: Context, exempt: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BATTERY_EXEMPT, exempt)
            .apply()
    }

    /**
     * Check if battery is currently optimized.
     */
    fun isBatteryOptimized(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Open battery optimization settings.
     */
    fun openBatteryOptimizationSettings(activity: AppCompatActivity, requestCode: Int) {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            activity.startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            // Fallback to general battery settings
            val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            activity.startActivityForResult(intent, requestCode)
        }
    }

    /**
     * Register receiver for battery optimization state changes.
     */
    fun registerBatteryOptimizationReceiver(context: Context, receiver: android.content.BroadcastReceiver) {
        val filter = IntentFilter(ACTION_BATTERY_OPTIMIZATION_CHANGED)
        context.registerReceiver(receiver, filter)
    }

    /**
     * Unregister receiver.
     */
    fun unregisterBatteryOptimizationReceiver(context: Context, receiver: android.content.BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            // Not registered
        }
    }

    /**
     * Show toast confirming battery exemption granted.
     */
    fun showExemptionGrantedToast(context: Context) {
        Toast.makeText(context, "Jarvis will stay alive in the background", Toast.LENGTH_LONG).show()
    }
}