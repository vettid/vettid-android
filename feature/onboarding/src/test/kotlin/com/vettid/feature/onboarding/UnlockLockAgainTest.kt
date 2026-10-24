package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.LockNotice
import com.vettid.core.testing.FakeReleaseNotes
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** (Release L, owner decision 2026-10-24) "Lock it now" on "Your vault was opened again" asks for one fresh lock. */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockLockAgainTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Locked)

    @Test
    fun theButtonAsksForOneFreshLock() = runTest {
        vault.lockNotice.value = LockNotice.OpenedAgain
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertEquals(LockNotice.OpenedAgain, vm.uiState.value.lock)
        vm.lockAgain()
        advanceUntilIdle()
        assertEquals(1, vault.calls.count { it == "lockAgain" })
    }
}
