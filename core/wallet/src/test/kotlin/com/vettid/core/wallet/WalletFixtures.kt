// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.wallet

import com.vettid.core.chain.Account
import com.vettid.core.chain.ChainError
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.ChainSummary
import com.vettid.core.chain.ChainUtxo
import com.vettid.core.chain.InspectResult
import com.vettid.core.chain.InspectSummary
import com.vettid.core.chain.Network
import com.vettid.core.chain.PsbtBase64
import com.vettid.core.chain.PsbtInspector
import com.vettid.core.chain.Tx
import com.vettid.core.chain.Wallet
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultOpException
import com.vettid.core.vault.WalletImport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList

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

    fun walletJson(id: String = "01WALLET0000000000000000AA", network: String = "testnet4", nextReceive: Int = 1, nextChange: Int = 0, fingerprint: String = FP, path86: String = "m/86'/1'/0'", caps: String? = DEFAULT_CAPS) = """
        {"wallet_id":"$id","version":4,"name":"Savings","network":"$network","fingerprint":"$fingerprint","address_type":"p2tr",
         "accounts":[
          {"type":"p2tr","path":"$path86","xpub":"$TPUB86","descriptors":{"receive":"tr([$FP/86'/1'/0']$TPUB86/0/*)","change":"tr([$FP/86'/1'/0']$TPUB86/1/*)"},"next_receive":$nextReceive,"next_change":$nextChange},
          {"type":"p2wpkh","path":"m/84'/1'/0'","xpub":"$TPUB84","descriptors":{"receive":"wpkh([$FP/84'/1'/0']$TPUB84/0/*)","change":"wpkh([$FP/84'/1'/0']$TPUB84/1/*)"},"next_receive":0,"next_change":0}],
         "tags":[],${caps?.let { "\"caps\":$it," } ?: ""}"created_at":"2026-10-31T12:00:00Z"}"""

    /** A vault with spending limits (release 26) always gives `caps`; none are set. */
    const val DEFAULT_CAPS = """{"period_seconds":86400,"spent_sats":0}"""

    fun wallet(id: String = "01WALLET0000000000000000AA", network: String = "testnet4", nextReceive: Int = 1, caps: String? = DEFAULT_CAPS): VaultWallet =
        WalletWire.wallet(VaultJson.parseObject(walletJson(id, network, nextReceive, caps = caps).encodeToByteArray()))

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
    /** What was asked, in order. Thread-safe to read while a call on another thread adds to it. */
    val calls: MutableList<String> = CopyOnWriteArrayList()
    var unsupported = false
    var revealFails = false
    var lie: ((WalletAddress) -> WalletAddress)? = null
    var listGate: CompletableDeferred<Unit>? = null
    var created = 0

    /** A vault before release 26: its wallets have no `caps`. */
    var noCaps = false
    var conflict = false
    var nextLie: String? = null

    private fun refuse(type: String, code: String, message: String = ""): Nothing = throw VaultOpException(type, code, message)

    /** The words and passphrase of the last import (`wallet.create{item}`), as sent. */
    var importedWords: String? = null
    var importedPassphrase: String? = null

    /** The PIN and password this vault takes for `wallet.caps.raise` and a capped reveal. */
    var pin = "123456"

    /** A vault error code for `wallet.caps.set` / `wallet.caps.raise`, else none. */
    var capsSetFails: String? = null
    var capsRaiseFails: String? = null

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

    override suspend fun create(name: String, network: String, password: String, item: WalletImport?): VaultWallet {
        calls += "create:$network"
        if (item != null) {
            calls += "import"
            importedWords = String(item.mnemonic)
            importedPassphrase = item.passphrase?.let(::String)
        }
        if (password != "pw") refuse("wallet.create", "bad_password")
        val w = WalletFixtures.wallet(
            id = "01WALLET%018d".format(++created), network = network, nextReceive = 0, caps = WalletFixtures.DEFAULT_CAPS.takeUnless { noCaps },
        )
        wallets[w.id] = w
        return w
    }

    override suspend fun revealPhrase(walletId: String, password: String, pin: String?): RevealedPhrase {
        calls += "reveal"
        if (wallets[walletId]?.capped == true) {
            // §10.18: the item of a capped wallet needs the PIN (`bad_request`, message `pin_required`); a wrong one is `bad_pin`.
            if (pin == null) refuse("item.reveal", "bad_request", "pin_required")
            if (pin != this.pin) refuse("item.reveal", "bad_pin")
        }
        if (revealFails || password != "pw") refuse("item.reveal", "bad_password")
        return RevealedPhrase(List(24) { "abandon" }.joinToString(" ").toCharArray())
    }

    override suspend fun capsSet(walletId: String, version: Long, perTx: Long?, perPeriod: Long?): Long {
        calls += "caps.set:$perTx:$perPeriod"
        capsSetFails?.let { refuse("wallet.caps.set", it) }
        val w = wallets.getValue(walletId)
        if (version != w.version) refuse("wallet.caps.set", "conflict")
        // Lowers only: a value above the current one is `bad_request` (`use_wallet_caps_raise`).
        if (perTx != null && w.perTxSats != null && perTx > w.perTxSats) refuse("wallet.caps.set", "bad_request", "use_wallet_caps_raise")
        if (perPeriod != null && w.perPeriodSats != null && perPeriod > w.perPeriodSats) {
            refuse("wallet.caps.set", "bad_request", "use_wallet_caps_raise")
        }
        return (version + 1).also {
            wallets[walletId] = w.copy(version = it, capped = true, perTxSats = perTx ?: w.perTxSats, perPeriodSats = perPeriod ?: w.perPeriodSats)
        }
    }

    override suspend fun capsRaise(walletId: String, version: Long, perTx: Long?, perPeriod: Long?, pin: String, password: String): Long {
        calls += "caps.raise:$perTx:$perPeriod"
        capsRaiseFails?.let { refuse("wallet.caps.raise", it) }
        if (pin != this.pin) refuse("wallet.caps.raise", "bad_pin")
        if (password != "pw") refuse("wallet.caps.raise", "bad_password")
        val w = wallets.getValue(walletId)
        if (version != w.version) refuse("wallet.caps.raise", "conflict")
        return (version + 1).also {
            wallets[walletId] = w.copy(version = it, capped = perTx != null || perPeriod != null, perTxSats = perTx, perPeriodSats = perPeriod)
        }
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

    /** Holds `wallet.address.new` until completed (a lock while the vault answers); [newStarted] says it was asked. */
    var newGate: CompletableDeferred<Unit>? = null
    val newStarted = CompletableDeferred<Unit>()

    override suspend fun newAddress(walletId: String): WalletAddress {
        calls += "new"
        newStarted.complete(Unit)
        newGate?.await()
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

    // --- W2-1: the vault's side of a send ---

    /** The unlock window as this fake vault has it: `wallet.sign` is `credential_locked` while it is closed. */
    var windowOpen: () -> Boolean = { true }

    /** `invalid_psbt` for `wallet.psbt.inspect` with this reason. */
    var inspectReason: String? = null

    /** The vault's summary is altered before it is answered (a vault that saw another payment). */
    var tamper: ((InspectSummary) -> InspectSummary)? = null

    /** The `cap` of the inspect answer (null: a vault without spending limits). */
    var cap: SpendCap? = SpendCap(true, null, null, null)
    val inspected = mutableListOf<String>()

    /** A failure of `wallet.sign` that is not the vault's answer (before the send, or after it without an answer). */
    var signFailure: Exception? = null

    /** An error for `wallet.sign`, thrown as the vault's answer. */
    var signError: VaultOpException? = null

    /** The `wallet.sign` requests, as sent (the PSBT), with the password of each. */
    val signs = mutableListOf<String>()
    val signPasswords = mutableListOf<String>()

    /** The first signing gets no answer; this many resends get none either before the vault answers from its cache. */
    var unanswered = -1
    var resends = 0

    /** What a resend fails with instead of an answer: the vault's cached refusal ([VaultOpException]) or a transport failure. */
    var resendFailure: Exception? = null

    /** The signed transaction answered is not the one that was reviewed. */
    var signedDiffers = false

    private val vaultWallet get() = Wallet(Network.TESTNET4, listOf(WalletFixtures.account("p2tr"), WalletFixtures.account("p2wpkh")))

    override suspend fun inspect(walletId: String, psbtBase64: String): PsbtInspection {
        calls += "inspect"
        inspected += psbtBase64
        inspectReason?.let { refuse("wallet.psbt.inspect", "invalid_psbt", it) }
        val bytes = PsbtBase64.decode(psbtBase64) ?: refuse("wallet.psbt.inspect", "bad_request")
        val summary = when (val r = PsbtInspector.inspect(vaultWallet, bytes)) {
            is InspectResult.Ok -> r.summary
            is InspectResult.Refused -> refuse("wallet.psbt.inspect", "invalid_psbt", r.reason)
        }
        return PsbtInspection(tamper?.invoke(summary) ?: summary, cap)
    }

    override suspend fun sign(walletId: String, psbtBase64: String, password: String): SignOutcome {
        calls += "sign"
        signs += psbtBase64
        signPasswords += password
        if (!windowOpen()) return refused("wallet.sign", "credential_locked")
        signNotSent?.let { return SignOutcome.NotSent(it) }
        signFailure?.let { throw it }
        signError?.let { return SignOutcome.Answered(SignReply.Refused(it)) }
        if (password != "pw") return refused("wallet.sign", "bad_password")
        val signed = signedTx(psbtBase64)
        if (signUnreadable) return SignOutcome.Answered(SignReply.Ok(JsonObject(emptyMap())))
        if (unanswered < 0) return SignOutcome.Answered(SignReply.Ok(body(signed)))
        outstanding += walletId
        return waiting(walletId, signed)
    }

    private fun refused(type: String, code: String) = SignOutcome.Answered(SignReply.Refused(VaultOpException(type, code, "")))

    private fun body(signed: SignedTx) = JsonObject(
        mapOf("txid" to JsonPrimitive(signed.txid), "tx" to JsonPrimitive(signed.tx.joinToString("") { "%02x".format(it) })),
    )

    /** A failure of `wallet.sign` from before the request went out (nothing reached the vault). */
    var signNotSent: Exception? = null

    /** How many kept requests were dropped (a lock, or given up). */
    var forgets = 0

    /** The wallets with a signing the vault may still apply. */
    var outstanding: Set<String> = emptySet()

    /** The vault's clock is past the unanswered request's `exp` and the margin. */
    var expired = false

    /** The signed answer does not read (after the vault signed). */
    var signUnreadable = false

    override suspend fun signsOutstanding(): Set<String> = outstanding

    private fun waiting(walletId: String, signed: SignedTx): SignOutcome.Unanswered = SignOutcome.Unanswered({ forgets++ }, { expired }) {
        calls += "resend"
        resends++
        resendFailure?.let { if (it is VaultOpException) return@Unanswered SignOutcome.Answered(SignReply.Refused(it)) else throw it }
        if (unanswered > 0) {
            unanswered--
            waiting(walletId, signed)
        } else {
            outstanding -= walletId
            SignOutcome.Answered(SignReply.Ok(body(signed)))
        }
    }

    /** The PSBT's unsigned transaction as the "signed" one (inputs are segwit, so the txid is the same). */
    private fun signedTx(psbtBase64: String): SignedTx {
        val raw = PsbtBase64.decode(psbtBase64)!!
        // magic, then the global map's first entry: key 0x00, the unsigned transaction.
        var i = 5
        require(raw[i].toInt() == 1 && raw[i + 1].toInt() == 0)
        i += 2
        val len = raw[i].toInt() and 0xff
        require(len < 0xfd)
        var tx = raw.copyOfRange(i + 1, i + 1 + len)
        if (signedDiffers) tx = tx.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        return SignedTx(tx, Tx.id(tx)!!)
    }

    var history: List<WalletTx> = emptyList()
    var historyFails = false
    var historyThrows: Exception? = null

    override suspend fun history(walletId: String, limit: Int?): List<WalletTx> {
        calls += "history:$limit"
        historyThrows?.let { throw it }
        if (historyFails) throw VaultOpException("wallet.history", "internal")
        return history
    }

    override suspend fun markUsed(walletId: String, addresses: List<String>): Int {
        // As the vault: only addresses not marked before count, and only then does the version move.
        val fresh = addresses.filterNot { a -> marked.any { a in it } }
        marked += addresses
        if (fresh.isNotEmpty()) wallets[walletId]?.let { wallets[walletId] = it.copy(version = it.version + 1) }
        return fresh.size
    }
}

/** The chain server's side of a send: coins, their previous transactions, fee rates, and what was broadcast. */
internal class FakeSpend : SpendAccess {
    val utxos = mutableMapOf<String, MutableList<ChainUtxo>>()
    val rawTxs = mutableMapOf<String, ByteArray>()
    var fees: Map<Int, Double> = mapOf(2 to 12.0, 6 to 5.0, 144 to 1.0)

    /** The bytes of every broadcast, in order. */
    val broadcasts = mutableListOf<ByteArray>()

    /** An error for the next broadcasts (until cleared). */
    var broadcastError: ChainError? = null

    override suspend fun unspent(address: String): List<ChainUtxo> = utxos[address].orEmpty()

    override suspend fun rawTransaction(txid: String): ByteArray = rawTxs[txid] ?: throw ChainError.NotFound()

    override suspend fun feeEstimates(): Map<Int, Double> = fees

    /** The txids this server knows (a broadcast that got through), and what asking fails with. */
    val known = mutableSetOf<String>()
    var knowsError: ChainError? = null
    var knowsAsked = 0

    /** Questions after this many fail (a server that goes away between the two). */
    var knowsFailsFrom = Int.MAX_VALUE

    /** Txids a first question answers "not found" to and every later one finds (a server still taking a transaction). */
    val appearsLater = mutableSetOf<String>()

    val confirmed = mutableSetOf<String>()

    override suspend fun state(txid: String): com.vettid.core.chain.TxState {
        // As the Esplora client: a txid that is not 64 hex digits is a programming error, not an answer.
        require(txid.length == 64) { "not a txid" }
        knowsAsked++
        knowsError?.let { throw it }
        return when (txid) {
            in confirmed -> com.vettid.core.chain.TxState.CONFIRMED
            in known -> com.vettid.core.chain.TxState.UNCONFIRMED
            else -> com.vettid.core.chain.TxState.NOT_FOUND
        }
    }

    override suspend fun knows(txid: String): Boolean {
        knowsAsked++
        knowsError?.let { throw it }
        if (knowsAsked > knowsFailsFrom) throw ChainError.Timeout()
        if (txid in appearsLater) {
            appearsLater -= txid
            known += txid
            return false
        }
        return txid in known
    }

    override suspend fun broadcast(tx: ByteArray): String {
        broadcasts += tx
        broadcastError?.let { throw it }
        return Tx.id(tx)!!
    }
}

/** A chain server answering from a map of address to totals (none: an address without history). */
internal class FakeChain(var open: ChainOpen? = null) : ChainSource {
    val history = mutableMapOf<String, ChainSummary>()
    val asked = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var spend = FakeSpend()

    /** Runs when a server is opened (a lock can come in at that moment). */
    var onOpen: (suspend () -> Unit)? = null

    /** A coin of [sats] at [chain]'s [index], confirmed or not, with the previous transaction that created it. */
    fun fund(chain: AddressChain, index: Int, sats: Long, confirmed: Boolean = true, salt: Int = index) {
        val account = WalletFixtures.account(chain.type)
        val script = account.scriptPubKey(chain.change, index)
        val out = ByteArray(8) { ((sats shr (8 * it)) and 0xff).toByte() }
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        val prev = hex(byteArrayOf(2, 0, 0, 0, 1)) + hex(ByteArray(32) { (salt + 1).toByte() }) + "00000000" + "01" + "51" + "ffffffff" + "01" +
            hex(out) + "%02x".format(script.size) + hex(script) + "00000000"
        val bytes = prev.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val txid = Tx.id(bytes)!!
        spend.rawTxs[txid] = bytes
        val address = account.address(chain.change, index)
        spend.utxos.getOrPut(address) { mutableListOf() } += ChainUtxo(txid, 0, sats, confirmed, if (confirmed) 100L else null)
        history[address] = if (confirmed) ChainSummary(sats, 0, 1, 1) else ChainSummary(0, sats, 0, 1)
    }

    override suspend fun open(network: Network): ChainOpen {
        onOpen?.invoke()
        return openNow()
    }

    private fun openNow(): ChainOpen = open ?: ChainOpen.Ready("mempool.space", spend) { address ->
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
