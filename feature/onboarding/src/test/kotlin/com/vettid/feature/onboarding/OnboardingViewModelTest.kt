package com.vettid.feature.onboarding

import com.vettid.core.data.account.SetupLinkInbox
import com.vettid.core.data.policy.PinPolicy
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.SetupCodeInput
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

    private val vault = FakeVault()
    private val inbox = SetupLinkInbox()
    private fun vm() = OnboardingViewModel(vault, vault, inbox)
    private val secret = "AbCdEfGhIjKlMnOpQrStUv"

    @Test
    fun typedSetupCodeThenEnrollAndCreateTheCredential() = runTest {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(OnboardingStep.WELCOME, vm.uiState.value.step)
        vm.start()
        assertEquals(OnboardingStep.SETUP_SCAN, vm.uiState.value.step)
        vm.typeCode()
        assertEquals(OnboardingStep.SETUP_TYPE, vm.uiState.value.step)
        vm.setEmail("not-an-email")
        vm.setCode("K7QM-4XR0")
        assertTrue("0 is never in a code", vm.uiState.value.codeInvalid)
        vm.submitCode()
        assertTrue(vm.uiState.value.emailInvalid)
        assertFalse("nothing is sent for a malformed code", "redeemSetupCode" in vault.calls)
        vm.setEmail("  Sam@Example.org ")
        vm.setCode("k7qm 4xrp")
        assertFalse(vm.uiState.value.codeInvalid)
        vm.submitCode()
        advanceUntilIdle()
        assertEquals(SetupCodeInput.Typed("sam@example.org", "K7QM4XRP"), vault.lastRedeemed)
        // The account is shown before any PIN is asked for (reverse phishing, ENROLLMENT-CODES §7).
        assertEquals(OnboardingStep.CONFIRM_ACCOUNT, vm.uiState.value.step)
        assertEquals("m***@example.com", vm.uiState.value.emailHint)
        vm.confirmAccount()
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
    fun scannedSetupQrIsRedeemedOnlyForThisEnvironment() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.start()
        vm.scanned("""{"v":1,"t":"e","api":"https://account.staging.vettid.org","s":"$secret"}""")
        assertEquals(ScanRefusal.OTHER_ENVIRONMENT, vm.uiState.value.scanRefusal)
        assertEquals("https://account.staging.vettid.org", vm.uiState.value.otherApi)
        vm.scanned("hello")
        assertEquals(ScanRefusal.NOT_A_CODE, vm.uiState.value.scanRefusal)
        val recoveryQr = """{"v":1,"t":"r","api":"https://account.vettid.org","vault_id":"0123456789abcdef0123456789abcdef",""" +
            """"recovery_id":"01JABCDEF0123456789ABCDEFG","code":"SK0ATG8WK2FYJ0Y5MEHJ5R5J7QZKWHX0"}"""
        vm.scanned(recoveryQr)
        assertEquals(ScanRefusal.RECOVERY_CODE, vm.uiState.value.scanRefusal)
        assertFalse("redeemSetupCode" in vault.calls)
        vm.scanned("""{"v":1,"t":"e","api":"https://account.vettid.org","s":"$secret"}""")
        advanceUntilIdle()
        assertEquals(SetupCodeInput.Secret(secret), vault.lastRedeemed)
        assertEquals(OnboardingStep.CONFIRM_ACCOUNT, vm.uiState.value.step)
    }

    @Test
    fun appLinkIsRedeemedWhileNothingIsSetUp() = runTest {
        val vm = vm()
        advanceUntilIdle()
        inbox.offer("https://account.vettid.org/vault/enroll/#s=$secret")
        advanceUntilIdle()
        assertNull(inbox.link.value)
        assertEquals(SetupCodeInput.Secret(secret), vault.lastRedeemed)
        assertEquals(OnboardingStep.CONFIRM_ACCOUNT, vm.uiState.value.step)
    }

    @Test
    fun anotherEnvironmentsAppLinkIsNotRedeemed() = runTest {
        val vm = vm()
        advanceUntilIdle()
        inbox.offer("https://account.staging.vettid.org/vault/enroll/#s=$secret")
        advanceUntilIdle()
        assertFalse("redeemSetupCode" in vault.calls)
        assertEquals(ScanRefusal.NOT_A_CODE, vm.uiState.value.scanRefusal)
    }

    @Test
    fun aRefusedCodeSaysSoAndNotMyAccountStartsOver() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.start()
        vm.typeCode()
        vm.setEmail("sam@example.org")
        vm.setCode("K7QM-4XRP")
        vault.fail["redeemSetupCode"] = FakeVault.failure(FailureKind.SETUP_CODE_INVALID, "invalid_code")
        vm.submitCode()
        advanceUntilIdle()
        assertEquals(OnboardingStep.SETUP_TYPE, vm.uiState.value.step)
        assertEquals(FailureKind.SETUP_CODE_INVALID, vm.uiState.value.error)
        vm.submitCode()
        advanceUntilIdle()
        assertEquals(OnboardingStep.CONFIRM_ACCOUNT, vm.uiState.value.step)
        vm.useAnotherCode()
        advanceUntilIdle()
        assertTrue("forgetSetupCode" in vault.calls)
        assertEquals(OnboardingStep.WELCOME, vm.uiState.value.step)
    }

    @Test
    fun backupOffNeedsTheAcknowledgement() = runTest {
        vault.phase.value = AppPhase.Setup(SetupStage.NEW_VAULT)
        val vm = vm()
        advanceUntilIdle()
        vm.confirmAccount()
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
    fun vaultElsewhereFollowsThePhase() = runTest {
        val vm = vm()
        advanceUntilIdle()
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
        vm.confirmAccount()
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
        vm.confirmAccount()
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

    @Test
    fun recoverFromTheWelcomeScreenNeedsNoSignIn() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.startRecovery()
        assertEquals(OnboardingStep.RECOVER, vm.uiState.value.step)
        // Registered: the phase follows, the flow stays; finishing does not jump to the generic done screen.
        vault.phase.value = AppPhase.Setup(SetupStage.RECOVERING)
        advanceUntilIdle()
        assertEquals(OnboardingStep.RECOVER, vm.uiState.value.step)
        vault.phase.value = AppPhase.Setup(SetupStage.FINISHING)
        advanceUntilIdle()
        assertEquals(OnboardingStep.RECOVER, vm.uiState.value.step)
    }

    @Test
    fun transferFromTheWelcomeScreenAndBack() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.startTransfer()
        assertEquals(OnboardingStep.TRANSFER_IN, vm.uiState.value.step)
        vm.leaveMove()
        advanceUntilIdle()
        assertEquals(OnboardingStep.WELCOME, vm.uiState.value.step)
        // After a redeem that found a vault elsewhere, leaving goes back to that choice.
        vault.phase.value = AppPhase.Setup(SetupStage.NEW_VAULT)
        advanceUntilIdle()
        vm.transfer()
        vm.leaveMove()
        advanceUntilIdle()
        assertEquals(OnboardingStep.VAULT_ELSEWHERE, vm.uiState.value.step)
        vm.recover()
        assertEquals(OnboardingStep.RECOVER, vm.uiState.value.step)
    }

    @Test
    fun aRecoveryInProgressResumesAfterARestart() = runTest {
        vault.phase.value = AppPhase.Setup(SetupStage.RECOVERING)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(OnboardingStep.RECOVER, vm.uiState.value.step)
    }

    @Test
    fun aPhoneThatErasedItselfShowsAFreshWelcome() = runTest {
        // Owner decision 2026-10-05: a replaced phone wipes itself and is SignedOut; nothing of before is shown.
        vault.phase.value = AppPhase.Setup(SetupStage.NEW_VAULT)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(OnboardingStep.CONFIRM_ACCOUNT, vm.uiState.value.step)
        vm.confirmAccount()
        vm.setEmail("sam@example.org")
        vm.setPin("40281795")
        vault.phase.value = AppPhase.SignedOut
        advanceUntilIdle()
        assertEquals(OnboardingUiState(devHint = vault.devHint), vm.uiState.value)
        assertEquals(OnboardingStep.WELCOME, vm.uiState.value.step)
        assertEquals("", vm.uiState.value.email)
        assertEquals("", vm.uiState.value.pin)
        assertNull(vm.uiState.value.error)
    }
}
