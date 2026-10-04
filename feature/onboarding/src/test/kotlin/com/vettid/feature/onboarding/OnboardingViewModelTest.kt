package com.vettid.feature.onboarding

import com.vettid.core.altchan.SignInStatus
import com.vettid.core.data.account.SignInLinkInbox
import com.vettid.core.data.policy.PinPolicy
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.SetupStage
import com.vettid.core.testing.FakeVault
import com.vettid.core.ui.components.StepState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val token = "test-sign-in-token-0000"
    private val vault = FakeVault()
    private val inbox = SignInLinkInbox()
    private fun vm() = OnboardingViewModel(vault, vault, inbox)

    @Test
    fun signInByPastedLinkThenEnrollAndCreateTheCredential() = runTest {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(OnboardingStep.WELCOME, vm.uiState.value.step)
        vm.start()
        vm.setEmail("not-an-email")
        vm.submitEmail()
        assertTrue(vm.uiState.value.emailInvalid)
        vm.setEmail("sam@example.org")
        vm.submitEmail()
        advanceUntilIdle()
        assertEquals(OnboardingStep.CHECK_EMAIL, vm.uiState.value.step)
        vm.setLinkInput("https://evil.example/auth/#t=$token")
        vm.submitLink()
        assertTrue(vm.uiState.value.linkInvalid)
        vm.setLinkInput("https://account.vettid.org/auth/#t=$token&e=sam%40example.org")
        vm.submitLink()
        assertEquals(OnboardingStep.CONFIRM_SIGN_IN, vm.uiState.value.step)
        // Nothing is sent before the member confirms.
        assertFalse("verifySignIn" in vault.calls)
        vm.confirmSignIn()
        advanceUntilIdle()
        assertEquals(OnboardingStep.PIN_CREATE, vm.uiState.value.step)

        vm.setPin("123456")
        vm.submitPin()
        assertEquals(PinPolicy.Problem.SEQUENCE, vm.uiState.value.pinProblem)
        vm.setPin("40281795")
        vm.submitPin()
        vm.setPinConfirm("40281796")
        vm.submitPinConfirm()
        assertTrue(vm.uiState.value.pinMismatch)
        vm.setPinConfirm("40281795")
        vm.submitPinConfirm()
        assertEquals(OnboardingStep.PASSWORD, vm.uiState.value.step)
        vm.setPassword("40281795")
        vm.setPasswordConfirm("40281795")
        vm.submitPassword()
        assertEquals(OnboardingStep.PASSWORD, vm.uiState.value.step)
        vm.setPassword("correct horse battery staple")
        vm.setPasswordConfirm("correct horse battery staple")
        vm.submitPassword()
        assertEquals(OnboardingStep.BACKUP, vm.uiState.value.step)

        vm.submitBackup()
        advanceUntilIdle()
        assertEquals(OnboardingStep.DONE, vm.uiState.value.step)
        assertEquals("40281795", vault.lastPin)
        assertEquals(true, vault.lastBackup)
        assertTrue(vm.uiState.value.progress.all { it.second == StepState.DONE })
        assertEquals("", vm.uiState.value.pin)
        assertEquals("", vm.uiState.value.password)
        vm.finish()
        advanceUntilIdle()
        assertEquals(AppPhase.Unlocked, vault.phase.value)
    }

    @Test
    fun backupOffNeedsTheAcknowledgement() = runTest {
        vault.phase.value = AppPhase.Setup(SetupStage.NEW_VAULT)
        val vm = vm()
        advanceUntilIdle()
        vm.setPin("40281795"); vm.submitPin(); vm.setPinConfirm("40281795"); vm.submitPinConfirm()
        vm.setPassword("correct horse battery staple"); vm.setPasswordConfirm("correct horse battery staple"); vm.submitPassword()
        vm.setBackup(false)
        assertFalse(vm.uiState.value.canContinueBackup)
        vm.submitBackup()
        assertEquals(OnboardingStep.BACKUP, vm.uiState.value.step)
        vm.acknowledgeBackupOff(true)
        vm.submitBackup()
        advanceUntilIdle()
        assertEquals(false, vault.lastBackup)
    }

    @Test
    fun appLinkAndAccountPin() = runTest {
        vault.signInStatus = SignInStatus.PIN_REQUIRED
        val vm = vm()
        advanceUntilIdle()
        inbox.offer("https://account.vettid.org/auth/#t=$token&e=sam%40example.org")
        advanceUntilIdle()
        assertNull(inbox.link.value)
        assertEquals(OnboardingStep.CONFIRM_SIGN_IN, vm.uiState.value.step)
        assertEquals("sam@example.org", vm.uiState.value.email)
        vm.confirmSignIn()
        advanceUntilIdle()
        assertEquals(OnboardingStep.ACCOUNT_PIN, vm.uiState.value.step)
        vm.setAccountPin("12a34")
        assertEquals("1234", vm.uiState.value.accountPin)
        vm.submitAccountPin()
        advanceUntilIdle()
        assertEquals(OnboardingStep.PIN_CREATE, vm.uiState.value.step)
    }

    @Test
    fun termsAndVaultElsewhereFollowThePhase() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vault.phase.value = AppPhase.TermsRequired(updated = true)
        advanceUntilIdle()
        assertEquals(OnboardingStep.TERMS, vm.uiState.value.step)
        assertTrue(vm.uiState.value.termsUpdated)
        vault.phase.value = AppPhase.Setup(SetupStage.VAULT_ELSEWHERE)
        advanceUntilIdle()
        assertEquals(OnboardingStep.VAULT_ELSEWHERE, vm.uiState.value.step)
        vm.enrollAnyway()
        assertEquals(OnboardingStep.PIN_CREATE, vm.uiState.value.step)
    }

    @Test
    fun enrollFailureMarksTheStepAndRetryResumesAfterEnrollment() = runTest {
        vault.phase.value = AppPhase.Setup(SetupStage.NEW_VAULT)
        val vm = vm()
        advanceUntilIdle()
        vm.setPin("40281795"); vm.submitPin(); vm.setPinConfirm("40281795"); vm.submitPinConfirm()
        vm.setPassword("correct horse battery staple"); vm.setPasswordConfirm("correct horse battery staple"); vm.submitPassword()
        vault.fail["createCredential"] = FakeVault.failure(FailureKind.NO_RESPONSE)
        vm.submitBackup()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(OnboardingStep.PROGRESS, s.step)
        assertEquals(FailureKind.NO_RESPONSE, s.error)
        assertTrue(s.progressFailed)
        vm.run()
        advanceUntilIdle()
        assertEquals(1, vault.calls.count { it == "enroll" })
        assertEquals(OnboardingStep.DONE, vm.uiState.value.step)
    }

    @Test
    fun vaultExistsGoesToTheElsewhereStep() = runTest {
        vault.phase.value = AppPhase.Setup(SetupStage.NEW_VAULT)
        val vm = vm()
        advanceUntilIdle()
        vm.setPin("40281795"); vm.submitPin(); vm.setPinConfirm("40281795"); vm.submitPinConfirm()
        vm.setPassword("correct horse battery staple"); vm.setPasswordConfirm("correct horse battery staple"); vm.submitPassword()
        vault.fail["enroll"] = FakeVault.failure(FailureKind.VAULT_EXISTS, "vault_exists")
        vm.submitBackup()
        advanceUntilIdle()
        assertEquals(OnboardingStep.VAULT_ELSEWHERE, vm.uiState.value.step)
    }

    @Test
    fun needsCredentialResumesAtThePassword() = runTest {
        vault.phase.value = AppPhase.Setup(SetupStage.NEEDS_CREDENTIAL)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(OnboardingStep.PASSWORD, vm.uiState.value.step)
        assertTrue(vm.uiState.value.credentialOnly)
        assertFalse(vm.back())
    }
}
