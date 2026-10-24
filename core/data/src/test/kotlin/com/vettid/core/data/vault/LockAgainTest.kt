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
}
