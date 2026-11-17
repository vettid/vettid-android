package com.vettid.core.crypto.json

import com.vettid.core.crypto.CryptoException
import java.io.ByteArrayOutputStream

/**
 * The JSON Canonicalization Scheme (RFC 8785) for the documents VettID signs or hashes (VAULT-MESSAGING 0.89.0 §10.4
 * "Approval documents", BALLOT-PROTOCOL §2), the twin of vettid-vault `vms/jcs`. Those documents hold only objects,
 * arrays, strings, integers, booleans and null, so RFC 8785's number rule reduces to plain integers within 2^53 - 1: a
 * value parsed by [StrictJson] is written back with its members sorted by the UTF-16 code units of their names, no white
 * space, its strings escaped as ECMAScript's `JSON.stringify` escapes them, and its integers in decimal.
 *
 * Not [JsonBuilder]: that one writes Go's `encoding/json` escapes (U+2028 and U+2029 stay escaped there, a lone
 * surrogate becomes U+FFFD), which are not RFC 8785's.
 */
object Jcs {
    /** [value] in its canonical form; throws for a number that is not a plain integer within 2^53 - 1. */
    fun canonical(value: JsonValue): ByteArray {
        val out = ByteArrayOutputStream()
        append(value, out)
        return out.toByteArray()
    }

    /** A plain JSON integer: digits only, no sign, fraction, exponent or leading zero, at most 2^53 - 1 (§5.3). */
    fun isInteger(raw: String): Boolean {
        if (raw.isEmpty() || raw.length > MAX_DIGITS || raw.any { it !in '0'..'9' }) return false
        if (raw.length > 1 && raw[0] == '0') return false
        return raw.toLong() <= StrictJson.MAX_SAFE_INTEGER
    }

    private fun append(v: JsonValue, out: ByteArrayOutputStream) {
        when (v) {
            is JsonNull -> out.ascii("null")
            is JsonBool -> out.ascii(if (v.value) "true" else "false")
            is JsonNumber -> {
                if (!isInteger(v.raw)) throw CryptoException.Format("jcs: not an integer")
                out.ascii(v.raw)
            }
            is JsonString -> appendString(v.value, out)
            is JsonArray -> {
                out.write('['.code)
                v.items.forEachIndexed { i, e ->
                    if (i > 0) out.write(','.code)
                    append(e, out)
                }
                out.write(']'.code)
            }
            is JsonObject -> {
                // RFC 8785 §3.2.3: by the names' UTF-16 code units, which is how Kotlin compares strings.
                val names = v.members.keys.sorted()
                out.write('{'.code)
                names.forEachIndexed { i, n ->
                    if (i > 0) out.write(','.code)
                    appendString(n, out)
                    out.write(':'.code)
                    append(v.members.getValue(n), out)
                }
                out.write('}'.code)
            }
        }
    }

    /**
     * RFC 8785 §3.2.2.2: the two-character escapes, `\u00xx` (lower-case hex) for the other control characters, and every
     * other character as itself in UTF-8 (`/`, U+007F and U+2028 included).
     */
    private fun appendString(s: String, out: ByteArrayOutputStream) {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000c' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 15])
                else -> sb.append(c)
            }
        }
        out.write(sb.append('"').toString().toByteArray(Charsets.UTF_8))
    }

    private fun ByteArrayOutputStream.ascii(s: String) = write(s.toByteArray(Charsets.US_ASCII))

    private const val HEX = "0123456789abcdef"
    private const val MAX_DIGITS = 16
}
