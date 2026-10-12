package com.vettid.core.data.vault

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The member's lock failing secure (owner decision 2026-10-12): pending, durably, until the vault is known to be
 * locked; tried again until then; never after a newer unlock. Virtual time, a fake store and a scripted vault.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockKeeperTest {
    private val t0: Instant = Instant.parse("2026-10-12T10:00:00Z")

    private class Store(var marker: LockMarker? = null) : LockMarkerStore {
        val writes = mutableListOf<LockMarker?>()

        override fun load(): LockMarker? = marker

        override fun save(marker: LockMarker?) {
            this.marker = marker
            writes += marker
        }
    }

    /** The vault as the keeper reaches it: each attempt is recorded, and answered from [answers] (false when empty). */
    private class Vault : LockAttempt {
        val attempts = mutableListOf<Pair<Long, Boolean>>() // (when, first)
        val answers = ArrayDeque<Boolean>()
        var clock: () -> Long = { 0 }
        var onAttempt: suspend () -> Unit = {}
        var throws = false

        override suspend fun tryLock(first: Boolean): Boolean {
            attempts += clock() to first
            onAttempt()
            if (throws) error("unreachable")
            return answers.removeFirstOrNull() ?: false
        }
    }

    private val store = Store()
    private val vault = Vault()

    /** Backoff without jitter: 1 s, 2 s, 5 s, 10 s, then 30 s. */
    private fun steps(n: Int): Duration = Duration.ofSeconds(listOf(1L, 2L, 5L, 10L).getOrElse(n) { 30L })

    private fun TestScope.keeper(s: Store = store, settle: () -> Duration = { Duration.ZERO }): LockKeeper {
        vault.clock = { testScheduler.currentTime }
        return LockKeeper(backgroundScope, s, vault, { t0.plusMillis(testScheduler.currentTime) }, ::steps, settle)
    }

    @Test
    fun aLockTheVaultAcknowledgesAtOnceIsNeverInDoubt() = runTest {
        val k = keeper()
        vault.answers += true
        // The marker is on the phone before anything is sent.
        vault.onAttempt = { assertEquals(LockMarker(t0.toEpochMilli()), store.marker) }
        assertTrue(k.ask())
        assertEquals(LockNotice.None, k.notice.value)
        assertFalse(k.pending)
        assertNull(store.marker)
        advanceTimeBy(120_000)
        assertEquals("no retry after a confirmed lock", listOf(0L to true), vault.attempts)
    }

    @Test
    fun aLockThatCannotBeConfirmedIsPendingAndTriedAgainUntilItIs() = runTest {
        val k = keeper()
        vault.answers += listOf(false, false, false, false, false, false, true)
        assertFalse("not confirmed: the caller is told so, the phone is locked all the same", k.ask())
        assertTrue(k.pending)
        assertEquals(LockNotice.Unconfirmed(t0, 1), k.notice.value)
        assertEquals(LockMarker(t0.toEpochMilli()), store.marker)
        advanceTimeBy(48_001) // 1 + 2 + 5 + 10 + 30 s: five more failures
        val tried = listOf(0L to true, 1_000L to false, 3_000L to false, 8_000L to false, 18_000L to false, 48_000L to false)
        assertEquals(tried, vault.attempts)
        assertEquals(LockNotice.Unconfirmed(t0, 6), k.notice.value)
        assertEquals("still on the phone", LockMarker(t0.toEpochMilli()), store.marker)
        advanceTimeBy(30_000)
        assertEquals("the member saw the warning: they are told it is over", LockNotice.ConfirmedLate, k.notice.value)
        assertFalse(k.pending)
        assertNull(store.marker)
        advanceTimeBy(300_000)
        assertEquals(7, vault.attempts.size)
        k.dismissConfirmed()
        assertEquals(LockNotice.None, k.notice.value)
    }

    @Test
    fun anAttemptThatThrowsIsAnAttemptThatDidNotConfirm() = runTest {
        val k = keeper()
        vault.throws = true
        assertFalse(k.ask())
        assertTrue(k.pending)
        vault.throws = false
        vault.answers += true
        advanceTimeBy(1_001)
        assertEquals(LockNotice.ConfirmedLate, k.notice.value)
    }

    /** Process death: a new keeper over the same store. It starts pending, and tries at once. */
    @Test
    fun aPendingLockSurvivesTheProcess() = runTest {
        val first = keeper()
        assertFalse(first.ask())
        val asked = store.marker
        val attemptsBefore = vault.attempts.size

        // The process ends here (nothing of `first` is used again); the next one finds the marker.
        val later = Store(asked)
        val next = LockKeeper(backgroundScope, later, vault, { t0.plusSeconds(3600) }, ::steps)
        assertFalse(next.pending)
        next.resume()
        assertTrue("locked from the start, before anything else", next.pending)
        assertEquals(LockNotice.Unconfirmed(t0, 0), next.notice.value)
        vault.answers += true
        runCurrent()
        assertEquals("tried at once, as a retry", false, vault.attempts[attemptsBefore].second)
        assertEquals(LockNotice.ConfirmedLate, next.notice.value)
        assertNull(later.marker)

        // No marker: nothing is pending and nothing is sent.
        val clean = LockKeeper(backgroundScope, Store(), vault, { t0 }, ::steps)
        clean.resume()
        runCurrent()
        assertFalse(clean.pending)
    }

    @Test
    fun aNewerUnlockReplacesAPendingLockWhichIsThenNeverSent() = runTest {
        val k = keeper()
        assertFalse(k.ask())
        advanceTimeBy(1_001)
        assertEquals(2, vault.attempts.size)
        // The member unlocks: the vault is open again, by their will, later than the lock.
        val opened = k.unlocking(opened = { it }) { true }
        assertTrue(opened)
        assertFalse(k.pending)
        assertEquals(LockNotice.None, k.notice.value)
        assertNull("cleared only now that the unlock succeeded", store.marker)
        advanceTimeBy(600_000)
        assertEquals("no attempt of the older lock, ever", 2, vault.attempts.size)
        // And nothing of it comes back with the next process.
        val next = LockKeeper(backgroundScope, store, vault, { t0 }, ::steps)
        next.resume()
        runCurrent()
        assertFalse(next.pending)
    }

    @Test
    fun anUnlockThatFailsLeavesThePendingLockAsItWas() = runTest {
        val k = keeper()
        assertFalse(k.ask())
        assertFalse(k.unlocking(opened = { it }) { false }) // a wrong PIN
        assertTrue(k.pending)
        assertEquals(LockMarker(t0.toEpochMilli()), store.marker)
        vault.answers += true
        advanceTimeBy(1_001)
        assertEquals(LockNotice.ConfirmedLate, k.notice.value)
    }

    /** The race: a retry that is due while the unlock runs waits for it, and is not made once the vault has opened. */
    @Test
    fun aRetryNeverRunsBesideAnUnlockNorAfterOne() = runTest {
        val k = keeper()
        assertFalse(k.ask())
        val unlockMayFinish = CompletableDeferred<Unit>()
        val unlock = async {
            k.unlocking(opened = { it }) {
                unlockMayFinish.await()
                true
            }
        }
        runCurrent()
        advanceTimeBy(60_000) // retries fall due while the unlock is in flight
        assertEquals("none was made beside the unlock", 1, vault.attempts.size)
        unlockMayFinish.complete(Unit)
        assertTrue(unlock.await())
        advanceTimeBy(600_000)
        assertEquals("and none after it", 1, vault.attempts.size)
        assertFalse(k.pending)
    }

    /** The other order: an attempt in flight finishes before the unlock starts. */
    @Test
    fun anUnlockWaitsForALockAttemptInFlight() = runTest {
        val k = keeper()
        val attemptMayFinish = CompletableDeferred<Unit>()
        vault.onAttempt = { attemptMayFinish.await() }
        val asking = async { k.ask() }
        runCurrent()
        var unlocked = false
        val unlock = async { k.unlocking(opened = { it }) { true.also { unlocked = true } } }
        runCurrent()
        assertFalse("the unlock waits", unlocked)
        attemptMayFinish.complete(Unit)
        assertFalse(asking.await())
        assertTrue(unlock.await())
        assertFalse(k.pending)
        advanceTimeBy(600_000)
        assertEquals(1, vault.attempts.size)
    }

    /** A lock request with no answer may still be on its way for a moment: the unlock is not sent before it has expired. */
    @Test
    fun anUnlockAfterAnUnansweredLockWaitsUntilThatRequestHasExpired() = runTest {
        val k = keeper(settle = { Duration.ofSeconds(30) })
        assertFalse(k.ask())
        var sentAt = -1L
        k.unlocking(opened = { it }) { true.also { sentAt = testScheduler.currentTime } }
        assertEquals(30_000L, sentAt)
        // Nothing pending: no wait.
        val start = testScheduler.currentTime
        k.unlocking(opened = { it }) { true }
        assertEquals(start, testScheduler.currentTime)
    }

    @Test
    fun theCallerOfALockMayGoAwayTheLockDoesNot() = runTest {
        val k = keeper()
        val attemptMayFinish = CompletableDeferred<Unit>()
        vault.onAttempt = { attemptMayFinish.await() }
        val caller = launch { k.ask() }
        runCurrent()
        caller.cancelAndJoin() // the screen that asked went away
        assertTrue("pending from the moment it was asked", k.pending)
        vault.onAttempt = {}
        attemptMayFinish.complete(Unit)
        vault.answers += listOf(false, true)
        advanceTimeBy(1_001)
        assertEquals(LockNotice.ConfirmedLate, k.notice.value)
        assertNull(store.marker)
    }

    @Test
    fun tryAgainAndEvidenceFromElsewhere() = runTest {
        val k = keeper()
        assertFalse(k.ask())
        advanceTimeBy(18_001) // now waiting 30 s between tries
        val n = vault.attempts.size
        k.retryNow() // "Try again", or the network came back
        runCurrent()
        assertEquals(n + 1, vault.attempts.size)
        // The vault's own `vault.locking`, or a status that says locked.
        k.confirmed()
        assertEquals(LockNotice.ConfirmedLate, k.notice.value)
        assertNull(store.marker)
        advanceTimeBy(600_000)
        assertEquals(n + 1, vault.attempts.size)
        // Evidence without a pending lock changes nothing.
        k.dismissConfirmed()
        k.confirmed()
        assertEquals(LockNotice.None, k.notice.value)
    }

    @Test
    fun aPhoneThatNoLongerHoldsTheVaultStopsTrying() = runTest {
        val k = keeper()
        assertFalse(k.ask())
        k.abandon() // erased, or the vault deleted
        assertFalse(k.pending)
        assertEquals(LockNotice.None, k.notice.value)
        assertNull(store.marker)
        advanceTimeBy(600_000)
        assertEquals(1, vault.attempts.size)
    }

    @Test
    fun aSecondLockWhileOneIsPendingIsOneLock() = runTest {
        val k = keeper()
        assertFalse(k.ask())
        advanceTimeBy(500)
        assertFalse(k.ask())
        advanceTimeBy(1_001)
        // The first lock's retry (due at 1 s) was replaced by the second's (due at 1.5 s): one sequence, not two.
        assertEquals(listOf(0L to true, 500L to true, 1_500L to false), vault.attempts)
        assertEquals(2, store.writes.count { it != null })
    }

    @Test
    fun theDefaultBackoffStartsQuicklyThenSettlesAtHalfAMinute() {
        val seconds = (0..6).map { LockKeeper.defaultBackoff(it).toMillis() / 1000.0 }
        val base = listOf(1.0, 2.0, 5.0, 10.0, 30.0, 30.0, 30.0)
        for ((got, want) in seconds.zip(base)) assertTrue("$got for $want", got >= want && got <= want * 1.2)
    }
}
