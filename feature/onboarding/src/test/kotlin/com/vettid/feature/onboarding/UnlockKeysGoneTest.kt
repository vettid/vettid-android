package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.UnlockAttempt
import com.vettid.core.testing.FakeReleaseNotes
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A phone that no longer holds the vault's keys (`device_secret`): recover-or-erase, the erase confirmed; the offer is
 * kept through typing and failures that did not reach the vault, and goes with any answer of the vault or a new release
 * check; an ended release or a rollback found meanwhile wins (no erase).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockKeysGoneTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Locked)

    /** A phone that no longer holds the vault's keys: erase-or-recover; the erase still needs the confirmation. */
    @Test
    fun aPhoneWithoutTheVaultsKeysMayBeErasedAfterTheConfirmation() = runTest {
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.DEVICE_SECRET, "device_secret")
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.keysGone)
        assertFalse(vm.uiState.value.notRecognised)
        // Typing again clears the failure's message, not the offer.
        vm.setPin("1")
        assertTrue(vm.uiState.value.keysGone)
        vm.confirmErase() // nothing without the dialog
        advanceUntilIdle()
        assertFalse("eraseThisPhone" in vault.calls)
        vm.askErase()
        assertTrue(vm.uiState.value.eraseConfirm)
        vm.confirmErase()
        advanceUntilIdle()
        assertEquals(1, vault.calls.count { it == "eraseThisPhone" })
    }

    /**
     * A phone with no usable user_guid (a move an older build completed without one) can never unlock: the same
     * recover-or-erase notice (owner decision a, 2026-11-18); the erase still needs the confirmation.
     */
    @Test
    fun aPhoneWithoutAUserGuidGetsTheRecoverOrEraseNotice() = runTest {
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.NOT_SUPPORTED, UnlockAttempt.CODE_NO_USER_GUID)
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.keysGone)
        assertTrue(vm.uiState.value.eraseOffered)
        assertEquals(UnlockMessage.NoUserGuid, vm.uiState.value.message)
        // No second try: no PIN can open the vault here (owner decision a, 2026-11-19).
        assertFalse(vm.uiState.value.pinAllowed)
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertEquals("sent once", 1, vault.calls.count { it == "unlock" })
        assertTrue(vm.uiState.value.eraseOffered)
        vm.confirmErase() // nothing without the dialog
        advanceUntilIdle()
        assertFalse("eraseThisPhone" in vault.calls)
        vm.askErase()
        assertTrue(vm.uiState.value.eraseConfirm)
        vm.confirmErase()
        advanceUntilIdle()
        assertEquals(1, vault.calls.count { it == "eraseThisPhone" })
    }

    /**
     * (Owner decision a, 2026-11-19) No usable user_guid on this phone: the screen opens on recover-or-erase, asks for no
     * PIN and sends nothing; a PIN typed is not taken.
     */
    @Test
    fun noUserGuidIsToldAtOnceAndNoPinIsAsked() = runTest {
        vault.userGuidUsable = false
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.noUserGuid)
        assertTrue(s.stuckNoticeShown)
        assertTrue(s.eraseOffered)
        assertFalse(s.pinAllowed)
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertFalse("nothing sent", vault.calls.any { it == "unlock" })
        assertEquals(AppPhase.Locked, vault.phase.value)
    }

    /**
     * An old phone that approved a move and is waiting for the vault's word (the 7 days over): no erase offer, and the
     * failure is the no-user_guid message (the screen shows its words alone), never "the move back failed".
     */
    @Test
    fun aPhoneWithoutAUserGuidIsToldWhileAKeptCheckWaits() = runTest {
        vault.transferKeptSince.value = java.time.Instant.parse("2026-11-01T00:00:00Z")
        vault.transferCheckWaiting.value = true
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.NOT_SUPPORTED, UnlockAttempt.CODE_NO_USER_GUID)
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.keptTransfer)
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.eraseOffered)
        assertEquals(UnlockMessage.NoUserGuid, vm.uiState.value.message)
    }

    /** Inside the 7 days (kept, no check waiting): the notice with its erase, and not "the move back failed" beside it. */
    @Test
    fun aKeptPhoneWithoutAUserGuidIsNotToldTheMoveBackFailed() = runTest {
        vault.transferKeptSince.value = java.time.Instant.parse("2026-11-17T00:00:00Z")
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.NOT_SUPPORTED, UnlockAttempt.CODE_NO_USER_GUID)
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.keptTransfer)
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.eraseOffered)
        assertEquals(UnlockMessage.NoUserGuid, vm.uiState.value.message)
    }

    /** Only the guard's own refusal (kind and code) is a phone without a user_guid: neither half alone offers an erase. */
    @Test
    fun theCodeOrTheKindAloneOffersNoErase() = runTest {
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.NOT_SUPPORTED, "unsupported_type")
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.OTHER, UnlockAttempt.CODE_NO_USER_GUID)
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        repeat(2) {
            vm.setPin("40281795")
            vm.submit()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.keysGone)
            assertFalse(vm.uiState.value.eraseOffered)
            assertTrue(vm.uiState.value.message is UnlockMessage.Failed)
        }
    }

    /** The offer goes with any other answer of the vault, and stays through a network failure. */
    @Test
    fun aPhoneWithoutTheKeysKeepsTheOfferUntilTheVaultAnswersOtherwise() = runTest {
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.DEVICE_SECRET, "device_secret")
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.NETWORK, null)
        vault.unlockResults += UnlockAttempt.BadPin(retryAfterSeconds = 0)
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        for (expected in listOf(true, true, false)) {
            vm.setPin("40281795")
            vm.submit()
            advanceUntilIdle()
            assertEquals(expected, vm.uiState.value.keysGone)
        }
    }

    /** Keys gone, then a release check that finds a rollback or an ended release: no erase. */
    @Test
    fun aRollbackFoundAfterKeysGoneOffersNoErase() = runTest {
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.DEVICE_SECRET, "device_secret")
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.keysGone)
        vault.fail["preflight"] = FakeVault.failure(FailureKind.RELEASE_ENDED)
        vm.retryPreflight()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.recoverOnly)
        assertFalse(vm.uiState.value.keysGone)
        assertFalse(vm.uiState.value.eraseOffered)
        vm.askErase()
        vm.confirmErase()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.eraseConfirm)
        assertFalse("eraseThisPhone" in vault.calls)
    }

    /** An answer of the vault (here an attestation refusal) clears the keys-gone offer; it did reach the vault. */
    @Test
    fun anAttestationRefusalClearsTheKeysGoneOffer() = runTest {
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.DEVICE_SECRET, "device_secret")
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.ATTESTATION, "attestation")
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        for (expected in listOf(true, false)) {
            vm.setPin("40281795")
            vm.submit()
            advanceUntilIdle()
            assertEquals(expected, vm.uiState.value.keysGone)
        }
    }

    /** An ordinary failure offers no erase. */
    @Test
    fun aNetworkFailureOffersNoErase() = runTest {
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.NETWORK, null)
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        vm.askErase()
        assertFalse(vm.uiState.value.eraseConfirm)
    }
}
