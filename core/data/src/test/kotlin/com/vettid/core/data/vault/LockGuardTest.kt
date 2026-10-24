package com.vettid.core.data.vault

import com.vettid.core.altchan.Slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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

        /** When set, the relay lock waits for it: the vault's answer is on its way. */
        var relayGate: CompletableDeferred<Unit>? = null

        /** What the relay and the member API were asked with: the unlock each named. */
        val named = mutableListOf<String?>()

        /** When set, the relay's answer instead of [relay]. */
        var relayAnswer: RelayLock? = null

        override suspend fun relayLock(unlockId: String?): RelayLock {
            asked += "relay"
            named += unlockId
            relayGate?.await()
            return relayAnswer ?: if (relay) RelayLock.LOCKED else RelayLock.NO_ANSWER
        }

        override suspend fun memberLock(unlockId: String?): MemberLock = member.also {
            asked += "member"
            named += unlockId
        }

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
            assertEquals("$answer, and the status still says unlocked", LockOutcome.UNCONFIRMED, g.attempt(first = false))
        }
        // The enclave answers "done" whatever became of its lock: only the status decides.
        ports.member = MemberLock.Answered(done = true)
        ports.unlocked = null
        assertEquals("a status that could not be read confirms nothing", LockOutcome.UNCONFIRMED, g.attempt(first = false))
        ports.unlocked = false
        assertEquals(LockOutcome.LOCKED, g.attempt(first = false))
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

    /**
     * Confirmation pass C1 of #127: a lock asked for while an unlock is on its way. The unlock succeeds; the lock is
     * the newer wish and stays pending; so the phase after that unlock is Locked (the manager asks exactly this),
     * nothing is admitted, and the lock's attempt follows and confirms.
     */
    @Test
    fun anUnlockThatALockOvertookDoesNotOpenThisPhone() = runTest {
        val g = guard()
        val unlockMayFinish = CompletableDeferred<Unit>()
        val unlock = async {
            g.keeper.unlocking(opened = { it }) {
                unlockMayFinish.await()
                true
            }
        }
        runCurrent()
        val mine = g.keeper.begin() // the member locks while the unlock is on its way
        ports.relay = true
        val answer = CompletableDeferred<Unit>()
        ports.relayGate = answer
        val asking = async { g.keeper.ask(mine) }
        runCurrent()
        assertEquals("the lock waits for the unlock", emptyList<String>(), ports.asked)
        unlockMayFinish.complete(Unit)
        assertTrue("the unlock itself succeeded", unlock.await())
        // What VaultManager.unlockStep asks before it publishes a phase: the status says unlocked, a lock is pending.
        assertTrue(g.lockedWhatever(unlocked = true))
        assertFalse(g.admits())
        assertFalse(g.takesEvents())
        answer.complete(Unit)
        assertTrue(asking.await())
        assertEquals("the lock went out after the unlock", listOf("relay"), ports.asked)
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
        assertEquals(LockOutcome.UNCONFIRMED, g.attempt(first = false))
        assertEquals(listOf("member", "status"), ports.asked)
        ports.asked.clear()
        advanceTimeBy(119_000)
        assertEquals(LockOutcome.UNCONFIRMED, g.attempt(first = false))
        assertEquals("no POST, the status read stays", listOf("status"), ports.asked)
        ports.unlocked = false
        assertEquals("and a status that says locked confirms meanwhile", LockOutcome.LOCKED, g.attempt(first = false))
        ports.unlocked = true
        ports.asked.clear()
        advanceTimeBy(1_000)
        // A 429 without a time: a minute.
        ports.member = MemberLock.Limited(Duration.ZERO)
        assertEquals(LockOutcome.UNCONFIRMED, g.attempt(first = false))
        assertEquals(listOf("member", "status"), ports.asked)
        ports.asked.clear()
        advanceTimeBy(59_000)
        assertEquals(LockOutcome.UNCONFIRMED, g.attempt(first = false))
        assertEquals(listOf("status"), ports.asked)
        advanceTimeBy(1_000)
        assertEquals(LockOutcome.UNCONFIRMED, g.attempt(first = false))
        assertEquals(listOf("status", "member", "status"), ports.asked)
    }

    /** After an answered request the status may take a moment to follow: read up to three times, a second apart. */
    @Test
    fun afterAnAnsweredRequestTheStatusIsGivenAMomentToFollow() = runTest {
        val g = guard()
        ports.member = MemberLock.Answered(done = true)
        val start = testScheduler.currentTime
        assertEquals(LockOutcome.UNCONFIRMED, g.attempt(first = false))
        assertEquals(listOf("member", "status", "status", "status"), ports.asked)
        assertEquals(2_000L, testScheduler.currentTime - start)
        runCurrent()
    }

    // --- Release L (VAULT-MESSAGING 0.29.0 §10.2 "Locks", §8.6, §11.5; VAULT-LOCK §3.6, §3.7; ANDROID-PLAN §4 part 2) ---

    private val id = "0123456789abcdef0123456789abcdef"

    /** A bound lock names its unlock over the relay and through the member API, and leaves no wait before an unlock. */
    @Test
    fun aBoundLockNamesItsUnlockAndIsSentOverTheRelayAtEveryAttempt() = runTest {
        val g = guard()
        assertFalse(g.keeper.ask(g.keeper.begin(id)))
        assertEquals(listOf("relay", "member", "status"), ports.asked)
        assertEquals(listOf(id, id), ports.named)
        assertEquals("bound: nothing to wait out before an unlock", LockRecord(t0.toEpochMilli(), null, id), store.record)
        ports.asked.clear()
        advanceTimeBy(3_600_001)
        assertEquals("a retry sends the relay lock again (a late copy is stale_lock)", listOf("relay", "member", "status"), ports.asked)
        var sentAt = -1L
        g.keeper.unlocking(opened = { it }) { true.also { sentAt = testScheduler.currentTime } }
        assertEquals("no wait before the unlock", 3_600_001L, sentAt)
        assertEquals(LockRecord(), store.record)
    }

    /** The slot's `locked` and `not_running` confirm a bound lock; a `done` without a code still does not. */
    @Test
    fun aBoundLocksSlotCodesConfirmIt() = runTest {
        val g = guard()
        for (code in listOf(Slot.CODE_LOCKED, Slot.CODE_NOT_RUNNING)) {
            ports.asked.clear()
            ports.member = MemberLock.Answered(done = true, code = code)
            assertEquals(code, LockOutcome.LOCKED, g.attempt(first = false, unlockId = id))
            assertEquals("the code decides, no status read", listOf("relay", "member"), ports.asked)
        }
        ports.member = MemberLock.Answered(done = true)
        assertEquals("a done from a release before L proves nothing", LockOutcome.UNCONFIRMED, g.attempt(first = false, unlockId = id))
        ports.member = MemberLock.Answered(done = false, code = Slot.CODE_LOCKED)
        assertEquals("a code beside anything but done is not read", LockOutcome.UNCONFIRMED, g.attempt(first = false, unlockId = id))
    }

    /**
     * Gated: a lock without the unlock's name (a vault before L, S21/S22; or a phone that does not hold the value,
     * VAULT-LOCK Q3) is #127's lock exactly: the relay once, its wait before the next unlock, the slot's codes not read.
     */
    @Test
    fun withoutTheUnlockIdTheLockIsMadeAsBeforeL() = runTest {
        val g = guard()
        ports.member = MemberLock.Answered(done = true, code = Slot.CODE_LOCKED)
        assertFalse(g.keeper.ask(g.keeper.begin(null)))
        assertEquals(listOf("relay", "member", "status", "status", "status"), ports.asked)
        assertEquals("nothing named: an unconditional API lock", listOf<String?>(null, null), ports.named)
        assertEquals(LockRecord(t0.toEpochMilli(), t0.plusSeconds(40).toEpochMilli()), store.record)
        assertTrue("the host's code is not taken for a lock as before L", g.pending)
        // A stale_lock to an unbound lock is not the vault's word on it: still pending.
        ports.relayAnswer = RelayLock.STALE
        ports.member = MemberLock.Answered(done = true, code = Slot.CODE_STALE)
        assertEquals(LockOutcome.UNCONFIRMED, g.attempt(first = true, unlockId = null))
        ports.asked.clear()
        advanceTimeBy(3_600_001)
        assertEquals("a retry sends no relay lock", "member", ports.asked.first())
    }

    /** #127's wait before an unlock stays for a lock made as before L: the relay lock's exp and a margin. */
    @Test
    fun anUnlockAfterAnUnboundLockWaitsOutItsRelayLock() = runTest {
        val g = guard()
        assertFalse(g.keeper.ask(g.keeper.begin(null)))
        var sentAt = -1L
        g.keeper.unlocking(opened = { it }) { true.also { sentAt = testScheduler.currentTime } }
        assertEquals(40_000L, sentAt)
    }

    /**
     * `stale_lock` over the relay: the vault was opened again since. The lock is over and not repeated, it is not
     * taken as locked, the member is told, and this phone stays locked until an unlock here.
     */
    @Test
    fun aStaleLockIsNotTakenAsLocked() = runTest {
        val g = guard()
        ports.relayAnswer = RelayLock.STALE
        assertFalse("not locked", g.keeper.ask(g.keeper.begin(id)))
        assertEquals("the status is read to say what is true now", listOf("relay", "status"), ports.asked)
        assertEquals(LockNotice.OpenedAgain, g.keeper.notice.value)
        assertFalse(g.pending)
        assertEquals("nothing is tried again; the phone is held locked, on the phone too", LockRecord(held = true), store.record)
        ports.asked.clear()
        advanceTimeBy(3_600_001)
        assertEquals("not repeated", emptyList<String>(), ports.asked)
        // This phone is locked all the same, whatever the status says, also once the notice was read.
        assertTrue(g.lockedWhatever(unlocked = true))
        assertFalse(g.admits())
        assertFalse(g.takesEvents())
        assertTrue(g.keeper.openedAgain)
        g.keeper.dismissConfirmed()
        assertEquals(LockNotice.None, g.keeper.notice.value)
        assertTrue("dismissing the notice does not open this phone", g.lockedWhatever(unlocked = true))
        g.keeper.unlocking(opened = { it }) { true }
        assertFalse("an unlock here does", g.lockedWhatever(unlocked = true))
        assertEquals(LockRecord(), store.record)
        assertTrue(g.admits())
        assertFalse(g.keeper.openedAgain)
    }

    /** The slot's `stale` does the same; and a stale answer after which the status says locked confirms the lock. */
    @Test
    fun aStaleSlotEndsTheLockAndTheStatusSaysWhatIsTrueNow() = runTest {
        val g = guard()
        ports.member = MemberLock.Answered(done = true, code = Slot.CODE_STALE)
        assertEquals(LockOutcome.OPENED_AGAIN, g.attempt(first = false, unlockId = id))
        ports.unlocked = false
        assertEquals("locked since: confirmed", LockOutcome.LOCKED, g.attempt(first = false, unlockId = id))
        ports.unlocked = null
        assertEquals("unknown: not known locked", LockOutcome.OPENED_AGAIN, g.attempt(first = false, unlockId = id))
        ports.relayAnswer = RelayLock.STALE
        ports.unlocked = false
        assertTrue("stale, then the status says locked: confirmed", g.keeper.ask(g.keeper.begin(id)))
        assertEquals(LockNotice.None, g.keeper.notice.value)
        assertFalse(g.lockedWhatever(unlocked = false))
    }
}
