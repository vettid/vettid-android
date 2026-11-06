package com.vettid.core.crypto

import com.google.crypto.tink.subtle.Ed25519Verify as TinkEd25519Verify
import org.bouncycastle.math.ec.rfc8032.Ed25519 as BcEd25519
import java.security.GeneralSecurityException

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

    /**
     * Checks an Ed25519 signature over label || msg by exactly the four steps of BALLOT-PROTOCOL §2, which every verifier
     * of a vote applies so that they all agree (VAULT-MESSAGING 0.86.4 §10.19.4):
     * 1. `A` and `R` have `y` below p, and are not `x = 0` with the sign bit set;
     * 2. neither `[8]A` nor `[8]R` is the identity;
     * 3. `S < L`;
     * 4. `[S]B = R + [k]A`, **cofactorless**.
     *
     * Steps 1 and 2 are a table here ([strictPoint]), as in vettid-vault `vms/votewire/verify.go`. Steps 3 and 4 are
     * Tink's pure-Java Ed25519 verify ([cofactorless], owner decision a, #310): it refuses `S >= L`, computes
     * `R' = [S]B - [k]A` without the factor 8 (`k = SHA-512(R || A || M) mod L`) and compares the encoding of `R'` with the
     * bytes of `R`. Tink alone takes what steps 1 and 2 refuse: an `A` of small order, and an `A` whose `y` is p or more
     * (read mod p); the table refuses both before Tink is called. (An `R` that is not canonical never equals Tink's
     * encoding of `R'`; one of small order whose equation holds would.)
     */
    fun verifyStrict(publicKey: ByteArray, label: String, msg: ByteArray, sig: ByteArray): Boolean {
        if (publicKey.size != Suite.ED25519_PUBLIC_SIZE || sig.size != Suite.ED25519_SIGNATURE_SIZE) return false
        if (!strictPoint(publicKey) || !strictPoint(sig.copyOf(Suite.ED25519_PUBLIC_SIZE))) return false
        return cofactorless(publicKey, Bytes.concat(label.toByteArray(Charsets.US_ASCII), msg), sig)
    }

    /**
     * Steps 3 and 4 of BALLOT-PROTOCOL §2: Tink's `subtle.Ed25519Verify` (its constructor is always the pure-Java code,
     * never a JCE provider), `S < L` and `[S]B - [k]A` encoded equal to `R`. Sizes are the caller's.
     */
    internal fun cofactorless(publicKey: ByteArray, m: ByteArray, sig: ByteArray): Boolean = try {
        TinkEd25519Verify(publicKey).verify(sig, m)
        true
    } catch (_: GeneralSecurityException) {
        false
    }

    /** Steps 1 and 2 of BALLOT-PROTOCOL §2 for a 32-byte point encoding: `y` below p and not the `y` of a small-order point. */
    internal fun strictPoint(b: ByteArray): Boolean {
        val y = b.copyOf()
        y[31] = (y[31].toInt() and 0x7f).toByte()
        if (!lessLE(y, FIELD_P)) return false
        // The eight points of small order have y = 0 (order 4), 1 (the identity), p-1 (order 2) or one of the two y of the
        // points of order 8, each with either sign of x; x = 0 with the sign bit set has y = 1 or p-1, so it is here too.
        return SMALL_ORDER_Y.none { it.contentEquals(y) }
    }

    /** a < b, both little-endian of the same length. */
    private fun lessLE(a: ByteArray, b: ByteArray): Boolean {
        for (i in a.indices.reversed()) {
            val x = a[i].toInt() and 0xff
            val z = b[i].toInt() and 0xff
            if (x != z) return x < z
        }
        return false
    }

    /** p = 2^255 - 19, little-endian. */
    private val FIELD_P = ByteArray(32) { 0xff.toByte() }.also {
        it[0] = 0xed.toByte()
        it[31] = 0x7f
    }

    private val SMALL_ORDER_Y: List<ByteArray> = listOf(
        ByteArray(32),
        ByteArray(32).also { it[0] = 1 },
        FIELD_P.copyOf().also { it[0] = 0xec.toByte() },
        Bytes.unhex("26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05"),
        Bytes.unhex("c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a"),
    )

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
