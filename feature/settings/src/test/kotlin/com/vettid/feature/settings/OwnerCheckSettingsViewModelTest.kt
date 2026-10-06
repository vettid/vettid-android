package com.vettid.feature.settings

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
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

/** The interval and the hold switch (VAULT-MESSAGING §3.6.2, §3.6.7). */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerCheckSettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Unlocked).apply {
        ownerCheck.value = OwnerCheckView(
            OwnerCheckState.OK, Instant.now().plusSeconds(20_000), 86_400, 0, hold = false, holdOffUntil = null,
        )
    }

    @Test
    fun shorteningOffersACheck() = runTest {
        val vm = OwnerCheckSettingsViewModel(vault)
        advanceUntilIdle()
        vm.setIntervalHours(4)
        advanceUntilIdle()
        assertEquals(14_400L, vault.lastInterval)
        assertTrue(vm.uiState.value.offerCheck)
        vm.dismissOffer()
        // Lengthening takes effect at the next check: nothing to offer.
        vm.setIntervalHours(24)
        advanceUntilIdle()
        assertEquals(86_400L, vault.lastInterval)
        assertFalse(vm.uiState.value.offerCheck)
    }

    @Test
    fun turningTheHoldOnNeedsNoCheck() = runTest {
        val vm = OwnerCheckSettingsViewModel(vault)
        vm.turnHoldOn()
        advanceUntilIdle()
        assertTrue("turnHoldOn" in vault.calls)
        assertFalse("check" in vault.calls)
        assertTrue(vm.uiState.value.view!!.hold)
    }

    @Test
    fun aRefusalIsShown() = runTest {
        vault.fail["setCheckInterval"] = FakeVault.failure(FailureKind.OWNER_CHECK_REQUIRED, "owner_check_required")
        val vm = OwnerCheckSettingsViewModel(vault)
        vm.setIntervalHours(2)
        advanceUntilIdle()
        assertEquals(FailureKind.OWNER_CHECK_REQUIRED, vm.uiState.value.error)
    }
}
