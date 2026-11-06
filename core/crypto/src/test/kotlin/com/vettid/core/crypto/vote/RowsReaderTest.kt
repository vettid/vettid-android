package com.vettid.core.crypto.vote

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.VectorDoc
import com.vettid.core.crypto.json.JsonNull
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.json.asObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/**
 * [RowsReader]: rows.json read strictly without keeping its text (review 1, finding 2). The same rows as the strict JSON
 * parser reads, the strictness of VAULT-MESSAGING §5.3 (it need not be JCS), and the memory a capped file costs.
 */
class RowsReaderTest {
    private val v = VectorDoc.load("vote.json")
    private val rowsText = v.sub("documents").str("rows")

    private fun read(text: String, eligible: Int = 3) = RowsReader(text.toByteArray(Charsets.UTF_8), eligible) {}.read()

    private fun reason(block: () -> Unit): String = assertThrows(ResultsProblem::class.java) { block() }.reason

    @Test
    fun theSharedRowsReadAsTheStrictParserReadsThem() {
        val t = read(rowsText)
        val rows = StrictJson.parseObject(rowsText).array("rows").map { it.asObject() }
        assertEquals(rows.size, t.count)
        for ((i, r) in rows.withIndex()) {
            assertArrayEquals(r.base64("record_tag", 32), t.tag(i))
            val b = r["ballot"]
            if (b == null || b is JsonNull) {
                assertFalse(t.hasBallot(i))
            } else {
                val o = b.asObject()
                assertArrayEquals(o.base64("vk"), t.vk(i))
                assertArrayEquals(o.base64("ballot_sig"), t.sig(i))
                assertEquals(o.string("choice_id"), String(t.choice(i), Charsets.UTF_8))
            }
        }
        assertTrue(t.documentExact)
        assertTrue(t.membersExact)
        assertEquals("01JBQZ0000000000000000000A", t.proposalId)
    }

    @Test
    fun whitespaceAndEscapesAreReadAsJson() {
        // Not JCS: spaces, new lines and escaped characters are the same document.
        val spaced = rowsText.replace(",", " ,\n ").replace(":", " : ").replace("\"yes\"", "\"\\u0079es\"")
        val t = read(spaced)
        assertEquals("yes", String(t.choice(0), Charsets.UTF_8))
        assertArrayEquals(read(rowsText).leafHashes, t.leafHashes)
    }

    @Test
    fun whatIsNotStrictJsonIsNotRead() {
        val bad = listOf(
            "\uFEFF" + rowsText, // a byte order mark
            rowsText + "x", // something after the document
            rowsText.replace("\"v\":1", "\"v\":01"), // a number out of the grammar
            rowsText.replace("\"v\":1", "\"v\":1,\"v\":1"), // a duplicate name
            rowsText.replace("\"yes\"", "\"y\u0001s\""), // a raw control character
            rowsText.replace("\"yes\"", "\"\\ud800s\""), // a lone surrogate escape
            rowsText.replace("\"yes\"", "\"\\x\""), // an unknown escape
            rowsText.dropLast(1), // cut short
            rowsText.replace("\"rows\"", "\"rowz\""), // no rows
            rowsText.replace("[{", "[[{").replace("}]", "}]]"), // rows deeper than 4
        )
        for (b in bad) assertEquals(b.take(60), "rows", reason { read(b) })
        // Invalid UTF-8: an overlong form, and a lone continuation byte.
        for (seq in listOf(byteArrayOf(0xC0.toByte(), 0xAF.toByte()), byteArrayOf(0x80.toByte()))) {
            val raw = rowsText.replace("\"yes\"", "\"#\"").toByteArray()
            val at = raw.indexOf('#'.code.toByte())
            val bytes = raw.copyOfRange(0, at) + seq + raw.copyOfRange(at + 1, raw.size)
            assertEquals("rows", reason { RowsReader(bytes, 3) {}.read() })
        }
        // One more or one fewer row than eligible.
        assertEquals("rows_count", reason { read(rowsText, eligible = 2) })
        assertEquals("rows_count", reason { read(rowsText, eligible = 4) })
    }

    @Test
    fun aRowThatCannotBeMadeIntoALeafIsNotRead() {
        val bad = listOf(
            rowsText.replace("\"ballot\":null", "\"ballot\":7"), // a ballot that is not an object
            rowsText.replace("\"ballot\":null", "\"ballot\":[]"),
            rowsText.replaceFirst(Regex("\"record_tag\":\"[^\"]+\""), "\"record_tag\":\"AAAA\""), // not 32 bytes
            rowsText.replaceFirst("\"vk\":\"", "\"vk\":\"!"), // not base64
            rowsText.replaceFirst("\"choice_id\":\"yes\"", "\"choice_id\":7"), // not a string
            rowsText.replaceFirst("\"choice_id\":\"yes\",", ""), // a ballot member missing
            rowsText.replaceFirst("{\"ballot\":null,", "7,"), // a row that is not an object
        )
        for (b in bad) assertEquals(b.take(80), "rows", reason { read(b) })
    }

    /** A rows.json of [n] rows with ballots, padded inside each ballot (an unknown member) to about [size] bytes in all. */
    private fun padded(n: Int, size: Long): ByteArray {
        val vk = Base64s.encodeStd(ByteArray(32) { 1 })
        val sig = Base64s.encodeStd(ByteArray(64) { 2 })
        fun row(i: Int, padding: String): String {
            val tag = ByteArray(32).also { Bytes.uintBE(i.toLong(), 4).copyInto(it, 0) }
            return "{\"ballot\":{\"ballot_sig\":\"$sig\",\"choice_id\":\"yes\",\"pad\":\"$padding\",\"vk\":\"$vk\"}," +
                "\"record_tag\":\"${Base64s.encodeStd(tag)}\"}"
        }
        val head = "{\"proposal_id\":\"01JBQZ0000000000000000000A\",\"rows\":["
        val foot = "],\"v\":1}"
        val pad = ((size - head.length - foot.length) / n - row(0, "").length - 1).toInt().coerceAtLeast(0)
        val padding = "0".repeat(pad)
        val sb = StringBuilder(size.toInt()).append(head)
        for (i in 0 until n) {
            if (i > 0) sb.append(',')
            sb.append(row(i, padding))
        }
        return sb.append(foot).toString().toByteArray(Charsets.US_ASCII)
    }

    private fun allocatedBy(block: () -> Unit): Long {
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = mx.getThreadAllocatedBytes(id)
        block()
        return mx.getThreadAllocatedBytes(id) - before
    }

    @Test
    fun aCappedFileIsReadWithinAMemoryBound() {
        val n = VoteRows.MAX_ROWS
        val bytes = padded(n, VoteRows.ROWS_CAP_MAX)
        assertTrue("near the cap: ${bytes.size}", bytes.size > VoteRows.ROWS_CAP_MAX * 9 / 10 && bytes.size <= VoteRows.ROWS_CAP_MAX)
        val plain = padded(n, 0)
        RowsReader(plain, n) {}.read() // warm up
        var t: RowsTable? = null
        val paddedAlloc = allocatedBy { t = RowsReader(bytes, n) {}.read() }
        val plainAlloc = allocatedBy { RowsReader(plain, n) {}.read() }
        assertEquals(n, t!!.count)
        // What stays: about 180 bytes a row (tag, leaf hash, vk, choice, sig, offsets), never the text.
        assertTrue("retained ${t!!.retainedBytes()}", t!!.retainedBytes() <= n * 200L)
        // The padding (most of the file) is read without being copied: it costs a small part of its own size, where a
        // parser that keeps text would copy it several times over.
        val paddingBytes = bytes.size - plain.size
        assertTrue("padding cost ${paddedAlloc - plainAlloc} for $paddingBytes", paddedAlloc - plainAlloc < paddingBytes / 10)
        // The padding was not a member the protocol has.
        assertFalse(t!!.membersExact)
    }
}
