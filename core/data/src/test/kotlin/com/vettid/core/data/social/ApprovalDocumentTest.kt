// Documents stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.data.social.ApprovalDocument.Check
import com.vettid.core.data.social.ApprovalDocument.Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's checks of an approval's document (VAULT-MESSAGING 0.89.0 §10.4 "Approval documents"): the §16 vector's
 * document is shown, and every document vettid-vault's receiver drops (`vms/peerwire` `TestDocumentChecks`) is one the
 * app does not show, as are a document of another hash, size or encoding.
 */
class ApprovalDocumentTest {
    /** The vector, read from `:core:crypto`'s copy (its `src/test/resources/vectors/SOURCE.md`). */
    private val vector = StrictJson.parseObject(File("../crypto/src/test/resources/vectors/connauth_document.json").readBytes())

    private fun sha(b: ByteArray): String = Base64s.encodeStd(Bytes.sha256(b))

    private fun ref(doc: String): String = "ref " + ApprovalDocument.ref(Bytes.sha256(doc.toByteArray()))

    /** [doc] checked as fetched for a challenge whose `context` ends with its own reference, of its own hash and size. */
    private fun check(doc: String, context: String = "Approve — ${ref(doc)}"): Failure? {
        val b = doc.toByteArray()
        return (ApprovalDocument.check(Base64s.encodeStd(b), sha(b), b.size, context, "a1") as? Check.Refused)?.reason
    }

    @Test
    fun theVectorsDocumentIsShown() {
        val doc = vector.string("document")
        val r = ApprovalDocument.check(
            Base64s.encodeStd(doc.toByteArray()), vector.string("document_sha256_b64"), doc.toByteArray().size, vector.string("context"), "a1",
        )
        val d = (r as Check.Shown).document
        // The challenge as the service sent it carries exactly these bytes.
        assertTrue(StrictJson.parseObject(vector.string("challenge_body")).base64("document").contentEquals(d.bytes))
        assertTrue(d.bytes.contentEquals(doc.toByteArray()))
        assertEquals(vector.string("document_sha256_b64"), d.sha256)
        assertEquals(vector.string("document_sha256_hex"), Bytes.hex(d.d))
        assertEquals("proposal.create", d.action)
        assertEquals("Adopt the 2027 budget", d.summary)
        assertEquals(
            listOf("binding", "choices", "closes_at", "opens_at", "outage_rule", "quorum", "removed_may_vote", "text", "title"),
            d.payload.members.keys.toList(),
        )
    }

    @Test
    fun whatTheVaultDropsIsNotShown() {
        val good = """{"action":"admin_set.add","payload":{"n":1},"summary":"Add Ann"}"""
        // The document is level 1 and payload level 2: 15 more objects make 17 levels, 14 make 16.
        val deep = """{"action":"a","payload":{""" + "\"x\":{".repeat(15) + "}".repeat(15) + """},"summary":""}"""
        val deepOK = """{"action":"a","payload":{""" + "\"x\":{".repeat(14) + "}".repeat(14) + """},"summary":""}"""
        val deep33 = """{"action":"a","payload":{""" + "\"x\":{".repeat(31) + "}".repeat(31) + """},"summary":""}"""
        val edge = """{"action":"""" + "a".repeat(64) + """","payload":{},"summary":"""" + "s".repeat(1024) + """"}"""
        assertNull(check(good))
        assertNull("16 levels", check(deepOK))
        assertNull("65,536 bytes", check(pad(65_536)))
        assertNull("a 64-byte action and a 1,024-byte summary", check(edge))
        assertNull("an array inside counts as a level, integers in it", check("""{"action":"a","payload":{"x":[0,9007199254740991,[true,null]]},"summary":""}"""))
        val refused = listOf(
            pad(65_537) to Failure.SIZE,
            "x" to Failure.SHAPE,
            "[1]" to Failure.SHAPE,
            """{"action":"a","extra":1,"payload":{},"summary":""}""" to Failure.SHAPE,
            """{"action":"a","payload":{}}""" to Failure.SHAPE,
            """{"action":"a","payload":[],"summary":""}""" to Failure.SHAPE,
            """{"action":"A","payload":{},"summary":""}""" to Failure.SHAPE,
            """{"action":"","payload":{},"summary":""}""" to Failure.SHAPE,
            """{"action":"""" + "a".repeat(65) + """","payload":{},"summary":""}""" to Failure.SHAPE,
            """{"action":"a","payload":{},"summary":"""" + "s".repeat(1025) + """"}""" to Failure.SHAPE,
            """{"action":"a","payload":{"x":1,"x":2},"summary":""}""" to Failure.SHAPE,
            """{"action":"a","payload":{"x":-1},"summary":""}""" to Failure.SHAPE,
            """{"action":"a","payload":{"x":1.5},"summary":""}""" to Failure.SHAPE,
            """{"action":"a","payload":{"x":1e3},"summary":""}""" to Failure.SHAPE,
            """{"action":"a","payload":{"x":9007199254740992},"summary":""}""" to Failure.SHAPE,
            """{"action":"a","payload":{"x":[{"y":9007199254740992}]},"summary":""}""" to Failure.SHAPE,
            """{"action":"a","payload":{"x":"\ud800"},"summary":""}""" to Failure.SHAPE,
            """{"action":"a-b","payload":{},"summary":""}""" to Failure.SHAPE,
            """{"action":"a/b","payload":{},"summary":""}""" to Failure.SHAPE,
            """{"action":"é","payload":{},"summary":""}""" to Failure.SHAPE,
            """{"action":1,"payload":{},"summary":""}""" to Failure.SHAPE,
            """{"action":"a","payload":{},"summary":null}""" to Failure.SHAPE,
            deep to Failure.SHAPE,
            deep33 to Failure.SHAPE,
            """{"payload":{},"action":"a","summary":""}""" to Failure.CANONICAL,
            """{"action": "a","payload":{},"summary":""}""" to Failure.CANONICAL,
            """{"action":"a","payload":{},"summary":"\u0041"}""" to Failure.CANONICAL,
            """{"action":"a","payload":{},"summary":"\/"}""" to Failure.CANONICAL,
            """{"action":"a","payload":{},"summary":"\u001F"}""" to Failure.CANONICAL,
            """{"action":"a","payload":{},"summary":"\u007f"}""" to Failure.CANONICAL,
            """{"action":"a","payload":{"b":1,"a":2},"summary":""}""" to Failure.CANONICAL,
            "\uFEFF" + good to Failure.SHAPE,
            good + " " to Failure.CANONICAL,
            good + "\n" to Failure.CANONICAL,
            " " + good to Failure.CANONICAL,
        )
        for ((doc, failure) in refused) assertEquals(doc.take(60), failure, check(doc))
        assertEquals(Failure.REF, check(good, "Approve"))
        assertEquals(Failure.REF, check(good, ref(good) + " more"))
        assertEquals(Failure.REF, check(good, ref(good).uppercase()))
        assertEquals("another document's reference", Failure.REF, check(good, ref(deepOK)))
        assertEquals("the reference alone is enough", null, check(good, ApprovalDocument.ref(Bytes.sha256(good.toByteArray()))))
        // A mark (U+0600) just before the reference does not hide it, as in the vault, which compares bytes.
        assertNull(check(good, "x \u0600" + ApprovalDocument.ref(Bytes.sha256(good.toByteArray()))))
    }

    /** A document of exactly [n] bytes. */
    private fun pad(n: Int): String {
        val head = """{"action":"a","payload":{"x":""""
        val tail = """"},"summary":""}"""
        return head + "a".repeat(n - head.length - tail.length) + tail
    }

    @Test
    fun aDocumentOfAnotherHashOrSizeOrEncodingIsNotShown() {
        val good = """{"action":"a","payload":{},"summary":""}"""
        val b = good.toByteArray()
        val ctx = ref(good)
        val std = Base64s.encodeStd(b)
        fun refused(base64: String = std, sha: String? = sha(b), size: Int? = b.size, context: String? = ctx): Failure? =
            (ApprovalDocument.check(base64, sha, size, context, "a1") as? Check.Refused)?.reason
        assertNull(refused())
        assertEquals(Failure.HASH, refused(sha = sha("other".toByteArray())))
        assertEquals(Failure.HASH, refused(sha = ""))
        assertEquals(Failure.HASH, refused(sha = null))
        assertEquals("31 bytes", Failure.HASH, refused(sha = Base64s.encodeStd(Bytes.sha256(b).copyOf(31))))
        assertEquals("unpadded hash", Failure.HASH, refused(sha = sha(b).trimEnd('=')))
        assertEquals(Failure.SIZE, refused(size = b.size + 1))
        assertEquals(Failure.SIZE, refused(size = b.size - 1))
        assertEquals(Failure.SIZE, refused(size = null))
        assertEquals(Failure.REF, refused(context = null))
        val urlAlphabet = Base64s.encodeRawUrl((good + "??>>").toByteArray())
        for (bad in listOf("%%%", std.trimEnd('='), std.dropLast(1), urlAlphabet, "e30=\n", std.take(8) + "\n" + std.drop(8), std.take(8) + "\r\n" + std.drop(8), " $std", "$std ")) {
            assertEquals(bad, Failure.ENCODING, refused(base64 = bad))
        }
        // An over-long string that is not even base64 is judged by its length first, as the vault does.
        assertEquals(Failure.SIZE, refused(base64 = "%".repeat(90_000)))
    }

    @Test
    fun theSameBytesAreTheSameDocument() {
        val good = """{"action":"a","payload":{},"summary":""}"""
        val b = good.toByteArray()
        val one = (ApprovalDocument.check(Base64s.encodeStd(b), sha(b), b.size, ref(good), "a1") as Check.Shown).document
        val two = (ApprovalDocument.check(Base64s.encodeStd(b), sha(b), b.size, "x " + ref(good), "a2") as Check.Shown).document
        assertEquals(one, two)
        assertEquals(sha(b), one.sha256)
        // Each bound to what it was checked for.
        assertEquals("a1" to ref(good), one.requestId to one.context)
        assertEquals("a2" to "x " + ref(good), two.requestId to two.context)
        // What it hands out is a copy.
        one.bytes[0] = '['.code.toByte()
        assertTrue(one.bytes.contentEquals(b))
        assertEquals("ApprovalDocument(action=a, size=${b.size})", one.toString())
    }
}
