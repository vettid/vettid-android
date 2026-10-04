package com.vettid.feature.credential

import com.vettid.core.data.policy.PasswordPolicy
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.CredentialAlarm
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CredentialViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Unlocked)

    @Test
    fun loadsStatusOpensAndClosesTheWindowAndSetsTheBackup() = runTest {
        val vm = CredentialViewModel(vault)
        advanceUntilIdle()
        assertEquals(2L, vm.uiState.value.status?.version)
        vm.showWindowDialog(true)
        vm.setWindowPassword("pw")
        vm.openWindow()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.windowUntil)
        assertFalse(vm.uiState.value.windowDialog)
        vm.closeWindow()
        advanceUntilIdle()
        assertNull(vm.uiState.value.windowUntil)
        vm.setBackup(false)
        advanceUntilIdle()
        assertEquals(false, vault.lastBackup)
        assertEquals(CredentialNotice.BACKUP_OFF, vm.uiState.value.notice)
        vm.setTtl(900)
        advanceUntilIdle()
        assertEquals(900, vm.uiState.value.status?.unlockTtlSeconds)
    }

    @Test
    fun wrongPasswordKeepsTheDialogOpen() = runTest {
        val vm = CredentialViewModel(vault)
        advanceUntilIdle()
        vault.fail["openUnlockWindow"] = FakeVault.failure(FailureKind.BAD_PASSWORD, "bad_password")
        vm.showWindowDialog(true)
        vm.setWindowPassword("wrong")
        vm.openWindow()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.windowDialog)
        assertEquals(FailureKind.BAD_PASSWORD, vm.uiState.value.windowError)
        assertEquals("", vm.uiState.value.windowPassword)
    }

    @Test
    fun passwordChangeChecksTheRules() = runTest {
        val vm = ChangePasswordViewModel(vault)
        vm.setCurrent("old password 1")
        vm.setPassword("weak")
        vm.setConfirm("weak")
        vm.submit()
        assertEquals(PasswordPolicy.Problem.TOO_SHORT, vm.uiState.value.problem)
        vm.setPassword("old password 1")
        vm.submit()
        advanceUntilIdle()
        assertFalse("changePassword" in vault.calls)
        vm.setPassword("a much better passphrase here")
        vm.setConfirm("a much better passphrase here")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.done)
        assertEquals("a much better passphrase here", vault.lastPassword)
    }

    @Test
    fun alarmNotMineThenForcedRotationResolves() = runTest {
        vault.alarm.value = CredentialAlarm("01J0000000000000000000000A", CredentialAlarm.STATE_FROZEN, null, "other")
        val vm = AlarmViewModel(vault)
        advanceUntilIdle()
        assertEquals(AlarmStep.ASK, vm.uiState.value.step)
        vm.answer(mine = false)
        assertEquals(AlarmStep.CONFIRM_NOT_MINE, vm.uiState.value.step)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(AlarmStep.ROTATE, vm.uiState.value.step)
        assertTrue(vm.uiState.value.notMine)
        assertTrue(vault.alarm.value!!.rotationRequired)
        vm.setPassword("pw")
        vm.rotate()
        advanceUntilIdle()
        assertEquals(AlarmStep.RESOLVED, vm.uiState.value.step)
        assertNull(vault.alarm.value)
    }

    @Test
    fun rotationFailureIsShown() = runTest {
        val vm = RotateViewModel(vault)
        vault.fail["rotate"] = FakeVault.failure(FailureKind.CREDENTIAL_FROZEN)
        vm.setPassword("pw")
        vm.submit()
        advanceUntilIdle()
        assertEquals(FailureKind.CREDENTIAL_FROZEN, vm.uiState.value.error)
        assertFalse(vm.uiState.value.done)
    }
}
