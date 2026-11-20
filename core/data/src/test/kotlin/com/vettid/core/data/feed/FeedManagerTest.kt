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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
@Suppress("LargeClass") // one feed: its reads, events, the release check's hold and the replays side by side
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
        var failLists = 0

        override suspend fun list(afterSeq: Long, limit: Int): FeedPage {
            synchronized(this) { lists += afterSeq }
            gate?.await()
            if (synchronized(this) { failLists > 0 && failLists-- > 0 }) throw VaultOpException("feed.list", "internal")
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

    private fun TestScope.manager(
        ops: Ops,
        gated: () -> Boolean = { false },
        held: () -> Boolean = { false },
        ownerGated: () -> Boolean = { false },
        vaultOpen: () -> Boolean = { true },
    ) = FeedManager(this, ops = { ops }, gated = gated, now = { now }, held = held, ownerGated = ownerGated, vaultOpen = vaultOpen)

    /** Loads [items] as the open read's one page. */
    private suspend fun TestScope.opened(
        ops: Ops,
        vararg items: FeedItem,
        gated: () -> Boolean = { false },
        held: () -> Boolean = { false },
        ownerGated: () -> Boolean = { false },
    ): FeedManager {
        items.forEach { ops.known[it.itemId] = it }
        ops.pages += FeedPage(items.toList(), items.maxOfOrNull { it.seq } ?: 0)
        // The vault opened just now: the events the tests send (at `now`) were sent since.
        return manager(ops, gated, held, ownerGated).also {
            it.vaultOpened(now)
            it.refresh()
        }
    }

    /** [opened], the vault opened ten minutes ago: the events the tests send since are news, not backlog. */
    private suspend fun TestScope.openedEarlier(ops: Ops, vararg items: FeedItem, held: () -> Boolean = { false }): FeedManager {
        items.forEach { ops.known[it.itemId] = it }
        ops.pages += FeedPage(items.toList(), items.maxOfOrNull { it.seq } ?: 0)
        return manager(ops, held = held).also {
            it.vaultOpened(now.minusSeconds(600))
            it.refresh()
        }
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

    private fun event(item: FeedItem, ts: Instant = now) = VaultMessage(
        Inner(id = Ulid.new(), type = "feed.event", ts = ts, body = json.encodeToString(FeedItem.serializer(), item).toByteArray()),
    )

    private fun sync(kind: String, id: String, seq: Long) = VaultMessage(
        Inner(id = Ulid.new(), type = "sync.event", ts = now, body = """{"kind":"$kind","item_id":"$id","seq":$seq}""".toByteArray()),
    )

    // --- Rule 3: the release check holds the feed's events and arrivals ---

    /**
     * While the release is checked (and while that check fails, which pauses the feed), a live event and a held-back one
     * replayed after a push wake wait; they arrive when the check is over with the vault not behind ([FeedManager.releaseHeld]).
     */
    @Test
    fun eventsDuringTheReleaseCheckArriveWhenItIsOver() = runTest {
        val ops = Ops()
        var checking = true
        var paused = false
        val m = opened(ops, item("i1", 1), gated = { paused }, held = { checking })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.onEvent(event(item("i2", 2)))
        // The check failed: the feed is paused, the push wake's held-back event waits all the same.
        paused = true
        m.replay(event(item("i3", 3)), wake = true)
        // A live one while the failed check pauses the feed waits too.
        m.onEvent(event(item("i4", 4)))
        settle { true }
        assertTrue("nothing during the check", arrived.isEmpty())
        // The check is over, the vault current.
        checking = false
        paused = false
        m.releaseHeld()
        settle { arrived.size >= 3 }
        assertEquals(listOf("i2", "i3", "i4"), arrived.map { it.itemId })
        m.releaseHeld()
        assertEquals("once", 3, arrived.size)
    }

    /** Ended behind, they wait for the lift (nothing comes while paused); a lock (a clear) drops them. */
    @Test
    fun heldEventsWaitForTheLiftAndGoWithALock() = runTest {
        val ops = Ops()
        var checking = true
        var paused = false
        val m = opened(ops, item("i1", 1), gated = { paused }, held = { checking })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.onEvent(event(item("i2", 2)))
        checking = false
        paused = true
        m.onEvent(event(item("i3", 3)))
        settle { true }
        assertTrue(arrived.isEmpty())
        paused = false
        m.releaseHeld()
        settle { arrived.isNotEmpty() }
        assertEquals("the one taken during the check; none while behind", listOf("i2"), arrived.map { it.itemId })
        checking = true
        m.onEvent(event(item("i4", 4)))
        m.clear()
        checking = false
        m.releaseHeld()
        settle { true }
        assertEquals(listOf("i2"), arrived.map { it.itemId })
    }

    /**
     * (Owner decision 2026-11-18, as the iPhone app) While the owner check is due or held nothing is held for the release
     * check's end: an event that comes then is dropped as a live one is, so it does not notify at the lift. One held
     * before the check came due goes too.
     */
    @Test
    fun anEventWhileTheOwnerCheckGatesDuringTheReleaseCheckIsNotNotifiedAfterTheLift() = runTest {
        val ops = Ops()
        var checking = true
        var ownerHeld = false
        val m = opened(ops, item("i1", 1), gated = { ownerHeld }, held = { checking }, ownerGated = { ownerHeld })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.onEvent(event(item("i2", 2))) // held: the owner check is fine
        ownerHeld = true
        m.onEvent(event(item("i3", 3)))
        m.replay(event(item("i4", 4)), wake = true)
        settle { true }
        assertTrue(arrived.isEmpty())
        // The check passes and the release check ends: the gate lifts and what was held is released.
        ownerHeld = false
        checking = false
        m.releaseHeld()
        settle { arrived.isNotEmpty() }
        assertEquals("only the one held before the owner check came due", listOf("i2"), arrived.map { it.itemId })
        // Held, then the owner check comes due before the release: nothing.
        checking = true
        m.onEvent(event(item("i5", 5)))
        ownerHeld = true
        m.releaseHeld()
        ownerHeld = false
        checking = false
        m.releaseHeld()
        settle { true }
        assertEquals(listOf("i2"), arrived.map { it.itemId })
    }

    /** (Owner decision 2026-11-18) A held arrival the feed no longer lists when it is released (deleted) is not notified. */
    @Test
    fun aHeldArrivalTheFeedNoLongerListsIsNotNotified() = runTest {
        val ops = Ops()
        var checking = true
        val m = opened(ops, item("i1", 1), held = { checking })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        ops.pages += FeedPage(listOf(item("i2", 2), item("i3", 3)), 3)
        m.catchUp()
        settle { m.seq == 3L }
        assertTrue(arrived.isEmpty())
        // i3 deleted on another device meanwhile: the feed no longer lists it.
        ops.pages += FeedPage(listOf(item("i3", 4, status = "deleted")), 4)
        m.catchUp()
        settle { m.seq == 4L }
        checking = false
        m.releaseHeld()
        settle { arrived.isNotEmpty() }
        assertEquals(listOf("i2"), arrived.map { it.itemId })
    }

    /** A catch-up's arrivals during the check wait too, and come as the feed has them then (not at all once read). */
    @Test
    fun aCatchUpsArrivalsDuringTheCheckWait() = runTest {
        val ops = Ops()
        var checking = true
        val m = opened(ops, item("i1", 1), held = { checking })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        ops.pages += FeedPage(listOf(item("i2", 2), item("i3", 3)), 3)
        m.catchUp()
        settle { m.seq == 3L }
        assertTrue(arrived.isEmpty())
        // i3 read on another device meanwhile.
        ops.pages += FeedPage(listOf(item("i3", 4, status = "read")), 4)
        m.catchUp()
        settle { m.seq == 4L }
        checking = false
        m.releaseHeld()
        settle { arrived.isNotEmpty() }
        assertEquals(listOf("i2"), arrived.map { it.itemId })
    }

    /**
     * A held event whose item already arrived among the held arrivals (a catch-up brought it during the check) is applied
     * but not notified a second time when they are released.
     */
    @Test
    fun aHeldEventOfAnItemThatJustArrivedIsNotNotifiedTwice() = runTest {
        val ops = Ops()
        var checking = true
        val m = opened(ops, item("i1", 1), held = { checking })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        ops.pages += FeedPage(listOf(item("i2", 2)), 2)
        m.catchUp()
        settle { m.seq == 2L }
        m.onEvent(event(item("i2", 2)))
        m.onEvent(event(item("i3", 3)))
        settle { true }
        assertTrue(arrived.isEmpty())
        checking = false
        m.releaseHeld()
        settle { arrived.size >= 2 }
        assertEquals("i2 once, then i3", listOf("i2", "i3"), arrived.map { it.itemId })
        assertEquals("the events still applied", 3L, m.seq)
    }

    /**
     * The check is over but its held events have not come yet (the gate's notice is on its way): a live event and a live
     * arrival then wait behind them and release them first, so none overtakes them; the release that follows adds nothing.
     */
    @Test
    fun aLiveEventAfterTheCheckNeverOvertakesTheHeldOnes() = runTest {
        val ops = Ops()
        var checking = true
        val m = opened(ops, item("i1", 1), held = { checking })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.onEvent(event(item("i2", 2)))
        m.onEvent(event(item("i3", 3)))
        settle { true }
        assertTrue(arrived.isEmpty())
        checking = false
        m.onEvent(event(item("i4", 4)))
        settle { arrived.size >= 3 }
        assertEquals(listOf("i2", "i3", "i4"), arrived.map { it.itemId })
        m.releaseHeld()
        m.onEvent(event(item("i5", 5)))
        settle { arrived.size >= 4 }
        assertEquals("nothing twice; a later one comes as usual", listOf("i2", "i3", "i4", "i5"), arrived.map { it.itemId })
    }

    /**
     * (ANDROID-PLAN §6.7, the lead's rule for both apps) The mailbox's backlog at an opening stays silent: a held event the
     * open read covers notifies only if the vault sent it since the vault opened. One the read does not cover is applied as
     * a live one, and a push wake's held-back event (what the push was for) notifies whenever it was sent.
     */
    @Test
    fun theOpeningsBacklogStaysSilentAndWhatCameSinceNotifies() = runTest {
        val ops = Ops()
        var checking = true
        val m = opened(ops, item("i1", 1), item("i2", 2), item("i3", 3), held = { checking })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        val before = now.minusSeconds(60)
        m.onEvent(event(item("i1", 1), ts = before)) // backlog, covered by the open read
        m.onEvent(event(item("i2", 2))) // sent since the opening, covered
        m.replay(event(item("i3", 3), ts = before), wake = true) // a push wake's, covered
        settle { true }
        assertTrue(arrived.isEmpty())
        checking = false
        m.releaseHeld()
        settle { arrived.size >= 2 }
        settle { true }
        assertEquals(listOf("i2", "i3"), arrived.map { it.itemId })
    }

    /**
     * (ANDROID-PLAN 0.1.134) The opening's moment is the phone's clock until the first answer of this opening puts it on the
     * vault's ([FeedManager.openingMarked]); judged at release, it tells the backlog (sent before the opening, on the vault's
     * clock) from what came since, with the phone's clock ahead of the vault's and behind it.
     */
    @Test
    fun theOpeningIsOnTheVaultsClockFromItsFirstAnswer() = runTest {
        for (skewMinutes in listOf(10L, -10L)) {
            val ops = Ops()
            var checking = true
            val vaultOpen = now.minusSeconds(3_600)
            listOf(item("old", 1), item("new", 2)).forEach { ops.known[it.itemId] = it }
            ops.pages += FeedPage(listOf(item("old", 1), item("new", 2)), 2)
            val m = manager(ops, held = { checking })
            val opening = m.vaultOpened(vaultOpen.plusSeconds(skewMinutes * 60)) // the phone's clock at the opening
            m.refresh()
            val arrived = mutableListOf<FeedItem>()
            val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
            m.onEvent(event(item("old", 1), ts = vaultOpen.minusSeconds(300))) // the mailbox's backlog
            m.onEvent(event(item("new", 2), ts = vaultOpen.plusSeconds(60))) // sent since the opening
            m.openingMarked(vaultOpen, opening) // the first answer of this opening
            assertEquals(vaultOpen, m.openingMark)
            checking = false
            m.releaseHeld()
            settle { arrived.isNotEmpty() }
            settle { true }
            assertEquals("phone ${skewMinutes}min", listOf("new"), arrived.map { it.itemId })
            job.cancel()
        }
    }

    /** After a clear (a lock, the hold), a mark the device tells belongs to no opening: ignored until the next opening. */
    @Test
    fun aMarkAfterAClearIsIgnored() = runTest {
        val m = manager(Ops())
        val opening = m.vaultOpened(now)
        m.clear()
        m.openingMarked(now.minusSeconds(50), opening)
        assertEquals(now, m.openingMark)
        val next = m.vaultOpened(now.plusSeconds(10))
        m.openingMarked(now.plusSeconds(5), next)
        assertEquals(now.plusSeconds(5), m.openingMark)
    }

    /**
     * (ANDROID-PLAN 0.1.134) A held-back event of a start no push caused that comes while the feed's full read is still under
     * way (the release check already over) waits for the read and is judged then: a day-old backlog stays silent.
     */
    @Test
    fun anOrdinaryStartsHeldBackEventWaitsForTheFullRead() = runTest {
        val ops = Ops()
        val m = manager(ops)
        m.vaultOpened(now)
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        val gate = CompletableDeferred<Unit>()
        ops.gate = gate
        ops.known["b1"] = item("b1", 1)
        ops.pages += FeedPage(listOf(item("b1", 1)), 1)
        m.open() // the opening's read, held at its list
        settle { true }
        m.replay(event(item("b1", 1), ts = now.minus(1, ChronoUnit.DAYS)), wake = false)
        settle { true }
        assertTrue("not applied as a live one: $arrived", arrived.isEmpty())
        gate.complete(Unit)
        settle { m.load.value == ListLoad.LOADED }
        settle { true }
        assertTrue("backlog: silent", arrived.isEmpty())
        // A push wake's is what the push was for, so it is judged at once: not held for the read.
        val ops2 = Ops()
        val m2 = manager(ops2)
        m2.vaultOpened(now)
        val arrived2 = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m2.arrivals.collect { arrived2 += it } }
        val gate2 = CompletableDeferred<Unit>()
        ops2.gate = gate2
        ops2.known["b1"] = item("b1", 1)
        ops2.pages += FeedPage(listOf(item("b1", 1)), 1)
        m2.open()
        settle { true }
        m2.replay(event(item("b1", 1), ts = now.minus(1, ChronoUnit.DAYS)), wake = true)
        gate2.complete(Unit)
        settle { arrived2.isNotEmpty() }
        assertEquals(listOf("b1"), arrived2.map { it.itemId })
    }

    /**
     * A held-back event of an ordinary start that comes after a lock (it was on its way while the names were read) is kept
     * nowhere and starts no read: nothing is held outside an open vault, and the next opening sees nothing of it.
     */
    @Test
    fun aHeldBackEventAfterALockIsDropped() = runTest {
        val ops = Ops()
        var open = true
        ops.known["b1"] = item("b1", 1)
        ops.pages += FeedPage(listOf(item("b1", 1)), 1)
        val m = manager(ops, vaultOpen = { open }).also {
            it.vaultOpened(now)
            it.refresh()
        }
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        open = false
        m.clear() // the lock
        val reads = ops.lists.size
        m.replay(event(item("b1", 1)), wake = false)
        // A read would run on the IO dispatcher: give it the time it would take.
        repeat(SETTLE_TRIES / 10) {
            advanceUntilIdle()
            Thread.sleep(SETTLE_MS)
        }
        assertEquals("no read while locked", reads, synchronized(ops) { ops.lists.size })
        // The next opening: its read releases nothing of it.
        open = true
        m.vaultOpened(now)
        m.refresh()
        settle { true }
        assertTrue(arrived.isEmpty())
    }

    /**
     * (ANDROID-PLAN 0.1.132, 0.1.134) The waiting-asks replay is the replay rule's own second alert: an ask notified live
     * earlier in the same opening (no clear between, as when a due state known only from the clock ends) is replayed.
     */
    @Test
    fun aWaitingAsksReplayIsNotHeldBackByTheNoRepeatRule() = runTest {
        val ops = Ops()
        val ask = item("a1", 1, kind = WaitingAsks.SIGN_IN)
        val m = opened(ops)
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        ops.known["a1"] = ask
        m.onEvent(event(ask))
        settle { arrived.size == 1 }
        val token = m.startReplayWindow()
        assertTrue(m.replayWaitingAsks(token, listOf(com.vettid.core.data.social.Approval.Authentication("ra1", "c1", null, now.minusSeconds(60), now.plusSeconds(300))), now))
        assertEquals(2, arrived.size)
        assertTrue(arrived.last().replayed)
    }

    /**
     * (ANDROID-PLAN 0.1.134; the live path) After the check, while the opening's full read is still under way, a live event
     * of the mailbox's backlog waits for the read and is judged then: silent. One sent since the opening notifies.
     */
    @Test
    fun aLiveBacklogEventDuringTheOpeningsReadStaysSilent() = runTest {
        val ops = Ops()
        val m = manager(ops)
        m.vaultOpened(now)
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        val gate = CompletableDeferred<Unit>()
        ops.gate = gate
        listOf(item("old", 1), item("new", 2)).forEach { ops.known[it.itemId] = it }
        ops.pages += FeedPage(listOf(item("old", 1), item("new", 2)), 2)
        m.open() // the check is over (no hold); the opening's read, held at its list
        settle { true }
        m.onEvent(event(item("old", 1), ts = now.minus(1, ChronoUnit.DAYS))) // backlog, live
        m.onEvent(event(item("new", 2), ts = now.plusSeconds(5))) // since the opening, live
        settle { true }
        assertTrue("nothing before the read: $arrived", arrived.isEmpty())
        gate.complete(Unit)
        settle { arrived.isNotEmpty() }
        settle { true }
        assertEquals(listOf("new"), arrived.map { it.itemId })
    }

    /** The same after a full read that failed: the backlog waits for the next read, which the event starts, and stays silent. */
    @Test
    fun aLiveBacklogEventAfterAFailedReadWaitsForTheNextOne() = runTest {
        val ops = Ops()
        val m = manager(ops)
        m.vaultOpened(now)
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        ops.failLists = 1
        m.open()
        settle { m.load.value == ListLoad.FAILED }
        ops.known["old"] = item("old", 1)
        ops.pages += FeedPage(listOf(item("old", 1)), 1)
        m.onEvent(event(item("old", 1), ts = now.minus(1, ChronoUnit.DAYS)))
        settle { m.load.value == ListLoad.LOADED }
        settle { true }
        assertTrue("backlog: silent: $arrived", arrived.isEmpty())
    }

    /**
     * (Rule 3) A read that failed leaves the feed behind: the next release reads first (an item read elsewhere meanwhile
     * does not notify), and when nothing comes, one catch-up after a while releases what is held.
     */
    @Test
    fun afterAFailedReadTheReleaseCatchesUpFirst() = runTest {
        val ops = Ops()
        var checking = true
        val m = opened(ops, item("i1", 1), held = { checking })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.onEvent(event(item("i1", 1))) // covered, sent since the opening: would notify
        checking = false
        ops.failLists = 1
        m.catchUp() // fails: behind
        // Without moving the clock, so that the one scheduled catch-up does not run first.
        val settledNow = { done: () -> Boolean ->
            repeat(SETTLE_TRIES) {
                runCurrent()
                if (done()) return@repeat
                Thread.sleep(SETTLE_MS)
            }
        }
        settledNow { ops.failLists == 0 && synchronized(ops) { ops.lists.size } >= 2 }
        // i1 was read on another device meanwhile; a live event comes.
        ops.pages += FeedPage(listOf(item("i1", 3, status = "read")), 3)
        m.onEvent(event(item("i4", 4)))
        settledNow { m.seq >= 3L }
        runCurrent()
        assertTrue(m.seq >= 3L)
        assertFalse("read elsewhere: $arrived", arrived.any { it.itemId == "i1" })
    }

    /**
     * (ANDROID-PLAN 0.1.134) A full read that fails and leaves the feed not loaded starts no read of its own, also with
     * something held: the next event starts one.
     */
    @Test
    fun aFailedFullReadStartsNoReadOfItsOwn() = runTest {
        val ops = Ops()
        var checking = true
        val m = manager(ops, held = { checking })
        m.vaultOpened(now)
        m.onEvent(event(item("i1", 1))) // held
        checking = false
        ops.failLists = 1
        m.open()
        settle { m.load.value == ListLoad.FAILED }
        val reads = synchronized(ops) { ops.lists.size }
        advanceTimeBy(FeedManager.RETRY_AFTER_MS * 3)
        settle { true }
        assertEquals("no read of its own", reads, synchronized(ops) { ops.lists.size })
        assertEquals(ListLoad.FAILED, m.load.value)
    }

    /** (Rule 3) A failed read with something held: one catch-up later releases it, with no other trigger. */
    @Test
    fun aFailedReadIsTriedOnceMoreForWhatIsHeld() = runTest {
        val ops = Ops()
        var checking = true
        val m = opened(ops, item("i1", 1), held = { checking })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.onEvent(event(item("i2", 2)))
        checking = false
        // The failing read throws before it takes a page: this one is for the retry, whenever it runs.
        ops.pages += FeedPage(listOf(item("i2", 2)), 2)
        ops.failLists = 1
        m.catchUp()
        advanceTimeBy(FeedManager.RETRY_AFTER_MS + 100)
        settle { arrived.isNotEmpty() }
        assertTrue("the retry read it", synchronized(ops) { ops.lists.size } >= 3)
        assertEquals(listOf("i2"), arrived.map { it.itemId })
    }

    /** The one catch-up after a failed read does not run while the vault is not open (Unreachable since the failure). */
    @Test
    fun theRetryWaitsWhileTheVaultIsNotOpen() = runTest {
        val ops = Ops()
        var checking = true
        var open = true
        ops.known["i1"] = item("i1", 1)
        ops.pages += FeedPage(listOf(item("i1", 1)), 1)
        val m = manager(ops, held = { checking }, vaultOpen = { open }).also {
            it.vaultOpened(now)
            it.refresh()
        }
        m.onEvent(event(item("i2", 2)))
        checking = false
        ops.failLists = 1
        val gate = CompletableDeferred<Unit>()
        ops.gate = gate
        m.catchUp()
        // The failing read is out (held at the gate) when the vault stops being open; then it fails.
        repeat(SETTLE_TRIES) { if (synchronized(ops) { ops.lists.size } < 2) { runCurrent(); Thread.sleep(SETTLE_MS) } }
        assertEquals(2, synchronized(ops) { ops.lists.size })
        open = false
        ops.gate = null
        gate.complete(Unit)
        repeat(SETTLE_TRIES) { if (ops.failLists > 0) { runCurrent(); Thread.sleep(SETTLE_MS) } }
        advanceTimeBy(FeedManager.RETRY_AFTER_MS + 100)
        settle { true }
        assertEquals("no read while not open", 2, synchronized(ops) { ops.lists.size })
    }

    /**
     * (ANDROID-PLAN 0.1.134) A status read that could not be made, between two open phases, closed nothing: the opening
     * carries on with its moment, so an event the vault sent during the blip (after the opening, before "now") notifies.
     */
    @Test
    fun anOpeningCarriesOnAcrossAStatusReadThatFailed() = runTest {
        val ops = Ops()
        var checking = true
        val opened = now.minusSeconds(600)
        val m = manager(ops, held = { checking })
        val n = m.vaultOpened(opened)
        m.openingMarked(opened, n)
        m.clear(keepHeld = true) // Unreachable: the clears of a lock, soft
        assertTrue(m.openingContinues())
        assertEquals(opened, m.openingMark)
        m.vaultOpened(now) // what a new opening would have done, for contrast below
        val fresh = manager(Ops())
        assertFalse("no opening to carry on", fresh.openingContinues())
        // Back in the carried-on opening: an event of the blip, covered by the read, notifies.
        val m2 = manager(ops, held = { checking })
        val n2 = m2.vaultOpened(opened)
        m2.openingMarked(opened, n2)
        m2.clear(keepHeld = true)
        assertTrue(m2.openingContinues())
        ops.known["b"] = item("b", 1)
        ops.pages += FeedPage(listOf(item("b", 1)), 1)
        m2.refresh()
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m2.arrivals.collect { arrived += it } }
        m2.replay(event(item("b", 1), ts = now.minusSeconds(60)), wake = false)
        checking = false
        m2.releaseHeld()
        settle { arrived.isNotEmpty() }
        assertEquals(listOf("b"), arrived.map { it.itemId })
    }

    /**
     * A held waiting-asks replay that a newer arrival of the same item replaces in the held arrivals (a catch-up during the
     * check) is still a replay when released: posted as a new arrival.
     */
    @Test
    fun aHeldReplayReplacedByACatchUpsArrivalStaysAReplay() = runTest {
        val ops = Ops()
        var checking = true
        val ask = item("a1", 1, kind = WaitingAsks.SIGN_IN)
        val m = opened(ops, ask, held = { checking })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        val token = m.startReplayWindow()
        val approval = com.vettid.core.data.social.Approval.Authentication("ra1", "c1", null, now.minusSeconds(60), now.plusSeconds(300))
        assertTrue(m.replayWaitingAsks(token, listOf(approval), now))
        ops.pages += FeedPage(listOf(ask.copy(seq = 2)), 2)
        m.catchUp() // its arrival replaces the held replay
        settle { m.seq == 2L }
        assertTrue(arrived.isEmpty())
        checking = false
        m.releaseHeld()
        settle { arrived.isNotEmpty() }
        assertEquals(2L, arrived.single().seq)
        assertTrue("still a replay", arrived.single().replayed)
    }

    /**
     * A lock while a push wake's replay is still on its way (the release check still running): what it brings after the
     * lock's clear is held nowhere, so nothing of it notifies at the next opening, whatever its `ts`.
     */
    @Test
    fun aWakeReplayAfterALockIsNotKeptForTheNextOpening() = runTest {
        val ops = Ops()
        var open = true
        var checking = true
        ops.known["w1"] = item("w1", 1)
        ops.pages += FeedPage(listOf(item("w1", 1)), 1)
        val m = manager(ops, held = { checking }, vaultOpen = { open }).also {
            it.vaultOpened(now)
            it.refresh()
        }
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        open = false
        m.clear() // the lock
        m.replay(event(item("w1", 1)), wake = true) // the replay, arriving after the clear
        m.onEvent(event(item("w2", 2))) // and a live one
        // The next opening, and its check's end.
        open = true
        m.vaultOpened(now)
        ops.pages += FeedPage(listOf(item("w1", 1)), 1)
        m.refresh()
        checking = false
        m.releaseHeld()
        settle { true }
        assertTrue("nothing held across the lock: $arrived", arrived.isEmpty())
    }

    /**
     * After a hold (its clears empty the feed, the vault stays open), a live event that comes while the hold's full read is
     * under way waits for it and notifies; after that read failed, the event starts a read and notifies (it never stalls).
     */
    @Test
    fun afterAHoldALiveEventWaitsForTheReadAndNotifies() = runTest {
        for (fails in listOf(false, true)) {
            val ops = Ops()
            val m = opened(ops, item("i1", 1))
            val arrived = mutableListOf<FeedItem>()
            val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
            m.clear() // the hold
            ops.known["i2"] = item("i2", 2)
            if (fails) {
                ops.failLists = 1
                m.open()
                settle { m.load.value == ListLoad.FAILED }
                ops.pages += FeedPage(listOf(item("i1", 1), item("i2", 2)), 2)
                m.onEvent(event(item("i2", 2)))
            } else {
                val gate = CompletableDeferred<Unit>()
                ops.gate = gate
                ops.pages += FeedPage(listOf(item("i1", 1)), 1)
                m.open() // the hold's full read, held at its list
                settle { true }
                m.onEvent(event(item("i2", 2)))
                settle { true }
                assertTrue(arrived.isEmpty())
                ops.gate = null
                gate.complete(Unit)
            }
            settle { arrived.isNotEmpty() }
            assertEquals("fails $fails", listOf("i2"), arrived.map { it.itemId })
            job.cancel()
        }
    }

    /**
     * (ANDROID-PLAN 0.1.134) A status read that could not be made during the release check: its clears keep what the check
     * holds (keepHeld), so when the opening continues, the held events notify at the check's end; a real close drops them.
     */
    @Test
    fun aBlipDuringTheCheckKeepsWhatItHolds() = runTest {
        for (close in listOf(false, true)) {
            val ops = Ops()
            var checking = true
            val m = opened(ops, item("i1", 1), held = { checking })
            val arrived = mutableListOf<FeedItem>()
            val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
            m.onEvent(event(item("i2", 2))) // held by the check
            m.clear(keepHeld = true) // Unreachable
            if (close) m.clear() // then a lock
            assertTrue(m.openingContinues() || close)
            ops.pages += FeedPage(listOf(item("i1", 1), item("i2", 2)), 2)
            m.refresh()
            checking = false
            m.releaseHeld()
            settle { true }
            assertEquals("close $close", if (close) emptyList() else listOf("i2"), arrived.map { it.itemId })
            job.cancel()
        }
    }

    /**
     * (ANDROID-PLAN 0.1.134, "nothing missed while due or held notifies later") After the hold's lift, an event the vault sent
     * during the hold (left on the relay, collected now) is applied but notifies nothing; one sent after the lift notifies.
     */
    @Test
    fun anEventFromTheHoldCollectedAfterTheLiftIsSilent() = runTest {
        val ops = Ops()
        val m = openedEarlier(ops, item("i1", 1))
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.ownerCheckLifted(now)
        ops.pages += FeedPage(listOf(item("i1", 1), item("h2", 2)), 2) // the full read it waits for
        m.onEvent(event(item("h2", 2), ts = now.minusSeconds(120))) // sent during the hold
        settle { m.seq == 2L && m.load.value == ListLoad.LOADED }
        assertTrue("applied, silent: $arrived", arrived.isEmpty())
        m.onEvent(event(item("n3", 3), ts = now.plusSeconds(5))) // after the lift
        settle { arrived.isNotEmpty() }
        assertEquals(listOf("n3"), arrived.map { it.itemId })
        // A new opening forgets the lift (its list too: the event waits for the opening's full read, and is news since it).
        m.vaultOpened(now.minusSeconds(600))
        ops.pages += FeedPage(listOf(item("i1", 1), item("h2", 2), item("n3", 3), item("o4", 4)), 4)
        m.onEvent(event(item("o4", 4), ts = now.minusSeconds(120)))
        settle { arrived.size == 2 }
        assertEquals(listOf("n3", "o4"), arrived.map { it.itemId })
    }

    /** The clone alarm notifies "also while held": its event from the hold, collected after the lift, still notifies. */
    @Test
    fun theCloneAlarmFromTheHoldStillNotifiesAfterTheLift() = runTest {
        val ops = Ops()
        val m = openedEarlier(ops, item("i1", 1))
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.ownerCheckLifted(now)
        val alarm = item("a2", 2, kind = "credential.alarm", priority = "urgent")
        ops.pages += FeedPage(listOf(item("i1", 1), alarm), 2) // the full read it waits for
        m.onEvent(event(alarm, ts = now.minusSeconds(120)))
        settle { arrived.isNotEmpty() }
        assertEquals(listOf("a2"), arrived.map { it.itemId })
    }

    /** The replay path is judged against the lift too: a push wake's event of the hold, covered by the read, is silent. */
    @Test
    fun aReplayedEventFromTheHoldIsSilent() = runTest {
        val ops = Ops()
        val m = opened(ops, item("h1", 1))
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.ownerCheckLifted(now)
        m.replay(event(item("h1", 1), ts = now.minusSeconds(120)), wake = true)
        settle { true }
        assertTrue(arrived.isEmpty())
    }

    /**
     * A catch-up after the lift is never judged by an item's own `at`: a batch's later ask (a new `seq`, the batch's old
     * `at`) notifies. What changed during the hold is covered by the full read every lift starts (VaultManager).
     */
    @Test
    fun aBatchsLaterAskAfterTheLiftNotifiesThoughItsItemIsOld() = runTest {
        val ops = Ops()
        val m = opened(ops, item("i1", 1))
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.ownerCheckLifted(now.minusSeconds(60))
        ops.pages += FeedPage(listOf(item("i1", 1)), 1)
        m.refresh() // the lift's full read
        // seq 2, made 5 minutes ago (before the lift), changed now by a later ask.
        ops.pages += FeedPage(listOf(item("batch", 2, minutesAgo = 5)), 2)
        m.catchUp()
        settle { arrived.isNotEmpty() }
        assertEquals(listOf("batch"), arrived.map { it.itemId })
    }

    /**
     * (ANDROID-PLAN 0.1.134) The lift is told before its value is published, and the feed's cursor is the one before the due
     * state: a catch-up then (a `seq` gap, before the lift's own read) reads in full, and the due state's changes notify
     * nothing; a live event waits for that read.
     */
    @Test
    fun afterTheLiftACatchUpReadsInFullAndTheDueStateStaysSilent() = runTest {
        val ops = Ops()
        val m = openedEarlier(ops, item("i1", 1))
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.ownerCheckLifted(now)
        assertEquals("a full read is owed", ListLoad.NOT_LOADED, m.load.value)
        ops.pages += FeedPage(listOf(item("i1", 1), item("d2", 2), item("d3", 3)), 3)
        m.catchUp()
        settle { m.seq == 3L && m.load.value == ListLoad.LOADED }
        assertEquals(0L, ops.lists.last())
        assertTrue("the due state's changes are silent: $arrived", arrived.isEmpty())
    }

    /**
     * An incremental read already out when the lift is told reads from the cursor of before the due state: it applies
     * nothing and reads in full instead, so nothing of the due state notifies. A lift with no time known still owes it.
     */
    @Test
    fun aReadOutAcrossTheLiftReadsInFullInstead() = runTest {
        val ops = Ops()
        val m = openedEarlier(ops, item("i1", 1))
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        val gate = CompletableDeferred<Unit>()
        ops.gate = gate
        ops.pages += FeedPage(listOf(item("d2", 2)), 2)
        ops.pages += FeedPage(listOf(item("i1", 1), item("d2", 2)), 2)
        m.catchUp()
        repeat(SETTLE_TRIES) { if (synchronized(ops) { ops.lists.size } < 2) { runCurrent(); Thread.sleep(SETTLE_MS) } }
        assertEquals(listOf(0L, 1L), synchronized(ops) { ops.lists.toList() })
        m.ownerCheckLifted(null)
        assertEquals(ListLoad.NOT_LOADED, m.load.value)
        ops.gate = null
        gate.complete(Unit)
        settle { m.seq == 2L && m.load.value == ListLoad.LOADED }
        assertEquals(listOf(0L, 1L, 0L), synchronized(ops) { ops.lists.toList() })
        assertTrue("the due state is silent: $arrived", arrived.isEmpty())
    }

    /** Held arrivals from before the due state notify nothing after its lift; the clone alarm's still do. */
    @Test
    fun theLiftDropsHeldArrivalsButTheAlarms() = runTest {
        val ops = Ops()
        var checking = false
        val m = openedEarlier(ops, item("i1", 1), held = { checking })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        checking = true
        val alarm = item("a3", 3, kind = "credential.alarm", priority = "urgent")
        ops.pages += FeedPage(listOf(item("x2", 2), alarm), 3)
        m.catchUp()
        settle { m.seq == 3L }
        assertTrue(arrived.isEmpty())
        m.ownerCheckLifted(now)
        checking = false
        ops.pages += FeedPage(listOf(item("i1", 1), item("x2", 2), alarm), 3)
        m.releaseHeld()
        settle { arrived.isNotEmpty() && m.load.value == ListLoad.LOADED }
        assertEquals(listOf("a3"), arrived.map { it.itemId })
    }

    /**
     * Unreachable keeps the replay's window as well as the queues, and nothing is released while the vault is not open; a
     * new opening (a close conflated away) starts empty.
     */
    @Test
    fun whileUnreachableNothingIsReleasedAndANewOpeningStartsEmpty() = runTest {
        val ops = Ops()
        var open = true
        var checking = true
        ops.known["i1"] = item("i1", 1)
        ops.pages += FeedPage(listOf(item("i1", 1)), 1)
        val m = manager(ops, held = { checking }, vaultOpen = { open }).also {
            it.vaultOpened(now)
            it.refresh()
        }
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        val token = m.startReplayWindow()
        m.onEvent(event(item("i2", 2))) // held by the check
        open = false
        m.clear(keepHeld = true) // Unreachable
        assertTrue("the replay's window is kept", m.replayOwns(token))
        checking = false // the check's end ran with the close's clears
        ops.pages += FeedPage(listOf(item("i1", 1), item("i2", 2)), 2)
        m.refresh()
        m.releaseHeld()
        settle { true }
        assertTrue("nothing released while not open: $arrived", arrived.isEmpty())
        // A new opening (not a continued one): what was kept is gone.
        open = true
        m.vaultOpened(now)
        m.releaseHeld()
        settle { true }
        assertTrue(arrived.isEmpty())
    }

    /**
     * A release asked for while the vault is not open (Unreachable) takes nothing out of the kept queues: when the opening
     * continues, the held event still notifies at the check's end.
     */
    @Test
    fun aReleaseWhileUnreachableKeepsTheQueuesForTheContinuedOpening() = runTest {
        val ops = Ops()
        var open = true
        var checking = true
        ops.known["i1"] = item("i1", 1)
        ops.pages += FeedPage(listOf(item("i1", 1)), 1)
        val m = manager(ops, held = { checking }, vaultOpen = { open }).also {
            it.vaultOpened(now)
            it.refresh()
        }
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.onEvent(event(item("i2", 2)))
        open = false
        m.clear(keepHeld = true)
        checking = false
        ops.pages += FeedPage(listOf(item("i1", 1), item("i2", 2)), 2)
        m.refresh()
        m.releaseHeld() // while not open
        settle { true }
        // The opening continues; its check runs again and ends.
        open = true
        checking = true
        assertTrue(m.openingContinues())
        ops.pages += FeedPage(listOf(item("i1", 1), item("i2", 2)), 2)
        m.refresh()
        checking = false
        m.releaseHeld()
        settle { arrived.isNotEmpty() }
        assertEquals(listOf("i2"), arrived.map { it.itemId })
    }

    /** A blip, then the continued opening's full read and the arrivals it brings (ids). */
    private suspend fun TestScope.afterABlip(
        m: FeedManager,
        ops: Ops,
        read: List<FeedItem>,
        between: () -> Unit = {},
    ): MutableList<String> {
        val arrived = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it.itemId } }
        m.clear(keepHeld = true) // Unreachable
        between()
        read.forEach { ops.known[it.itemId] = it }
        ops.pages += FeedPage(read, read.maxOf { it.seq })
        m.refresh() // the continued opening's full read
        advanceUntilIdle()
        return arrived
    }

    /**
     * (As the iPhone app) An item sent during a blip is notified by the continued opening's full read, as a catch-up from
     * the kept cursor would; its `feed.event` coming after the read notifies nothing more.
     */
    @Test
    fun theReturnReadNotifiesTheBlipsItemOnce() = runTest {
        val ops = Ops()
        val m = openedEarlier(ops, item("i1", 1))
        val g2 = item("g2", 2)
        val arrived = afterABlip(m, ops, listOf(item("i1", 1), g2)) { assertTrue(m.openingContinues()) }
        assertEquals(listOf("g2"), arrived)
        m.onEvent(event(g2))
        // Its event held back while the phase was not known, replayed now (covered, sent since the opening): not again.
        m.replay(event(g2), wake = false)
        advanceUntilIdle()
        assertEquals("once", listOf("g2"), arrived)
    }

    /**
     * While the vault is not open on this phone nothing is read for a `sync.event`, and a read made all the same notifies
     * nothing, marks nothing notified and leaves what the blip kept: the continued opening's read notifies the blip's item.
     */
    @Test
    fun aReadWhileNotOpenTakesNothingOfTheBlip() = runTest {
        val ops = Ops()
        var open = true
        val i1 = item("i1", 1)
        ops.known["i1"] = i1
        ops.pages += FeedPage(listOf(i1), 1)
        val m = manager(ops, vaultOpen = { open }).also { it.vaultOpened(now.minusSeconds(600)); it.refresh() }
        val arrived = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it.itemId } }
        open = false
        m.clear(keepHeld = true) // Unreachable
        val reads = synchronized(ops) { ops.lists.size }
        m.onEvent(sync("feed.updated", "i1", 3))
        // A read would run on the IO dispatcher: give it the time to show.
        repeat(SETTLE_TRIES / 4) { advanceUntilIdle(); Thread.sleep(SETTLE_MS) }
        assertEquals("no read while not open", reads, synchronized(ops) { ops.lists.size })
        val g2 = item("g2", 2)
        ops.known["g2"] = g2
        ops.pages += FeedPage(listOf(i1, g2), 2)
        m.refresh() // a read all the same (one already asked for)
        advanceUntilIdle()
        assertEquals("nothing while not open", emptyList<String>(), arrived)
        assertFalse("nothing applied while not open", m.loaded)
        assertEquals(emptyList<FeedItem>(), m.items.value)
        open = true
        ops.pages += FeedPage(listOf(i1, g2), 2)
        m.refresh() // the continued opening's read
        advanceUntilIdle()
        assertEquals(listOf("g2"), arrived)
    }

    /**
     * A catch-up that ends after the vault stopped being open (a blip published before the collector's soft clear) applies
     * nothing: the kept cursor still covers what it brought, and the continued opening's read notifies it.
     */
    @Test
    fun aCatchUpEndingWhileNotOpenAppliesNothing() = runTest {
        val ops = Ops()
        var open = true
        val i1 = item("i1", 1)
        ops.known["i1"] = i1
        ops.pages += FeedPage(listOf(i1), 1)
        val m = manager(ops, vaultOpen = { open }).also { it.vaultOpened(now.minusSeconds(600)); it.refresh() }
        val arrived = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it.itemId } }
        val g2 = item("g2", 2)
        ops.known["g2"] = g2
        val gate = CompletableDeferred<Unit>()
        ops.gate = gate
        ops.pages += FeedPage(listOf(g2), 2)
        m.catchUp() // incremental, waits at the gate
        repeat(SETTLE_TRIES / 4) { if (synchronized(ops) { ops.lists.size } < 2) { advanceUntilIdle(); Thread.sleep(SETTLE_MS) } }
        open = false // the blip, published
        ops.gate = null
        gate.complete(Unit)
        repeat(SETTLE_TRIES / 4) { advanceUntilIdle(); Thread.sleep(SETTLE_MS) }
        assertEquals("not applied", 1L, m.seq)
        assertEquals(emptyList<String>(), arrived)
        m.clear(keepHeld = true) // the collector's soft clear
        open = true
        ops.pages += FeedPage(listOf(i1, g2), 2)
        m.refresh() // the continued opening's read
        advanceUntilIdle()
        assertEquals(listOf("g2"), arrived)
    }

    /** A waiting-asks replay asked while the vault is not open is not done: its window stays for the run to try again. */
    @Test
    fun aReplayWhileNotOpenIsNotDone() = runTest {
        val ops = Ops()
        var open = true
        ops.pages += FeedPage(emptyList(), 0)
        val m = manager(ops, vaultOpen = { open }).also { it.vaultOpened(now); it.refresh() }
        val token = m.startReplayWindow()
        open = false
        assertFalse(m.replayWaitingAsks(token, emptyList(), now))
        assertTrue(m.replayOwns(token))
        open = true
        assertTrue(m.replayWaitingAsks(token, emptyList(), now))
    }

    /** The return read while the owner check is due or held notifies nothing, and drops what the blip kept. */
    @Test
    fun aReturnReadWhileTheCheckIsDueNotifiesNothing() = runTest {
        val ops = Ops()
        var due = false
        val i1 = item("i1", 1)
        ops.known["i1"] = i1
        ops.pages += FeedPage(listOf(i1), 1)
        val m = manager(ops, gated = { due }).also { it.vaultOpened(now.minusSeconds(600)); it.refresh() }
        val arrived = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it.itemId } }
        m.clear(keepHeld = true) // the blip
        due = true // another device's pass not known yet: still due here
        val g2 = item("g2", 2)
        ops.known["g2"] = g2
        ops.pages += FeedPage(listOf(i1, g2), 2)
        m.refresh() // the return read
        advanceUntilIdle()
        assertEquals(emptyList<String>(), arrived)
        due = false
        ops.pages += FeedPage(listOf(i1, g2), 2)
        m.refresh() // the lift's read
        advanceUntilIdle()
        assertEquals("dropped, not notified later", emptyList<String>(), arrived)
    }

    /** A blip the phase collector never cleared for (it reached it after the return): the opening's cursor is kept then. */
    @Test
    fun aBlipWithNoSoftClearIsKeptWhenTheOpeningContinues() = runTest {
        val ops = Ops()
        val m = openedEarlier(ops, item("i1", 1))
        val arrived = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it.itemId } }
        assertTrue(m.openingContinues()) // the return, with no soft clear before it
        val g2 = item("g2", 2)
        ops.known["g2"] = g2
        ops.pages += FeedPage(listOf(item("i1", 1), g2), 2)
        m.refresh()
        advanceUntilIdle()
        assertEquals(listOf("g2"), arrived)
    }

    /** Two soft clears before the return keep the first's cursor: the return read notifies the blip's item. */
    @Test
    fun twoSoftClearsKeepTheFirst() = runTest {
        val ops = Ops()
        val m = openedEarlier(ops, item("i1", 1))
        val g2 = item("g2", 2)
        val arrived = afterABlip(m, ops, listOf(item("i1", 1), g2)) { m.clear(keepHeld = true) } // a second blip clear
        assertEquals(listOf("g2"), arrived)
    }

    /**
     * An item applied out of order while the hold silenced it (sent during the hold, never notified) stays silent through a
     * blip: what the blip keeps is the highest seq the list held, not only its cursor.
     */
    @Test
    fun aSilencedGapItemStaysSilentThroughABlip() = runTest {
        val ops = Ops()
        val m = openedEarlier(ops, item("i1", 1))
        val arrived = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it.itemId } }
        m.ownerCheckLifted(now)
        ops.pages += FeedPage(listOf(item("i1", 1)), 1) // the lift's full read
        m.refresh()
        val g3 = item("g3", 3)
        ops.known["g3"] = g3
        ops.pages += FeedPage(emptyList(), 1) // the gap's catch-up finds nothing more
        m.onEvent(event(g3, ts = now.minusSeconds(120))) // sent during the hold: applied, silent, out of order
        settle { synchronized(ops) { ops.lists.size } >= 3 }
        advanceUntilIdle()
        assertEquals(emptyList<String>(), arrived)
        assertEquals(1L, m.seq)
        m.clear(keepHeld = true) // a blip
        ops.pages += FeedPage(listOf(item("i1", 1), g3), 3)
        m.refresh() // the continued opening's read
        advanceUntilIdle()
        assertEquals("still silent", emptyList<String>(), arrived)
    }

    /**
     * What a blip keeps is each item's seq, not the highest: an item in a gap below one applied out of order (the gap's
     * catch-up had not brought it) is notified by the return read.
     */
    @Test
    fun aGapItemBelowAnOutOfOrderOneIsNotifiedAfterABlip() = runTest {
        val ops = Ops()
        val m = openedEarlier(ops, item("i1", 1))
        val arrived = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it.itemId } }
        val g3 = item("g3", 3)
        ops.known["g3"] = g3
        ops.pages += FeedPage(emptyList(), 1) // the gap's catch-up finds nothing yet
        m.onEvent(event(g3)) // live, out of order: notified
        settle { arrived.isNotEmpty() && synchronized(ops) { ops.lists.size } >= 2 }
        advanceUntilIdle()
        val g2 = item("g2", 2)
        m.clear(keepHeld = true) // a blip
        ops.known["g2"] = g2
        ops.pages += FeedPage(listOf(item("i1", 1), g2, g3), 3)
        m.refresh() // the continued opening's read
        advanceUntilIdle()
        assertEquals(listOf("g3", "g2"), arrived)
    }

    /**
     * A soft clear that comes while a read's arrivals are being handed on (here from the first one's own hand-off) loses
     * none of the others: they are not kept by the blip, so the return read notifies them, and the first not twice.
     */
    @Test
    fun aSoftClearDuringTheFirstArrivalLosesNothing() = runTest {
        val ops = Ops()
        var armed = false
        lateinit var m: FeedManager
        m = openedEarlier(ops, item("i1", 1), held = {
            if (armed) {
                armed = false
                m.clear(keepHeld = true) // the blip, while the first arrival is handed on
            }
            false
        })
        val arrived = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it.itemId } }
        val g2 = item("g2", 2)
        val g3 = item("g3", 3)
        ops.known["g2"] = g2
        ops.known["g3"] = g3
        ops.pages += FeedPage(listOf(g2, g3), 3)
        armed = true
        m.catchUp()
        settle { arrived.isNotEmpty() }
        advanceUntilIdle()
        assertEquals(listOf("g2"), arrived)
        ops.pages += FeedPage(listOf(item("i1", 1), g2, g3), 3)
        m.refresh() // the continued opening's read
        advanceUntilIdle()
        assertEquals(listOf("g2", "g3"), arrived)
    }

    /** An event that finds the list not loaded notifies its own copy only for a push wake (what the push was for). */
    @Test
    fun aNonWakeEventFindingTheListClearedNotifiesNothing() = runTest {
        val ops = Ops()
        val m = openedEarlier(ops, item("i1", 1))
        val arrived = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it.itemId } }
        m.clear(keepHeld = true) // a clear racing in after the event's checks
        m.onItem(item("g2", 2), notify = true, wake = false)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), arrived)
        m.onItem(item("w3", 3), notify = true, wake = true)
        advanceUntilIdle()
        assertEquals(listOf("w3"), arrived)
    }

    /** A catch-up's item the feed no longer lists (past retention) is not notified (owner decision 2026-11-18). */
    @Test
    fun aCatchUpsItemPastRetentionIsNotNotified() = runTest {
        val ops = Ops()
        ops.retention = 1
        val m = openedEarlier(ops, item("i1", 1))
        val arrived = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it.itemId } }
        val old = item("o2", 2, minutesAgo = 3 * 24 * 60L) // older than the 1 day kept
        ops.known["o2"] = old
        ops.pages += FeedPage(listOf(old), 2)
        m.catchUp()
        settle { m.seq == 2L }
        assertEquals(emptyList<String>(), arrived)
        assertEquals(listOf("i1"), m.items.value.map { it.itemId })
    }

    /**
     * A clear that comes right after a read applied (here, from a collector of its first arrival) leaves the list not
     * loaded: the read is marked loaded with its list, never over a cleared one.
     */
    @Test
    fun aClearAfterTheApplyLeavesTheListNotLoaded() = runTest {
        val ops = Ops()
        val m = openedEarlier(ops, item("i1", 1))
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { m.clear() } }
        val n2 = item("n2", 2)
        ops.known["n2"] = n2
        ops.pages += FeedPage(listOf(n2), 2)
        m.catchUp()
        settle { m.seq == 0L }
        assertFalse("not marked loaded over the cleared list", m.loaded)
    }

    /** A lift that comes right after a read applied (from a collector of its first arrival) leaves the list not loaded. */
    @Test
    fun aLiftAfterTheApplyLeavesTheListNotLoaded() = runTest {
        val ops = Ops()
        val m = openedEarlier(ops, item("i1", 1))
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { m.ownerCheckLifted(now) } }
        val n2 = item("n2", 2)
        ops.known["n2"] = n2
        ops.pages += FeedPage(listOf(n2), 2)
        m.catchUp()
        settle { m.seq == 2L }
        advanceUntilIdle()
        assertFalse("the lift's full read is owed", m.loaded)
    }

    /** A catch-up page with an item and its later change (sent, then read on another device): nothing notified. */
    @Test
    fun anItemReadInTheSamePageIsNotNotified() = runTest {
        val ops = Ops()
        val m = openedEarlier(ops, item("i1", 1))
        val arrived = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it.itemId } }
        ops.pages += FeedPage(listOf(item("x", 2), item("x", 3, status = "read")), 3)
        m.catchUp()
        settle { m.seq == 3L }
        assertEquals(emptyList<String>(), arrived)
    }

    /** A live event of an item the feed does not list once applied (past retention) is not notified. */
    @Test
    fun aLiveItemPastRetentionIsNotNotified() = runTest {
        val ops = Ops()
        ops.retention = 1
        val m = openedEarlier(ops, item("i1", 1))
        val arrived = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it.itemId } }
        m.onEvent(event(item("o2", 2, minutesAgo = 3 * 24 * 60L))) // a batch's later ask keeps its first `at`
        advanceUntilIdle()
        assertEquals(2L, m.seq)
        assertEquals(emptyList<String>(), arrived)
        m.onEvent(event(item("n3", 3)))
        advanceUntilIdle()
        assertEquals(listOf("n3"), arrived)
    }

    /** A new opening holds nothing of the list before it: its backlog waits for its own full read. */
    @Test
    fun aNewOpeningEmptiesTheList() = runTest {
        val ops = Ops()
        val m = openedEarlier(ops, item("i1", 1))
        assertTrue(m.loaded)
        m.vaultOpened(now)
        assertFalse(m.loaded)
        assertEquals(0L, m.seq)
        assertEquals(emptyList<FeedItem>(), m.items.value)
    }

    /** Only what is new or changed since the kept cursor and still unread: the rest of the list is silent. */
    @Test
    fun theReturnReadNotifiesOnlyWhatChangedSinceTheCursor() = runTest {
        val ops = Ops()
        val m = openedEarlier(ops, item("i1", 1), item("i3", 3))
        val arrived = afterABlip(m, ops, listOf(item("i1", 1), item("i3", 4, status = "read"), item("g5", 5)))
        assertEquals(listOf("g5"), arrived)
    }

    /** A lift during the blip: its full read notifies nothing (what changed while due is covered by it). */
    @Test
    fun aLiftDuringTheBlipKeepsTheReturnReadSilent() = runTest {
        val ops = Ops()
        val m = openedEarlier(ops, item("i1", 1))
        val arrived = afterABlip(m, ops, listOf(item("i1", 1), item("g2", 2))) { m.ownerCheckLifted(now) }
        assertEquals(emptyList<String>(), arrived)
    }

    /** A new opening holds nothing of the one before: its full read notifies nothing. */
    @Test
    fun aNewOpeningAfterTheBlipReadsSilently() = runTest {
        val ops = Ops()
        val m = openedEarlier(ops, item("i1", 1))
        val arrived = afterABlip(m, ops, listOf(item("i1", 1), item("g2", 2))) { m.vaultOpened(now) }
        assertEquals(emptyList<String>(), arrived)
    }

    /** A close after the blip (a lock) drops what it kept. */
    @Test
    fun aCloseAfterTheBlipDropsWhatItKept() = runTest {
        val ops = Ops()
        val m = openedEarlier(ops, item("i1", 1))
        val arrived = afterABlip(m, ops, listOf(item("i1", 1), item("g2", 2))) { m.clear() }
        assertEquals(emptyList<String>(), arrived)
    }

    /** A replay window kept across Unreachable belongs to that opening: a new opening closes it. */
    @Test
    fun aNewOpeningClosesAKeptReplayWindow() = runTest {
        val m = opened(Ops())
        val token = m.startReplayWindow()
        m.clear(keepHeld = true)
        assertTrue(m.replayOwns(token))
        m.vaultOpened(now)
        assertFalse(m.replayOwns(token))
    }

    /** A hard clear that closes (a lock, a wipe) ends the opening: a status read after it has no opening to carry on. */
    @Test
    fun aHardClearEndsTheOpening() = runTest {
        val m = opened(Ops())
        m.clear()
        assertFalse(m.openingContinues())
    }

    /**
     * The owner check's hold clears the feed but closes nothing: after its pass (a replay window opened), a status read
     * that could not be made keeps the same opening, and the replay's window with it.
     */
    @Test
    fun aHoldsClearKeepsTheOpeningForABlipAfterThePass() = runTest {
        val m = manager(Ops())
        val n = m.vaultOpened(now)
        m.clear(endsOpening = false) // the hold
        val token = m.startReplayWindow() // its pass
        m.clear(keepHeld = true) // Unreachable
        assertTrue(m.openingContinues())
        assertTrue(m.replayOwns(token))
        // The continued opening takes its mark on the vault's clock, as before the hold.
        m.openingMarked(now.minusSeconds(5), n)
        assertEquals(now.minusSeconds(5), m.openingMark)
        // And a lock after the hold ends it.
        m.clear()
        assertFalse(m.openingContinues())
    }

    /** A full read begun before the lift is read again in full: its cursor may stand before changes of the due state. */
    @Test
    fun aFullReadAcrossTheLiftIsReadAgain() = runTest {
        val ops = Ops()
        val m = openedEarlier(ops, item("i1", 1))
        val gate = CompletableDeferred<Unit>()
        ops.gate = gate
        ops.pages += FeedPage(listOf(item("i1", 1)), 1)
        ops.pages += FeedPage(listOf(item("i1", 1), item("d2", 2)), 2)
        backgroundScope.launch { m.refresh() }
        repeat(SETTLE_TRIES) { if (synchronized(ops) { ops.lists.size } < 2) { runCurrent(); Thread.sleep(SETTLE_MS) } }
        m.ownerCheckLifted(now)
        ops.gate = null
        gate.complete(Unit)
        repeat(SETTLE_TRIES) { if (m.seq != 2L) { runCurrent(); advanceUntilIdle(); Thread.sleep(SETTLE_MS) } }
        assertEquals("lists ${synchronized(ops) { ops.lists.toList() }} load ${m.load.value}", 2L, m.seq)
        assertEquals(listOf(0L, 0L, 0L), synchronized(ops) { ops.lists.toList() })
        assertEquals(ListLoad.LOADED, m.load.value)
    }

    /** A mark for an earlier opening is ignored; the one for this opening is kept as it was given. */
    @Test
    fun aMarkForAnEarlierOpeningIsIgnored() = runTest {
        val m = manager(Ops())
        val first = m.vaultOpened(now.minusSeconds(100))
        val second = m.vaultOpened(now)
        m.openingMarked(now.minusSeconds(50), first)
        assertEquals("the phone's clock until this opening's answer", now, m.openingMark)
        m.openingMarked(now.minusSeconds(5), second)
        assertEquals(now.minusSeconds(5), m.openingMark)
    }

    /**
     * (ANDROID-PLAN 0.1.134 "A push wake") At a cold start with the vault open, the events held back until the vault was known
     * open are a day-old backlog: silent when no push started the process, notified when one did (what it was for); a fresh
     * event after the opening notifies either way.
     */
    @Test
    fun aColdStartsHeldBackBacklogNotifiesOnlyForAPushWake() = runTest {
        for (wake in listOf(false, true)) {
            val ops = Ops()
            var checking = true
            val m = opened(ops, item("b1", 1), item("b2", 2), held = { checking })
            val arrived = mutableListOf<FeedItem>()
            val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
            val dayOld = now.minus(1, ChronoUnit.DAYS)
            m.replay(event(item("b1", 1), ts = dayOld), wake)
            m.replay(event(item("b2", 2), ts = dayOld), wake)
            ops.pages += FeedPage(listOf(item("f3", 3)), 3)
            m.onEvent(event(item("f3", 3))) // fresh, after the opening
            checking = false
            m.releaseHeld()
            settle { arrived.any { it.itemId == "f3" } }
            settle { true }
            assertEquals("wake $wake", if (wake) listOf("b1", "b2", "f3") else listOf("f3"), arrived.map { it.itemId })
            job.cancel()
        }
    }

    /**
     * (ANDROID-PLAN 0.1.134) An item notified at a change is not notified again during the same opening for the same or an
     * older change (`seq`): a newer change is, and so is the same change in the next opening.
     */
    @Test
    fun noSecondAlertWithinAnOpeningForTheSameOrAnOlderChange() = runTest {
        val ops = Ops()
        val m = opened(ops, item("i1", 1))
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.onEvent(event(item("i1", 2)))
        settle { true }
        assertEquals(listOf(2L), arrived.map { it.seq })
        // The same change again (a push wake's copy, covered now), and an older one: no second alert.
        m.replay(event(item("i1", 2)), wake = true)
        m.replay(event(item("i1", 1)), wake = true)
        settle { true }
        assertEquals(listOf(2L), arrived.map { it.seq })
        m.onEvent(event(item("i1", 3)))
        settle { true }
        assertEquals(listOf(2L, 3L), arrived.map { it.seq })
        // The next opening: the same change may notify again.
        m.vaultOpened(now)
        m.replay(event(item("i1", 3)), wake = true)
        settle { true }
        assertEquals(listOf(2L, 3L, 3L), arrived.map { it.seq })
    }

    /**
     * (Rule 3) A full read that begins between a release's look at the feed and the release itself (here, as the release asks
     * whether the feed is gated) leaves what was held for that read's end: judged against the feed it leaves, an item read
     * elsewhere meanwhile does not notify.
     */
    @Test
    fun aReadBegunAsTheReleaseRunsKeepsTheQueueForItsEnd() = runTest {
        val ops = Ops()
        var checking = true
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        var armed = false
        var calls = 0
        lateinit var m: FeedManager
        m = FeedManager(scope, ops = { ops }, gated = {
            if (armed && ++calls == 2) {
                // The release's own look at the gate: a full read begins now, held at its list.
                ops.gate = CompletableDeferred()
                ops.pages += FeedPage(listOf(item("i1", 2, status = "read")), 2)
                m.open()
            }
            false
        }, now = { now }, held = { checking })
        ops.known["i1"] = item("i1", 1)
        ops.pages += FeedPage(listOf(item("i1", 1)), 1)
        m.vaultOpened(now)
        m.refresh()
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.onEvent(event(item("i1", 1))) // covered, sent since the opening
        checking = false
        armed = true
        m.releaseHeld()
        armed = false
        settle { true }
        assertTrue("released against the feed before the read: $arrived", arrived.isEmpty())
        ops.gate!!.complete(Unit)
        settle { m.seq == 2L }
        settle { true }
        assertTrue("read elsewhere: no alert", arrived.isEmpty())
        scope.cancel()
    }

    /**
     * (Rule 3) An incremental catch-up that fails leaves the feed loaded but behind: it releases nothing; the next read that
     * succeeds does.
     */
    @Test
    fun aFailedCatchUpReleasesNothing() = runTest {
        val ops = Ops()
        var checking = true
        val m = opened(ops, item("i1", 1), held = { checking })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.onEvent(event(item("i2", 2))) // held: not yet read
        checking = false
        ops.failLists = 1
        m.catchUp()
        settle { ops.failLists == 0 }
        settle { true }
        assertEquals(ListLoad.LOADED, m.load.value)
        assertTrue("nothing released by a failed read: $arrived", arrived.isEmpty())
        ops.pages += FeedPage(listOf(item("i2", 2)), 2)
        m.catchUp()
        settle { arrived.isNotEmpty() }
        settle { true }
        assertEquals(listOf("i2"), arrived.map { it.itemId })
    }

    /**
     * (Rule 3) A full read reserved before it starts ([FeedManager.reserveRefresh]: the read of a passed check's lift, launched)
     * holds a release asked for meanwhile until it has run; one dropped unrun gives its place back.
     */
    @Test
    fun aReservedReadHoldsTheReleaseUntilItRuns() = runTest {
        val ops = Ops()
        var checking = true
        val m = opened(ops, item("i1", 1), item("i2", 2), held = { checking })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.onEvent(event(item("i1", 1)))
        val read = m.reserveRefresh()
        checking = false
        m.releaseHeld() // the lift's sends, before the launched read starts
        settle { true }
        assertTrue(arrived.isEmpty())
        ops.pages += FeedPage(listOf(item("i1", 3, status = "read"), item("i2", 2)), 3)
        read.run()
        settle { true }
        assertTrue("read elsewhere meanwhile: no alert", arrived.isEmpty())
        // Dropped unrun: the release goes ahead.
        checking = true
        m.onEvent(event(item("i2", 2)))
        val unrun = m.reserveRefresh()
        checking = false
        m.releaseHeld()
        settle { true }
        assertTrue(arrived.isEmpty())
        unrun.drop()
        unrun.drop() // once only
        settle { arrived.isNotEmpty() }
        assertEquals(listOf("i2"), arrived.map { it.itemId })
    }

    /**
     * (Rule 3) The release waits for the feed's reads: at the lift (the gate's reads read the feed in full, then what was held
     * is released), an item read on another device while the work was paused (its `feed.updated` not taken then) does not
     * notify.
     */
    @Test
    fun theLiftReleasesAfterTheFullReadSoAnItemReadMeanwhileDoesNotNotify() = runTest {
        val ops = Ops()
        var checking = true
        var paused = false
        val m = opened(ops, item("i1", 1), gated = { paused }, held = { checking })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        ops.pages += FeedPage(listOf(item("i2", 2)), 2)
        m.catchUp()
        settle { m.seq == 2L }
        // The check fails: the work pauses. i2 is read on another device meanwhile.
        paused = true
        ops.pages += FeedPage(listOf(item("i1", 1), item("i2", 3, status = "read")), 3)
        // The lift: the gate's reads (a full read), then the release.
        paused = false
        checking = false
        m.open()
        m.releaseHeld()
        settle { m.seq == 3L }
        settle { true }
        assertTrue(arrived.isEmpty())
    }

    /**
     * (Rule 3) A read that fails after the check does not leave the feed stalled: the next event starts a read (before the
     * one catch-up a failed read schedules), and what was held and the event come after it.
     */
    @Test
    fun aFailedReadAfterTheCheckDoesNotStallTheFeed() = runTest {
        val ops = Ops()
        var checking = true
        val m = opened(ops, item("i1", 1), held = { checking })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.onEvent(event(item("i2", 2)))
        checking = false
        ops.failLists = 1
        m.open() // fails
        m.releaseHeld()
        // Without moving the clock: the scheduled catch-up must not run before the event below.
        repeat(SETTLE_TRIES) {
            runCurrent()
            if (m.load.value == ListLoad.FAILED) return@repeat
            Thread.sleep(SETTLE_MS)
        }
        assertEquals(ListLoad.FAILED, m.load.value)
        assertTrue(arrived.isEmpty())
        // A live event: a read starts, then what was held and the event come.
        ops.pages += FeedPage(listOf(item("i1", 1), item("i2", 2), item("i3", 3)), 3)
        m.onEvent(event(item("i3", 3)))
        settle { arrived.size >= 2 }
        assertEquals(listOf("i2", "i3"), arrived.map { it.itemId })
        assertEquals(listOf("i1", "i2", "i3"), m.items.value.map { it.itemId }.sorted())
    }

    /**
     * (ANDROID-PLAN §6.7) A check that ends before the opening's read has landed: the release waits for it, so the backlog
     * is judged against a loaded feed and stays silent.
     */
    @Test
    fun aCheckThatEndsBeforeTheOpeningsReadKeepsTheBacklogSilent() = runTest {
        val ops = Ops()
        var checking = true
        val m = manager(ops, held = { checking })
        m.vaultOpened(now)
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        val gate = CompletableDeferred<Unit>()
        ops.gate = gate
        ops.pages += FeedPage(listOf(item("i1", 1)), 1)
        m.open() // the opening's read, held at its list
        m.onEvent(event(item("i1", 1), ts = now.minusSeconds(60))) // the mailbox's backlog
        checking = false
        m.releaseHeld()
        settle { true }
        gate.complete(Unit)
        settle { m.load.value == ListLoad.LOADED }
        settle { true }
        assertTrue(arrived.isEmpty())
    }

    /** No opening marked yet (none seen): a covered held event is backlog. */
    @Test
    fun withNoOpeningMarkedACoveredHeldEventIsSilent() = runTest {
        val ops = Ops()
        var checking = true
        ops.known["i1"] = item("i1", 1)
        ops.pages += FeedPage(listOf(item("i1", 1)), 1)
        val m = manager(ops, held = { checking }).also { it.refresh() }
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.onEvent(event(item("i1", 1)))
        checking = false
        m.releaseHeld()
        settle { true }
        assertTrue(arrived.isEmpty())
    }

    /** A newer change of an item notified among the held arrivals is notified: only what is no newer is not notified twice. */
    @Test
    fun aNewerChangeOfAJustNotifiedItemIsNotified() = runTest {
        val ops = Ops()
        var checking = true
        val m = opened(ops, item("i1", 4), held = { checking })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        ops.pages += FeedPage(listOf(item("x", 5)), 5)
        m.catchUp()
        settle { m.seq == 5L }
        m.onEvent(event(item("x", 7)))
        settle { true }
        checking = false
        m.releaseHeld()
        settle { arrived.size >= 2 }
        assertEquals(listOf("x" to 5L, "x" to 7L), arrived.map { it.itemId to it.seq })
    }

    /**
     * A held event the feed's read does not cover yet (a full read runs), for an item just notified among the held arrivals:
     * not notified again. The release waits for the read under way, so it comes once that read has landed.
     */
    @Test
    fun aHeldEventNotYetCoveredOfAJustNotifiedItemIsNotNotifiedTwice() = runTest {
        val ops = Ops()
        var checking = true
        val m = opened(ops, item("i1", 1), held = { checking })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        ops.pages += FeedPage(listOf(item("x", 2)), 2)
        m.catchUp()
        settle { m.seq == 2L }
        m.onEvent(event(item("x", 2)))
        val gate = CompletableDeferred<Unit>()
        ops.gate = gate
        ops.pages += FeedPage(listOf(item("i1", 1), item("x", 2)), 2)
        m.open() // a full read, held at its list
        settle { m.load.value == ListLoad.LOADING }
        checking = false
        m.releaseHeld()
        settle { true }
        assertTrue("not before the read under way has landed", arrived.isEmpty())
        gate.complete(Unit)
        settle { m.load.value == ListLoad.LOADED && arrived.isNotEmpty() }
        settle { true }
        assertEquals("once", listOf("x"), arrived.map { it.itemId })
    }

    /**
     * After the check, a catch-up's arrival while its events are still held comes after them (they come first, and the
     * held event of an item the catch-up brought is not notified again); the release that follows adds nothing.
     */
    @Test
    fun aCatchUpAfterTheCheckComesAfterTheHeldOnes() = runTest {
        val ops = Ops()
        var checking = true
        val m = opened(ops, item("i1", 1), held = { checking })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.onEvent(event(item("i2", 2)))
        settle { true }
        checking = false
        ops.pages += FeedPage(listOf(item("i2", 2), item("i3", 3)), 3)
        m.catchUp()
        settle { arrived.size >= 2 }
        m.releaseHeld()
        settle { true }
        assertEquals(listOf("i2", "i3"), arrived.map { it.itemId })
    }

    /**
     * The check ended behind (the feed gated): a live event is dropped, as while paused, and what the check held is kept
     * for the lift, not released early.
     */
    @Test
    fun aLiveEventWhileGatedAfterTheCheckIsDroppedAndTheHeldOnesWait() = runTest {
        val ops = Ops()
        var checking = true
        var paused = false
        val m = opened(ops, item("i1", 1), gated = { paused }, held = { checking })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.onEvent(event(item("i2", 2)))
        checking = false
        paused = true
        m.onEvent(event(item("i3", 3)))
        settle { true }
        assertTrue(arrived.isEmpty())
        paused = false
        m.releaseHeld()
        settle { arrived.isNotEmpty() }
        assertEquals("the held one at the lift; the one while gated dropped", listOf("i2"), arrived.map { it.itemId })
    }

    /**
     * The check ends, and its release runs, between an event's reading of the check and its add to the queue: the event is
     * released at once all the same, never left waiting for a later release (or dropped by a lock).
     */
    @Test
    fun aCheckEndingBetweenTheReadingAndTheAddLeavesNothingBehind() = runTest {
        val ops = Ops()
        var checking = true
        var endOnRead = false
        lateinit var m: FeedManager
        val held = {
            if (endOnRead) {
                // This reading still says "checking"; the check ends and its release runs right after it.
                endOnRead = false
                checking = false
                m.releaseHeld()
                true
            } else {
                checking
            }
        }
        m = opened(ops, item("i1", 1), held = held)
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.onEvent(event(item("i2", 2)))
        settle { true }
        endOnRead = true
        m.onEvent(event(item("i3", 3)))
        settle { arrived.size >= 2 }
        assertEquals(listOf("i2", "i3"), arrived.map { it.itemId })
    }

    /**
     * The check ends behind (the feed gated) between an event's reading of the check and its add: nothing is released or
     * dropped then; what was held, that event included, comes at the lift.
     */
    @Test
    fun aCheckEndingBehindBetweenTheReadingAndTheAddKeepsItForTheLift() = runTest {
        val ops = Ops()
        var checking = true
        var paused = false
        var endOnRead = false
        val held = {
            if (endOnRead) {
                endOnRead = false
                checking = false
                paused = true
                true
            } else {
                checking
            }
        }
        val m = opened(ops, item("i1", 1), gated = { paused }, held = held)
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.onEvent(event(item("i2", 2)))
        endOnRead = true
        m.onEvent(event(item("i3", 3)))
        settle { true }
        assertTrue(arrived.isEmpty())
        paused = false
        m.releaseHeld()
        settle { arrived.size >= 2 }
        assertEquals(listOf("i2", "i3"), arrived.map { it.itemId })
    }

    /**
     * The lift comes between an event's reading of the gate (still paused) and its handling: the event is dropped on that
     * reading, as while paused, and never overtakes what the check held (which comes with the lift's release).
     */
    @Test
    fun aLiftBetweenTheReadingAndTheHandlingLetsNoEventOvertake() = runTest {
        val ops = Ops()
        var checking = true
        var paused = false
        var liftOnRead = false
        val gated = {
            if (liftOnRead) {
                // This reading still says "paused"; the gate lifts right after it.
                liftOnRead = false
                paused = false
                true
            } else {
                paused
            }
        }
        val m = opened(ops, item("i1", 1), gated = gated, held = { checking })
        val arrived = mutableListOf<FeedItem>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.onEvent(event(item("i2", 2)))
        checking = false
        paused = true
        liftOnRead = true
        m.onEvent(event(item("i3", 3)))
        settle { true }
        assertTrue("nothing before the lift's release", arrived.isEmpty())
        m.releaseHeld()
        settle { arrived.isNotEmpty() }
        assertEquals(listOf("i2"), arrived.map { it.itemId })
    }

    // --- events held back until the vault was known to be open (a process started by a push wake) ---

    @Test
    fun aHeldBackEventArrivesAlthoughTheOpenReadAlreadyBroughtItsItem() = runTest {
        val ops = Ops()
        // The open's full read lists i1 and i2 (a full read notifies nothing); i2's event had been held back.
        val m = opened(ops, item("i1", 1, status = "read"), item("i2", 2))
        val arrived = mutableListOf<FeedItem>()
        val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.replay(event(item("i2", 2)), wake = true)
        assertEquals(listOf("i2"), arrived.map { it.itemId })
        // Read meanwhile on another device (the list has it as read): nothing arrives.
        m.replay(event(item("i1", 1)), wake = true)
        // Deleted meanwhile: not in the list, nothing arrives.
        m.replay(event(item("gone", 2)), wake = true)
        assertEquals(1, arrived.size)
        assertEquals(1, ops.lists.size) // and no further read
        job.cancel()
    }

    @Test
    fun aHeldBackEventBeforeTheOpenReadIsAnArrivalLikeALiveOne() = runTest {
        val ops = Ops()
        val m = manager(ops)
        val arrived = mutableListOf<FeedItem>()
        val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        ops.pages += FeedPage(listOf(item("i7", 7)), 7)
        m.replay(event(item("i7", 7)), wake = true)
        assertEquals(listOf("i7"), arrived.map { it.itemId })
        settle { m.load.value == ListLoad.LOADED }
        assertEquals("the full read that follows brings no second arrival", 1, arrived.size)
        job.cancel()
    }

    @Test
    fun nothingHeldBackArrivesWhileTheVaultIsHeld() = runTest {
        val ops = Ops()
        val m = manager(ops, gated = { true })
        val arrived = mutableListOf<FeedItem>()
        val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.replay(event(item("i7", 7)), wake = true)
        advanceUntilIdle()
        assertTrue(arrived.isEmpty())
        assertTrue(ops.lists.isEmpty())
        job.cancel()
    }

    /**
     * ANDROID-PLAN 0.1.52 §4 "Event titles" (de-duplication): one recovery raises one notification, `recovery.completed`;
     * `credential.recovered`, `recovery.device_paired` and a PIN change are neither listed, counted nor notified, and the
     * cursor still moves past them.
     */
    @Test
    fun theHistoryOnlyKindsAreNeitherListedCountedNorArrivals() = runTest {
        val ops = Ops()
        val m = opened(ops, item("i1", 1, kind = "vault.pin_changed"), item("i2", 2, kind = "message.received"))
        assertEquals(listOf("i2"), m.items.value.map { it.itemId })
        assertEquals(1, m.badge.value.unread)
        val arrived = mutableListOf<FeedItem>()
        val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.onEvent(event(item("i3", 3, kind = "credential.recovered")))
        m.onEvent(event(item("i4", 4, kind = "recovery.device_paired")))
        m.onEvent(event(item("i5", 5, kind = "recovery.completed")))
        assertEquals(5L, m.seq)
        assertEquals(1, ops.lists.size) // in order: no catch-up
        assertEquals(listOf("i5"), arrived.map { it.itemId })
        assertEquals(setOf("i2", "i5"), m.items.value.map { it.itemId }.toSet())
        assertEquals(2, m.badge.value.unread)
        // A held-back one does not arrive either.
        m.replay(event(item("i6", 6, kind = "vault.pin_changed")), wake = true)
        assertEquals(listOf("i5"), arrived.map { it.itemId })
        job.cancel()
    }

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
        // The catch-up's read runs on the IO dispatcher: wait for what it applied, not only for its start.
        settle { synchronized(ops) { ops.lists.size } == 2 && m.seq == 5L }
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
    fun viewedItemsAreMarkedReadExceptUrgentOnes() = runTest {
        val ops = Ops()
        val m = opened(
            ops,
            item("m1", 1),
            item("m2", 2),
            item("r", 3, status = "read"),
            item("a", 4, kind = "credential.alarm", priority = "urgent"),
            item("x", 5, status = "archived"),
        )
        m.markViewed(listOf("m1", "m2", "r", "a", "x", "gone"))
        settle { ops.updates.size == 2 }
        assertEquals(setOf("m1" to "read", "m2" to "read"), ops.updates.toSet())
        assertEquals(listOf("a"), m.items.value.filter { it.status == "active" }.map { it.itemId })
        assertEquals(1, m.badge.value.unread)
        assertTrue(m.badge.value.urgent)
        m.markViewed(listOf("m1", "a")) // nothing left to mark
        advanceUntilIdle()
        assertEquals(2, ops.updates.size)
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

    // --- one ask of a batch withdrawn (VAULT-MESSAGING 0.80.0 §10.4) ---

    private fun batchItem(id: String, seq: Long, ref: String, count: Int? = null, minutesAgo: Long = 1) =
        FeedItem(
            id, seq, "connection.authenticate.requested", now.minus(minutesAgo, ChronoUnit.MINUTES).toString(), "active",
            connectionId = "c1", ref = ref, count = count,
        )

    private fun withdrawn(remaining: Int, vararg keys: String, openUntil: Instant? = null, closeAt: Instant? = null, askTs: Instant? = null) =
        WithdrawnBatch("c1", keys.toSet(), now, remaining, openUntil, askKey = keys.first(), closeAt = closeAt, askTs = askTs)

    @Test
    fun theLastWithdrawnAskOfABatchMarksItsItemRead() = runTest {
        val ops = Ops()
        val m = opened(ops, batchItem("b", 1, "r1", count = 2))
        // r1 (the item's ref) was decided earlier; r2 is withdrawn now and was the last open: the item is found by the batch window.
        m.askWithdrawn(withdrawn(0, "auth:r2"))
        settle { ops.updates.size == 1 }
        assertEquals(listOf("b" to "read"), ops.updates)
    }

    @Test
    fun aWithdrawnAskThatLeavesOthersKeepsTheItemAndPostsTheNotificationAgain() = runTest {
        val ops = Ops()
        val m = opened(ops, batchItem("b", 1, "r1", count = 3))
        val arrived = mutableListOf<FeedItem>()
        val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        val soon = now.plusSeconds(300)
        val later = now.plusSeconds(900)
        m.askWithdrawn(withdrawn(2, "auth:r1", "auth:r2", "auth:r3", openUntil = soon, closeAt = later))
        advanceUntilIdle()
        assertTrue("no feed.update: the item stays", ops.updates.isEmpty())
        assertEquals(1, arrived.size)
        assertEquals(2, arrived.single().count)
        assertEquals(soon, arrived.single().openUntil)
        assertEquals("it goes by itself only at the latest deadline", later, arrived.single().closeAt)
        assertTrue(arrived.single().quietRepost)
        // One ask left: the item stands for a single ask now.
        m.askWithdrawn(withdrawn(1, "auth:r1", "auth:r3", openUntil = soon))
        advanceUntilIdle()
        assertNull(arrived.last().count)
        assertEquals("b", m.items.value.single().itemId)
        assertEquals(3, m.items.value.single().count)
        job.cancel()
    }

    /**
     * (Owner decision 2026-11-18) An arrival made here while the owner check is due or held is never kept for the release
     * check's end: it goes as it would with no release check, and the lift sends nothing more.
     */
    @Test
    fun anArrivalWhileTheOwnerCheckGatesIsNotKeptForTheReleaseChecksEnd() = runTest {
        val ops = Ops()
        var checking = false
        var ownerHeld = false
        val m = opened(ops, batchItem("b", 1, "r1", count = 3), held = { checking }, ownerGated = { ownerHeld })
        val arrived = mutableListOf<FeedItem>()
        val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        checking = true
        ownerHeld = true
        m.askWithdrawn(withdrawn(2, "auth:r1", "auth:r2", "auth:r3"))
        advanceUntilIdle()
        assertEquals("not kept for the release check", 1, arrived.size)
        ownerHeld = false
        checking = false
        m.releaseHeld()
        advanceUntilIdle()
        assertEquals("nothing more at the lift", 1, arrived.size)
        job.cancel()
    }

    @Test
    fun aReadItemIsNotPostedAgainAndAnUnknownBatchDoesNothing() = runTest {
        val ops = Ops()
        val m = opened(ops, batchItem("b", 1, "r1", count = 2).copy(status = "read"))
        val arrived = mutableListOf<FeedItem>()
        val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.askWithdrawn(withdrawn(1, "auth:r1", "auth:r2"))
        m.askWithdrawn(WithdrawnBatch("c9", setOf("auth:zz"), now, 0, null))
        advanceUntilIdle()
        assertTrue(arrived.isEmpty())
        assertTrue(ops.updates.isEmpty())
        job.cancel()
    }

    @Test
    fun anItemWithoutACountStandsOnlyForItsOwnAskAndIsReadEvenIfOthersWait() = runTest {
        val ops = Ops()
        // Asks listed after an unlock collapse into one app batch; the withdrawn ask's own item is still its own.
        val m = opened(ops, batchItem("own", 1, "r2"), batchItem("other", 2, "r9", count = 2))
        m.askWithdrawn(WithdrawnBatch("c1", setOf("auth:r1", "auth:r2"), now, 1, null, askKey = "auth:r2"))
        settle { ops.updates.size == 1 }
        assertEquals(listOf("own" to "read"), ops.updates)
    }

    @Test
    fun theWindowFallbackIgnoresReadItemsAndUsesTheVaultsTimeWhenKnown() = runTest {
        val ops = Ops()
        // The phone's clock is a day off; the ask's vault time (askTs) finds the batch item.
        val m = opened(ops, batchItem("old", 1, "r0", count = 2, minutesAgo = 600).copy(status = "read"), batchItem("b", 2, "r1", count = 2, minutesAgo = 3))
        val phone = now.plusSeconds(86_400)
        m.askWithdrawn(WithdrawnBatch("c1", setOf("auth:r8", "auth:r9"), phone, 0, null, askKey = "auth:r9", askTs = now))
        settle { ops.updates.size == 1 }
        assertEquals(listOf("b" to "read"), ops.updates)
    }

    @Test
    fun twoVaultBatchesAfterAnUnlockEachKeepTheirOwnItem() = runTest {
        val ops = Ops()
        // Both batches' asks collapse into one app batch; the withdrawn ask r3 belongs to the item "second" (ref r3, count 2).
        val m = opened(ops, batchItem("first", 1, "r1", count = 2), batchItem("second", 2, "r3", count = 2))
        val arrived = mutableListOf<FeedItem>()
        val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.arrivals.collect { arrived += it } }
        m.askWithdrawn(WithdrawnBatch("c1", setOf("auth:r1", "auth:r2", "auth:r3", "auth:r4"), now, 3, null, askKey = "auth:r3"))
        advanceUntilIdle()
        assertEquals(listOf("second"), arrived.map { it.itemId })
        assertTrue(ops.updates.isEmpty())
        job.cancel()
    }

    private companion object {
        val json = kotlinx.serialization.json.Json { explicitNulls = false }
        const val SETTLE_TRIES = 500
        const val SETTLE_MS = 10L
    }

    /**
     * §3.6.3 and Rule 3: a catch-up reads nothing while the check is due or held or the release gate pauses the vault's work,
     * whoever asks for it (the return to the foreground, the relay's reconnection, a push wake, `sync.event`).
     */
    @Test
    fun aCatchUpReadsNothingWhileGated() = runTest {
        val ops = Ops()
        var gated = false
        val m = opened(ops, item("i1", 1), gated = { gated })
        val before = ops.lists.size
        gated = true
        m.catchUp()
        // A read would run on the IO dispatcher: give it the time to land before saying none did.
        repeat(5) {
            advanceUntilIdle()
            Thread.sleep(SETTLE_MS)
        }
        assertEquals(before, ops.lists.size)
        gated = false
        m.catchUp()
        settle { ops.lists.size == before + 1 }
        advanceUntilIdle()
        assertEquals(before + 1, ops.lists.size)
    }
}
