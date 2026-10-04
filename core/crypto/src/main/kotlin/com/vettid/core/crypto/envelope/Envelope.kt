package com.vettid.core.crypto.envelope

import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Kid
import com.vettid.core.crypto.Labels
import com.vettid.core.crypto.Suite
import com.vettid.core.crypto.aead.XChaCha20Poly1305
import com.vettid.core.crypto.hpke.HpkeExporter
import com.vettid.core.crypto.hpke.HpkeRecipient
import com.vettid.core.crypto.hpke.HpkeSender
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.crypto.hpke.KemPublicKey

/**
 * A structurally valid, still-encrypted v2 envelope (§5.2):
 *
 * ```
 * 0  1   ver 0x02 | 1 1 suite 0x02 | 2 1 mode | 3 1 flags 0x00
 * 4  8   sender_kid | 12 8 recipient_kid
 * session: 20 24 nonce | 44 n+16 XChaCha20-Poly1305(k, nonce, aad = bytes[0:44], padded)
 * sealed:  20 1120 enc | 1140 n+16 HPKE Seal(aad = bytes[0:1140], padded)
 * ```
 *
 * [parse] rejects anything a conforming sender would not produce before any
 * decryption is attempted.
 */
class Envelope private constructor(private val raw: ByteArray, val mode: Mode) {
    val senderKid: Kid = Kid(raw.copyOfRange(OFF_SENDER_KID, OFF_RECIPIENT_KID))
    val recipientKid: Kid = Kid(raw.copyOfRange(OFF_RECIPIENT_KID, OFF_BODY))

    val headerLen: Int get() = if (mode == Mode.SESSION) HEADER_SESSION else HEADER_SEALED

    /** The header, which is also the AAD. */
    fun header(): ByteArray = raw.copyOf(headerLen)

    fun bytes(): ByteArray = raw.copyOf()

    val size: Int get() = raw.size

    val paddedLen: Int get() = raw.size - headerLen - Suite.TAG_SIZE

    internal fun body(): ByteArray = raw.copyOfRange(OFF_BODY, headerLen)

    internal fun ciphertext(): ByteArray = raw.copyOfRange(headerLen, raw.size)

    companion object {
        const val VERSION = 0x02
        private const val OFF_VER = 0
        private const val OFF_SUITE = 1
        private const val OFF_MODE = 2
        private const val OFF_FLAGS = 3
        private const val OFF_SENDER_KID = 4
        private const val OFF_RECIPIENT_KID = 12
        private const val OFF_BODY = 20
        const val HEADER_SESSION = OFF_BODY + Suite.X_NONCE_SIZE // 44
        const val HEADER_SEALED = OFF_BODY + Suite.ENC_SIZE // 1140
        const val OVERHEAD_SESSION = HEADER_SESSION + Suite.TAG_SIZE // 60
        const val OVERHEAD_SEALED = HEADER_SEALED + Suite.TAG_SIZE // 1156

        /**
         * Validates structure: version 2, suite 2 (suite 1 rejected), mode
         * session or sealed, flags zero, and a ciphertext of tag + a valid
         * padded size. The input is copied.
         */
        fun parse(b: ByteArray): Envelope {
            if (b.size < OFF_BODY) throw CryptoException.Format("envelope truncated")
            if (b[OFF_VER].toInt() != VERSION) throw CryptoException.Format("envelope version")
            Suite.check(b[OFF_SUITE].toInt() and 0xff, 0)
            if (b[OFF_FLAGS].toInt() != 0) throw CryptoException.Format("envelope flags")
            val mode = Mode.of(b[OFF_MODE].toInt() and 0xff) ?: throw CryptoException.Format("envelope mode")
            val h = if (mode == Mode.SESSION) HEADER_SESSION else HEADER_SEALED
            if (b.size < h + Suite.TAG_SIZE + Padding.MIN_PADDED) throw CryptoException.Format("envelope truncated")
            if (!Padding.isValidPaddedLen(b.size - h - Suite.TAG_SIZE)) throw CryptoException.Format("envelope length")
            return Envelope(b.copyOf(), mode)
        }

        internal fun header(mode: Mode, senderKid: Kid, recipientKid: Kid, body: ByteArray): ByteArray =
            Bytes.concat(
                byteArrayOf(VERSION.toByte(), Suite.SUITE_2.toByte(), mode.byte.toByte(), 0),
                senderKid.bytes(),
                recipientKid.bytes(),
                body,
            )

        /**
         * A session-mode envelope: a fresh random nonce and XChaCha20-Poly1305
         * under the epoch's directional key, the 44-byte header as AAD.
         */
        fun sealSession(key: ByteArray, senderKid: Kid, recipientKid: Kid, padded: ByteArray): ByteArray {
            if (!Padding.isValidPaddedLen(padded.size)) throw CryptoException.Format("length")
            val nonce = XChaCha20Poly1305.newNonce()
            val h = header(Mode.SESSION, senderKid, recipientKid, nonce)
            return Bytes.concat(h, XChaCha20Poly1305.seal(key, nonce, h, padded))
        }

        /** Decrypts a session-mode envelope and returns the padded inner plaintext. */
        fun openSession(e: Envelope, key: ByteArray): ByteArray {
            if (e.mode != Mode.SESSION) throw CryptoException.Protocol("wrong mode")
            return XChaCha20Poly1305.open(key, e.body(), e.header(), e.ciphertext())
        }

        /** SetupBaseS + Seal in one step (§4.3). */
        fun sealSealed(recipient: KemPublicKey, senderKid: Kid, padded: ByteArray): Pair<ByteArray, HpkeExporter> {
            val s = Sealer(recipient, senderKid)
            return s.seal(padded) to s
        }

        /**
         * Decrypts a sealed-mode envelope. recipient_kid must be the key's kid:
         * the caller selects the key by kid and no other key is tried (§4.4).
         */
        fun openSealed(e: Envelope, key: KemPrivateKey): Pair<ByteArray, HpkeExporter> {
            if (e.mode != Mode.SEALED) throw CryptoException.Protocol("wrong mode")
            if (e.recipientKid != key.publicKey.kid) throw CryptoException.Protocol("kid does not match the key")
            val r = HpkeRecipient.setup(e.body(), key, Labels.SEALED.toByteArray(Charsets.US_ASCII))
            return r.open(e.header(), e.ciphertext()) to r
        }
    }
}

/**
 * Builds one sealed-mode envelope in two steps, so that a caller can read the
 * header (and Export from the HPKE context) before sealing: the handshake's
 * `th` covers hs.resp's header inside hs.resp's own plaintext (§6.3).
 */
class Sealer(recipient: KemPublicKey, senderKid: Kid) : HpkeExporter {
    private val ctx = HpkeSender.setup(recipient, Labels.SEALED.toByteArray(Charsets.US_ASCII))
    private val header = Envelope.header(Mode.SEALED, senderKid, recipient.kid, ctx.enc())

    fun header(): ByteArray = header.copyOf()

    override fun export(label: String, length: Int): ByteArray = ctx.export(label, length)

    /** Encrypts the padded inner plaintext; callable once. */
    fun seal(padded: ByteArray): ByteArray {
        if (!Padding.isValidPaddedLen(padded.size)) throw CryptoException.Format("length")
        return Bytes.concat(header, ctx.seal(header, padded))
    }
}

/**
 * Claim-check blobs (§5.5): `blob = nonce(24) || XChaCha20-Poly1305(key,
 * nonce, aad = "vettid/vms/2/blob", content)`; the receiver checks SHA-256(blob)
 * in constant time before decrypting.
 */
object Blob {
    const val THRESHOLD = 64 * 1024

    class Sealed(val blob: ByteArray, val key: ByteArray, val sha256: ByteArray)

    fun seal(content: ByteArray): Sealed {
        val key = com.vettid.core.crypto.Randomness.bytes(Suite.KEY_SIZE)
        val nonce = XChaCha20Poly1305.newNonce()
        val blob = Bytes.concat(nonce, XChaCha20Poly1305.seal(key, nonce, Labels.BLOB.toByteArray(), content))
        return Sealed(blob, key, Bytes.sha256(blob))
    }

    fun open(blob: ByteArray, key: ByteArray, sha256: ByteArray): ByteArray {
        if (!Bytes.constantTimeEquals(Bytes.sha256(blob), sha256)) throw CryptoException.Decrypt()
        if (blob.size < Suite.X_NONCE_SIZE + Suite.TAG_SIZE) throw CryptoException.Decrypt()
        return XChaCha20Poly1305.open(
            key,
            blob.copyOf(Suite.X_NONCE_SIZE),
            Labels.BLOB.toByteArray(),
            blob.copyOfRange(Suite.X_NONCE_SIZE, blob.size),
        )
    }
}
