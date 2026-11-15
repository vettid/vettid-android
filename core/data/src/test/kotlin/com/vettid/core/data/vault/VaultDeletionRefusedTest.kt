package com.vettid.core.data.vault

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A refused "gone", and the vault's own word arriving with no "being deleted" up. */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultDeletionRefusedTest {
    private val min = 60_000L

    // A "gone" the app refused (a 410-only marker came meanwhile, or the attempt ended) is not reported as GONE: the watch
    // keeps its timer, "Check again" concludes nothing, and "still finishing" (with Remove) still comes at the bound.
    @Test
    fun aRefusedGoneIsNotGone() = runTest {
        var reads = 0
        var asked = 0
        val said = mutableListOf<DeletingMarker>()
        val m = DeletingMarker("v", 0, 10 * min)
        val w = DeletionWatch(
            backgroundScope, { currentTime }, read = { reads++; DeletionCheck.GONE }, onGone = { asked++; false },
            onSlow = { said += it },
        )
        w.start(m, readNow = false)
        assertEquals(DeletionCheck.UNCONFIRMED, w.check())
        assertEquals(1, asked)
        // The timer still runs past a refused GONE (reads at 5, 15, 45 and 105 s): a GONE taken as gone would stop it at
        // the first timed read.
        advanceTimeBy(5 * min)
        runCurrent()
        assertTrue("the watch kept reading: $reads", reads >= 5)
        assertEquals(reads, asked)
        assertTrue("no still finishing before the bound", said.isEmpty())
        // The bound's timer was not taken away by the refusals.
        advanceTimeBy(5 * min + 1)
        runCurrent()
        assertEquals(listOf(m), said)
        w.stop()
    }

    // An accepted GONE is gone: no "still finishing" after it.
    @Test
    fun anAcceptedGoneEndsStillFinishing() = runTest {
        val said = mutableListOf<DeletingMarker>()
        val w = DeletionWatch(
            backgroundScope, { currentTime }, read = { DeletionCheck.GONE }, onGone = { true }, onSlow = { said += it },
        )
        w.start(DeletingMarker("v", 0, 10 * min), readNow = false)
        assertEquals(DeletionCheck.GONE, w.check())
        advanceTimeBy(11 * min)
        runCurrent()
        assertTrue(said.isEmpty())
    }

    // The vault's word with no "being deleted" up starts it only while this phone still has its vault and its record is
    // open; once "being deleted" is up it erases only with a 410 marker standing.
    @Test
    fun theVaultsWordStartsOnlyWithAVault() {
        fun starts(p: AppPhase, id: String = "v", closed: Boolean = false) = VaultDeletion.vaultWordStarts(p, id, closed)
        assertTrue(starts(AppPhase.Locked))
        assertTrue(starts(AppPhase.Unlocked))
        assertTrue(starts(AppPhase.Starting))
        assertTrue("as startDeleting, in any phase with a vault", starts(AppPhase.Setup(SetupStage.NEEDS_CREDENTIAL)))
        assertFalse("no vault id", starts(AppPhase.Locked, id = ""))
        assertFalse("after a wipe", starts(AppPhase.SignedOut))
        assertFalse("a wipe runs (still Locked)", starts(AppPhase.Locked, closed = true))
        assertFalse("an erase that failed", starts(AppPhase.Unreachable(FailureKind.ERASE_FAILED), closed = true))
        assertTrue("the 410 stands: erase", VaultDeletion.vaultWordErases(apiHeld = true))
        assertFalse("this phone's own deletion is up: a second word does nothing", VaultDeletion.vaultWordErases(apiHeld = false))
    }

    // While the app decides a "gone" (onGone waits on the deletion's lock), a bound that falls shows no "still finishing":
    // an erase takes it away at once; a refusal says it then.
    @Test
    fun theBoundWaitsWhileAGoneIsDecided() = runTest {
        for (erases in listOf(true, false)) {
            val said = mutableListOf<DeletingMarker>()
            val gate = CompletableDeferred<Unit>()
            val base = currentTime // each pass on its own clock
            val m = DeletingMarker("v", base, base + 10 * min)
            val w = DeletionWatch(
                backgroundScope, { currentTime }, read = {
                    if (currentTime - base >= 9 * min) DeletionCheck.GONE else DeletionCheck.LISTED
                }, onGone = { gate.await(); erases }, onSlow = { said += it },
            )
            w.start(m, readNow = false)
            advanceTimeBy(9 * min)
            val out = async { w.check() }
            runCurrent()
            advanceTimeBy(2 * min) // the bound falls while onGone waits
            runCurrent()
            assertTrue("no still finishing while deciding ($erases)", said.isEmpty())
            gate.complete(Unit)
            assertEquals(if (erases) DeletionCheck.GONE else DeletionCheck.UNCONFIRMED, out.await())
            runCurrent()
            assertEquals(if (erases) emptyList() else listOf(m), said)
            w.stop()
        }
    }
}
