package com.vettid.core.crypto

import java.security.MessageDigest

/**
 * Byte helpers used across the protocol code. Every comparison of a tag, key,
 * hash commitment or kid goes through [constantTimeEquals] (VAULT-MESSAGING
 * §13.6); every buffer that held a secret is cleared with [wipe] when the code
 * that owns it is done with it.
 */
object Bytes {
    private const val HEX = "0123456789abcdef"

    /**
     * Compares two byte strings in time that depends only on their lengths.
     * Arrays of different lengths are unequal.
     */
    fun constantTimeEquals(a: ByteArray?, b: ByteArray?): Boolean {
        if (a == null || b == null) return false
        return MessageDigest.isEqual(a, b) && a.size == b.size
    }

    /**
     * Overwrites [b] with zeros. The JVM may have copied a secret elsewhere
     * (GC moves, library internals), so this is best-effort hygiene that
     * shortens the life of the copies this code controls, as in the Go
     * reference.
     */
    fun wipe(vararg b: ByteArray?) {
        for (x in b) x?.fill(0)
    }

    /** Concatenates byte arrays. */
    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var off = 0
        for (p in parts) {
            p.copyInto(out, off)
            off += p.size
        }
        return out
    }

    /** Lowercase hex. */
    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    /** Parses lowercase hex strictly (even length, `[0-9a-f]` only). */
    fun unhex(s: String): ByteArray {
        if (s.length % 2 != 0) throw CryptoException.Format("hex")
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = HEX.indexOf(s[2 * i])
            val lo = HEX.indexOf(s[2 * i + 1])
            if (hi < 0 || lo < 0) throw CryptoException.Format("hex")
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** Reports whether [s] is exactly [n] lowercase hex characters. */
    fun isLowerHex(s: String, n: Int): Boolean =
        s.length == n && s.all { it in '0'..'9' || it in 'a'..'f' }

    /** SHA-256 of the concatenation of [parts]. */
    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    /** SHA-256(label || parts...): the labelled hash of VAULT-MESSAGING §4. */
    fun labeledHash(label: String, vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(label.toByteArray(Charsets.US_ASCII))
        for (p in parts) md.update(p)
        return md.digest()
    }

    /** Big-endian encoding of [v] in [n] bytes. */
    fun uintBE(v: Long, n: Int): ByteArray {
        val out = ByteArray(n)
        for (i in 0 until n) out[n - 1 - i] = (v ushr (8 * i)).toByte()
        return out
    }

    /** Reads a big-endian unsigned integer of up to 8 bytes. */
    fun readUintBE(b: ByteArray, off: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (b[off + i].toLong() and 0xff)
        return v
    }
}

/** Copies [this] (`null` stays `null`). */
internal fun ByteArray?.copyOrNull(): ByteArray? = this?.copyOf()
