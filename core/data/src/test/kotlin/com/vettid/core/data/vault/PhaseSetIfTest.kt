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
        Thread.sleep(LOCK_TRIES_MS)
        release.countDown()
        opener.join(5_000)
        locker.join(5_000)
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

    private companion object {
        /** Long enough for the lock's thread to reach the holder's lock (it would publish at once without it). */
        const val LOCK_TRIES_MS = 200L
    }
}
