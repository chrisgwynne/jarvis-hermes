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
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.jarvis.hermes.service.VoiceService

/**
 * Bluetooth-aware mode manager.
 * Auto-starts conversation when car kit or headphones connect.
 */
object BluetoothAutoManager {

    private const val PREFS_NAME = "jarvis_hermes"
    private const val KEY_BLUETOOTH_AUTO_ENABLED = "bluetooth_auto_enabled"
    private const val KEY_AUTO_STARTED_VIA_BLUETOOTH = "auto_started_via_bluetooth"
    private const val KEY_BLUETOOTH_DEVICE_TYPES = "bluetooth_device_types"

    // Device type constants
    const val DEVICE_TYPE_CAR = "car"
    const val DEVICE_TYPE_HEADPHONES = "headphones"
    const val DEVICE_TYPE_ALL = "all"

    private var context: Context? = null
    private var bluetoothReceiver: BroadcastReceiver? = null
    private var isInitialized = false

    /**
     * Initialize Bluetooth manager. Call from VoiceService.onCreate().
     */
    fun init(ctx: Context) {
        context = ctx.applicationContext
        registerBluetoothReceiver()
        isInitialized = true
    }

    /**
     * Cleanup. Call from VoiceService.onDestroy().
     */
    fun cleanup() {
        unregisterBluetoothReceiver()
        isInitialized = false
    }

    /**
     * Check if Bluetooth auto-start is enabled.
     */
    fun isBluetoothAutoEnabled(ctx: Context): Boolean {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BLUETOOTH_AUTO_ENABLED, false)
    }

    /**
     * Enable or disable Bluetooth auto-start.
     */
    fun setBluetoothAutoEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BLUETOOTH_AUTO_ENABLED, enabled)
            .apply()
    }

    /**
     * Get which device types trigger auto-start (car, headphones, or all).
     */
    fun getDeviceTypes(ctx: Context): String {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BLUETOOTH_DEVICE_TYPES, DEVICE_TYPE_ALL) ?: DEVICE_TYPE_ALL
    }

    /**
     * Set which device types trigger auto-start.
     */
    fun setDeviceTypes(ctx: Context, types: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BLUETOOTH_DEVICE_TYPES, types)
            .apply()
    }

    /**
     * Check if conversation was auto-started via Bluetooth.
     */
    fun wasAutoStartedViaBluetooth(ctx: Context): Boolean {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_STARTED_VIA_BLUETOOTH, false)
    }

    /**
     * Set whether conversation was auto-started via Bluetooth.
     */
    fun setAutoStartedViaBluetooth(ctx: Context, auto: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_STARTED_VIA_BLUETOOTH, auto)
            .apply()
    }

    /**
     * Get Bluetooth adapter.
     */
    fun getBluetoothAdapter(): BluetoothAdapter? {
        val ctx = context ?: return null
        val bluetoothManager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bluetoothManager?.adapter
    }

    /**
     * Check if Bluetooth is enabled.
     */
    fun isBluetoothEnabled(): Boolean {
        return getBluetoothAdapter()?.isEnabled == true
    }

    /**
     * Check if we have Bluetooth connect permission.
     */
    fun hasBluetoothPermission(): Boolean {
        val ctx = context ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun registerBluetoothReceiver() {
        val ctx = context ?: return
        if (bluetoothReceiver != null) return
        if (!hasBluetoothPermission()) return

        bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
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

        ctx.registerReceiver(bluetoothReceiver, filter)
    }

    private fun unregisterBluetoothReceiver() {
        val ctx = context ?: return
        bluetoothReceiver?.let {
            try {
                ctx.unregisterReceiver(it)
            } catch (e: Exception) {
                // Not registered
            }
            bluetoothReceiver = null
        }
    }

    private fun handleDeviceConnected(intent: Intent) {
        val ctx = context ?: return

        if (!isBluetoothAutoEnabled(ctx)) return
        if (!hasBluetoothPermission()) return

        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            ?: return

        val deviceClass = device.bluetoothClass
        val majorClass = deviceClass?.majorDeviceClass

        val enabledTypes = getDeviceTypes(ctx)
        val isCarKit = isCarKitDevice(deviceClass)
        val isHeadphones = isHeadphonesDevice(deviceClass)

        // Check if this device type is enabled
        val shouldTrigger = when (enabledTypes) {
            DEVICE_TYPE_CAR -> isCarKit
            DEVICE_TYPE_HEADPHONES -> isHeadphones
            else -> isCarKit || isHeadphones // ALL
        }

        if (!shouldTrigger) return

        // Check if conversation is not already active and not in wake word mode
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val conversationActive = prefs.getBoolean("conversation_active", false)
        val wakeWordMode = prefs.getBoolean("wake_word_mode", false)

        if (conversationActive || wakeWordMode) return

        // Auto-start conversation
        setAutoStartedViaBluetooth(ctx, true)
        speak(ctx, "Conversation started.")
    }

    private fun handleDeviceDisconnected(intent: Intent) {
        val ctx = context ?: return

        if (!wasAutoStartedViaBluetooth(ctx)) return

        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            ?: return

        val deviceClass = device.bluetoothClass
        val enabledTypes = getDeviceTypes(ctx)
        val isCarKit = isCarKitDevice(deviceClass)
        val isHeadphones = isHeadphonesDevice(deviceClass)

        val shouldTrigger = when (enabledTypes) {
            DEVICE_TYPE_CAR -> isCarKit
            DEVICE_TYPE_HEADPHONES -> isHeadphones
            else -> isCarKit || isHeadphones
        }

        if (!shouldTrigger) return

        // Auto-stop conversation
        speak(ctx, "Conversation paused.")
        setAutoStartedViaBluetooth(ctx, false)
    }

    private fun isCarKitDevice(btClass: BluetoothClass?): Boolean {
        // Car kit typically uses HFP or SAP profile
        val classes = btClass?.deviceClasses ?: emptySet()
        return classes.any {
            it == BluetoothClass.Device.PHONE_SMART ||
            it == BluetoothClass.Device.PHONE_UNCATEGORIZED
        } && btClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE
    }

    private fun isHeadphonesDevice(btClass: BluetoothClass?): Boolean {
        // A2DP Sink or Headphones
        val classes = btClass?.deviceClasses ?: emptySet()
        return classes.any {
            it == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES ||
            it == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET
        }
    }

    private fun speak(ctx: Context, text: String) {
        // Write to SharedPreferences for VoiceService to pick up
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("bluetooth_auto_speak", text)
            .apply()
    }
}