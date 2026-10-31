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
        val huge = entry("HUGE", VoteSync.BODY_MAX)
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
}
