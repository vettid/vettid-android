package com.vettid.feature.wallet

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.chain.Network
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.wallet.WalletCaps
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
import kotlin.random.Random

/**
 * Spending limits in the new-wallet flow and the PIN for the phrase of a capped wallet (ANDROID-PLAN §4 "Spending limits", W1d): the
 * limits step after the name and network, the wallet created without limits, the limits sent only after the phrase check, and the
 * PIN field beside the password.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BackupLimitsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val ops = FakeWallets()
    private val flagsFile = MemoryFlags()
    private val newId = "01NEW000000000000000000000"

    private lateinit var wallets: WalletsManager

    private suspend fun TestScope.vm(saved: SavedStateHandle = SavedStateHandle()): BackupViewModel {
        wallets = manager(backgroundScope, ops, WalletFlags(flagsFile))
        wallets.open()
        return BackupViewModel(saved, wallets, Random(7))
    }

    private fun BackupViewModel.toCreate(limits: BackupViewModel.() -> Unit = {}) {
        setName("Savings")
        setNetwork(Network.TESTNET4)
        toLimits()
        limits()
        toPassword()
    }

    private suspend fun BackupViewModel.checkAll() {
        eventually { state.value.step == BackupStep.PHRASE }
        startCheck()
        for (at in state.value.asked) answer(ops.words[at])
        eventually { state.value.step == BackupStep.DONE && !state.value.busy }
    }

    @Test
    fun theLimitsStepComesAfterTheNameAndNetworkAndNoLimitIsTheDefault() = runTest {
        val vm = vm()
        vm.setName("")
        vm.toLimits()
        assertEquals("a name is needed first", BackupStep.FORM, vm.state.value.step)
        vm.setName("Savings")
        vm.toLimits()
        assertEquals(BackupStep.LIMITS, vm.state.value.step)
        assertFalse(vm.state.value.limits.limit)
        assertNull(vm.state.value.limitsParse.caps)
        assertTrue(vm.state.value.limitsParse.ok)
        vm.toPassword()
        assertEquals(BackupStep.CREATE, vm.state.value.step)
        vm.previous()
        assertEquals(BackupStep.LIMITS, vm.state.value.step)
        vm.previous()
        assertEquals(BackupStep.FORM, vm.state.value.step)
    }

    @Test
    fun noLimitCreatesAndSetsNothing() = runTest {
        val vm = vm()
        vm.toCreate()
        vm.setPassword("pw")
        vm.create()
        vm.checkAll()
        assertTrue(vm.state.value.backedUp)
        assertTrue(ops.calls.none { it.startsWith("caps.") })
        assertTrue(wallets.capsPending.value.isEmpty())
    }

    @Test
    fun aLimitIsChosenThenTheWalletIsCreatedWithoutItAndItIsSetOnlyAfterTheCheck() = runTest {
        val vm = vm()
        vm.toCreate {
            setLimitChoice(true)
            setPerTx("500000")
            setPerPeriod("2000000")
        }
        assertEquals(BackupStep.CREATE, vm.state.value.step)
        vm.setPassword("pw")
        vm.create()
        eventually { vm.state.value.step == BackupStep.PHRASE }
        // Created, revealed with the password alone, nothing set: Receive is off for the wallet.
        assertEquals(listOf("create:Savings:testnet4:true", "reveal:$newId"), ops.calls.filterNot { it == "list" })
        assertEquals(setOf(newId), wallets.capsPending.value)
        assertFalse(ops.wallets.getValue(newId).capped)
        vm.startCheck()
        // A wrong answer ends the check; the limit is still not sent.
        vm.answer("nope")
        assertTrue(ops.calls.none { it.startsWith("caps.") })
        vm.showAgain()
        vm.startCheck()
        for (at in vm.state.value.asked) vm.answer(ops.words[at])
        eventually { vm.state.value.step == BackupStep.DONE && !vm.state.value.busy }
        assertEquals("caps.set:500000:2000000", ops.calls.last())
        assertTrue(vm.state.value.backedUp)
        assertFalse(vm.state.value.capsFailed)
        assertTrue(wallets.capsPending.value.isEmpty())
        assertEquals(500_000L, ops.wallets.getValue(newId).perTxSats)
    }

    @Test
    fun onlyOneLimitIsSentAsItsOwnMember() = runTest {
        val vm = vm()
        vm.toCreate {
            setLimitChoice(true)
            setPerPeriod("1000")
        }
        vm.setPassword("pw")
        vm.create()
        vm.checkAll()
        assertEquals("caps.set:null:1000", ops.calls.last())
    }

    @Test
    fun limitsTheVaultWouldRefuseKeepTheStepFromGoingOn() = runTest {
        val vm = vm()
        vm.setName("Savings")
        vm.toLimits()
        vm.setLimitChoice(true)
        // "Set a limit" with both fields empty is no limit chosen.
        assertFalse(vm.state.value.limitsParse.ok)
        vm.toPassword()
        assertEquals(BackupStep.LIMITS, vm.state.value.step)
        vm.setPerTx("3000000")
        vm.setPerPeriod("2000000")
        assertEquals(LimitProblem.ABOVE_PERIOD, vm.state.value.limitsParse.perTxProblem)
        vm.toPassword()
        assertEquals(BackupStep.LIMITS, vm.state.value.step)
        vm.setPerTx("2000000")
        vm.toPassword()
        assertEquals(BackupStep.CREATE, vm.state.value.step)
    }

    @Test
    fun theUnitToggleCarriesAValidAmountOverAndClearsTheRest() = runTest {
        val vm = vm()
        vm.setName("Savings")
        vm.toLimits()
        vm.setLimitChoice(true)
        vm.setPerTx("500000")
        vm.setPerPeriod("2000000")
        vm.toggleUnit()
        assertTrue(vm.state.value.limits.btc)
        assertEquals("0.005", vm.state.value.limits.perTx)
        assertEquals("0.02", vm.state.value.limits.perPeriod)
        assertEquals(WalletCaps(500_000, 2_000_000), vm.state.value.limitsParse.caps)
        vm.toggleUnit()
        assertEquals("500000", vm.state.value.limits.perTx)
        // Sats take whole numbers only: other characters are not kept.
        vm.setPerTx("1,5.0 0")
        assertEquals("1500", vm.state.value.limits.perTx)
    }

    @Test
    fun aLimitThatCouldNotBeSetIsTriedAgainAndOnlyAsASet() = runTest {
        ops.capsSetFails = "internal"
        val vm = vm()
        vm.toCreate {
            setLimitChoice(true)
            setPerTx("500000")
        }
        vm.setPassword("pw")
        vm.create()
        vm.checkAll()
        // Backed up, but the limit is not set: Receive stays off, and the screen offers Try again.
        assertTrue(vm.state.value.backedUp)
        assertTrue(vm.state.value.capsFailed)
        assertEquals(setOf(newId), wallets.capsPending.value)
        ops.capsSetFails = null
        vm.retryCaps()
        eventually { !vm.state.value.capsFailed && !vm.state.value.busy }
        assertTrue(wallets.capsPending.value.isEmpty())
        assertEquals("caps.set:500000:null", ops.calls.last())
        assertTrue(ops.calls.none { it.startsWith("caps.raise") })
    }

    @Test
    fun theCappedPhraseNeedsThePinAsWellAsThePassword() = runTest {
        val id = "01CAPPED00000000000000000A"
        ops.wallets[id] = ops.wallet(id).copy(capped = true)
        val vm = vm(SavedStateHandle(mapOf("walletId" to id)))
        assertTrue(vm.state.value.capped)
        vm.setPassword("pw")
        vm.setPin("12ab34 56")
        assertEquals("123456", vm.state.value.pin)
        // A wrong PIN: the vault's `bad_pin`, shown on the PIN, and the PIN is cleared.
        vm.setPin("000000")
        vm.reveal()
        eventually { vm.state.value.error != null }
        assertEquals(FailureKind.BAD_PIN, vm.state.value.error)
        assertEquals("", vm.state.value.pin)
        assertEquals("", vm.state.value.password)
        assertEquals(BackupStep.PASSWORD, vm.state.value.step)
        // The right one.
        vm.setPassword("pw")
        vm.setPin("123456")
        vm.reveal()
        eventually { vm.state.value.step == BackupStep.PHRASE }
        assertTrue("reveal:$id:pin" in ops.calls)
        assertEquals("", vm.state.value.pin)
    }

    @Test
    fun aWalletWithoutALimitAsksForNoPin() = runTest {
        val id = "01PLAIN00000000000000000A"
        ops.wallet(id)
        val vm = vm(SavedStateHandle(mapOf("walletId" to id)))
        assertFalse(vm.state.value.capped)
        assertTrue(vm.state.value.pinReady)
        vm.setPassword("pw")
        vm.reveal()
        eventually { vm.state.value.step == BackupStep.PHRASE }
        assertTrue("reveal:$id" in ops.calls)
    }

    @Test
    fun thePinIsNeverPrinted() = runTest {
        val vm = vm(SavedStateHandle(mapOf("walletId" to "x")))
        vm.setPin("987654")
        assertFalse(vm.state.value.toString().contains("987654"))
        assertFalse(LimitsForm(true, "424242", "7").toString().contains("424242"))
    }

    // A vault before release 26

    @Test
    fun aListedWalletWithoutCapsSkipsTheLimitsStepAndNothingIsPendingOrGated() = runTest {
        ops.wallets["01OLD00000000000000000000A"] = ops.wallet("01OLD00000000000000000000A").copy(capsSupported = false)
        val vm = vm()
        vm.setName("Savings")
        vm.toLimits()
        assertEquals(BackupStep.CREATE, vm.state.value.step)
        assertFalse(vm.state.value.limitsAsked)
        // Back goes to the name, not to a step that was never shown.
        vm.previous()
        assertEquals(BackupStep.FORM, vm.state.value.step)
        vm.toLimits()
        vm.setPassword("pw")
        vm.create()
        vm.checkAll()
        assertTrue(vm.state.value.backedUp)
        assertTrue(ops.calls.none { it.startsWith("caps.") })
        assertTrue(wallets.capsPending.value.isEmpty())
        assertFalse(vm.state.value.capsUnsupported)
    }

    @Test
    fun aCreateAnswerWithoutCapsDropsTheChosenLimitAndSaysSo() = runTest {
        ops.noCaps = true
        val vm = vm()
        vm.toCreate {
            setLimitChoice(true)
            setPerTx("500000")
        }
        vm.setPassword("pw")
        vm.create()
        eventually { vm.state.value.step == BackupStep.PHRASE }
        assertTrue(vm.state.value.capsUnsupported)
        assertTrue(wallets.capsPending.value.isEmpty())
        assertTrue(WalletFlags(flagsFile).allPendingCaps()!!.isEmpty())
        vm.startCheck()
        for (at in vm.state.value.asked) vm.answer(ops.words[at])
        eventually { vm.state.value.step == BackupStep.DONE && !vm.state.value.busy }
        assertTrue(vm.state.value.backedUp)
        assertTrue(vm.state.value.capsUnsupported)
        assertFalse(vm.state.value.capsFailed)
        assertTrue(ops.calls.none { it.startsWith("caps.") })
    }

    @Test
    fun limitsThatCouldNotBeSavedOnThisPhoneAreSaidAndClearedOnceSet() = runTest {
        val vm = vm()
        vm.toCreate {
            setLimitChoice(true)
            setPerTx("500000")
        }
        vm.setPassword("pw")
        flagsFile.failSaves = true
        vm.create()
        eventually { vm.state.value.step == BackupStep.PHRASE }
        assertTrue(vm.state.value.capsNotSaved)
        flagsFile.failSaves = false
        vm.startCheck()
        for (at in vm.state.value.asked) vm.answer(ops.words[at])
        eventually { vm.state.value.step == BackupStep.DONE && !vm.state.value.busy }
        assertEquals("caps.set:500000:null", ops.calls.last())
        assertFalse(vm.state.value.capsNotSaved)
    }

    @Test
    fun anUnreadableFlagsFileHoldsReceiveBackForTheWalletDetail() = runTest {
        val id = "01WALLET0000000000000000AA"
        ops.wallet(id)
        flagsFile.failLoads = true
        wallets = manager(backgroundScope, ops, WalletFlags(flagsFile))
        wallets.open()
        assertTrue(WalletsManager.isPending(wallets.capsPending.value, id))
        flagsFile.failLoads = false
        wallets.settleCaps(id)
        assertFalse(WalletsManager.isPending(wallets.capsPending.value, id))
    }
}
