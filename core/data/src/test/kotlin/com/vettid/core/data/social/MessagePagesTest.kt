package com.vettid.core.data.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Merging `message.list` pages (VAULT-MESSAGING 0.32.0 §10.5.2). */
class MessagePagesTest {
    private fun m(n: Int) = MessageInfo(
        "c1",
        "m%02d".format(n),
        outgoing = n % 2 == 0,
        "t$n",
        Instant.ofEpochSecond(1_000L + n),
        delivered = false,
        read = false,
    )

    private fun ids(l: List<MessageInfo>) = l.map { it.messageId.removePrefix("m").toInt() }

    @Test
    fun theFirstNewestPageAnnouncesOlderMessages() {
        val r = MessagePages.newest((6..10).map(::m), "m06", emptyList(), MessagePaging())
        assertEquals((6..10).toList(), ids(r.messages))
        assertEquals("m06", r.paging.nextBefore)
        assertTrue(r.paging.hasOlder)
        assertFalse(r.paging.olderLoaded)
    }

    @Test
    fun anOlderPageIsAddedAtTheTop() {
        val first = MessagePages.newest((6..10).map(::m), "m06", emptyList(), MessagePaging())
        val r = MessagePages.older((1..5).map(::m), null, first.messages)
        assertEquals((1..10).toList(), ids(r.messages))
        assertNull(r.paging.nextBefore)
        assertTrue(r.paging.olderLoaded)
    }

    @Test
    fun reloadingTheNewestPageKeepsTheOlderPagesItJoins() {
        val first = MessagePages.newest((6..10).map(::m), "m06", emptyList(), MessagePaging())
        val older = MessagePages.older((3..5).map(::m), "m03", first.messages)
        // Two new messages since; message 8 was deleted on another device; 9 is now read.
        val page = (listOf(7, 9, 10, 11, 12)).map(::m).map { if (it.messageId == "m09") it.copy(read = true) else it }
        val r = MessagePages.newest(page, "m07", older.messages, older.paging)
        assertEquals(listOf(3, 4, 5, 6, 7, 9, 10, 11, 12), ids(r.messages))
        assertTrue(r.messages.first { it.messageId == "m09" }.read)
        assertEquals("m03", r.paging.nextBefore) // the scrolled position is kept
        assertTrue(r.paging.olderLoaded)
    }

    @Test
    fun aNewestPageThatDoesNotJoinDropsTheOlderPagesRatherThanLeaveAHole() {
        val first = MessagePages.newest((6..10).map(::m), "m06", emptyList(), MessagePaging())
        val older = MessagePages.older((3..5).map(::m), "m03", first.messages)
        val r = MessagePages.newest((20..24).map(::m), "m20", older.messages, older.paging)
        assertEquals((20..24).toList(), ids(r.messages))
        assertEquals("m20", r.paging.nextBefore)
        assertFalse(r.paging.olderLoaded)
    }

    @Test
    fun messagesArrivedMeanwhileStay() {
        val current = (6..12).map(::m)
        val r = MessagePages.newest((6..10).map(::m), "m06", current, MessagePaging())
        assertEquals((6..12).toList(), ids(r.messages))
    }

    @Test
    fun aWholeHistoryInOnePageHasNothingOlder() {
        val r = MessagePages.newest((1..4).map(::m), null, emptyList(), MessagePaging())
        assertFalse(r.paging.hasOlder)
        val empty = MessagePages.newest(emptyList(), null, emptyList(), MessagePaging())
        assertTrue(empty.messages.isEmpty())
        assertFalse(empty.paging.hasOlder)
    }

    @Test
    fun anOlderPageDoesNotDuplicateWhatIsShown() {
        val r = MessagePages.older((4..6).map(::m), "m04", (6..8).map(::m))
        assertEquals((4..8).toList(), ids(r.messages))
    }
}
