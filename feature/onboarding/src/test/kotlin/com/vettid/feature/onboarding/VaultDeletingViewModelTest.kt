package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.DeletionCheck
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** ANDROID-PLAN 0.1.54 "A vault being deleted": "Check again" after the bound. */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultDeletingViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Deleting(slow = true))

    @Test
    fun checkAgainSaysStillOrUnreachableAndAGoneVaultLeaves() = runTest {
        val vm = VaultDeletingViewModel(vault)
        vm.setSlow(true)
        vault.deletionChecks += DeletionCheck.LISTED
        vm.checkAgain()
        advanceUntilIdle()
        assertEquals(DeletionCheck.LISTED, vm.uiState.value.lastCheck)
        vault.deletionChecks += DeletionCheck.UNREACHABLE
        vm.checkAgain()
        advanceUntilIdle()
        assertEquals(DeletionCheck.UNREACHABLE, vm.uiState.value.lastCheck)
        vault.deletionChecks += DeletionCheck.GONE
        vm.checkAgain()
        advanceUntilIdle()
        assertNull(vm.uiState.value.lastCheck)
        assertEquals(AppPhase.SignedOut, vault.phase.value)
    }

    @Test
    fun aSecondTapWaitsForTheFirst() = runTest {
        val gate = CompletableDeferred<Unit>()
        vault.deletionCheckGate = gate
        val vm = VaultDeletingViewModel(vault)
        vm.checkAgain()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.checking)
        vm.checkAgain()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, vault.calls.count { it == "checkDeletion" })
    }
}
