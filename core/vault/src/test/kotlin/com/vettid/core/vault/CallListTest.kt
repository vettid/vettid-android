// Test data: one entry per line.
@file:Suppress("MaxLineLength")

package com.vettid.core.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** (GC #7718, the lead's decision #7715, as vettid-ios) `call.list`: an entry that does not read is skipped, never the whole answer. */
class CallListTest {
    private val good = """{"call_id":"01JX0000000000000000000001","connection_id":"conn-1","direction":"in","media":"audio","state":"ended","reason":"timeout","started_at":"2026-10-12T10:00:00.000Z"}"""
    private val good2 = good.replace("0001", "0002").replace("conn-1", "conn-2")

    private fun read(calls: String) = callListOf(Json.parseToJsonElement("""{"calls":$calls}""").jsonObject)

    @Test
    fun oneBadEntryAmongGoodOnesIsSkippedAndCounted() {
        val l = read("""[$good, {"call_id":"01JXBAD0000000000000000000","connection_id":"conn-1","direction":"in"}, $good2]""")
        assertEquals(listOf("01JX0000000000000000000001", "01JX0000000000000000000002"), l.calls.map { it.callId })
        assertEquals(1, l.skipped.size)
        assertEquals("01JXBAD0000000000000000000", l.skipped.single().callId)
        assertEquals("conn-1", l.skipped.single().connectionId)
        assertEquals("CallList(2 calls, 1 skipped)", l.toString())
        assertEquals("SkippedCall", l.skipped.single().toString())
    }

    @Test
    fun everyEntryBad() {
        val l = read("""[{"x":1}, 5, "s", {"call_id":"not a ulid","connection_id":7}]""")
        assertEquals(emptyList<CallRecord>(), l.calls)
        assertEquals(4, l.skipped.size)
        assertNull("an id that does not read is not kept", l.skipped.last().callId)
        assertNull(l.skipped.last().connectionId)
    }

    @Test
    fun noneBad() {
        val l = read("[$good, $good2]")
        assertEquals(2, l.calls.size)
        assertEquals(0, l.skipped.size)
        assertEquals(0, callListOf(Json.parseToJsonElement("{}").jsonObject).calls.size)
    }

    @Test
    fun aCallsMemberThatIsNotAnArrayDoesNotRead() {
        assertThrows(CallMessageException::class.java) { read("""{"a":1}""") }
    }

    /** vettid-ios's `callListSkipsEntriesItCannotRead…`, the same seven entries: two read, five are skipped. */
    @Test
    fun anUnknownDirectionOrStateOrATimeThatIsNoneDoesNotRead() {
        fun entry(id: String, dir: String = "in", state: String = "ended", started: String = "2026-10-12T10:00:00.000Z", extra: String = "") =
            """{"call_id":"$id","connection_id":"k","direction":"$dir","media":"audio","state":"$state","started_at":"$started"$extra}"""
        val l = read(
            listOf(
                entry("good"), entry("d", dir = "sideways"), entry("s", state = "weird"), entry("t", started = "yesterday"),
                entry("a", extra = ""","answered_at":"soon""""), entry("e", extra = ""","ended_at":5"""),
                entry("ok2", dir = "out", state = "active", extra = ""","answered_at":"2026-10-12T10:00:05.000Z""""),
            ).joinToString(",", "[", "]"),
        )
        assertEquals(listOf("good", "ok2"), l.calls.map { it.callId })
        assertEquals(5, l.skipped.size)
    }

    @Test
    fun aConnectionIdIsKeptOnlyWhenItReads() {
        val id = "01JXBAD0000000000000000000"
        fun skippedConn(conn: String) = read("""[{"call_id":"$id","connection_id":"$conn"}]""").skipped.single().connectionId
        assertNull("empty", skippedConn(""))
        assertNull("over 128", skippedConn("c".repeat(129)))
        assertEquals("c".repeat(128), skippedConn("c".repeat(128)))
    }

    /** (C2) A vault is C2 for the app only with `v: 2` and its `now`: either alone is not enough. */
    @Test
    fun formTwoNeedsTheVersionAndTheVaultsClock() {
        fun of(j: String) = callListOf(Json.parseToJsonElement(j).jsonObject)
        assertTrue(of("""{"v":2,"now":"2026-11-11T12:00:00.000Z","calls":[]}""").formTwo)
        assertFalse(of("""{"v":2,"calls":[]}""").formTwo)
        assertFalse(of("""{"now":"2026-11-11T12:00:00.000Z","calls":[]}""").formTwo)
        assertFalse(of("""{"v":"2","now":"2026-11-11T12:00:00.000Z","calls":[]}""").formTwo)
        assertFalse(of("""{"v":2,"now":"yesterday","calls":[]}""").formTwo)
    }
}
