package com.vettid.core.data.items

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * One-time codes from an `otp` field (§10.7: an `otpauth://` URI or a base32 secret; apps show codes), RFC 6238
 * TOTP over RFC 4226 HOTP. The seed stays the item's value; codes are computed on the phone when shown.
 */
@Suppress("MagicNumber") // RFC 4226 dynamic truncation
class Totp private constructor(private val key: ByteArray, val digits: Int, val periodSeconds: Long, private val algorithm: String) {
    /** The code for the time step that holds [epochSeconds]. */
    fun code(epochSeconds: Long): String {
        val counter = Math.floorDiv(epochSeconds, periodSeconds)
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        val h = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(counter).array())
        val off = h[h.size - 1].toInt() and 0x0f
        val bin = ((h[off].toInt() and 0x7f) shl 24) or ((h[off + 1].toInt() and 0xff) shl 16) or
            ((h[off + 2].toInt() and 0xff) shl 8) or (h[off + 3].toInt() and 0xff)
        val mod = POW10[digits]
        return (bin % mod).toString().padStart(digits, '0')
    }

    /** Seconds until the code of [epochSeconds] changes. */
    fun secondsLeft(epochSeconds: Long): Long = periodSeconds - Math.floorMod(epochSeconds, periodSeconds)

    override fun toString(): String = "Totp($digits digits, ${periodSeconds}s)"

    companion object {
        private val POW10 = intArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000)
        private const val DEFAULT_DIGITS = 6
        private const val DEFAULT_PERIOD = 30L
        private const val MAX_PERIOD = 3_600L

        /** Parses an `otp` value; null when it is neither a valid `otpauth://totp/` URI nor a base32 secret. */
        @Suppress("ReturnCount", "CyclomaticComplexMethod")
        fun parse(value: String): Totp? {
            val v = value.trim()
            if (!v.startsWith("otpauth://", ignoreCase = true)) {
                return base32(v)?.let { Totp(it, DEFAULT_DIGITS, DEFAULT_PERIOD, "HmacSHA1") }
            }
            val uri = try {
                URI(v)
            } catch (_: URISyntaxException) {
                return null
            }
            if (!uri.host.equals("totp", ignoreCase = true)) return null
            val q = (uri.rawQuery ?: "").split('&').mapNotNull { p ->
                val i = p.indexOf('=')
                if (i <= 0) null else p.substring(0, i).lowercase() to URLDecoder.decode(p.substring(i + 1), "UTF-8")
            }.toMap()
            val key = q["secret"]?.let(::base32) ?: return null
            val digits = q["digits"]?.toIntOrNull() ?: DEFAULT_DIGITS
            val period = q["period"]?.toLongOrNull() ?: DEFAULT_PERIOD
            val alg = when (q["algorithm"]?.uppercase() ?: "SHA1") {
                "SHA1" -> "HmacSHA1"
                "SHA256" -> "HmacSHA256"
                "SHA512" -> "HmacSHA512"
                else -> return null
            }
            if (digits !in DEFAULT_DIGITS..POW10.size - 1 || period !in 1..MAX_PERIOD) return null
            return Totp(key, digits, period, alg)
        }

        /** RFC 4648 base32, case-insensitive, padding and spaces ignored. */
        @Suppress("ReturnCount", "MagicNumber")
        fun base32(s: String): ByteArray? {
            val t = s.filterNot { it == ' ' || it == '=' || it == '-' }.uppercase()
            if (t.isEmpty()) return null
            val out = java.io.ByteArrayOutputStream()
            var buffer = 0
            var bits = 0
            for (c in t) {
                val v = when (c) {
                    in 'A'..'Z' -> c - 'A'
                    in '2'..'7' -> c - '2' + 26
                    else -> return null
                }
                buffer = (buffer shl 5) or v
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    out.write((buffer shr bits) and 0xff)
                }
            }
            return out.toByteArray().takeIf { it.isNotEmpty() }
        }
    }
}
