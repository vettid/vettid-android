package com.vettid.core.data.vault

import com.vettid.core.altchan.MemberApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ANDROID-PLAN 0.1.54 "A vault being deleted": the bound, the re-reads, "still finishing", and the phase it holds. */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultDeletionTest {
    private val min = 60_000L

    @Test
    fun theBoundIsTenMinutesOrTheDeletesExpPlusTwo() {
        assertEquals(10 * min, VaultDeletion.untilMs(0, null))
        assertEquals(10 * min, VaultDeletion.untilMs(0, 5 * min)) // an earlier exp + 2 does not shorten it
        assertEquals(14 * min, VaultDeletion.untilMs(0, 12 * min)) // a later exp: exp + 2 minutes
    }

    @Test
    fun theReReadsComeAfter5And10And30SecondsThenEvery60() {
        val at = generateSequence(VaultDeletion.nextReadMs(0, 0)) { VaultDeletion.nextReadMs(0, it) }.take(6).toList()
        assertEquals(listOf(5_000L, 15_000L, 45_000L, 105_000L, 165_000L, 225_000L), at)
        // After a restart, the next one on the same schedule.
        assertEquals(45_000L, VaultDeletion.nextReadMs(0, 20_000))
    }

    @Test
    fun nothingReadsAsGoneAndA401OrNoVaultNeverErases() {
        assertEquals(DeletionCheck.UNCONFIRMED, VaultDeletion.ofStatus(vaultListed = false)) // `{vault:null}` is advisory
        assertEquals(DeletionCheck.LISTED, VaultDeletion.ofStatus(vaultListed = true))
        // a bare 401 (any kind) is not a definite answer
        for (k in FailureKind.values()) assertNotEquals(DeletionCheck.GONE, VaultDeletion.ofFailure(k))
        assertEquals(DeletionCheck.UNCONFIRMED, VaultDeletion.ofFailure(FailureKind.UNAUTHORIZED))
        assertEquals(DeletionCheck.UNCONFIRMED_CLOCK, VaultDeletion.ofFailure(FailureKind.CLOCK))
        assertEquals(DeletionCheck.UNREACHABLE, VaultDeletion.ofFailure(FailureKind.NETWORK))
        assertEquals(DeletionCheck.UNREACHABLE, VaultDeletion.ofFailure(FailureKind.SERVICE_PAUSED))
        // `{vault: null}` while the service is paused concludes nothing.
        assertEquals(DeletionCheck.UNREACHABLE, VaultDeletion.ofStatus(vaultListed = false, servicePaused = true))
        assertEquals(DeletionCheck.UNREACHABLE, VaultDeletion.ofStatus(vaultListed = true, servicePaused = true))
    }

    @Test
    fun readCheckNeverSaysGoneAndKeepsTheMappingOfEachAnswer() = runTest {
        suspend fun viaStatus(status: Int) =
            VaultDeletion.readCheck { throw VaultFailure(FailureKind.OTHER, cause = MemberApiException(status, "x")) }
        suspend fun fail(k: FailureKind, code: String? = null) = VaultDeletion.readCheck { throw VaultFailure(k, code) }
        assertEquals(DeletionCheck.UNCONFIRMED, fail(FailureKind.UNAUTHORIZED)) // a 401
        assertEquals(DeletionCheck.UNCONFIRMED_CLOCK, fail(FailureKind.CLOCK))
        // A 403 (kind OTHER, code forbidden, or a MemberApiException with status 403) is unconfirmed; 5xx and 429 are not.
        assertEquals(DeletionCheck.UNCONFIRMED, fail(FailureKind.OTHER, code = "forbidden"))
        assertEquals(DeletionCheck.UNCONFIRMED, viaStatus(403))
        assertEquals(DeletionCheck.UNREACHABLE, viaStatus(503))
        assertEquals(DeletionCheck.UNREACHABLE, viaStatus(500))
        assertEquals(DeletionCheck.UNREACHABLE, fail(FailureKind.RATE_LIMITED))
        assertEquals(DeletionCheck.UNREACHABLE, fail(FailureKind.NETWORK))
        assertEquals(DeletionCheck.UNREACHABLE, fail(FailureKind.SERVICE_PAUSED))
        assertEquals(DeletionCheck.UNREACHABLE, VaultDeletion.readCheck { throw IllegalStateException("x") })
        assertEquals(DeletionCheck.UNCONFIRMED, VaultDeletion.readCheck { VaultDeletion.StatusSeen(false, false) }) // {vault:null}
        assertEquals(DeletionCheck.LISTED, VaultDeletion.readCheck { VaultDeletion.StatusSeen(true, false) })
        var paused: Boolean? = null
        assertEquals(DeletionCheck.UNREACHABLE, VaultDeletion.readCheck({ paused = it }) { VaultDeletion.StatusSeen(false, true) })
        assertEquals(true, paused)
        for (k in FailureKind.values()) assertNotEquals(DeletionCheck.GONE, fail(k))
    }

    private fun gone(status: Int, code: String) =
        VaultFailure(VaultManager.memberFailure(MemberApiException(status, code)), code, cause = MemberApiException(status, code))

    @Test
    fun only410VaultDeletedOrAccountDeletedReadsAsGone() = runTest {
        suspend fun check(f: VaultFailure) = VaultDeletion.readCheck { throw f }
        assertEquals(DeletionCheck.GONE, check(gone(410, "vault_deleted")))
        assertEquals(DeletionCheck.GONE, check(gone(410, "account_deleted")))
        // Other 410s, the same codes under another status, and every other refusal are "could not tell".
        assertEquals(DeletionCheck.UNREACHABLE, check(gone(410, "release_unavailable")))
        assertEquals(DeletionCheck.UNREACHABLE, check(gone(410, "recovery_ended")))
        assertEquals(DeletionCheck.UNREACHABLE, check(gone(404, "vault_deleted")))
        assertEquals(DeletionCheck.UNREACHABLE, check(gone(500, "account_deleted")))
        assertEquals(DeletionCheck.UNCONFIRMED, check(gone(401, "unauthorized")))
        assertEquals(DeletionCheck.UNCONFIRMED, check(gone(403, "forbidden")))
        assertEquals(DeletionCheck.UNREACHABLE, check(gone(429, "rate_limited")))
        // The kind alone, without the error behind it, is not the signal.
        assertEquals(DeletionCheck.UNREACHABLE, check(VaultFailure(FailureKind.VAULT_DELETED, "vault_deleted")))
        // `{vault: null}` and a listed vault are not GONE either.
        assertEquals(DeletionCheck.UNCONFIRMED, VaultDeletion.readCheck { VaultDeletion.StatusSeen(false, false) })
        assertEquals(DeletionCheck.LISTED, VaultDeletion.readCheck { VaultDeletion.StatusSeen(true, false) })
    }

    @Test
    fun memberFailureMapsThe410CodesOnlyWith410() {
        assertEquals(FailureKind.VAULT_DELETED, VaultManager.memberFailure(MemberApiException(410, "vault_deleted")))
        assertEquals(FailureKind.VAULT_DELETED, VaultManager.memberFailure(MemberApiException(410, "account_deleted")))
        assertEquals(FailureKind.OTHER, VaultManager.memberFailure(MemberApiException(404, "vault_deleted")))
        assertEquals(FailureKind.RELEASE_ENDED, VaultManager.memberFailure(MemberApiException(410, "release_unavailable")))
    }

    @Test
    fun aGoneErasesOnlyWithTheVaultsWordAndAloneIsApiOnly() {
        assertEquals(DeletionCheck.GONE, VaultDeletion.resolve(DeletionCheck.GONE, vaultWord = true))
        assertEquals(DeletionCheck.API_ONLY, VaultDeletion.resolve(DeletionCheck.GONE, vaultWord = false))
        for (c in DeletionCheck.values().filter { it != DeletionCheck.GONE }) {
            assertEquals(c, VaultDeletion.resolve(c, vaultWord = true))
            assertEquals(c, VaultDeletion.resolve(c, vaultWord = false))
        }
    }

    @Test
    fun theApiOnlyBoundIsTenMinutesAfterTheFirst410AndNeverScaled() {
        assertEquals(1_000L + 10 * min, VaultDeletion.apiUntilMs(1_000L))
        val m = DeletedApiMarker("v", 5_000L, VaultDeletion.apiUntilMs(5_000L), "vault_deleted")
        assertEquals(DeletingMarker("v", 5_000L, 5_000L + 10 * min), m.watch())
        val json = kotlinx.serialization.json.Json
        assertEquals(m, json.decodeFromString<DeletedApiMarker>(json.encodeToString(DeletedApiMarker.serializer(), m)))
    }

    @Test
    fun whileA410StandsOnlyTheVaultsDeletedEventErasesAndReplacementProofsGoToTheHolder() {
        val u = HolderPolicy.DEVICE_UNLINKED
        assertTrue(VaultDeletion.erasesOnEvent(u, "vault_deleted", apiStanding = true))
        // No other event erases, whatever it is.
        for (r in listOf("transferred", "replaced", "other", null)) assertFalse(r.toString(), VaultDeletion.erasesOnEvent(u, r, true))
        for (t in listOf("vault.locking", "sync.event", "credential.alarm", "device.paired")) {
            assertFalse(t, VaultDeletion.erasesOnEvent(t, "vault_deleted", true))
            assertEquals(VaultDeletion.EventAction.DROP, VaultDeletion.eventAction(t, "vault_deleted", true))
        }
        // Without a standing 410 the ordinary handling applies (the filter does not act).
        assertEquals(null, VaultDeletion.eventAction(u, "vault_deleted", false))
        assertFalse(VaultDeletion.erasesOnEvent(u, "vault_deleted", false))
        // A replaced or transferred phone is handled as always, so it is not left holding keys.
        assertEquals(VaultDeletion.EventAction.HOLDER, VaultDeletion.eventAction(u, "transferred", true))
        assertEquals(VaultDeletion.EventAction.HOLDER, VaultDeletion.eventAction(u, "replaced", true))
    }

    @Test
    fun aSessionIsOpenedWhileDeletingOnlyForTheMailboxOfAStanding410() {
        assertTrue(VaultDeletion.allowsSession(deleting = false, mailboxOnly = false, apiStanding = false))
        assertFalse(VaultDeletion.allowsSession(deleting = true, mailboxOnly = false, apiStanding = true))
        assertFalse(VaultDeletion.allowsSession(deleting = true, mailboxOnly = true, apiStanding = false))
        assertTrue(VaultDeletion.allowsSession(deleting = true, mailboxOnly = true, apiStanding = true))
    }

    @Test
    fun a410StartsTheStateOnlyOnAPhoneWithAVaultAndNotTwice() {
        assertTrue(VaultDeletion.api410Applies(AppPhase.Unlocked, deleting = false))
        assertTrue(VaultDeletion.api410Applies(AppPhase.Starting, deleting = false))
        assertTrue(VaultDeletion.api410Applies(AppPhase.Setup(SetupStage.FINISHING), deleting = false))
        assertFalse(VaultDeletion.api410Applies(AppPhase.Setup(SetupStage.NEW_VAULT), deleting = false))
        assertFalse(VaultDeletion.api410Applies(AppPhase.SignedOut, deleting = false))
        assertFalse(VaultDeletion.api410Applies(AppPhase.Unlocked, deleting = true))
    }

    @Test
    fun aOpenOrPairedPhoneIsLockedOnA410AndTheLockIsPendingAndRetried() = runTest {
        assertTrue(VaultDeletion.locksOnApiDeleted(AppPhase.Unlocked))
        assertTrue(VaultDeletion.locksOnApiDeleted(AppPhase.Setup(SetupStage.FINISHING)))
        assertTrue(VaultDeletion.locksOnApiDeleted(AppPhase.Setup(SetupStage.NEEDS_CREDENTIAL)))
        assertFalse(VaultDeletion.locksOnApiDeleted(AppPhase.Locked))
        assertFalse(VaultDeletion.locksOnApiDeleted(AppPhase.Setup(SetupStage.NEW_VAULT)))
        // The lock the manager begins (unbound without a current unlock) stays pending while the vault cannot be reached.
        val attempts = mutableListOf<String?>()
        val store = object : LockRecordStore {
            var r = LockRecord()
            override fun load() = r
            override fun save(record: LockRecord) { r = record }
        }
        val k = LockKeeper(backgroundScope, store, object : LockAttempt {
            override suspend fun tryLock(first: Boolean, unlockId: String?): LockOutcome {
                attempts += unlockId
                return LockOutcome.UNCONFIRMED
            }
        })
        val mine = k.begin(null)
        assertTrue(k.pending)
        assertTrue(store.r.pendingSinceMs != null)
        k.ask(mine)
        assertTrue(k.pending)
        assertEquals(listOf<String?>(null), attempts)
    }

    @Test
    fun onlyAListedStatusClearsTheApiMarker() {
        for (c in DeletionCheck.values()) assertEquals(c == DeletionCheck.LISTED, VaultDeletion.clearsApiMarker(c))
    }

    @Test
    fun anApiOnlyStateKeepsReadingOnItsScheduleAndNeverErases() = runTest {
        // With a 410 alone the manager's read answers API_ONLY: the watch keeps its timer and onGone never runs.
        val phase = PhaseHolder(AppPhase.Unlocked)
        phase.deleting(AppPhase.Deleting(apiOnly = true))
        var gone = 0
        val reads = mutableListOf<Long>()
        val w = DeletionWatch(backgroundScope, { currentTime }, read = {
            reads += currentTime
            VaultDeletion.resolve(VaultDeletion.readCheck { throw gone(410, "vault_deleted") }, vaultWord = false)
        }, onGone = { gone++ }, onSlow = {})
        w.start(DeletingMarker("v", 0, 10 * min), readNow = false)
        advanceTimeBy(106_000)
        assertEquals(listOf(5_000L, 15_000L, 45_000L, 105_000L), reads)
        assertEquals(0, gone)
        assertTrue(phase.deleting)
        // With the vault's word the same 410 is GONE: the erase runs once.
        val w2 = DeletionWatch(backgroundScope, { currentTime }, read = {
            VaultDeletion.resolve(VaultDeletion.readCheck { throw gone(410, "account_deleted") }, vaultWord = true)
        }, onGone = { gone++ }, onSlow = {})
        assertEquals(DeletionCheck.GONE, w2.check())
        assertEquals(1, gone)
    }

    @Test
    fun readCheckThroughAWatchLeavesThePhaseAndTheMarkerAlone() = runTest {
        // A 401 and {vault:null} through the watch: onGone never runs, so the "being deleted" hold stays.
        val phase = PhaseHolder(AppPhase.Unlocked)
        phase.deleting(AppPhase.Deleting(slow = true))
        var gone = 0
        val answers = ArrayDeque(listOf<suspend () -> VaultDeletion.StatusSeen>(
            { throw VaultFailure(FailureKind.UNAUTHORIZED) }, { VaultDeletion.StatusSeen(false, false) },
        ))
        val w = DeletionWatch(backgroundScope, { currentTime }, read = { VaultDeletion.readCheck { answers.removeFirst()() } },
            onGone = { gone++; phase.endDeleting(AppPhase.SignedOut) }, onSlow = {})
        assertEquals(DeletionCheck.UNCONFIRMED, w.check())
        assertEquals(DeletionCheck.UNCONFIRMED, w.check())
        assertEquals(0, gone)
        assertTrue(phase.deleting)
        assertEquals(AppPhase.Deleting(slow = true), phase.value)
    }

    @Test
    fun aClockMovedBackIsPastTheBoundWithinATolerance() = runTest {
        assertTrue(VaultDeletion.pastBound(1_000_000L, 1_000_000L + 10 * min, 1_000_000L - 24 * 60 * min))
        assertFalse(VaultDeletion.pastBound(0, 10 * min, 5 * min))
        // A small step back (an NTP correction) is not past the bound; a larger one is.
        assertFalse(VaultDeletion.pastBound(1_000_000L, 1_000_000L + 10 * min, 1_000_000L - 59_000L))
        assertTrue(VaultDeletion.pastBound(1_000_000L, 1_000_000L + 10 * min, 1_000_000L - 61_000L))
        assertTrue(VaultDeletion.pastBound(0, 10 * min, 10 * min))
        // A watch started with the event "in the future" is slow at once and sets no day-long wait.
        val r = Reads(ArrayDeque())
        watch(r).start(DeletingMarker("v", 24 * 60 * min, 24 * 60 * min + 10 * min), readNow = false)
        runCurrent()
        assertEquals(1, r.slow)
        // And a clock that moves back after the start: the wait is at most 60 s, so the bound is seen again.
        val r2 = Reads(ArrayDeque())
        var skew = 0L
        val w = DeletionWatch(
            backgroundScope, { currentTime - skew }, read = { r2.at += currentTime; DeletionCheck.LISTED },
            onGone = {}, onSlow = { r2.slow++ },
        )
        advanceTimeBy(20_000)
        w.start(DeletingMarker("v", 0, 10 * min), readNow = false)
        skew = 24 * 60 * min // the clock jumps back a day
        advanceTimeBy(60_001 + 5_000)
        assertEquals(1, r2.slow)
    }

    @Test
    fun aDroppedMarkerWriteIsTriedAgain() {
        var calls = 0
        assertTrue(VaultDeletion.saveMarker { ++calls == 3 })
        assertEquals(3, calls)
        calls = 0
        assertTrue(VaultDeletion.saveMarker { calls++; if (calls == 1) throw java.io.IOException("persist") else true })
        assertEquals(2, calls)
        calls = 0
        assertFalse(VaultDeletion.saveMarker { calls++; false })
        assertEquals(3, calls)
    }

    /**
     * MEMBER-API 2.10.0 / VAULT-MESSAGING 0.51.0: the status never says "deleting"; any state but `unlocked`, one the app
     * does not know included, is locked, and never "being deleted" (that comes only from `device.unlinked{vault_deleted}`).
     */
    @Test
    fun anUnknownStatusStateIsLocked() {
        assertTrue(VaultDeletion.statusUnlocked("unlocked"))
        for (s in listOf("locked", "deleting", "held", "", null)) assertFalse(s.toString(), VaultDeletion.statusUnlocked(s))
    }

    private class Reads(val answers: ArrayDeque<DeletionCheck>) {
        val at = mutableListOf<Long>()
        var gone = 0
        var slow = 0
    }

    private fun TestScope.watch(r: Reads) = DeletionWatch(
        backgroundScope,
        nowMs = { currentTime },
        read = {
            r.at += currentTime
            r.answers.removeFirstOrNull() ?: DeletionCheck.LISTED
        },
        onGone = { r.gone++ },
        onSlow = { r.slow++ },
    )

    @Test
    fun listedUnlockedThenLockedThenGoneMovesOnlyAtGone() = runTest {
        // The status still lists the vault (unlocked while the lease runs, then locked), then reads `{vault: null}`.
        val r = Reads(ArrayDeque(listOf(DeletionCheck.LISTED, DeletionCheck.LISTED, DeletionCheck.GONE)))
        watch(r).start(DeletingMarker("v", 0, 10 * min), readNow = false)
        runCurrent()
        assertTrue(r.at.isEmpty()) // nothing read at the event itself
        advanceTimeBy(15_001)
        assertEquals(0, r.gone)
        advanceTimeBy(30_000)
        assertEquals(listOf(5_000L, 15_000L, 45_000L), r.at)
        assertEquals(1, r.gone)
        advanceTimeBy(20 * min)
        assertEquals(3, r.at.size) // nothing more after gone
        assertEquals(0, r.slow)
    }

    @Test
    fun stillListedAtTheBoundIsStillFinishingWithNoTimerAfter() = runTest {
        val r = Reads(ArrayDeque())
        watch(r).start(DeletingMarker("v", 0, 10 * min), readNow = false)
        advanceTimeBy(10 * min + 1)
        assertEquals(1, r.slow)
        assertEquals(10 * min, r.at.last()) // read at the bound itself
        val n = r.at.size
        advanceTimeBy(60 * min)
        assertEquals(n, r.at.size)
        assertEquals(1, r.slow)
    }

    @Test
    fun theBoundFromALaterDeleteExp() = runTest {
        val r = Reads(ArrayDeque())
        watch(r).start(DeletingMarker("v", 0, VaultDeletion.untilMs(0, 12 * min)), readNow = false)
        advanceTimeBy(10 * min + 1)
        assertEquals(0, r.slow)
        advanceTimeBy(4 * min)
        assertEquals(1, r.slow)
    }

    @Test
    fun aStartReadsAtOnceAndGoesOnOnTheSameSchedule() = runTest {
        advanceTimeBy(20_000) // the process restarted 20 s after the event
        val r = Reads(ArrayDeque())
        watch(r).start(DeletingMarker("v", 0, 10 * min), readNow = true)
        runCurrent()
        assertEquals(listOf(20_000L), r.at)
        advanceTimeBy(25_001)
        assertEquals(listOf(20_000L, 45_000L), r.at)
    }

    @Test
    fun aStartAfterTheBoundReadsOnceAndIsStillFinishing() = runTest {
        advanceTimeBy(30 * min)
        val r = Reads(ArrayDeque())
        val w = watch(r)
        w.start(DeletingMarker("v", 0, 10 * min), readNow = true)
        runCurrent()
        assertEquals(1, r.at.size)
        assertEquals(1, r.slow)
        advanceTimeBy(60 * min)
        assertEquals(1, r.at.size)
        // Check again, and the vault gone later (also at a start or a return to the foreground: the same one read).
        r.answers += DeletionCheck.GONE
        val c = w.check()
        assertEquals(DeletionCheck.GONE, c)
        assertEquals(1, r.gone)
    }

    @Test
    fun aSecondCheckWaitsForTheFirst() = runTest {
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val w = DeletionWatch(backgroundScope, { currentTime }, read = {
            reads++
            gate.await()
            DeletionCheck.UNREACHABLE
        }, onGone = {}, onSlow = {})
        val a = async { w.check() }
        val b = async { w.check() }
        runCurrent()
        gate.complete(Unit)
        assertEquals(DeletionCheck.UNREACHABLE, a.await())
        assertEquals(DeletionCheck.UNREACHABLE, b.await())
        assertEquals(1, reads)
    }

    @Test
    fun beingDeletedHoldsThePhaseUntilItEnds() {
        val p = PhaseHolder(AppPhase.Unlocked)
        p.deleting(AppPhase.Deleting())
        // An unlock, a lock or a refresh that was on its way publishes nothing over it.
        p.value = AppPhase.Unlocked
        p.value = AppPhase.Locked
        assertEquals(AppPhase.Deleting(), p.value)
        assertTrue(p.deleting)
        p.deleting(AppPhase.Deleting(slow = true))
        assertEquals(AppPhase.Deleting(slow = true), p.value)
        p.endDeleting(AppPhase.SignedOut)
        assertEquals(AppPhase.SignedOut, p.value)
        assertFalse(p.deleting)
        p.value = AppPhase.Locked
        assertEquals(AppPhase.Locked, p.value)
    }
}
