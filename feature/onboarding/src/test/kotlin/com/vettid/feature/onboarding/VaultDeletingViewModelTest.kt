package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.DeletionCheck
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun checkAgainSaysStillOrUnreachableAndOnlyADefiniteGoneLeaves() = runTest {
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

    @Test
    fun anUnreachableOrUnconfirmedAnswerNeverErases() = runTest {
        val vm = VaultDeletingViewModel(vault)
        vm.setSlow(true)
        for (c in listOf(DeletionCheck.UNREACHABLE, DeletionCheck.UNCONFIRMED, DeletionCheck.UNCONFIRMED)) {
            vault.deletionChecks += c
            vm.checkAgain()
            advanceUntilIdle()
        }
        assertEquals(AppPhase.Deleting(slow = true), vault.phase.value)
        assertTrue(vault.calls.none { it == "eraseThisPhone" })
    }

    @Test
    fun aConfirmedRemovalAfterTheBoundErasesAndAnUnconfirmedOneDoesNot() = runTest {
        val vm = VaultDeletingViewModel(vault)
        vm.askRemove() // before the bound: not offered
        assertFalse(vm.uiState.value.removeConfirm)
        vm.setSlow(true)
        vm.askRemove()
        assertTrue(vm.uiState.value.removeConfirm)
        vm.dismissRemove()
        advanceUntilIdle()
        assertTrue(vault.calls.none { it == "eraseThisPhone" })
        vm.askRemove()
        vm.confirmRemove()
        advanceUntilIdle()
        assertEquals(1, vault.calls.count { it == "eraseThisPhone" })
    }

    @Test
    fun noRemovalBeforeTheBound() = runTest {
        val vm = VaultDeletingViewModel(vault)
        vm.confirmRemove()
        advanceUntilIdle()
        assertTrue(vault.calls.none { it == "eraseThisPhone" })
    }

    @Test
    fun anApiOnlyStateOffersRemoveOnlyFromTheBoundAndANewChecksNeverLeaves() = runTest {
        val api = FakeVault(AppPhase.Deleting(apiOnly = true))
        val vm = VaultDeletingViewModel(api)
        vm.setApiOnly(true)
        vm.askRemove() // before the bound
        vm.confirmRemove()
        assertFalse(vm.uiState.value.removeConfirm)
        api.deletionChecks += DeletionCheck.API_ONLY
        vm.checkAgain()
        advanceUntilIdle()
        assertEquals(DeletionCheck.API_ONLY, vm.uiState.value.lastCheck)
        assertEquals(AppPhase.Deleting(apiOnly = true), api.phase.value)
        assertTrue(api.calls.none { it == "eraseThisPhone" })
        vm.setSlow(true)
        vm.askRemove()
        assertTrue(vm.uiState.value.removeConfirm)
        vm.confirmRemove()
        advanceUntilIdle()
        assertEquals(1, api.calls.count { it == "eraseThisPhone" })
    }
}
