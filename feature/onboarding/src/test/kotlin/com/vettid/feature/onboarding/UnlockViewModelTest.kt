package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckOutcome
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.ReleaseNotes
import com.vettid.core.data.vault.UnlockAttempt
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeReleaseNotes
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class UnlockViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Locked)

    @Test
    fun unlocksWithThePin() = runTest {
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pinAllowed)
        vm.setPin("4028 1795")
        assertEquals("40281795", vm.uiState.value.pin)
        vm.submit()
        advanceUntilIdle()
        assertEquals(AppPhase.Unlocked, vault.phase.value)
        assertEquals("", vm.uiState.value.pin)
        assertNull(vault.lastApproved)
    }

    /**
     * Release R (§11.14.10): on the old phone after a transfer's approval, an unlock its own keys cannot make is "The move
     * didn't finish" failing ("Contact VettID within a day"), never the "start a recovery" text; a network failure is
     * said as that.
     */
    @Test
    fun theMoveBackFailingSaysContactVettId() = runTest {
        vault.transferKeptSince.value = Instant.parse("2026-10-16T10:00:00Z")
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.keptTransfer)
        vault.unlockResults.add(UnlockAttempt.Failed(FailureKind.DEVICE_SECRET, "device_secret"))
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertEquals(UnlockMessage.MoveBackFailed, vm.uiState.value.message)
        vault.unlockResults.add(UnlockAttempt.Failed(FailureKind.NETWORK, null))
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertEquals(UnlockMessage.Failed(FailureKind.NETWORK, null), vm.uiState.value.message)
        // Without a kept transfer the failure is the usual one.
        assertEquals(
            UnlockMessage.Failed(FailureKind.DEVICE_SECRET, "device_secret"),
            unlockFailedMessage(UnlockAttempt.Failed(FailureKind.DEVICE_SECRET, "device_secret"), keptTransfer = false),
        )
    }

    private fun view(state: OwnerCheckState, deadline: Instant) =
        OwnerCheckView(state, deadline, 86_400, 0, hold = true, holdOffUntil = null)

    /** A locked vault past its owner-check deadline: PIN and password on one screen, the check after the unlock (§3.6.5). */
    @Test
    fun pastTheDeadlineAsksForThePasswordWithThePin() = runTest {
        vault.ownerCheck.value = view(OwnerCheckState.HELD, Instant.now().minusSeconds(60))
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.checkDue)
        vm.setPin("40281795")
        assertFalse(vm.uiState.value.submitAllowed) // the password too
        vm.setPassword("correct horse")
        vm.submit()
        advanceUntilIdle()
        assertTrue("unlockWithCheck" in vault.calls)
        assertEquals("correct horse", vault.lastPassword)
        assertEquals("", vm.uiState.value.password)
        assertEquals(OwnerCheckOutcome.Passed, vault.unlockCheckOutcome.value)
    }

    @Test
    fun beforeTheDeadlineOnlyThePin() = runTest {
        vault.ownerCheck.value = view(OwnerCheckState.OK, Instant.now().plusSeconds(3_600))
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertFalse(vm.uiState.value.checkDue)
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertFalse("unlockWithCheck" in vault.calls)
        assertTrue("unlock" in vault.calls)
    }

    private suspend fun TestScope.dueVm(): UnlockViewModel {
        vault.ownerCheck.value = view(OwnerCheckState.HELD, Instant.now().minusSeconds(60))
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        return vm
    }

    private fun UnlockViewModel.checkIn(pin: String, password: String) {
        setPin(pin)
        setPassword(password)
        submit()
    }

    /** (ANDROID-PLAN 0.1.93) No saved owner-check state (a new install, a transfer, a recovery): the PIN only. */
    @Test
    fun withoutSavedStateOnlyThePin() = runTest {
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertFalse(vm.uiState.value.checkDue)
        vm.setPin("40281795")
        assertTrue(vm.uiState.value.submitAllowed)
    }

    /** The PIN is right and the password wrong: the same screen says so, with the tries left, and asks only for the password. */
    @Test
    fun aWrongPasswordAfterTheUnlockStaysHereAndAsksOnlyForThePassword() = runTest {
        val vm = dueVm()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(7))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.checkRetry)
        assertEquals(UnlockMessage.CheckPasswordWrong(7), s.message)
        assertEquals("", s.pin)
        assertEquals("", s.password)
        assertEquals(AppPhase.Locked, vault.phase.value) // no Daily check screen: the app has not opened
        assertNull(vault.unlockCheckOutcome.value) // nothing parked for the Daily check screen
        assertFalse(s.submitAllowed) // the password first
        // The next try sends the kept PIN with the new password; a pass opens the app.
        vm.setPassword("correct horse")
        assertTrue(vm.uiState.value.submitAllowed)
        vm.submit()
        advanceUntilIdle()
        assertEquals("40281795" to "correct horse", vault.lastHeldCheck)
        assertEquals(AppPhase.Unlocked, vault.phase.value)
        assertFalse(vm.uiState.value.checkRetry)
    }

    @Test
    fun anotherWrongPasswordCountsDownTheTries() = runTest {
        val vm = dueVm()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(7))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(6))
        vm.setPassword("still no")
        vm.submit()
        advanceUntilIdle()
        assertEquals(UnlockMessage.CheckPasswordWrong(6), vm.uiState.value.message)
        assertTrue(vm.uiState.value.checkRetry)
    }

    /** The tenth failure locks the vault: the locked screen with the owner-check lock texts, not a retry. */
    @Test
    fun theTenthWrongPasswordShowsTheLockedScreen() = runTest {
        val vm = dueVm()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(0))
        vault.lockedByOwnerCheck.value = true // vault.locking{reason: owner_check}
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        val s = vm.uiState.value
        assertFalse(s.checkRetry)
        assertTrue(s.lockedByOwnerCheck)
        assertTrue(s.checkDue)
    }

    /** A failure that locks the vault while the retry shows ends the retry. */
    @Test
    fun aLockWhileRetryingEndsTheRetry() = runTest {
        val vm = dueVm()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(1))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.checkRetry)
        vault.lockedByOwnerCheck.value = true
        advanceUntilIdle()
        assertFalse(vm.uiState.value.checkRetry)
    }

    /** A backoff used no try: the vault is open, the countdown runs, and "Check in" is off until it ends. */
    @Test
    fun aBackoffShowsTheCountdownAndKeepsCheckInOff() = runTest {
        val vm = dueVm()
        vault.checkResults.add(OwnerCheckOutcome.Backoff(30))
        vm.checkIn("40281795", "pw")
        advanceTimeBy(2_000)
        var s = vm.uiState.value
        assertTrue(s.checkRetry)
        assertNull(s.message)
        assertTrue(s.checkWaitSeconds in 1..30)
        vm.setPassword("pw")
        assertFalse(vm.uiState.value.submitAllowed)
        advanceTimeBy(31_000)
        s = vm.uiState.value
        assertEquals(0L, s.checkWaitSeconds)
        assertTrue(s.submitAllowed)
    }

    /** A wrong PIN is only the PIN error: no check is sent. */
    @Test
    fun aWrongPinSendsNoCheck() = runTest {
        val vm = dueVm()
        vault.unlockResults.add(UnlockAttempt.BadPin(0))
        vm.checkIn("40281795", "pw")
        advanceUntilIdle()
        assertEquals(UnlockMessage.BadPin, vm.uiState.value.message)
        assertFalse(vm.uiState.value.checkRetry)
        assertFalse("check" in vault.calls)
        assertNull(vault.unlockCheckOutcome.value)
    }

    /** The accepted PIN is forgotten when the app leaves the foreground: both are asked again. */
    @Test
    fun leavingTheForegroundForgetsThePin() = runTest {
        val vm = dueVm()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(7))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        vm.appLeft()
        val s = vm.uiState.value
        assertFalse(s.checkRetry)
        assertTrue(s.checkDue)
        assertEquals("", s.pin)
        vm.setPassword("pw")
        vm.submit() // no PIN: nothing is sent
        advanceUntilIdle()
        assertNull(vault.lastHeldCheck)
    }

    @Test
    fun tenFailedChecksShowTheLock() = runTest {
        vault.lockedByOwnerCheck.value = true
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.lockedByOwnerCheck)
        assertTrue(vm.uiState.value.checkDue)
    }

    @Test
    fun softwareUpdatedMustBeAcknowledgedBeforeThePin() = runTest {
        vault.preflightInfo = PreflightInfo(FakeVault.release(4), 3, softwareUpdated = true, rollback = false, offer = null)
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertFalse("unlock" in vault.calls)
        vm.acknowledgeUpdate()
        vm.submit()
        advanceUntilIdle()
        assertTrue("unlock" in vault.calls)
    }

    @Test
    fun rollbackNeverSendsThePin() = runTest {
        vault.preflightInfo = PreflightInfo(FakeVault.release(2), 3, softwareUpdated = false, rollback = true, offer = null)
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.pinAllowed)
        assertFalse("unlock" in vault.calls)
    }

    @Test
    fun approvedOfferTravelsWithTheUnlock() = runTest {
        val offer = FakeVault.release(5)
        vault.preflightInfo = PreflightInfo(FakeVault.release(4, "deprecated"), 4, false, false, offer)
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setApproveOffer(true)
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertEquals(offer, vault.lastApproved)
    }

    @Test
    fun whatsNewIsLookedUpForTheOfferOnly() = runTest {
        val notes = FakeReleaseNotes(ReleaseNotes.Unavailable)
        UnlockViewModel(vault, vault, vault, notes)
        advanceUntilIdle()
        assertTrue(notes.asked.isEmpty()) // no offer
        vault.preflightInfo = PreflightInfo(FakeVault.release(4), 4, false, false, FakeVault.release(6), listOf(FakeVault.release(5)))
        val vm = UnlockViewModel(vault, vault, vault, notes)
        advanceUntilIdle()
        assertEquals(listOf(6L), notes.asked)
        assertEquals(ReleaseNotes.Unavailable, vm.uiState.value.notes)
        // An older release: the PIN is not sent, and nothing is offered.
        vault.preflightInfo = PreflightInfo(FakeVault.release(2), 4, false, true, FakeVault.release(6))
        UnlockViewModel(vault, vault, vault, notes)
        advanceUntilIdle()
        assertEquals(listOf(6L), notes.asked)
    }

    @Test
    fun badPinStartsTheBackoffCountdown() = runTest {
        vault.unlockResults += UnlockAttempt.BadPin(retryAfterSeconds = 30)
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("11112222")
        vm.submit()
        advanceTimeBy(1)
        assertEquals(UnlockMessage.BadPin, vm.uiState.value.message)
        assertEquals(30L, vm.uiState.value.waitSeconds)
        assertFalse(vm.uiState.value.pinAllowed)
        advanceTimeBy(10_500)
        assertEquals(20L, vm.uiState.value.waitSeconds)
        advanceUntilIdle()
        assertEquals(0L, vm.uiState.value.waitSeconds)
        assertTrue(vm.uiState.value.pinAllowed)
    }

    /** Release R: a "Finish the move" that did not open the vault says why on the unlock screen it leads to. */
    @Test
    fun aFailedFinishTheMoveIsShownHere() = runTest {
        vault.finishMoveFailure.value = FailureKind.TRANSFER_INCOMPLETE
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertEquals(FailureKind.TRANSFER_INCOMPLETE, vm.uiState.value.moveFailure)
        vm.dismissMoveFailure()
        advanceUntilIdle()
        assertNull(vm.uiState.value.moveFailure)
    }

    @Test
    fun recoveryPendingThenCancelAndUnlock() = runTest {
        vault.unlockResults += UnlockAttempt.RecoveryPending
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.recoveryPending)
        assertFalse(vault.lastCancelRecovery)
        vm.cancelRecoveryAndUnlock()
        advanceUntilIdle()
        assertTrue(vault.lastCancelRecovery)
        assertEquals(AppPhase.Unlocked, vault.phase.value)
    }

    /** Release R (ANDROID-PLAN "Other screens"): a cancel from the app asks for the password and sends the owner check with it. */
    @Test
    fun releaseRCancelSendsTheOwnerCheck() = runTest {
        vault.phoneSecret = true
        vault.unlockResults += UnlockAttempt.RecoveryPending
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.recoveryPending)
        assertFalse("the password first", vm.uiState.value.cancelAllowed)
        vm.cancelRecoveryAndUnlock()
        advanceUntilIdle()
        assertFalse(vault.lastCancelRecovery)
        vm.setPassword("the password")
        assertTrue(vm.uiState.value.cancelAllowed)
        vm.cancelRecoveryAndUnlock()
        advanceUntilIdle()
        assertTrue(vault.lastCancelRecovery)
        assertEquals("the password", vault.lastCancelCheckPassword)
        assertEquals("", vm.uiState.value.password)
        assertEquals(AppPhase.Unlocked, vault.phase.value)
    }

    /**
     * VAULT-MESSAGING 0.27.0 §11.7.1: `signer_refused` is a state of its own. Not a wrong PIN: no message under the
     * field, no countdown, the field stays usable; the same answer for any PIN keeps the notice, and nothing offers
     * to erase the phone (the vault knows it).
     */
    @Test
    fun signerRefusedIsItsOwnStateNotAWrongPin() = runTest {
        vault.unlockResults += UnlockAttempt.SignerRefused
        vault.unlockResults += UnlockAttempt.SignerRefused
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        var s = vm.uiState.value
        assertTrue(s.signerRefused)
        assertNull(s.message)
        assertEquals(0L, s.waitSeconds)
        assertEquals("", s.pin)
        assertFalse(s.busy)
        assertTrue(s.pinAllowed)
        assertFalse(s.notRecognised)
        assertFalse(s.recoveryPending)
        assertEquals(AppPhase.Locked, vault.phase.value)
        // The help opens and closes; a different PIN gets the same answer and the same state.
        vm.showSignerHelp(true)
        assertTrue(vm.uiState.value.signerHelp)
        vm.showSignerHelp(false)
        assertFalse(vm.uiState.value.signerHelp)
        vm.setPin("11112222")
        vm.submit()
        advanceUntilIdle()
        s = vm.uiState.value
        assertTrue(s.signerRefused)
        assertNull(s.message)
        assertEquals(0L, s.waitSeconds)
        vm.askErase()
        assertFalse(vm.uiState.value.eraseConfirm)
    }

    /** The notice goes when the vault answers anything else (a release that pins the signer again), and stays through a network failure. */
    @Test
    fun signerRefusedClearsOnAnotherAnswerOfTheVault() = runTest {
        vault.unlockResults += UnlockAttempt.SignerRefused
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.NETWORK, null)
        vault.unlockResults += UnlockAttempt.BadPin(retryAfterSeconds = 0)
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.showSignerHelp(true) // nothing to help with yet
        assertFalse(vm.uiState.value.signerHelp)
        for (expected in listOf(true, true, false)) {
            vm.setPin("40281795")
            vm.submit()
            advanceUntilIdle()
            assertEquals(expected, vm.uiState.value.signerRefused)
        }
        assertEquals(UnlockMessage.BadPin, vm.uiState.value.message)
    }

    @Test
    fun stateRollbackAndFailures() = runTest {
        vault.unlockResults += UnlockAttempt.StateRollback
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.stateRollback)

        vault.fail["preflight"] = FakeVault.failure(FailureKind.RELEASE_ENDED)
        val ended = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertEquals(FailureKind.RELEASE_ENDED, ended.uiState.value.preflightError)
        assertFalse(ended.uiState.value.pinAllowed)
    }

    /**
     * Owner report 2026-10-11: the vault's release is not in the app's manifest, the manifest could not be loaded,
     * or the enclave's document did not verify. Each is its own failure (never the phone's attestation), no PIN is
     * asked for or sent, and "Try again" checks again.
     */
    @Test
    fun noPinWhileTheVaultsSoftwareIsNotVerified() = runTest {
        val kinds = listOf(
            FailureKind.RELEASE_UNKNOWN, FailureKind.MANIFEST_UNAVAILABLE, FailureKind.MANIFEST, FailureKind.ENCLAVE, FailureKind.NETWORK,
        )
        for (kind in kinds) {
            val v = FakeVault(AppPhase.Locked)
            v.fail["preflight"] = FakeVault.failure(kind, "enclave")
            val vm = UnlockViewModel(v, v, v, FakeReleaseNotes())
            advanceUntilIdle()
            val s = vm.uiState.value
            assertEquals(kind, s.preflightError)
            assertNull("$kind", s.preflight)
            assertFalse("$kind: no PIN is asked for", s.pinAllowed)
            assertTrue("$kind: Try again is offered", s.retryAllowed)
            vm.setPin("40281795")
            assertFalse("$kind", vm.uiState.value.submitAllowed)
            vm.submit()
            vm.cancelRecoveryAndUnlock()
            advanceUntilIdle()
            assertTrue("$kind: no PIN was sent: ${v.calls}", v.calls.none { it.startsWith("unlock") })
            assertEquals(AppPhase.Locked, v.phase.value)
            // "Try again" loads the release list and checks again; once it verifies, the PIN is asked for.
            v.fail.remove("preflight")
            vm.retryPreflight()
            advanceUntilIdle()
            assertNull("$kind", vm.uiState.value.preflightError)
            assertEquals(2, v.calls.count { it == "preflight" })
            assertTrue("$kind", vm.uiState.value.pinAllowed)
        }
    }

    @Test
    fun aCheckThatFailsAfterOneThatPassedTakesThePinAway() = runTest {
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pinAllowed)
        vault.fail["preflight"] = FakeVault.failure(FailureKind.ENCLAVE, "enclave")
        vm.retryPreflight()
        advanceUntilIdle()
        assertEquals(FailureKind.ENCLAVE, vm.uiState.value.preflightError)
        assertNull("the earlier check does not count", vm.uiState.value.preflight)
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.pinAllowed)
        assertTrue(vault.calls.none { it.startsWith("unlock") })
    }

    // --- "Erase VettID from this phone" (owner decision, 2026-10-05) ---

    private suspend fun TestScope.unrecognised(): UnlockViewModel {
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.OTHER, UnlockViewModel.CODE_UNREADABLE)
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        return vm
    }

    @Test
    fun anUnreadableResultOffersTheErase() = runTest {
        val vm = unrecognised()
        assertTrue(vm.uiState.value.notRecognised)
        assertFalse(vm.uiState.value.eraseConfirm)
        // Typing a PIN again keeps the offer; the vault has not answered anything new.
        vm.setPin("1")
        assertTrue(vm.uiState.value.notRecognised)
        assertFalse("eraseThisPhone" in vault.calls)
    }

    @Test
    fun confirmErasesOnceThenTheWelcomeScreen() = runTest {
        val vm = unrecognised()
        vm.askErase()
        assertTrue(vm.uiState.value.eraseConfirm)
        assertFalse("nothing before the confirmation", "eraseThisPhone" in vault.calls)
        vm.confirmErase()
        vm.confirmErase() // a double tap
        advanceUntilIdle()
        assertEquals(1, vault.calls.count { it == "eraseThisPhone" })
        assertTrue(vm.uiState.value.erasing)
        assertFalse(vm.uiState.value.eraseConfirm)
        assertEquals(AppPhase.SignedOut, vault.phase.value)
        assertNull(vault.account.value)
    }

    @Test
    fun tryAgainClosesAnOpenEraseConfirmation() = runTest {
        val vm = unrecognised()
        vm.askErase()
        assertTrue(vm.uiState.value.eraseConfirm)
        vm.retryPreflight()
        assertFalse("closed while the release check runs", vm.uiState.value.eraseConfirm)
        advanceUntilIdle()
        assertFalse("still closed after it", vm.uiState.value.eraseConfirm)
        assertTrue("the offer itself stays", vm.uiState.value.eraseOffered)
        vm.confirmErase() // a stale confirm from the closed dialog
        advanceUntilIdle()
        assertFalse("eraseThisPhone" in vault.calls)
    }

    @Test
    fun cancelErasesNothing() = runTest {
        val vm = unrecognised()
        vm.askErase()
        vm.dismissErase()
        assertFalse(vm.uiState.value.eraseConfirm)
        vm.confirmErase() // a stale confirm after the dialog closed
        advanceUntilIdle()
        assertFalse("eraseThisPhone" in vault.calls)
        assertEquals(AppPhase.Locked, vault.phase.value)
        assertTrue(vm.uiState.value.notRecognised)
    }

    @Test
    fun noEraseWhereTheVaultKnowsThisPhone() = runTest {
        // Before any unlock, after a wrong PIN, after a network failure, after a rollback refusal: no offer.
        val outcomes = listOf(
            UnlockAttempt.BadPin(retryAfterSeconds = 0),
            UnlockAttempt.Failed(FailureKind.NETWORK, null),
            UnlockAttempt.Failed(FailureKind.OTHER, "unknown_device"),
            UnlockAttempt.StateRollback,
            UnlockAttempt.RecoveryPending,
        )
        for (o in outcomes) {
            vault.unlockResults += o
            val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
            advanceUntilIdle()
            assertFalse(vm.uiState.value.notRecognised)
            vm.setPin("40281795")
            vm.submit()
            advanceUntilIdle()
            assertFalse("$o", vm.uiState.value.notRecognised)
            vm.askErase()
            vm.confirmErase()
            advanceUntilIdle()
            assertFalse("$o", vm.uiState.value.eraseConfirm)
        }
        assertFalse("eraseThisPhone" in vault.calls)
    }

    /**
     * A6 audit N4/N5: a `401` that the phone's clock explains (the member API client reports it as its own failure
     * after a freshly signed retry) says to check the date and time; it never offers to erase the phone.
     */
    @Test
    fun aRefusalTheClockExplainsOffersNoErase() = runTest {
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.CLOCK, "clock_skew")
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.notRecognised)
        assertEquals(UnlockMessage.Failed(FailureKind.CLOCK, "clock_skew"), vm.uiState.value.message)
        vm.askErase()
        assertFalse(vm.uiState.value.eraseConfirm)
    }

    @Test
    fun aRecognisedAnswerWithdrawsTheOffer() = runTest {
        val vm = unrecognised()
        // A network failure says nothing either way: the offer stays.
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.NETWORK, null)
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.notRecognised)
        // A sealed bad_pin: the vault knows this phone after all.
        vault.unlockResults += UnlockAttempt.BadPin(retryAfterSeconds = 0)
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.notRecognised)
        vm.askErase()
        assertFalse(vm.uiState.value.eraseConfirm)
    }

    // --- a phone sent here by repeated relay refusals (RefusalWatch) ---

    @Test
    fun aRefusedPhoneIsOfferedTheEraseAfterAnUnreadableUnlock() = runTest {
        vault.refusedByVault.value = true
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.refused)
        assertFalse("the refusals alone offer nothing", vm.uiState.value.notRecognised)
        vm.askErase()
        assertFalse(vm.uiState.value.eraseConfirm)
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.OTHER, UnlockViewModel.CODE_UNREADABLE)
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.notRecognised)
        assertFalse("never erased without the member", "eraseThisPhone" in vault.calls)
        assertEquals(AppPhase.Locked, vault.phase.value)
        vm.askErase()
        vm.confirmErase()
        advanceUntilIdle()
        assertEquals(1, vault.calls.count { it == "eraseThisPhone" })
    }

    @Test
    fun aRefusedPhoneThatTheVaultKnowsIsNotOfferedTheErase() = runTest {
        // A working phone whose token errors were passing: its PIN gets a sealed answer.
        vault.refusedByVault.value = true
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vault.unlockResults += UnlockAttempt.BadPin(retryAfterSeconds = 0)
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.refused)
        assertFalse(vm.uiState.value.notRecognised)
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertEquals(AppPhase.Unlocked, vault.phase.value)
        assertFalse("eraseThisPhone" in vault.calls)
    }

    @Test
    fun withoutRefusalsTheUnlockScreenSaysNothingOfThem() = runTest {
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertFalse(vm.uiState.value.refused)
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.NETWORK, null)
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.refused)
        assertFalse(vm.uiState.value.notRecognised)
    }

    @Test
    fun aPausedServiceWaitsForRetryAfterAndNeverOffersTheErase() = runTest {
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.SERVICE_PAUSED, "vault_unavailable", retryAfterSeconds = 300)
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceTimeBy(1)
        val s = vm.uiState.value
        assertEquals(UnlockMessage.Failed(FailureKind.SERVICE_PAUSED, "vault_unavailable"), s.message)
        assertEquals(300L, s.serviceWaitSeconds)
        assertFalse(s.notRecognised)
        assertFalse(s.pinAllowed)
        // The member's next try waits; nothing retries by itself.
        vm.setPin("40281795")
        vm.submit()
        advanceTimeBy(299_000)
        assertEquals(1, vault.calls.count { it == "unlock" })
        assertFalse(vm.uiState.value.pinAllowed)
        advanceUntilIdle()
        assertEquals(0L, vm.uiState.value.serviceWaitSeconds)
        assertTrue(vm.uiState.value.pinAllowed)
        assertEquals(1, vault.calls.count { it == "unlock" })
        vm.submit()
        advanceUntilIdle()
        assertEquals(AppPhase.Unlocked, vault.phase.value)
    }

    @Test
    fun aPausedReleaseCheckHoldsTryAgainUntilRetryAfter() = runTest {
        vault.fail["preflight"] = VaultFailure(FailureKind.SERVICE_PAUSED, "vault_unavailable", retryAfterSeconds = 300)
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceTimeBy(1)
        assertEquals(FailureKind.SERVICE_PAUSED, vm.uiState.value.preflightError)
        assertEquals(300L, vm.uiState.value.serviceWaitSeconds)
        assertFalse(vm.uiState.value.retryAllowed)
        vm.retryPreflight()
        advanceTimeBy(1_000)
        assertEquals(1, vault.calls.count { it == "preflight" })
        advanceUntilIdle()
        assertTrue(vm.uiState.value.retryAllowed)
        assertEquals(1, vault.calls.count { it == "preflight" }) // no automatic retry
        vm.retryPreflight()
        advanceUntilIdle()
        assertEquals(2, vault.calls.count { it == "preflight" })
        assertNull(vm.uiState.value.preflightError)
        assertTrue(vm.uiState.value.pinAllowed)
    }

    @Test
    fun aServiceThatIsNotThereYetHasNoWait() = runTest {
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.VAULT_UNAVAILABLE, "vault_unavailable")
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertEquals(0L, vm.uiState.value.serviceWaitSeconds)
        assertTrue(vm.uiState.value.pinAllowed)
        assertFalse(vm.uiState.value.notRecognised)
    }

    /** A lock the vault has not confirmed is said on the unlock screen, with "Try again", until it is confirmed. */
    @Test
    fun anUnconfirmedLockIsShownUntilTheVaultConfirms() = runTest {
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertEquals(com.vettid.core.data.vault.LockNotice.None, vm.uiState.value.lock)
        val pending = com.vettid.core.data.vault.LockNotice.Unconfirmed(java.time.Instant.parse("2026-10-12T10:00:00Z"), 2)
        vault.lockNotice.value = pending
        advanceUntilIdle()
        assertEquals(pending, vm.uiState.value.lock)
        vm.retryLock()
        assertTrue("retryLock" in vault.calls)
        vault.lockNotice.value = com.vettid.core.data.vault.LockNotice.ConfirmedLate
        advanceUntilIdle()
        assertEquals(com.vettid.core.data.vault.LockNotice.ConfirmedLate, vm.uiState.value.lock)
        vm.dismissLockNotice()
        advanceUntilIdle()
        assertEquals(com.vettid.core.data.vault.LockNotice.None, vm.uiState.value.lock)
    }

    /**
     * VAULT-MESSAGING 0.44.0 §11.14.9 step 5: with a PIN change unresolved the screen asks for the previous PIN; when the
     * vault shows (`device_secret`, before any PIN is tested) that the change went through it asks for the new PIN, and
     * the PIN typed first is not reused: each PIN the member types is the one the vault tests.
     */
    @Test
    fun anUnresolvedPinChangeAsksForThePreviousPinThenTheNewOne() = runTest {
        vault.pinChangeUnresolved.value = true
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.previousPinStep)
        assertFalse(vm.uiState.value.newPinStep)
        vault.unlockResults.add(UnlockAttempt.NewPinNeeded)
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertEquals("40281795", vault.lastPin)
        with(vm.uiState.value) {
            assertTrue(newPinStep)
            assertEquals("the previous PIN is not kept for the new record", "", pin)
            assertNull(message)
            assertFalse("not \"this phone no longer holds the keys\"", keysGone)
        }
        // A failure that never reached the vault keeps the step.
        vault.unlockResults.add(UnlockAttempt.Failed(FailureKind.NETWORK, null))
        vm.setPin("59173048")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.newPinStep)
        vm.setPin("59173048")
        vm.submit()
        advanceUntilIdle()
        assertEquals("59173048", vault.lastPin)
        assertEquals(3, vault.calls.count { it == "unlock" })
        assertFalse(vm.uiState.value.newPinStep)
        assertEquals(AppPhase.Unlocked, vault.phase.value)
        // Decided: the prompt is the usual one again.
        vault.pinChangeUnresolved.value = false
        advanceUntilIdle()
        assertFalse(vm.uiState.value.previousPinStep)
    }

    /** A wrong new PIN (the candidate matched, so the change is confirmed) ends the second step: the next try is as usual. */
    @Test
    fun aWrongNewPinEndsTheSecondStep() = runTest {
        vault.pinChangeUnresolved.value = true
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vault.unlockResults.add(UnlockAttempt.NewPinNeeded)
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        vault.unlockResults.add(UnlockAttempt.BadPin(0))
        vm.setPin("11112222")
        vm.submit()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.newPinStep)
        assertEquals(UnlockMessage.BadPin, vm.uiState.value.message)
    }
}
