package com.vettid.feature.settings

import com.vettid.core.data.lock.AppLock
import com.vettid.core.data.lock.AppLockKeys
import com.vettid.core.data.lock.WrappedKeyFile
import com.vettid.core.data.policy.PinPolicy
import com.vettid.core.data.prefs.InMemoryPreferencesRepository
import com.vettid.core.data.prefs.ThemePreference
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.RecoveryView
import com.vettid.core.data.vault.SetupStage
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import javax.crypto.Cipher

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Unlocked)
    private val prefs = InMemoryPreferencesRepository()
    private val noKeys = object : AppLockKeys {
        override fun create() = Unit
        override fun exists() = false
        override fun encryptCipher(): Cipher = error("unused")
        override fun decryptCipher(iv: ByteArray): Cipher = error("unused")
        override fun delete() = Unit
    }
    private val noFile = object : WrappedKeyFile {
        override fun read(): ByteArray? = null
        override fun write(b: ByteArray) = Unit
        override fun delete() = Unit
    }

    @Test
    fun themeIsPersistedAndLockWorks() = runTest {
        val vm = SettingsViewModel(vault, vault, prefs, AppLock(prefs, noKeys, noFile))
        vm.setTheme(ThemePreference.DARK)
        advanceUntilIdle()
        assertEquals(ThemePreference.DARK, prefs.current.value.theme)
        assertEquals(ThemePreference.DARK, vm.uiState.value.preferences.theme)
        vm.lockVault()
        advanceUntilIdle()
        assertEquals(AppPhase.Locked, vault.phase.value)
    }

    @Test
    fun changePinRules() = runTest {
        val vm = ChangePinViewModel(vault)
        vm.setCurrent("40281795")
        vm.setPin("111111")
        vm.setConfirm("111111")
        vm.submit()
        assertEquals(PinPolicy.Problem.REPEATED, vm.uiState.value.problem)
        vm.setPin("40281795")
        vm.submit()
        assertTrue(vm.uiState.value.same)
        vm.setPin("59173048")
        vm.setConfirm("59173049")
        vm.submit()
        assertTrue(vm.uiState.value.mismatch)
        vm.setConfirm("59173048")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.done)
        assertEquals("59173048", vault.lastPin)
    }

    @Test
    fun changePinNotSupportedIsReported() = runTest {
        vault.fail["changePin"] = FakeVault.failure(FailureKind.NOT_SUPPORTED, "unsupported_type")
        val vm = ChangePinViewModel(vault)
        vm.setCurrent("40281795"); vm.setPin("59173048"); vm.setConfirm("59173048")
        vm.submit()
        advanceUntilIdle()
        assertEquals(FailureKind.NOT_SUPPORTED, vm.uiState.value.error)
        assertEquals("", vm.uiState.value.current)
    }

    @Test
    fun deleteNeedsPhrasePinPasswordAcknowledgementAndFinalConfirmation() = runTest {
        val vm = DeleteVaultViewModel(vault)
        vm.setPhrase("Delete my vault")
        vm.setPin("40281795")
        vm.setPassword("pw")
        vm.setAcknowledged(true)
        assertFalse(vm.uiState.value.ready)
        vm.submit()
        assertTrue(vm.uiState.value.phraseWrong)
        vm.setPhrase("delete my vault")
        vm.submit()
        assertTrue(vm.uiState.value.confirming)
        assertFalse("deleteVault" in vault.calls)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(AppPhase.SignedOut, vault.phase.value)
        assertEquals("40281795", vault.lastPin)
    }

    @Test
    fun aRecoveryInProgressIsShown() = runTest {
        // MEMBER-API 2.0.0: the app reads it from the vault's status; it is cancelled on the account portal.
        vault.recoveryValue = RecoveryView("pending", "2026-10-05T00:00:00Z")
        val vm = RecoveryViewModel(vault, vault)
        advanceUntilIdle()
        assertEquals("pending", vm.uiState.value.recovery?.state)
    }
}
