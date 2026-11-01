// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.wallet

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.wallet.WalletFlags
import com.vettid.core.wallet.WalletsManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Spending limits screen's ViewModel (ANDROID-PLAN §4 "Spending limits", "The screen"): `set` for a form that only lowers, `raise`
 * for one that raises or removes (and for both), the lower-only validation, and every refusal leaving the limits as they were.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SpendingLimitsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val ops = FakeWallets()
    private val flagsFile = MemoryFlags()
    private val id = "01WALLET0000000000000000AA"

    private suspend fun TestScope.vm(capped: Boolean = true, supported: Boolean = true): SpendingLimitsViewModel {
        val w = ops.wallet(id)
        ops.wallets[id] = when {
            !supported -> w.copy(capsSupported = false)
            capped -> w.copy(capped = true, perTxSats = 500_000, perPeriodSats = 2_000_000, spentSats = 750_000, periodSeconds = 86_400)
            else -> w
        }
        val m = manager(backgroundScope, ops, WalletFlags(flagsFile))
        m.open()
        val vm = SpendingLimitsViewModel(SavedStateHandle(mapOf("walletId" to id)), m)
        eventually { vm.state.value.loaded }
        return vm
    }

    private fun vaultWallet() = ops.wallets.getValue(id)

    private fun sent(prefix: String) = ops.calls.filter { it.startsWith(prefix) }

    @Test
    fun theScreenShowsTheLimitsAndWhatThePeriodHolds() = runTest {
        val vm = vm()
        val s = vm.state.value
        assertEquals(500_000L, s.perTx)
        assertEquals(2_000_000L, s.perPeriod)
        assertEquals(750_000L, s.spent)
        assertTrue(s.supported)
        assertEquals("SpendingLimitsUiState", s.toString())
        val none = vm(capped = false).state.value
        assertNull(none.perTx)
        assertNull(none.perPeriod)
    }

    @Test
    fun aFormThatOnlyLowersIsSentAsSetWithNoPassword() = runTest {
        val vm = vm()
        vm.openLower()
        vm.setPerTx("400000")
        assertTrue(vm.state.value.plan.ok)
        vm.next()
        assertEquals(LimitsStep.CONFIRM_SET, vm.state.value.step)
        vm.confirmSet()
        eventually { vm.state.value.step == LimitsStep.OVERVIEW && !vm.state.value.busy }
        assertEquals(listOf("caps.set:400000:null"), sent("caps."))
        assertEquals(400_000L, vm.state.value.perTx)
        assertEquals(2_000_000L, vm.state.value.perPeriod)
        assertNull(vm.state.value.error)
    }

    @Test
    fun lowerAcceptsOnlyLowerValuesOrALimitWhereThereWasNone() = runTest {
        val vm = vm()
        vm.openLower()
        // Higher, or equal alone: explained on the field / nothing would change; next goes nowhere.
        vm.setPerTx("600000")
        assertEquals(CapFieldProblem.NOT_LOWER, vm.state.value.plan.txProblem)
        assertFalse(vm.state.value.plan.ok)
        vm.next()
        assertEquals(LimitsStep.LOWER, vm.state.value.step)
        vm.setPerTx("500000")
        assertEquals(CapFormProblem.NOTHING, vm.state.value.plan.problem)
        assertFalse(vm.state.value.plan.ok)
        vm.setPerTx("")
        assertFalse(vm.state.value.plan.ok)
        // Above the period it would come under.
        vm.setPerPeriod("100000")
        assertEquals(CapFormProblem.ABOVE_PERIOD, vm.state.value.plan.problem)
        vm.setPerPeriod("900000")
        assertTrue(vm.state.value.plan.ok)
        assertTrue(sent("caps.").isEmpty())
        // Where there was none, any number is a lowering.
        val bare = vm(capped = false)
        bare.openLower()
        bare.setPerTx("123456")
        assertTrue(bare.state.value.plan.ok)
        bare.next()
        bare.confirmSet()
        eventually { vaultWallet().perTxSats == 123_456L }
        assertEquals("caps.set:123456:null", sent("caps.").last())
    }

    @Test
    fun aFormThatRaisesIsSentAsRaiseWithThePinAndPassword() = runTest {
        val vm = vm()
        vm.openRaise()
        vm.setPerTx("600000")
        vm.next()
        assertEquals(LimitsStep.CONFIRM_RAISE, vm.state.value.step)
        // Nothing is sent until both are typed.
        vm.confirmRaise()
        assertTrue(sent("caps.").isEmpty())
        vm.setPin("123456")
        vm.setPassword("pw")
        vm.confirmRaise()
        // Both are wiped from the state as soon as they are sent.
        assertEquals("", vm.state.value.pin)
        assertEquals("", vm.state.value.password)
        eventually { vm.state.value.step == LimitsStep.OVERVIEW && !vm.state.value.busy }
        assertEquals(listOf("caps.raise:600000:2000000"), sent("caps."))
        assertEquals(600_000L, vm.state.value.perTx)
    }

    @Test
    fun aRaiseFormThatOnlyLowersIsSentAsSetAndOneThatDoesBothAsRaiseWithTheLoweredValueInIt() = runTest {
        val onlyLower = vm()
        onlyLower.openRaise()
        onlyLower.setPerPeriod("1500000")
        onlyLower.next()
        assertEquals(LimitsStep.CONFIRM_SET, onlyLower.state.value.step)
        onlyLower.confirmSet()
        eventually { vaultWallet().perPeriodSats == 1_500_000L }
        assertEquals(listOf("caps.set:null:1500000"), sent("caps."))
        // Per payment up (a raise), per 24 hours down (a lowering): one raise carrying both values.
        val both = vm()
        both.openRaise()
        both.setPerTx("600000")
        both.setPerPeriod("1000000")
        both.next()
        assertEquals(LimitsStep.CONFIRM_RAISE, both.state.value.step)
        both.setPin("123456")
        both.setPassword("pw")
        both.confirmRaise()
        eventually { vaultWallet().perPeriodSats == 1_000_000L && vaultWallet().perTxSats == 600_000L }
        assertEquals("caps.raise:600000:1000000", sent("caps.").last())
    }

    @Test
    fun removingALimitSendsNoLimitForIt() = runTest {
        val vm = vm()
        vm.openRaise()
        vm.setRemoveTx(true)
        vm.next()
        assertEquals(LimitsStep.CONFIRM_RAISE, vm.state.value.step)
        vm.setPin("123456")
        vm.setPassword("pw")
        vm.confirmRaise()
        eventually { vaultWallet().perTxSats == null && vaultWallet().version > 1 && vm.state.value.step == LimitsStep.OVERVIEW && !vm.state.value.busy }
        assertEquals("caps.raise:null:2000000", sent("caps.").last())
        assertEquals(2_000_000L, vaultWallet().perPeriodSats)
        // And the other one: now nothing is left to raise or remove.
        vm.openRaise()
        vm.setRemovePeriod(true)
        vm.next()
        vm.setPin("123456")
        vm.setPassword("pw")
        vm.confirmRaise()
        eventually { vaultWallet().perPeriodSats == null && vm.state.value.step == LimitsStep.OVERVIEW && !vm.state.value.busy }
        assertFalse(vaultWallet().capped)
        assertEquals("caps.raise:null:null", sent("caps.").last())
    }

    private suspend fun TestScope.raiseWith(pin: String, password: String): SpendingLimitsViewModel {
        val vm = vm()
        vm.openRaise()
        vm.setPerTx("600000")
        vm.next()
        vm.setPin(pin)
        vm.setPassword(password)
        val lists = ops.calls.count { it == "list" }
        vm.confirmRaise()
        eventually { !vm.state.value.busy && vm.state.value.error != null }
        // The screen read the wallet again.
        eventually { ops.calls.count { it == "list" } > lists }
        return vm
    }

    private fun assertLimitsAsTheyWere(vm: SpendingLimitsViewModel) {
        assertEquals(500_000L, vaultWallet().perTxSats)
        assertEquals(2_000_000L, vaultWallet().perPeriodSats)
        assertEquals(500_000L, vm.state.value.perTx)
        assertEquals(2_000_000L, vm.state.value.perPeriod)
        assertEquals("", vm.state.value.pin)
        assertEquals("", vm.state.value.password)
    }

    @Test
    fun aWrongPinOrPasswordLeavesTheLimitsAndAsksAgain() = runTest {
        val pin = raiseWith("000000", "pw")
        assertEquals(FailureKind.BAD_PIN, pin.state.value.error)
        assertEquals(LimitsStep.CONFIRM_RAISE, pin.state.value.step)
        assertLimitsAsTheyWere(pin)
        val password = raiseWith("123456", "nope")
        assertEquals(FailureKind.BAD_PASSWORD, password.state.value.error)
        assertEquals(LimitsStep.CONFIRM_RAISE, password.state.value.step)
        assertLimitsAsTheyWere(password)
    }

    @Test
    fun aBackoffLeavesTheLimits() = runTest {
        ops.capsRaiseFails = "backoff"
        val vm = raiseWith("123456", "pw")
        assertEquals(FailureKind.BACKOFF, vm.state.value.error)
        assertLimitsAsTheyWere(vm)
        vm.dismissError()
        assertNull(vm.state.value.error)
    }

    @Test
    fun aConflictLeavesTheLimitsReadsTheWalletAndGoesBackToTheOverview() = runTest {
        val vm = vm()
        vm.openRaise()
        vm.setPerTx("600000")
        vm.next()
        vm.setPin("123456")
        vm.setPassword("pw")
        // The wallet moved on elsewhere.
        ops.wallets[id] = vaultWallet().copy(version = 7)
        vm.confirmRaise()
        eventually { !vm.state.value.busy && vm.state.value.error != null }
        assertEquals(FailureKind.CONFLICT, vm.state.value.error)
        assertEquals(LimitsStep.OVERVIEW, vm.state.value.step)
        assertEquals(1, sent("caps.raise").size)
        assertLimitsAsTheyWere(vm)
    }

    @Test
    fun aRefusedLowerLeavesTheLimitsToo() = runTest {
        ops.capsSetFails = "internal"
        val vm = vm()
        vm.openLower()
        vm.setPerTx("400000")
        vm.next()
        vm.confirmSet()
        eventually { !vm.state.value.busy && vm.state.value.error != null }
        assertEquals(LimitsStep.CONFIRM_SET, vm.state.value.step)
        assertLimitsAsTheyWere(vm)
        vm.back()
        assertEquals(LimitsStep.LOWER, vm.state.value.step)
        vm.back()
        assertEquals(LimitsStep.OVERVIEW, vm.state.value.step)
    }

    @Test
    fun theUnitToggleCarriesWhatIsTypedAndAVaultWithoutLimitsOffersNoForm() = runTest {
        val vm = vm()
        vm.openLower()
        vm.setPerTx("100000")
        vm.toggleUnit()
        assertEquals("0.001", vm.state.value.form.perTx)
        assertEquals(100_000L, CapsEdit.plan(500_000, 2_000_000, vm.state.value.form, false).newPerTx)
        val old = vm(supported = false)
        old.openLower()
        old.openRaise()
        assertEquals(LimitsStep.OVERVIEW, old.state.value.step)
        assertFalse(old.state.value.supported)
    }

    @Test
    fun aRaiseNeverGoesOverAPendingLowerThatHasNotReachedTheVault() = runTest {
        val w = ops.wallet(id)
        ops.wallets[id] = w.copy(capped = true, perTxSats = 500_000, perPeriodSats = 2_000_000)
        WalletFlags(flagsFile).setPendingCaps(id, com.vettid.core.wallet.WalletCaps(null, 1_000_000))
        ops.capsSetFails = "internal"
        val m = manager(backgroundScope, ops, WalletFlags(flagsFile))
        m.open()
        val vm = SpendingLimitsViewModel(SavedStateHandle(mapOf("walletId" to id)), m)
        eventually { vm.state.value.loaded && vm.state.value.capsPending }
        vm.openRaise()
        vm.setPerTx("600000")
        vm.next()
        vm.setPin("123456")
        vm.setPassword("pw")
        // The pending set cannot be sent: nothing is raised, and the limits are as they were.
        vm.confirmRaise()
        eventually { !vm.state.value.busy && vm.state.value.error != null }
        assertTrue(sent("caps.raise").isEmpty())
        assertEquals(2_000_000L, vaultWallet().perPeriodSats)
        // The set goes through, and the raise (chosen against the old limits) is not sent over it: the screen shows the new limits.
        ops.capsSetFails = null
        vm.dismissError()
        vm.setPin("123456")
        vm.setPassword("pw")
        vm.confirmRaise()
        eventually { vm.state.value.errorCode == WalletsManager.CAPS_SETTLED }
        assertTrue(sent("caps.raise").isEmpty())
        assertEquals(LimitsStep.OVERVIEW, vm.state.value.step)
        assertEquals(1_000_000L, vm.state.value.perPeriod)
        assertFalse(vm.state.value.capsPending)
    }
}
