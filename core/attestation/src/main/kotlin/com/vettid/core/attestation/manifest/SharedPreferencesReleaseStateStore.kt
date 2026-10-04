package com.vettid.core.attestation.manifest

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant
import java.util.Base64

/**
 * [ReleaseStateStore] in the app's private SharedPreferences. The data is
 * public (a signed manifest, a serial, a PCR0), so it is not encrypted; it is
 * excluded from backups by the app (`allowBackup=false`), and an attacker who
 * can rewrite app-private files has already defeated the sandbox.
 */
class SharedPreferencesReleaseStateStore(context: Context) : ReleaseStateStore {
    private val prefs: SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    override var highestSerial: Long
        get() = prefs.getLong(K_SERIAL, 0)
        set(v) = prefs.edit().putLong(K_SERIAL, v).apply()

    override var servedManifest: ByteArray?
        get() = prefs.getString(K_SERVED, null)?.let { Base64.getDecoder().decode(it) }
        set(v) = prefs.edit().putString(K_SERVED, v?.let { Base64.getEncoder().encodeToString(it) }).apply()

    override var lastFetchAt: Instant?
        get() = prefs.getLong(K_FETCH, -1).takeIf { it >= 0 }?.let { Instant.ofEpochMilli(it) }
        set(v) = prefs.edit().putLong(K_FETCH, v?.toEpochMilli() ?: -1).apply()

    override var lastRelease: Pair<String, Long>?
        get() {
            val pcr0 = prefs.getString(K_RELEASE_PCR0, null) ?: return null
            return pcr0 to prefs.getLong(K_RELEASE_NUMBER, 0)
        }
        set(v) = prefs.edit().putString(K_RELEASE_PCR0, v?.first).putLong(K_RELEASE_NUMBER, v?.second ?: 0).apply()

    private companion object {
        const val NAME = "vettid_release_state"
        const val K_SERIAL = "manifest_serial"
        const val K_SERVED = "served_manifest"
        const val K_FETCH = "manifest_fetched_at"
        const val K_RELEASE_PCR0 = "last_release_pcr0"
        const val K_RELEASE_NUMBER = "last_release_number"
    }
}
