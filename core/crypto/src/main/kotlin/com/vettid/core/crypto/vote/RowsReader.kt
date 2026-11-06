package com.vettid.core.crypto.vote

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import java.security.MessageDigest

/**
 * `rows.json` as it was read ([RowsReader]): no text is kept, only what the checks need, packed. [tags] and [leafHashes]
 * hold 32 bytes per row; a row's ballot is `vk`, `choice_id` and `ballot_sig` in [data] at [offsets] (`vkStart`,
 * `choiceStart`, `sigStart`, `end` per row; `vkStart` -1 for an empty row). The shape flags are what the observer only
 * reports (VAULT-MESSAGING 0.86.4 §10.19.6 step 1).
 */
internal class RowsTable(
    val count: Int,
    val tags: ByteArray,
    val leafHashes: ByteArray,
    val data: ByteArray,
    val offsets: IntArray,
    /** The document's members are exactly `v`, `proposal_id`, `rows`, and `v` is the number `1`. */
    val documentExact: Boolean,
    /** The document's `proposal_id` when it is a string of at most 64 bytes, else null. */
    val proposalId: String?,
    /** Every row is exactly `{record_tag, ballot}` and every ballot exactly `{vk, choice_id, ballot_sig}` (BALLOT-PROTOCOL §8.5). */
    val membersExact: Boolean,
) {
    fun tag(i: Int): ByteArray = tags.copyOfRange(i * HASH, (i + 1) * HASH)

    fun hasBallot(i: Int): Boolean = offsets[i * 4] >= 0

    fun vk(i: Int): ByteArray = data.copyOfRange(offsets[i * 4], offsets[i * 4 + 1])

    fun choice(i: Int): ByteArray = data.copyOfRange(offsets[i * 4 + 1], offsets[i * 4 + 2])

    fun sig(i: Int): ByteArray = data.copyOfRange(offsets[i * 4 + 2], offsets[i * 4 + 3])

    /** The bytes held for the rows: what stays alive after the reading. */
    fun retainedBytes(): Long = tags.size.toLong() + leafHashes.size + data.size + offsets.size * 4L

    private companion object {
        const val HASH = 32
    }
}

/**
 * A strict reader of `rows.json` that keeps no raw text (review 1, finding 2): the bytes are walked once, the JSON grammar
 * checked as VAULT-MESSAGING §5.3 asks (valid UTF-8, no raw control character in a string, `\u` escapes with paired
 * surrogates, no duplicate member name, the number grammar, nothing after the document; it need not be JCS) and nested at
 * most 4 deep (§10.19.6 step 1). Only the decoded tag, `vk`, `choice_id` and `ballot_sig` of each row are kept.
 *
 * What stops the reading ([ResultsProblem]) is only what stops a submit (§10.19.6 step 1): nesting deeper than 4, `rows`
 * missing or not an array, more or fewer rows than [eligible], or a row that cannot be made into a leaf (a row or a ballot
 * that is not an object, a `record_tag` that is not 32 bytes of base64, a ballot member missing or not a base64 string).
 * The rest (other members, other lengths) is kept for the observer to report.
 */
internal class RowsReader(private val b: ByteArray, private val eligible: Int, private val checkpoint: () -> Unit) {
    private var i = 0
    private val tags = ByteArray(eligible * HASH)
    private val leafHashes = ByteArray(eligible * HASH)
    private var data = ByteArray(minOf(b.size, INITIAL_DATA))
    private var dataLen = 0
    private val offsets = IntArray(eligible * 4)
    private var count = 0
    private var membersExact = true
    private val scratch = Scratch()

    fun read(): RowsTable {
        ws()
        if (peek() != '{'.code) fail()
        var vOne = false
        var proposalId: String? = null
        var sawRows = false
        val names = HashSet<String>()
        objectMembers(depth = 1, names) { name ->
            when (name) {
                "v" -> vOne = numberIsOne(depth = 1)
                "proposal_id" -> proposalId = if (peek() == '"'.code) {
                    stringValue(PROPOSAL_ID_MAX)?.let { String(it, Charsets.UTF_8) }
                } else {
                    skipValue(1)
                    null
                }
                "rows" -> {
                    rows()
                    sawRows = true
                }
                else -> skipValue(1)
            }
        }
        ws()
        if (i != b.size) fail()
        if (!sawRows) fail()
        if (count != eligible) throw ResultsProblem("rows_count")
        val exact = names == DOCUMENT_MEMBERS && vOne
        return RowsTable(count, tags, leafHashes, data.copyOf(dataLen), offsets, exact, proposalId, membersExact)
    }

    private fun rows() {
        if (peek() != '['.code) fail()
        i++
        ws()
        if (peek() == ']'.code) {
            i++
            return
        }
        while (true) {
            ws()
            row()
            ws()
            when (next()) {
                ','.code -> continue
                ']'.code -> return
                else -> fail()
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun row() {
        if (count >= eligible) throw ResultsProblem("rows_count")
        if (count % CHECK_EVERY == 0) checkpoint()
        if (peek() != '{'.code) fail()
        var tag: ByteArray? = null
        var sawBallot = false
        var vk: ByteArray? = null
        var choice: ByteArray? = null
        var sig: ByteArray? = null
        val names = HashSet<String>()
        objectMembers(depth = 3, names) { name ->
            when (name) {
                "record_tag" -> tag = base64Value(HASH)
                "ballot" -> {
                    sawBallot = true
                    when (peek()) {
                        'n'.code -> literal("null")
                        '{'.code -> {
                            val ballotNames = HashSet<String>()
                            objectMembers(depth = 4, ballotNames) { m ->
                                when (m) {
                                    "vk" -> vk = base64Value(-1)
                                    "ballot_sig" -> sig = base64Value(-1)
                                    "choice_id" -> choice = if (peek() == '"'.code) stringValue(LP_MAX) ?: fail() else fail()
                                    else -> skipValue(4)
                                }
                            }
                            if (vk == null || sig == null || choice == null) fail()
                            if (ballotNames != BALLOT_MEMBERS) membersExact = false
                        }
                        else -> fail()
                    }
                }
                else -> skipValue(3)
            }
        }
        val t = tag ?: fail()
        if (!sawBallot || names != ROW_MEMBERS) membersExact = false
        t.copyInto(tags, count * HASH)
        val at = count * 4
        val leaf: ByteArray
        if (vk == null) {
            offsets[at] = -1
            leaf = t
        } else {
            offsets[at] = append(vk!!)
            offsets[at + 1] = append(choice!!)
            offsets[at + 2] = append(sig!!)
            offsets[at + 3] = dataLen
            leaf = Bytes.concat(t, VoteRows.lp(vk!!), VoteRows.lp(choice!!), VoteRows.lp(sig!!))
        }
        VoteMerkle.leafHash(leaf).copyInto(leafHashes, count * HASH)
        count++
    }

    private fun append(x: ByteArray): Int {
        val start = dataLen
        if (dataLen + x.size > data.size) data = data.copyOf(maxOf(data.size * 2, dataLen + x.size))
        x.copyInto(data, dataLen)
        dataLen += x.size
        return start
    }

    /** The members of an object at [depth]: each name once ([names] collects them), [member] reads its value. */
    private inline fun objectMembers(depth: Int, names: MutableSet<String>, member: (String) -> Unit) {
        if (depth > MAX_DEPTH) fail()
        if (next() != '{'.code) fail()
        ws()
        if (peek() == '}'.code) {
            i++
            return
        }
        while (true) {
            ws()
            if (peek() != '"'.code) fail()
            val name = nameKey()
            if (!names.add(name)) fail() // no duplicate names (§5.3)
            ws()
            if (next() != ':'.code) fail()
            ws()
            member(name)
            ws()
            when (next()) {
                ','.code -> continue
                '}'.code -> return
                else -> fail()
            }
        }
    }

    /** A member name as a key: the name itself when short, else its SHA-256 (it is no name this reader knows). */
    private fun nameKey(): String {
        scratch.reset(NAME_KEEP, digestOverflow = true)
        string(scratch)
        return scratch.key()
    }

    /** A string value's decoded bytes, or null when it is longer than [max] bytes (the string is still checked). */
    private fun stringValue(max: Int): ByteArray? {
        scratch.reset(max, digestOverflow = false)
        string(scratch)
        return if (scratch.overflow) null else scratch.bytes()
    }

    /** A base64 string member; [n] >= 0 requires that many bytes. Anything else cannot be made into a leaf. */
    private fun base64Value(n: Int): ByteArray {
        if (peek() != '"'.code) fail()
        val raw = stringValue(B64_MAX) ?: fail()
        if (raw.any { it < 0 }) fail()
        val decoded = try {
            Base64s.decodeStd(String(raw, Charsets.US_ASCII), n)
        } catch (_: CryptoException) {
            fail()
        }
        if (decoded.size > LP_MAX) fail()
        return decoded
    }

    private fun numberIsOne(depth: Int): Boolean {
        if (peek() != '-'.code && peek() !in '0'.code..'9'.code) {
            skipValue(depth)
            return false
        }
        val start = i
        number()
        return i - start == 1 && b[start] == '1'.code.toByte()
    }

    /** Checks and skips any value at [depth] (its own nesting counted from there). */
    @Suppress("CyclomaticComplexMethod")
    private fun skipValue(depth: Int) {
        when (peek()) {
            '{'.code -> objectMembers(depth + 1, HashSet()) { skipValue(depth + 1) }
            '['.code -> {
                if (depth + 1 > MAX_DEPTH) fail()
                i++
                ws()
                if (peek() == ']'.code) {
                    i++
                    return
                }
                while (true) {
                    ws()
                    skipValue(depth + 1)
                    ws()
                    when (next()) {
                        ','.code -> continue
                        ']'.code -> return
                        else -> fail()
                    }
                }
            }
            '"'.code -> {
                scratch.reset(0, digestOverflow = false)
                string(scratch)
            }
            't'.code -> literal("true")
            'f'.code -> literal("false")
            'n'.code -> literal("null")
            else -> number()
        }
    }

    private fun literal(word: String) {
        for (c in word) if (next() != c.code) fail()
    }

    // -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?
    @Suppress("CyclomaticComplexMethod")
    private fun number() {
        if (peek() == '-'.code) i++
        when (peek()) {
            '0'.code -> i++
            in '1'.code..'9'.code -> while (peek() in '0'.code..'9'.code) i++
            else -> fail()
        }
        if (peek() == '.'.code) {
            i++
            if (peek() !in '0'.code..'9'.code) fail()
            while (peek() in '0'.code..'9'.code) i++
        }
        if (peek() == 'e'.code || peek() == 'E'.code) {
            i++
            if (peek() == '+'.code || peek() == '-'.code) i++
            if (peek() !in '0'.code..'9'.code) fail()
            while (peek() in '0'.code..'9'.code) i++
        }
    }

    /** A string: valid UTF-8, no raw control character, the JSON escapes; its decoded UTF-8 bytes go to [out]. */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private fun string(out: Scratch) {
        if (next() != '"'.code) fail()
        while (true) {
            val c = next()
            when {
                c == '"'.code -> return
                c < 0x20 -> fail()
                c == '\\'.code -> when (next()) {
                    '"'.code -> out.add('"'.code)
                    '\\'.code -> out.add('\\'.code)
                    '/'.code -> out.add('/'.code)
                    'b'.code -> out.add(0x08)
                    'f'.code -> out.add(0x0c)
                    'n'.code -> out.add(0x0a)
                    'r'.code -> out.add(0x0d)
                    't'.code -> out.add(0x09)
                    'u'.code -> out.addCodePoint(unicodeEscape())
                    else -> fail()
                }
                c < 0x80 -> out.add(c)
                else -> utf8(c, out)
            }
        }
    }

    // RFC 3629: no overlong form, no surrogate, nothing above U+10FFFF.
    private fun utf8(lead: Int, out: Scratch) {
        val (n, lo, hi) = when (lead) {
            in 0xC2..0xDF -> Triple(1, 0x80, 0xBF)
            0xE0 -> Triple(2, 0xA0, 0xBF)
            in 0xE1..0xEC, 0xEE, 0xEF -> Triple(2, 0x80, 0xBF)
            0xED -> Triple(2, 0x80, 0x9F)
            0xF0 -> Triple(3, 0x90, 0xBF)
            in 0xF1..0xF3 -> Triple(3, 0x80, 0xBF)
            0xF4 -> Triple(3, 0x80, 0x8F)
            else -> fail()
        }
        out.add(lead)
        val second = next()
        if (second !in lo..hi) fail()
        out.add(second)
        repeat(n - 1) {
            val c = next()
            if (c !in 0x80..0xBF) fail()
            out.add(c)
        }
    }

    private fun unicodeEscape(): Int {
        val hi = hex4()
        if (hi in 0xD800..0xDBFF) {
            if (next() != '\\'.code || next() != 'u'.code) fail()
            val lo = hex4()
            if (lo !in 0xDC00..0xDFFF) fail()
            return 0x10000 + ((hi - 0xD800) shl 10) + (lo - 0xDC00)
        }
        if (hi in 0xDC00..0xDFFF) fail()
        return hi
    }

    private fun hex4(): Int {
        var v = 0
        repeat(4) {
            val d = when (val c = next()) {
                in '0'.code..'9'.code -> c - '0'.code
                in 'a'.code..'f'.code -> c - 'a'.code + 10
                in 'A'.code..'F'.code -> c - 'A'.code + 10
                else -> fail()
            }
            v = (v shl 4) or d
        }
        return v
    }

    private fun ws() {
        while (i < b.size) {
            val c = b[i].toInt()
            if (c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code) i++ else return
        }
    }

    private fun peek(): Int = if (i < b.size) b[i].toInt() and 0xff else -1

    private fun next(): Int = if (i < b.size) b[i++].toInt() and 0xff else fail()

    private fun fail(): Nothing = throw ResultsProblem("rows")

    /** The decoded bytes of one string, up to a limit; past it only a digest (names) or nothing is kept. */
    private class Scratch {
        private var buf = ByteArray(INITIAL_SCRATCH)
        private var len = 0
        private var max = 0
        private var digest: MessageDigest? = null
        private var useDigest = false
        var overflow = false
            private set

        fun reset(max: Int, digestOverflow: Boolean) {
            this.max = max
            len = 0
            overflow = false
            useDigest = digestOverflow
            digest = null
        }

        fun add(c: Int) {
            if (len < max) {
                if (len == buf.size) buf = buf.copyOf(minOf(buf.size * 2, max))
                buf[len++] = c.toByte()
                return
            }
            if (!overflow) {
                overflow = true
                if (useDigest) digest = MessageDigest.getInstance("SHA-256").also { it.update(buf, 0, len) }
            }
            digest?.update(c.toByte())
        }

        fun addCodePoint(cp: Int) {
            for (x in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) add(x.toInt() and 0xff)
        }

        fun bytes(): ByteArray = buf.copyOf(len)

        fun key(): String = digest?.let { "#" + Bytes.hex(it.digest()) } ?: String(buf, 0, len, Charsets.UTF_8)
    }

    private companion object {
        const val HASH = 32
        const val MAX_DEPTH = 4
        const val CHECK_EVERY = 256
        const val INITIAL_DATA = 1 shl 16
        const val INITIAL_SCRATCH = 64
        const val NAME_KEEP = 64
        const val PROPOSAL_ID_MAX = 64

        /** `lp` lengths are 16 bits. */
        const val LP_MAX = 65_535

        /** The base64 text of [LP_MAX] bytes. */
        const val B64_MAX = 87_380
        val DOCUMENT_MEMBERS = setOf("v", "proposal_id", "rows")
        val ROW_MEMBERS = setOf("record_tag", "ballot")
        val BALLOT_MEMBERS = setOf("vk", "choice_id", "ballot_sig")
    }
}
