// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.votes

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The limits of one `vote.site.sync` call (VAULT-MESSAGING 0.81.2 §10.19.8; ANDROID-PLAN 0.1.107). */
class VoteSyncTest {
    private fun list(version: Long, size: Int = 100) = VoteSync.ServedList(ByteArray(size) { 'a'.code.toByte() }, ByteArray(64), version)

    private fun services(call: JsonObject) = (call["services"] as JsonArray).size

    @Test
    fun theRequestLimitsAreTheSpecsNumbers() {
        assertEquals(245_760, VoteSync.INLINE_MAX)
        assertEquals(8_257_532, VoteSync.BODY_MAX)
        assertEquals(1_048_576, VoteSync.BIG_LIST)
        assertEquals(8, VoteSync.MAX_LISTS)
        assertEquals(8, VoteSync.MAX_ENTRIES)
    }

    @Test
    fun atMostEightListsACallAndOneAloneWhenAListIsOverOneMebibyte() {
        val small = (1L..10L).map { list(it) }
        assertEquals(listOf(8, 2), VoteSync.listCalls(small).map(::services))
        // A big list is alone in its call; the others go around it, in order.
        val mixed = listOf(list(1), list(2), list(3, size = VoteSync.BIG_LIST + 1), list(4), list(5))
        val calls = VoteSync.listCalls(mixed)
        assertEquals(listOf(2, 1, 2), calls.map(::services))
        // A 4,194,304-byte list is one call (as base64 about 5.6 MB, so a blob call).
        val four = VoteSync.listCalls(listOf(list(1, size = 4_194_304)))
        assertEquals(1, four.size)
        val size = VoteSync.encoded(four[0]).size
        assertTrue("$size", size in (VoteSync.INLINE_MAX + 1)..VoteSync.BODY_MAX)
    }

    @Test
    fun aListThatCouldNeverBeSentStopsTheChain() {
        val tooBig = list(2, size = VoteSync.BODY_MAX + 1)
        val calls = VoteSync.listCalls(listOf(list(1), tooBig, list(3)))
        assertEquals(listOf(1), calls.map(::services))
    }

    @Test
    fun theHeldListIsOnlyConfirmedAnOlderOneIsNotSentAndANewerOneFollowsTheMissingVersions() {
        val current = list(3)
        val sha = Base64s.encodeStd(Bytes.sha256(current.doc))
        assertEquals(VoteSync.ListPlan.Confirm(3, sha), VoteSync.planLists(current, 3, sha, emptyList()))
        assertEquals(VoteSync.ListPlan.Nothing, VoteSync.planLists(list(2), 3, "other", emptyList()))
        val send = VoteSync.planLists(list(4), 2, "other", listOf(list(3))) as VoteSync.ListPlan.Send
        assertEquals(listOf(3L, 4L), send.lists.map { it.version })
        // A vault with no list takes the current one whatever its version.
        assertEquals(listOf(7L), (VoteSync.planLists(list(7), null, null, emptyList()) as VoteSync.ListPlan.Send).lists.map { it.version })
        // A missing copy: only what chains.
        assertEquals(VoteSync.ListPlan.Nothing, VoteSync.planLists(list(5), 2, "other", emptyList()))
    }

    private fun entry(id: String, size: Int) = VoteSync.entryJson(id, null, VoteSync.Document.Found(ByteArray(size), null, null, emptyList()))

    @Test
    fun atMostEightEntriesACallAndNoEntryThatCouldNeverBeSent() {
        val entries = (1..10).map { entry("P$it", 10) }
        assertEquals(listOf(8, 2), VoteSync.entryCalls(entries).map { (it["entries"] as JsonArray).size })
        // An entry body that alone passes the limit (built raw: through entryJson a proposal this big is sent unreachable).
        val huge = JsonObject(mapOf("proposal_id" to JsonPrimitive("HUGE"), "pad" to JsonPrimitive("x".repeat(VoteSync.BODY_MAX))))
        val calls = VoteSync.entryCalls(listOf(entry("A", 10), huge, entry("B", 10)))
        assertEquals(listOf(2), calls.map { (it["entries"] as JsonArray).size })
        assertTrue(calls.all { VoteSync.encoded(it).size <= VoteSync.BODY_MAX })
    }

    @Test
    fun anEntryCarriesNoTimeAndTheStatusesAreTheSpecsThree() {
        for ((d, status) in listOf(VoteSync.Document.Absent to "absent", VoteSync.Document.Unreachable to "unreachable")) {
            val e = VoteSync.entryJson("P", null, d)
            val doc = e["document"] as JsonObject
            assertEquals(setOf("status"), doc.keys)
            assertEquals(status, (doc["status"] as JsonPrimitive).content)
        }
        val found = VoteSync.entryJson("P", null, VoteSync.Document.Found(byteArrayOf(1), byteArrayOf(2), null, emptyList()))["document"] as JsonObject
        assertEquals("found", (found["status"] as JsonPrimitive).content)
        assertNull(found["at"])
    }

    @Test
    fun theVersionOfAListIsReadStrictly() {
        assertEquals(3L, VoteSync.versionOf("""{"version":3}""".toByteArray()))
        assertNull(VoteSync.versionOf("""{"version":3,"version":4}""".toByteArray()))
        assertNull(VoteSync.versionOf("nope".toByteArray()))
    }

    @Test
    fun theInlineLimitIsMeasuredOnTheInnerPlaintextThroughTheRealEncoder() {
        fun body(n: Int) = ("{\"k\":\"" + "a".repeat(n) + "\"}").toByteArray()
        fun encodes(b: ByteArray) = try {
            com.vettid.core.crypto.envelope.Inner.encode(
                com.vettid.core.crypto.envelope.Inner(
                    com.vettid.core.crypto.envelope.Ulid.new(), "vote.site.sync", java.time.Instant.now(), seq = com.vettid.core.crypto.json.StrictJson.MAX_SAFE_INTEGER, body = b,
                ),
                com.vettid.core.crypto.envelope.Mode.SESSION,
            )
            true
        } catch (_: com.vettid.core.crypto.CryptoException) {
            false
        }
        // The first body length that no longer fits, found by the encoder itself.
        var n = VoteSync.INLINE_MAX - 400
        while (encodes(body(n))) n++
        // Just under the cap and just over: the same answer as the encoder, and the body alone is under 245,760 bytes at the edge.
        assertTrue(VoteSync.fitsInline(body(n - 1)))
        assertFalse(VoteSync.fitsInline(body(n)))
        assertTrue(body(n).size < VoteSync.INLINE_MAX + 400)
        // A body under the old limit by less than the envelope no longer goes inline when the inner would not fit.
        assertFalse(VoteSync.fitsInline(body(VoteSync.INLINE_MAX - 5)))
    }
    // ---- Shape checks before sending (VAULT-MESSAGING 0.81.5 §10.19.8) ----

    private fun found(proposal: Int = 100, bundle: JsonObject? = JsonObject(emptyMap()), extensions: List<VoteSync.Extension> = emptyList()) =
        VoteSync.Document.Found(ByteArray(proposal), ByteArray(64), bundle, extensions)

    private fun ext(size: Int = 10, sig: Int? = 64, bundle: JsonObject? = JsonObject(emptyMap())) =
        VoteSync.Extension(ByteArray(size), sig?.let { ByteArray(it) }, bundle)

    private fun bigBundle(bytes: Int) = JsonObject(mapOf("x" to JsonPrimitive("a".repeat(bytes))))

    @Test
    fun theShapeLimitsAreTheVaults() {
        assertEquals(64, VoteSync.SIG_BYTES)
        assertEquals(65_536, VoteSync.PROPOSAL_MAX)
        assertEquals(65_536, VoteSync.BUNDLE_MAX)
        assertEquals(4_096, VoteSync.EXTENSION_MAX)
        assertEquals(24, VoteSync.MAX_EXTENSIONS)
    }

    @Test
    fun aListWithAnEmptyDocOrABadSigIsNotSentAndTheCatchUpStopsThere() {
        val badSig = VoteSync.ServedList(ByteArray(10), ByteArray(63), 2)
        val empty = VoteSync.ServedList(ByteArray(0), ByteArray(64), 2)
        for (bad in listOf(badSig, empty)) {
            assertFalse(VoteSync.sendable(bad))
            assertEquals(listOf(1), VoteSync.listCalls(listOf(list(1), bad, list(3))).map(::services))
            assertTrue(VoteSync.listCalls(listOf(bad)).isEmpty())
        }
    }

    @Test
    fun aProposalOverTheLimitIsSentUnreachable() {
        assertEquals(VoteSync.Document.Unreachable, VoteSync.shaped(found(proposal = 65_537)))
        assertTrue(VoteSync.shaped(found(proposal = 65_536)) is VoteSync.Document.Found)
        // Absent and unreachable stay as they are.
        assertEquals(VoteSync.Document.Absent, VoteSync.shaped(VoteSync.Document.Absent))
    }

    @Test
    fun aBundleOverTheLimitIsLeftOut() {
        val d = VoteSync.shaped(found(bundle = bigBundle(70_000))) as VoteSync.Document.Found
        assertNull(d.bundle)
        assertTrue((VoteSync.shaped(found()) as VoteSync.Document.Found).bundle != null)
    }

    @Test
    fun theExtensionsEndBeforeTheFirstBadOne() {
        val cases = listOf(ext(size = 4_097), ext(sig = 63), ext(sig = null), ext(bundle = null), ext(bundle = bigBundle(70_000)))
        for (bad in cases) {
            val d = VoteSync.shaped(found(extensions = listOf(ext(), ext(), bad, ext()))) as VoteSync.Document.Found
            assertEquals(2, d.extensions.size)
        }
        // At most 24.
        val many = VoteSync.shaped(found(extensions = List(30) { ext() })) as VoteSync.Document.Found
        assertEquals(24, many.extensions.size)
    }

    @Test
    fun anIndexElementWithANonStringServiceIdIsLeftOut() {
        val bad = JsonObject(mapOf("proposal_id" to JsonPrimitive("P"), "service_id" to JsonPrimitive(7)))
        val good = JsonObject(mapOf("proposal_id" to JsonPrimitive("P"), "service_id" to JsonPrimitive("s")))
        val none = JsonObject(mapOf("proposal_id" to JsonPrimitive("P")))
        assertNull(VoteSync.shapedIndex(bad))
        assertEquals(good, VoteSync.shapedIndex(good))
        assertEquals(none, VoteSync.shapedIndex(none))
        // The entry goes without it.
        assertNull(VoteSync.entryJson("P", bad, VoteSync.Document.Absent)["index"])
    }

    @Test
    fun resultsAreSubmittableOnlyWithAnythingInThemAndA64ByteSig() {
        assertTrue(VoteSync.submittable(ByteArray(10), ByteArray(64)))
        assertFalse(VoteSync.submittable(ByteArray(0), ByteArray(64)))
        assertFalse(VoteSync.submittable(ByteArray(10), ByteArray(63)))
    }
    // ---- Strict JSON and the proposal id (VAULT-MESSAGING 0.81.6 §10.19.8) ----

    /** An object nested [depth] deep in all (the outer one counts 1). */
    private fun nested(depth: Int): String = "{\"a\":".repeat(depth - 1) + "{}" + "}".repeat(depth - 1)

    private val pid = "01JBQZ0000000000000000000A"

    @Test
    fun aSiteValueThatIsNotStrictJsonIsNotAnObject() {
        assertTrue(VoteSync.jsonObject("""{"mediaType":"x","n":{"a":1}}""".toByteArray()) != null)
        // A duplicate member name, at the top or deeper.
        assertNull(VoteSync.jsonObject("""{"a":1,"a":2}""".toByteArray()))
        assertNull(VoteSync.jsonObject("""{"x":{"b":1,"b":1}}""".toByteArray()))
        // Invalid UTF-8.
        assertNull(VoteSync.jsonObject(byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 0xC3.toByte(), '"'.code.toByte(), ':'.code.toByte(), '1'.code.toByte(), '}'.code.toByte())))
        // Too deep for where it goes.
        assertTrue(VoteSync.jsonObject(nested(VoteSync.BUNDLE_DEPTH).toByteArray(), VoteSync.BUNDLE_DEPTH) != null)
        assertNull(VoteSync.jsonObject(nested(VoteSync.BUNDLE_DEPTH + 1).toByteArray(), VoteSync.BUNDLE_DEPTH))
    }

    /** The inner message an inline request travels as, parsed as strictly as the vault parses it (vms/envelope/inner.go). */
    private fun parseInner(body: JsonObject) = com.vettid.core.crypto.json.StrictJson.parseObject(
        com.vettid.core.crypto.envelope.Inner(com.vettid.core.crypto.envelope.Ulid.new(), "vote.site.sync", java.time.Instant.now(), seq = 1, body = VoteSync.encoded(body))
            .marshal(com.vettid.core.crypto.envelope.Mode.SESSION),
    )

    @Test
    fun theDepthLeftIsWhereEachValueSitsInTheRequest() {
        assertEquals(28, VoteSync.INDEX_DEPTH)
        assertEquals(27, VoteSync.BUNDLE_DEPTH)
        assertEquals(25, VoteSync.EXTENSION_BUNDLE_DEPTH)
        assertEquals(30, VoteSync.RESULTS_BUNDLE_DEPTH)
        fun entry(extra: Int): JsonObject {
            val bundle = VoteSync.jsonObject(nested(VoteSync.BUNDLE_DEPTH + extra).toByteArray())!!
            val extBundle = VoteSync.jsonObject(nested(VoteSync.EXTENSION_BUNDLE_DEPTH + extra).toByteArray())!!
            val index = VoteSync.jsonObject(("{\"proposal_id\":\"$pid\",\"x\":" + nested(VoteSync.INDEX_DEPTH - 1 + extra) + "}").toByteArray())!!
            // Built raw (not through entryJson), so that the over-deep case is sent as it is.
            return JsonObject(
                mapOf(
                    "proposal_id" to JsonPrimitive(pid),
                    "index" to index,
                    "document" to JsonObject(
                        mapOf(
                            "status" to JsonPrimitive("found"),
                            "bundle" to bundle,
                            "extensions" to JsonArray(listOf(JsonObject(mapOf("bundle" to extBundle)))),
                        ),
                    ),
                ),
            )
        }
        // Values at exactly their budget: the vault's strict parse of the whole inner message passes.
        parseInner(JsonObject(mapOf("entries" to JsonArray(listOf(entry(0))))))
        // One level more: it would refuse the call (so the budgets are not too generous).
        assertTrue(runCatching { parseInner(JsonObject(mapOf("entries" to JsonArray(listOf(entry(1)))))) }.isFailure)
        // The results' bundle in vote.results.submit.
        parseInner(JsonObject(mapOf("bundle" to VoteSync.jsonObject(nested(VoteSync.RESULTS_BUNDLE_DEPTH).toByteArray())!!)))
        assertTrue(runCatching { parseInner(JsonObject(mapOf("bundle" to VoteSync.jsonObject(nested(VoteSync.RESULTS_BUNDLE_DEPTH + 1).toByteArray())!!))) }.isFailure)
        // And the app's own check refuses what is one level too deep for its place.
        assertNull(VoteSync.jsonObject(nested(VoteSync.BUNDLE_DEPTH + 1).toByteArray(), VoteSync.BUNDLE_DEPTH))
    }

    @Test
    fun theSplitterEndsOnBrokenArraysWithNothing() {
        for (bad in listOf("[}]", "[1}", "[{]}", "[,]", "[1,]", "[", "[\"x]", "[{\"a\":1}")) {
            assertEquals(bad, emptyList<ByteArray>(), VoteSync.elements(bad.toByteArray()))
        }
    }

    @Test
    fun namesThatReencodingWouldMergeAreNotAnObject() {
        // "?" and a lone surrogate are two names to the strict parse, one after re-encoding.
        assertNull(VoteSync.jsonObject("{\"?\":1,\"\\ud800\":2}".toByteArray()))
        assertTrue(VoteSync.jsonObject("{\"?\":1,\"b\":2}".toByteArray()) != null)
    }

    @Test
    fun anOversizedIndexElementIsLeftOutBeforeItIsParsed() {
        val big = "{\"proposal_id\":\"$pid\",\"pad\":\"" + "x".repeat(VoteSync.INDEX_ELEMENT_MAX) + "\"}"
        val small = "{\"proposal_id\":\"01JBQZ0000000000000000000B\"}"
        assertEquals(listOf("01JBQZ0000000000000000000B"), VoteSync.indexOf("[$big,$small]".toByteArray()).map { VoteSync.proposalIdOf(it) })
    }

    @Test
    fun choiceIdsAreTheVaults() {
        for (good in listOf("yes", "no", "abstain", "option-2", "a".repeat(32))) assertTrue(good, VoteSync.validChoiceId(good))
        for (bad in listOf("", "Yes", "a b", "a_b", "a".repeat(33), "é")) assertFalse(bad, VoteSync.validChoiceId(bad))
    }

    @Test
    fun eachIndexElementIsJudgedOnItsOwn() {
        val good = """{"proposal_id":"$pid","service_id":"s","note":"a ] , \" { inside"}"""
        val index = """[ $good, {"proposal_id":"$pid","a":1,"a":2}, {"proposal_id":"not-a-ulid"}, 7, {"proposal_id":"01JBQZ0000000000000000000B"} ]"""
        val out = VoteSync.indexOf(index.toByteArray())
        assertEquals(listOf(pid, "01JBQZ0000000000000000000B"), out.map { VoteSync.proposalIdOf(it) })
        // Not an array, or broken: none.
        assertTrue(VoteSync.indexOf("{}".toByteArray()).isEmpty())
        assertTrue(VoteSync.indexOf("[{\"proposal_id\":".toByteArray()).isEmpty())
        assertTrue(VoteSync.indexOf("[]".toByteArray()).isEmpty())
    }

    @Test
    fun theSplitterFindsEachElementsBytes() {
        val parts = VoteSync.elements("""[{"a":"]"},[1,2], "x\"y" ,{}]""".toByteArray()).map { String(it) }
        assertEquals(listOf("""{"a":"]"}""", "[1,2]", "\"x\\\"y\"", "{}"), parts)
    }
}
