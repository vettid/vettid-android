package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.testing.FakeReleaseNotes
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UnlockApprovalTickTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Locked)

    /** The lead's review of #327 (L2): a tick is consent to one release; another offer clears it, the very same one keeps it. */
    @Test
    fun aTickNeverCarriesOverToAnotherRelease() = runTest {
        vault.preflightInfo = PreflightInfo(FakeVault.release(4), 4, false, false, FakeVault.release(6))
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setApproveOffer(true)
        vm.retryPreflight()
        advanceUntilIdle()
        assertTrue("the same offer keeps the tick", vm.uiState.value.approveOffer)
        // The same number with another PCR0.
        vault.preflightInfo = PreflightInfo(FakeVault.release(4), 4, false, false, FakeVault.release(6).copy(pcr0 = "ff".repeat(48)))
        vm.retryPreflight()
        advanceUntilIdle()
        assertFalse("another PCR0", vm.uiState.value.approveOffer)
        vm.setApproveOffer(true)
        vault.preflightInfo = PreflightInfo(FakeVault.release(4), 4, false, false, FakeVault.release(7))
        vm.retryPreflight()
        advanceUntilIdle()
        assertFalse("another release", vm.uiState.value.approveOffer)
        vm.setApproveOffer(true)
        vault.fail["preflight"] = FakeVault.failure(FailureKind.NETWORK)
        vm.retryPreflight()
        advanceUntilIdle()
        assertFalse("a failed check", vm.uiState.value.approveOffer)
        vault.fail.remove("preflight")
        vm.retryPreflight()
        advanceUntilIdle()
        assertFalse("not brought back by the next check", vm.uiState.value.approveOffer)
    }

    /** The lead's recheck of #327: a rollback of the same offer, or no offer, clears the tick. */
    @Test
    fun aRollbackOrNoOfferClearsTheTick() = runTest {
        val offer = FakeVault.release(6)
        vault.preflightInfo = PreflightInfo(FakeVault.release(4), 4, false, false, offer)
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setApproveOffer(true)
        vault.preflightInfo = PreflightInfo(FakeVault.release(4), 4, false, true, offer)
        vm.retryPreflight()
        advanceUntilIdle()
        assertFalse("the same offer, as a rollback", vm.uiState.value.approveOffer)
        vault.preflightInfo = PreflightInfo(FakeVault.release(4), 4, false, false, offer)
        vm.retryPreflight()
        advanceUntilIdle()
        vm.setApproveOffer(true)
        vault.preflightInfo = PreflightInfo(FakeVault.release(4), 4, false, false, null)
        vm.retryPreflight()
        advanceUntilIdle()
        assertFalse("no offer", vm.uiState.value.approveOffer)
    }
}
