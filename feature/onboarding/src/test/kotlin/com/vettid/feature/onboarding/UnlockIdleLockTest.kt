package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.IdleLocked
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
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
import java.time.Instant

/** Release L (VAULT-MESSAGING 0.29.0 §3.6.8): the unlock screen after the vault locked itself. */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockIdleLockTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Locked)

    /** Release L (§3.6.8): the vault locked itself; the unlock and the check on this screen. */
    @Test
    fun anIdleLockIsSaidAndTheCheckAsked() = runTest {
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.idleLocked)
        vault.idleLocked.value = IdleLocked(604_800)
        advanceUntilIdle()
        assertEquals(IdleLocked(604_800), vm.uiState.value.idleLocked)
        assertTrue("the password with the PIN", vm.uiState.value.checkDue)
        assertFalse(vm.uiState.value.lockedByOwnerCheck)
    }

    /** Without the notice (the phone was off): a time past the `idle_lock_at` last read says why. */
    @Test
    fun withoutTheNoticeTheTimeLastReadSaysWhy() = runTest {
        val deadline = Instant.now().minusSeconds(8 * 86_400L)
        vault.ownerCheck.value = OwnerCheckView(
            OwnerCheckState.HELD, deadline, 86_400, 0, hold = true, holdOffUntil = null,
            idleLockAt = deadline.plusSeconds(604_800),
        )
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertEquals(IdleLocked(604_800), vm.uiState.value.idleLocked)
    }
}
