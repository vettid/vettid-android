package com.vettid.feature.settings

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.ChangeCheck
import com.vettid.core.data.vault.ChangeKind
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The new phone secret keeps ASCII digits only and sends only a PIN of the vault PIN's form; Change PIN's button needs the
 * password when the phone secret asks for it, and each term of its rule counts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PinFieldsAsciiTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = kotlinx.coroutines.Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = kotlinx.coroutines.Dispatchers.resetMain()

    private val arabicIndic = (0x0661..0x0666).map { Char(it) }.joinToString("")

    @Test
    fun theNewPhoneSecretSendsOnlyAWellFormedPin() = runTest(dispatcher) {
        val vault = FakeVault(AppPhase.Unlocked).apply { phoneSecret = true }
        val vm = NewPhoneSecretViewModel(vault)
        vm.setPin(arabicIndic)
        assertEquals("", vm.uiState.value.pin)
        vm.setPassword("the password")
        vm.setPin("97531")
        assertFalse(vm.uiState.value.canSubmit)
        vm.submit()
        advanceUntilIdle()
        assertFalse("a short PIN is not sent", "renewPhoneSecret" in vault.calls)
        vm.setPin("975310")
        assertTrue(vm.uiState.value.canSubmit)
    }

    @Test
    fun changePinsButtonNeedsEachOfItsTerms() {
        val ready = ChangePinUiState(current = "402817", pin = "593104", confirm = "593104")
        assertTrue(ready.submitAllowed)
        assertFalse("the password the phone secret asks for", ready.copy(withPassword = true).submitAllowed)
        assertTrue(ready.copy(withPassword = true, password = "p").submitAllowed)
        assertFalse("a current PIN of the PIN's form", ready.copy(current = arabicIndic).submitAllowed)
        assertFalse("the confirmation", ready.copy(confirm = "").submitAllowed)
        assertFalse("the new PIN", ready.copy(pin = "").submitAllowed)
        assertFalse("while a change is decided", ready.copy(check = ChangeCheck.Checking(ChangeKind.PIN)).submitAllowed)
    }

    @Test
    fun changePinKeepsAsciiDigits() {
        val vm = ChangePinViewModel(FakeVault(AppPhase.Unlocked))
        vm.setCurrent(arabicIndic + "402817")
        assertEquals("402817", vm.uiState.value.current)
    }
}
