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
}
