package com.vettid.feature.credential

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A new credential (VAULT-MESSAGING 0.15.2 §3.5.5): PIN, current password, new password, an explicit warning. */
@OptIn(ExperimentalCoroutinesApi::class)
class NewCredentialViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Unlocked)

    private fun filled(vm: NewCredentialViewModel) {
        vm.setPin("975310")
        vm.setCurrent("old password")
        vm.setPassword("a much longer new pass phrase 42")
        vm.setConfirm("a much longer new pass phrase 42")
    }

    @Test
    fun needsTheAcknowledgementAndAConfirmation() = runTest {
        val vm = NewCredentialViewModel(vault)
        filled(vm)
        assertFalse(vm.uiState.value.filled)
        vm.setAcknowledged(true)
        vm.submit()
        assertTrue(vm.uiState.value.confirming)
        assertFalse("newCredential" in vault.calls)
        vm.confirm()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.done)
        assertEquals("975310", vault.lastPin)
        assertEquals("old password", vault.lastPassword)
        assertEquals("a much longer new pass phrase 42", vault.lastNewPassword)
    }

    @Test
    fun aWrongEntryIsShownAndNotKept() = runTest {
        vault.fail["newCredential"] = FakeVault.failure(FailureKind.BAD_PASSWORD, "bad_password")
        val vm = NewCredentialViewModel(vault)
        filled(vm)
        vm.setAcknowledged(true)
        vm.submit()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(FailureKind.BAD_PASSWORD, vm.uiState.value.error)
        assertEquals("", vm.uiState.value.pin)
        assertEquals("", vm.uiState.value.current)
    }

    @Test
    fun theNewPasswordMustMatch() = runTest {
        val vm = NewCredentialViewModel(vault)
        filled(vm)
        vm.setConfirm("something else entirely 99")
        vm.setAcknowledged(true)
        vm.submit()
        assertTrue(vm.uiState.value.mismatch)
        assertFalse(vm.uiState.value.confirming)
    }
}
