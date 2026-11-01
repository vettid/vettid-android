package com.vettid.feature.wallet

import com.vettid.core.chain.Network
import com.vettid.core.vault.VaultOpException
import com.vettid.core.wallet.AddressChain
import com.vettid.core.wallet.AddressPage
import com.vettid.core.wallet.ChainOpen
import com.vettid.core.wallet.RevealedPhrase
import com.vettid.core.wallet.VaultWallet
import com.vettid.core.wallet.WalletAddress
import com.vettid.core.wallet.WalletFlags
import com.vettid.core.wallet.WalletFlagsFile
import com.vettid.core.wallet.WalletOps
import com.vettid.core.wallet.WalletsManager
import kotlinx.coroutines.CoroutineScope

/** A vault for the screens: wallets as given, a 24-word phrase, and what was asked. */
internal class FakeWallets : WalletOps {
    val wallets = linkedMapOf<String, VaultWallet>()
    val calls = mutableListOf<String>()
    var revealFails = false
    var listFails = false
    var words = List(24) { i -> "word${'a' + (i % 26)}$i" }

    fun wallet(id: String, name: String = "Savings", network: String = "testnet4") =
        VaultWallet(id, 1, name, network, "73c5da0a", "p2tr", emptyList(), emptyList()).also { wallets[id] = it }

    override suspend fun list(): List<VaultWallet> {
        calls += "list"
        if (listFails) throw VaultOpException("wallet.list", "internal")
        return wallets.values.toList()
    }

    override suspend fun get(walletId: String) = wallets.getValue(walletId)

    override suspend fun create(name: String, network: String, password: String): VaultWallet {
        calls += "create:$name:$network:${password.isNotEmpty()}"
        if (password != "pw") throw VaultOpException("wallet.create", "bad_password")
        return wallet("01NEW000000000000000000000", name, network)
    }

    override suspend fun revealPhrase(walletId: String, password: String): RevealedPhrase {
        calls += "reveal:$walletId"
        if (revealFails || password != "pw") throw VaultOpException("item.reveal", "bad_password")
        return RevealedPhrase(words.joinToString(" ").toCharArray())
    }

    override suspend fun delete(walletId: String, password: String) = Unit

    override suspend fun newAddress(walletId: String): WalletAddress = error("not in these screens")

    override suspend fun addresses(walletId: String, chain: AddressChain, after: Int?, limit: Int) = AddressPage(emptyList(), null)

    override suspend fun markUsed(walletId: String, addresses: List<String>) = Unit
}

internal class MemoryFlags : WalletFlagsFile {
    var bytes: ByteArray? = null

    override fun load(): ByteArray? = bytes

    var saves = 0
    var failSaves = false

    override fun save(bytes: ByteArray) {
        if (failSaves) throw java.io.IOException("not now")
        saves++
        this.bytes = bytes
    }
}

internal fun manager(scope: CoroutineScope, ops: FakeWallets, flags: WalletFlags = WalletFlags(MemoryFlags())) = WalletsManager(
    scope,
    { ops },
    { ChainOpen.NoServer(com.vettid.core.chain.ChainServer.NotSet) },
    flags,
    { it == Network.SIGNET || it == Network.TESTNET4 },
)
