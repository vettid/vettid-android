package com.vettid.core.data.vault

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A wipe of this phone fails secure: the session goes first and again last, and only a complete erase says SignedOut. */
class WipeRunTest {
    private class Fixture(
        var markFails: Int = 0,
        val eraseResult: Boolean = true,
        val eraseThrows: Boolean = false,
        val abandonThrows: Boolean = false,
    ) {
        val record = AccountRecord(LocalAccount(userGuid = "u", vaultId = "v"), readable = true, persist = {})
        val steps = mutableListOf<String>()
        var phase: AppPhase? = null
        var marks = 0

        /** What a session asked for at each step got: refused, or opened. */
        val sessions = mutableListOf<String>()

        private fun askSession() {
            sessions += try {
                SessionGate.refuseWhileClosed(record)
                "opened"
            } catch (e: VaultFailure) {
                "refused ${e.kind}"
            }
        }

        val run = WipeRun(
            record,
            abandonLock = {
                steps += "abandon"
                if (abandonThrows) error("abandon")
            },
            detach = {
                steps += "detach"
                askSession()
            },
            mark = {
                marks++
                askSession()
                if (markFails-- > 0) throw java.io.IOException("no space")
                steps += "mark"
            },
            erase = {
                steps += "erase"
                askSession()
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
        assertEquals(listOf("abandon", "detach", "mark", "erase", "abandon", "detach"), f.steps)
        assertEquals(AppPhase.SignedOut, f.phase)
        assertEquals(LocalAccount(), f.record.value)
        assertFalse(f.record.closed)
    }

    @Test
    fun noSessionOpensWhileTheWipeRuns() = runTest {
        val f = Fixture()
        f.run.run()
        // A lock retry, the owner check or a refresh during the wipe: refused at every step.
        assertEquals(List(4) { "refused ${FailureKind.BUSY}" }, f.sessions)
    }

    @Test
    fun theSessionIsDetachedAgainAtTheEndWhateverHappens() = runTest {
        val f = Fixture(eraseThrows = true)
        assertTrue(runCatching { f.run.run() }.isFailure)
        assertEquals("detach", f.steps.last())
    }

    @Test
    fun aMarkerThatCannotBeWrittenStopsTheWipeAndSaysSo() = runTest {
        val f = Fixture(markFails = Int.MAX_VALUE)
        f.run.run()
        // The session went first (and again last); nothing was erased; the phase is not a clean welcome screen.
        assertEquals(listOf("abandon", "detach", "abandon", "detach"), f.steps)
        assertEquals(WipeRun.MARK_ATTEMPTS, f.marks)
        assertEquals(WipeRun.failedPhase, f.phase)
    }

    @Test
    fun theRecordStaysShutAfterAWipeThatDidNotFinish() = runTest {
        val f = Fixture(markFails = Int.MAX_VALUE)
        f.run.run()
        // The old record was never erased: nothing is written or reset over it, and no session opens.
        assertTrue(f.record.eraseFailed)
        assertFalse(f.record.update(RecordStore.UNBOUND) { it.copy(movedIn = true) })
        assertFalse(f.record.reset(LocalAccount()))
        assertEquals(LocalAccount(userGuid = "u", vaultId = "v"), f.record.value)
        try {
            SessionGate.refuseWhileClosed(f.record)
            fail("a session opened after a wipe that did not finish")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.ERASE_FAILED, e.kind)
        }
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
        assertTrue(f.record.eraseFailed)
    }

    @Test
    fun anEraseThatThrowsSaysSo() = runTest {
        val f = Fixture(eraseThrows = true)
        assertTrue(runCatching { f.run.run() }.isFailure)
        assertEquals(WipeRun.failedPhase, f.phase)
        assertTrue(f.record.eraseFailed)
    }

    @Test
    fun aRetriedWipeThatCompletesOpensTheRecord() = runTest {
        val f = Fixture(markFails = WipeRun.MARK_ATTEMPTS)
        f.run.run()
        assertTrue(f.record.eraseFailed)
        // "Try again": the marker can be written now.
        f.run.run()
        assertEquals(AppPhase.SignedOut, f.phase)
        assertFalse(f.record.closed)
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

    @Test
    fun theSessionIsDetachedEvenWhenTheLockKeeperCannotBeAbandoned() = runTest {
        val f = Fixture(abandonThrows = true)
        f.run.run()
        assertEquals(listOf("abandon", "detach", "mark", "erase", "abandon", "detach"), f.steps)
        assertEquals(AppPhase.SignedOut, f.phase)
    }
}
