package com.vettid.core.data.vault

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * (Release L, owner decision 2026-10-24) "Lock it now" after "Your vault was opened again": one fresh lock, bound to the
 * opening the vault's status names now, unconditional when none is known; then the lock's usual rules.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockAgainTest {
    private val now = "00112233445566778899aabbccddeeff"
    private val old = "ffeeddccbbaa99887766554433221100"

    @Test
    fun theFreshLockNamesTheOpeningRunningNowOrNone() = runTest {
        assertEquals(now, LockAgain.unlockId { now })
        assertNull("the status names none (a vault before L)", LockAgain.unlockId { null })
        assertNull("not of the form", LockAgain.unlockId { "" })
        assertNull("not readable: unconditional, never the stale value", LockAgain.unlockId { throw IOException("offline") })
    }

    private class Store(var record: LockRecord = LockRecord()) : LockRecordStore {
        override fun load(): LockRecord = record

        override fun save(record: LockRecord) {
            this.record = record
        }
    }

    /** The vault: the first lock is answered stale; the fresh one as [fresh] says; every attempt's binding recorded. */
    private class Vault : LockAttempt {
        val bound = mutableListOf<String?>()
        val outcomes = ArrayDeque<LockOutcome>()

        override suspend fun tryLock(first: Boolean, unlockId: String?): LockOutcome {
            bound += unlockId
            return outcomes.removeFirstOrNull() ?: LockOutcome.UNCONFIRMED
        }
    }

    @Test
    fun lockItNowSendsOneBoundLockThatConfirmsOrRetriesLikeAnyLock() = runTest {
        val store = Store()
        val vault = Vault()
        val t0 = Instant.parse("2026-10-24T10:00:00Z")
        val k = LockKeeper(backgroundScope, store, vault, { t0.plusMillis(testScheduler.currentTime) }, { Duration.ofSeconds(1) })
        vault.outcomes += LockOutcome.OPENED_AGAIN
        assertFalse(k.ask(k.begin(old)))
        assertEquals(LockNotice.OpenedAgain, k.notice.value)
        // "Lock it now": one fresh lock, bound to the opening now; not confirmed at first, retried as any lock.
        assertFalse(k.ask(k.begin(LockAgain.unlockId { now })))
        assertTrue(k.pending)
        assertTrue("still held", k.holdsPhone)
        assertEquals(LockRecord(t0.toEpochMilli(), null, now, held = true), store.record)
        vault.outcomes += LockOutcome.LOCKED
        advanceTimeBy(1_001)
        assertEquals("one try, then a retry, both named", listOf(old, now, now), vault.bound)
        assertEquals(LockNotice.ConfirmedLate, k.notice.value)
        assertFalse("locked: the hold ends with it", k.holdsPhone)
        assertEquals(LockRecord(), store.record)
    }

    /** Held after "opened again", a status that is not "unlocked" replaces the notice with "locked"; still held. */
    @Test
    fun aLaterLockedStatusReplacesOpenedAgain() = runTest {
        val vault = Vault().apply { outcomes += LockOutcome.OPENED_AGAIN }
        val k = LockKeeper(backgroundScope, Store(), vault, { Instant.EPOCH }, { Duration.ofHours(1) })
        k.ask(k.begin(old))
        k.confirmed()
        assertEquals(LockNotice.ConfirmedLate, k.notice.value)
        assertTrue(k.holdsPhone)
    }

    /** Second recheck of #190, D3: after a restart such a phone is still held, and is not told "opened again" again. */
    @Test
    fun heldAndConfirmedSaysNothingNewAfterARestart() = runTest {
        val store = Store()
        val vault = Vault().apply { outcomes += LockOutcome.OPENED_AGAIN }
        val k = LockKeeper(backgroundScope, store, vault, { Instant.EPOCH }, { Duration.ofHours(1) })
        k.ask(k.begin(old))
        k.confirmed()
        val next = LockKeeper(backgroundScope, Store(store.record), vault, { Instant.EPOCH }, { Duration.ofHours(1) })
        next.resume()
        assertTrue(next.holdsPhone)
        assertEquals(LockNotice.None, next.notice.value)
    }

    /**
     * Second recheck of #190, D1: "Lock it now" is pending, unbound, before the status is read; the read binds it before
     * its first attempt; a read that names nothing leaves it unbound (as before L).
     */
    @Test
    fun theFreshLockIsPendingBeforeTheReadAndBoundBeforeItsFirstAttempt() = runTest {
        val store = Store()
        val vault = Vault().apply { outcomes += LockOutcome.LOCKED }
        val k = LockKeeper(backgroundScope, store, vault, { Instant.EPOCH }, { Duration.ofHours(1) })
        val mine = k.begin(null)
        assertTrue("pending before anything is read", k.pending)
        assertEquals(null, store.record.unlockId)
        k.rebind(mine, now, k.openingMark())
        assertEquals(now, store.record.unlockId)
        assertTrue(k.ask(mine))
        assertEquals(listOf<String?>(now), vault.bound)
        // Unbound when the read names nothing; and a rebind of a lock that is no longer pending does nothing.
        vault.outcomes += LockOutcome.LOCKED
        val two = k.begin(null)
        k.rebind(two, null, k.openingMark())
        assertTrue(k.ask(two))
        assertEquals(listOf<String?>(now, null), vault.bound)
        k.rebind(two, now, k.openingMark())
        assertEquals(LockRecord(), store.record)
    }

    private val w = "aabbccddeeff00112233445566778899"
    private val z = "11223344556677889900aabbccddeeff"

    /**
     * Review of the iOS mirror, defect 1: held on "opened again", the member unlocks (result W) and taps "Lock it now"
     * while that unlock is in flight; the status read is answered under Z and arrives after W bound the pending lock.
     * The late answer is dropped: the lock stays on W, and ends the member's own opening.
     */
    @Test
    fun aStatusAnswerFromBeforeAnUnlockThatCameSinceIsNotBoundToTheLock() = runTest {
        val store = Store()
        val vault = Vault().apply { outcomes += LockOutcome.OPENED_AGAIN }
        val k = LockKeeper(backgroundScope, store, vault, { Instant.EPOCH }, { Duration.ofHours(1) })
        k.ask(k.begin(old))
        assertTrue(k.openedAgain)
        // The unlock is in flight when the member taps: pending unbound, the mark taken before the read.
        var mine = 0L
        var mark = 0L
        // The unlock succeeds (W) and rebinds the pending lock.
        k.unlocking({ true }, { w }) {
            mine = k.begin(null)
            mark = k.openingMark()
            "ok"
        }
        assertEquals(w, store.record.unlockId)
        assertTrue("the newer lock stays pending", k.pending)
        // The late answer, under Z.
        k.rebind(mine, z, mark)
        assertEquals(w, store.record.unlockId)
        assertEquals(w, k.boundUnlockId)
        assertTrue(k.ask(mine.also { vault.outcomes += LockOutcome.LOCKED }))
        assertEquals("the lock names W, never Z", w, vault.bound.last())
    }

    /** The same race when the unlock result names none: the stale answer must not bind the lock either. */
    @Test
    fun aLateAnswerDoesNotBindEvenWhenTheUnlockNamedNone() = runTest {
        val store = Store()
        val k = LockKeeper(backgroundScope, store, Vault(), { Instant.EPOCH }, { Duration.ofHours(1) })
        var mine = 0L
        var mark = 0L
        k.unlocking({ true }, { null }) {
            mine = k.begin(null)
            mark = k.openingMark()
            "ok"
        }
        k.rebind(mine, z, mark)
        assertNull(k.boundUnlockId)
        assertNull(store.record.unlockId)
    }

    /** Defect 2: "opened again" forgets the dead name; the keeper says which one it was. */
    @Test
    fun openedAgainForgetsTheDeadOpening() = runTest {
        val forgotten = mutableListOf<String?>()
        val vault = Vault().apply { outcomes += LockOutcome.OPENED_AGAIN }
        val k = LockKeeper(backgroundScope, Store(), vault, { Instant.EPOCH }, { Duration.ofHours(1) }, onOpenedAgain = { forgotten += it })
        k.ask(k.begin(old))
        assertEquals(listOf<String?>(old), forgotten)
        assertEquals(old, k.deadUnlockId)
        // A new lock, or an unlock here, ends that.
        k.begin(null)
        assertNull(k.deadUnlockId)
    }
}
