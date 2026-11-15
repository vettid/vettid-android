package com.vettid.core.data.vault

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (ANDROID-PLAN 0.1.132, Notifications 11) The reads behind the waiting-asks replay after a passed check: tried again until
 * both are had or the session ends, however long that takes; a lock during the reads replays nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaitingAsksRunTest {
    private class Fake {
        var open = true
        var feedLoaded = true
        var feedReads = 0
        var feedDown = false
        var listed = false
        var listReads = 0
        var listDown = false
        var unsupported = false
        var replays = 0
        var ended = 0

        /** Runs during a read (a lock that comes while it is out). */
        var duringListRead: () -> Unit = {}

        fun run() = WaitingAsksRun(
            open = { open },
            feedLoaded = { feedLoaded },
            readFeed = {
                feedReads++
                if (feedDown) error("feed.list failed")
                feedLoaded = true
            },
            listed = { listed },
            unsupported = { unsupported },
            readList = {
                listReads++
                duringListRead()
                if (listDown) error("pending.list failed")
                listed = true
            },
            replay = {
                if (feedLoaded) replays++
                feedLoaded
            },
            end = { ended++ },
        )
    }

    /** The list the hold's end read is merged by the first try: replayed once, no read of its own. */
    @Test
    fun aListAlreadyReadReplaysAtTheFirstTry() = runTest {
        val f = Fake().apply { listed = true }
        assertTrue(f.run().run())
        assertEquals(1, f.replays)
        assertEquals(0, f.listReads)
        assertEquals(0, f.ended)
    }

    /** No successful `pending.list` read for long past the old ~82 s: still tried, every minute, until one comes. */
    @Test
    fun noSuccessfulListReadKeepsTrying() = runTest {
        val f = Fake().apply { listDown = true }
        val run = async { f.run().run() }
        advanceTimeBy(30 * 60_000L)
        runCurrent()
        assertFalse(run.isCompleted)
        assertTrue("tried again and again", f.listReads > 20)
        f.listDown = false
        advanceTimeBy(60_001L)
        runCurrent()
        assertTrue(run.await())
        assertEquals(1, f.replays)
        assertEquals(0, f.ended)
    }

    /** A feed read that failed or still runs is read again; the replay waits for it and keeps its window meanwhile. */
    @Test
    fun aFailedFeedReadIsTriedAgain() = runTest {
        val f = Fake().apply {
            feedLoaded = false
            feedDown = true
            listed = true
        }
        val run = async { f.run().run() }
        advanceTimeBy(2_000L + 5_000L + 1)
        runCurrent()
        assertEquals(2, f.feedReads)
        assertEquals(0, f.replays)
        f.feedDown = false
        advanceTimeBy(15_001L)
        runCurrent()
        assertTrue(run.await())
        assertEquals(1, f.replays)
        assertEquals(0, f.ended)
    }

    /** A lock during the reads: nothing is replayed, the window is closed, and nothing more is read. */
    @Test
    fun aLockDuringTheReadsReplaysNothing() = runTest {
        val f = Fake()
        f.duringListRead = { f.open = false }
        assertFalse(f.run().run())
        assertEquals(0, f.replays)
        assertEquals(1, f.ended)
        assertEquals(1, f.listReads)
    }

    /** A lock during the feed read: the list is not read after it. */
    @Test
    fun aLockDuringTheFeedReadSkipsTheListRead() = runTest {
        val f = Fake().apply { feedLoaded = false }
        val run = WaitingAsksRun(
            open = { f.open },
            feedLoaded = { f.feedLoaded },
            readFeed = {
                f.feedReads++
                f.open = false
            },
            listed = { f.listed },
            unsupported = { f.unsupported },
            readList = { f.listReads++ },
            replay = { true },
            end = { f.ended++ },
        )
        assertFalse(run.run())
        assertEquals(1, f.feedReads)
        assertEquals(0, f.listReads)
        assertEquals(1, f.ended)
    }

    /** A lock between tries ends the run at the next one, without a read. */
    @Test
    fun aLockBetweenTriesEndsTheRun() = runTest {
        val f = Fake().apply { listDown = true }
        val run = async { f.run().run() }
        advanceTimeBy(2_001L)
        runCurrent()
        assertEquals(1, f.listReads)
        f.open = false
        advanceTimeBy(5_001L)
        runCurrent()
        assertFalse(run.await())
        assertEquals(1, f.listReads)
        assertEquals(1, f.ended)
    }

    /** An older vault without `pending.list` merges nothing: nothing is replayed. */
    @Test
    fun anOlderVaultReplaysNothing() = runTest {
        val f = Fake().apply { unsupported = true }
        assertFalse(f.run().run())
        assertEquals(0, f.replays)
        assertEquals(1, f.ended)
    }
}
