package com.vettid.core.data.vault

import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * The rules of a pending lock ([LockGuard]), through the three ways the vault is reached. Each of the first four
 * tests fails when its rule is taken out (the mutations of the #127 review).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockGuardTest {
    private val t0: Instant = Instant.parse("2026-10-12T10:00:00Z")

    private class Store(var record: LockRecord = LockRecord()) : LockRecordStore {
        override fun load(): LockRecord = record

        override fun save(record: LockRecord) {
            this.record = record
        }
    }

    /** The vault as the app reaches it for a lock; everything it was asked, in order. */
    private class Ports : LockPorts {
        val asked = mutableListOf<String>()
        var relay = false
        var member: MemberLock = MemberLock.NoAnswer
        var unlocked: Boolean? = true
        override val relayValidFor: Duration = Duration.ofSeconds(30)

        override suspend fun relayLock(): Boolean = relay.also { asked += "relay" }

        override suspend fun memberLock(): MemberLock = member.also { asked += "member" }

        override suspend fun unlocked(): Boolean? = unlocked.also { asked += "status" }
    }

    private val ports = Ports()
    private val store = Store()

    private fun TestScope.guard(): LockGuard {
        val now = { t0.plusMillis(testScheduler.currentTime) }
        // Retries only when a test advances an hour: each test makes its attempts itself.
        return LockGuard(ports, now) { attempt -> LockKeeper(backgroundScope, store, attempt, now, backoff = { Duration.ofHours(1) }) }
    }

    /** Mutation 1 (confirm on the member API's answer, "expired" or "done"): the answer is never proof. */
    @Test
    fun theMemberApisAnswerToALockNeverConfirmsIt() = runTest {
        val g = guard()
        for (answer in listOf(MemberLock.Answered(done = false), MemberLock.Answered(done = true), MemberLock.NoAnswer)) {
            ports.member = answer
            assertFalse("$answer, and the status still says unlocked", g.attempt(first = false))
        }
        // The enclave answers "done" whatever became of its lock: only the status decides.
        ports.member = MemberLock.Answered(done = true)
        ports.unlocked = null
        assertFalse("a status that could not be read confirms nothing", g.attempt(first = false))
        ports.unlocked = false
        assertTrue(g.attempt(first = false))
        // And through the keeper: pending until the status says so.
        ports.unlocked = true
        assertFalse(g.keeper.ask())
        assertTrue(g.pending)
        ports.unlocked = false
        advanceTimeBy(3_600_001)
        assertFalse(g.pending)
        assertEquals(LockNotice.ConfirmedLate, g.keeper.notice.value)
    }

    /** Mutation 2 (the phase trusts the status while a lock is pending). */
    @Test
    fun whileALockIsPendingThisPhoneIsLockedWhateverTheStatusSays() = runTest {
        val g = guard()
        assertFalse("nothing pending: the status decides", g.lockedWhatever(unlocked = true))
        assertFalse(g.keeper.ask())
        assertTrue("the status says unlocked: locked here all the same", g.lockedWhatever(unlocked = true))
        assertTrue(g.pending)
        assertTrue("not readable: locked, and not confirmed", g.lockedWhatever(unlocked = null))
        assertTrue(g.pending)
        assertTrue("it says locked: locked, and now confirmed", g.lockedWhatever(unlocked = false))
        assertFalse(g.pending)
        assertEquals(LockRecord(null, t0.plusSeconds(40).toEpochMilli()), store.record)
        assertFalse(g.lockedWhatever(unlocked = true))
    }

    /** Mutation 3 (the vault client is handed out while a lock is pending). */
    @Test
    fun whileALockIsPendingNothingIsSentToTheVaultButTheLockAndAHangUp() = runTest {
        val g = guard()
        assertTrue(g.admits())
        assertFalse(g.keeper.ask())
        assertFalse("an operation on the open vault", g.admits())
        assertTrue(g.admits(LockTimeUse.LOCK))
        assertTrue(g.admits(LockTimeUse.CALL_END))
        g.keeper.confirmed()
        assertTrue(g.admits())
    }

    /** Mutation 4 (what the vault sends is taken into the app's lists while a lock is pending). */
    @Test
    fun whileALockIsPendingNothingTheVaultSendsIsTakenIn() = runTest {
        val g = guard()
        assertTrue(g.takesEvents())
        assertFalse(g.keeper.ask())
        assertFalse(g.takesEvents())
        g.keeper.unlocking(opened = { it }) { true }
        assertTrue("an unlock later than the lock: open again", g.takesEvents())
    }

    /**
     * Review L2 of #127, gap (b), and L8: from the moment the lock is asked for, before its hooks and its first
     * attempt, every rule holds: a phase computed then is Locked, nothing is admitted but the hang-up of the call
     * the lock ends, nothing is taken in.
     */
    @Test
    fun theRulesHoldFromTheMomentTheLockIsAskedFor() = runTest {
        val g = guard()
        val mine = g.keeper.begin() // VaultManager.lock(), before the hooks
        assertEquals("nothing was sent", emptyList<String>(), ports.asked)
        assertTrue("a refresh that read 'unlocked' a moment ago does not open the app", g.lockedWhatever(unlocked = true))
        assertFalse(g.admits())
        assertTrue("the hook's own hang-up goes out", g.admits(LockTimeUse.CALL_END))
        assertFalse(g.takesEvents())
        ports.relay = true
        assertTrue(g.keeper.ask(mine))
        assertFalse(g.pending)
    }

    /** The relay lock: by the first attempt alone, recorded before it is sent, forgotten when the vault answers. */
    @Test
    fun theRelayLockIsSentOnceAndItsValidityIsOnThePhoneBeforeItGoesOut() = runTest {
        val g = guard()
        assertFalse(g.keeper.ask())
        assertEquals(listOf("relay", "member", "status"), ports.asked)
        assertEquals("until its exp and a margin", t0.plusSeconds(40).toEpochMilli(), store.record.relayLockUntilMs)
        ports.asked.clear()
        advanceTimeBy(3_600_001)
        assertEquals("a retry sends no relay lock", listOf("member", "status"), ports.asked)

        // Answered: confirmed, and nothing of it is on its way.
        ports.relay = true
        ports.asked.clear()
        assertTrue(g.keeper.ask())
        assertEquals("the vault's own answer is enough", listOf("relay"), ports.asked)
        assertEquals(LockRecord(), store.record)
    }

    /** Review L7 of #127: after a 429 the member API is not asked to lock again before its time; the status still is. */
    @Test
    fun aRateLimitedMemberApiIsNotAskedAgainBeforeItsTime() = runTest {
        val g = guard()
        ports.member = MemberLock.Limited(Duration.ofSeconds(120))
        assertFalse(g.attempt(first = false))
        assertEquals(listOf("member", "status"), ports.asked)
        ports.asked.clear()
        advanceTimeBy(119_000)
        assertFalse(g.attempt(first = false))
        assertEquals("no POST, the status read stays", listOf("status"), ports.asked)
        ports.unlocked = false
        assertTrue("and a status that says locked confirms meanwhile", g.attempt(first = false))
        ports.unlocked = true
        ports.asked.clear()
        advanceTimeBy(1_000)
        // A 429 without a time: a minute.
        ports.member = MemberLock.Limited(Duration.ZERO)
        assertFalse(g.attempt(first = false))
        assertEquals(listOf("member", "status"), ports.asked)
        ports.asked.clear()
        advanceTimeBy(59_000)
        assertFalse(g.attempt(first = false))
        assertEquals(listOf("status"), ports.asked)
        advanceTimeBy(1_000)
        assertFalse(g.attempt(first = false))
        assertEquals(listOf("status", "member", "status"), ports.asked)
    }

    /** After an answered request the status may take a moment to follow: read up to three times, a second apart. */
    @Test
    fun afterAnAnsweredRequestTheStatusIsGivenAMomentToFollow() = runTest {
        val g = guard()
        ports.member = MemberLock.Answered(done = true)
        val start = testScheduler.currentTime
        assertFalse(g.attempt(first = false))
        assertEquals(listOf("member", "status", "status", "status"), ports.asked)
        assertEquals(2_000L, testScheduler.currentTime - start)
        runCurrent()
    }
}
