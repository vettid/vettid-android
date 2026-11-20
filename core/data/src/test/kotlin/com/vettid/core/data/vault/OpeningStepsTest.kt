package com.vettid.core.data.vault

import com.vettid.core.data.feed.FeedManager
import com.vettid.core.data.feed.FeedOps
import com.vettid.core.vault.FeedItem
import com.vettid.core.vault.FeedPage
import com.vettid.core.data.feed.WaitingAsks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * (ANDROID-PLAN 0.1.134) An opening carried across a status read that could not be made, driven as the vault manager drives it:
 * the phase holder's openings ([OpeningSteps.onOpen]), the phase collector's clears ([OpeningSteps.clearFeed],
 * [OpeningSteps.dropReplayOnClose]) and a kept waiting-asks replay, with a real feed and a real phase holder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpeningStepsTest {
    private val now = Instant.parse("2026-11-19T12:00:00Z")
    private val blip = AppPhase.Unreachable(FailureKind.NETWORK)

    private class NoFeed : FeedOps {
        /** What every full read finds. */
        var items: List<FeedItem> = emptyList()

        override suspend fun list(afterSeq: Long, limit: Int) =
            FeedPage(items.filter { it.seq > afterSeq }, maxOf(afterSeq, items.maxOfOrNull { it.seq } ?: 0))
        override suspend fun update(itemId: String, status: String): FeedItem = error("not here")
        override suspend fun delete(itemId: String) = Unit
        override suspend fun retentionDays(): Int? = null
    }

    private class World(scope: TestScope, now: Instant) {
        val held = HeldAskReplay()
        lateinit var phase: PhaseHolder
        val ops = NoFeed()
        val feed = FeedManager(scope.backgroundScope, ops = { ops }, now = { now }, vaultOpen = { phase.value == AppPhase.Unlocked })
        val steps = OpeningSteps(feed, held) { phase }

        init {
            phase = PhaseHolder(AppPhase.Locked, onOpen = { c -> steps.onOpen(c, now) })
        }

        /** A phase published; the feed's opening the collector notes when it receives it. */
        fun publish(p: AppPhase): Int {
            phase.value = p
            return feed.openingNumber
        }

        /** A phase published, then the collector's clears for it (as the manager's phase steps run them). */
        fun move(p: AppPhase) = collect(p, publish(p))

        /** The collector's clears for [p], received in the feed's opening [seen], whenever it reaches them. */
        fun collect(p: AppPhase, seen: Int) {
            if (p != AppPhase.Unlocked) {
                steps.clearFeed(p, seen)
                steps.dropReplayOnClose(p, seen)
            }
        }

        /** A check passed and its waiting-asks replay kept (a pause, or the vault not open): its window and the kept run. */
        fun keepReplay(): Int {
            val token = feed.startReplayWindow()
            held.hold(HeldAskReplay.Due(gen = 1, listMark = 0, token = token))
            return token
        }
    }

    /** The owner check's hold, a pass while the release gate pauses, then a blip: the continued opening still has the replay. */
    @Test
    fun aHoldThenAPassWhilePausedThenABlipKeepsTheReplay() = runTest {
        val w = World(this, now)
        w.move(AppPhase.Unlocked)
        w.feed.clear(endsOpening = false) // the hold's clear, as the owner-check collector makes it
        val token = w.keepReplay() // the pass, while the gate pauses the reads
        w.move(blip)
        w.move(AppPhase.Unlocked) // the opening continues
        assertTrue("kept for the continued opening's owed reads", w.held.held())
        assertTrue(w.feed.replayOwns(token))
    }

    /** A pass learnt during the blip, kept for the opening: it continues and the replay is there; one close drops it. */
    @Test
    fun aPassLearntDuringTheBlipIsKeptOnlyForTheSameOpening() = runTest {
        val w = World(this, now)
        w.move(AppPhase.Unlocked)
        w.move(blip)
        val token = w.keepReplay() // [OpeningSteps.placePassReplay] kept it
        w.move(AppPhase.Unlocked)
        assertTrue(w.held.held())
        assertTrue(w.feed.replayOwns(token))

        val v = World(this, now)
        v.move(AppPhase.Unlocked)
        v.move(blip)
        v.keepReplay()
        v.move(AppPhase.Locked) // a close in between
        v.move(AppPhase.Unlocked)
        assertFalse("a close drops it", v.held.held())
    }

    /**
     * A lock and an unlock faster than the phase collector: its clear for the lock, reached after the new opening was
     * published, ends nothing of that opening. A later blip keeps the new opening's replay.
     */
    @Test
    fun aLateCloseClearLeavesTheNewOpeningAlone() = runTest {
        val w = World(this, now)
        w.move(AppPhase.Unlocked)
        val seen = w.publish(AppPhase.Locked) // published; the collector is behind
        w.publish(AppPhase.Unlocked) // a new opening
        val token = w.keepReplay()
        w.collect(AppPhase.Locked, seen) // the collector reaches the lock now
        w.move(blip)
        w.move(AppPhase.Unlocked)
        assertTrue("the new opening's replay", w.held.held())
        assertTrue(w.feed.replayOwns(token))
    }

    /**
     * A blip, a lock and a new opening faster than the collector: its soft clear for the blip, reached in the new opening,
     * changes nothing there (the new opening's list, read, stays).
     */
    @Test
    fun aLateBlipClearLeavesTheNewOpeningAlone() = runTest {
        val w = World(this, now)
        w.move(AppPhase.Unlocked)
        val seen = w.publish(blip) // published; the collector is behind
        w.publish(AppPhase.Locked)
        w.publish(AppPhase.Unlocked) // a new opening
        w.feed.refresh() // its read
        assertTrue(w.feed.loaded)
        w.collect(blip, seen) // the collector reaches the blip now
        assertTrue("the new opening's list stays", w.feed.loaded)
    }

    /**
     * A lock, an unlock and a lock again, the second lock conflated away (the collector holds the first): its one late clear
     * finds the vault closed and clears it all, the newer opening's list and replay included.
     */
    @Test
    fun aConflatedSecondLockStillClears() = runTest {
        val w = World(this, now)
        w.move(AppPhase.Unlocked)
        val seen = w.publish(AppPhase.Locked)
        w.publish(AppPhase.Unlocked) // a new opening
        w.feed.refresh()
        w.keepReplay()
        w.publish(AppPhase.Locked) // the same value as the collector's: never delivered again
        w.collect(AppPhase.Locked, seen)
        assertFalse("cleared", w.feed.loaded)
        assertFalse(w.feed.openingContinues())
        assertFalse(w.held.held())
    }

    /** A blip whose late step finds the vault closed since: the clear of a close, not the blip's soft one. */
    @Test
    fun aLateBlipStepAfterACloseClearsItAll() = runTest {
        val w = World(this, now)
        w.move(AppPhase.Unlocked)
        w.feed.refresh()
        val seen = w.publish(blip)
        w.publish(AppPhase.Locked)
        w.collect(blip, seen)
        assertFalse("the opening ended", w.feed.openingContinues())
    }

    /**
     * A close, then a blip (a status read that could not be made, with the vault already closed), and the collector reaches only
     * the blip: it continues no opening, so it clears as the close would, list, opening and kept replay.
     */
    @Test
    fun aBlipRightAfterACloseClearsItAll() = runTest {
        val w = World(this, now)
        w.move(AppPhase.Unlocked)
        w.feed.refresh()
        w.keepReplay()
        w.publish(AppPhase.Locked) // conflated away: the collector never gets it
        val seen = w.publish(blip)
        assertFalse(w.phase.blipKeepsOpening)
        w.collect(blip, seen)
        assertFalse("the opening ended", w.feed.openingContinues())
        assertFalse("the replay went", w.held.held())
    }

    /** A blip of an open vault keeps the opening and its replay (the soft clear). */
    @Test
    fun aBlipOfAnOpenVaultKeepsTheOpening() = runTest {
        val w = World(this, now)
        w.move(AppPhase.Unlocked)
        w.feed.refresh()
        w.keepReplay()
        w.move(blip)
        assertTrue(w.phase.blipKeepsOpening)
        assertTrue("the replay stays", w.held.held())
        assertTrue("the opening continues", w.feed.openingContinues())
    }

    /** A blip and the return of the same opening, both before the collector reaches the blip: its soft clear is skipped. */
    @Test
    fun aLateBlipClearOfAContinuedOpeningLeavesItAlone() = runTest {
        val w = World(this, now)
        w.move(AppPhase.Unlocked)
        w.feed.refresh()
        val seen = w.publish(blip)
        w.publish(AppPhase.Unlocked) // the same opening continues
        assertEquals(seen, w.feed.openingNumber)
        w.collect(blip, seen) // the collector reaches the blip now
        assertTrue("the continued opening's list stays", w.feed.loaded)
    }

    /**
     * A lock, a new opening and a blip of it, all before the collector reaches the lock: the vault is not open now, but the
     * lock's clear is the old opening's, and the new one's list and opening stay.
     */
    @Test
    fun aLateLockClearReachedDuringANewOpeningsBlipLeavesItAlone() = runTest {
        val w = World(this, now)
        w.move(AppPhase.Unlocked)
        val seen = w.publish(AppPhase.Locked)
        w.publish(AppPhase.Unlocked) // a new opening
        w.feed.refresh()
        w.publish(blip)
        w.collect(AppPhase.Locked, seen) // the collector reaches the lock now
        assertTrue("the new opening's list stays", w.feed.loaded)
        assertTrue(w.feed.openingContinues())
    }

    /**
     * A pass learnt during the blip, placed on the phase as it is then: kept while still Unreachable, launched once open
     * again, dropped once closed (nothing is read with the vault closed).
     */
    @Test
    fun aPassIsPlacedOnThePhaseAsItIsNow() = runTest {
        val w = World(this, now)
        val due = HeldAskReplay.Due(1, 0, 0)
        w.move(AppPhase.Unlocked)
        val first = w.feed.openingNumber
        assertEquals(OpeningSteps.PassReplay.LAUNCH, w.steps.placePassReplay(due, first))
        assertFalse(w.held.held())
        w.move(AppPhase.Locked)
        assertEquals(OpeningSteps.PassReplay.DROP, w.steps.placePassReplay(due, first))
        assertFalse(w.held.held())
        w.move(AppPhase.Unlocked) // a new opening
        val second = w.feed.openingNumber
        w.move(blip)
        assertEquals(OpeningSteps.PassReplay.HELD, w.steps.placePassReplay(due, second))
        assertTrue(w.held.held())
    }

    /** A pass learnt in a blip, placed after a lock and a new opening came: dropped, never launched at an unlock. */
    @Test
    fun aPassFromAnEarlierOpeningIsDropped() = runTest {
        val w = World(this, now)
        w.move(AppPhase.Unlocked)
        w.move(blip)
        val atPass = w.feed.openingNumber
        w.publish(AppPhase.Locked)
        w.publish(AppPhase.Unlocked) // a new opening, before the pass is placed
        assertEquals(OpeningSteps.PassReplay.DROP, w.steps.placePassReplay(HeldAskReplay.Due(1, 0, 0), atPass))
        w.publish(blip)
        assertEquals(OpeningSteps.PassReplay.DROP, w.steps.placePassReplay(HeldAskReplay.Due(1, 0, 0), atPass))
        assertFalse(w.held.held())
    }

    /**
     * End to end: a pass learnt during a blip is held; the opening continues; its owed reads take it and run the waiting-asks
     * replay (as `afterHoldPassed` does), which notifies the ask once.
     */
    @Test
    fun aHeldReplayRunsOnceWhenTheOpeningContinues() = runTest {
        val w = World(this, now)
        val ask = FeedItem("a1", 1, WaitingAsks.SIGN_IN, now.minusSeconds(120).toString(), "active", connectionId = "c1", ref = "r1")
        w.ops.items = listOf(ask)
        val arrived = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            w.feed.arrivals.collect { arrived += it.itemId }
        }
        w.move(AppPhase.Unlocked)
        w.feed.refresh()
        w.move(blip)
        val token = w.feed.startReplayWindow()
        val placed = w.steps.placePassReplay(HeldAskReplay.Due(1, 0, token), w.feed.openingNumber)
        assertEquals(OpeningSteps.PassReplay.HELD, placed)
        w.move(AppPhase.Unlocked) // the opening continues
        val due = w.held.take() // its owed reads (afterGatedReads)
        assertEquals(token, due?.token)
        val approval = com.vettid.core.data.social.Approval.Authentication("r1", "c1", null, now.minusSeconds(120), null)
        val ran = WaitingAsksRun(
            open = { w.feed.replayOwns(token) },
            feedLoaded = { w.feed.loaded },
            readFeed = { w.feed.refresh() },
            listed = { true },
            readList = {},
            replay = { w.feed.replayWaitingAsks(token, listOf(approval), now) },
            end = { w.feed.endReplayWindow(token) },
            waits = longArrayOf(0),
        ).run()
        assertTrue(ran)
        assertEquals(listOf("a1"), arrived)
    }

    /** A close the collector reaches in time clears the feed and ends the opening: the next is a new one, holding nothing. */
    @Test
    fun aCloseInTimeEndsTheOpening() = runTest {
        val w = World(this, now)
        w.move(AppPhase.Unlocked)
        val token = w.keepReplay()
        w.move(AppPhase.Locked)
        assertEquals(false, w.feed.openingContinues())
        w.move(AppPhase.Unlocked)
        assertFalse(w.held.held())
        assertFalse(w.feed.replayOwns(token))
    }
}
