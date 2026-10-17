package com.vettid.core.data.vault

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private class Store(var record: LockRecord = LockRecord()) : LockRecordStore {
        val writes = mutableListOf<LockRecord>()

        override fun load(): LockRecord = record

        override fun save(record: LockRecord) {
            this.record = record
            writes += record
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

    private fun TestScope.now(): Instant = t0.plusMillis(testScheduler.currentTime)

    private fun TestScope.keeper(s: Store = store, attempt: LockAttempt = vault, backoff: (Int) -> Duration = ::steps): LockKeeper {
        vault.clock = { testScheduler.currentTime }
        return LockKeeper(backgroundScope, s, attempt, { now() }, backoff)
    }

    @Test
    fun aLockTheVaultAcknowledgesAtOnceIsNeverInDoubt() = runTest {
        val k = keeper()
        vault.answers += true
        // The marker is on the phone before anything is sent.
        vault.onAttempt = { assertEquals(LockRecord(t0.toEpochMilli()), store.record) }
        assertTrue(k.ask())
        assertEquals(LockNotice.None, k.notice.value)
        assertFalse(k.pending)
        assertEquals(LockRecord(), store.record)
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
        assertEquals(LockRecord(t0.toEpochMilli()), store.record)
        advanceTimeBy(48_001) // 1 + 2 + 5 + 10 + 30 s: five more failures
        val tried = listOf(0L to true, 1_000L to false, 3_000L to false, 8_000L to false, 18_000L to false, 48_000L to false)
        assertEquals(tried, vault.attempts)
        assertEquals(LockNotice.Unconfirmed(t0, 6), k.notice.value)
        assertEquals("still on the phone", LockRecord(t0.toEpochMilli()), store.record)
        advanceTimeBy(30_000)
        assertEquals("the member saw the warning: they are told it is over", LockNotice.ConfirmedLate, k.notice.value)
        assertFalse(k.pending)
        assertEquals(LockRecord(), store.record)
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
        val asked = store.record
        val attemptsBefore = vault.attempts.size

        // The process ends here (nothing of `first` is used again); the next one finds the marker.
        val later = Store(asked)
        val next = LockKeeper(backgroundScope, later, vault, { t0.plusSeconds(3600) }, ::steps)
        assertFalse(next.pending)
        next.resume()
        assertTrue("locked from the start, before anything else", next.pending)
        // In doubt from the start: it was not confirmed while the process was gone.
        assertEquals(LockNotice.Unconfirmed(t0, 1), next.notice.value)
        vault.answers += true
        runCurrent()
        assertEquals("tried at once, as a retry", false, vault.attempts[attemptsBefore].second)
        assertEquals(LockNotice.ConfirmedLate, next.notice.value)
        assertEquals(LockRecord(), later.record)

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
        assertEquals("cleared only now that the unlock succeeded", LockRecord(), store.record)
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
        assertEquals(LockRecord(t0.toEpochMilli()), store.record)
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

    /**
     * Review L3 of #127 (the reviewer's K1): the relay lock got no answer, the lock was then confirmed another way.
     * Nothing is pending, and the relay request may still be waiting for the vault, which collects again at the
     * next unlock: that unlock is not sent before the request has expired, with a margin.
     */
    @Test
    fun anUnlockWaitsOutAnUnansweredRelayLockAlsoWhenTheLockWasConfirmedAnotherWay() = runTest {
        lateinit var k: LockKeeper
        k = keeper(attempt = { _ ->
            k.relayLockSent(Duration.ofSeconds(30))
            delay(10_000) // no answer over the relay; then the status says it is locked
            true
        })
        assertTrue(k.ask())
        assertFalse(k.pending)
        assertEquals("kept on the phone after the confirmation", LockRecord(null, t0.plusSeconds(40).toEpochMilli()), store.record)
        var sentAt = -1L
        k.unlocking(opened = { it }) { true.also { sentAt = testScheduler.currentTime } }
        assertEquals("30 s of validity and 10 s of margin after the lock was sent", 40_000L, sentAt)
        assertEquals(LockRecord(), store.record)
        // Nothing left to wait for.
        val start = testScheduler.currentTime
        k.unlocking(opened = { it }) { true }
        assertEquals(start, testScheduler.currentTime)
    }

    /** L3 (b): the time is on the phone, so a new process waits too; and an answered relay lock is not waited for. */
    @Test
    fun theWaitForAnUnansweredRelayLockSurvivesTheProcessAndEndsWithItsAnswer() = runTest {
        val k = keeper()
        k.relayLockSent(Duration.ofSeconds(30))
        advanceTimeBy(5_000)
        val next = keeper() // the process died and started again: a keeper that knows only what is on the phone
        var sentAt = -1L
        next.unlocking(opened = { it }) { false.also { sentAt = testScheduler.currentTime } } // a wrong PIN
        assertEquals(40_000L, sentAt)
        // An unlock that failed after the wait does not wait again.
        next.unlocking(opened = { it }) { false.also { sentAt = testScheduler.currentTime } }
        assertEquals(40_000L, sentAt)

        k.relayLockSent(Duration.ofSeconds(30))
        k.relayLockAnswered()
        assertEquals(null, store.record.relayLockUntilMs)
        k.unlocking(opened = { it }) { true.also { sentAt = testScheduler.currentTime } }
        assertEquals("answered: the vault acted on it, nothing is on its way", 40_000L, sentAt)
    }

    /** A phone whose clock was set back must not hold an unlock for longer than the longest a relay lock can live. */
    @Test
    fun theWaitForARelayLockIsBounded() = runTest {
        store.record = LockRecord(null, t0.plus(Duration.ofDays(2)).toEpochMilli())
        val k = keeper()
        var sentAt = -1L
        k.unlocking(opened = { it }) { true.also { sentAt = testScheduler.currentTime } }
        assertEquals(LockKeeper.SETTLE_MAX.toMillis(), sentAt)
    }

    /** Review L4 of #127 (the reviewer's K2): while the first try runs nothing has failed, and nothing says it has. */
    @Test
    fun nothingIsInDoubtBeforeATryHasFailed() = runTest {
        val k = keeper()
        vault.onAttempt = { delay(3_000) }
        val asking = backgroundScope.launch { k.ask() }
        advanceTimeBy(1_000)
        val early = k.notice.value as LockNotice.Unconfirmed
        assertTrue("pending all the same: the phone is locked", k.pending)
        assertFalse("one second into a lock that has not failed: no warning", early.inDoubt)
        advanceTimeBy(2_001)
        assertTrue("the first try failed: now the vault may still be open", (k.notice.value as LockNotice.Unconfirmed).inDoubt)
        asking.cancel()

        // And a lock that the first try confirms, however long it took, never showed a warning nor "confirmed late".
        val other = keeper(s = Store())
        vault.answers += true
        assertTrue(other.ask())
        assertEquals(LockNotice.None, other.notice.value)
    }

    /**
     * Review L2 of #127, gap (a): the lock is pending and on the phone from [LockKeeper.begin], which the manager
     * calls before the hooks that run ahead of the lock (up to two seconds). A process that dies in that time
     * starts locked and tries.
     */
    @Test
    fun aLockIsPendingAndOnThePhoneBeforeAnythingElseIsDoneForIt() = runTest {
        val k = keeper()
        val mine = k.begin()
        assertTrue(k.pending)
        assertEquals(LockRecord(t0.toEpochMilli()), store.record)
        assertEquals("nothing was sent yet", 0, vault.attempts.size)

        // The process dies here, during the hooks.
        val next = keeper()
        next.resume()
        assertTrue(next.pending)
        runCurrent()
        assertEquals("the new process tries", 1, vault.attempts.size)

        // Without the death: the first attempt is the one of the lock that was begun.
        vault.answers += true
        assertTrue(k.ask(mine))
        assertFalse(k.pending)
    }

    /** A lock asked for while an unlock is on its way is the newer wish: the unlock, succeeding, does not undo it. */
    @Test
    fun aLockAskedForWhileAnUnlockIsOnItsWayOutlivesIt() = runTest {
        val k = keeper()
        val unlockMayFinish = CompletableDeferred<Unit>()
        val unlock = async {
            k.unlocking(opened = { it }) {
                unlockMayFinish.await()
                true
            }
        }
        runCurrent()
        vault.answers += true
        val asking = async { k.ask() }
        runCurrent()
        assertTrue(k.pending)
        assertEquals("the attempt waits for the unlock", 0, vault.attempts.size)
        unlockMayFinish.complete(Unit)
        assertTrue(unlock.await())
        assertTrue("the lock was confirmed after the unlock", asking.await())
        assertEquals(1, vault.attempts.size)
        assertFalse(k.pending)
        assertEquals(LockRecord(), store.record)
    }

    /** Review L7 of #127: "Try again" starts a try once in ten seconds at most. */
    @Test
    fun tryAgainStartsATryOnceInTenSecondsAtMost() = runTest {
        val k = keeper(backoff = { Duration.ofHours(1) })
        assertFalse("nothing pending: nothing to try", k.retryNow())
        assertFalse(k.ask())
        assertTrue(k.retryNow())
        runCurrent()
        assertEquals(2, vault.attempts.size)
        repeat(9) {
            advanceTimeBy(1_000)
            assertFalse(k.retryNow())
        }
        runCurrent()
        assertEquals("nine more taps in nine seconds: no more tries", 2, vault.attempts.size)
        advanceTimeBy(1_000)
        assertTrue(k.retryNow())
        runCurrent()
        assertEquals(3, vault.attempts.size)
    }

    /**
     * Review L7 of #127 (the reviewer's K3): every try is one `POST /api/vault/lock`, which the member API allows 30
     * times in 15 minutes per member. Counted without jitter (jitter only lengthens the waits) and with tries that
     * take no time (the worst case).
     */
    @Test
    fun theRetriesStayUnderTheMemberApisLimitForTheLockRoute() = runTest {
        val k = keeper(backoff = LockKeeper::backoffBase)
        assertFalse(k.ask())
        advanceTimeBy(15 * 60_000L)
        val first = vault.attempts.size
        assertEquals("the first 15 minutes", 21, first)
        advanceTimeBy(15 * 60_000L)
        assertEquals("every 15 minutes after: one a minute", 15, vault.attempts.size - first)
        assertTrue(k.pending)
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
        assertEquals(LockRecord(), store.record)
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
        assertEquals(LockRecord(), store.record)
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
        assertEquals(LockRecord(), store.record)
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
        assertEquals(2, store.writes.count { it.pendingSinceMs != null })
    }

    @Test
    fun theDefaultBackoffStartsQuicklyThenSettlesAtAMinute() {
        val seconds = (0..9).map { LockKeeper.defaultBackoff(it).toMillis() / 1000.0 }
        val base = listOf(1.0, 2.0, 5.0, 10.0, 30.0, 30.0, 30.0, 30.0, 60.0, 60.0)
        for ((got, want) in seconds.zip(base)) assertTrue("$got for $want", got >= want && got <= want * 1.2)
    }

    /** A store whose saves all fail (a full phone, a failed rename): [failures] counts the reports. */
    private class FailingStore : LockRecordStore {
        override fun load(): LockRecord = LockRecord()

        override fun save(record: LockRecord) = throw java.io.IOException("no space left")
    }

    @Test
    fun aLockWhoseRecordCannotBeSavedIsStillSentAndRetried() = runTest {
        var failures = 0
        vault.clock = { testScheduler.currentTime }
        val k = LockKeeper(backgroundScope, FailingStore(), vault, { now() }, ::steps, onSaveFailed = { failures++ })
        assertFalse(k.ask())
        // Pending in this process, sent at once, and reported.
        assertTrue(k.pending)
        assertEquals(listOf(0L to true), vault.attempts)
        assertTrue(failures > 0)
        // Tried again on the backoff, as with a record that was saved.
        advanceTimeBy(1_001)
        assertEquals(listOf(0L to true, 1_000L to false), vault.attempts)
        vault.answers += true
        advanceTimeBy(2_001)
        assertFalse(k.pending)
    }

    @Test
    fun aRelayLockWhoseRecordCannotBeSavedStillHoldsAnUnlock() = runTest {
        vault.clock = { testScheduler.currentTime }
        val k = LockKeeper(backgroundScope, FailingStore(), vault, { now() }, ::steps)
        k.relayLockSent(Duration.ofSeconds(30))
        var sentAt = -1L
        k.unlocking(opened = { it }) { true.also { sentAt = testScheduler.currentTime } }
        assertEquals("kept in memory: 30 s and the margin", 40_000L, sentAt)
    }

    @Test
    fun abandoningForgetsTheRelayLocksTimeInMemoryToo() = runTest {
        vault.clock = { testScheduler.currentTime }
        // Only in memory: the record could not be saved.
        val k = LockKeeper(backgroundScope, FailingStore(), vault, { now() }, ::steps)
        k.relayLockSent(Duration.ofSeconds(30))
        // The phone was erased; a vault enrolled after it unlocks in the same process at once.
        k.abandon()
        var sentAt = -1L
        k.unlocking(opened = { it }) { true.also { sentAt = testScheduler.currentTime } }
        assertEquals(0L, sentAt)
    }
}
