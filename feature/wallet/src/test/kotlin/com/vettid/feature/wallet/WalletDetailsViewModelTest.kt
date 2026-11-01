package com.vettid.feature.wallet

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.chain.ChainDefaults
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.Network
import com.vettid.core.chain.SaveResult
import com.vettid.core.wallet.ChainServerSettings
import com.vettid.core.wallet.VaultAccount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The wallet's details: what the vault gave, the chain server's host, and nothing printed. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WalletDetailsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val ops = FakeWallets()
    private val id = "01WALLET0000000000000000AA"
    private val settings = object : ChainServerSettings {
        var server: ChainServer = ChainServer.Default(ChainDefaults.TESTNET4)

        override suspend fun server(network: Network) = server

        override suspend fun save(network: Network, text: String): SaveResult = error("not here")

        override suspend fun reset(network: Network): Boolean = error("not here")
    }

    @Test
    fun theFingerprintAccountsAndHostAreShown() = runTest {
        ops.wallets[id] = ops.wallet(id).copy(
            accounts = listOf(
                VaultAccount("p2tr", "m/86'/1'/0'", "tpubX", "tr(R/0/*)", "tr(R/1/*)", 1, 0),
                VaultAccount("p2wpkh", "m/84'/1'/0'", "tpubY", "wpkh(R/0/*)", "wpkh(R/1/*)", 0, 0),
            ),
        )
        val m = manager(backgroundScope, ops)
        m.open()
        val vm = WalletDetailsViewModel(SavedStateHandle(mapOf("walletId" to id)), m, settings)
        eventually { vm.state.value.loaded }
        val s = vm.state.value
        assertEquals("73c5da0a", s.fingerprint)
        assertEquals(Network.TESTNET4, s.network)
        assertEquals("testnet4", s.networkName)
        assertEquals("mempool.space", s.host)
        assertEquals(listOf("p2tr", "p2wpkh"), s.accounts.map { it.type })
        assertEquals("tr(R/1/*)", s.accounts[0].change)
        for (secret in listOf("73c5da0a", "tpub", "tr(R", "m/86")) assertFalse(s.toString().contains(secret))
        assertFalse(s.accounts[0].toString().contains("tr("))
    }

    @Test
    fun theHostIsReadAgainWhenTheScreenIsInFrontAgain() = runTest {
        ops.wallet(id)
        val m = manager(backgroundScope, ops)
        m.open()
        val vm = WalletDetailsViewModel(SavedStateHandle(mapOf("walletId" to id)), m, settings)
        eventually { vm.state.value.host == "mempool.space" }
        settings.server = ChainServer.NotSet
        vm.resumed()
        eventually { vm.state.value.host == null }
    }

    @Test
    fun aVaultWithoutWalletsClosesTheScreen() = runTest {
        ops.unsupported = true
        val m = manager(backgroundScope, ops)
        runCatching { m.open() }
        val vm = WalletDetailsViewModel(SavedStateHandle(mapOf("walletId" to id)), m, settings)
        eventually { vm.state.value.closed }
    }

    @Test
    fun withoutAServerThereIsNoHostAndWhenTheVaultClosesTheScreenLeaves() = runTest {
        ops.wallet(id)
        settings.server = ChainServer.NotSet
        val m = manager(backgroundScope, ops)
        m.open()
        val vm = WalletDetailsViewModel(SavedStateHandle(mapOf("walletId" to id)), m, settings)
        eventually { vm.state.value.loaded }
        assertEquals(null, vm.state.value.host)
        m.clear()
        eventually { vm.state.value.closed }
    }
}
