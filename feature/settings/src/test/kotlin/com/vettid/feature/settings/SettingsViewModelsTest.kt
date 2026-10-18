package com.vettid.feature.settings

import com.vettid.core.data.lock.AppLock
import com.vettid.core.data.lock.AppLockKeys
import com.vettid.core.data.lock.WrappedKeyFile
import com.vettid.core.data.policy.PinPolicy
import com.vettid.core.data.prefs.InMemoryPreferencesRepository
import com.vettid.core.data.prefs.ThemePreference
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.ChangeCheck
import com.vettid.core.data.vault.ChangeKind
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.RecoveryView
import com.vettid.core.data.vault.SetupStage
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
        override fun create(method: com.vettid.core.data.prefs.AppLockMethod) = Unit
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

    private fun kotlinx.coroutines.test.TestScope.screenshots(onByDefault: Boolean = false) =
        com.vettid.core.data.lock.ScreenshotAllowance(onByDefault, backgroundScope) { testScheduler.currentTime }

    /** A6 audit S1 (owner decision 2026-10-11): off by default, on from the switch, and a local choice only. */
    private fun viewModel(allowance: com.vettid.core.data.lock.ScreenshotAllowance, lock: AppLock = AppLock(prefs, noKeys, noFile)) =
        SettingsViewModel(vault, vault, prefs, lock, vault, com.vettid.core.testing.FakeReleaseUpdates(), allowance)

    @Test
    fun allowScreenshotsIsOffUntilTurnedOnAndNeverStoredOrSentToTheVault() = runTest {
        val allowance = screenshots()
        val vm = viewModel(allowance)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.screenshotsAllowed)
        assertFalse(vm.uiState.value.screenshotsStayOn)
        val before = prefs.current.value
        vm.setScreenshotsAllowed(true)
        runCurrent()
        assertTrue(vm.uiState.value.screenshotsAllowed)
        assertTrue(allowance.allowed.value)
        // Nothing is written to the preferences and nothing is asked of the vault.
        assertEquals(before, prefs.current.value)
        assertTrue(vault.calls.toString(), vault.calls.none { it != "refreshProfile" })
        // The vault locks: the row shows it off again.
        allowance.vaultClosed()
        runCurrent()
        assertFalse(vm.uiState.value.screenshotsAllowed)
        vm.setScreenshotsAllowed(true)
        vm.setScreenshotsAllowed(false)
        runCurrent()
        assertFalse(allowance.allowed.value)
    }

    @Test
    fun aDevelopmentBuildShowsTheSwitchOn() = runTest {
        val vm = viewModel(screenshots(onByDefault = true))
        runCurrent()
        assertTrue(vm.uiState.value.screenshotsAllowed)
        assertTrue(vm.uiState.value.screenshotsStayOn)
    }

    @Test
    fun themeIsPersistedAndLockWorks() = runTest {
        val vm = viewModel(screenshots())
        vm.setTheme(ThemePreference.DARK)
        advanceUntilIdle()
        assertEquals(ThemePreference.DARK, prefs.current.value.theme)
        assertEquals(ThemePreference.DARK, vm.uiState.value.preferences.theme)
        vm.lockVault()
        advanceUntilIdle()
        assertEquals(AppPhase.Locked, vault.phase.value)
    }

    /** The app lock's method while the lock is off (owner request 2026-10-09): saved, shown, used when turned on. */
    @Test
    fun theAppLockMethodIsChosenWhileOff() = runTest {
        val lock = AppLock(prefs, noKeys, noFile)
        lock.start()
        val vm = viewModel(screenshots(), lock)
        advanceUntilIdle()
        assertEquals(com.vettid.core.data.prefs.AppLockMethod.BIOMETRICS, vm.uiState.value.appLockMethod)
        vm.chooseAppLockMethod(com.vettid.core.data.prefs.AppLockMethod.SCREEN_LOCK)
        advanceUntilIdle()
        assertEquals(com.vettid.core.data.prefs.AppLockMethod.SCREEN_LOCK, vm.uiState.value.appLockMethod)
        assertEquals(com.vettid.core.data.prefs.AppLockMethod.SCREEN_LOCK, prefs.current.value.appLockMethod)
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

    private fun ChangePinViewModel.typed() {
        setCurrent("40281795")
        setPin("59173048")
        setConfirm("59173048")
    }

    /**
     * VAULT-MESSAGING 0.44.0 §11.14.9: no answer to the PIN change is not a failure. The entries stay, the screen follows
     * VettID's check (checking, unreachable) and sends nothing meanwhile; "applied" is the usual success.
     */
    @Test
    fun aLostPinChangeAnswerKeepsTheEntriesAndFollowsTheCheck() = runTest {
        vault.fail["changePin"] = VaultFailure(FailureKind.CHANGE_CHECKING, "change_checking")
        val vm = ChangePinViewModel(vault)
        vm.typed()
        vm.submit()
        advanceUntilIdle()
        vault.changeCheck.value = ChangeCheck.Checking(ChangeKind.PIN)
        advanceUntilIdle()
        with(vm.uiState.value) {
            assertNull(error)
            assertFalse(busy)
            assertEquals("40281795", current)
            assertEquals("59173048", pin)
            assertEquals("59173048", confirm)
            assertTrue(checking)
            assertEquals(ChangeCheck.Checking(ChangeKind.PIN), check)
        }
        vm.submit()
        advanceUntilIdle()
        assertEquals("nothing sent while it is decided", 1, vault.calls.count { it == "changePin" })
        vault.changeCheck.value = ChangeCheck.Unreachable(ChangeKind.PIN)
        advanceUntilIdle()
        assertEquals(ChangeCheck.Unreachable(ChangeKind.PIN), vm.uiState.value.check)
        assertEquals("59173048", vm.uiState.value.pin)
        vault.changeCheck.value = ChangeCheck.Applied(ChangeKind.PIN)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.done)
        assertEquals(1, vault.changeCheckAcknowledged)
        assertNull(vault.changeCheck.value)
    }

    /** "Not changed": said, the entries kept, and the member may try again (a new request). */
    @Test
    fun aPinChangeThatDidNotApplyMayBeTriedAgain() = runTest {
        vault.fail["changePin"] = VaultFailure(FailureKind.CHANGE_CHECKING, "change_checking")
        val vm = ChangePinViewModel(vault)
        vm.typed()
        vm.submit()
        advanceUntilIdle()
        vault.changeCheck.value = ChangeCheck.NotApplied(ChangeKind.PIN)
        advanceUntilIdle()
        assertEquals(ChangeCheck.NotApplied(ChangeKind.PIN), vm.uiState.value.check)
        assertFalse(vm.uiState.value.checking)
        assertEquals("40281795", vm.uiState.value.current)
        vm.submit()
        advanceUntilIdle()
        assertEquals(2, vault.calls.count { it == "changePin" })
        assertTrue(vm.uiState.value.done)
    }

    /**
     * An answer that came at last and refused the change is shown as it is, the wrong entry cleared and the new ones kept;
     * another change still being decided keeps everything; the password change's check is not this screen's.
     */
    @Test
    fun aRefusedOrUndecidedPinChangeClearsOnlyTheWrongEntry() = runTest {
        val vm = ChangePinViewModel(vault)
        vm.typed()
        vault.changeCheck.value = ChangeCheck.Checking(ChangeKind.PASSWORD)
        advanceUntilIdle()
        assertNull(vm.uiState.value.check)
        vault.changeCheck.value = ChangeCheck.Checking(ChangeKind.PIN)
        advanceUntilIdle()
        vault.changeCheck.value = ChangeCheck.Refused(ChangeKind.PIN, FailureKind.BAD_PIN)
        advanceUntilIdle()
        assertEquals(FailureKind.BAD_PIN, vm.uiState.value.error)
        assertEquals("", vm.uiState.value.current)
        assertEquals("59173048", vm.uiState.value.pin)
        vm.typed()
        vault.fail["changePin"] = VaultFailure(FailureKind.CHANGE_UNRESOLVED, "change_unresolved")
        vm.submit()
        advanceUntilIdle()
        assertEquals(FailureKind.CHANGE_UNRESOLVED, vm.uiState.value.error)
        assertEquals("40281795", vm.uiState.value.current)
        assertEquals("59173048", vm.uiState.value.pin)
    }

    /**
     * (M2) A decision this screen did not follow (an old "applied", said once by the shell) never opens Change PIN as
     * "done", and is left for the shell to say; "ended without a decision" holds the screen (no new try with what may be
     * the wrong PIN).
     */
    @Test
    fun anOldDecisionDoesNotOpenChangePinAsDone() = runTest {
        vault.changeCheck.value = ChangeCheck.Applied(ChangeKind.PIN)
        val vm = ChangePinViewModel(vault)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.done)
        assertNull(vm.uiState.value.check)
        assertEquals(0, vault.changeCheckAcknowledged)
        assertEquals(ChangeCheck.Applied(ChangeKind.PIN), vault.changeCheck.value)
        // A check this screen follows that ends without a decision: said, and nothing more is sent from here.
        vault.changeCheck.value = ChangeCheck.Checking(ChangeKind.PIN)
        advanceUntilIdle()
        vault.changeCheck.value = ChangeCheck.Ended(ChangeKind.PIN)
        advanceUntilIdle()
        assertEquals(ChangeCheck.Ended(ChangeKind.PIN), vm.uiState.value.check)
        assertTrue(vm.uiState.value.checking)
        vm.typed()
        vm.submit()
        advanceUntilIdle()
        assertFalse("changePin" in vault.calls)
    }

    /** Refused with nothing sent (an earlier request, an unconfirmed renew): every entry stays. */
    @Test
    fun aRefusalWithNothingSentKeepsTheEntries() = runTest {
        for (kind in listOf(FailureKind.CREDENTIAL_UNSETTLED, FailureKind.RENEW_UNCONFIRMED)) {
            vault.fail["changePin"] = VaultFailure(kind, null)
            val vm = ChangePinViewModel(vault)
            vm.typed()
            vm.submit()
            advanceUntilIdle()
            assertEquals(kind, vm.uiState.value.error)
            assertEquals("40281795", vm.uiState.value.current)
            assertEquals("59173048", vm.uiState.value.pin)
            assertEquals("59173048", vm.uiState.value.confirm)
        }
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

    /** 0.34.0 §11.10.9: a release with recovery_r and without recovery_offered: recovery is hidden for the vault. */
    @Test
    fun recoveryIsHiddenWhenTheReleaseDoesNotOfferIt() = runTest {
        assertTrue(RecoveryViewModel(vault, vault).uiState.value.offered)
        vault.recoveryOfferedValue = false
        assertFalse(RecoveryViewModel(vault, vault).uiState.value.offered)
    }
}
