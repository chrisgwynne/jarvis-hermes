package com.jarvis.hermes

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted SharedPreferences for sensitive data (API keys, session
 * transcripts).
 *
 * Falls back to plain SharedPreferences if the secure path throws (e.g.
 * a corrupted keystore — happens after some factory-reset scenarios).
 * That fallback is intentional: we'd rather degrade than crash on
 * startup, but we log so the user can re-enter the API key.
 */
object EncryptedPrefs {

    private const val FILE_NAME = "jarvis_hermes_secure"
    @Volatile private var instance: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val prefs = try {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                android.util.Log.w("EncryptedPrefs",
                    "Falling back to plain prefs: ${e.message}")
                context.applicationContext
                    .getSharedPreferences(FILE_NAME + "_fallback", Context.MODE_PRIVATE)
            }
            instance = prefs
            return prefs
        }
    }

    /**
     * One-time migration from plain prefs → encrypted prefs for sensitive
     * keys. Safe to call repeatedly — the second call is a no-op because
     * the source keys are removed after the first.
     */
    fun migrateFromPlain(context: Context, plainPrefsName: String, keys: List<String>) {
        val plain = context.applicationContext
            .getSharedPreferences(plainPrefsName, Context.MODE_PRIVATE)
        val secure = get(context)
        val editPlain = plain.edit()
        val editSecure = secure.edit()
        var migrated = 0
        for (key in keys) {
            val v = plain.all[key] ?: continue
            when (v) {
                is String -> editSecure.putString(key, v)
                is Boolean -> editSecure.putBoolean(key, v)
                is Int -> editSecure.putInt(key, v)
                is Long -> editSecure.putLong(key, v)
                is Float -> editSecure.putFloat(key, v)
                else -> continue
            }
            editPlain.remove(key)
            migrated++
        }
        if (migrated > 0) {
            editSecure.apply()
            editPlain.apply()
        }
    }
}
