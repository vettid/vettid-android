package com.vettid.core.data.feed

import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.social.Approval
import com.vettid.core.vault.FeedItem
import com.vettid.core.vault.FeedPage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * (Owner decision a, 2026-11-15) After a hold ended by a passed check, each ask still waiting is notified once: a
 * sign-in, a device session, a held approval; never an expired one, a decided one, a pairing or anything else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaitingAsksTest {
    private val now = Instant.parse("2026-11-15T14:13:00Z")

    private fun item(id: String, kind: String, ref: String = "r$id", minutesAgo: Long = 1, status: String = "active", seq: Long = 1) =
        FeedItem(id, seq, kind, now.minusSeconds(minutesAgo * 60).toString(), status, ref = ref)

    private fun signIn(ref: String, exp: Instant?) = Approval.Authentication(ref, "c1", null, now.minusSeconds(60), exp)

    private fun device(kind: String, ref: String, exp: Instant?) = Approval.DeviceRequest(
        type = kind, id = ref, deviceName = null, role = null, requestType = null, receivedAt = now.minusSeconds(60), exp = exp,
    )

    @Test
    fun theThreeAsksStillWaitingAndNothingElse() {
        val items = listOf(
            item("1", WaitingAsks.SIGN_IN),
            item("2", WaitingAsks.SESSION),
            item("3", WaitingAsks.HELD),
            item("4", "device.pair.pending", minutesAgo = 3), // left out: cannot be told waiting from done
            item("5", "message.received"), // not an ask: never replayed
            item("6", "connection.request"), // an ask that does not expire: as before (0.1.23)
        )
        val approvals = listOf(
            signIn("r1", now.plusSeconds(200)),
            device(WaitingAsks.SESSION, "r2", now.plusSeconds(300)),
            device(WaitingAsks.HELD, "r3", null), // no exp: waiting while listed
        )
        assertEquals(setOf("1", "2", "3"), WaitingAsks.of(items, approvals, now).map { it.itemId }.toSet())
    }

    @Test
    fun anExpiredDecidedOrUnlistedAskIsNotReplayed() {
        val items = listOf(
            item("1", WaitingAsks.SIGN_IN), // expired
            item("2", WaitingAsks.SESSION), // no longer listed: decided or gone
            item("3", WaitingAsks.HELD, status = "read"), // seen in Notifications
            item("4", WaitingAsks.HELD, status = "archived"),
            item("5", "device.pair.pending", minutesAgo = 1), // a pairing, even a fresh one
            item("6", WaitingAsks.SIGN_IN, ref = ""), // no ref: nothing to match
        )
        val approvals = listOf(
            signIn("r1", now.minusSeconds(1)),
            device(WaitingAsks.HELD, "r3", now.plusSeconds(100)),
            device(WaitingAsks.HELD, "r4", now.plusSeconds(100)),
            signIn("", now.plusSeconds(100)),
        )
        assertTrue(WaitingAsks.of(items, approvals, now).isEmpty())
    }

    /** Exactly the three kinds: another ask listed with its approval (a grant request, a pairing) is still left out. */
    @Test
    fun anotherListedKindIsLeftOut() {
        val items = listOf(item("1", "grant.request"), item("2", "device.pair.pending"), item("3", WaitingAsks.SIGN_IN))
        val approvals = listOf(
            Approval.GrantRequest("r1", "c1", emptyList(), null, null, null, now.minusSeconds(60), now.plusSeconds(300)),
            device("device.pair.pending", "r2", now.plusSeconds(300)),
            signIn("r3", now.plusSeconds(300)),
        )
        assertEquals(listOf("3"), WaitingAsks.of(items, approvals, now).map { it.itemId })
    }

    private class Ops(val items: List<FeedItem>) : FeedOps {
        override suspend fun list(afterSeq: Long, limit: Int) =
            if (afterSeq == 0L) FeedPage(items, items.maxOf { it.seq }) else FeedPage(emptyList(), items.maxOf { it.seq })

        override suspend fun update(itemId: String, status: String): FeedItem = throw VaultOpException("feed.update", "not_found")

        override suspend fun delete(itemId: String) = Unit

        override suspend fun retentionDays(): Int? = null
    }

    /** The full read notifies nothing; the replay after the passed check makes each waiting ask arrive, once. */
    @Test
    fun theReplayMakesOnlyTheWaitingAsksArrive() = runTest {
        val items = listOf(item("1", WaitingAsks.SIGN_IN, seq = 1), item("2", "message.received", seq = 2))
        var gated = false
        val m = FeedManager(this, ops = { Ops(items) }, gated = { gated }, now = { now })
        val arrived = mutableListOf<FeedItem>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.refresh()
        advanceUntilIdle()
        assertEquals(ListLoad.LOADED, m.load.value)
        assertTrue("a full read notifies nothing", arrived.isEmpty())
        m.replayWaitingAsks(listOf(signIn("r1", now.plusSeconds(200))), now)
        assertEquals(listOf("1"), arrived.map { it.itemId })
        gated = true // held again: nothing replays
        m.replayWaitingAsks(listOf(signIn("r1", now.plusSeconds(200))), now)
        assertEquals(1, arrived.size)
        job.cancel()
    }

    /** An ask that arrived live while the hold's reads ran is not sent again by the replay. */
    @Test
    fun anAskThatArrivedDuringTheReadsIsNotSentTwice() = runTest {
        val items = listOf(item("1", WaitingAsks.SIGN_IN, seq = 1), item("2", WaitingAsks.SIGN_IN, seq = 2))
        val m = FeedManager(this, ops = { Ops(items) }, now = { now })
        val arrived = mutableListOf<FeedItem>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.startReplayWindow()
        m.refresh()
        advanceUntilIdle()
        val live = items[1].copy(seq = 3)
        m.onEvent(
            com.vettid.core.vault.VaultMessage(
                com.vettid.core.crypto.envelope.Inner(
                    id = com.vettid.core.crypto.envelope.Ulid.new(), type = "feed.event", ts = now,
                    body = com.vettid.core.vault.VaultJson.json.encodeToString(FeedItem.serializer(), live).toByteArray(),
                ),
            ),
        )
        assertEquals(listOf("2"), arrived.map { it.itemId })
        m.replayWaitingAsks(listOf(signIn("r1", now.plusSeconds(200)), signIn("r2", now.plusSeconds(200))), now)
        assertEquals("only the one not already sent", listOf("2", "1"), arrived.map { it.itemId })
        job.cancel()
    }
}
