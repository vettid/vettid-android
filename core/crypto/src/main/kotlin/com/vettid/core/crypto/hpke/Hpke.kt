package com.vettid.core.crypto.hpke

import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Kid
import com.vettid.core.crypto.Randomness
import com.vettid.core.crypto.Suite
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.hpke.HPKEContext
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Suite 2 HPKE (RFC 9180, base mode): KEM MLKEM768X25519 (0x647a, the X-Wing
 * hybrid of ML-KEM-768 and X25519, draft-connolly-cfrg-xwing-kem /
 * draft-ietf-hpke-pq), KDF HKDF-SHA256, AEAD ChaCha20-Poly1305.
 *
 * The implementation is BouncyCastle's `HPKE` with `kem_X_WING` (BC ≥ 1.86),
 * which interoperates with Go 1.26 `crypto/hpke` byte for byte: the §16
 * vectors pin the encapsulation key, the encapsulated key, the key schedule
 * and every sealed byte.
 */
internal object Hpke {
    /** HPKE encapsulation randomness: [0:32] ML-KEM `m`, [32:64] the X25519 ephemeral secret. */
    const val RANDOMNESS_SIZE = 64

    private const val MLKEM_EK_SIZE = 1184
    private const val MLKEM_T_SIZE = 1152
    private const val MLKEM_Q = 3329

    fun suite(): HPKE = HPKE(HPKE.mode_base, HPKE.kem_X_WING, HPKE.kdf_HKDF_SHA256, HPKE.aead_CHACHA20_POLY1305)

    /**
     * FIPS 203 encapsulation-key check: every 12-bit coefficient of the
     * ML-KEM-768 part is below q, i.e. the encoding is canonical (Go's
     * `mlkem.NewEncapsulationKey768` applies the same rule).
     */
    fun mlkemKeyIsCanonical(ek: ByteArray): Boolean {
        var bad = 0
        var i = 0
        while (i < MLKEM_T_SIZE) {
            val b0 = ek[i].toInt() and 0xff
            val b1 = ek[i + 1].toInt() and 0xff
            val b2 = ek[i + 2].toInt() and 0xff
            val d1 = b0 or ((b1 and 0x0f) shl 8)
            val d2 = (b1 ushr 4) or (b2 shl 4)
            bad = bad or (if (d1 >= MLKEM_Q) 1 else 0) or (if (d2 >= MLKEM_Q) 1 else 0)
            i += 3
        }
        return bad == 0 && ek.size >= MLKEM_EK_SIZE
    }
}

/** An MLKEM768X25519 encapsulation key `ek` (1,216 bytes, §3.2), validated. */
class KemPublicKey private constructor(private val raw: ByteArray) {
    internal val param: AsymmetricKeyParameter = Hpke.suite().deserializePublicKey(raw)

    /** The key's kid (§4.4). */
    val kid: Kid = Kid.of(raw)

    fun bytes(): ByteArray = raw.copyOf()

    /** Constant-time comparison. */
    override fun equals(other: Any?): Boolean = other is KemPublicKey && Bytes.constantTimeEquals(raw, other.raw)

    override fun hashCode(): Int = raw.contentHashCode()

    override fun toString(): String = "KemPublicKey(kid=$kid)"

    companion object {
        /** Parses a 1,216-byte encapsulation key; rejects non-canonical ML-KEM encodings. */
        fun parse(ek: ByteArray): KemPublicKey {
            if (ek.size != Suite.EK_SIZE) throw CryptoException.Key("ek size")
            if (!Hpke.mlkemKeyIsCanonical(ek)) throw CryptoException.Key("ek not canonical")
            return try {
                KemPublicKey(ek.copyOf())
            } catch (_: IllegalArgumentException) {
                throw CryptoException.Key("ek")
            }
        }
    }
}

/**
 * An MLKEM768X25519 decapsulation key, stored as its 32-byte seed (§3.2): the
 * RFC 9180 serialized private key, expanded with SHAKE256 as in X-Wing.
 * [destroy] wipes the seed and drops the expanded key (which lives inside
 * BouncyCastle objects that cannot be wiped from here, as in the Go reference).
 */
class KemPrivateKey private constructor(seed: ByteArray) {
    private var seed: ByteArray? = seed.copyOf()
    private var pair: AsymmetricCipherKeyPair? = Hpke.suite().deserializePrivateKey(seed, null)

    /** The encapsulation key. */
    val publicKey: KemPublicKey = KemPublicKey.parse(Hpke.suite().serializePublicKey(pair!!.public))

    internal fun pair(): AsymmetricCipherKeyPair = synchronized(this) { pair ?: throw CryptoException.Key("destroyed") }

    /** A copy of the seed, for wrapped storage. Wipe it after use. */
    fun seed(): ByteArray = synchronized(this) { (seed ?: throw CryptoException.Key("destroyed")).copyOf() }

    fun destroy() = synchronized(this) {
        Bytes.wipe(seed)
        seed = null
        pair = null
    }

    override fun toString(): String = "KemPrivateKey(kid=${publicKey.kid}, [redacted])"

    companion object {
        fun fromSeed(seed: ByteArray): KemPrivateKey {
            if (seed.size != Suite.SEED_SIZE) throw CryptoException.Key("seed size")
            return KemPrivateKey(seed)
        }

        /** A new key pair from a fresh random seed. */
        fun generate(): KemPrivateKey {
            val s = Randomness.bytes(Suite.SEED_SIZE)
            try {
                return KemPrivateKey(s)
            } finally {
                Bytes.wipe(s)
            }
        }
    }
}

/** RFC 9180 Export. */
interface HpkeExporter {
    fun export(label: String, length: Int): ByteArray
}

/**
 * A sending context (SetupBaseS). It seals exactly one message (§4.3: a
 * context MUST NOT be reused) and may export any number of secrets.
 */
class HpkeSender private constructor(private val ctx: HPKEContext, private val enc: ByteArray) : HpkeExporter {
    private val used = AtomicBoolean(false)

    /** The encapsulated key (1,120 bytes). */
    fun enc(): ByteArray = enc.copyOf()

    fun seal(aad: ByteArray, plaintext: ByteArray): ByteArray {
        if (!used.compareAndSet(false, true)) throw CryptoException.Used("hpke context")
        return ctx.seal(aad, plaintext)
    }

    override fun export(label: String, length: Int): ByteArray = ctx.export(label.toByteArray(Charsets.US_ASCII), length)

    companion object {
        /** SetupBaseS(pk_R = [recipient], [info]) with randomness from [Randomness]. */
        fun setup(recipient: KemPublicKey, info: ByteArray): HpkeSender {
            val rnd = Randomness.bytes(Hpke.RANDOMNESS_SIZE)
            try {
                val c = Hpke.suite().setupBaseS(recipient.param, info, rnd)
                val enc = c.encapsulation
                if (enc.size != Suite.ENC_SIZE) throw CryptoException.Key("enc size")
                return HpkeSender(c, enc)
            } finally {
                Bytes.wipe(rnd)
            }
        }
    }
}

/** A receiving context (SetupBaseR). It opens exactly one message. */
class HpkeRecipient private constructor(private val ctx: HPKEContext) : HpkeExporter {
    private val used = AtomicBoolean(false)

    fun open(aad: ByteArray, ciphertext: ByteArray): ByteArray {
        if (!used.compareAndSet(false, true)) throw CryptoException.Used("hpke context")
        if (ciphertext.size < Suite.TAG_SIZE) throw CryptoException.Decrypt()
        return try {
            ctx.open(aad, ciphertext)
        } catch (_: InvalidCipherTextException) {
            throw CryptoException.Decrypt()
        }
    }

    override fun export(label: String, length: Int): ByteArray = ctx.export(label.toByteArray(Charsets.US_ASCII), length)

    companion object {
        /** SetupBaseR(enc, sk_R, info). */
        fun setup(enc: ByteArray, key: KemPrivateKey, info: ByteArray): HpkeRecipient {
            if (enc.size != Suite.ENC_SIZE) throw CryptoException.Key("enc size")
            return try {
                HpkeRecipient(Hpke.suite().setupBaseR(enc, key.pair(), info))
            } catch (_: IllegalArgumentException) {
                throw CryptoException.Decrypt()
            } catch (_: IllegalStateException) {
                throw CryptoException.Decrypt()
            }
        }
    }
}
