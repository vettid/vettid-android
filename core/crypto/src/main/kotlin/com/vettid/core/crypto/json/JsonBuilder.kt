package com.vettid.core.crypto.json

import com.vettid.core.crypto.Base64s

/**
 * Writes a compact JSON object with members in call order. Strings are encoded
 * exactly as the Go reference encodes them (encoding/json without HTML
 * escaping), so both implementations produce the same bytes for the same
 * message: that is what the §16 vectors pin.
 */
class JsonBuilder {
    private val sb = StringBuilder("{")
    private var first = true

    private fun name(n: String) {
        if (!first) sb.append(',')
        first = false
        appendString(sb, n)
        sb.append(':')
    }

    fun string(n: String, v: String): JsonBuilder = apply {
        name(n)
        appendString(sb, v)
    }

    fun uint(n: String, v: Long): JsonBuilder = apply {
        require(v >= 0)
        name(n)
        sb.append(v)
    }

    fun bool(n: String, v: Boolean): JsonBuilder = apply {
        name(n)
        sb.append(v)
    }

    fun base64(n: String, v: ByteArray): JsonBuilder = string(n, Base64s.encodeStd(v))

    /** Adds a member whose value is already-encoded JSON (the caller vouches for it). */
    fun raw(n: String, v: String): JsonBuilder = apply {
        name(n)
        sb.append(v)
    }

    fun raw(n: String, v: ByteArray): JsonBuilder = raw(n, String(v, Charsets.UTF_8))

    /** Closes the object. */
    fun build(): String = "$sb}"

    fun bytes(): ByteArray = build().toByteArray(Charsets.UTF_8)

    companion object {
        private const val HEX = "0123456789abcdef"

        /** Encodes [s] as a JSON string, Go-compatible. */
        fun quote(s: String): String = StringBuilder().also { appendString(it, s) }.toString()

        /** A JSON array of already-encoded elements. */
        fun array(elements: List<String>): String = elements.joinToString(",", "[", "]")

        @Suppress("CyclomaticComplexMethod")
        internal fun appendString(sb: StringBuilder, s: String) {
            sb.append('"')
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c == '\b' -> sb.append("\\b")
                    c == '\u000c' -> sb.append("\\f")
                    c < ' ' -> sb.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 0xf])
                    c == ' ' -> sb.append("\\u2028")
                    c == ' ' -> sb.append("\\u2029")
                    Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                        sb.append(c).append(s[i + 1])
                        i++
                    }
                    // A lone surrogate is invalid UTF-8 in Go terms: U+FFFD.
                    Character.isSurrogate(c) -> sb.append("\\ufffd")
                    else -> sb.append(c)
                }
                i++
            }
            sb.append('"')
        }
    }
}
