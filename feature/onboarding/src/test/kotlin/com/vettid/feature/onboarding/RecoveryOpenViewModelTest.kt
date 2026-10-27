package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.RecoveryOpenOutcome
import com.vettid.core.data.vault.RecoveryStage
import com.vettid.core.data.vault.SetupStage
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** The recovery open of release R (VAULT-MESSAGING 0.33.0 §11.14.6, ANDROID-PLAN 0.1.40). */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryOpenViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Setup(SetupStage.RECOVERING)).apply { phoneSecret = true }

    private fun RecoveryOpenViewModel.enterAndSend(pin: String = "975310", password: String = "correct horse battery") {
        setPin(pin)
        setPassword(password)
        review()
        send()
    }

    @Test
    fun okShowsTheAttemptsUsedBeforeTheAppOpens() = runTest {
        vault.recoveryOpenOutcomes.add(RecoveryOpenOutcome("ok", 2))
        val vm = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        vm.enterAndSend()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(OpenStep.DONE, s.step)
        assertEquals(2, s.outcome?.used)
        assertEquals("", s.pin)
        assertEquals("", s.password)
        // The app opens only when the member continues.
        assertFalse("finishRecoveryOpen" in vault.calls)
        vm.openApp()
        advanceUntilIdle()
        assertTrue("finishRecoveryOpen" in vault.calls)
        // A recovered phone is a new install: the open finishes setup itself, and the screen is told it opened.
        assertEquals(AppPhase.Unlocked, vault.phase.value)
        assertTrue(vault.setupComplete)
        assertTrue(vm.uiState.value.opened)
    }

    /**
     * The release-R recovery end to end over one vault, as the screens run it: onboarding at RECOVER, the recovery flow
     * at its PIN, the open answered `ok`, Continue. When the open returns without the app open (FINISHING, which the
     * onboarding ignores while it shows the recovery, as it must for the earlier path's own last screen), the recovery
     * screen's hand-over to the end of setup opens it.
     */
    @Test
    fun okThenContinueOpensTheAppFromTheRecoveryScreens() = runTest {
        vault.recoveryOpenLeavesFinishing = true
        vault.recoveryStageValue = RecoveryStage.PIN
        val onboarding = OnboardingViewModel(vault, vault, com.vettid.core.data.account.SetupLinkInbox())
        val recover = RecoverViewModel(vault, vault, vault, com.vettid.core.testing.FakeReleaseNotes())
        val open = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        assertEquals(OnboardingStep.RECOVER, onboarding.uiState.value.step)
        assertEquals(RecoverStep.PIN, recover.uiState.value.step)
        assertTrue("the release-R pane", recover.uiState.value.phoneSecret)
        vault.recoveryOpenOutcomes.add(RecoveryOpenOutcome("ok", 1))
        open.enterAndSend()
        advanceUntilIdle()
        assertEquals(OpenStep.DONE, open.uiState.value.step)
        open.openApp()
        advanceUntilIdle()
        // The phase the bug left the phone in: FINISHING, ignored under the recovery screen.
        assertEquals(AppPhase.Setup(SetupStage.FINISHING), vault.phase.value)
        assertEquals(OnboardingStep.RECOVER, onboarding.uiState.value.step)
        // What RecoveryOpenPane does once opened (MoveScreens: onOpened = the recovery flow's finish).
        assertTrue(open.uiState.value.opened)
        recover.finish()
        advanceUntilIdle()
        assertTrue("finishSetup" in vault.calls)
        assertEquals(AppPhase.Unlocked, vault.phase.value)
    }

    /** A phone whose setup was complete (a recovery on the same, set-up phone) opens at once. */
    @Test
    fun aSetUpPhoneOpensAtOnce() = runTest {
        vault.setupComplete = true
        vault.recoveryOpenOutcomes.add(RecoveryOpenOutcome("ok", 0))
        val vm = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        vm.enterAndSend()
        advanceUntilIdle()
        vm.openApp()
        advanceUntilIdle()
        assertEquals(AppPhase.Unlocked, vault.phase.value)
    }

    /**
     * A lock asked for between `ok` and Continue is pending: the phone stays locked, the app never opens, and the
     * screen says so (not a silent stop); nothing is handed over to the end of setup.
     */
    @Test
    fun aLockPendingAtContinueKeepsThePhoneLockedAndSaysSo() = runTest {
        vault.recoveryOpenOutcomes.add(RecoveryOpenOutcome("ok", 1))
        val vm = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        vm.enterAndSend()
        advanceUntilIdle()
        vault.lockPending = true
        vm.openApp()
        advanceUntilIdle()
        assertEquals(AppPhase.Locked, vault.phase.value)
        assertFalse(vault.setupComplete)
        val s = vm.uiState.value
        assertFalse(s.opened)
        assertEquals(FailureKind.NO_RESPONSE, s.failure)
        assertEquals(com.vettid.core.data.vault.MoveRepository.CODE_LOCK_PENDING, s.failureCode)
        assertEquals(OpenStep.DONE, s.step)
    }

    /** Anything else thrown at Continue (the account record's write): a failure on the screen, never the open app. */
    @Test
    fun anUnexpectedErrorAtContinueIsAFailureNotACrash() = runTest {
        val move = object : com.vettid.core.data.vault.MoveRepository by vault {
            override suspend fun finishRecoveryOpen() {
                error("the record could not be written")
            }
        }
        vault.recoveryOpenOutcomes.add(RecoveryOpenOutcome("ok", 1))
        val vm = RecoveryOpenViewModel(move, vault)
        advanceUntilIdle()
        vm.enterAndSend()
        advanceUntilIdle()
        vm.openApp()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertFalse(s.opened)
        assertEquals(FailureKind.OTHER, s.failure)
        assertEquals(com.vettid.core.data.vault.MoveRepository.CODE_NOT_OPENED, s.failureCode)
        assertEquals(AppPhase.Setup(SetupStage.RECOVERING), vault.phase.value)
    }

    /** After ok the vault removed the recovery: a failure then, or a restart, finishes with the PIN, never a new open. */
    @Test
    fun anOkWhoseRestFailedIsFinishedWithThePin() = runTest {
        vault.fail["recoveryOpen"] = VaultFailure(FailureKind.NO_RESPONSE)
        val vm = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        vault.recoveryOpenedValue = true // the vault answered ok; the handshake did not complete
        vm.enterAndSend()
        advanceUntilIdle()
        assertEquals(OpenStep.FINISH, vm.uiState.value.step)
        assertEquals(FailureKind.NO_RESPONSE, vm.uiState.value.failure)
        // The phone's own unlock with a wrong PIN: said as that, still finishing.
        vault.recoveryOpenOutcomes.add(RecoveryOpenOutcome("bad_pin", 1, opened = true))
        vm.setPin("111111")
        vm.send()
        advanceUntilIdle()
        assertEquals(OpenStep.FINISH, vm.uiState.value.step)
        assertTrue(vm.uiState.value.outcome!!.opened)
        vault.recoveryOpenOutcomes.add(RecoveryOpenOutcome("ok", 1))
        vm.setPin("975310")
        vm.send()
        advanceUntilIdle()
        assertEquals(OpenStep.DONE, vm.uiState.value.step)
        // And after a restart: straight to the PIN alone.
        val again = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        assertEquals(OpenStep.FINISH, again.uiState.value.step)
    }

    /** Finishing an ok recovery the vault no longer accepts: erase VettID from this phone, after a confirmation only. */
    @Test
    fun aVaultThatNoLongerHasThisPhoneOffersTheErase() = runTest {
        vault.recoveryOpenedValue = true
        val vm = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        assertEquals(OpenStep.FINISH, vm.uiState.value.step)
        assertFalse(vm.uiState.value.canErase)
        // A temporary failure: no erase.
        vault.fail["recoveryOpen"] = VaultFailure(FailureKind.NO_RESPONSE)
        vm.setPin("975310")
        vm.send()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canErase)
        // Its own key refused at both epochs: the vault no longer has this phone.
        vault.recoveryOpenOutcomes.add(RecoveryOpenOutcome("device_secret", 1, opened = true))
        vm.setPin("975310")
        vm.send()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canErase)
        vm.erase()
        assertFalse("eraseThisPhone" in vault.calls)
        vm.askErase()
        assertTrue(vm.uiState.value.confirmErase)
        vm.erase()
        advanceUntilIdle()
        assertTrue("eraseThisPhone" in vault.calls)
        assertEquals(AppPhase.SignedOut, vault.phase.value)
        // Answers the host or the member API can produce never offer it: random bytes, a 401, no vault.
        for (f in listOf(
            VaultFailure(FailureKind.OTHER, "unreadable_result"),
            VaultFailure(FailureKind.UNAUTHORIZED),
            VaultFailure(FailureKind.NO_VAULT),
        )) {
            val again = RecoveryOpenViewModel(vault, vault)
            advanceUntilIdle()
            vault.fail["recoveryOpen"] = f
            again.setPin("975310")
            again.send()
            advanceUntilIdle()
            assertEquals(OpenStep.FINISH, again.uiState.value.step)
            assertFalse(f.toString(), again.uiState.value.canErase)
        }
    }

    /**
     * A lost ok (0.34.0 §11.14.6): after an open with no readable answer the PIN unlocks with this phone's secret; a
     * result with the vault's bundle finishes the recovery, one without it leads back to the open.
     */
    @Test
    fun aLostOkIsResumedWithThePin() = runTest {
        vault.fail["recoveryOpen"] = VaultFailure(FailureKind.OTHER, "unreadable_result")
        val vm = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        vm.enterAndSend()
        advanceUntilIdle()
        assertEquals(OpenStep.ANSWERED, vm.uiState.value.step)
        assertEquals("unreadable_result", vm.uiState.value.failureCode)
        vm.finishLostOk()
        assertEquals(OpenStep.FINISH, vm.uiState.value.step)
        assertTrue(vm.uiState.value.lostOk)
        assertEquals("", vm.uiState.value.password)
        assertFalse(vm.uiState.value.canErase)
        // No bundle for this phone: said so, and back to the open.
        vault.lostOkOutcomes.add(RecoveryOpenOutcome("no_bundle", 2, opened = true, attemptUsed = false))
        vm.setPin("975310")
        vm.send()
        advanceUntilIdle()
        assertEquals(OpenStep.FINISH, vm.uiState.value.step)
        assertEquals("no_bundle", vm.uiState.value.outcome?.code)
        assertFalse("recoveryOpen" in vault.calls.drop(1))
        // The vault's device_secret to the resume: this phone is not in its unlock keys, said like no bundle, with the
        // way back (never "try again").
        vault.lostOkOutcomes.add(RecoveryOpenOutcome("device_secret", 2, opened = true, attemptUsed = false))
        vm.setPin("975310")
        vm.send()
        advanceUntilIdle()
        assertEquals("no_bundle", vm.uiState.value.outcome?.code)
        assertEquals(R.string.onboarding_open_lost_not_here, openAnswerText(vm.uiState.value.outcome!!, false).res)
        assertFalse(vm.uiState.value.canErase)
        // The bundle came: the recovery is finished on this phone, without a new open.
        vm.setPin("975310")
        vm.send()
        advanceUntilIdle()
        assertEquals(OpenStep.DONE, vm.uiState.value.step)
        assertEquals(3, vault.calls.count { it == "resumeLostOk" })
        assertEquals(1, vault.calls.count { it == "recoveryOpen" })
        vm.again()
        assertFalse(vm.uiState.value.lostOk)
    }

    /** "The last attempt was right but could not be completed": only after an open that got no answer. */
    @Test
    fun rightButIncompleteOnlyAfterAnUnansweredOpen() = runTest {
        val at = Instant.parse("2026-10-20T12:00:00Z")
        vault.recoveryOpenOutcomes.add(RecoveryOpenOutcome("attempt_lost", 3, at))
        val vm = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        vm.enterAndSend()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.rightButIncomplete)
        // An attempt_lost before never makes the next answer "right but incomplete".
        vault.recoveryOpenOutcomes.add(RecoveryOpenOutcome("wait", 3, at))
        vm.again()
        vm.review()
        vm.send()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.rightButIncomplete)
        // The data layer reports that the open before got no answer (kept across a restart).
        vault.recoveryOpenOutcomes.add(RecoveryOpenOutcome("wait", 4, at, afterUnanswered = true))
        vm.again()
        vm.review()
        vm.send()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.rightButIncomplete)
    }

    /** The warnings before sending also on the first send after a restart: the attempts used as last reported. */
    @Test
    fun warningsUseTheAttemptsReportedBeforeARestart() = runTest {
        vault.recoveryAttemptsUsed = 1
        val second = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        assertEquals(OpenWarning.SECOND, second.uiState.value.warning)
        vault.recoveryAttemptsUsed = 8
        assertEquals(OpenWarning.FEW_LEFT, RecoveryOpenViewModel(vault, vault).uiState.value.warning)
    }

    // --- ANDROID-PLAN 0.1.78 "A phone that could not tell" ---

    @Test
    fun aDueLostOkUnlockOpensTheLostOkStepAtOnce() = runTest {
        vault.lostOkDueValue = true // a 200 on status, or 409 key_in_use at a claim
        val vm = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        assertEquals(OpenStep.FINISH, vm.uiState.value.step)
        assertTrue(vm.uiState.value.lostOk)
    }

    @Test
    fun theLostOkUnlockStillRecoveringStaysOnTheStepAndNothingIsForgotten() = runTest {
        vault.lostOkDueValue = true
        vault.undecidedValue = true
        vault.lostOkOutcomes.add(RecoveryOpenOutcome("device_secret", 1, opened = true, attemptUsed = false, contactVettid = true))
        val vm = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        vm.setPin("975310")
        vm.send()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(OpenStep.FINISH, s.step)
        assertTrue(s.undecided) // no start over
        // The approved text, not the table's.
        assertEquals(R.string.onboarding_open_took_phone, openAnswerText(s.outcome!!, false).res)
        assertTrue(vault.calls.none { it == "abandonRecovery" })
    }

    @Test
    fun aLostOkUnlockThatNeedsAnOpenGoesBackToTheEntriesWhenTheRecoveryIsStillRegistered() = runTest {
        vault.lostOkDueValue = true
        vault.lostOkOutcomes.add(RecoveryOpenOutcome("open_again", 1, opened = true, attemptUsed = false))
        val vm = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        vm.setPin("975310")
        vm.send()
        advanceUntilIdle()
        assertEquals(OpenStep.ENTER, vm.uiState.value.step)
        assertTrue(vm.uiState.value.openAgain)
    }

    @Test
    fun aLostOkUnlockThatNeedsANewRecoveryShowsTheApprovedText() = runTest {
        vault.lostOkDueValue = true
        vault.lostOkOutcomes.add(RecoveryOpenOutcome("start_new", 1, opened = true, attemptUsed = false))
        val vm = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        vm.setPin("975310")
        vm.send()
        advanceUntilIdle()
        assertEquals(OpenStep.ANSWERED, vm.uiState.value.step)
        assertEquals(R.string.recover_could_not_tell, openAnswerText(vm.uiState.value.outcome!!, false).res)
        assertFalse(openCanTryAgain("start_new"))
    }

    @Test
    fun theLostOkStepOffersANewCodeOnlyWhenTheRuleAllowedIt() = runTest {
        vault.lostOkDueValue = true
        vault.undecidedValue = true
        vault.newCodeAllowedValue = true
        val vm = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.newCodeAllowed)
        vault.newCodeAllowedValue = false
        val other = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        assertFalse(other.uiState.value.newCodeAllowed)
    }

    @Test
    fun aNewCodeThatRegisteredTakesTheLostOkPaneBackToTheEntries() = runTest {
        vault.lostOkDueValue = true
        vault.undecidedValue = true
        vault.newCodeAllowedValue = true
        val vm = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        assertEquals(OpenStep.FINISH, vm.uiState.value.step)
        assertTrue(vm.uiState.value.newCodeAllowed)
        // The new code registered: the rule no longer owes a lost-ok unlock, and the pane is read again on return.
        vault.lostOkDueValue = false
        vault.newCodeAllowedValue = false
        vm.refresh()
        advanceUntilIdle()
        assertEquals(OpenStep.ENTER, vm.uiState.value.step)
        assertFalse(vm.uiState.value.lostOk)
        assertFalse(vm.uiState.value.newCodeAllowed)
    }

    @Test
    fun aNewCodeRefusedWithKeyInUseKeepsTheLostOkPaneWithoutTheButton() = runTest {
        vault.lostOkDueValue = true
        vault.undecidedValue = true
        vault.newCodeAllowedValue = true
        val vm = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        // `409 key_in_use`: the vault took this phone; the rule no longer allows a new code.
        vault.newCodeAllowedValue = false
        vm.refresh()
        advanceUntilIdle()
        assertEquals(OpenStep.FINISH, vm.uiState.value.step)
        assertTrue(vm.uiState.value.lostOk)
        assertFalse(vm.uiState.value.newCodeAllowed)
    }
}
