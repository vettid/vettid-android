package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * [PhaseHolder.setIf] (#242): the check and the publish as one step, so a lock that publishes Locked while an unlock's
 * end is being checked is never followed by that unlock's stale phase.
 */
class PhaseSetIfTest {
    @Test
    fun aLockDuringTheCheckPublishesAfterTheOpenPhase() {
        val phase = PhaseHolder(AppPhase.Locked)
        val checking = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opener = thread {
            phase.setIf(AppPhase.Unlocked) {
                checking.countDown()
                release.await(5, TimeUnit.SECONDS)
                true
            }
        }
        assertTrue(checking.await(5, TimeUnit.SECONDS))
        // The lock lands after the check passed and before the publish: it waits for the publish, and comes after it.
        val locker = thread { phase.value = AppPhase.Locked }
        // Blocked on the holder's lock (without it, the lock would have published at once and finished).
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (locker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.onSpinWait()
        assertEquals(Thread.State.BLOCKED, locker.state)
        release.countDown()
        opener.join(5_000)
        locker.join(5_000)
        assertFalse(opener.isAlive)
        assertFalse(locker.isAlive)
        assertEquals(AppPhase.Locked, phase.value)
    }

    @Test
    fun nothingIsPublishedWhenTheCheckFails() {
        val phase = PhaseHolder(AppPhase.Locked)
        assertFalse(phase.setIf(AppPhase.Unlocked) { false })
        assertEquals(AppPhase.Locked, phase.value)
        assertTrue(phase.setIf(AppPhase.Unlocked) { true })
        assertEquals(AppPhase.Unlocked, phase.value)
    }

    @Test
    fun beingDeletedStillHoldsOverACheckThatPassed() {
        val phase = PhaseHolder(AppPhase.Unlocked)
        val d = AppPhase.Deleting()
        phase.deleting(d)
        assertTrue(phase.setIf(AppPhase.Unlocked) { true })
        assertEquals(d, phase.value)
    }
}
