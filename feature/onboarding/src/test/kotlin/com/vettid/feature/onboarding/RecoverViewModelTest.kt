package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.RecoverOutcome
import com.vettid.core.data.vault.RecoveryRegistration
import com.vettid.core.data.vault.RecoveryStage
import com.vettid.core.altchan.RecoveryCode
import com.vettid.core.altchan.RecoveryCodes
import com.vettid.core.altchan.TypedRecoveryCode
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
import org.junit.Assert.assertNull
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

    // --- the code typed instead of scanned (VAULT-MESSAGING 0.25.0 §11.11.10; ANDROID-PLAN 0.1.33) ---

    // §16's vector (TEST ONLY): the 48 characters the production account site would show for this locator and code.
    private val typedLocator = "7ZK3M9QX2HTD"
    private val typedCode = "CDEFGHJKMNPQRSTVWXYZ0123456789AB"
    private val typed = typedLocator + typedCode + "V3A1"
    private val typedGrouped = "7ZK3 M9QX 2HTD CDEF GHJK MNPQ RSTV WXYZ 0123 4567 89AB V3A1"

    private fun kotlinx.coroutines.test.TestScope.typing(): RecoverViewModel {
        val vm = RecoverViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.type()
        assertEquals(RecoverStep.TYPE, vm.uiState.value.step)
        return vm
    }

    private fun registered(name: String = "registerTypedRecovery") = vault.calls.count { it == name }

    @Test
    fun typeRegisterPinPasswordDone() = runTest {
        val vm = typing()
        assertEquals("account.vettid.org", vm.uiState.value.accountHost)
        assertEquals(0, vm.uiState.value.typedGroups)
        assertFalse(vm.uiState.value.typedComplete)
        // As typed: lower case, with the site's spaces.
        vm.setTyped(typedGrouped.lowercase())
        assertEquals(typed, vm.uiState.value.typed)
        assertEquals(12, vm.uiState.value.typedGroups)
        assertTrue(vm.uiState.value.typedComplete)
        assertNull(vm.uiState.value.typedProblem)
        vm.submitTyped()
        assertEquals(RecoverStep.REGISTERING, vm.uiState.value.step)
        advanceUntilIdle()
        val sent = vault.lastTyped!!
        assertEquals(typedLocator, sent.locator)
        assertEquals(typedCode, sent.code)
        assertFalse("the scanned register is not used", "registerRecovery" in vault.calls)
        assertEquals(vault.emailHint, vm.uiState.value.emailHint)
        assertEquals(RecoverStep.PIN, vm.uiState.value.step)
        assertEquals("the characters are gone once the code was accepted", "", vm.uiState.value.typed)
        // From here on it is the scanned path.
        vm.setPin("975310")
        vm.submitPin()
        advanceUntilIdle()
        assertEquals(RecoverStep.PASSWORD, vm.uiState.value.step)
        vm.setPassword("correct horse battery")
        vm.submitPassword()
        advanceUntilIdle()
        assertEquals(RecoverStep.DONE, vm.uiState.value.step)
    }

    @Test
    fun typedCharactersAreNormalizedAndRefusedOnesAreNamedWithTheirGroup() = runTest {
        val vm = typing()
        vm.setTyped("7zk3-m9")
        assertEquals("7ZK3M9", vm.uiState.value.typed)
        assertEquals(1, vm.uiState.value.typedGroups)
        // I and L are read as 1, O as 0.
        vm.setTyped("7ZK3M9iLoO")
        assertEquals("7ZK3M91100", vm.uiState.value.typed)
        assertNull(vm.uiState.value.typedProblem)
        // A U is refused in the field: dropped, never guessed to be a V; the 11th character is in group 3.
        vm.setTyped("7ZK3M91100u")
        assertEquals("7ZK3M91100", vm.uiState.value.typed)
        assertEquals(TypedProblem.LETTER_U, vm.uiState.value.typedProblem)
        assertEquals(3, vm.uiState.value.typedGroup)
        // The next valid character clears the notice.
        vm.setTyped("7ZK3M91100V")
        assertNull(vm.uiState.value.typedProblem)
        vm.setTyped("7ZK3M91100V!")
        assertEquals("7ZK3M91100V", vm.uiState.value.typed)
        assertEquals(TypedProblem.OTHER_CHARACTER, vm.uiState.value.typedProblem)
        assertEquals(3, vm.uiState.value.typedGroup)
        // A forty-ninth character is ignored.
        vm.setTyped(typed + "Z")
        assertEquals(typed, vm.uiState.value.typed)
        assertNull(vm.uiState.value.typedProblem)
        assertEquals(0, registered())
        assertFalse("the entry is never printed", vm.uiState.value.toString().contains("7ZK3"))
    }

    @Test
    fun aStringFailingTheCheckSendsNothingAndIsNotCounted() = runTest {
        val vm = typing()
        // One mistyped character.
        vm.setTyped(typed.replaceFirst('M', 'N'))
        assertTrue(vm.uiState.value.typedComplete)
        vm.submitTyped()
        advanceUntilIdle()
        assertEquals(RecoverStep.TYPE, vm.uiState.value.step)
        assertEquals(TypedProblem.BAD_CHECK, vm.uiState.value.typedProblem)
        assertEquals("kept for correction", typed.replaceFirst('M', 'N'), vm.uiState.value.typed)
        // A staging account site's code in this (production) app: the same refusal, on the phone.
        vm.setTyped(typedLocator + typedCode + "J5JH")
        assertNull("editing clears the notice", vm.uiState.value.typedProblem)
        vm.submitTyped()
        advanceUntilIdle()
        assertEquals(TypedProblem.BAD_CHECK, vm.uiState.value.typedProblem)
        // Nothing reached the repository (so neither the member API nor the vault), and no wrong code was counted.
        assertEquals(0, registered())
        assertEquals(0, registered("registerRecovery"))
        assertNull(vault.lastTyped)
        assertEquals(0, vm.uiState.value.wrongCodes)
        assertNull(vm.uiState.value.refusal)
        assertNull(vm.uiState.value.error)
        // Corrected, it goes out.
        vm.setTyped(typed)
        vm.submitTyped()
        advanceUntilIdle()
        assertEquals(1, registered())
        assertEquals(RecoverStep.PIN, vm.uiState.value.step)
    }

    @Test
    fun wrongLengthsAreNotSent() = runTest {
        val vm = typing()
        vm.submitTyped()
        assertEquals(TypedProblem.INCOMPLETE, vm.uiState.value.typedProblem)
        vm.setTyped(typed.dropLast(1))
        assertNull(vm.uiState.value.typedProblem)
        assertFalse(vm.uiState.value.typedComplete)
        vm.submitTyped()
        assertEquals(TypedProblem.INCOMPLETE, vm.uiState.value.typedProblem)
        // The bare 32 characters of an account page from before MEMBER-API 2.4.0: said at the paste, and at Continue.
        vm.setTyped("")
        vm.setTyped(RecoveryCodes.grouped(typedCode))
        assertEquals(TypedProblem.SHORT_CODE, vm.uiState.value.typedProblem)
        vm.submitTyped()
        assertEquals(TypedProblem.SHORT_CODE, vm.uiState.value.typedProblem)
        // Typing through the 32nd character is not a paste.
        vm.setTyped(typed.take(31))
        vm.setTyped(typed.take(32))
        assertNull(vm.uiState.value.typedProblem)
        advanceUntilIdle()
        assertEquals(0, registered())
    }

    @Test
    fun typedRefusalsShowTheScansTextsAndReturnToTheEntry() = runTest {
        val vm = typing()
        vm.setTyped(typed)
        for ((c, r) in listOf(
            // The claim's 404 (any miss) and 409.
            "no_recovery" to CodeRefusal.GONE, "not_available" to CodeRefusal.NOT_AVAILABLE,
            // The vault's answers.
            "too_early" to CodeRefusal.TOO_EARLY, "attestation" to CodeRefusal.ATTESTATION, "retry" to CodeRefusal.RETRY,
        )) {
            vault.registrations.add(RecoveryRegistration.Refused(c))
            vm.submitTyped()
            advanceUntilIdle()
            assertEquals(c, r, vm.uiState.value.refusal)
            assertEquals(c, RecoverStep.TYPE, vm.uiState.value.step)
            assertEquals("$c: kept for correction", typed, vm.uiState.value.typed)
            assertFalse(vm.uiState.value.busy)
        }
        // The recovery has ended: the characters go.
        for ((c, r) in listOf("expired" to CodeRefusal.EXPIRED, "used" to CodeRefusal.GONE)) {
            vm.setTyped(typed)
            vault.registrations.add(RecoveryRegistration.Refused(c))
            vm.submitTyped()
            advanceUntilIdle()
            assertEquals(c, r, vm.uiState.value.refusal)
            assertEquals(c, RecoverStep.TYPE, vm.uiState.value.step)
            assertEquals(c, "", vm.uiState.value.typed)
        }
        vm.setTyped(typed)
        vault.registrations.add(RecoveryRegistration.Refused("no_backup"))
        vm.submitTyped()
        advanceUntilIdle()
        assertEquals(RecoverStep.NO_BACKUP, vm.uiState.value.step)
        assertEquals("", vm.uiState.value.typed)
    }

    @Test
    fun wrongTypedCodesAreCountedWithScannedOnesOfTheSameRecovery() = runTest {
        repeat(5) { vault.registrations.add(RecoveryRegistration.Refused("bad_code")) }
        // The claim by locator names the recovery the QR names.
        vault.typedRecoveryId = rid
        val vm = typing()
        vm.setTyped(typed)
        for (i in 1..2) {
            vm.submitTyped()
            advanceUntilIdle()
            assertEquals(RecoverStep.TYPE, vm.uiState.value.step)
            assertEquals(CodeRefusal.BAD_CODE, vm.uiState.value.refusal)
            assertEquals(i, vm.uiState.value.wrongCodes)
            assertEquals(typed, vm.uiState.value.typed)
        }
        // The same recovery's QR, scanned: the count goes on.
        vm.scan()
        vm.scanned(qr)
        advanceUntilIdle()
        assertEquals(CodeRefusal.BAD_CODE, vm.uiState.value.refusal)
        assertEquals(3, vm.uiState.value.wrongCodes)
        vm.type()
        assertEquals("the entry is still there after a look at the scanner", typed, vm.uiState.value.typed)
        vm.submitTyped()
        advanceUntilIdle()
        assertEquals(4, vm.uiState.value.wrongCodes)
        vm.submitTyped()
        advanceUntilIdle()
        assertEquals(CodeRefusal.VOIDED, vm.uiState.value.refusal)
        assertEquals("", vm.uiState.value.typed)
    }

    @Test
    fun aRateLimitedClaimSaysHowLongToWait() = runTest {
        val vm = typing()
        vm.setTyped(typed)
        vault.fail["registerTypedRecovery"] = FakeVault.failure(FailureKind.RATE_LIMITED, "rate_limited", 754)
        vm.submitTyped()
        advanceTimeBy(1500)
        assertEquals(RecoverStep.TYPE, vm.uiState.value.step)
        assertEquals(FailureKind.RATE_LIMITED, vm.uiState.value.error)
        assertTrue(vm.uiState.value.retrySeconds in 752..754)
        assertEquals(typed, vm.uiState.value.typed)
        assertEquals(0, vm.uiState.value.wrongCodes)
        // The wait is for the text only: another try (another network) is possible at once, and clears it.
        vm.submitTyped()
        advanceUntilIdle()
        assertEquals(RecoverStep.PIN, vm.uiState.value.step)
        assertEquals(0L, vm.uiState.value.retrySeconds)
    }

    @Test
    fun otherFailuresOfTheTypedClaimAreShownAndTheEntryKept() = runTest {
        val vm = typing()
        vm.setTyped(typed)
        for (kind in listOf(
            FailureKind.NETWORK, FailureKind.UNAUTHORIZED, FailureKind.VAULT_UNAVAILABLE, FailureKind.SERVICE_PAUSED,
            FailureKind.NO_RESPONSE, FailureKind.OTHER, FailureKind.RATE_LIMITED,
        )) {
            vault.fail["registerTypedRecovery"] = FakeVault.failure(kind)
            vm.submitTyped()
            advanceUntilIdle()
            assertEquals(kind, vm.uiState.value.error)
            assertEquals(RecoverStep.TYPE, vm.uiState.value.step)
            assertEquals(typed, vm.uiState.value.typed)
            assertEquals(0L, vm.uiState.value.retrySeconds)
            assertNull(vm.uiState.value.refusal)
        }
    }

    @Test
    fun typingAndScanningLeadToEachOtherAndBackLeavesToTheIntro() = runTest {
        val vm = RecoverViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.type()
        assertEquals(RecoverStep.TYPE, vm.uiState.value.step)
        vm.scan()
        assertEquals(RecoverStep.SCAN, vm.uiState.value.step)
        // A QR read while the typed entry is shown is not acted on, and typing while the scanner is shown changes nothing.
        vm.setTyped(typed)
        assertEquals("", vm.uiState.value.typed)
        vm.type()
        vm.scanned(qr)
        advanceUntilIdle()
        assertFalse("registerRecovery" in vault.calls)
        assertTrue(vm.back())
        assertEquals(RecoverStep.INTRO, vm.uiState.value.step)
        assertFalse(vm.back())
    }

    @Test
    fun aTypedCodeParsesOnlyWithItsCheck() {
        // What the tests above rely on: the fake is only ever handed a code whose check matched.
        assertTrue(TypedRecoveryCode.parse(typed, vault.apiOrigin) is TypedRecoveryCode.Parsed.Ok)
        assertEquals(TypedRecoveryCode.Parsed.BadCheck, TypedRecoveryCode.parse(typed.dropLast(1) + "2", vault.apiOrigin))
    }
}
