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
