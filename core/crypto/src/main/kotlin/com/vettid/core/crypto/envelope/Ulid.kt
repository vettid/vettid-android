package com.vettid.core.crypto.envelope

import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Randomness
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** ULIDs (§5.3): 26 upper-case Crockford base32 characters, the first in 0–7. */
object Ulid {
    const val LENGTH = 26
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun isValid(s: String): Boolean = s.length == LENGTH && s[0] <= '7' && s.all { it in CROCKFORD }

    /** A ULID for [t]: 48 bits of milliseconds and 80 random bits. */
    fun new(t: Instant = Instant.now()): String {
        val b = ByteArray(16)
        val ms = t.toEpochMilli()
        for (i in 0 until 6) b[i] = (ms ushr (40 - 8 * i)).toByte()
        Randomness.bytes(10).copyInto(b, 6)
        return encode(b)
    }

    private fun encode(b: ByteArray): String {
        var hi = 0L
        var lo = 0L
        for (i in 0 until 8) hi = (hi shl 8) or (b[i].toLong() and 0xff)
        for (i in 8 until 16) lo = (lo shl 8) or (b[i].toLong() and 0xff)
        val out = CharArray(LENGTH)
        for (i in LENGTH - 1 downTo 0) {
            out[i] = CROCKFORD[(lo and 31).toInt()]
            lo = (lo ushr 5) or (hi shl 59)
            hi = hi ushr 5
        }
        return String(out)
    }
}

/**
 * Timestamps. Inner `ts`/`exp` are `YYYY-MM-DDTHH:MM:SS.mmmZ` (§5.3); bundles,
 * manifests and descriptors use whole seconds `YYYY-MM-DDTHH:MM:SSZ`. Parsing
 * is strict: the string must round-trip exactly.
 */
object Timestamps {
    private val millis = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private val seconds = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    /** Formats [t] in the inner format, truncating to milliseconds. */
    fun formatMillis(t: Instant): String = millis.format(t)

    /** Formats [t] in whole seconds (truncating). */
    fun formatSeconds(t: Instant): String = seconds.format(t)

    fun parseMillis(s: String): Instant = parse(s, millis, 24)

    fun parseSeconds(s: String): Instant = parse(s, seconds, 20)

    private fun parse(s: String, f: DateTimeFormatter, len: Int): Instant {
        if (s.length != len) throw CryptoException.Format("timestamp")
        val t = try {
            Instant.from(f.parse(s))
        } catch (_: DateTimeParseException) {
            throw CryptoException.Format("timestamp")
        }
        if (f.format(t) != s) throw CryptoException.Format("timestamp")
        return t
    }
}
