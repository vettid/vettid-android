package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.RecoverOutcome
import com.vettid.core.data.vault.RecoveryRegistration
import com.vettid.core.data.vault.RecoveryStage
import com.vettid.core.altchan.RecoveryCode
import com.vettid.core.data.vault.SetupStage
import com.vettid.core.data.vault.UnlockAttempt
import com.vettid.core.testing.FakeReleaseNotes
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecoverViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Setup(SetupStage.VAULT_ELSEWHERE))
    private val vid = "0123456789abcdef0123456789abcdef"
    private val rid = "01JA0RECVERY0000000000001X"
    private val code = "SK01TG8WK2FYJ1Y5MEHJ5R5J7QZKWHX0"
    private val api = "https://account.vettid.org"
    private val qr = """{"v":1,"t":"r","api":"$api","vault_id":"$vid","recovery_id":"$rid","code":"$code"}"""

    @Test
    fun scanRegisterPinPasswordDone() = runTest {
        val vm = RecoverViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertEquals(RecoverStep.INTRO, vm.uiState.value.step)
        vm.scan()
        vm.scanned(qr)
        advanceUntilIdle()
        assertEquals(RecoveryCode(vid, rid, code, api), vault.lastRegistered)
        assertEquals(vault.emailHint, vm.uiState.value.emailHint)
        assertEquals(AppPhase.Setup(SetupStage.RECOVERING), vault.phase.value)
        assertEquals(RecoverStep.PIN, vm.uiState.value.step)
        assertTrue(vm.uiState.value.pinAllowed)
        vm.setPin("975310")
        vm.submitPin()
        advanceUntilIdle()
        assertEquals("975310", vault.lastPin)
        assertEquals(RecoverStep.PASSWORD, vm.uiState.value.step)
        assertEquals("", vm.uiState.value.pin)
        vm.setPassword("correct horse battery")
        vm.submitPassword()
        advanceUntilIdle()
        assertEquals("correct horse battery", vault.lastPassword)
        assertEquals(RecoverStep.DONE, vm.uiState.value.step)
        assertEquals("", vm.uiState.value.password)
        vm.finish()
        advanceUntilIdle()
        assertEquals(AppPhase.Unlocked, vault.phase.value)
    }

    @Test
    fun scansThatAreNotThisAccountsRecoveryCodeAreRefusedLocally() = runTest {
        val vm = RecoverViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.scan()
        vm.scanned("https://example.org")
        assertEquals(CodeRefusal.NOT_A_CODE, vm.uiState.value.refusal)
        // §11.11.2 (0.15.0): `api` must equal this build's member API origin exactly; it is never contacted.
        vm.scanned(qr.replace(api, "https://account.staging.vettid.org"))
        assertEquals(CodeRefusal.OTHER_ENVIRONMENT, vm.uiState.value.refusal)
        assertEquals("https://account.staging.vettid.org", vm.uiState.value.otherApi)
        vm.scanned(qr.replace("\"api\":\"$api\",", ""))
        assertEquals(CodeRefusal.NO_API, vm.uiState.value.refusal)
        assertFalse("registerRecovery" in vault.calls)
    }

    @Test
    fun wrongCodesAreCountedAndTheFifthVoidsTheRecovery() = runTest {
        repeat(5) { vault.registrations.add(RecoveryRegistration.Refused("bad_code")) }
        val vm = RecoverViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.scan()
        for (i in 1..4) {
            vm.scanned(qr)
            advanceUntilIdle()
            assertEquals(RecoverStep.SCAN, vm.uiState.value.step)
            assertEquals(CodeRefusal.BAD_CODE, vm.uiState.value.refusal)
            assertEquals(i, vm.uiState.value.wrongCodes)
        }
        vm.scanned(qr)
        advanceUntilIdle()
        assertEquals(CodeRefusal.VOIDED, vm.uiState.value.refusal)
    }

    @Test
    fun enclaveRefusalsSayWhy() = runTest {
        val vm = RecoverViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.scan()
        for ((c, r) in listOf(
            "expired" to CodeRefusal.EXPIRED, "too_early" to CodeRefusal.TOO_EARLY, "no_recovery" to CodeRefusal.GONE,
            "used" to CodeRefusal.GONE, "attestation" to CodeRefusal.ATTESTATION, "retry" to CodeRefusal.RETRY,
        )) {
            vault.registrations.add(RecoveryRegistration.Refused(c))
            vm.scanned(qr)
            advanceUntilIdle()
            assertEquals(c, r, vm.uiState.value.refusal)
            assertEquals(RecoverStep.SCAN, vm.uiState.value.step)
        }
        // The API's 409 recovery_not_available at the claim or the register (the app cannot read the recovery).
        vault.registrations.add(RecoveryRegistration.Refused("not_available"))
        vm.scanned(qr)
        advanceUntilIdle()
        assertEquals(CodeRefusal.NOT_AVAILABLE, vm.uiState.value.refusal)
    }

    @Test
    fun aCancelledRecoveryAtThePinSaysSo() = runTest {
        vault.recoveryStageValue = RecoveryStage.PIN
        vault.recoveryUnlockResults.add(UnlockAttempt.Failed(FailureKind.OTHER, "unknown_device"))
        val vm = RecoverViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertEquals(RecoverStep.PIN, vm.uiState.value.step)
        vm.setPin("975310")
        vm.submitPin()
        advanceUntilIdle()
        assertEquals(RecoverViewModel.CODE_UNKNOWN_DEVICE, vm.uiState.value.errorCode)
        assertEquals(RecoverStep.PIN, vm.uiState.value.step)
    }

    @Test
    fun badPinStartsTheBackoff() = runTest {
        vault.recoveryStageValue = RecoveryStage.PIN
        vault.recoveryUnlockResults.add(UnlockAttempt.BadPin(30))
        val vm = RecoverViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("975310")
        vm.submitPin()
        advanceTimeBy(1500)
        assertTrue(vm.uiState.value.pinWrong)
        assertTrue(vm.uiState.value.waitSeconds in 1..30)
        assertFalse(vm.uiState.value.pinAllowed)
    }

    @Test
    fun aReleaseThatEndedBlocksThePin() = runTest {
        vault.recoveryStageValue = RecoveryStage.PIN
        vault.fail["recoveryPreflight"] = FakeVault.failure(FailureKind.RELEASE_ENDED)
        val vm = RecoverViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertEquals(FailureKind.RELEASE_ENDED, vm.uiState.value.preflightError)
        assertFalse(vm.uiState.value.pinAllowed)
        vm.setPin("975310")
        vm.submitPin()
        advanceUntilIdle()
        assertFalse("recoveryUnlock" in vault.calls)
    }

    @Test
    fun anOfferedReleaseIsApprovedWithThePin() = runTest {
        vault.recoveryStageValue = RecoveryStage.PIN
        vault.preflightInfo =
            PreflightInfo(FakeVault.release(3), 0, softwareUpdated = false, rollback = false, offer = FakeVault.release(4))
        val vm = RecoverViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setApproveOffer(true)
        vm.setPin("975310")
        vm.submitPin()
        advanceUntilIdle()
        assertEquals(4L, vault.lastApproved?.number)
    }

    /** Registered, at the PIN: unlocks with [backup] as the result's `credential_backup` (0.10.6). */
    private fun kotlinx.coroutines.test.TestScope.unlockedWith(backup: Boolean?): RecoverViewModel {
        vault.recoveryStageValue = RecoveryStage.PIN
        vault.recoveryCredentialBackupValue = backup
        val vm = RecoverViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertEquals(RecoverStep.PIN, vm.uiState.value.step)
        vm.setPin("975310")
        vm.submitPin()
        advanceUntilIdle()
        return vm
    }

    @Test
    fun credentialBackupTrueAsksForThePassword() = runTest {
        val vm = unlockedWith(true)
        assertEquals(RecoverStep.PASSWORD, vm.uiState.value.step)
        vm.setPassword("correct horse battery")
        vm.submitPassword()
        advanceUntilIdle()
        assertEquals(RecoverStep.DONE, vm.uiState.value.step)
    }

    /** 0.16.0: a vault without a backup copy cannot be recovered; the app says so and links to the start-over. */
    @Test
    fun credentialBackupFalseCannotBeRecovered() = runTest {
        val vm = unlockedWith(false)
        assertEquals(RecoverStep.NO_BACKUP, vm.uiState.value.step)
        assertEquals("", vm.uiState.value.password)
        assertFalse("recoverCredential" in vault.calls)
        assertTrue("abandonRecovery" in vault.calls)
        assertEquals("https://account.vettid.org/account/vault/deletion/", vm.uiState.value.startOverUrl)
    }

    @Test
    fun noBackupAtTheUnlockCannotBeRecovered() = runTest {
        vault.recoveryStageValue = RecoveryStage.PIN
        vault.recoveryUnlockResults += UnlockAttempt.Failed(FailureKind.OTHER, "no_backup")
        val vm = RecoverViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("975310")
        vm.submitPin()
        advanceUntilIdle()
        assertEquals(RecoverStep.NO_BACKUP, vm.uiState.value.step)
        assertEquals("", vm.uiState.value.pin)
    }

    @Test
    fun noBackupAtTheRegisterCannotBeRecovered() = runTest {
        vault.registrations += RecoveryRegistration.Refused("no_backup")
        val vm = RecoverViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.scan()
        vm.scanned(qr)
        advanceUntilIdle()
        assertEquals(RecoverStep.NO_BACKUP, vm.uiState.value.step)
        assertTrue("abandonRecovery" in vault.calls)
    }

    @Test
    fun credentialBackupAbsentAsksForThePasswordAsBefore() = runTest {
        // A vault that did not say: the password's answer decides (an older vault's credential_lost: no recovery).
        vault.recoverOutcome = RecoverOutcome.NO_BACKUP
        val vm = unlockedWith(null)
        assertEquals(RecoverStep.PASSWORD, vm.uiState.value.step)
        vm.setPassword("whatever")
        vm.submitPassword()
        advanceUntilIdle()
        assertTrue("recoverCredential" in vault.calls)
        assertEquals(RecoverStep.NO_BACKUP, vm.uiState.value.step)
    }

    @Test
    fun credentialBackupFalseAlsoAppliesWhenTheFlowResumes() = runTest {
        vault.recoveryStageValue = RecoveryStage.PASSWORD
        vault.recoveryCredentialBackupValue = false
        val vm = RecoverViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertEquals(RecoverStep.NO_BACKUP, vm.uiState.value.step)
        assertFalse("recoverCredential" in vault.calls)
    }

    @Test
    fun aWrongPasswordIsShownAndTheFieldCleared() = runTest {
        vault.recoveryStageValue = RecoveryStage.PASSWORD
        vault.fail["recoverCredential"] = FakeVault.failure(FailureKind.BAD_PASSWORD, "bad_password")
        val vm = RecoverViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPassword("wrong")
        vm.submitPassword()
        advanceUntilIdle()
        assertEquals(FailureKind.BAD_PASSWORD, vm.uiState.value.error)
        assertEquals("", vm.uiState.value.password)
        assertEquals(RecoverStep.PASSWORD, vm.uiState.value.step)
    }

    @Test
    fun noRecoveryShowsWhatToDo() = runTest {
        val vm = RecoverViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertEquals(RecoverStep.INTRO, vm.uiState.value.step)
        assertFalse(vm.back())
        vm.scan()
        assertTrue(vm.back())
        assertEquals(RecoverStep.INTRO, vm.uiState.value.step)
    }
}
