package com.vettid.feature.onboarding

import com.vettid.core.data.account.SetupLinkInbox
import com.vettid.core.data.policy.PasswordRule
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

    /**
     * (A6 audit N3) A restart with an enrollment outstanding: it is resumed with no PIN asked (the vault may hold the one
     * chosen then); once it made the vault, the password follows and, for release R, the PIN chosen before.
     */
    @Test
    fun anOutstandingEnrollmentAfterARestartIsResumedWithoutANewPin() = runTest {
        vault.phoneSecret = true
        vault.pending = true
        vault.phase.value = AppPhase.Setup(SetupStage.ENROLLING)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(1, vault.calls.count { it == "resumeEnroll" })
        assertFalse("never posted over", "enroll" in vault.calls)
        val s = vm.uiState.value
        assertEquals(OnboardingStep.PASSWORD, s.step)
        assertTrue(s.credentialOnly)
        assertTrue("the vault verifies the PIN chosen before", s.askPin && s.pinFromBefore)
        assertFalse(vm.back())
    }

    /** (N3) A resumed enrollment that made no vault: the member starts with the account and a PIN, as a first time. */
    @Test
    fun aResumedEnrollmentThatMadeNoVaultStartsWithThePin() = runTest {
        vault.pending = true
        vault.resumeMadeVault = false
        vault.phase.value = AppPhase.Setup(SetupStage.ENROLLING)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(OnboardingStep.CONFIRM_ACCOUNT, vm.uiState.value.step)
        assertFalse(vm.uiState.value.resuming)
        vm.confirmAccount()
        assertEquals(OnboardingStep.PIN_CREATE, vm.uiState.value.step)
    }

    /**
     * (N3) A resume that failed (the relay out of reach): Try again resumes again, and Edit goes to the account (another
     * code), never to a new PIN; continuing from there resumes once more.
     */
    @Test
    fun aFailedResumeIsTriedAgainAndNeverAsksForANewPin() = runTest {
        vault.pending = true
        vault.fail["resumeEnroll"] = FakeVault.failure(FailureKind.ENROLL_WAITING)
        vault.phase.value = AppPhase.Setup(SetupStage.ENROLLING)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(OnboardingStep.PROGRESS, vm.uiState.value.step)
        assertEquals(FailureKind.ENROLL_WAITING, vm.uiState.value.error)
        vm.editAfterFailure()
        assertEquals(OnboardingStep.CONFIRM_ACCOUNT, vm.uiState.value.step)
        vm.confirmAccount()
        advanceUntilIdle()
        assertEquals(2, vault.calls.count { it == "resumeEnroll" })
        assertFalse("enroll" in vault.calls)
        assertEquals(OnboardingStep.PASSWORD, vm.uiState.value.step)
    }

    /**
     * (N3) A lost answer in this flow ("Try again" after a failed poll): the PIN of the outstanding request stays the PIN;
     * Edit goes back to the password, not to the PIN, and the retry carries the same PIN.
     */
    @Test
    fun aLostAnswerKeepsThePinOfTheOutstandingRequest() = runTest {
        vault.phase.value = AppPhase.Setup(SetupStage.NEW_VAULT)
        val vm = vm()
        advanceUntilIdle()
        vm.confirmAccount()
        vm.setPin("40281795"); vm.submitPin(); vm.setPinConfirm("40281795"); vm.submitPinConfirm()
        vm.setPassword("correct horse battery staple"); vm.setPasswordConfirm("correct horse battery staple"); vm.submitPassword()
        vault.pendingAfterFailure = true
        vault.fail["enroll"] = FakeVault.failure(FailureKind.NETWORK)
        vm.submitBackup()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pinFixed)
        vm.editAfterFailure()
        assertEquals(OnboardingStep.PASSWORD, vm.uiState.value.step)
        assertFalse("the PIN can no longer be changed here", vm.back())
        vm.submitPassword()
        vm.submitBackup()
        advanceUntilIdle()
        assertEquals("40281795", vault.lastPin)
        assertEquals(OnboardingStep.DONE, vm.uiState.value.step)
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

    /** Release R (VAULT-MESSAGING 0.33.0 §11.14.9): credential.create carries the enrollment's PIN. */
    @Test
    fun releaseRCredentialCreateCarriesThePin() = runTest {
        vault.phoneSecret = true
        vault.phase.value = AppPhase.Setup(SetupStage.NEW_VAULT)
        val vm = vm()
        advanceUntilIdle()
        vm.confirmAccount()
        vm.setPin("40281795"); vm.submitPin(); vm.setPinConfirm("40281795"); vm.submitPinConfirm()
        vm.setPassword("correct horse battery staple"); vm.setPasswordConfirm("correct horse battery staple"); vm.submitPassword()
        vm.submitBackup()
        advanceUntilIdle()
        assertTrue("createCredential" in vault.calls)
        assertEquals("40281795", vault.lastCredentialPin)
    }

    /** Release R: after a restart the PIN is not in memory; the password step asks for it and credential.create carries it. */
    @Test
    fun releaseRNeedsCredentialAsksForThePin() = runTest {
        vault.phoneSecret = true
        vault.phase.value = AppPhase.Setup(SetupStage.NEEDS_CREDENTIAL)
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.askPin)
        vm.setPassword("correct horse battery staple"); vm.setPasswordConfirm("correct horse battery staple")
        assertFalse(vm.uiState.value.credentialPinReady)
        vm.submitPassword()
        assertEquals(OnboardingStep.PASSWORD, vm.uiState.value.step)
        vm.setPin("40281795")
        vm.submitPassword()
        assertEquals(OnboardingStep.BACKUP, vm.uiState.value.step)
        vm.submitBackup()
        advanceUntilIdle()
        assertEquals("40281795", vault.lastCredentialPin)
    }

    /** Before release R nothing asks for the PIN again and none is sent. */
    @Test
    fun beforeReleaseRNeedsCredentialAsksNoPin() = runTest {
        vault.phase.value = AppPhase.Setup(SetupStage.NEEDS_CREDENTIAL)
        val vm = vm()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.askPin)
        vm.setPassword("correct horse battery staple"); vm.setPasswordConfirm("correct horse battery staple"); vm.submitPassword()
        vm.submitBackup()
        advanceUntilIdle()
        assertTrue("createCredential" in vault.calls)
        assertEquals(null, vault.lastCredentialPin)
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

    /** ANDROID-PLAN "Passwords": the rules as the member types; Continue only once they pass (staging release 15: password1234). */
    @Test
    fun theReleaseRPasswordRulesAtEnrollment() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.confirmAccount()
        vm.setPin("40281795"); vm.submitPin(); vm.setPinConfirm("40281795"); vm.submitPinConfirm()
        assertEquals(OnboardingStep.PASSWORD, vm.uiState.value.step)
        fun verdict(pw: String): PasswordRule {
            vm.setPassword(pw); vm.setPasswordConfirm(pw)
            return vm.uiState.value.passwordRule
        }
        assertEquals(PasswordRule.SHORT, verdict("short"))
        assertEquals(PasswordRule.REPEATED, verdict("aaaaaaaaaaaaaa"))
        assertEquals(PasswordRule.COMMON, verdict("password1234"))
        assertEquals(PasswordRule.COMMON, verdict("Password1234"))
        assertEquals(PasswordRule.GUESSABLE, verdict("123456789123"))
        assertFalse(vm.uiState.value.canSubmitPassword)
        vm.submitPassword()
        assertEquals("a refused password goes no further", OnboardingStep.PASSWORD, vm.uiState.value.step)
        assertEquals(PasswordRule.OK, verdict("correct horse battery staple"))
        assertTrue(vm.uiState.value.canSubmitPassword)
        vm.submitPassword()
        assertEquals(OnboardingStep.BACKUP, vm.uiState.value.step)
    }

    @Test
    fun aPasswordEqualToThePinIsRefused() = runTest {
        vault.phoneSecret = true
        vault.phase.value = AppPhase.Setup(SetupStage.NEEDS_CREDENTIAL)
        val vm = vm()
        advanceUntilIdle()
        vm.setPassword("402817950582163"); vm.setPasswordConfirm("402817950582163")
        assertTrue(vm.uiState.value.passwordRule != PasswordRule.SAME_AS_PIN)
        // The PIN typed after the password on the same screen.
        vm.setPin("402817950582163")
        assertEquals(PasswordRule.SAME_AS_PIN, vm.uiState.value.passwordRule)
        assertFalse(vm.uiState.value.canSubmitPassword)
    }

    /** The vault's weak_password{reason}: back to the password, the reason under the field in the app's own words. */
    @Test
    fun theVaultsWeakPasswordReasonIsShown() = runTest {
        vault.phase.value = AppPhase.Setup(SetupStage.NEEDS_CREDENTIAL)
        vault.fail["createCredential"] = com.vettid.core.data.vault.VaultFailure(
            FailureKind.WEAK_PASSWORD, "weak_password",
            cause = com.vettid.core.vault.VaultOpException(
                "credential.create", "weak_password", body = kotlinx.serialization.json.buildJsonObject {
                    put("reason", kotlinx.serialization.json.JsonPrimitive("guessable"))
                },
            ),
        )
        val vm = vm()
        advanceUntilIdle()
        vm.setPassword("correct horse battery staple"); vm.setPasswordConfirm("correct horse battery staple"); vm.submitPassword()
        vm.submitBackup()
        advanceUntilIdle()
        assertEquals(OnboardingStep.PASSWORD, vm.uiState.value.step)
        assertEquals(PasswordRule.GUESSABLE, vm.uiState.value.passwordRule)
        assertTrue(vm.uiState.value.passwordRefused)
        assertNull(vm.uiState.value.error)
        assertFalse("the same password is not sent again", vm.uiState.value.canSubmitPassword)
        vm.setPassword("a much better passphrase 9 here"); vm.setPasswordConfirm("a much better passphrase 9 here")
        assertFalse(vm.uiState.value.passwordRefused)
        assertTrue(vm.uiState.value.canSubmitPassword)
    }

    /** A reason this app does not know: the generic text, and the same password is not sent again. */
    @Test
    fun anUnknownWeakPasswordReasonBlocksTheSamePassword() = runTest {
        vault.phase.value = AppPhase.Setup(SetupStage.NEEDS_CREDENTIAL)
        vault.fail["createCredential"] = com.vettid.core.data.vault.VaultFailure(FailureKind.WEAK_PASSWORD, "weak_password")
        val vm = vm()
        advanceUntilIdle()
        vm.setPassword("correct horse battery staple"); vm.setPasswordConfirm("correct horse battery staple"); vm.submitPassword()
        vm.submitBackup()
        advanceUntilIdle()
        assertEquals(OnboardingStep.PASSWORD, vm.uiState.value.step)
        assertEquals(PasswordRule.VAULT_REFUSED, vm.uiState.value.passwordRule)
        assertTrue(vm.uiState.value.passwordRefused)
        assertFalse(vm.uiState.value.canSubmitPassword)
        vm.submitPassword()
        assertEquals(OnboardingStep.PASSWORD, vm.uiState.value.step)
    }

    /** After a move (also after a restart before leaving onboarding) the last screen is the move's, not the enrollment's. */
    @Test
    fun theLastScreenAfterAMoveIsTheMoves() = runTest {
        vault.movedInFlag = true
        vault.phoneSecret = true
        vault.phase.value = AppPhase.Setup(SetupStage.FINISHING)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(OnboardingStep.DONE, vm.uiState.value.step)
        assertTrue(vm.uiState.value.movedIn)
        assertTrue(vm.uiState.value.phoneSecret)
    }

    @Test
    fun theLastScreenAfterAnEnrollmentIsTheEnrollments() = runTest {
        vault.phase.value = AppPhase.Setup(SetupStage.FINISHING)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(OnboardingStep.DONE, vm.uiState.value.step)
        assertFalse(vm.uiState.value.movedIn)
    }
}
