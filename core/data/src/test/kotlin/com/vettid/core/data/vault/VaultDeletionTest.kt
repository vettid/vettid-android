package com.vettid.core.data.vault

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
    fun goneOnlyForNoVaultOrA401() {
        assertEquals(DeletionCheck.GONE, VaultDeletion.ofStatus(vaultListed = false))
        assertEquals(DeletionCheck.LISTED, VaultDeletion.ofStatus(vaultListed = true))
        assertEquals(DeletionCheck.GONE, VaultDeletion.ofFailure(FailureKind.UNAUTHORIZED))
        assertEquals(DeletionCheck.UNREACHABLE, VaultDeletion.ofFailure(FailureKind.NETWORK))
        assertEquals(DeletionCheck.UNREACHABLE, VaultDeletion.ofFailure(FailureKind.CLOCK)) // a 401 for the clock is not the row gone
        assertEquals(DeletionCheck.UNREACHABLE, VaultDeletion.ofFailure(FailureKind.SERVICE_PAUSED))
        // `{vault: null}` while the service is paused concludes nothing.
        assertEquals(DeletionCheck.UNREACHABLE, VaultDeletion.ofStatus(vaultListed = false, servicePaused = true))
        assertEquals(DeletionCheck.UNREACHABLE, VaultDeletion.ofStatus(vaultListed = true, servicePaused = true))
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
