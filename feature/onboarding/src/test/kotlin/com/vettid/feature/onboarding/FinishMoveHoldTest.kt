package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
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
 * VAULT-MESSAGING 0.45.0 §11.14.9 step 2: "Finish the move" locks and unlocks with a PIN typed on its own prompt, so it
 * is held while a PIN or password change is unresolved or the PIN uncertain: the gate's text, and nothing is sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FinishMoveHoldTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Unlocked)

    @Test
    fun finishTheMoveIsHeldWhileAChangeIsUnresolved() = runTest {
        vault.changeHold.value = FailureKind.CHANGE_UNDECIDED
        val vm = FinishMoveViewModel(vault)
        advanceUntilIdle()
        assertEquals(FailureKind.CHANGE_UNDECIDED, vm.uiState.value.held)
        vm.setPin("40281795")
        vm.finish()
        advanceUntilIdle()
        assertFalse("nothing sent", vault.calls.any { it == "lock" || it == "unlock" || it == "finishMove" })
        vault.changeHold.value = null
        advanceUntilIdle()
        assertNull(vm.uiState.value.held)
    }

    /**
     * (§11.14.9 step 2; chosen) Which PIN the vault expects is read when the PIN field becomes usable: a change sent or
     * decided while the member types sends nothing ("earlier request" text), the field empties, and the PIN typed again
     * goes with a fresh reading.
     */
    @Test
    fun aPinTakenBeforeAChangeSendsNothingAndIsAskedAgain() = runTest {
        vault.pinGeneration = 5
        val vm = FinishMoveViewModel(vault)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pinAllowed)
        vm.setPin("40281795")
        vault.pinGeneration = 6 // a PIN change began while the member typed
        vm.finish()
        advanceUntilIdle()
        assertEquals(FailureKind.CREDENTIAL_UNSETTLED, vm.uiState.value.failure)
        assertEquals("", vm.uiState.value.pin)
        assertFalse("nothing sent", vault.calls.any { it == "lock" || it == "unlock" })
        vm.setPin("13579246")
        vm.finish()
        advanceUntilIdle()
        assertEquals(listOf<Int?>(5, 6), vault.finishMoveTaken)
        assertTrue(vm.uiState.value.done)
        assertEquals(listOf("lock", "unlock"), vault.calls.filter { it == "lock" || it == "unlock" })
    }

    /** Digits typed before a hold go with it: the field empties, and the reading is taken again once the hold lifts. */
    @Test
    fun aHoldEmptiesThePinAndTheReadingIsTakenAfterIt() = runTest {
        val vm = FinishMoveViewModel(vault)
        advanceUntilIdle()
        vm.setPin("40281795")
        vault.changeHold.value = FailureKind.CHANGE_CHECKING
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.pin)
        vault.pinGeneration = 9 // the change decided
        vault.changeHold.value = null
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.finish()
        advanceUntilIdle()
        assertEquals(listOf<Int?>(9), vault.finishMoveTaken)
    }
}
