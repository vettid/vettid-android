package com.vettid.core.data.vault

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * (Show held as held, Android) The outbox is flushed once a mailbox's quota backoff ends, behind the lock gate: not while a
 * lock is pending (nothing but the lock goes), nor once the session ended or the device was stopped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuotaFlushTest {
    private var admits = true
    private var live = true
    private var flushes = 0

    private fun kotlinx.coroutines.test.TestScope.flush() = QuotaFlush(backgroundScope, { admits }, { live }, { flushes++ })

    @Test
    fun theOutboxIsFlushedWhenTheBackoffEnds() = runTest {
        val f = flush()
        f.backoffSet(60_000)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("not before the backoff has ended", 0, flushes)
        advanceTimeBy(QuotaFlush.MARGIN_MS + 1)
        runCurrent()
        assertEquals(1, flushes)
    }

    @Test
    fun notWhileALockIsPendingAndOnceTheLockIsConfirmedItGoes() = runTest {
        val f = flush()
        admits = false
        f.backoffSet(60_000)
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals("nothing but the lock goes", 0, flushes)
        advanceTimeBy(QuotaFlush.RECHECK_MS * 3)
        runCurrent()
        assertEquals("still pending", 0, flushes)
        admits = true // the lock is confirmed after the backoff ended
        advanceTimeBy(QuotaFlush.RECHECK_MS + 1)
        runCurrent()
        assertEquals("it goes at the next look", 1, flushes)
    }

    @Test
    fun afterTheLockItGoes() = runTest {
        val f = flush()
        admits = false
        f.backoffSet(60_000)
        advanceTimeBy(30_000)
        admits = true // the lock was confirmed while the backoff ran
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(1, flushes)
    }

    @Test
    fun notOnceTheSessionEndedOrTheDeviceStopped() = runTest {
        val f = flush()
        live = false
        f.backoffSet(60_000)
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(0, flushes)
    }

    @Test
    fun aLaterBackoffReplacesTheWait() = runTest {
        val f = flush()
        f.backoffSet(60_000)
        advanceTimeBy(30_000)
        f.backoffSet(120_000) // doubled: the first wait no longer counts
        advanceTimeBy(40_000)
        runCurrent()
        assertEquals(0, flushes)
        advanceTimeBy(81_000)
        runCurrent()
        assertEquals(1, flushes)
    }
}
