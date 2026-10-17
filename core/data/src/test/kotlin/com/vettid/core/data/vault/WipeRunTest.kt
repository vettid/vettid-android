package com.vettid.core.data.vault

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A wipe of this phone fails secure: the session goes first, and only a complete erase says SignedOut. */
class WipeRunTest {
    private class Fixture(var markFails: Int = 0, val eraseResult: Boolean = true, val eraseThrows: Boolean = false) {
        val record = AccountRecord(LocalAccount(userGuid = "u", vaultId = "v"), readable = true, persist = {})
        val steps = mutableListOf<String>()
        var phase: AppPhase? = null
        var marks = 0
        val run = WipeRun(
            record,
            detach = { steps += "detach" },
            mark = {
                marks++
                if (markFails-- > 0) throw java.io.IOException("no space")
                steps += "mark"
            },
            erase = {
                steps += "erase"
                if (eraseThrows) error("erase")
                eraseResult
            },
            onEnd = { phase = it },
            retryMs = 1,
        )
    }

    @Test
    fun aCompleteWipeSaysSignedOut() = runTest {
        val f = Fixture()
        f.run.run()
        assertEquals(listOf("detach", "mark", "erase"), f.steps)
        assertEquals(AppPhase.SignedOut, f.phase)
        assertEquals(LocalAccount(), f.record.value)
    }

    @Test
    fun aMarkerThatCannotBeWrittenStopsTheWipeAndSaysSo() = runTest {
        val f = Fixture(markFails = Int.MAX_VALUE)
        f.run.run()
        // The session went first; nothing was erased; the phase is not a clean welcome screen.
        assertEquals(listOf("detach"), f.steps)
        assertEquals(WipeRun.MARK_ATTEMPTS, f.marks)
        assertEquals(WipeRun.failedPhase, f.phase)
        // The guard is open again (on the fresh record), so that "Try again" can run the wipe.
        assertTrue(f.record.update(f.record.recordGeneration) { it.copy(movedIn = true) })
    }

    @Test
    fun aMarkerIsTriedAgain() = runTest {
        val f = Fixture(markFails = 1)
        f.run.run()
        assertEquals(2, f.marks)
        assertEquals(AppPhase.SignedOut, f.phase)
    }

    @Test
    fun anEraseThatLeavesSomethingSaysSo() = runTest {
        val f = Fixture(eraseResult = false)
        f.run.run()
        assertEquals(WipeRun.failedPhase, f.phase)
    }

    @Test
    fun anEraseThatThrowsSaysSo() = runTest {
        val f = Fixture(eraseThrows = true)
        assertTrue(runCatching { f.run.run() }.isFailure)
        assertEquals(WipeRun.failedPhase, f.phase)
        assertEquals(LocalAccount(), f.record.value)
    }

    @Test
    fun anEnrolmentFinishesAPendingWipeFirst() = runTest {
        var pending = true
        var finished = 0
        WipeRun.beforeEnrolment(pending = { pending }) {
            finished++
            pending = false
        }
        assertEquals(1, finished)
    }

    @Test
    fun anEnrolmentIsRefusedWhileAWipeStaysUnfinished() = runTest {
        var tried = false
        try {
            WipeRun.beforeEnrolment(pending = { true }) {
                tried = true
                error("still failing")
            }
            fail("an enrolment was let through")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.ERASE_FAILED, e.kind)
            assertEquals(WipeRun.CODE_WIPE_PENDING, e.code)
        }
        assertTrue(tried)
    }

    @Test
    fun anEnrolmentWithNothingPendingGoesOn() = runTest {
        var finished = false
        WipeRun.beforeEnrolment(pending = { false }) { finished = true }
        assertFalse(finished)
    }
}
