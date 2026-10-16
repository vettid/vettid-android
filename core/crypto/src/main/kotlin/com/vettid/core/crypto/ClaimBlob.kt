package com.vettid.core.crypto

import com.vettid.core.crypto.aead.XChaCha20Poly1305

/**
 * The claim-check blob of VAULT-MESSAGING 0.32.0 §5.5, version 1: a version byte, a fresh nonce, and the content
 * framed with its length and padded with Padmé, under a fresh 256-bit key used for this one blob.
 *
 * ```
 * blob   = ver (1: 0x01) || nonce (24) || XChaCha20-Poly1305(key, nonce, aad, framed)
 * aad    = "vettid/vms/2/blob" || 0x00 || ver
 * framed = uint32be(len(content)) || content || 0x00 * (Padme(4 + len(content)) - 4 - len(content))
 * ```
 *
 * The receiver checks, in this order: the SHA-256 of the received bytes against the reference's `sha256` (constant
 * time, before anything is decrypted), the version, the AEAD, the framing (exact Padmé length, `len` within it, zero
 * padding), and the content's length against the reference's `size`. Fixed by vettid-vault's `blob.json` (§16).
 */
object ClaimBlob {
    const val VERSION = 1
    const val LABEL = "vettid/vms/2/blob"

    /** Bytes around the framed plaintext: the version, the nonce and the tag (41). */
    const val OVERHEAD = 1 + Suite.X_NONCE_SIZE + Suite.TAG_SIZE
    private const val LEN = 4
    private const val HEADER = 1 + Suite.X_NONCE_SIZE

    /** Why a received blob was refused (the vector file's `error` names). */
    enum class Failure(val code: String) { SHA256("sha256"), VERSION("version"), DECRYPT("decrypt"), FRAMING("framing"), SIZE("size") }

    /** A refused blob. The message names the failure only; never bytes of the blob or its key. */
    class Refused(val failure: Failure) : CryptoException("blob: ${failure.code}")

    /** A sealed blob: the bytes to upload, its fresh [key], the [sha256] of [blob] and the content's [size]. */
    class Sealed(val blob: ByteArray, val key: ByteArray, val sha256: ByteArray, val size: Int) {
        /** Zeroizes the key and the sealed bytes once the reference has been sent (or abandoned). */
        fun destroy() {
            key.fill(0)
            blob.fill(0)
        }
    }

    /**
     * Padmé (Nikitin et al., 2019) for [l] ≥ 2: `E = floor(log2 L)`, `S = floor(log2 E) + 1`, `z = E - S`, and L
     * rounded up to a multiple of `2^z`.
     */
    fun padme(l: Long): Long {
        require(l >= 2) { "padme undefined below 2" }
        val e = 63 - java.lang.Long.numberOfLeadingZeros(l)
        val s = (31 - Integer.numberOfLeadingZeros(e)) + 1
        val z = e - s
        if (z <= 0) return l
        val mask = (1L shl z) - 1
        return (l + mask) and mask.inv()
    }

    /** The length of the blob that holds [contentSize] bytes of content: 41 + Padmé(4 + size). */
    fun blobLength(contentSize: Long): Long = OVERHEAD + padme(LEN + contentSize)

    /** The AAD for version [ver]. */
    fun aad(ver: Int = VERSION): ByteArray = LABEL.toByteArray(Charsets.US_ASCII) + byteArrayOf(0, ver.toByte())

    /** [content] framed and padded (also the plaintext of the vault's stored attachment objects, §10.5.1). */
    fun frame(content: ByteArray): ByteArray {
        if (content.isEmpty()) throw CryptoException.Format("blob content empty")
        val total = padme(LEN.toLong() + content.size)
        if (total > Int.MAX_VALUE) throw CryptoException.Format("blob content too large")
        val out = ByteArray(total.toInt())
        val n = content.size
        out[0] = (n ushr 24).toByte()
        out[1] = (n ushr 16).toByte()
        out[2] = (n ushr 8).toByte()
        out[3] = n.toByte()
        content.copyInto(out, LEN)
        return out
    }

    /** The content of [framed] after the framing checks; throws [Refused] with [Failure.FRAMING]. */
    fun unframe(framed: ByteArray): ByteArray {
        if (framed.size < LEN + 1) throw Refused(Failure.FRAMING)
        val n = ((framed[0].toLong() and 0xff) shl 24) or ((framed[1].toLong() and 0xff) shl 16) or
            ((framed[2].toLong() and 0xff) shl 8) or (framed[3].toLong() and 0xff)
        if (n < 1 || n > framed.size - LEN) throw Refused(Failure.FRAMING)
        if (padme(LEN + n) != framed.size.toLong()) throw Refused(Failure.FRAMING)
        var pad = 0
        for (i in LEN + n.toInt() until framed.size) pad = pad or framed[i].toInt()
        if (pad != 0) throw Refused(Failure.FRAMING)
        return framed.copyOfRange(LEN, LEN + n.toInt())
    }

    /** Seals [content] under a fresh key (or [key], for the vector) and a fresh nonce. */
    fun seal(content: ByteArray, key: ByteArray = Randomness.bytes(Suite.KEY_SIZE)): Sealed {
        if (key.size != Suite.KEY_SIZE) throw CryptoException.Key("blob key size")
        val framed = frame(content)
        val nonce = XChaCha20Poly1305.newNonce()
        val ct = try {
            XChaCha20Poly1305.seal(key, nonce, aad(VERSION), framed)
        } finally {
            framed.fill(0)
        }
        val blob = ByteArray(HEADER + ct.size)
        blob[0] = VERSION.toByte()
        nonce.copyInto(blob, 1)
        ct.copyInto(blob, HEADER)
        return Sealed(blob, key, Bytes.sha256(blob), content.size)
    }

    /**
     * Opens a received [blob] whose reference named [sha256] and [size]: every check of §5.5 in order, nothing
     * decrypted before the hash and the version passed. Returns the content.
     */
    fun open(blob: ByteArray, key: ByteArray, sha256: ByteArray, size: Long): ByteArray {
        if (!Bytes.constantTimeEquals(Bytes.sha256(blob), sha256)) throw Refused(Failure.SHA256)
        if (blob.isEmpty() || blob[0].toInt() != VERSION) throw Refused(Failure.VERSION)
        if (blob.size < OVERHEAD + LEN + 1) throw Refused(Failure.FRAMING)
        val framed = try {
            XChaCha20Poly1305.open(key, blob.copyOfRange(1, HEADER), aad(VERSION), blob.copyOfRange(HEADER, blob.size))
        } catch (_: CryptoException.Decrypt) {
            throw Refused(Failure.DECRYPT)
        }
        val content = try {
            unframe(framed)
        } finally {
            framed.fill(0)
        }
        if (content.size.toLong() != size) {
            content.fill(0)
            throw Refused(Failure.SIZE)
        }
        return content
    }
}
