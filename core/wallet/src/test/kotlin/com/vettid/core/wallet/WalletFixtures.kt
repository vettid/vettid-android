// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.wallet

import com.vettid.core.chain.Account
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.ChainSummary
import com.vettid.core.chain.Network
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject

/**
 * The vault's testnet4 vector wallet (vettid-vault `features/wallet`, as in :core:chain's `wallet-inspect-vault-214b88f.json`): the
 * BIP84/BIP86 test phrase's account keys. Public test data.
 */
internal object WalletFixtures {
    const val FP = "73c5da0a"
    const val TPUB86 = "tpubDDfvzhdVV4unsoKt5aE6dcsNsfeWbTgmLZPi8LQDYU2xixrYemMfWJ3BaVneH3u7DBQePdTwhpybaKRU95pi6PMUtLPBJLVQRpzEnjfjZzX"
    const val TPUB84 = "tpubDC8msFGeGuwnKG9Upg7DM2b4DaRqg3CUZa5g8v2SRQ6K4NSkxUgd7HsL2XVWbVm39yBA4LAxysQAm397zwQSQoQgewGiYZqrA9DsP4zbQ1M"

    /** The vault's own first P2WPKH receive address of this wallet on testnet4 (AccountTest). */
    const val FIRST_P2WPKH = "tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl"

    fun walletJson(id: String = "01WALLET0000000000000000AA", network: String = "testnet4", nextReceive: Int = 1, nextChange: Int = 0, fingerprint: String = FP, path86: String = "m/86'/1'/0'") = """
        {"wallet_id":"$id","version":4,"name":"Savings","network":"$network","fingerprint":"$fingerprint","address_type":"p2tr",
         "accounts":[
          {"type":"p2tr","path":"$path86","xpub":"$TPUB86","descriptors":{"receive":"tr([$FP/86'/1'/0']$TPUB86/0/*)","change":"tr([$FP/86'/1'/0']$TPUB86/1/*)"},"next_receive":$nextReceive,"next_change":$nextChange},
          {"type":"p2wpkh","path":"m/84'/1'/0'","xpub":"$TPUB84","descriptors":{"receive":"wpkh([$FP/84'/1'/0']$TPUB84/0/*)","change":"wpkh([$FP/84'/1'/0']$TPUB84/1/*)"},"next_receive":0,"next_change":0}],
         "tags":[],"created_at":"2026-10-31T12:00:00Z"}"""

    fun wallet(id: String = "01WALLET0000000000000000AA", network: String = "testnet4", nextReceive: Int = 1): VaultWallet =
        WalletWire.wallet(VaultJson.parseObject(walletJson(id, network, nextReceive).encodeToByteArray()))

    fun account(type: String): Account = if (type == "p2tr") {
        Account.fromDescriptors("tr([$FP/86'/1'/0']$TPUB86/0/*)", "tr([$FP/86'/1'/0']$TPUB86/1/*)", Network.TESTNET4)
    } else {
        Account.fromDescriptors("wpkh([$FP/84'/1'/0']$TPUB84/0/*)", "wpkh([$FP/84'/1'/0']$TPUB84/1/*)", Network.TESTNET4)
    }

    fun address(chain: AddressChain, index: Int, used: Boolean = false) =
        WalletAddress(account(chain.type).address(chain.change, index), chain.type, index, chain.change, used)
}

/** An in-memory vault for the manager: issues the vector wallet's real addresses, and records what was asked. */
internal class FakeWalletOps : WalletOps {
    val wallets = linkedMapOf<String, VaultWallet>()
    val issued = mutableMapOf<Pair<String, AddressChain>, MutableList<WalletAddress>>()
    val marked = mutableListOf<List<String>>()
    val calls = mutableListOf<String>()
    var unsupported = false
    var revealFails = false
    var lie: ((WalletAddress) -> WalletAddress)? = null
    var listGate: CompletableDeferred<Unit>? = null
    var created = 0
    var conflict = false
    var nextLie: String? = null

    private fun refuse(type: String, code: String): Nothing = throw VaultOpException(type, code)

    override suspend fun list(): List<VaultWallet> {
        calls += "list"
        listGate?.await()
        if (unsupported) refuse("wallet.list", "unsupported_type")
        return wallets.values.toList()
    }

    var gets = 0

    override suspend fun get(walletId: String): VaultWallet {
        gets++
        return wallets[walletId] ?: refuse("wallet.get", "not_found")
    }

    override suspend fun create(name: String, network: String, password: String): VaultWallet {
        calls += "create:$network"
        if (password != "pw") refuse("wallet.create", "bad_password")
        val w = WalletFixtures.wallet(id = "01WALLET%018d".format(++created), network = network, nextReceive = 0)
        wallets[w.id] = w
        return w
    }

    override suspend fun revealPhrase(walletId: String, password: String): RevealedPhrase {
        calls += "reveal"
        if (revealFails || password != "pw") refuse("item.reveal", "bad_password")
        return RevealedPhrase(List(24) { "abandon" }.joinToString(" ").toCharArray())
    }

    override suspend fun delete(walletId: String, password: String) {
        calls += "delete"
        if (password != "pw") refuse("item.delete", "bad_password")
        wallets.remove(walletId)
    }

    override suspend fun setAddressType(walletId: String, version: Long, type: String): Long {
        calls += "update:$version:$type"
        val w = wallets.getValue(walletId)
        // Like the vault: a version that is not the wallet's current one is a conflict.
        if (conflict || version != w.version) refuse("wallet.update", "conflict")
        return (version + 1).also { wallets[walletId] = w.copy(addressType = type, version = it) }
    }

    override suspend fun newAddress(walletId: String): WalletAddress {
        calls += "new"
        val w = wallets.getValue(walletId)
        val chain = AddressChain(w.addressType, false)
        val list = issued.getOrPut(walletId to chain) { mutableListOf() }
        return WalletFixtures.address(chain, list.size).let { a -> nextLie?.let { a.copy(address = it) } ?: a }.also {
            list += it
            // The vault's version and next index move with every address it issues.
            wallets[walletId] = w.copy(
                version = w.version + 1,
                accounts = w.accounts.map { a -> if (a.type == chain.type) a.copy(nextReceive = list.size) else a },
            )
        }
    }

    override suspend fun addresses(walletId: String, chain: AddressChain, after: Int?, limit: Int): AddressPage {
        calls += "list:${chain.type}:${chain.change}"
        val all = issued[walletId to chain].orEmpty().filter { after == null || it.index > after }
        val page = all.take(limit).map { a -> lie?.invoke(a) ?: a }
        return AddressPage(page, if (all.size > limit) page.last().index else null)
    }

    override suspend fun markUsed(walletId: String, addresses: List<String>): Int {
        // As the vault: only addresses not marked before count, and only then does the version move.
        val fresh = addresses.filterNot { a -> marked.any { a in it } }
        marked += addresses
        if (fresh.isNotEmpty()) wallets[walletId]?.let { wallets[walletId] = it.copy(version = it.version + 1) }
        return fresh.size
    }
}

/** A chain server answering from a map of address to totals (none: an address without history). */
internal class FakeChain(var open: ChainOpen? = null) : ChainSource {
    val history = mutableMapOf<String, ChainSummary>()
    val asked = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun open(network: Network): ChainOpen = open ?: ChainOpen.Ready("mempool.space") { address ->
        gate?.await()
        synchronized(asked) { asked += address }
        history[address] ?: ChainSummary(0, 0, 0, 0)
    }

    companion object {
        fun noServer(s: ChainServer) = FakeChain(ChainOpen.NoServer(s))
    }
}

internal fun syncEvent(kind: String, walletId: String): JsonObject =
    VaultJson.parseObject("""{"kind":"$kind","wallet_id":"$walletId"}""".encodeToByteArray())
