package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.testing.FakeReleaseNotes
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
 * A vault PIN is 6 to 32 ASCII digits (VAULT-MESSAGING §11.3, `PinPolicy`): the unlock's button is off for a shorter one.
 * (Before, the request was refused on the phone when it was made, `AltChannel.isValidPin`, so nothing reached the vault and no
 * attempt was counted; the member only saw a failure.) Any length in that range is a PIN: the field keeps up to 32 digits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockPinLengthTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Locked)

    @Test
    fun aPinShorterThanSixDigitsIsNotSent() = runTest {
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("12345")
        assertFalse(vm.uiState.value.submitAllowed)
        vm.submit()
        advanceUntilIdle()
        assertEquals(AppPhase.Locked, vault.phase.value)
        vm.setPin("123456")
        assertTrue(vm.uiState.value.submitAllowed)
    }

    @Test
    fun tenDigitsAreAPin() = runTest {
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("1234567890")
        assertEquals("1234567890", vm.uiState.value.pin)
        assertTrue(vm.uiState.value.submitAllowed)
    }

    @Test
    fun thirtyTwoDigitsAreKeptAndTheThirtyThirdIsNot() = runTest {
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        val longest = "1".repeat(32)
        vm.setPin(longest + "7")
        assertEquals(longest, vm.uiState.value.pin)
        assertTrue(vm.uiState.value.submitAllowed)
    }
}
