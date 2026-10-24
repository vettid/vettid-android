package com.vettid.core.data.vault

import android.content.Context
import com.vettid.core.keystore.DeviceKeys
import com.vettid.core.keystore.KeySlot
import com.vettid.core.keystore.SeedWrapKey
import com.vettid.core.keystore.SharedPreferencesWrappedKeyStore
import java.io.File

/**
 * (ANDROID-PLAN 0.1.78 "A phone that could not tell") Where the phone's device identities live. A phone holds one identity
 * (slot 0: the names every earlier build used, unchanged) and, only while a reused-key recovery is undecided, a second
 * (slot 1). Each slot has its own wrapped device keys (identity, KEM and relay seeds) and its own device state file (its
 * keyring, the alternate-channel floors `min_header_seq` / `min_state_seq`, its recovery record with the `recovery_id`).
 *
 * Which slot the app is using is one number, [active], written with `commit()`: switching identity is one atomic write, and
 * a crash at any point leaves both slots whole. Nothing is copied or moved between slots, so an identity is never lost to a
 * half-finished swap. [clear] removes a slot only when the caller says the vault answered `ok` and the pairing completed.
 */
internal class IdentitySlots(private val app: Context, private val dir: File) {
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The slot in use. A value that is not a slot reads as 0. */
    var active: Int
        get() = prefs.getInt(ACTIVE, 0).takeIf { it in SLOTS } ?: 0
        set(v) {
            require(v in SLOTS)
            check(prefs.edit().putInt(ACTIVE, v).commit()) { "identity slot not written" }
        }

    fun other(slot: Int): Int = if (slot == 0) 1 else 0

    fun keys(slot: Int): DeviceKeys = DeviceKeys(SeedWrapKey.wrapper(), SharedPreferencesWrappedKeyStore(app, prefsName(slot)))

    fun file(slot: Int): File = File(dir, if (slot == 0) FILE_0 else FILE_1)

    /** Whether [slot] holds keys. */
    fun exists(slot: Int): Boolean = KeySlot.entries.all { keys(slot).has(it) }

    /** Removes [slot]'s keys and state file. Never the active one (the caller switches first). */
    fun clear(slot: Int) {
        check(slot != active) { "the active identity is not cleared" }
        keys(slot).clear()
        file(slot).delete()
    }

    /** Every slot but 0 is removed and 0 is active again (a full forget). */
    fun resetToSingle() {
        if (active != 0) active = 0
        keys(1).clear()
        file(1).delete()
    }

    private fun prefsName(slot: Int) = if (slot == 0) SharedPreferencesWrappedKeyStore.DEFAULT_NAME else PREFS_1

    companion object {
        private const val PREFS = "vettid_identity"
        private const val ACTIVE = "active"
        private const val PREFS_1 = "vettid_device_keys_1"
        const val FILE_0 = "vault-device.bin"
        const val FILE_1 = "vault-device-1.bin"
        private val SLOTS = 0..1
    }
}
