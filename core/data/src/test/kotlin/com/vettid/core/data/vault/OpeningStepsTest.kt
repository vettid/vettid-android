package com.vettid.core.data.vault

import com.vettid.core.data.feed.FeedManager
import com.vettid.core.data.feed.FeedOps
import com.vettid.core.vault.FeedItem
import com.vettid.core.vault.FeedPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        override suspend fun list(afterSeq: Long, limit: Int) = FeedPage(emptyList(), afterSeq)
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

        /** A phase published, then the collector's clears for it (as the manager's phase steps run them). */
        fun move(p: AppPhase) {
            phase.value = p
            collect(p)
        }

        /** The collector's clears for [p], whenever it reaches them. */
        fun collect(p: AppPhase) {
            if (p != AppPhase.Unlocked) {
                steps.clearFeed(p)
                steps.dropReplayOnClose(p)
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
        w.phase.value = AppPhase.Locked // published; the collector is behind
        w.phase.value = AppPhase.Unlocked // a new opening
        val token = w.keepReplay()
        w.collect(AppPhase.Locked) // the collector reaches the lock now
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
        w.phase.value = blip // published; the collector is behind
        w.phase.value = AppPhase.Locked
        w.phase.value = AppPhase.Unlocked // a new opening
        w.feed.refresh() // its read
        assertTrue(w.feed.loaded)
        w.collect(blip) // the collector reaches the blip now
        assertTrue("the new opening's list stays", w.feed.loaded)
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
        assertEquals(OpeningSteps.PassReplay.LAUNCH, w.steps.placePassReplay(due))
        assertFalse(w.held.held())
        w.move(AppPhase.Locked)
        assertEquals(OpeningSteps.PassReplay.DROP, w.steps.placePassReplay(due))
        assertFalse(w.held.held())
        w.move(blip)
        assertEquals(OpeningSteps.PassReplay.HELD, w.steps.placePassReplay(due))
        assertTrue(w.held.held())
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
