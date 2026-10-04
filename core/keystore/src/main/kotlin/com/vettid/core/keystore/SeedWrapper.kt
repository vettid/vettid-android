package com.vettid.core.keystore

import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.hpke.KemPrivateKey
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the app's device private keys (relay key, identity key `ik`, static KEM
 * key — all held as 32-byte seeds) under an AES-256-GCM key that never leaves
 * the Android Keystore (VAULT-MESSAGING §3.2: "If a platform keystore cannot
 * hold them natively, they MUST be stored encrypted under a key that the
 * keystore holds"). Ed25519 is in Keystore only from API 33 and ML-KEM not at
 * all, so every seed is wrapped the same way.
 *
 * Format: `0x01 || iv (12) || AES-GCM(key, iv, aad = "vettid/keystore/1" || 0x00 || name, seed)`.
 * The name in the AAD keeps a wrapped relay seed from being swapped in as the
 * identity seed. The IV is chosen by the Keystore (randomized encryption).
 */
class SeedWrapper(private val key: () -> SecretKey) {
    /** Wraps [seed] for slot [name]. */
    fun wrap(name: KeySlot, seed: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(aad(name))
        val ct = c.doFinal(seed)
        val iv = c.iv
        if (iv.size != IV_SIZE) throw KeystoreException("unexpected IV size")
        return Bytes.concat(byteArrayOf(FORMAT), iv, ct)
    }

    /** Unwraps a blob made by [wrap] for the same slot. The caller wipes the result. */
    fun unwrap(name: KeySlot, blob: ByteArray): ByteArray {
        if (blob.size < 1 + IV_SIZE + TAG_SIZE || blob[0] != FORMAT) throw KeystoreException("malformed wrapped key")
        return try {
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_SIZE * 8, blob, 1, IV_SIZE))
            c.updateAAD(aad(name))
            c.doFinal(blob, 1 + IV_SIZE, blob.size - 1 - IV_SIZE)
        } catch (e: GeneralSecurityException) {
            throw KeystoreException("cannot unwrap ${name.wire}", e)
        }
    }

    fun unwrapEd25519(name: KeySlot, blob: ByteArray): Ed25519PrivateKey {
        val seed = unwrap(name, blob)
        try {
            return Ed25519PrivateKey.fromSeed(seed)
        } finally {
            Bytes.wipe(seed)
        }
    }

    fun unwrapKem(name: KeySlot, blob: ByteArray): KemPrivateKey {
        val seed = unwrap(name, blob)
        try {
            return KemPrivateKey.fromSeed(seed)
        } finally {
            Bytes.wipe(seed)
        }
    }

    private fun aad(name: KeySlot) = Bytes.concat(AAD_LABEL.toByteArray(), byteArrayOf(0), name.wire.toByteArray())

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT: Byte = 0x01
        private const val IV_SIZE = 12
        private const val TAG_SIZE = 16
        private const val AAD_LABEL = "vettid/keystore/1"
    }
}

/** The device keys the app wraps (§3.2). */
enum class KeySlot(val wire: String) {
    /** The relay key (Ed25519): signs relay requests, mints tokens. */
    RELAY("relay"),

    /** The identity key `ik` (Ed25519): signs handshakes and unlocks. */
    IDENTITY("identity"),

    /** The static KEM key (MLKEM768X25519 seed): receives sealed messages. */
    KEM("kem"),
}

/** A Keystore operation failed. Messages never carry key material. */
class KeystoreException(message: String, cause: Throwable? = null) : Exception(message, cause)
