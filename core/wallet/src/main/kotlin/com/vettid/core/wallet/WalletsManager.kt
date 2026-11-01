package com.vettid.core.wallet

import com.vettid.core.chain.Account
import com.vettid.core.chain.AccountType
import com.vettid.core.chain.ChainError
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.Network
import com.vettid.core.chain.Wallet
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.vaultGuard
import com.vettid.core.vault.ADDRESS_LIST_MAX
import com.vettid.core.vault.ADDRESS_USED_MAX
import com.vettid.core.vault.VaultJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** Whether the vault has wallets (it answers `wallet.list`); asked once per unlock. */
enum class WalletAvailability { UNKNOWN, AVAILABLE, UNSUPPORTED }

/** The last look-up of a wallet on the chain server: memory only. */
data class WalletLookup(
    val at: Instant,
    /** The server's host, for "Using mempool.space"; never its path. */
    val host: String,
    val confirmedSats: Long,
    /** What the unconfirmed transactions add (or take: negative), for "+ 0.001 incoming". */
    val unconfirmedSats: Long,
    /** The wallet's addresses the server shows funded (any history). */
    val funded: Set<String>,
) {
    override fun toString(): String = "WalletLookup"
}

/** The outcome of [WalletsManager.lookup]; every state shows as what it is (W1c-2), nothing fails silently. */
sealed interface LookupResult {
    data class Done(val lookup: WalletLookup) : LookupResult

    /** A legacy `testnet` wallet the member has not said the test chain of ("Which test network is this wallet on?"). */
    data object NeedsNetwork : LookupResult

    /** The app does not connect to this wallet's network in this build (testnet3, mainnet for now). */
    data object NetworkNotOffered : LookupResult

    /** No server for the network: not set, or the setting could not be read ([server]). */
    data class NoServer(val server: ChainServer) : LookupResult

    /** The server is on another network than it was ([found]). */
    data class ServerWrongNetwork(val found: Network?) : LookupResult

    /** The server did not answer, or answered what Esplora does not ([error]). */
    data class ServerFailed(val error: Exception) : LookupResult {
        // The error's message could name the server's URL.
        override fun toString(): String = "ServerFailed"
    }

    /** The app's derivation differs from the vault's addresses: "This wallet's addresses don't match your vault's. Nothing was sent." */
    data object AddressMismatch : LookupResult

    /** The vault could not be asked ([failure]). */
    data class VaultFailed(val failure: VaultFailure) : LookupResult {
        override fun toString(): String = "VaultFailed"
    }

    /**
     * The chain server's privacy note was not shown for this wallet: nothing is sent to the server before it is (the screens show it
     * first; this is the check behind them).
     */
    data object NoteNeeded : LookupResult {
        override fun toString(): String = "NoteNeeded"
    }

    /** The vault was closed (locked, held, wiped) while the look-up ran: nothing of it is kept, nothing more was asked. */
    data object Cleared : LookupResult
}

/** The outcome of [WalletsManager.receiveAddress] and [WalletsManager.newReceiveAddress]. */
sealed interface ReceiveResult {
    /** The vault's address, which the app's own derivation also gives at that index. */
    data class Address(val address: WalletAddress) : ReceiveResult {
        override fun toString(): String = "Address"
    }

    /** A legacy `testnet` wallet whose test chain was not said yet. */
    data object NeedsNetwork : ReceiveResult

    /** The app does not connect to this wallet's network in this build: no address is given. */
    data object NotOffered : ReceiveResult

    /** The app's derivation differs from the vault's: no address is shown. */
    data object Mismatch : ReceiveResult
}

/** The result of [WalletsManager.create]. */
data class Created(
    val wallet: VaultWallet,
    /** The new phrase, to show and check; null when the reveal right after the create failed ([revealFailure]). */
    val phrase: RevealedPhrase?,
    val revealFailure: VaultFailure? = null,
)

/**
 * The member's wallets (ANDROID-PLAN §4 "Wallet"; VAULT-MESSAGING §10.18), in memory only: the wallets, account keys,
 * addresses, balances and look-ups are cleared at lock, on entering the hold, at a wipe and with the process ([clear]); each open
 * looks them up again. On disk only [WalletFlags] and the chain server setting. A [generation] guard drops whatever was read before
 * a clear.
 */
@Suppress("TooManyFunctions")
class WalletsManager(
    private val scope: CoroutineScope,
    private val ops: () -> WalletOps,
    private val chain: ChainSource,
    private val flags: WalletFlags,
    /** Whether the build connects to [Network]: signet and testnet4 (regtest in devStack). */
    private val offered: (Network) -> Boolean,
    private val clock: () -> Instant = Instant::now,
) {
    private val availabilityFlow = MutableStateFlow(WalletAvailability.UNKNOWN)
    val availability: StateFlow<WalletAvailability> = availabilityFlow.asStateFlow()

    /** Whether the vault is open for the wallets (unlocked, not held or due): the drawer entry is shown while it is. */
    private val openFlow = MutableStateFlow(false)
    val isOpen: StateFlow<Boolean> = openFlow.asStateFlow()

    private val walletsFlow = MutableStateFlow<List<VaultWallet>>(emptyList())
    val wallets: StateFlow<List<VaultWallet>> = walletsFlow.asStateFlow()

    /** Moves at every change of this phone's flags (backed up, the legacy test chain): the screens that show them read them again. */
    private val flagsChangedFlow = MutableStateFlow(0)
    val flagsChanged: StateFlow<Int> = flagsChangedFlow.asStateFlow()

    private val lookupsFlow = MutableStateFlow<Map<String, WalletLookup>>(emptyMap())
    val lookups: StateFlow<Map<String, WalletLookup>> = lookupsFlow.asStateFlow()

    /**
     * Guards [generation], [open], [checked] and every publish: a [clear] moves the generation and resets everything at once, and
     * nothing read before it is published after it.
     */
    private val lock = Any()
    private var generation = 0L
    private var open = false

    /** (wallet, network) pairs whose addresses were checked against the vault's this session, with the app's derivation. */
    private val checked = HashMap<Pair<String, Network>, Wallet>()
    private var pendingRefresh: Job? = null

    /**
     * Follows the vault: [open] is the open session's identity (the count of the vault's openings) while it is open (unlocked and not
     * held), and null while it is not. An open value reads the wallets ([open]); null closes them ([close]: forgotten, and
     * [onClosed] run), whatever came before. A different identity than the last open one means a lock came between, even if its
     * null was conflated away: the wallets are closed first, then read again, so nothing of the session before outlives it. A new
     * value cancels the work of the last (`collectLatest`), so a read in flight never holds a close back. [events] are the
     * vault's messages (`sync.event` `wallet.*`).
     */
    fun follow(open: Flow<Int?>, events: Flow<Pair<String, JsonObject>>, onClosed: suspend () -> Unit = {}): List<Job> = listOf(
        scope.launch {
            var lastOpen: Int? = null
            open.collectLatest { session ->
                val before = lastOpen
                if (session != null) lastOpen = session
                when {
                    session == null -> close(onClosed)
                    before != null && before != session -> {
                        close(onClosed)
                        runCatching { open() }
                    }
                    else -> runCatching { open() }
                }
            }
        },
        scope.launch { events.collect { (type, body) -> onEvent(type, body) } },
    )

    /** The vault is open: the wallets are read. */
    suspend fun open() {
        synchronized(lock) {
            open = true
            openFlow.value = true
        }
        refresh()
    }

    /** The vault is closed (locked, held, due): everything is forgotten, then [onClosed] runs (the servers' checks are reset). */
    suspend fun close(onClosed: suspend () -> Unit = {}) {
        clear()
        // Not cancelled partway (a new value of `follow`'s flow): once cleared, the servers' checks are always reset too.
        withContext(NonCancellable) { onClosed() }
    }

    /** Lock, hold, wipe: forget everything, at once. */
    fun clear() {
        synchronized(lock) {
            generation++
            open = false
            openFlow.value = false
            pendingRefresh?.cancel()
            pendingRefresh = null
            availabilityFlow.value = WalletAvailability.UNKNOWN
            walletsFlow.value = emptyList()
            lookupsFlow.value = emptyMap()
            checked.clear()
        }
    }

    private fun gen(): Long = synchronized(lock) { generation }

    /** Runs [update] only if nothing was cleared since [g]; false when it was (the update is dropped). */
    private inline fun publish(g: Long, update: () -> Unit): Boolean = synchronized(lock) {
        if (g != generation) return@synchronized false
        update()
        true
    }

    /**
     * Reads the wallets (`wallet.list`), only while the vault is open; a vault without them is [WalletAvailability.UNSUPPORTED]
     * until the next unlock.
     */
    suspend fun refresh() {
        val g = synchronized(lock) { if (!open) return else generation }
        if (availabilityFlow.value == WalletAvailability.UNSUPPORTED) return
        val list = try {
            vaultGuard { ops().list() }
        } catch (e: VaultFailure) {
            if (e.kind == FailureKind.NOT_SUPPORTED) publish(g) { availabilityFlow.value = WalletAvailability.UNSUPPORTED }
            throw e
        }
        publish(g) {
            walletsFlow.value = list
            availabilityFlow.value = WalletAvailability.AVAILABLE
            // A wallet gone (deleted on another device): its look-up goes too.
            lookupsFlow.update { m -> m.filterKeys { id -> list.any { it.id == id } } }
        }
    }

    /** `sync.event` `wallet.changed`, `wallet.deleted`, `wallet.signed`: the list again, once the burst is over (only while open). */
    @Suppress("ReturnCount")
    fun onEvent(type: String, body: JsonObject) {
        if (type != "sync.event") return
        val kind = VaultJson.str(body, "kind") ?: return
        if (kind !in EVENTS) return
        synchronized(lock) {
            if (!open || availabilityFlow.value != WalletAvailability.AVAILABLE) return
            if (kind == "wallet.deleted") {
                VaultJson.str(body, "wallet_id")?.let { id ->
                    walletsFlow.update { l -> l.filterNot { it.id == id } }
                    lookupsFlow.update { it - id }
                    checked.keys.removeAll { it.first == id }
                    scope.launch { flags.forget(id) }
                }
            }
            pendingRefresh?.cancel()
            pendingRefresh = scope.launch {
                delay(EVENT_DEBOUNCE_MS)
                runCatching { refresh() }
            }
        }
    }

    /** Whether the build connects to [network] (signet and testnet4; regtest in devStack). */
    fun offers(network: Network): Boolean = offered(network)

    /** The networks a new wallet can be made on in this build, in the order offered. */
    val creatable: List<Network> get() = listOf(Network.SIGNET, Network.TESTNET4, Network.REGTEST).filter(offered)

    /** The chain [wallet] is on: by its name, or for a legacy `testnet` wallet by what the member said; null: ask. */
    suspend fun networkOf(wallet: VaultWallet): Network? = wallet.network ?: flags.testnetLabel(wallet.id)

    suspend fun setTestNetwork(walletId: String, network: Network): Boolean =
        flags.setTestnetLabel(walletId, network).also { flagsChangedFlow.update { it + 1 } }

    suspend fun backedUp(walletId: String): Boolean = flags.backedUp(walletId)

    /** Whether the chain server's privacy note was shown for [walletId]. */
    suspend fun privacyNoteShown(walletId: String): Boolean = flags.privacyNoteShown(walletId)

    /** The note acknowledged: false if the flag could not be written (the note shows again). */
    suspend fun setPrivacyNoteShown(walletId: String): Boolean =
        flags.setPrivacyNoteShown(walletId).also { flagsChangedFlow.update { it + 1 } }

    /**
     * "Receiving address type": `wallet.update{address_type}` at the wallet's current version. On success the wallet shows the new type
     * and version; on a `conflict` (changed on another device) the list is read again and the failure is thrown.
     */
    suspend fun setAddressType(walletId: String, type: String) {
        val g = gen()
        val before = walletsFlow.value.firstOrNull { it.id == walletId }?.addressType
        val version = try {
            updateType(walletId, type)
        } catch (e: VaultFailure) {
            if (e.kind != FailureKind.CONFLICT) throw e
            // The wallet moved on (an address issued or used elsewhere): read it again. Already the type asked for: done. Another
            // type chosen elsewhere meanwhile: the conflict stands, nothing is overwritten. Otherwise ask once more.
            refresh()
            val now = walletsFlow.value.firstOrNull { it.id == walletId }?.addressType
            if (now == type) return
            if (now != before) throw e
            updateType(walletId, type)
        }
        publish(g) {
            walletsFlow.update { l -> l.map { if (it.id == walletId) it.copy(addressType = type, version = version) else it } }
        }
    }

    private suspend fun updateType(walletId: String, type: String): Long {
        val wallet = walletsFlow.value.firstOrNull { it.id == walletId } ?: throw VaultFailure(FailureKind.OTHER, "no_wallet")
        return vaultGuard { ops().setAddressType(walletId, wallet.version, type) }
    }

    /**
     * Takes the wallet's new state in after this phone issued or marked addresses (`wallet.get`): the vault's `version` and
     * `next_receive` / `next_change` moved, and a stale one would make the next `wallet.update` a conflict and a look-up skip an
     * address just issued. Best effort: a failed read leaves what was there.
     */
    private suspend fun reload(walletId: String, g: Long) {
        try {
            val w = vaultGuard { ops().get(walletId) }
            publish(g) { walletsFlow.update { l -> l.map { if (it.id == walletId) w else it } } }
        } catch (_: VaultFailure) {
            // Kept as it was.
        }
    }

    /** The phrase checked: "Backed up". False if the flag could not be written (it shows "Not backed up" again). */
    suspend fun confirmBackup(walletId: String): Boolean = flags.setBackedUp(walletId).also { flagsChangedFlow.update { it + 1 } }

    /**
     * New wallet (ANDROID-PLAN §4 "Creating and backing up", 1): [password] once, for `wallet.create` and at once after its answer
     * `item.reveal` of the new item. A failed reveal leaves the wallet ("Not backed up"; the check is offered from its row). A lock
     * in between: the wallet is in the vault, not in memory, and nothing is revealed.
     */
    suspend fun create(name: String, network: Network, password: String): Created {
        require(offered(network) && network != Network.MAINNET) { "not a network to create on" }
        val g = gen()
        val wallet = vaultGuard { ops().create(name, network.vaultName, password) }
        val kept = publish(g) {
            walletsFlow.update { l -> l.filterNot { it.id == wallet.id } + wallet }
            availabilityFlow.value = WalletAvailability.AVAILABLE
        }
        if (!kept) return Created(wallet, null, VaultFailure(FailureKind.OTHER, CLEARED))
        return try {
            val phrase = vaultGuard { ops().revealPhrase(wallet.id, password) }
            if (gen() != g) {
                phrase.wipe()
                Created(wallet, null, VaultFailure(FailureKind.OTHER, CLEARED))
            } else {
                Created(wallet, phrase)
            }
        } catch (e: VaultFailure) {
            Created(wallet, null, e)
        }
    }

    /**
     * The phrase again, for the check of a wallet that is not backed up yet: `item.reveal`, a credential operation. A clear that
     * overtook it (a lock while it was answered): the phrase is wiped and a [VaultFailure] with [CLEARED] is thrown.
     */
    suspend fun revealPhrase(walletId: String, password: String): RevealedPhrase {
        val g = gen()
        val phrase = vaultGuard { ops().revealPhrase(walletId, password) }
        if (gen() != g) {
            phrase.wipe()
            throw VaultFailure(FailureKind.OTHER, CLEARED)
        }
        return phrase
    }

    /** "Delete wallet": `item.delete` of its item with the password. */
    suspend fun delete(walletId: String, password: String) {
        val g = gen()
        // Not cancelled partway: once the request is sent, the vault's answer and what is kept here are taken in together
        // (a caller that leaves must not leave the deleted wallet shown).
        withContext(NonCancellable) {
            vaultGuard { ops().delete(walletId, password) }
            publish(g) {
                walletsFlow.update { l -> l.filterNot { it.id == walletId } }
                lookupsFlow.update { it - walletId }
                checked.keys.removeAll { it.first == walletId }
            }
            flags.forget(walletId)
        }
    }

    /**
     * Receive (ANDROID-PLAN §4 "The chain source", "Receive"): the newest address the vault issued on the receiving account that has
     * not been used (by the vault's marks and the last look-up); a new one only when there is none such. Like a look-up, it first
     * checks the app's derivation against the vault's (vault only, so before the privacy note too), and the address given must be
     * the one the app derives at its index. A network the build does not connect to gives no address.
     */
    suspend fun receiveAddress(walletId: String): ReceiveResult = receive(walletId, fresh = false)

    /** "New address" on Receive: the next address of the receiving account, checked like [receiveAddress]. */
    suspend fun newReceiveAddress(walletId: String): ReceiveResult = receive(walletId, fresh = true)

    @Suppress("ReturnCount")
    private suspend fun receive(walletId: String, fresh: Boolean): ReceiveResult {
        val g = gen()
        val wallet = walletsFlow.value.firstOrNull { it.id == walletId } ?: throw VaultFailure(FailureKind.OTHER, "no_wallet")
        val network = networkOf(wallet) ?: return ReceiveResult.NeedsNetwork
        if (!offered(network)) return ReceiveResult.NotOffered
        val derived = checkedWallet(wallet, network, g) ?: return ReceiveResult.Mismatch
        val now = walletsFlow.value.firstOrNull { it.id == walletId } ?: wallet
        val address = if (fresh) newAddress(walletId) else unusedAddress(now)
        // A lock (a clear) while the vault answered: nothing of the wallet is shown after it.
        if (gen() != g) throw VaultFailure(FailureKind.OTHER, CLEARED)
        return if (derives(derived, address)) ReceiveResult.Address(address) else ReceiveResult.Mismatch
    }

    private suspend fun unusedAddress(wallet: VaultWallet): WalletAddress {
        val newest = vaultGuard { issued(wallet.id, AddressChain(wallet.addressType, false), all = true) }.maxByOrNull { it.index }
        val funded = lookupsFlow.value[wallet.id]?.funded.orEmpty()
        if (newest != null && !newest.used && newest.address !in funded) return newest
        return newAddress(wallet.id)
    }

    /** The address is a receive address the app's own derivation gives at the same index. */
    private fun derives(derived: Wallet, a: WalletAddress): Boolean {
        val type = AccountType.fromVaultName(a.type) ?: return false
        return !a.change && a.index in 0 until Account.MAX_INDEX && derived.account(type).address(false, a.index) == a.address
    }

    /** `wallet.address.new` on the receiving account (unchecked: Receive uses [newReceiveAddress]); the wallet is read again after. */
    suspend fun newAddress(walletId: String): WalletAddress {
        val g = gen()
        val a = vaultGuard { ops().newAddress(walletId) }
        reload(walletId, g)
        return a
    }

    /**
     * A look-up (ANDROID-PLAN §4 "The chain source", "How the app uses it with the vault"): the server for the wallet's network, the
     * address check once per session, then both accounts' receive and change chains up to the higher of the vault's next index and
     * the last address with history, plus a gap of [GAP]; the totals; and the funded addresses the vault issued (below its next
     * index on each chain) reported with `wallet.address.used` (at most 256 per request). Nothing is sent to the server before the
     * check passes, and nothing more once the vault is closed: a look-up a clear overtook ends [LookupResult.Cleared].
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    suspend fun lookup(walletId: String): LookupResult {
        val g = gen()
        val wallet = walletsFlow.value.firstOrNull { it.id == walletId }
            ?: return LookupResult.VaultFailed(VaultFailure(FailureKind.OTHER, "no_wallet"))
        val network = networkOf(wallet) ?: return LookupResult.NeedsNetwork
        if (!offered(network)) return LookupResult.NetworkNotOffered
        if (!flags.privacyNoteShown(walletId)) return LookupResult.NoteNeeded
        val ready = when (val open = chain.open(network)) {
            is ChainOpen.Ready -> open
            is ChainOpen.NoServer -> return LookupResult.NoServer(open.server)
            is ChainOpen.WrongNetwork -> return LookupResult.ServerWrongNetwork(open.found)
            is ChainOpen.Unchecked -> return LookupResult.ServerFailed(open.error)
        }
        val derived = try {
            checkedWallet(wallet, network, g) ?: return LookupResult.AddressMismatch
        } catch (e: VaultFailure) {
            return LookupResult.VaultFailed(e)
        }
        if (gen() != g) return LookupResult.Cleared
        // The check may have issued the first address: the wallet as it is now.
        val now = walletsFlow.value.firstOrNull { it.id == walletId } ?: wallet
        val scan = try {
            scan(now, derived, ready, g) ?: return LookupResult.Cleared
        } catch (e: ChainError) {
            return LookupResult.ServerFailed(e)
        }
        val lookup = WalletLookup(clock(), ready.host, scan.confirmed, scan.unconfirmed, scan.funded.map { it.address }.toSet())
        // Only while the wallet is still listed: a look-up that ends after a delete must not bring it back.
        var listed = false
        if (!publish(g) {
                listed = walletsFlow.value.any { it.id == walletId }
                if (listed) lookupsFlow.update { it + (walletId to lookup) }
            }
        ) {
            return LookupResult.Cleared
        }
        if (!listed) return LookupResult.VaultFailed(VaultFailure(FailureKind.OTHER, "no_wallet"))
        try {
            for (chunk in reportable(now, scan.funded).chunked(ADDRESS_USED_MAX)) {
                if (gen() != g) return LookupResult.Cleared
                val marked = vaultGuard { ops().markUsed(walletId, chunk) }
                // `wallet.address.used` moves the wallet's version when it marked something: only then is it read again.
                if (marked > 0) reload(walletId, g)
            }
        } catch (e: VaultFailure) {
            // The balance stands; the marks go again with the next look-up.
            return LookupResult.VaultFailed(e)
        }
        return LookupResult.Done(lookup)
    }

    /**
     * The funded addresses to report: only those the vault issued (each chain's indices below its `next_receive` / `next_change`;
     * a gap address the app found is not the vault's), sorted, at most [MAX_ISSUED].
     */
    private fun reportable(vault: VaultWallet, funded: List<Funded>): List<String> = funded.filter { f ->
        val a = vault.account(f.type) ?: return@filter false
        f.index < if (f.change) a.nextChange else a.nextReceive
    }.map { it.address }.sorted().take(MAX_ISSUED)

    /** The app's derivation, once its first addresses of each chain equal the vault's; null on any difference. */
    private suspend fun checkedWallet(wallet: VaultWallet, network: Network, g: Long): Wallet? {
        val key = wallet.id to network
        synchronized(lock) { checked[key] }?.let { return it }
        // The receiving chain has an address by the first look-up (the one Receive shows), so that the check always compares one.
        val receiving = AddressChain(wallet.addressType, false)
        if (vaultGuard { issued(wallet.id, receiving, all = false) }.isEmpty()) newAddress(wallet.id)
        val issued = CHAINS.associateWith { c -> vaultGuard { issued(wallet.id, c, all = false) } }
        return when (val r = AddressCheck.check(wallet, network, issued)) {
            is AddressCheckResult.Match -> r.wallet.also {
                publish(g) { if (walletsFlow.value.any { w -> w.id == wallet.id }) checked[key] = it }
            }
            AddressCheckResult.Mismatch -> null
        }
    }

    /** The addresses the vault issued on [chain]: the first [AddressCheck.FIRST], or all of them. */
    @Suppress("ReturnCount")
    private suspend fun issued(walletId: String, chain: AddressChain, all: Boolean): List<WalletAddress> {
        if (!all) return ops().addresses(walletId, chain, null, AddressCheck.FIRST).addresses
        val out = ArrayList<WalletAddress>()
        var after: Int? = null
        while (true) {
            val page = ops().addresses(walletId, chain, after, ADDRESS_LIST_MAX)
            out += page.addresses
            after = page.next ?: return out
            if (out.size > MAX_ISSUED) return out
        }
    }

    /** A funded address and where it is: its account, chain and index. */
    private data class Funded(val address: String, val type: String, val change: Boolean, val index: Int)

    private class Scan(val confirmed: Long, val unconfirmed: Long, val funded: List<Funded>)

    /** Both accounts' chains, in batches; null as soon as a clear overtook it (no more is asked of the server). */
    @Suppress("NestedBlockDepth")
    private suspend fun scan(vault: VaultWallet, wallet: Wallet, ready: ChainOpen.Ready, g: Long): Scan? = coroutineScope {
        var confirmed = 0L
        var unconfirmed = 0L
        val funded = ArrayList<Funded>()
        for (type in AccountType.entries) {
            val account = wallet.account(type)
            val stated = vault.account(type.vaultName)
            for (change in listOf(false, true)) {
                val next = if (change) stated?.nextChange ?: 0 else stated?.nextReceive ?: 0
                var lastUsed = -1
                var index = 0
                // Up to the higher of the vault's next index and the last address with history, plus the gap.
                while (index < maxOf(next, lastUsed + 1) + GAP && index < Account.MAX_INDEX) {
                    if (gen() != g) return@coroutineScope null
                    val batch = (index until minOf(index + BATCH, maxOf(next, lastUsed + 1) + GAP)).toList()
                    val sums = batch.map { i -> async { i to account.address(change, i).let { a -> a to ready.summary(a) } } }.awaitAll()
                    for ((i, pair) in sums) {
                        val (address, s) = pair
                        confirmed += s.confirmedSats
                        unconfirmed += s.unconfirmedSats
                        if (s.txCount > 0) {
                            funded += Funded(address, type.vaultName, change, i)
                            lastUsed = maxOf(lastUsed, i)
                        }
                    }
                    index += batch.size
                }
            }
        }
        Scan(confirmed, unconfirmed, funded)
    }

    companion object {
        /** BIP44's gap: addresses past the last used one that are looked at before a chain is taken as ended. */
        const val GAP = 20

        /** Addresses asked about at once. */
        const val BATCH = 5

        /** The vault issues at most 2,000 per wallet. */
        const val MAX_ISSUED = 2_000

        const val EVENT_DEBOUNCE_MS = 300L

        val EVENTS = setOf("wallet.changed", "wallet.deleted", "wallet.signed")

        /** What a create or a look-up that a clear overtook answers. */
        const val CLEARED = "cleared"

        /** The four chains: both accounts, receive and change. */
        val CHAINS = AccountType.entries.flatMap { t -> listOf(AddressChain(t.vaultName, false), AddressChain(t.vaultName, true)) }
    }
}
