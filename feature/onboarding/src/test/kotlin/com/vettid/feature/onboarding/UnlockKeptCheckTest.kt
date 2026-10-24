package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.testing.FakeReleaseNotes
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** The old phone's unlock screen once a kept transfer's 7 days are over and the vault has not confirmed the move. */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockKeptCheckTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Locked)

    /** §11.14.10 (owner decision 2026-10-23): the end-of-keep check got no definite answer: the screen says it waits. */
    @Test
    fun anUnansweredEndOfKeepCheckIsSaid() = runTest {
        vault.transferKeptSince.value = Instant.parse("2026-10-16T10:00:00Z")
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertFalse(vm.uiState.value.keptCheckWaiting)
        vault.transferCheckWaiting.value = true
        advanceUntilIdle()
        assertTrue(vm.uiState.value.keptCheckWaiting)
        assertTrue("the move back stays offered", vm.uiState.value.keptTransfer)
        vault.transferCheckWaiting.value = false
        advanceUntilIdle()
        assertFalse(vm.uiState.value.keptCheckWaiting)
    }

    /** §11.14.10 (owner decision 2026-10-23): while the vault has not confirmed the move, no erase is offered. */
    @Test
    fun noEraseIsOfferedWhileTheMoveIsUnconfirmed() {
        val stuck = UnlockUiState(loading = false, keptTransfer = true, notRecognised = true, keysGone = true)
        assertTrue(stuck.eraseOffered)
        assertFalse(stuck.copy(keptCheckWaiting = true).eraseOffered)
    }
}
