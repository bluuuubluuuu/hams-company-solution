package com.klk.hams.provisioning

import android.content.Context
import android.provider.Settings
import com.klk.hams.AppConfig

fun shouldAutoPush(isProvisioned: Boolean, pending: Int): Boolean =
    isProvisioned && pending > 0

/**
 * Holds the device's claimed Wialon `unique_id`. Storage is injected so the
 * core behaviour is JVM-testable without Android.
 */
class ProvisioningStore(private val store: KeyValueStore) {

    interface KeyValueStore {
        fun getString(key: String): String?
        fun putString(key: String, value: String)
        fun remove(key: String)
    }

    fun uniqueIdOrNull(): String? = store.getString(KEY_UNIQUE_ID)?.takeIf { it.isNotBlank() }

    fun isProvisioned(): Boolean = uniqueIdOrNull() != null

    fun save(uniqueId: String) {
        store.putString(KEY_UNIQUE_ID, uniqueId)
    }

    fun clear() {
        store.remove(KEY_UNIQUE_ID)
    }

    /** Stored id if provisioned, else the BuildConfig fallback for dev/test builds. */
    fun resolveUniqueId(): String = uniqueIdOrNull() ?: AppConfig.DEVICE_UNIQUE_ID

    /** Provider form for call sites that must read the latest stored id lazily. */
    fun uniqueIdProvider(): () -> String = { resolveUniqueId() }

    companion object {
        const val PREFS_NAME: String = "hams_prefs"
        private const val KEY_UNIQUE_ID: String = "device_unique_id"

        fun fromContext(context: Context): ProvisioningStore =
            ProvisioningStore(SharedPrefsKeyValueStore(context))

        /** ANDROID_ID; null/blank on rare defective or cloned devices. */
        fun deviceFingerprint(context: Context): String? =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() }
    }

    class SharedPrefsKeyValueStore(context: Context) : KeyValueStore {
        private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun getString(key: String): String? = prefs.getString(key, null)

        override fun putString(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }

        override fun remove(key: String) {
            prefs.edit().remove(key).apply()
        }
    }
}
