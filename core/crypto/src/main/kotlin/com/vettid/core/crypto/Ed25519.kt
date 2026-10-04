package com.vettid.core.crypto

import org.bouncycastle.math.ec.rfc8032.Ed25519 as BcEd25519

/**
 * Ed25519 (RFC 8032) for identity keys, relay keys and the credential key.
 * Every suite 2 signature is over `label || message` (§13.4).
 */
object Ed25519 {
    const val SEED_SIZE = 32

    /** Checks an Ed25519 signature over label || msg. */
    fun verify(publicKey: ByteArray, label: String, msg: ByteArray, sig: ByteArray): Boolean =
        verifyRaw(publicKey, Bytes.concat(label.toByteArray(Charsets.US_ASCII), msg), sig)

    /** Checks an Ed25519 signature over [msg] (no label). */
    fun verifyRaw(publicKey: ByteArray, msg: ByteArray, sig: ByteArray): Boolean {
        if (publicKey.size != Suite.ED25519_PUBLIC_SIZE || sig.size != Suite.ED25519_SIGNATURE_SIZE) return false
        return try {
            BcEd25519.verify(sig, 0, publicKey, 0, msg, 0, msg.size)
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /** Throws unless the signature verifies. */
    fun requireValid(publicKey: ByteArray, label: String, msg: ByteArray, sig: ByteArray, what: String) {
        if (!verify(publicKey, label, msg, sig)) throw CryptoException.Signature(what)
    }

    /** Compares two public keys in constant time. */
    fun equalPublic(a: ByteArray?, b: ByteArray?): Boolean =
        a != null && b != null && a.size == Suite.ED25519_PUBLIC_SIZE && b.size == Suite.ED25519_PUBLIC_SIZE &&
            Bytes.constantTimeEquals(a, b)
}

/**
 * An Ed25519 private key held as its 32-byte seed. [destroy] wipes it. Keys
 * that live in the Android Keystore wrap this seed (`:core:keystore`); the
 * seed itself is never written anywhere in the clear.
 */
class Ed25519PrivateKey private constructor(seed: ByteArray) {
    private var seed: ByteArray? = seed.copyOf()

    /** The 32-byte public key. */
    val publicKey: ByteArray = ByteArray(Suite.ED25519_PUBLIC_SIZE).also { BcEd25519.generatePublicKey(seed, 0, it, 0) }

    private fun live(): ByteArray = seed ?: throw CryptoException.Key("destroyed")

    /** Ed25519(seed, label || msg). Deterministic. */
    fun sign(label: String, msg: ByteArray): ByteArray = signRaw(Bytes.concat(label.toByteArray(Charsets.US_ASCII), msg))

    /** Ed25519(seed, msg) without a label (only where the spec signs a full string, e.g. §11.4). */
    fun signRaw(msg: ByteArray): ByteArray {
        val sig = ByteArray(Suite.ED25519_SIGNATURE_SIZE)
        synchronized(this) { BcEd25519.sign(live(), 0, msg, 0, msg.size, sig, 0) }
        return sig
    }

    /** A copy of the seed, for wrapping by the keystore. Wipe it after use. */
    fun seed(): ByteArray = synchronized(this) { live().copyOf() }

    fun destroy() = synchronized(this) {
        Bytes.wipe(seed)
        seed = null
    }

    override fun toString(): String = "Ed25519PrivateKey[redacted]"

    companion object {
        /** Derives a key from a 32-byte seed (copied). */
        fun fromSeed(seed: ByteArray): Ed25519PrivateKey {
            if (seed.size != Ed25519.SEED_SIZE) throw CryptoException.Key("seed size")
            return Ed25519PrivateKey(seed)
        }

        /** Generates a key from [Randomness]. */
        fun generate(): Ed25519PrivateKey {
            val s = Randomness.bytes(Ed25519.SEED_SIZE)
            try {
                return Ed25519PrivateKey(s)
            } finally {
                Bytes.wipe(s)
            }
        }
    }
}
