package com.vettid.feature.credential

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.ChangeCheck
import com.vettid.core.data.vault.ChangeKind
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
        val vm = NewCredentialViewModel(vault, vault)
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
        val vm = NewCredentialViewModel(vault, vault)
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
        val vm = NewCredentialViewModel(vault, vault)
        filled(vm)
        vm.setConfirm("something else entirely 99")
        vm.setAcknowledged(true)
        vm.submit()
        assertTrue(vm.uiState.value.mismatch)
        assertFalse(vm.uiState.value.confirming)
    }

    /** Release R (ANDROID-PLAN "Passwords"): a reset's new password follows the rules too. */
    @Test
    fun releaseRResetFollowsTheRules() = runTest {
        vault.phoneSecret = true
        val vm = NewCredentialViewModel(vault, vault)
        assertTrue(vm.uiState.value.newRules)
        vm.setPin("975310")
        vm.setCurrent("old password")
        vm.setPassword("password12345")
        vm.setConfirm("password12345")
        vm.setAcknowledged(true)
        assertEquals(com.vettid.core.data.policy.PasswordRule.COMMON, vm.uiState.value.rule)
        assertFalse(vm.uiState.value.filled)
        vm.setPassword("a much longer new pass phrase 42")
        vm.setConfirm("a much longer new pass phrase 42")
        assertTrue(vm.uiState.value.filled)
    }

    // --- a lost answer to credential.reset (VAULT-MESSAGING 0.48.0, ANDROID-PLAN 0.1.51) ---

    private fun ready(vm: NewCredentialViewModel) {
        filled(vm)
        vm.setAcknowledged(true)
    }

    /** No answer is not a failure: the entries stay, the check's state follows, and the form is closed to a new send. */
    @Test
    fun noAnswerKeepsTheEntriesAndFollowsTheCheck() = runTest {
        vault.fail["newCredential"] = FakeVault.failure(FailureKind.CHANGE_CHECKING, "change_checking")
        val vm = NewCredentialViewModel(vault, vault)
        ready(vm)
        vm.submit()
        vault.changeCheck.value = ChangeCheck.Checking(ChangeKind.RESET)
        vm.confirm()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals("975310", s.pin)
        assertEquals("old password", s.current)
        assertEquals(ChangeCheck.Checking(ChangeKind.RESET), s.check)
        assertEquals(null, s.error)
        assertFalse(s.busy)
        assertFalse("nothing is sent while it is decided", s.filled)
    }

    /** Nothing was sent (an earlier request undecided): the error is shown and every entry stays. */
    @Test
    fun aRefusalWithNothingSentKeepsEveryEntry() = runTest {
        for (kind in listOf(FailureKind.RESET_UNDECIDED, FailureKind.CHANGE_UNRESOLVED, FailureKind.CREDENTIAL_UNSETTLED)) {
            vault.fail["newCredential"] = FakeVault.failure(kind, "x")
            val vm = NewCredentialViewModel(vault, vault)
            ready(vm)
            vm.submit()
            vm.confirm()
            advanceUntilIdle()
            assertEquals(kind, vm.uiState.value.error)
            assertEquals("975310", vm.uiState.value.pin)
            assertEquals("old password", vm.uiState.value.current)
        }
    }

    /** Ended and Moved close the form (no Try again); NotApplied opens it again. */
    @Test
    fun endedAndMovedDisableTheFormAndNotAppliedDoesNot() = runTest {
        val vm = NewCredentialViewModel(vault, vault)
        ready(vm)
        vault.changeCheck.value = ChangeCheck.Checking(ChangeKind.RESET)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.filled)
        vault.changeCheck.value = ChangeCheck.Ended(ChangeKind.RESET)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.filled)
        assertEquals(ChangeCheck.Ended(ChangeKind.RESET), vm.uiState.value.check)
        vault.changeCheck.value = ChangeCheck.Moved(ChangeKind.RESET)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.filled)
        vault.changeCheck.value = ChangeCheck.Checking(ChangeKind.RESET)
        advanceUntilIdle()
        vault.changeCheck.value = ChangeCheck.NotApplied(ChangeKind.RESET)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.filled)
        assertEquals(ChangeCheck.NotApplied(ChangeKind.RESET), vm.uiState.value.check)
    }

    /** A reset being decided when the screen opened is followed; a decision it did not follow is not shown here. */
    @Test
    fun anOldDecisionIsIgnoredButAnOpenCheckIsFollowed() = runTest {
        vault.changeCheck.value = ChangeCheck.Applied(ChangeKind.RESET)
        val vm = NewCredentialViewModel(vault, vault)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.done)
        vault.changeCheck.value = ChangeCheck.Checking(ChangeKind.RESET)
        advanceUntilIdle()
        vault.changeCheck.value = ChangeCheck.Applied(ChangeKind.RESET)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.done)
        assertTrue("acknowledged: said here, not again by the shell", vault.changeCheckAcknowledged > 0)
    }

    /** A refusal decided later by the resolver clears the PIN and the current password, as an answer does. */
    @Test
    fun aRefusalDecidedLaterClearsTheSecrets() = runTest {
        val vm = NewCredentialViewModel(vault, vault)
        ready(vm)
        vault.changeCheck.value = ChangeCheck.Checking(ChangeKind.RESET)
        advanceUntilIdle()
        vault.changeCheck.value = ChangeCheck.Refused(ChangeKind.RESET, FailureKind.BAD_PASSWORD)
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(FailureKind.BAD_PASSWORD, s.error)
        assertEquals("", s.pin)
        assertEquals("", s.current)
        assertEquals(null, s.check)
    }
}
