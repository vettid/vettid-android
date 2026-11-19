package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.UnlockAttempt
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The open app's "Finish the move" dialog keeps its view model across moves (it is scoped to the activity, and a wipe does not
 * end the activity): a second move into this phone starts from the PIN form, never from the first one's "done".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FinishMoveFreshTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Unlocked)

    /** Finishes the pending move here, as the manager does: the flag turns false during the unlock, before its answer. */
    private fun kotlinx.coroutines.test.TestScope.finishHere(vm: FinishMoveViewModel) {
        vault.duringUnlock = { vault.finishMovePending.value = false }
        vm.setPin("40281795")
        vm.finish()
        advanceUntilIdle()
        vault.duringUnlock = null
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun aSecondMoveInTheSameProcessStartsFromThePinForm() = runTest {
        vault.finishMovePending.value = true
        val vm = FinishMoveViewModel(vault)
        advanceUntilIdle()
        finishHere(vm)
        // An erase, then a second move into this phone (Later on its last screen): pending again.
        vault.finishMovePending.value = true
        advanceUntilIdle()
        // The banner shows before the dialog opens: the form is fresh already.
        assertFalse("fresh when the move begins", vm.uiState.value.done)
        vm.opened()
        val s = vm.uiState.value
        assertFalse("not the first move's done", s.done)
        assertTrue(s.pinAllowed)
        assertNull(s.failure)
        assertEquals("", s.pin)
    }

    /** The backstop in [FinishMoveViewModel.opened]: "done" with a move still pending is never shown. */
    @Test
    fun anOpeningWithAMovePendingIsNeverDone() = runTest {
        vault.finishMovePending.value = true
        val vm = FinishMoveViewModel(vault)
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.finish()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.done)
        vm.opened() // the flag never turned false (a transition the collector did not see)
        assertFalse(vm.uiState.value.done)
        assertTrue(vm.uiState.value.pinAllowed)
    }

    /** A reopening after the move finished (a rotation, the dialog composed again) keeps its "done". */
    @Test
    fun aFinishedMoveStaysDoneWhenTheDialogOpensAgain() = runTest {
        vault.finishMovePending.value = true
        val vm = FinishMoveViewModel(vault)
        advanceUntilIdle()
        finishHere(vm)
        vm.opened()
        assertTrue(vm.uiState.value.done)
    }

    /** A reopening of the same pending move keeps what it said (a wrong PIN): only a new move starts afresh. */
    @Test
    fun theSameMoveKeepsItsFailureWhenTheDialogOpensAgain() = runTest {
        vault.finishMovePending.value = true
        vault.unlockResults += UnlockAttempt.BadPin(0)
        val vm = FinishMoveViewModel(vault)
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.finish()
        advanceUntilIdle()
        assertEquals(FailureKind.BAD_PIN, vm.uiState.value.failure)
        vm.opened()
        assertEquals(FailureKind.BAD_PIN, vm.uiState.value.failure)
    }

    /**
     * The second move's PIN goes with a reading of its own (taken again at the first digit typed), never the first move's. A
     * guard against regressions in the keypad's reading across a reset.
     */
    @Test
    fun aSecondMovesPinGoesWithAFreshReading() = runTest {
        vault.pinGeneration = 5
        vault.finishMovePending.value = true
        val vm = FinishMoveViewModel(vault)
        advanceUntilIdle()
        finishHere(vm)
        vault.pinGeneration = 6 // a change in between
        vault.finishMovePending.value = true
        advanceUntilIdle()
        vm.opened()
        vm.setPin("13579246")
        vm.finish()
        advanceUntilIdle()
        assertEquals(6, vault.finishMoveTaken.last())
    }

    /** A new move's edge while a finish runs changes nothing of the run: it stays busy, and its answer is shown. */
    @Test
    fun aRunningMoveIsNotResetAndItsAnswerStays() = runTest {
        val vm = FinishMoveViewModel(vault)
        advanceUntilIdle()
        vault.unlockGate = kotlinx.coroutines.CompletableDeferred()
        vault.unlockResults += UnlockAttempt.BadPin(0)
        vm.setPin("40281795")
        vm.finish()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.busy)
        vault.finishMovePending.value = true // a new move's edge while the unlock is held
        advanceUntilIdle()
        assertTrue("still running", vm.uiState.value.busy)
        vault.unlockGate?.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.busy)
        assertEquals("the run's answer", FailureKind.BAD_PIN, vm.uiState.value.failure)
    }

    /** A new move keeps the user_guid's reading and its failure: no PIN can open the vault here either way. */
    @Test
    fun aNewMoveKeepsTheNoUserGuidFailure() = runTest {
        vault.userGuidUsable = false
        val vm = FinishMoveViewModel(vault)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.noUserGuid)
        vault.finishMovePending.value = true
        advanceUntilIdle()
        assertTrue(vm.uiState.value.noUserGuid)
        assertEquals(FailureKind.NOT_SUPPORTED, vm.uiState.value.failure)
        assertFalse(vm.uiState.value.pinAllowed)
    }

    /** An earlier move's failure and typed digits are not carried into the next move. */
    @Test
    fun aNewMoveClearsAnEarlierMovesFailureAndDigits() = runTest {
        vault.finishMovePending.value = true
        vault.unlockResults += UnlockAttempt.BadPin(0)
        val vm = FinishMoveViewModel(vault)
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.finish()
        advanceUntilIdle()
        assertEquals(FailureKind.BAD_PIN, vm.uiState.value.failure)
        vm.setPin("1357") // typed, then Later
        vault.finishMovePending.value = false // a wipe ends that move
        advanceUntilIdle()
        vault.finishMovePending.value = true // a new move
        advanceUntilIdle()
        assertNull(vm.uiState.value.failure)
        assertEquals("", vm.uiState.value.pin)
    }
}
