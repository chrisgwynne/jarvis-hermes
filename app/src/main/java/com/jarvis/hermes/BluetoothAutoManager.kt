package com.jarvis.hermes

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import com.jarvis.hermes.service.VoiceService

/**
 * Bluetooth-aware auto-start manager.
 *
 * When a watched audio device (car kit / headphones) connects we kick the
 * VoiceService into a fresh conversation. When the device disconnects we end
 * the conversation if (and only if) we were the ones that started it.
 *
 * Notes on permissions:
 *  - API 31+ requires BLUETOOTH_CONNECT (runtime). Without it we skip silently.
 *  - We do not enable/disable the adapter — that's restricted to system apps
 *    on API 33+, and would surprise the user anyway.
 */
object BluetoothAutoManager {

    private const val PREFS_NAME = "jarvis_hermes"
    private const val KEY_BLUETOOTH_AUTO_ENABLED = "bluetooth_auto_enabled"
    private const val KEY_AUTO_STARTED_VIA_BLUETOOTH = "auto_started_via_bluetooth"
    private const val KEY_BLUETOOTH_DEVICE_TYPES = "bluetooth_device_types"

    const val DEVICE_TYPE_CAR = "car"
    const val DEVICE_TYPE_HEADPHONES = "headphones"
    const val DEVICE_TYPE_ALL = "all"

    private var context: Context? = null
    private var bluetoothReceiver: BroadcastReceiver? = null

    fun init(ctx: Context) {
        context = ctx.applicationContext
        registerBluetoothReceiver()
    }

    fun cleanup() {
        unregisterBluetoothReceiver()
    }

    fun isBluetoothAutoEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BLUETOOTH_AUTO_ENABLED, false)

    fun setBluetoothAutoEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BLUETOOTH_AUTO_ENABLED, enabled).apply()
    }

    fun getDeviceTypes(ctx: Context): String =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BLUETOOTH_DEVICE_TYPES, DEVICE_TYPE_ALL) ?: DEVICE_TYPE_ALL

    fun setDeviceTypes(ctx: Context, types: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_BLUETOOTH_DEVICE_TYPES, types).apply()
    }

    private fun wasAutoStartedViaBluetooth(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_STARTED_VIA_BLUETOOTH, false)

    private fun setAutoStartedViaBluetooth(ctx: Context, auto: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_STARTED_VIA_BLUETOOTH, auto).apply()
    }

    fun getBluetoothAdapter(): BluetoothAdapter? {
        val ctx = context ?: return null
        return (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    fun isBluetoothEnabled(): Boolean = try {
        getBluetoothAdapter()?.isEnabled == true
    } catch (_: SecurityException) { false }

    /** API 31+ requires BLUETOOTH_CONNECT. Older versions don't. */
    private fun hasBluetoothConnectPermission(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun registerBluetoothReceiver() {
        val ctx = context ?: return
        if (bluetoothReceiver != null) return

        bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> handleDeviceConnected(intent)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> handleDeviceDisconnected(intent)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.registerReceiver(bluetoothReceiver, filter)
            }
        } catch (_: Exception) { /* already registered, fine */ }
    }

    private fun unregisterBluetoothReceiver() {
        val ctx = context ?: return
        bluetoothReceiver?.let {
            try { ctx.unregisterReceiver(it) } catch (_: Exception) {}
            bluetoothReceiver = null
        }
    }

    private fun deviceTypeFromClass(btClass: BluetoothClass?): String? {
        if (btClass == null) return null
        // BluetoothClass.getDeviceClass() returns the minor device class.
        return when (btClass.deviceClass) {
            BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> DEVICE_TYPE_CAR
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
            BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> DEVICE_TYPE_HEADPHONES
            else -> null
        }
    }

    private fun deviceMatchesPreference(intent: Intent, ctx: Context): Boolean {
        @Suppress("DEPRECATION")
        val device: BluetoothDevice =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return false
            else
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return false

        if (!hasBluetoothConnectPermission(ctx)) return false

        val type = try {
            deviceTypeFromClass(device.bluetoothClass)
        } catch (_: SecurityException) { null } ?: return false

        return when (getDeviceTypes(ctx)) {
            DEVICE_TYPE_CAR -> type == DEVICE_TYPE_CAR
            DEVICE_TYPE_HEADPHONES -> type == DEVICE_TYPE_HEADPHONES
            else -> true // ALL
        }
    }

    private fun handleDeviceConnected(intent: Intent) {
        val ctx = context ?: return
        if (!isBluetoothAutoEnabled(ctx)) return
        if (!deviceMatchesPreference(intent, ctx)) return

        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean("conversation_active", false)) return

        setAutoStartedViaBluetooth(ctx, true)
        prefs.edit().putString("bluetooth_auto_speak", "Bluetooth connected. Conversation started.").apply()

        val svc = Intent(ctx, VoiceService::class.java).apply { action = "START" }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(svc)
            else ctx.startService(svc)
        } catch (_: Exception) { /* ignored */ }
    }

    private fun handleDeviceDisconnected(intent: Intent) {
        val ctx = context ?: return
        if (!wasAutoStartedViaBluetooth(ctx)) return
        if (!deviceMatchesPreference(intent, ctx)) return

        setAutoStartedViaBluetooth(ctx, false)
        val svc = Intent(ctx, VoiceService::class.java).apply { action = "END" }
        try { ctx.startService(svc) } catch (_: Exception) {}
    }
}
