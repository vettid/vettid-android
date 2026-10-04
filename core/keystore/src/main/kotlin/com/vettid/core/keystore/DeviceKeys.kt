package com.vettid.core.keystore

import android.content.Context
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.hpke.KemPrivateKey
import java.util.Base64

/** Where wrapped seeds are kept. */
interface WrappedKeyStore {
    fun get(slot: KeySlot): ByteArray?

    fun put(slot: KeySlot, blob: ByteArray)

    fun remove(slot: KeySlot)
}

/** Wrapped seeds in app-private SharedPreferences (ciphertext only; the wrapping key is in the Keystore). */
class SharedPreferencesWrappedKeyStore(context: Context) : WrappedKeyStore {
    private val prefs = context.getSharedPreferences("vettid_device_keys", Context.MODE_PRIVATE)

    override fun get(slot: KeySlot): ByteArray? = prefs.getString(slot.wire, null)?.let { Base64.getDecoder().decode(it) }

    override fun put(slot: KeySlot, blob: ByteArray) {
        // commit(): a key that is generated but not stored would orphan the device's identity.
        check(prefs.edit().putString(slot.wire, Base64.getEncoder().encodeToString(blob)).commit())
    }

    override fun remove(slot: KeySlot) {
        prefs.edit().remove(slot.wire).commit()
    }
}

/**
 * The app's device keys (§3.2): relay key and identity key (Ed25519) and the
 * static KEM key, each generated from a fresh random seed, stored only
 * wrapped under the Keystore ([SeedWrapper]), and unwrapped on use. The three
 * keys are distinct by construction (independent seeds), as §3.2 requires.
 */
class DeviceKeys(private val wrapper: SeedWrapper, private val store: WrappedKeyStore) {
    /** Generates and stores a new seed for [slot], replacing any existing one. */
    fun generate(slot: KeySlot) {
        val seed = when (slot) {
            KeySlot.KEM -> KemPrivateKey.generate().let { k -> k.seed().also { k.destroy() } }
            else -> Ed25519PrivateKey.generate().let { k -> k.seed().also { k.destroy() } }
        }
        try {
            store.put(slot, wrapper.wrap(slot, seed))
        } finally {
            Bytes.wipe(seed)
        }
    }

    fun has(slot: KeySlot): Boolean = store.get(slot) != null

    /** The relay or identity key. The caller destroys it when done. */
    fun ed25519(slot: KeySlot): Ed25519PrivateKey {
        require(slot != KeySlot.KEM)
        return wrapper.unwrapEd25519(slot, store.get(slot) ?: throw KeystoreException("no ${slot.wire} key"))
    }

    /** The static KEM key. The caller destroys it when done. */
    fun kem(): KemPrivateKey = wrapper.unwrapKem(KeySlot.KEM, store.get(KeySlot.KEM) ?: throw KeystoreException("no kem key"))

    /** Removes every device key (sign-out of this device; a transfer away). */
    fun clear() = KeySlot.entries.forEach { store.remove(it) }
}
