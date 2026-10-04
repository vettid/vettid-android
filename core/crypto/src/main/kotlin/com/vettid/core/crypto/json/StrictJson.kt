package com.vettid.core.crypto.json

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.CryptoException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Strict JSON for the vault messaging wire format (VAULT-MESSAGING §5.3), the
 * Kotlin twin of the Go reference's `internal/strictjson`:
 *
 * - valid UTF-8, exactly one top-level object, nothing after it but white space;
 * - no duplicate member names at any depth (names are case-sensitive);
 * - nesting at most [MAX_DEPTH];
 * - integers as plain JSON integers (no sign, fraction, exponent or leading
 *   zero) no larger than 2^53 − 1 ([MAX_SAFE_INTEGER]).
 *
 * Errors never echo input bytes.
 */
object StrictJson {
    const val MAX_DEPTH = 32
    const val MAX_SAFE_INTEGER = (1L shl 53) - 1

    /** Parses [b] as exactly one JSON object. */
    fun parseObject(b: ByteArray): JsonObject {
        val text = decodeUtf8(b)
        val v = Parser(text).parseDocument()
        return v as? JsonObject ?: throw CryptoException.Format("json: not an object")
    }

    /** Parses [s] as exactly one JSON object. */
    fun parseObject(s: String): JsonObject = parseObject(s.toByteArray(Charsets.UTF_8))

    /**
     * Validates [raw] as a strict JSON object and returns its compact form
     * (white space outside strings removed, everything else byte for byte), as
     * Go's json.Compact does.
     */
    fun compactObject(raw: ByteArray): ByteArray {
        parseObject(raw)
        return compact(raw)
    }

    /** Removes insignificant white space from already-validated JSON. */
    fun compact(raw: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(raw.size)
        var inString = false
        var escaped = false
        for (x in raw) {
            val c = x.toInt() and 0xff
            if (inString) {
                out.write(c)
                when {
                    escaped -> escaped = false
                    c == '\\'.code -> escaped = true
                    c == '"'.code -> inString = false
                }
                continue
            }
            when (c) {
                ' '.code, '\t'.code, '\n'.code, '\r'.code -> Unit
                '"'.code -> {
                    inString = true
                    out.write(c)
                }
                else -> out.write(c)
            }
        }
        return out.toByteArray()
    }

    internal fun decodeUtf8(b: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b))
            .toString()
    } catch (_: CharacterCodingException) {
        throw CryptoException.Format("json: invalid UTF-8")
    }

    /** Parses the text of a plain JSON integer in [min, max] (≤ 2^53 − 1). */
    internal fun parseUint(text: String, min: Long, max: Long): Long {
        if (text.isEmpty() || text.length > 16 || text.any { it !in '0'..'9' }) throw CryptoException.Format("json: integer")
        if (text.length > 1 && text[0] == '0') throw CryptoException.Format("json: integer")
        val v = text.toLong()
        if (v < min || v > max || v > MAX_SAFE_INTEGER) throw CryptoException.Format("json: integer out of range")
        return v
    }
}

/** A parsed JSON value. [raw] is its exact source text. */
sealed class JsonValue(val raw: String) {
    /** The exact source bytes of this value (UTF-8). */
    fun rawBytes(): ByteArray = raw.toByteArray(Charsets.UTF_8)
}

class JsonString internal constructor(raw: String, val value: String) : JsonValue(raw)

class JsonNumber internal constructor(raw: String) : JsonValue(raw)

class JsonBool internal constructor(raw: String, val value: Boolean) : JsonValue(raw)

class JsonNull internal constructor(raw: String) : JsonValue(raw)

class JsonArray internal constructor(raw: String, val items: List<JsonValue>) : JsonValue(raw)

/**
 * A parsed object with typed, strict accessors. Unknown members are ignored by
 * callers (§5.3); known members must have exactly the specified type.
 */
@Suppress("TooManyFunctions") // one strict accessor per JSON type, required and optional
class JsonObject internal constructor(raw: String, val members: Map<String, JsonValue>) : JsonValue(raw) {
    fun has(name: String): Boolean = members.containsKey(name)

    operator fun get(name: String): JsonValue? = members[name]

    private fun require(name: String): JsonValue = members[name] ?: throw CryptoException.Format("json: missing $name")

    fun string(name: String): String =
        (require(name) as? JsonString)?.value ?: throw CryptoException.Format("json: $name is not a string")

    /** An optional string; `null` when absent. */
    fun optString(name: String): String? = if (has(name)) string(name) else null

    fun uint(name: String, min: Long, max: Long): Long {
        val v = require(name) as? JsonNumber ?: throw CryptoException.Format("json: $name is not an integer")
        return StrictJson.parseUint(v.raw, min, max)
    }

    fun optUint(name: String, min: Long, max: Long): Long? = if (has(name)) uint(name, min, max) else null

    fun bool(name: String): Boolean =
        (require(name) as? JsonBool)?.value ?: throw CryptoException.Format("json: $name is not a boolean")

    fun obj(name: String): JsonObject =
        require(name) as? JsonObject ?: throw CryptoException.Format("json: $name is not an object")

    fun optObj(name: String): JsonObject? = if (has(name)) obj(name) else null

    fun array(name: String): List<JsonValue> =
        (require(name) as? JsonArray)?.items ?: throw CryptoException.Format("json: $name is not an array")

    fun optArray(name: String): List<JsonValue>? = if (has(name)) array(name) else null

    /** A required member holding canonical standard base64 of [n] bytes (n < 0: any). */
    fun base64(name: String, n: Int = -1): ByteArray = Base64s.decodeStd(string(name), n)
}

/** Reads an array element as a string. */
fun JsonValue.asString(): String = (this as? JsonString)?.value ?: throw CryptoException.Format("json: not a string")

/** Reads an array element as an object. */
fun JsonValue.asObject(): JsonObject = this as? JsonObject ?: throw CryptoException.Format("json: not an object")

/** Reads an array element as an integer in [min, max]. */
fun JsonValue.asUint(min: Long, max: Long): Long {
    val n = this as? JsonNumber ?: throw CryptoException.Format("json: not an integer")
    return StrictJson.parseUint(n.raw, min, max)
}

@Suppress("TooManyFunctions")
private class Parser(private val s: String) {
    private var i = 0

    fun parseDocument(): JsonValue {
        ws()
        if (i >= s.length || s[i] != '{') throw CryptoException.Format("json: not an object")
        val v = value(0)
        ws()
        if (i != s.length) throw CryptoException.Format("json: trailing data")
        return v
    }

    private fun fail(): Nothing = throw CryptoException.Format("json: syntax")

    private fun ws() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }

    private fun value(depth: Int): JsonValue {
        if (i >= s.length) fail()
        return when (s[i]) {
            '{' -> obj(depth + 1)
            '[' -> arr(depth + 1)
            '"' -> {
                val start = i
                val v = str()
                JsonString(s.substring(start, i), v)
            }
            't' -> literal("true") { JsonBool(it, true) }
            'f' -> literal("false") { JsonBool(it, false) }
            'n' -> literal("null") { JsonNull(it) }
            else -> num()
        }
    }

    private fun literal(word: String, make: (String) -> JsonValue): JsonValue {
        if (!s.startsWith(word, i)) fail()
        i += word.length
        return make(word)
    }

    private fun obj(depth: Int): JsonObject {
        if (depth > StrictJson.MAX_DEPTH) throw CryptoException.Format("json: nesting too deep")
        val start = i
        i++ // {
        val members = LinkedHashMap<String, JsonValue>()
        ws()
        if (i < s.length && s[i] == '}') {
            i++
            return JsonObject(s.substring(start, i), members)
        }
        while (true) {
            ws()
            if (i >= s.length || s[i] != '"') fail()
            val name = str()
            if (members.containsKey(name)) throw CryptoException.Format("json: duplicate member name")
            ws()
            if (i >= s.length || s[i] != ':') fail()
            i++
            ws()
            members[name] = value(depth)
            ws()
            if (i >= s.length) fail()
            when (s[i]) {
                ',' -> i++
                '}' -> {
                    i++
                    return JsonObject(s.substring(start, i), members)
                }
                else -> fail()
            }
        }
    }

    private fun arr(depth: Int): JsonArray {
        if (depth > StrictJson.MAX_DEPTH) throw CryptoException.Format("json: nesting too deep")
        val start = i
        i++ // [
        val items = ArrayList<JsonValue>()
        ws()
        if (i < s.length && s[i] == ']') {
            i++
            return JsonArray(s.substring(start, i), items)
        }
        while (true) {
            ws()
            items.add(value(depth))
            ws()
            if (i >= s.length) fail()
            when (s[i]) {
                ',' -> i++
                ']' -> {
                    i++
                    return JsonArray(s.substring(start, i), items)
                }
                else -> fail()
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private fun str(): String {
        i++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) fail()
            val c = s[i++]
            when {
                c == '"' -> return sb.toString()
                c < ' ' -> fail() // raw control characters are not allowed
                c != '\\' -> sb.append(c)
                else -> {
                    if (i >= s.length) fail()
                    when (s[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> sb.append(unicodeEscape())
                        else -> fail()
                    }
                }
            }
        }
    }

    // \uXXXX, combining surrogate pairs; a lone surrogate decodes to U+FFFD,
    // as Go's encoding/json does.
    private fun unicodeEscape(): String {
        val hi = hex4()
        if (hi in 0xD800..0xDBFF && s.startsWith("\\u", i)) {
            val save = i
            i += 2
            val lo = hex4()
            if (lo in 0xDC00..0xDFFF) return String(Character.toChars(0x10000 + ((hi - 0xD800) shl 10) + (lo - 0xDC00)))
            i = save
        }
        if (hi in 0xD800..0xDFFF) return "�"
        return hi.toChar().toString()
    }

    private fun hex4(): Int {
        if (i + 4 > s.length) fail()
        var v = 0
        repeat(4) {
            val d = Character.digit(s[i++], 16)
            if (d < 0) fail()
            v = (v shl 4) or d
        }
        return v
    }

    // The JSON number grammar: -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?
    @Suppress("CyclomaticComplexMethod")
    private fun num(): JsonNumber {
        val start = i
        if (i < s.length && s[i] == '-') i++
        if (i >= s.length) fail()
        if (s[i] == '0') {
            i++
        } else if (s[i] in '1'..'9') {
            while (i < s.length && s[i] in '0'..'9') i++
        } else {
            fail()
        }
        if (i < s.length && s[i] == '.') {
            i++
            digits()
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            digits()
        }
        return JsonNumber(s.substring(start, i))
    }

    private fun digits() {
        val start = i
        while (i < s.length && s[i] in '0'..'9') i++
        if (i == start) fail()
    }
}
