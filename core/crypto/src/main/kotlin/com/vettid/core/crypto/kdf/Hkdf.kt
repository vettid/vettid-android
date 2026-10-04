package com.vettid.core.crypto.kdf

import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA-256 (RFC 5869), extract-then-expand, as used throughout VAULT-MESSAGING §4. */
object Hkdf {
    private const val HASH_LEN = 32
    private const val MAX_LEN = 255 * HASH_LEN

    private fun mac(key: ByteArray): Mac {
        val m = Mac.getInstance("HmacSHA256")
        // HMAC keys may be empty (RFC 5869: the default salt is HashLen zeros).
        m.init(SecretKeySpec(if (key.isEmpty()) ByteArray(HASH_LEN) else key, "HmacSHA256"))
        return m
    }

    /** HKDF-Extract(salt, ikm). An empty salt means HashLen zeros. */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = mac(salt).doFinal(ikm)

    /** HKDF-Expand(prk, info, length). */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        if (length < 0 || length > MAX_LEN) throw CryptoException.Key("hkdf length")
        val m = mac(prk)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var off = 0
        var counter = 1
        while (off < length) {
            m.update(t)
            m.update(info)
            m.update(counter.toByte())
            val next = m.doFinal()
            Bytes.wipe(t)
            t = next
            val n = minOf(t.size, length - off)
            t.copyInto(out, off, 0, n)
            off += n
            counter++
        }
        Bytes.wipe(t)
        return out
    }

    /** HKDF-SHA-256(ikm, salt, info, L): extract then expand. */
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = extract(salt, ikm)
        try {
            return expand(prk, info, length)
        } finally {
            Bytes.wipe(prk)
        }
    }
}
