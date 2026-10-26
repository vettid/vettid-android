package com.vettid.core.data.vault

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The manager's wiring of release L's lock rules, through the functions it calls ([LockWiring], [LockNotices.confirms]
 * with the keeper, [LockAgain.start]); each test fails when the matching line is taken out (review of #193).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockWiringTest {
    private val dead = "00112233445566778899aabbccddeeff"
    private val newer = "ffeeddccbbaa99887766554433221100"
    private val w = "aabbccddeeff00112233445566778899"
    private val z = "11223344556677889900aabbccddeeff"
    private val t0 = Instant.parse("2026-10-24T10:00:00Z")

    private class Store(var record: LockRecord = LockRecord()) : LockRecordStore {
        override fun load(): LockRecord = record

        override fun save(record: LockRecord) {
            this.record = record
        }
    }

    private class Ports : LockPorts {
        override val relayValidFor: Duration = Duration.ofSeconds(30)
        var relayAnswer = RelayLock.STALE
        val relayNamed = mutableListOf<String?>()
        val memberNamed = mutableListOf<String?>()
        var unlocked: Boolean? = true

        override suspend fun relayLock(unlockId: String?): RelayLock {
            relayNamed += unlockId
            return relayAnswer
        }

        override suspend fun memberLock(unlockId: String?): MemberLock {
            memberNamed += unlockId
            return MemberLock.NoAnswer
        }

        override suspend fun unlocked(): Boolean? = unlocked
    }

    private fun locking(ts: Instant, id: String?) = VaultMessage(
        Inner(id = Ulid.new(), type = "vault.locking", ts = ts, body = (if (id == null) "{}" else """{"unlock_id":"$id"}""").toByteArray()),
    )

    /** (e) After "opened again" a plain lock is sent, over the relay and the member API, without an unlock_id. */
    @Test
    fun aPlainLockAfterOpenedAgainNamesNoUnlock() = runTest {
        val ports = Ports()
        var held: String? = dead // the device's name for the opening
        val guard = LockGuard(ports, { t0 }) { attempt ->
            LockWiring.keeper(backgroundScope, Store(), attempt, {}) { d -> if (held == d) held = null }
        }
        val k = guard.keeper
        // The lock bound to the dead opening is answered stale_lock, the status says unlocked: opened again.
        assertFalse(k.ask(k.begin(held)))
        assertEquals(LockNotice.OpenedAgain, k.notice.value)
        assertEquals(listOf<String?>(dead), ports.relayNamed)
        assertNull("the dead name is forgotten", held)
        // A later plain lock (a release update's, the move's) is bound to nothing: unconditional.
        ports.relayNamed.clear()
        ports.memberNamed.clear()
        ports.relayAnswer = RelayLock.NO_ANSWER
        k.ask(k.begin(held))
        assertEquals(listOf<String?>(null), ports.relayNamed)
        assertEquals(listOf<String?>(null), ports.memberNamed)
    }

    /** (f) Held after "opened again": a vault.locking naming the dead opening leaves the notice as it is. */
    @Test
    fun aDeadNamedNoticeDoesNotTurnOpenedAgainIntoLocked() = runTest {
        val ports = Ports()
        val guard = LockGuard(ports, { t0 }) { attempt -> LockWiring.keeper(backgroundScope, Store(), attempt, {}) {} }
        val k = guard.keeper
        k.ask(k.begin(dead))
        assertEquals(LockNotice.OpenedAgain, k.notice.value)
        val notices = LockNotices(opened = t0.minusSeconds(60))
        val late = t0.plusSeconds(5)
        assertFalse(notices.confirms(locking(late, dead), k))
        assertFalse(notices.confirms(locking(late, null), k))
        assertEquals(LockNotice.OpenedAgain, k.notice.value)
        // A newer name does.
        assertTrue(notices.confirms(locking(late, newer), k))
    }

    /** (g) "Lock it now" during an unlock: the late status answer under Z does not rebind the lock moved to W. */
    @Test
    fun lockAgainDuringAnUnlockStaysBoundToTheNewOpening() = runTest {
        val store = Store()
        val ports = Ports()
        val guard = LockGuard(ports, { t0 }) { attempt -> LockWiring.keeper(backgroundScope, store, attempt, {}) {} }
        val k = guard.keeper
        k.ask(k.begin(dead)) // stale: opened again, held
        k.unlocking({ true }, { null }) { "earlier unlock" } // the count of openings is not 0 any more
        k.ask(k.begin(dead))
        var statusAnswer: String? = null
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        k.unlocking({ true }, { w }) {
            val mine = k.begin(null)
            // The read is answered under Z once the unlock has returned.
            jobs += LockAgain.start(backgroundScope, k, mine, Duration.ofSeconds(5), { statusAnswer = z; z }) {}
            "ok"
        }
        jobs.forEach { it.join() }
        assertEquals("the status was read", z, statusAnswer)
        assertEquals("the lock stays on W", w, k.boundUnlockId)
        assertEquals(w, store.record.unlockId)
    }

    /** (g) With no unlock in flight the status read binds the fresh lock (the mark is the count taken before the read). */
    @Test
    fun lockAgainWithNoUnlockInFlightBindsToTheStatus() = runTest {
        val store = Store()
        val ports = Ports()
        val guard = LockGuard(ports, { t0 }) { attempt -> LockWiring.keeper(backgroundScope, store, attempt, {}) {} }
        val k = guard.keeper
        k.unlocking({ true }, { null }) { "earlier unlock" }
        k.ask(k.begin(dead))
        val mine = k.begin(null)
        val j = LockAgain.start(backgroundScope, k, mine, Duration.ofSeconds(5), { z }) {}
        j.join()
        assertEquals(z, k.boundUnlockId)
        assertEquals(z, store.record.unlockId)
    }
}
