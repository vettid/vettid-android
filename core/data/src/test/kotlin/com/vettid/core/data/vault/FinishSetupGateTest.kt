package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Leaving onboarding never shows the shell over a locked vault; a failed "Finish the move" says why (release R). */
class FinishSetupGateTest {
    private val finishing = AppPhase.Setup(SetupStage.FINISHING)

    @Test
    fun onlyFromFinishingWithNothingPending() {
        assertTrue(FinishSetupGate.allows(finishing, lockPending = false, finishingMove = false))
        assertFalse("a lock pending", FinishSetupGate.allows(finishing, lockPending = true, finishingMove = false))
        assertFalse("Finish the move running", FinishSetupGate.allows(finishing, lockPending = false, finishingMove = true))
        assertFalse(FinishSetupGate.allows(AppPhase.Locked, lockPending = false, finishingMove = false))
        assertFalse(FinishSetupGate.allows(AppPhase.Setup(SetupStage.NEEDS_CREDENTIAL), lockPending = false, finishingMove = false))
    }

    @Test
    fun theFailureOfAMoveThatDidNotFinish() {
        assertNull(FinishSetupGate.failureOf(UnlockAttempt.Success))
        assertEquals(MoveFailure(FailureKind.BAD_PIN), FinishSetupGate.failureOf(UnlockAttempt.BadPin(0)))
        assertEquals(MoveFailure(FailureKind.BACKOFF), FinishSetupGate.failureOf(UnlockAttempt.Backoff(30)))
        assertEquals(
            MoveFailure(FailureKind.TRANSFER_INCOMPLETE, "transfer_incomplete"),
            FinishSetupGate.failureOf(UnlockAttempt.Failed(FailureKind.TRANSFER_INCOMPLETE, "transfer_incomplete")),
        )
        // The unlock guard's own refusal keeps its code, so the unlock screen can tell it apart.
        assertEquals(
            MoveFailure(FailureKind.NOT_SUPPORTED, UnlockAttempt.CODE_NO_USER_GUID),
            FinishSetupGate.failureOf(UnlockAttempt.Failed(FailureKind.NOT_SUPPORTED, UnlockAttempt.CODE_NO_USER_GUID)),
        )
    }
}
