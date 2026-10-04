package com.vettid.core.attestation.nitro

import com.vettid.core.attestation.AttestationException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The subset of CBOR (RFC 8949) that COSE_Sign1 attestation documents use,
 * ported from vettid-vault `internal/cbor`: unsigned and negative integers,
 * byte and text strings (definite length), arrays and maps (definite or
 * indefinite length, as the NSM emits), tags, booleans and null. Floats,
 * undefined, other simple values and indefinite strings are refused, as are
 * duplicate map keys, nesting deeper than [MAX_DEPTH] and trailing data.
 */
sealed class CborValue {
    data class UInt(val value: Long) : CborValue()

    /** A negative integer: the value is -1 - [arg]. */
    data class NInt(val arg: Long) : CborValue()

    class Bytes(val value: ByteArray) : CborValue()

    data class Text(val value: String) : CborValue()

    data class Array(val items: List<CborValue>) : CborValue()

    data class Map(val entries: List<Pair<CborValue, CborValue>>) : CborValue() {
        fun lookup(key: String): CborValue? = entries.firstOrNull { (k, _) -> k is Text && k.value == key }?.second

        fun lookupInt(key: Long): CborValue? = entries.firstOrNull { (k, _) ->
            if (key >= 0) k is UInt && k.value == key else k is NInt && k.arg == -1 - key
        }?.second

        fun bytesAt(key: String): ByteArray = (lookup(key) as? Bytes)?.value ?: throw AttestationException.Format("cbor $key")

        fun textAt(key: String): String = (lookup(key) as? Text)?.value ?: throw AttestationException.Format("cbor $key")

        fun uintAt(key: String): Long = (lookup(key) as? UInt)?.value ?: throw AttestationException.Format("cbor $key")
    }

    data class Tag(val tag: Long, val item: CborValue) : CborValue()

    data class Bool(val value: Boolean) : CborValue()

    data object Null : CborValue()

    /** Removes tag [tag] if present. */
    fun untag(tag: Long): CborValue = if (this is Tag && this.tag == tag) item else this

    companion object {
        const val MAX_DEPTH = 16

        fun decode(b: ByteArray): CborValue {
            val d = Decoder(b)
            val v = d.item(0)
            if (d.off != b.size) throw AttestationException.Format("cbor trailing data")
            return v
        }
    }
}

private class Decoder(val b: ByteArray) {
    var off = 0

    private fun fail(what: String): Nothing = throw AttestationException.Format("cbor $what")

    private class Head(val major: Int, val arg: Long, val indefinite: Boolean)

    private fun head(): Head {
        if (off >= b.size) fail("truncated")
        val ib = b[off++].toInt() and 0xff
        val major = ib ushr 5
        val ai = ib and 0x1f
        return when {
            ai < 24 -> Head(major, ai.toLong(), false)
            ai == 31 && (major == 4 || major == 5) -> Head(major, 0, true)
            ai <= 27 -> {
                val n = 1 shl (ai - 24)
                if (b.size - off < n) fail("truncated")
                var v = 0L
                repeat(n) { v = (v shl 8) or (b[off++].toLong() and 0xff) }
                // Lengths and integers above 2^63 - 1 are not used by attestation documents.
                if (v < 0) fail("integer too large")
                Head(major, v, false)
            }
            else -> fail("unsupported item") // reserved 28–30, indefinite strings, a stray break
        }
    }

    private fun atBreak(): Boolean {
        if (off >= b.size) fail("truncated")
        if ((b[off].toInt() and 0xff) == 0xff) {
            off++
            return true
        }
        return false
    }

    @Suppress("CyclomaticComplexMethod")
    fun item(depth: Int): CborValue {
        if (depth > CborValue.MAX_DEPTH) fail("nesting too deep")
        val start = off
        val h = head()
        if (h.indefinite) {
            if (h.major == 4) {
                val items = ArrayList<CborValue>()
                while (!atBreak()) items.add(item(depth + 1))
                return CborValue.Array(items)
            }
            val entries = ArrayList<Pair<CborValue, CborValue>>()
            while (!atBreak()) entries.add(pair(depth, entries))
            return CborValue.Map(entries)
        }
        return when (h.major) {
            0 -> CborValue.UInt(h.arg)
            1 -> CborValue.NInt(h.arg)
            2, 3 -> {
                if (h.arg > b.size - off) fail("truncated")
                val s = b.copyOfRange(off, off + h.arg.toInt())
                off += h.arg.toInt()
                if (h.major == 2) CborValue.Bytes(s) else CborValue.Text(utf8(s))
            }
            4 -> {
                if (h.arg > b.size - off) fail("truncated")
                CborValue.Array(List(h.arg.toInt()) { item(depth + 1) })
            }
            5 -> {
                if (h.arg > (b.size - off) / 2) fail("truncated")
                val entries = ArrayList<Pair<CborValue, CborValue>>()
                repeat(h.arg.toInt()) { entries.add(pair(depth, entries)) }
                CborValue.Map(entries)
            }
            6 -> CborValue.Tag(h.arg, item(depth + 1))
            else -> when (h.arg) {
                20L, 21L, 22L -> {
                    if (off - start != 1) fail("non-minimal simple value")
                    if (h.arg == 22L) CborValue.Null else CborValue.Bool(h.arg == 21L)
                }
                else -> fail("unsupported simple value") // undefined, floats, others
            }
        }
    }

    private fun pair(depth: Int, seen: List<Pair<CborValue, CborValue>>): Pair<CborValue, CborValue> {
        val k = item(depth + 1)
        if (k !is CborValue.UInt && k !is CborValue.NInt && k !is CborValue.Text && k !is CborValue.Bytes) fail("map key type")
        if (seen.any { sameKey(it.first, k) }) fail("duplicate map key")
        return k to item(depth + 1)
    }

    private fun sameKey(a: CborValue, b: CborValue): Boolean = when {
        a is CborValue.Bytes && b is CborValue.Bytes -> a.value.contentEquals(b.value)
        else -> a == b
    }

    private fun utf8(s: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(s)).toString()
    } catch (_: CharacterCodingException) {
        fail("invalid UTF-8")
    }
}

/** A minimal definite-length CBOR encoder (the COSE Sig_structure, test documents). */
class CborEncoder {
    private val out = ByteArrayOutputStream()

    private fun head(major: Int, arg: Long) {
        val m = major shl 5
        when {
            arg < 24 -> out.write(m or arg.toInt())
            arg <= 0xff -> {
                out.write(m or 24)
                out.write(arg.toInt())
            }
            arg <= 0xffff -> {
                out.write(m or 25)
                out.write((arg ushr 8).toInt())
                out.write(arg.toInt() and 0xff)
            }
            arg <= 0xffffffffL -> {
                out.write(m or 26)
                for (i in 3 downTo 0) out.write(((arg ushr (8 * i)) and 0xff).toInt())
            }
            else -> {
                out.write(m or 27)
                for (i in 7 downTo 0) out.write(((arg ushr (8 * i)) and 0xff).toInt())
            }
        }
    }

    fun uint(v: Long) = apply { head(0, v) }

    fun int(v: Long) = apply { if (v >= 0) head(0, v) else head(1, -1 - v) }

    fun bytes(v: ByteArray?) = apply {
        val b = v ?: ByteArray(0)
        head(2, b.size.toLong())
        out.write(b)
    }

    fun text(v: String) = apply {
        val b = v.toByteArray(Charsets.UTF_8)
        head(3, b.size.toLong())
        out.write(b)
    }

    fun array(n: Int) = apply { head(4, n.toLong()) }

    fun map(n: Int) = apply { head(5, n.toLong()) }

    fun tag(t: Long) = apply { head(6, t) }

    fun nul() = apply { out.write(0xf6) }

    fun raw(b: ByteArray) = apply { out.write(b) }

    fun bytes(): ByteArray = out.toByteArray()
}
