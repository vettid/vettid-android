package com.vettid.feature.wallet

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.wallet.WalletFlags
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

/** Receive's ViewModel (ANDROID-PLAN §4 screens table, "Receive"). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReceiveViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val ops = FakeWallets()
    private val flagsFile = MemoryFlags()
    private val id = "01WALLET0000000000000000AA"

    private suspend fun TestScope.vm(backedUp: Boolean = true): ReceiveViewModel {
        val flags = WalletFlags(flagsFile)
        if (backedUp) flags.setBackedUp(id)
        val m = manager(backgroundScope, ops, flags)
        m.open()
        return ReceiveViewModel(SavedStateHandle(mapOf("walletId" to id)), m)
    }

    @Test
    fun theAddressComesFromTheVaultAndNewAddressAsksForTheNext() = runTest {
        ops.wallet(id)
        val vm = vm()
        eventually { vm.state.value.address != null }
        assertEquals("tb1p0", vm.state.value.address?.address)
        assertEquals("BITCOIN:TB1P0", vm.state.value.uri)
        vm.newAddress()
        eventually { vm.state.value.address?.address == "tb1p1" }
        assertEquals(2, ops.calls.count { it == "new" })
    }

    @Test
    fun aWalletNotBackedUpAsksForNoAddress() = runTest {
        ops.wallet(id)
        val vm = vm(backedUp = false)
        eventually { vm.state.value.notBackedUp }
        assertNull(vm.state.value.address)
        assertTrue(ops.calls.none { it == "new" })
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun theAmountIsFilteredAndPutInTheUriOnlyWhenValid() = runTest {
        ops.wallet(id)
        val vm = vm()
        eventually { vm.state.value.address != null }
        vm.setAmount("0.0012x3")
        assertEquals("0.00123", vm.state.value.amountText)
        assertEquals("BITCOIN:TB1P0?amount=0.00123", vm.state.value.uri)
        vm.setAmount("0.123456789")
        assertEquals(BtcFormat.Amount.Invalid, vm.state.value.amount)
        assertEquals("BITCOIN:TB1P0", vm.state.value.uri)
        vm.setAmount("")
        assertEquals(BtcFormat.Amount.None, vm.state.value.amount)
    }

    @Test
    fun aVaultFailureIsShownAndTriedAgain() = runTest {
        ops.wallet(id)
        ops.addressesFail = true
        val vm = vm()
        eventually { vm.state.value.error != null }
        assertNull(vm.state.value.address)
        ops.addressesFail = false
        vm.load()
        eventually { vm.state.value.address != null }
        assertNull(vm.state.value.error)
    }

    @Test
    fun theAddressComesOnceThePhraseIsChecked() = runTest {
        ops.wallet(id)
        val flags = WalletFlags(flagsFile)
        val m = manager(backgroundScope, ops, flags)
        m.open()
        val vm = ReceiveViewModel(SavedStateHandle(mapOf("walletId" to id)), m)
        eventually { vm.state.value.notBackedUp }
        // The backup flow ran on top and passed.
        assertTrue(m.confirmBackup(id))
        eventually { vm.state.value.address != null }
        assertFalse(vm.state.value.notBackedUp)
    }

    @Test
    fun aLegacyTestnetWalletAsksWhichNetworkBeforeAnyAddress() = runTest {
        ops.wallet(id, network = "testnet")
        val vm = vm()
        eventually { vm.state.value.needsNetwork }
        assertTrue(ops.calls.none { it == "new" })
        vm.chooseTestNetwork(com.vettid.core.chain.Network.TESTNET4)
        eventually { vm.state.value.address != null }
        assertFalse(vm.state.value.needsNetwork)
        assertEquals(com.vettid.core.chain.Network.TESTNET4, vm.state.value.network)
    }

    @Test
    fun aVaultWithoutWalletsClosesTheScreen() = runTest {
        ops.unsupported = true
        val m = manager(backgroundScope, ops)
        runCatching { m.open() }
        val vm = ReceiveViewModel(SavedStateHandle(mapOf("walletId" to id)), m)
        eventually { vm.state.value.closed }
    }

    @Test
    fun theScreenLeavesWhenTheVaultCloses() = runTest {
        ops.wallet(id)
        val m = manager(backgroundScope, ops, WalletFlags(flagsFile).also { it.setBackedUp(id) })
        m.open()
        val vm = ReceiveViewModel(SavedStateHandle(mapOf("walletId" to id)), m)
        eventually { vm.state.value.address != null }
        m.clear()
        eventually { vm.state.value.closed }
        assertFalse(vm.state.value.toString().contains("tb1p"))
    }
}
