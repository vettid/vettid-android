package com.vettid.core.crypto.envelope

import com.vettid.core.crypto.CryptoException

/**
 * Padding of the inner plaintext (§5.4): `json || 0x80 || 0x00*` to the next
 * multiple of 512 bytes up to 16 KiB, above that to the next multiple of
 * 16 KiB; the bucket is computed for len(json) + 1 and over-padding is
 * malformed. The alternate channel pads to fixed sizes.
 */
object Padding {
    const val MIN_PADDED = 512
    const val SMALL_BUCKET_LIMIT = 16 * 1024
    const val MAX_PADDED = 245_760

    /** Fixed padded size of alternate-channel plaintexts. */
    const val ALT_CHANNEL = 4096

    /** Fixed padded size of `vault.enroll` and `vault.unlock`. */
    const val ALT_CHANNEL_REQUEST = 12_288

    /** The bucket for a JSON plaintext of [n] bytes. */
    fun paddedLen(n: Int): Int {
        if (n < 0) throw CryptoException.Format("length")
        val m = n + 1
        val p = if (m <= SMALL_BUCKET_LIMIT) {
            (m + MIN_PADDED - 1) / MIN_PADDED * MIN_PADDED
        } else {
            (m + SMALL_BUCKET_LIMIT - 1) / SMALL_BUCKET_LIMIT * SMALL_BUCKET_LIMIT
        }
        if (p > MAX_PADDED) throw CryptoException.Format("padded plaintext too large")
        return p
    }

    /** Whether [p] is a size some JSON length maps to. */
    fun isValidPaddedLen(p: Int): Boolean = when {
        p < MIN_PADDED || p > MAX_PADDED -> false
        p <= SMALL_BUCKET_LIMIT -> p % MIN_PADDED == 0
        else -> p % SMALL_BUCKET_LIMIT == 0
    }

    fun pad(json: ByteArray): ByteArray = padTo(json, paddedLen(json.size))

    /** Pads to exactly [n] bytes (a valid padded size that fits the JSON). */
    fun padFixed(json: ByteArray, n: Int): ByteArray {
        if (!isValidPaddedLen(n)) throw CryptoException.Format("length")
        if (json.size + 1 > n) throw CryptoException.Format("padded plaintext too large")
        return padTo(json, n)
    }

    private fun padTo(json: ByteArray, n: Int): ByteArray {
        val out = ByteArray(n)
        json.copyInto(out)
        out[json.size] = 0x80.toByte()
        return out
    }

    /** Strictly removes bucket padding (no over-padding). */
    fun unpad(padded: ByteArray): ByteArray {
        val json = unpadAny(padded)
        if (paddedLen(json.size) != padded.size) throw CryptoException.Format("padding")
        return json
    }

    /** Strictly removes fixed padding of exactly [n] bytes. */
    fun unpadFixed(padded: ByteArray, n: Int): ByteArray {
        if (padded.size != n) throw CryptoException.Format("padding")
        return unpadAny(padded)
    }

    // Finds the 0x80 marker scanning the whole buffer without a data-dependent
    // early exit, as the Go reference does.
    private fun unpadAny(padded: ByteArray): ByteArray {
        if (!isValidPaddedLen(padded.size)) throw CryptoException.Format("padding")
        var marker = -1
        var trailingZero = 1
        for (i in padded.indices.reversed()) {
            val b = padded[i].toInt() and 0xff
            val isZero = if (b == 0) 1 else 0
            val isMarker = if (b == 0x80) 1 else 0
            val first = trailingZero and (1 xor isZero)
            if ((first and isMarker) == 1) marker = i
            if ((first and (1 xor isMarker)) == 1) marker = -2
            trailingZero = trailingZero and isZero
        }
        if (marker < 0) throw CryptoException.Format("padding")
        return padded.copyOf(marker)
    }
}
