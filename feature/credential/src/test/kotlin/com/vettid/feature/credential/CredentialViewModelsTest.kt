package com.vettid.feature.credential

import com.vettid.core.data.policy.PasswordPolicy
import com.vettid.core.data.policy.PasswordRule
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.ChangeCheck
import com.vettid.core.data.vault.ChangeKind
import com.vettid.core.data.vault.CredentialAlarm
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
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
        val vm = ChangePasswordViewModel(vault, vault)
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

    /** Release R (VAULT-MESSAGING 0.33.0 §11.14.9): the password change asks for the PIN and carries it. */
    @Test
    fun releaseRPasswordChangeCarriesThePin() = runTest {
        vault.phoneSecret = true
        val vm = ChangePasswordViewModel(vault, vault)
        assertTrue(vm.uiState.value.askPin)
        vm.setCurrent("old password 1")
        vm.setPassword("a much better passphrase here")
        vm.setConfirm("a much better passphrase here")
        vm.submit()
        advanceUntilIdle()
        assertFalse("without the PIN nothing is sent", "changePassword" in vault.calls)
        vm.setPin("975310")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.done)
        assertEquals("975310", vault.lastPasswordChangePin)
    }

    @Test
    fun passwordChangeBeforeReleaseRAsksNoPin() = runTest {
        val vm = ChangePasswordViewModel(vault, vault)
        assertFalse(vm.uiState.value.askPin)
        vm.setCurrent("old password 1")
        vm.setPassword("a much better passphrase here")
        vm.setConfirm("a much better passphrase here")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.done)
        assertEquals(null, vault.lastPasswordChangePin)
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

    /** Release R (ANDROID-PLAN "Passwords"): the rules as the member types, and the vault's weak_password reason. */
    @Test
    fun releaseRPasswordChangeFollowsTheRules() = runTest {
        vault.phoneSecret = true
        val vm = ChangePasswordViewModel(vault, vault)
        assertTrue(vm.uiState.value.newRules)
        vm.setPin("975310")
        vm.setCurrent("old password 1")
        vm.setPassword("password1234")
        vm.setConfirm("password1234")
        assertEquals(PasswordRule.COMMON, vm.uiState.value.rule)
        assertFalse(vm.uiState.value.canSubmit)
        vm.submit()
        advanceUntilIdle()
        assertFalse("changePassword" in vault.calls)
        vm.setPassword("975310975310")
        vm.setConfirm("975310975310")
        assertFalse(vm.uiState.value.canSubmit)
        vm.setPassword("a much better passphrase here")
        vm.setConfirm("a much better passphrase here")
        assertEquals(PasswordRule.OK, vm.uiState.value.rule)
        vault.fail["changePassword"] = com.vettid.core.data.vault.VaultFailure(
            FailureKind.WEAK_PASSWORD, "weak_password",
            cause = com.vettid.core.vault.VaultOpException(
                "credential.password.change", "weak_password", body = kotlinx.serialization.json.buildJsonObject {
                    put("reason", kotlinx.serialization.json.JsonPrimitive("common"))
                },
            ),
        )
        vm.submit()
        advanceUntilIdle()
        assertEquals(PasswordRule.COMMON, vm.uiState.value.rule)
        assertTrue(vm.uiState.value.refused)
        assertEquals(null, vm.uiState.value.error)
        assertFalse(vm.uiState.value.done)
    }

    /** On the earlier meter too, a vault's weak_password is shown and the same password is not sent again. */
    @Test
    fun beforeReleaseRTheVaultsVerdictIsShown() = runTest {
        val vm = ChangePasswordViewModel(vault, vault)
        vault.fail["changePassword"] = com.vettid.core.data.vault.VaultFailure(FailureKind.WEAK_PASSWORD, "weak_password")
        vm.setCurrent("old password 1")
        vm.setPassword("Tr0ub4dor&3x")
        vm.setConfirm("Tr0ub4dor&3x")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.refused)
        assertEquals(PasswordRule.VAULT_REFUSED, vm.uiState.value.rule)
        vm.setCurrent("old password 1")
        assertFalse(vm.uiState.value.canSubmit)
        vm.setPassword("a different passphrase 7")
        assertFalse(vm.uiState.value.refused)
    }

    /** A vault before release R keeps the earlier meter and checks (the plan: nothing changes for it). */
    @Test
    fun beforeReleaseRThePasswordChangeKeepsTheMeter() = runTest {
        val vm = ChangePasswordViewModel(vault, vault)
        assertFalse(vm.uiState.value.newRules)
        vm.setCurrent("old password 1")
        vm.setPassword("Tr0ub4dor&3x")
        vm.setConfirm("Tr0ub4dor&3x")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.done)
    }

    /**
     * VAULT-MESSAGING 0.44.0 §11.14.9: no answer to the password change is not a failure. The entries stay while VettID
     * checks, nothing is sent meanwhile; "not changed" lets the member try again; "applied" is the usual success; a
     * refusal that came at last clears only the wrong entry.
     */
    @Test
    fun aLostPasswordChangeAnswerIsDecidedNotRepeated() = runTest {
        vault.fail["changePassword"] = VaultFailure(FailureKind.CHANGE_CHECKING, "change_checking")
        val vm = ChangePasswordViewModel(vault, vault)
        vm.setCurrent("old password 1")
        vm.setPassword("a much better passphrase here")
        vm.setConfirm("a much better passphrase here")
        vm.submit()
        advanceUntilIdle()
        vault.changeCheck.value = ChangeCheck.Checking(ChangeKind.PASSWORD)
        advanceUntilIdle()
        with(vm.uiState.value) {
            assertNull(error)
            assertTrue(checking)
            assertEquals("old password 1", current)
            assertEquals("a much better passphrase here", password)
        }
        vm.submit()
        advanceUntilIdle()
        assertEquals(1, vault.calls.count { it == "changePassword" })
        vault.changeCheck.value = ChangeCheck.NotApplied(ChangeKind.PASSWORD)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.checking)
        assertEquals(ChangeCheck.NotApplied(ChangeKind.PASSWORD), vm.uiState.value.check)
        vault.changeCheck.value = ChangeCheck.Checking(ChangeKind.PASSWORD)
        advanceUntilIdle()
        vault.changeCheck.value = ChangeCheck.Refused(ChangeKind.PASSWORD, FailureKind.BAD_PASSWORD)
        advanceUntilIdle()
        assertEquals(FailureKind.BAD_PASSWORD, vm.uiState.value.error)
        assertEquals("", vm.uiState.value.current)
        assertEquals("a much better passphrase here", vm.uiState.value.password)
        vault.changeCheck.value = ChangeCheck.Checking(ChangeKind.PASSWORD)
        advanceUntilIdle()
        vault.changeCheck.value = ChangeCheck.Applied(ChangeKind.PASSWORD)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.done)
    }
}
