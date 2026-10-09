// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.feed

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.FeedItem
import com.vettid.core.vault.FeedPage
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/** The vault's feed in the app (ANDROID-PLAN 0.1.23 Notifications, 2 and 11; VAULT-MESSAGING §10.9). */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedManagerTest {
    private val now = Instant.parse("2026-10-09T12:00:00Z")

    private class Ops : FeedOps {
        val lists = mutableListOf<Long>()
        val pages = ArrayDeque<FeedPage>()
        val updates = mutableListOf<Pair<String, String>>()
        val deletes = mutableListOf<String>()
        val known = mutableMapOf<String, FeedItem>()
        var counter = 100L
        var gate: CompletableDeferred<Unit>? = null
        var refuse = setOf<String>()
        var retention: Int? = null

        override suspend fun list(afterSeq: Long, limit: Int): FeedPage {
            synchronized(this) { lists += afterSeq }
            gate?.await()
            return synchronized(this) { pages.removeFirstOrNull() } ?: FeedPage(emptyList(), counter)
        }

        override suspend fun update(itemId: String, status: String): FeedItem {
            if (itemId in refuse) throw VaultOpException("feed.update", "not_found")
            return synchronized(this) {
                updates += itemId to status
                val i = known.getValue(itemId).copy(seq = ++counter, status = status)
                known[itemId] = i
                i
            }
        }

        override suspend fun delete(itemId: String) {
            deletes += itemId
        }

        override suspend fun retentionDays(): Int? = retention
    }

    private fun item(id: String, seq: Long, minutesAgo: Long = seq, status: String = "active", kind: String = "message.received", priority: String = "normal") =
        FeedItem(id, seq, kind, now.minus(minutesAgo, ChronoUnit.MINUTES).toString(), status, priority, connectionId = "c1", ref = "r$id")

    private fun TestScope.manager(ops: Ops, gated: () -> Boolean = { false }) = FeedManager(this, ops = { ops }, gated = gated, now = { now })

    /** Loads [items] as the open read's one page. */
    private suspend fun TestScope.opened(ops: Ops, vararg items: FeedItem, gated: () -> Boolean = { false }): FeedManager {
        items.forEach { ops.known[it.itemId] = it }
        ops.pages += FeedPage(items.toList(), items.maxOfOrNull { it.seq } ?: 0)
        return manager(ops, gated).also { it.refresh() }
    }

    /** The manager's vault calls run on the IO dispatcher (vaultGuard): let them finish. */
    private fun TestScope.settle(done: () -> Boolean) {
        repeat(SETTLE_TRIES) {
            advanceUntilIdle()
            if (done()) return
            Thread.sleep(SETTLE_MS)
        }
        fail("not settled")
    }

    private fun event(item: FeedItem) = VaultMessage(
        Inner(id = Ulid.new(), type = "feed.event", ts = now, body = json.encodeToString(FeedItem.serializer(), item).toByteArray()),
    )

    private fun sync(kind: String, id: String, seq: Long) = VaultMessage(
        Inner(id = Ulid.new(), type = "sync.event", ts = now, body = """{"kind":"$kind","item_id":"$id","seq":$seq}""".toByteArray()),
    )

    @Test
    fun theOpenReadIsPagedFromTheLastSeqAndDropsDeletedItems() = runTest {
        val ops = Ops()
        val first = (1..FeedManager.PAGE).map { item("a$it", it.toLong(), minutesAgo = 600L - it) }
        ops.pages += FeedPage(first, 620)
        ops.pages += FeedPage(listOf(item("b1", 601, minutesAgo = 1), item("a7", 602, status = "deleted"), item("a8", 603, status = "read")), 620)
        val m = manager(ops)
        m.refresh()
        assertEquals(listOf(0L, FeedManager.PAGE.toLong()), ops.lists)
        assertEquals(FeedManager.PAGE, m.items.value.size) // 500 + b1 − a7
        assertNull(m.items.value.firstOrNull { it.itemId == "a7" })
        assertEquals("read", m.items.value.first { it.itemId == "a8" }.status)
        assertEquals(620L, m.seq)
        assertEquals(ListLoad.LOADED, m.load.value)
        // Newest first by `at`.
        assertEquals("b1", m.items.value.first().itemId)
    }

    @Test
    fun anEventInOrderIsAppliedAndAGapCatchesUpOnce() = runTest {
        val ops = Ops()
        val m = opened(ops, item("i1", 1), item("i2", 2))
        m.onEvent(event(item("i3", 3)))
        assertEquals(3L, m.seq)
        assertEquals(1, ops.lists.size) // no catch-up
        // A gap: seq 5 after 3 (4 is another device's read).
        ops.pages += FeedPage(listOf(item("i2", 4, status = "read"), item("i5", 5)), 5)
        m.onEvent(event(item("i5", 5)))
        settle { ops.lists.size == 2 }
        assertEquals(3L, ops.lists.last())
        assertEquals(5L, m.seq)
        assertEquals("read", m.items.value.first { it.itemId == "i2" }.status)
        // An event already applied changes nothing.
        m.onEvent(event(item("i5", 5)))
        advanceUntilIdle()
        assertEquals(2, ops.lists.size)
    }

    @Test
    fun syncEventsCatchUpCoalescedOneInFlightOneQueued() = runTest {
        val ops = Ops()
        val m = opened(ops, item("i1", 1))
        ops.gate = CompletableDeferred()
        m.onEvent(sync("feed.updated", "i1", 2))
        settle { ops.lists.size == 2 } // the first catch-up is in flight
        m.onEvent(sync("feed.updated", "i1", 3))
        m.onEvent(sync("feed.deleted", "i1", 4))
        m.onEvent(sync("feed.updated", "i9", 5))
        ops.pages += FeedPage(listOf(item("i1", 2, status = "read")), 2)
        ops.pages += FeedPage(listOf(item("i1", 4, status = "deleted")), 5)
        ops.gate?.complete(Unit)
        settle { ops.lists.size == 3 && m.seq == 5L }
        advanceUntilIdle()
        assertEquals(3, ops.lists.size) // the open read, one in flight, one queued
        assertTrue(m.items.value.isEmpty())
    }

    @Test
    fun aBatchsLaterAskUpdatesItsRowInPlaceKeepingTheStatus() = runTest {
        val ops = Ops()
        val batch = item("b", 2, minutesAgo = 30, kind = "grant.request").copy(count = 2, status = "read")
        val m = opened(ops, item("i1", 1, minutesAgo = 10), batch, item("i3", 3, minutesAgo = 40))
        val order = m.items.value.map { it.itemId }
        ops.pages += FeedPage(listOf(batch.copy(seq = 4, count = 3)), 4)
        m.onEvent(sync("feed.updated", "b", 4))
        settle { m.seq == 4L }
        val b = m.items.value.first { it.itemId == "b" }
        assertEquals(3, b.count)
        assertEquals("read", b.status)
        assertEquals(order, m.items.value.map { it.itemId }) // the batch keeps its first ask's `at`
        assertEquals(2, m.badge.value.unread) // i1 and i3
    }

    @Test
    fun ownChangesApplyTheAnswerAndAreUndoneWhenRefused() = runTest {
        val ops = Ops().apply { counter = 2 }
        val m = opened(ops, item("i1", 1), item("i2", 2))
        m.setStatus("i1", "archived")
        assertEquals("archived", m.items.value.first { it.itemId == "i1" }.status)
        assertEquals(3L, m.seq) // the answer's seq followed the cursor
        ops.refuse = setOf("i2")
        try {
            m.setStatus("i2", "read")
            fail("refused")
        } catch (e: VaultFailure) {
            assertEquals("not_found", e.code)
        }
        assertEquals("active", m.items.value.first { it.itemId == "i2" }.status)
        m.delete("i1")
        assertEquals(listOf("i1"), ops.deletes)
        assertNull(m.items.value.firstOrNull { it.itemId == "i1" })
    }

    @Test
    fun itemsPastRetentionAreDropped() = runTest {
        val ops = Ops().apply { retention = 7 }
        val m = opened(ops, item("new", 2, minutesAgo = 60L * 24 * 6), item("old", 1, minutesAgo = 60L * 24 * 8))
        assertEquals(listOf("new"), m.items.value.map { it.itemId })
    }

    @Test
    fun theUnreadCountAndUrgentBadge() = runTest {
        val m = opened(Ops(), item("i1", 1), item("i2", 2, status = "read"), item("i3", 3, priority = "urgent", kind = "credential.alarm"))
        assertEquals(FeedBadge(2, urgent = true), m.badge.value)
        m.setStatus("i3", "read")
        assertEquals(FeedBadge(1, urgent = false), m.badge.value)
    }

    @Test
    fun clearOnLockHeldOrWipeAndNothingListedWhileHeld() = runTest {
        var held = false
        val ops = Ops()
        val m = opened(ops, item("i1", 1), gated = { held })
        m.clear()
        assertTrue(m.items.value.isEmpty())
        assertEquals(FeedBadge(), m.badge.value)
        assertEquals(ListLoad.NOT_LOADED, m.load.value)
        assertEquals(0L, m.seq)
        // Held: the clone alarm's feed.event arrives, and is not listed; no catch-up either.
        held = true
        m.onEvent(event(item("alarm", 2, kind = "credential.alarm", priority = "urgent")))
        m.onEvent(sync("feed.updated", "i1", 3))
        advanceUntilIdle()
        assertTrue(m.items.value.isEmpty())
        assertEquals(1, ops.lists.size)
        // After the check: a full read, never replayed events.
        held = false
        ops.pages += FeedPage(listOf(item("i1", 1), item("alarm", 2, kind = "credential.alarm", priority = "urgent")), 3)
        m.refresh()
        assertEquals(0L, ops.lists.last())
        assertEquals(2, m.badge.value.unread)
    }

    @Test
    fun aReadStartedBeforeAClearIsNotApplied() = runTest {
        val ops = Ops().apply { gate = CompletableDeferred() }
        ops.pages += FeedPage(listOf(item("i1", 1)), 1)
        val m = manager(ops)
        m.open()
        settle { ops.lists.size == 1 }
        m.clear()
        ops.gate?.complete(Unit)
        settle { ops.pages.isEmpty() }
        advanceUntilIdle()
        Thread.sleep(SETTLE_MS)
        advanceUntilIdle()
        assertTrue(m.items.value.isEmpty())
    }

    @Test
    fun markAllReadGoesOnPastAFailureAndReportsIt() = runTest {
        val ops = Ops().apply { refuse = setOf("i3") }
        val m = opened(ops, *(1..6).map { item("i$it", it.toLong()) }.toTypedArray())
        try {
            m.markAllRead()
            fail("one refused")
        } catch (_: VaultFailure) {
            // reported after the others ran
        }
        assertEquals(5, ops.updates.size)
        assertEquals(listOf("i3"), m.items.value.filter { it.status == "active" }.map { it.itemId })
        assertEquals(1, m.badge.value.unread)
    }

    @Test
    fun anAskDecidedAndAConversationOpenedMarkTheirItemsRead() = runTest {
        val ops = Ops()
        val ask = item("g", 1, kind = "grant.request").copy(ref = "req1")
        val other = item("h", 2, kind = "grant.request").copy(ref = "req2")
        val msg = item("m", 3)
        val m = opened(ops, ask, other, msg)
        val (kinds, ref) = FeedAsks.of("grant:req1")!!
        m.markReadWhere(kinds, ref)
        m.markReadWhere(setOf(FeedAsks.MESSAGE_RECEIVED), connectionId = "c1")
        settle { ops.updates.size == 2 }
        assertEquals(setOf("g" to "read", "m" to "read"), ops.updates.toSet())
        assertFalse(m.items.value.first { it.itemId == "h" }.status == "read")
    }

    @Test
    fun askKeysMapToTheirFeedKinds() {
        assertEquals(setOf("connection.request") to "p1", FeedAsks.of("connection:p1"))
        assertEquals(setOf("approval.pending") to "a1", FeedAsks.of("device:approval.pending:a1"))
        assertEquals(setOf("device.session.pending") to "s1", FeedAsks.of("device:device.session.pending:s1"))
        assertNull(FeedAsks.of("outgoing:c1"))
        assertEquals("share:r1", FeedAsks.approvalKey("share.pending", "r1"))
        assertEquals("device:approval.pending:a1", FeedAsks.approvalKey("approval.pending", "a1"))
        assertNull(FeedAsks.approvalKey("message.received", "m1"))
    }

    private companion object {
        val json = kotlinx.serialization.json.Json { explicitNulls = false }
        const val SETTLE_TRIES = 500
        const val SETTLE_MS = 10L
    }
}
