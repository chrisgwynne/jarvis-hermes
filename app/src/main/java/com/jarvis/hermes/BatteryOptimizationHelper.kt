package com.jarvis.hermes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Battery optimization whitelist helper.
 *
 * The status is read live from PowerManager, never cached, because the user
 * can revoke the exemption at any time.
 */
object BatteryOptimizationHelper {

    fun isBatteryExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Kept as a no-op so existing call sites still compile; status is live. */
    @Suppress("UNUSED_PARAMETER")
    fun setBatteryExempt(context: Context, exempt: Boolean) { /* no-op */ }

    fun isBatteryOptimized(context: Context): Boolean = !isBatteryExempt(context)

    /**
     * Show the per-app battery optimization dialog.
     */
    fun openBatteryOptimizationSettings(activity: AppCompatActivity, requestCode: Int) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivityForResult(intent, requestCode)
        } catch (_: Exception) {
            try {
                activity.startActivityForResult(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    requestCode
                )
            } catch (_: Exception) {
                Toast.makeText(activity, "Could not open battery settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun showExemptionGrantedToast(context: Context) {
        Toast.makeText(context, "Jarvis will stay alive in the background", Toast.LENGTH_LONG).show()
    }
}
