package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.SetupStage
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The PIN fields of the move and the recovery take ASCII digits only and send a PIN only in the vault PIN's form (§11.3, 6-32
 * ASCII digits): a non-ASCII digit is not kept, and a short PIN is not sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PinFieldsAsciiTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val arabicIndic = (0x0661..0x0666).map { Char(it) }.joinToString("")

    @Test
    fun finishingAMoveKeepsAsciiDigitsAndSendsOnlyAWellFormedPin() = runTest {
        val vault = FakeVault(AppPhase.Unlocked).apply { finishMovePending.value = true }
        val vm = FinishMoveViewModel(vault)
        advanceUntilIdle()
        vm.setPin(arabicIndic)
        assertEquals("", vm.uiState.value.pin)
        vm.setPin("40281")
        vm.finish()
        advanceUntilIdle()
        assertTrue("a short PIN is not sent", vault.finishMoveTaken.isEmpty())
        vm.setPin("402817")
        vm.finish()
        advanceUntilIdle()
        assertEquals(1, vault.finishMoveTaken.size)
    }

    @Test
    fun theRecoveryOpenKeepsAsciiDigitsAndSendsOnlyAWellFormedPin() = runTest {
        val vault = FakeVault(AppPhase.Setup(SetupStage.RECOVERING)).apply { phoneSecret = true }
        val vm = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        vm.setPin(arabicIndic)
        assertEquals("", vm.uiState.value.pin)
        vm.setPin("97531")
        vm.setPassword("correct horse battery")
        assertFalse(vm.uiState.value.pinValid)
        vm.review()
        vm.send()
        advanceUntilIdle()
        assertFalse("a short PIN is not sent", "recoveryOpen" in vault.calls)
        // The same steps with a PIN of the vault's form are sent.
        vm.setPin("975310")
        vm.review()
        vm.send()
        advanceUntilIdle()
        assertTrue("recoveryOpen" in vault.calls)
    }
}
