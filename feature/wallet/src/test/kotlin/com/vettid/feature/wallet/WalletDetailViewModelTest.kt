package com.vettid.feature.wallet

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.chain.ChainError
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.Network
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.wallet.ChainOpen
import com.vettid.core.wallet.ChainSource
import com.vettid.core.wallet.LookupResult
import com.vettid.core.wallet.WalletFlags
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The wallet detail's ViewModel (ANDROID-PLAN §4 "Wallet", screens table): look-up states, the privacy note, address type, delete. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WalletDetailViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val ops = FakeWallets()
    private val flagsFile = MemoryFlags()
    private val id = "01WALLET0000000000000000AA"
    private var opened = 0
    private var open: ChainOpen = ChainOpen.NoServer(ChainServer.NotSet)
    private val chain = ChainSource {
        opened++
        open
    }

    private suspend fun TestScope.vm(noteShown: Boolean = true): WalletDetailViewModel {
        val flags = WalletFlags(flagsFile)
        if (noteShown) flags.setPrivacyNoteShown(id)
        val m = manager(backgroundScope, ops, flags, chain)
        m.open()
        return WalletDetailViewModel(SavedStateHandle(mapOf("walletId" to id)), m)
    }

    @Test
    fun theFirstLookupWaitsForThePrivacyNoteAndNothingIsAskedBefore() = runTest {
        ops.wallet(id)
        val vm = vm(noteShown = false)
        eventually { vm.state.value.noteNeeded }
        assertEquals(0, opened)
        assertEquals(null, vm.state.value.lookup)
        // Pull to refresh does not look up either.
        vm.refresh()
        assertEquals(0, opened)
        vm.acknowledgeNote()
        eventually { vm.state.value.lookup != null }
        assertEquals(1, opened)
        assertFalse(vm.state.value.noteNeeded)
        assertTrue(WalletFlags(flagsFile).privacyNoteShown(id))
    }

    @Test
    fun aWalletWhoseNoteWasShownIsLookedUpAtOnce() = runTest {
        ops.wallet(id)
        val vm = vm()
        eventually { vm.state.value.lookup != null }
        assertFalse(vm.state.value.noteNeeded)
        assertEquals(1, opened)
        assertEquals(LookupResult.NoServer(ChainServer.NotSet), vm.state.value.lookup)
        assertEquals("Savings", vm.state.value.name)
        assertEquals(Network.TESTNET4, vm.state.value.network)
    }

    @Test
    fun aServerThatDoesNotAnswerIsShownAndTryAgainAsksAgain() = runTest {
        ops.wallet(id)
        open = ChainOpen.Unchecked(ChainError.Unreachable())
        val vm = vm()
        eventually { vm.state.value.lookup is LookupResult.ServerFailed }
        vm.refresh()
        eventually { opened == 2 && !vm.state.value.lookingUp }
        assertTrue(vm.state.value.lookup is LookupResult.ServerFailed)
    }

    @Test
    fun aServerOnAnotherNetworkAndAnAddressMismatchAreStates() = runTest {
        ops.wallet(id)
        open = ChainOpen.WrongNetwork(Network.MAINNET)
        val vm = vm()
        eventually { vm.state.value.lookup is LookupResult.ServerWrongNetwork }
        open = ChainOpen.Ready("mempool.space") { error("not asked: the address check stops first") }
        vm.refresh()
        eventually { vm.state.value.lookup == LookupResult.AddressMismatch }
    }

    @Test
    fun aLegacyTestnetWalletAsksWhichTestNetworkAndTestnet3IsNotOffered() = runTest {
        ops.wallet(id, network = "testnet")
        val vm = vm()
        eventually { vm.state.value.lookup == LookupResult.NeedsNetwork }
        assertEquals(0, opened)
        vm.chooseTestNetwork(Network.TESTNET)
        eventually { vm.state.value.lookup == LookupResult.NetworkNotOffered }
        assertEquals(Network.TESTNET, vm.state.value.network)
    }

    @Test
    fun comingBackFromTheChainServerLooksUpAgainAfterAProblem() = runTest {
        ops.wallet(id)
        val vm = vm()
        eventually { vm.state.value.lookup is LookupResult.NoServer }
        assertEquals(1, opened)
        // The server was fixed on the Chain server screen.
        open = ChainOpen.WrongNetwork(Network.MAINNET)
        vm.resumed()
        eventually { vm.state.value.lookup is LookupResult.ServerWrongNetwork }
        assertEquals(2, opened)
    }

    @Test
    fun comingBackDoesNotLookUpWhileTheNetworkQuestionIsOpen() = runTest {
        ops.wallet(id, network = "testnet")
        val vm = vm()
        eventually { vm.state.value.lookup == LookupResult.NeedsNetwork }
        vm.resumed()
        assertEquals(0, opened)
    }

    @Test
    fun aVaultWithoutWalletsClosesTheScreen() = runTest {
        ops.unsupported = true
        val m = manager(backgroundScope, ops, WalletFlags(flagsFile), chain)
        runCatching { m.open() }
        val vm = WalletDetailViewModel(SavedStateHandle(mapOf("walletId" to id)), m)
        eventually { vm.state.value.closed }
    }

    @Test
    fun testnet4IsKeptAndTheLookupRunsAgain() = runTest {
        ops.wallet(id, network = "testnet")
        val vm = vm()
        eventually { vm.state.value.lookup == LookupResult.NeedsNetwork }
        vm.chooseTestNetwork(Network.TESTNET4)
        eventually { vm.state.value.lookup is LookupResult.NoServer }
        assertEquals(Network.TESTNET4, vm.state.value.network)
        assertEquals(1, opened)
    }

    @Test
    fun aVaultFailureOfTheLookupIsShown() = runTest {
        ops.wallet(id)
        open = ChainOpen.Ready("mempool.space") { error("not asked") }
        ops.addressesFail = true
        val vm = vm()
        eventually { vm.state.value.lookup is LookupResult.VaultFailed }
        assertNotNull(vm.state.value.lookup)
    }

    @Test
    fun theReceivingAddressTypeIsChangedOrItsFailureIsShown() = runTest {
        ops.wallet(id)
        val vm = vm()
        eventually { vm.state.value.loaded }
        vm.setAddressType(AddressTypes.SEGWIT)
        eventually { vm.state.value.addressType == AddressTypes.SEGWIT }
        assertTrue("update:1:p2wpkh" in ops.calls)
        // The same type again asks nothing.
        vm.setAddressType(AddressTypes.SEGWIT)
        assertEquals(1, ops.calls.count { it.startsWith("update") })
        ops.conflict = true
        vm.setAddressType(AddressTypes.TAPROOT)
        eventually { vm.state.value.typeError == FailureKind.CONFLICT }
        assertEquals(AddressTypes.SEGWIT, vm.state.value.addressType)
        vm.dismissTypeError()
        assertEquals(null, vm.state.value.typeError)
    }

    @Test
    fun deleteAsksTheWarningThenThePasswordBlanksItAndLeaves() = runTest {
        ops.wallet(id)
        val vm = vm()
        eventually { vm.state.value.loaded }
        vm.askDelete()
        assertEquals(DeleteStep.CONFIRM, vm.state.value.delete)
        vm.cancelDelete()
        assertEquals(DeleteStep.NONE, vm.state.value.delete)
        vm.askDelete()
        // The password cannot be typed or sent before the warning is accepted.
        vm.delete()
        assertTrue(ops.calls.none { it.startsWith("delete") })
        vm.continueDelete()
        assertEquals(DeleteStep.PASSWORD, vm.state.value.delete)
        vm.setPassword("pw")
        vm.delete()
        assertEquals("", vm.state.value.password)
        eventually { vm.state.value.closed }
        assertTrue("delete:$id" in ops.calls)
    }

    @Test
    fun aWrongPasswordStaysWithItsTextAndANewTry() = runTest {
        ops.wallet(id)
        val vm = vm()
        eventually { vm.state.value.loaded }
        vm.askDelete()
        vm.continueDelete()
        vm.setPassword("wrong")
        vm.delete()
        assertEquals("", vm.state.value.password)
        eventually { vm.state.value.deleteError != null }
        assertEquals(FailureKind.BAD_PASSWORD, vm.state.value.deleteError)
        assertFalse(vm.state.value.closed)
        assertEquals(DeleteStep.PASSWORD, vm.state.value.delete)
        vm.setPassword("pw")
        assertEquals(null, vm.state.value.deleteError)
        vm.delete()
        eventually { vm.state.value.closed }
    }

    @Test
    fun aWalletNotBackedUpIsSaidSoAndTheStatePrintsNothing() = runTest {
        ops.wallet(id, name = "Savings")
        val vm = vm()
        eventually { vm.state.value.loaded }
        assertFalse(vm.state.value.backedUp)
        vm.askDelete()
        vm.continueDelete()
        vm.setPassword("hunter2")
        for (s in listOf("Savings", "hunter2", id)) assertFalse(vm.state.value.toString().contains(s))
    }

    @Test
    fun theScreenLeavesWhenTheVaultCloses() = runTest {
        ops.wallet(id)
        val m = manager(backgroundScope, ops, WalletFlags(flagsFile).also { it.setPrivacyNoteShown(id) }, chain)
        m.open()
        val vm = WalletDetailViewModel(SavedStateHandle(mapOf("walletId" to id)), m)
        eventually { vm.state.value.loaded }
        m.clear()
        eventually { vm.state.value.closed }
    }
}
