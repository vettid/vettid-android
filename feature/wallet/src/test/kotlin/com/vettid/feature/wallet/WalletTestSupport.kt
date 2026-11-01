// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.wallet

import com.vettid.core.chain.Account
import com.vettid.core.chain.Network
import com.vettid.core.vault.VaultOpException
import com.vettid.core.vault.WalletImport
import com.vettid.core.wallet.AddressChain
import com.vettid.core.wallet.AddressPage
import com.vettid.core.wallet.ChainOpen
import com.vettid.core.wallet.ChainSource
import com.vettid.core.wallet.RevealedPhrase
import com.vettid.core.wallet.VaultAccount
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
    /** A vault error code for the reveal (e.g. `pin_required`), else none. */
    var revealCode: String? = null
    /** A vault error code for the create (e.g. `limit`), else none. */
    var createFails: String? = null
    var passphrase: String? = null
    var listFails = false
    var unsupported = false
    var deleteFails = false
    var addressesFail = false
    var conflict = false
    var words = List(24) { i -> "word${'a' + (i % 26)}$i" }

    /** The addresses issued per chain, in order. */
    val issued = mutableMapOf<Pair<String, AddressChain>, MutableList<WalletAddress>>()

    /** The vault lists a different address at each index (the app's derivation will not match). */
    var lie = false

    /** The next address the vault issues is this one, not the one the app derives at its index. */
    var nextLie: String? = null

    /** The vector wallet (public test data, as in :core:wallet's fixtures): real accounts, so that the app's checks can pass. */
    fun wallet(id: String, name: String = "Savings", network: String = "testnet4") =
        VaultWallet(id, 1, name, network, FP, "p2tr", vectorAccounts(), emptyList()).also { wallets[id] = it }

    override suspend fun list(): List<VaultWallet> {
        calls += "list"
        if (unsupported) throw VaultOpException("wallet.list", "unsupported_type")
        if (listFails) throw VaultOpException("wallet.list", "internal")
        return wallets.values.toList()
    }

    override suspend fun get(walletId: String) = wallets.getValue(walletId)

    /** The words and passphrase of the last import, as sent. */
    var importedWords: String? = null
    var importedPassphrase: String? = null

    /** A vault error code for `wallet.caps.set`, else none. */
    var capsSetFails: String? = null

    /** The PIN this vault takes. */
    var pin = "123456"

    override suspend fun create(name: String, network: String, password: String, item: WalletImport?): VaultWallet {
        calls += "create:$name:$network:${password.isNotEmpty()}"
        if (item != null) {
            calls += "import"
            importedWords = String(item.mnemonic)
            importedPassphrase = item.passphrase?.let(::String)
        }
        createFails?.let { throw VaultOpException("wallet.create", it) }
        if (password != "pw") throw VaultOpException("wallet.create", "bad_password")
        return wallet("01NEW000000000000000000000", name, network)
    }

    override suspend fun capsSet(walletId: String, version: Long, perTx: Long?, perPeriod: Long?): Long {
        calls += "caps.set:$perTx:$perPeriod"
        capsSetFails?.let { throw VaultOpException("wallet.caps.set", it) }
        val w = wallets.getValue(walletId)
        if (version != w.version) throw VaultOpException("wallet.caps.set", "conflict")
        return (version + 1).also {
            wallets[walletId] = w.copy(version = it, capped = true, perTxSats = perTx ?: w.perTxSats, perPeriodSats = perPeriod ?: w.perPeriodSats)
        }
    }

    override suspend fun capsRaise(walletId: String, version: Long, perTx: Long?, perPeriod: Long?, pin: String, password: String): Long {
        calls += "caps.raise:$perTx:$perPeriod"
        error("the screens of W1d never raise")
    }

    override suspend fun revealPhrase(walletId: String, password: String, pin: String?): RevealedPhrase {
        calls += "reveal:$walletId" + if (pin != null) ":pin" else ""
        revealCode?.let { throw VaultOpException("item.reveal", it) }
        if (wallets[walletId]?.capped == true) {
            if (pin == null) throw VaultOpException("item.reveal", "bad_request", "pin_required")
            if (pin != this.pin) throw VaultOpException("item.reveal", "bad_pin")
        }
        if (revealFails || password != "pw") throw VaultOpException("item.reveal", "bad_password")
        return RevealedPhrase(words.joinToString(" ").toCharArray(), passphrase?.toCharArray())
    }

    override suspend fun delete(walletId: String, password: String) {
        calls += "delete:$walletId"
        if (deleteFails || password != "pw") throw VaultOpException("item.delete", "bad_password")
        wallets.remove(walletId)
    }

    override suspend fun setAddressType(walletId: String, version: Long, type: String): Long {
        calls += "update:$version:$type"
        if (conflict) throw VaultOpException("wallet.update", "conflict")
        return (version + 1).also { wallets[walletId] = wallets.getValue(walletId).copy(addressType = type, version = it) }
    }

    override suspend fun newAddress(walletId: String): WalletAddress {
        calls += "new"
        val w = wallets.getValue(walletId)
        val chain = AddressChain(w.addressType, false)
        val list = issued.getOrPut(walletId to chain) { mutableListOf() }
        val a = WalletAddress(nextLie ?: derive(w.addressType, list.size), w.addressType, list.size, false, false)
        list += a
        // The vault's version and next index move with every address it issues.
        wallets[walletId] = w.copy(
            version = w.version + 1,
            accounts = w.accounts.map { acc -> if (acc.type == w.addressType) acc.copy(nextReceive = list.size) else acc },
        )
        return a
    }

    override suspend fun addresses(walletId: String, chain: AddressChain, after: Int?, limit: Int): AddressPage {
        if (addressesFail) throw VaultOpException("wallet.address.list", "internal")
        val all = issued[walletId to chain].orEmpty().filter { after == null || it.index > after }
        val page = all.take(limit).map { if (lie) it.copy(address = derive(chain.type, it.index + 50)) else it }
        return AddressPage(page, if (all.size > limit) page.last().index else null)
    }

    override suspend fun markUsed(walletId: String, addresses: List<String>) = 0
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

internal fun manager(
    scope: CoroutineScope,
    ops: FakeWallets,
    flags: WalletFlags = WalletFlags(MemoryFlags()),
    chain: ChainSource = ChainSource { ChainOpen.NoServer(com.vettid.core.chain.ChainServer.NotSet) },
) = WalletsManager(
    scope,
    { ops },
    chain,
    flags,
    { it == Network.SIGNET || it == Network.TESTNET4 },
)

/** The chain server setting, without HTTP: whatever [server] says. */
internal class FakeServerSettings(var server: com.vettid.core.chain.ChainServer = com.vettid.core.chain.ChainServer.NotSet) :
    com.vettid.core.wallet.ChainServerSettings {
    override suspend fun server(network: Network) = server

    override suspend fun save(network: Network, text: String): com.vettid.core.chain.SaveResult = error("not here")

    override suspend fun reset(network: Network): Boolean = error("not here")
}

/** The vault calls run on the IO dispatcher: waits for [cond] in real time. */
internal suspend fun eventually(cond: () -> Boolean) {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        kotlinx.coroutines.withTimeout(5_000) {
            while (!cond()) kotlinx.coroutines.delay(10)
        }
    }
}

internal const val FP = "73c5da0a"
private const val TPUB86 = "tpubDDfvzhdVV4unsoKt5aE6dcsNsfeWbTgmLZPi8LQDYU2xixrYemMfWJ3BaVneH3u7DBQePdTwhpybaKRU95pi6PMUtLPBJLVQRpzEnjfjZzX"
private const val TPUB84 = "tpubDC8msFGeGuwnKG9Upg7DM2b4DaRqg3CUZa5g8v2SRQ6K4NSkxUgd7HsL2XVWbVm39yBA4LAxysQAm397zwQSQoQgewGiYZqrA9DsP4zbQ1M"

internal fun vectorAccounts() = listOf(
    VaultAccount("p2tr", "m/86'/1'/0'", TPUB86, "tr([$FP/86'/1'/0']$TPUB86/0/*)", "tr([$FP/86'/1'/0']$TPUB86/1/*)", 0, 0),
    VaultAccount("p2wpkh", "m/84'/1'/0'", TPUB84, "wpkh([$FP/84'/1'/0']$TPUB84/0/*)", "wpkh([$FP/84'/1'/0']$TPUB84/1/*)", 0, 0),
)

/** The receive address of the vector wallet's account of [type] at [index] (testnet encodings). */
internal fun derive(type: String, index: Int): String {
    val a = vectorAccounts().first { it.type == type }
    return Account.fromDescriptors(a.receiveDescriptor, a.changeDescriptor, Network.TESTNET4).address(false, index)
}
