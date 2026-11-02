package com.vettid.core.wallet

import com.vettid.core.chain.Account
import com.vettid.core.chain.AccountType
import com.vettid.core.chain.BroadcastRefusal
import com.vettid.core.chain.BuildCoin
import com.vettid.core.chain.BuildException
import com.vettid.core.chain.BuildOutput
import com.vettid.core.chain.ChainError
import com.vettid.core.chain.TxState
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.CoinSelection
import com.vettid.core.chain.InspectResult
import com.vettid.core.chain.Network
import com.vettid.core.chain.Payee
import com.vettid.core.chain.PayeeCheck
import com.vettid.core.chain.PsbtBase64
import com.vettid.core.chain.PsbtBuilder
import com.vettid.core.chain.PsbtInspector
import com.vettid.core.chain.PsbtReasons
import com.vettid.core.chain.Selection
import com.vettid.core.chain.Spendable
import com.vettid.core.chain.SummaryCompare
import com.vettid.core.chain.Tx
import com.vettid.core.chain.Wallet
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.vaultGuard
import com.vettid.core.vault.ADDRESS_LIST_MAX
import com.vettid.core.vault.ADDRESS_USED_MAX
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultOpException
import com.vettid.core.vault.WalletImport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    /** The spending limit the member chose has not been set yet (W1d): no Receive until `wallet.caps.set` has been answered. */
    data object CapsPending : ReceiveResult

    /** The pending limit was refused with `duplicate_wallet` (the vault holds another copy of this wallet): no Receive, and the reason. */
    data object CapsDuplicate : ReceiveResult

    /** This phone's wallet settings cannot be read, so no wallet is known to be free of a limit still to be set: no Receive. */
    data object SettingsUnreadable : ReceiveResult

    /**
     * The wallet settings were reset on this phone and the vault shows the wallet without a limit: "Set a spending limit?" is asked
     * again ([WalletsManager.answerLimits]) before any address.
     */
    data object LimitsAsk : ReceiveResult
}

/** The result of [WalletsManager.create]. */
data class Created(
    val wallet: VaultWallet,
    /** The new phrase, to show and check; null when the reveal right after the create failed ([revealFailure]). */
    val phrase: RevealedPhrase?,
    val revealFailure: VaultFailure? = null,
    /** The limits chosen could not be saved on this phone (they are kept for this session only): the screens say so. */
    val capsNotSaved: Boolean = false,
    /** The vault cannot enforce spending limits (its wallet has no `caps`): the limits chosen were not kept or sent; the screens say so. */
    val capsUnsupported: Boolean = false,
)

/** The result of [WalletsManager.import]: the wallet, and why its chosen limits are not set yet (null: set, or none were chosen). */
data class Imported(
    val wallet: VaultWallet,
    val capsFailure: VaultFailure? = null,
    /** This phone could not record the wallet as backed up (it shows "Not backed up"). */
    val notRecorded: Boolean = false,
    /** The limits chosen could not be saved on this phone (they are kept for this session only). */
    val capsNotSaved: Boolean = false,
    /** The vault cannot enforce spending limits (its wallet has no `caps`): the limits chosen were not kept or sent. */
    val capsUnsupported: Boolean = false,
) {
    override fun toString(): String = "Imported"
}

/**
 * The member's wallets (ANDROID-PLAN §4 "Wallet"; VAULT-MESSAGING §10.18), in memory only: the wallets, account keys,
 * addresses, balances and look-ups are cleared at lock, on entering the hold, at a wipe and with the process ([clear]); each open
 * looks them up again. On disk only [WalletFlags] and the chain server setting. A [generation] guard drops whatever was read before
 * a clear.
 */
@Suppress("TooManyFunctions", "LargeClass") // one manager for the wallets, their limits and the flags
class WalletsManager(
    private val scope: CoroutineScope,
    private val ops: () -> WalletOps,
    private val chain: ChainSource,
    private val flags: WalletFlags,
    /** Whether the build connects to [Network]: signet and testnet4 (regtest in devStack). */
    private val offered: (Network) -> Boolean,
    private val clock: () -> Instant = Instant::now,
    /** The credential's unlock window (VAULT-MESSAGING §3.5.3) as this app knows it: open now (W2: spending needs it). */
    private val windowOpen: () -> Boolean = { false },
    /** `credential.unlock` with the password, the app's own path ([com.vettid.core.data.vault.VaultRepository.openUnlockWindow]). */
    private val openWindow: suspend (String) -> Unit = {},
    /** `credential.lock`: closes a window this manager opened for a send. */
    private val closeWindow: suspend () -> Unit = {},
    /** How long a "not found" for a transaction just broadcast is doubted before it is believed ([checkSent]). */
    private val settleMs: Long = 4_000,
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

    /** The limits chosen but not yet carried by `wallet.caps.set`, per wallet (memory; the file is [WalletFlags]). Under [lock]. */
    private val pendingMap = HashMap<String, WalletCaps>()
    private val capsPendingFlow = MutableStateFlow<Set<String>>(emptySet())

    /** The flags file could not be read: nobody knows which wallets have a limit to set, so none is offered Receive. Under [lock]. */
    private var pendingUnknown = false

    /**
     * The wallets whose chosen spending limit is not set yet (W1d): Receive is not offered for them, and nothing else is asked of
     * them before the limit is sent (a restart sends it first, [open]). When this phone's flags cannot be read the set holds
     * [UNKNOWN]: every wallet is then pending (use [isPending]).
     */
    val capsPending: StateFlow<Set<String>> = capsPendingFlow.asStateFlow()

    /** The wallets whose pending `wallet.caps.set` the vault refused with `duplicate_wallet` (memory only). Under [lock]. */
    private val duplicateSet = HashSet<String>()
    private val duplicateFlow = MutableStateFlow<Set<String>>(emptySet())

    /** The wallets whose pending limit was refused because the vault holds another copy of the wallet: the screens say so. */
    val capsDuplicate: StateFlow<Set<String>> = duplicateFlow.asStateFlow()

    /** Wallets made in this session whose backup is under way: their pending limits wait for the check (the phrase needs no PIN). */
    private val held = HashSet<String>()
    private val capsLock = Mutex()

    /** One send at a time (build, sign, broadcast, and the tries again). */
    private val sendLock = Mutex()

    /** The signed transactions not yet broadcast, per wallet: **memory only**, never on disk, dropped at lock. Under [lock]. */
    private val keptTx = HashMap<String, KeptTx>()
    private val unsentFlow = MutableStateFlow<Set<String>>(emptySet())

    /** The wallets with a signing whose answer has not come (resent as the same request), per wallet. Under [lock]. */
    private val pendingSigns = HashMap<String, PendingSign>()

    /** Per wallet: the recent-payments check ran in this session, and whether it found something that was acknowledged. Under [lock]. */
    private class RecentState(val needsAck: Boolean, val acknowledged: Boolean)

    private val recentState = HashMap<String, RecentState>()

    /** The reviewed payments (their unsigned txids) whose `wallet.sign` went out: signed once, never again. Under [lock]. */
    private val consumed = HashSet<String>()

    /**
     * Per wallet, how many `wallet.sign` requests went out and were not given back. A payment prepared before one of them is out of
     * date ([SendResult.Superseded]): its coins may be spent by the newer one. Under [lock].
     */
    private val signCounts = HashMap<String, Long>()

    /** The wallets holding a signed transaction that is not broadcast yet, or a signing still unanswered: Try again is offered. */
    val unsent: StateFlow<Set<String>> = unsentFlow.asStateFlow()

    private val windowNotClosedFlow = MutableStateFlow(false)

    /** A send opened the credential's unlock window and could not close it: it stays open until it closes by itself. */
    val windowNotClosed: StateFlow<Boolean> = windowNotClosedFlow.asStateFlow()

    /**
     * Guards [generation], [open], [checked] and every publish: a [clear] moves the generation and resets everything at once, and
     * nothing read before it is published after it.
     */
    private val lock = Any()
    private var generation = 0L
    private var open = false

    /** A `wallet.list` was published since the vault opened (a created or imported wallet alone does not count). Under [lock]. */
    private var listRead = false

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
                        attempt { open() }
                    }
                    else -> attempt { open() }
                }
            }
        },
        scope.launch { events.collect { (type, body) -> onEvent(type, body) } },
    )

    /**
     * The vault is open: the pending limits are read from this phone's flags (before anything can be offered), the wallets are read,
     * and then, for every wallet with a pending limit, `wallet.caps.set` is sent before anything else is asked of that wallet. A
     * failed send leaves the limit pending: no Receive for that wallet until it is set (Try again on its screen).
     */
    suspend fun open() {
        val g: Long
        synchronized(lock) {
            open = true
            openFlow.value = true
            g = generation
        }
        loadPending(g)
        refresh()
        for (id in synchronized(lock) { pendingMap.keys.toList() }) {
            if (gen() != g) return
            if (synchronized(lock) { id in held }) continue
            attempt { settleCaps(id) }
        }
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
            listRead = false
            lookupsFlow.value = emptyMap()
            checked.clear()
            pendingMap.clear()
            pendingUnknown = false
            publishPending()
            held.clear()
            duplicateSet.clear()
            duplicateFlow.value = emptySet()
            keptTx.clear()
            pendingSigns.values.forEach { it.forget() }
            pendingSigns.clear()
            consumed.clear()
            signCounts.clear()
            recentState.clear()
            unsentFlow.value = emptySet()
        }
    }

    /** Publishes [capsPending] from the pending limits and whether the file could be read (under [lock]). */
    private fun publishPending() {
        capsPendingFlow.value = if (pendingUnknown) pendingMap.keys + UNKNOWN else pendingMap.keys.toSet()
    }

    /**
     * Reads the pending limits from this phone's flags. A file that cannot be read makes every wallet pending ([UNKNOWN]) until a
     * later read works; what is already pending in memory (a write that failed) stays.
     */
    private suspend fun loadPending(g: Long) {
        val stored = flags.allPendingCaps()
        publish(g) {
            pendingUnknown = stored == null
            stored?.forEach { (id, caps) -> pendingMap.putIfAbsent(id, caps) }
            publishPending()
        }
    }

    /** Runs [block]; false if it failed. A cancellation is not a failure: it is passed on. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
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
        val published = publish(g) {
            walletsFlow.value = list
            availabilityFlow.value = WalletAvailability.AVAILABLE
            listRead = true
            // A wallet gone (deleted on another device): its look-up goes too.
            lookupsFlow.update { m -> m.filterKeys { id -> list.any { it.id == id } } }
        }
        // A reset that came before the wallets were listed asks the ones listed now (and no wallet made after it).
        if (published) flags.listed(list.map { it.id }.toSet())
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
                    dropPending(id)
                    scope.launch { flags.forget(id) }
                }
            }
            pendingRefresh?.cancel()
            pendingRefresh = scope.launch {
                delay(EVENT_DEBOUNCE_MS)
                attempt { refresh() }
            }
        }
    }

    /**
     * Whether the vault enforces spending limits as far as the listed wallets show: no listed wallet lacks `caps` (a vault before
     * release 26 gives none). With no wallets yet it is assumed, and `wallet.create`'s answer decides ([Created.capsUnsupported]).
     * When it is false the limits step is not asked and nothing is pending.
     */
    val capsOffered: Boolean get() = walletsFlow.value.all { it.capsSupported }

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
        settleFirst(walletId)
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
     * in between: the wallet is in the vault, not in memory, and nothing is revealed. The wallet is created **without limits**; the
     * [caps] the member chose are kept on this phone as pending at once and sent by [settleCaps] after the phrase check, so the
     * phrase step never needs the PIN. Until then the wallet is held back from [open]'s send and has no Receive.
     */
    suspend fun create(name: String, network: Network, password: String, caps: WalletCaps? = null): Created {
        require(offered(network) && network != Network.MAINNET) { "not a network to create on" }
        require(caps == null || CapsRules.problem(caps) == null) { "limits" }
        val g = gen()
        val wallet = vaultGuard { ops().create(name, network.vaultName, password) }
        // A vault that gives the new wallet no `caps` cannot enforce a limit: nothing is kept or sent for it.
        val unsupported = caps != null && !caps.none && !wallet.capsSupported
        val saved = keepCaps(wallet.id, caps.takeUnless { unsupported }, g, hold = true)
        // The limit was chosen (or No limit) on the step before: a reset later does not ask this wallet's question again.
        flags.clearAskCaps(wallet.id)
        val kept = publish(g) {
            walletsFlow.update { l -> l.filterNot { it.id == wallet.id } + wallet }
            availabilityFlow.value = WalletAvailability.AVAILABLE
        }
        if (!kept) return Created(wallet, null, VaultFailure(FailureKind.OTHER, CLEARED), !saved, unsupported)
        return try {
            val phrase = vaultGuard { ops().revealPhrase(wallet.id, password) }
            if (gen() != g) {
                phrase.wipe()
                Created(wallet, null, VaultFailure(FailureKind.OTHER, CLEARED), !saved, unsupported)
            } else {
                Created(wallet, phrase, capsNotSaved = !saved, capsUnsupported = unsupported)
            }
        } catch (e: VaultFailure) {
            Created(wallet, null, e, !saved, unsupported)
        }
    }

    /**
     * Import a wallet (ANDROID-PLAN §4 screens table, "Import a wallet"): `wallet.create` with the member's [words] (single spaces,
     * checked by the screen against BIP39) and optional [passphrase] as `item`, with [password]. The caller wipes both arrays. An
     * imported wallet starts backed up (the member typed the words), so the [caps] they chose are sent at once; a failure of that
     * leaves them pending and is in [Imported.capsFailure].
     */
    suspend fun import(
        name: String,
        network: Network,
        password: String,
        words: CharArray,
        passphrase: CharArray?,
        caps: WalletCaps? = null,
    ): Imported {
        require(offered(network) && network != Network.MAINNET) { "not a network to create on" }
        require(caps == null || CapsRules.problem(caps) == null) { "limits" }
        val g = gen()
        val wallet = vaultGuard { ops().create(name, network.vaultName, password, WalletImport(words, passphrase)) }
        val unsupported = caps != null && !caps.none && !wallet.capsSupported
        val saved = keepCaps(wallet.id, caps.takeUnless { unsupported }, g, hold = false)
        flags.clearAskCaps(wallet.id)
        // The words were typed, so the wallet starts backed up; a flag that cannot be written is tried once more and then said.
        val recorded = flags.setBackedUp(wallet.id) || flags.setBackedUp(wallet.id)
        flagsChangedFlow.update { it + 1 }
        val kept = publish(g) {
            walletsFlow.update { l -> l.filterNot { it.id == wallet.id } + wallet }
            availabilityFlow.value = WalletAvailability.AVAILABLE
        }
        if (!kept) {
            val lost = if (caps == null || unsupported) null else VaultFailure(FailureKind.OTHER, CLEARED)
            return Imported(wallet, lost, !recorded, !saved, unsupported)
        }
        val failure = try {
            settleCaps(wallet.id)
            null
        } catch (e: VaultFailure) {
            e
        }
        return Imported(wallet, failure, !recorded, !saved && failure != null, unsupported)
    }

    /**
     * The limits chosen for a new wallet, kept on this phone before anything else happens to it (a restart then still sends them).
     * A file that cannot be written leaves them in memory for this session, and the result says so (false).
     */
    private suspend fun keepCaps(walletId: String, caps: WalletCaps?, g: Long, hold: Boolean): Boolean {
        if (caps == null || caps.none) return true
        val saved = flags.setPendingCaps(walletId, caps)
        publish(g) {
            pendingMap[walletId] = caps
            publishPending()
            if (hold) held += walletId
        }
        return saved
    }

    private fun dropPending(walletId: String) {
        synchronized(lock) {
            pendingMap.remove(walletId)
            held.remove(walletId)
            publishPending()
        }
    }

    /**
     * Sends the pending `wallet.caps.set` of [walletId] (nothing when none is pending) at the wallet's version, and forgets the
     * pending limits once it is answered. A `conflict` reads the wallet again and sends once more. This only ever lowers or sets a
     * limit: it never sends `wallet.caps.raise`. A `bad_request` (the wallet already has limits at least as strict) reads the
     * wallet, and if that is so the pending limits are dropped; otherwise, as for any other failure, the limits stay pending, the
     * wallet stays without Receive, and the [VaultFailure] is thrown. Not cancelled once sent.
     */
    suspend fun settleCaps(walletId: String) {
        capsLock.withLock {
            val g = gen()
            if (synchronized(lock) { pendingUnknown }) loadPending(g)
            val caps = synchronized(lock) { pendingMap[walletId] } ?: return
            val version = try {
                sendPending(walletId, caps, g)
            } catch (e: VaultFailure) {
                // The vault holds another copy of this wallet (`duplicate_wallet`): the limit stays pending, Receive stays off, and the
                // screens say why. Not retried here; the next Try again or unlock sends it once more.
                markDuplicate(walletId, e.code == DUPLICATE_WALLET)
                throw e
            }
            markDuplicate(walletId, false)
            withContext(NonCancellable) {
                if (version != null) {
                    publish(g) { walletsFlow.update { l -> l.map { if (it.id == walletId) withCaps(it, caps, version) else it } } }
                }
                done(walletId, g)
            }
        }
    }

    private fun markDuplicate(walletId: String, duplicate: Boolean) {
        synchronized(lock) {
            if (if (duplicate) duplicateSet.add(walletId) else duplicateSet.remove(walletId)) duplicateFlow.value = duplicateSet.toSet()
        }
    }

    /** The wallet's new version after `wallet.caps.set`; null when the wallet already has limits as strict as [caps]. */
    @Suppress("ThrowsCount", "NestedBlockDepth")
    private suspend fun sendPending(walletId: String, caps: WalletCaps, g: Long): Long? {
        var reloaded = false
        while (true) {
            try {
                return sendCaps(walletId, caps, g)
            } catch (e: VaultFailure) {
                val retry = e.kind == FailureKind.CONFLICT && !reloaded
                if (!retry && e.code != BAD_REQUEST) throw e
                // The wallet moved on (an address issued elsewhere), or it already has limits that are not above these.
                if (!reloaded) reload(walletId, g)
                reloaded = true
                val now = walletsFlow.value.firstOrNull { it.id == walletId } ?: throw e
                if (!retry) return if (asStrict(now, caps)) null else throw e
            }
        }
    }

    private fun pinRequired(): Nothing = throw VaultFailure(FailureKind.OTHER, PIN_REQUIRED)

    private fun noWallet(): Nothing = throw VaultFailure(FailureKind.OTHER, "no_wallet")

    private suspend fun sendCaps(walletId: String, caps: WalletCaps, g: Long): Long {
        val wallet = walletsFlow.value.firstOrNull { it.id == walletId } ?: throw VaultFailure(FailureKind.OTHER, "no_wallet")
        return withContext(NonCancellable) { vaultGuard { ops().capsSet(walletId, wallet.version, caps.perTxSats, caps.perPeriodSats) } }
            .also { if (gen() != g) throw VaultFailure(FailureKind.OTHER, CLEARED) }
    }

    /** Every limit chosen is already in force at least as strictly on [wallet]. */
    private fun asStrict(wallet: VaultWallet, caps: WalletCaps): Boolean =
        (caps.perTxSats == null || wallet.perTxSats?.let { it <= caps.perTxSats } == true) &&
            (caps.perPeriodSats == null || wallet.perPeriodSats?.let { it <= caps.perPeriodSats } == true)

    private fun withCaps(w: VaultWallet, caps: WalletCaps, version: Long) = w.copy(
        version = version,
        capped = true,
        perTxSats = caps.perTxSats ?: w.perTxSats,
        perPeriodSats = caps.perPeriodSats ?: w.perPeriodSats,
    )

    /** The pending limits are set (or settled): forgotten on this phone, and Receive may be offered. */
    private suspend fun done(walletId: String, g: Long) {
        publish(g) {
            pendingMap.remove(walletId)
            held.remove(walletId)
            publishPending()
        }
        flags.clearPendingCaps(walletId)
    }

    /**
     * Before anything else for a wallet that was not made in this session: its pending limit is sent first ("after a restart, the
     * pending `wallet.caps.set` is sent before anything else for that wallet"). Throws what the send threw.
     */
    private suspend fun settleFirst(walletId: String) {
        // The flags could not be read: read them again before anything is decided.
        if (synchronized(lock) { pendingUnknown }) loadPending(gen())
        val (pending, hold) = synchronized(lock) { (walletId in pendingMap) to (walletId in held) }
        if (pending && !hold) settleCaps(walletId)
    }

    /** "Lower a limit": `wallet.caps.set` with the new values (a null member is left as it is), no password. */
    suspend fun lowerCaps(walletId: String, perTx: Long?, perPeriod: Long?) {
        require(CapsRules.problem(perTx, perPeriod) == null && (perTx != null || perPeriod != null)) { "limits" }
        val g = gen()
        val caps = WalletCaps(perTx, perPeriod)
        val send = suspend {
            val w = walletsFlow.value.firstOrNull { it.id == walletId } ?: noWallet()
            vaultGuard { ops().capsSet(walletId, w.version, perTx, perPeriod) }
        }
        val version = try {
            send()
        } catch (e: VaultFailure) {
            if (e.kind == FailureKind.CONFLICT) reload(walletId, g)
            throw e
        }
        publish(g) { walletsFlow.update { l -> l.map { if (it.id == walletId) withCaps(it, caps, version) else it } } }
    }

    /** The wallet's version and limits as this phone holds them now: what a raise form is chosen against ([raiseCaps]). */
    fun capsSeen(walletId: String): CapsSeen? =
        walletsFlow.value.firstOrNull { it.id == walletId }?.let { CapsSeen(it.version, it.perTxSats, it.perPeriodSats) }

    /**
     * "Raise or remove a limit": `wallet.caps.raise`, one credential operation with [pin] and [password], bound to the values the
     * wallet will have ([perTx], [perPeriod]; null: no limit). A pending `wallet.caps.set` is sent first and the raise is then
     * refused ([CAPS_SETTLED]), or refused as it is ([CAPS_PENDING]). **Fail secure**: on any error the limits stay as they were (nothing
     * here is changed before the vault has answered `ok`), a `conflict` reads the wallet again and is thrown (the typed secrets are
     * not sent a second time), and the pending `wallet.caps.set` of [settleCaps] is a different request that never raises. [seen] is
     * what the member's form was chosen against: if the wallet's version or limits moved since, nothing is sent ([CAPS_CHANGED]).
     */
    @Suppress("ThrowsCount") // a pending limit, no wallet, limits that moved since the form
    suspend fun raiseCaps(walletId: String, seen: CapsSeen, perTx: Long?, perPeriod: Long?, pin: String, password: String) {
        require(CapsRules.problem(perTx, perPeriod) == null) { "limits" }
        val g = gen()
        // The pending limits cannot be known while the settings file is unreadable: say so (the reset is on the wallet's screen).
        if (synchronized(lock) { pendingUnknown }) {
            loadPending(g)
            if (synchronized(lock) { pendingUnknown }) throw VaultFailure(FailureKind.OTHER, SETTINGS_UNREADABLE)
        }
        // A pending `wallet.caps.set` goes first, and a raise never goes over a lower that has not reached the vault. If it cannot be
        // sent, or the pending limits cannot be known, nothing is raised ([CAPS_PENDING]). If it was sent, the values asked for were
        // chosen against limits that have just changed (a member left as it was would raise the lowered one again), so nothing
        // is raised then either ([CAPS_SETTLED]): the screen shows the limits as they now are, and the member asks again.
        if (isCapsPending(walletId)) {
            settleCaps(walletId)
            throw VaultFailure(FailureKind.OTHER, if (isCapsPending(walletId)) CAPS_PENDING else CAPS_SETTLED)
        }
        val wallet = walletsFlow.value.firstOrNull { it.id == walletId } ?: throw VaultFailure(FailureKind.OTHER, "no_wallet")
        // (#248 review) The values were chosen against [seen]. If the wallet moved since (another device, a read in between), the PIN
        // and password are not sent: the member sees the limits as they now are, as after a `conflict`.
        if (CapsSeen(wallet.version, wallet.perTxSats, wallet.perPeriodSats) != seen) {
            reload(walletId, g)
            throw VaultFailure(FailureKind.CONFLICT, CAPS_CHANGED)
        }
        val version = try {
            withContext(NonCancellable) { vaultGuard { ops().capsRaise(walletId, seen.version, perTx, perPeriod, pin, password) } }
        } catch (e: VaultFailure) {
            if (e.kind == FailureKind.CONFLICT) reload(walletId, g)
            throw e
        }
        publish(g) {
            walletsFlow.update { l ->
                l.map {
                    if (it.id != walletId) {
                        it
                    } else {
                        val capped = perTx != null || perPeriod != null
                        it.copy(version = version, capped = capped, perTxSats = perTx, perPeriodSats = perPeriod)
                    }
                }
            }
        }
        // The member set or removed the limits here: a reset later does not ask this wallet's question again.
        flags.clearAskCaps(walletId)
    }

    /**
     * The phrase again, for the check of a wallet that is not backed up yet, or from "Show recovery phrase": `item.reveal`, a
     * credential operation; [pin] too for a wallet with a spending limit (`pin_required` otherwise). A clear that
     * overtook it (a lock while it was answered): the phrase is wiped and a [VaultFailure] with [CLEARED] is thrown.
     */
    suspend fun revealPhrase(walletId: String, password: String, pin: String? = null): RevealedPhrase {
        val g = gen()
        // A pending limit goes first (a wallet made in this session waits for its check, which is this reveal).
        settleFirst(walletId)
        // The limit just set makes the wallet capped: its phrase needs the PIN, so nothing is sent without it.
        if (pin == null && walletsFlow.value.firstOrNull { it.id == walletId }?.capped == true) pinRequired()
        val phrase = try {
            vaultGuard { ops().revealPhrase(walletId, password, pin) }
        } catch (e: VaultFailure) {
            throw named(e)
        }
        if (gen() != g) {
            phrase.wipe()
            throw VaultFailure(FailureKind.OTHER, CLEARED)
        }
        return phrase
    }

    /**
     * The vault says `pin_required` as a `bad_request` with that message (VAULT-MESSAGING §10.18): the failure then carries it as
     * its code, so that the screen can tell it from any other `bad_request`.
     */
    private fun named(e: VaultFailure): VaultFailure {
        val detail = (e.cause as? VaultOpException)?.detail
        if (e.code != BAD_REQUEST || detail != PIN_REQUIRED) return e
        return VaultFailure(e.kind, PIN_REQUIRED, e.retryAfterSeconds, e.cause, e.limit)
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
                pendingMap.remove(walletId)
                held.remove(walletId)
                publishPending()
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

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one early answer per state
    private suspend fun receive(walletId: String, fresh: Boolean): ReceiveResult {
        val g = gen()
        val wallet = walletsFlow.value.firstOrNull { it.id == walletId } ?: throw VaultFailure(FailureKind.OTHER, "no_wallet")
        // No Receive until the limit the member chose is set: it is sent first (not for a wallet whose backup is under way), and
        // if it cannot be, nothing is issued.
        if (isCapsPending(walletId)) {
            attempt { settleFirst(walletId) }
            if (isCapsPending(walletId)) {
                return when {
                    synchronized(lock) { pendingUnknown } -> ReceiveResult.SettingsUnreadable
                    synchronized(lock) { walletId in duplicateSet } -> ReceiveResult.CapsDuplicate
                    else -> ReceiveResult.CapsPending
                }
            }
        }
        when (asksLimits(wallet)) {
            null -> return ReceiveResult.SettingsUnreadable
            true -> return ReceiveResult.LimitsAsk
            false -> Unit
        }
        val network = networkOf(wallet) ?: return ReceiveResult.NeedsNetwork
        if (!offered(network)) return ReceiveResult.NotOffered
        val derived = checkedWallet(wallet, network, g) ?: return ReceiveResult.Mismatch
        val now = walletsFlow.value.firstOrNull { it.id == walletId } ?: wallet
        val address = if (fresh) newAddress(walletId) else unusedAddress(now)
        // A lock (a clear) while the vault answered: nothing of the wallet is shown after it.
        if (gen() != g) throw VaultFailure(FailureKind.OTHER, CLEARED)
        return if (derives(derived, address)) ReceiveResult.Address(address) else ReceiveResult.Mismatch
    }

    /**
     * Whether the limit question is to be asked before [wallet]'s Receive (after [resetSettings]): true; null when the flags cannot
     * be read (no Receive); false otherwise. A wallet the vault already caps, or whose vault has no `caps`, is not asked.
     */
    private suspend fun asksLimits(wallet: VaultWallet): Boolean? =
        if (!wallet.capsSupported || wallet.capped) false else flags.asksCaps(wallet.id)

    /** Whether the limit question is to be asked before Receive for [walletId] (see [receiveAddress]). */
    suspend fun asksLimits(walletId: String): Boolean =
        walletsFlow.value.firstOrNull { it.id == walletId }?.let { asksLimits(it) } == true

    /**
     * "Reset wallet settings on this phone", offered only while the flags file is unreadable ([capsPending] holds [UNKNOWN]): it
     * forgets the local flags only (backed up, test-chain labels, privacy notes, pending limits) and writes a fresh file, the one
     * sanctioned overwrite. Nothing is sent to the vault. Every wallet is marked to be asked "Set a spending limit?" again (the
     * pending limit forgotten may have been one the member chose); whether it is, is decided at Receive from the vault's own view
     * (not for a wallet it already caps, or a vault without `caps`), so a list not read yet changes nothing. Limits chosen in this
     * session and not yet sent are carried into the fresh file. Nothing is marked backed up. False (nothing changed) when the file
     * reads after all, or could not be written.
     */
    suspend fun resetSettings(): Boolean {
        val g = gen()
        val pending = synchronized(lock) { HashMap(pendingMap) }
        // The wallets the vault listed; if no list was read since open (a created wallet alone is not a list), the first one names them.
        val listed = if (synchronized(lock) { listRead }) walletsFlow.value.map { it.id }.toSet() else null
        val done = flags.reset(pending, listed)
        if (!done) {
            // The file may read after all: the unknown state ends with it, and Receive can go on.
            loadPending(g)
            return false
        }
        publish(g) {
            pendingUnknown = false
            publishPending()
            flagsChangedFlow.update { it + 1 }
        }
        return true
    }

    /**
     * The answer to "Set a spending limit?" after a reset: [caps] null is **No limit**; otherwise the limits are kept as pending
     * and sent with `wallet.caps.set` ([settleCaps]; a failure leaves them pending, with no Receive until Try again). False when the
     * answer could not be kept on this phone (the question stays).
     */
    suspend fun answerLimits(walletId: String, caps: WalletCaps?): Boolean {
        require(caps == null || (CapsRules.problem(caps) == null && !caps.none)) { "limits" }
        val g = gen()
        val kept = caps == null || keepCaps(walletId, caps, g, hold = false)
        if (!kept || !flags.clearAskCaps(walletId)) return false
        flagsChangedFlow.update { it + 1 }
        if (caps != null) settleCaps(walletId)
        return true
    }

    /** Reads this phone's pending limits again (from "Try again" on the unreadable notice): the unknown state ends if the file reads. */
    suspend fun rereadSettings() = loadPending(gen())

    /** Whether the limit chosen for [walletId] is not set yet. */
    fun isCapsPending(walletId: String): Boolean = synchronized(lock) { pendingUnknown || walletId in pendingMap }

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
        try {
            settleFirst(walletId)
        } catch (e: VaultFailure) {
            return LookupResult.VaultFailed(e)
        }
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

    // --- Sending (W2-1; ANDROID-PLAN §4 "Sending", VAULT-MESSAGING §10.18) ---

    /** A signed transaction waiting to be broadcast (memory only). [unsignedTxid] is the reviewed one it must equal. */
    private class KeptTx(val network: Network, val signed: SignedTx) {
        /**
         * Some broadcast of these bytes ended without a definite answer (the server did not answer): it may be on the network. Set
         * once, kept for this transaction, cleared only when a reachable server has said it does not have it ([checkSent]).
         */
        @Volatile
        var mayBeOnNetwork = false
    }

    /** A `wallet.sign` whose answer has not come: [resend] sends the same request again. */
    private class PendingSign(
        val network: Network,
        val unsignedTxid: String,
        val forget: () -> Unit,
        val expired: () -> Boolean,
        val resend: suspend () -> SignOutcome,
    )

    /** Whether the wallet's pending limit holds a send back, as it holds Receive: it is sent first; null when nothing holds it. */
    @Suppress("ReturnCount")
    private suspend fun holdFor(walletId: String): SendHold? {
        if (!isCapsPending(walletId)) return null
        attempt { settleFirst(walletId) }
        if (!isCapsPending(walletId)) return null
        return when {
            synchronized(lock) { pendingUnknown } -> SendHold.SETTINGS_UNREADABLE
            synchronized(lock) { walletId in duplicateSet } -> SendHold.CAPS_DUPLICATE
            else -> SendHold.CAPS_PENDING
        }
    }

    /**
     * Steps 1 to 4 of a send, with no password and nothing signed: the checks (the limit still to be set first, the address for the
     * network, the fee rate), the coins of both accounts from the chain server, the selection ([CoinSelection]), the PSBT
     * ([PsbtBuilder], with the full previous transactions and the derivations), `wallet.psbt.inspect`, and the **compare** of the vault's
     * summary with what was built and entered ([SummaryCompare]): any difference is [PrepareResult.Different] and nothing goes on. The
     * review shows [PreparedSend.vault], the vault's summary.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    suspend fun prepareSend(walletId: String, request: SendRequest): PrepareResult = sendLock.withLock {
        val g = gen()
        val wallet = walletsFlow.value.firstOrNull { it.id == walletId } ?: noWallet()
        holdFor(walletId)?.let { return PrepareResult.Held(it) }
        // A signed payment waiting to be broadcast, or a signing waiting for its answer: no new payment beside it (it would pick other
        // coins, and both could confirm). It is decided first: Try again, or Discard once nothing of it can be on the network.
        if (hasUnsent(walletId)) return PrepareResult.Unsent
        // The vault's recent payments are checked, and any found acknowledged, before a new payment is prepared (memory only, per wallet).
        if (!recentOk(walletId)) return PrepareResult.RecentUnchecked
        if (signHeld(walletId)) return PrepareResult.Held(SendHold.SIGN_UNSETTLED)
        val network = networkOf(wallet) ?: return PrepareResult.NeedsNetwork
        if (!offered(network)) return PrepareResult.NotOffered
        val payee = when (val c = Payee.check(network, request.address)) {
            is PayeeCheck.Ok -> c
            else -> return PrepareResult.BadAddress(c)
        }
        if (request.feeRate !in CoinSelection.MIN_FEE_RATE..CoinSelection.MAX_FEE_RATE) return PrepareResult.BadFeeRate
        val derived = checkedWallet(wallet, network, g) ?: return PrepareResult.AddressMismatch
        if (withContext(Dispatchers.Default) { isOwnAddress(wallet, derived, request.address) }) return PrepareResult.SelfSend
        if (!flags.privacyNoteShown(walletId)) return PrepareResult.NoteNeeded
        val spend = when (val open = chain.open(network)) {
            is ChainOpen.Ready -> open.spend?.let { open to it } ?: return PrepareResult.NoChain(open)
            else -> return PrepareResult.NoChain(open)
        }
        if (gen() != g) return PrepareResult.Cleared
        // The wallet as the vault has it now: its next change index is where the change goes.
        reload(walletId, g)
        val now = walletsFlow.value.firstOrNull { it.id == walletId } ?: wallet
        val noAccount = PrepareResult.Refused(PsbtReasons.NO_ACCOUNT, alarm = false, byVault = false)
        val changeType = AccountType.fromVaultName(now.addressType) ?: return noAccount
        val changeIndex = now.account(now.addressType)?.nextChange ?: return noAccount
        val selected = try {
            val coins = collectCoins(now, derived, spend.first, spend.second, g) ?: return PrepareResult.Cleared
            val s = CoinSelection.select(
                coins, payee.scriptSize, request.amountSats, request.feeRate, changeType, request.includeUnconfirmed,
            )
            when (s) {
                is Selection.Selected -> s
                is Selection.NotEnough -> return PrepareResult.NotEnough(s.availableSats, s.neededSats)
                Selection.BadFeeRate -> return PrepareResult.BadFeeRate
                Selection.BadAmount -> return PrepareResult.BadAmount
                Selection.TooManyInputs -> return PrepareResult.TooManyInputs
            }
        } catch (e: ChainError) {
            return PrepareResult.ServerFailed(e)
        }
        val buildCoins = try {
            val prev = HashMap<String, ByteArray>()
            for (c in selected.coins) if (c.txid !in prev) prev[c.txid] = spend.second.rawTransaction(c.txid)
            selected.coins.map { c -> BuildCoin(c.accountType, c.change, c.index, prev.getValue(c.txid), c.vout) }
        } catch (e: ChainError) {
            return PrepareResult.ServerFailed(e)
        }
        val outputs = buildList<BuildOutput> {
            add(BuildOutput.Pay(request.address, selected.payeeSats))
            if (selected.changeSats > 0) add(BuildOutput.Own(changeType, true, changeIndex, selected.changeSats))
        }
        val psbt = try {
            PsbtBuilder.build(derived, buildCoins, outputs)
        } catch (e: BuildException) {
            return PrepareResult.Refused(e.reason, alarm = false, byVault = false)
        }
        val built = when (val r = PsbtInspector.inspect(derived, psbt)) {
            is InspectResult.Ok -> r.summary
            is InspectResult.Refused -> return PrepareResult.Refused(r.reason, alarm = false, byVault = false)
        }
        val psbtBase64 = PsbtBase64.encode(psbt)
        val inspection = try {
            vaultGuard { ops().inspect(walletId, psbtBase64) }
        } catch (e: VaultFailure) {
            val reason = invalidPsbtReason(e) ?: throw e
            return PrepareResult.Refused(reason, alarm = reason == SendCodes.WRONG_SEED, byVault = true)
        }
        val kinds = SummaryCompare.differences(request.address, selected.payeeSats, built, inspection.summary)
        if (kinds.isNotEmpty()) return PrepareResult.Different(kinds)
        val prepared = PreparedSend(
            walletId, network, request.address, inspection.summary, inspection.cap, selected.payeeSats, selected.changeSats,
            selected.feeSats, selected.vsize, selected.toFeeSats, selected.sendAll, selected.leftBehind, psbtBase64, built.txid, g,
            synchronized(lock) { signCounts[walletId] ?: 0L },
        )
        return if (gen() == g) PrepareResult.Ready(prepared) else PrepareResult.Cleared
    }

    /**
     * Whether [address] is one of this wallet's own: both accounts, receive and change, up to the vault's next index and the gap
     * beyond it. A payment to oneself comes back from the vault with the output as change, so it could never match what was entered
     * (`summary_mismatch`, review note 6): it is named before anything is built or sent.
     */
    private fun isOwnAddress(vault: VaultWallet, wallet: Wallet, address: String): Boolean = AccountType.entries.any { type ->
        val account = wallet.account(type)
        val stated = vault.account(type.vaultName)
        listOf(false, true).any { change ->
            val next = if (change) stated?.nextChange ?: 0 else stated?.nextReceive ?: 0
            (0 until minOf(next + GAP, Account.MAX_INDEX)).any { i ->
                val own = account.address(change, i)
                own == address || own == address.lowercase()
            }
        }
    }

    /** Both accounts' receive and change chains as [scan] walks them, reading the unspent outputs of the addresses with funds. */
    @Suppress("NestedBlockDepth")
    private suspend fun collectCoins(
        vault: VaultWallet,
        wallet: Wallet,
        ready: ChainOpen.Ready,
        spend: SpendAccess,
        g: Long,
    ): List<Spendable>? =
        coroutineScope {
            val out = ArrayList<Spendable>()
            for (type in AccountType.entries) {
                val account = wallet.account(type)
                val stated = vault.account(type.vaultName)
                for (change in listOf(false, true)) {
                    val next = if (change) stated?.nextChange ?: 0 else stated?.nextReceive ?: 0
                    var lastUsed = -1
                    var index = 0
                    while (index < maxOf(next, lastUsed + 1) + GAP && index < Account.MAX_INDEX) {
                        if (gen() != g) return@coroutineScope null
                        val batch = (index until minOf(index + BATCH, maxOf(next, lastUsed + 1) + GAP)).toList()
                        val found = batch.map { i ->
                            async {
                                val address = account.address(change, i)
                                val s = ready.summary(address)
                                val utxos = if (s.confirmedSats > 0 || s.unconfirmedSats > 0) spend.unspent(address) else emptyList()
                                Triple(i, s.txCount, utxos.map { Spendable(it.txid, it.vout, it.valueSats, type, change, i, it.confirmed) })
                            }
                        }.awaitAll()
                        for ((i, txCount, coins) in found) {
                            out += coins
                            if (txCount > 0) lastUsed = maxOf(lastUsed, i)
                        }
                        index += batch.size
                    }
                }
            }
            out
        }

    /** The fee rates the chain server gives for the wallet's network (sat/vB by confirmation target), for Slow, Normal and Fast. */
    @Suppress("ReturnCount")
    suspend fun feeEstimates(walletId: String): FeeEstimates {
        val wallet = walletsFlow.value.firstOrNull { it.id == walletId } ?: noWallet()
        val network = networkOf(wallet) ?: return FeeEstimates.NoChain(ChainOpen.NoServer(ChainServer.NotSet))
        val open = chain.open(network)
        val spend = (open as? ChainOpen.Ready)?.spend ?: return FeeEstimates.NoChain(open)
        return try {
            FeeEstimates.Done(spend.feeEstimates())
        } catch (e: ChainError) {
            FeeEstimates.Failed(e)
        }
    }

    /**
     * Steps 5 and 6: the password, typed once. When the unlock window is closed `credential.unlock` first (the app's own path), then
     * `wallet.sign` over the PSBT [prepared] (a window the app opened is closed again once the signing is over), then `POST /tx`. A
     * `credential_locked` after all (the window closed in between) opens it and signs once more; a wrong password and the like are
     * thrown as [VaultFailure]. The signed transaction is kept **in memory only**; a failed broadcast is [SendResult.BroadcastFailed]
     * and [retryBroadcast] sends the same bytes. An answer that does not come is [SendResult.Unanswered] and [retrySign] sends the
     * same request. Not cancelled once begun.
     */
    suspend fun approveSend(prepared: PreparedSend, password: String): SendResult = withContext(NonCancellable) {
        sendLock.withLock {
            if (gen() != prepared.generation) return@withLock SendResult.Cleared
            holdFor(prepared.walletId)?.let { return@withLock SendResult.Held(it) }
            // An unanswered signing is decided first (retrySign): a new one would build the payment again and count again.
            if (synchronized(lock) { prepared.walletId in pendingSigns }) return@withLock SendResult.Unanswered
            // A payment whose signing went out is signed once; a kept transaction is broadcast, not signed again.
            val signedBefore = synchronized(lock) { prepared.unsignedTxid in consumed || prepared.walletId in keptTx }
            if (signedBefore) return@withLock SendResult.AlreadySigned
            // Another signing went out for this wallet since this payment was prepared: its coins may be spent already.
            if (synchronized(lock) { (signCounts[prepared.walletId] ?: 0L) != prepared.signCount }) return@withLock SendResult.Superseded
            if (signHeld(prepared.walletId)) return@withLock SendResult.Held(SendHold.SIGN_UNSETTLED)
            var toLock = false
            try {
                if (!windowOpen()) toLock = openForSend(password)
                var outcome = signOnce(prepared, password)
                if (outcome is Outcome.Failed && outcome.result === SendResult.CredentialLocked && !toLock) {
                    // The window closed between the check and the vault's: opened now, and asked once more.
                    toLock = openForSend(password)
                    outcome = signOnce(prepared, password)
                }
                finishSign(prepared.walletId, prepared.network, prepared.unsignedTxid, prepared.generation, outcome)
            } finally {
                if (toLock) closeOpened()
            }
        }
    }

    private suspend fun openForSend(password: String): Boolean {
        try {
            openWindow(password)
        } catch (e: VaultFailure) {
            // Only a definite refusal opened nothing; any other failure may have come after the vault opened the window.
            if (e.kind in DEFINITE_REFUSALS) throw e
            closeOpened()
            throw e
        }
        return true
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun closeOpened() {
        try {
            closeWindow()
            windowNotClosedFlow.value = false
        } catch (e: Exception) {
            windowNotClosedFlow.value = true
        }
    }

    /** What one signing came to: the signed transaction, a signing still waiting for its answer, or the refusal the pipeline names. */
    private sealed interface Outcome {
        class Signed(val signed: SignedTx) : Outcome

        class Waiting(val forget: () -> Unit, val expired: () -> Boolean, val resend: suspend () -> SignOutcome) : Outcome

        class Failed(val result: SendResult) : Outcome
    }

    /**
     * The payment is consumed the moment the request goes out. It is given back by the vault's refusal, whatever its code (final,
     * and it counts nothing, §10.18), and by [SignOutcome.NotSent] (a failure from before the request was sent). It stays consumed
     * when the request may have reached the vault without a definite answer ([SignOutcome.Unanswered], a failure thrown after the
     * send) and when the answer was `ok`, an unreadable one included: the vault may have signed.
     */
    private suspend fun signOnce(prepared: PreparedSend, password: String): Outcome {
        synchronized(lock) {
            consumed += prepared.unsignedTxid
            signCounts.merge(prepared.walletId, 1L, Long::plus)
        }
        val outcome = try {
            vaultGuard { ops().sign(prepared.walletId, prepared.psbtBase64, password) }
        } catch (_: VaultFailure) {
            // A failure from before the request went out is `SignOutcome.NotSent` (a result, not this). One that reaches here came
            // after it: the vault may have signed. Never a "try your password again": consumed, with the plain notice.
            return Outcome.Failed(SendResult.SignedUnreadable)
        }
        return when (val o = outcome) {
            is SignOutcome.NotSent -> {
                giveBack(prepared.walletId, prepared.unsignedTxid, prepared.generation)
                throw failureOf(o.failure)
            }
            is SignOutcome.Answered -> answered(prepared.walletId, prepared.unsignedTxid, prepared.generation, o.reply)
            is SignOutcome.Unanswered -> Outcome.Waiting(o.forget, o.expired, o.resend)
        }
    }

    /** The vault's answer: `ok` is read (and stays consumed, even if it does not read); a refusal is final and gives the payment back. */
    private suspend fun answered(walletId: String, unsignedTxid: String, g: Long, reply: SignReply): Outcome = when (reply) {
        is SignReply.Ok -> try {
            Outcome.Signed(WalletWire.signed(reply.body))
        } catch (_: WalletWireException) {
            // `ok` that does not read: the vault signed and counted it. Consumed, with a plain notice; never an exception to the screen.
            Outcome.Failed(SendResult.SignedUnreadable)
        }
        is SignReply.Refused -> {
            giveBack(walletId, unsignedTxid, g)
            val failure = failureOf(reply.error)
            Outcome.Failed(signFailure(failure) ?: throw failure)
        }
    }

    /** [e] as every other screen's failure ([vaultGuard]'s mapping). */
    private suspend fun failureOf(e: Exception): VaultFailure = try {
        vaultGuard<Nothing> { throw e }
    } catch (f: VaultFailure) {
        f
    }

    /** The signing counted nothing: the payment may be approved again, and no signing is counted for the wallet. */
    private fun giveBack(walletId: String, unsignedTxid: String, g: Long) {
        // After a lock both are empty already: nothing is given back to a new generation (no counter below zero).
        publish(g) {
            consumed -= unsignedTxid
            signCounts.merge(walletId, -1L, Long::plus)
        }
    }

    /** Whether a `wallet.sign` of this wallet may still apply at the vault, after a lock, an unlock or a restart. */
    private suspend fun signHeld(walletId: String): Boolean = walletId in vaultGuard { ops().signsOutstanding() }

    /** A signing answered or not: the signed transaction kept and broadcast, an answer awaited, or the refusal. */
    private suspend fun finishSign(walletId: String, network: Network, unsignedTxid: String, g: Long, outcome: Outcome): SendResult =
        when (outcome) {
            is Outcome.Failed -> outcome.result
            is Outcome.Waiting -> awaitAnswer(
                walletId, PendingSign(network, unsignedTxid, outcome.forget, outcome.expired, outcome.resend), g,
            )
            is Outcome.Signed -> keepAndBroadcast(walletId, KeptTx(network, outcome.signed), unsignedTxid, g)
        }

    private fun awaitAnswer(walletId: String, pending: PendingSign, g: Long): SendResult {
        val kept = publish(g) {
            pendingSigns[walletId] = pending
            publishUnsent()
        }
        return if (kept) SendResult.Unanswered else SendResult.Cleared
    }

    private suspend fun keepAndBroadcast(walletId: String, kept: KeptTx, unsignedTxid: String, g: Long): SendResult {
        // Not the payment that was reviewed (a txid commits to every input and output): never broadcast.
        val same = kept.signed.txid == unsignedTxid
        val stored = publish(g) {
            pendingSigns.remove(walletId)
            if (same) keptTx[walletId] = kept
            publishUnsent()
        }
        return when {
            !stored -> SendResult.Cleared
            !same -> SendResult.SignedDiffers
            else -> broadcastKept(walletId, g)
        }
    }

    /** Under [lock]. */
    private fun publishUnsent() {
        unsentFlow.value = keptTx.keys + pendingSigns.keys
    }

    /**
     * `POST /tx` of the kept transaction: the server is opened afresh each time (so Try again also works after another server was
     * chosen), the bytes are the same. Success drops the transaction; a server that already has it is success too.
     */
    @Suppress("ReturnCount")
    private suspend fun broadcastKept(walletId: String, g: Long): SendResult {
        val kept = synchronized(lock) { keptTx[walletId] } ?: return SendResult.NothingToSend
        val open = chain.open(kept.network)
        val spend = (open as? ChainOpen.Ready)?.spend ?: return SendResult.BroadcastFailed(BroadcastCause.NoChain(open))
        var alreadyKnown = false
        // A lock that came in meanwhile dropped the transaction: nothing is broadcast after it.
        if (gen() != g) return SendResult.Cleared
        val txid = try {
            spend.broadcast(kept.signed.tx)
        } catch (e: ChainError.BroadcastRefused) {
            if (e.reason != BroadcastRefusal.ALREADY_KNOWN) return SendResult.BroadcastFailed(BroadcastCause.Refused(e.reason))
            alreadyKnown = true
            kept.signed.txid
        } catch (e: ChainError) {
            // No definite answer: the transaction may be on the network. It is not dropped until a server says it is not.
            kept.mayBeOnNetwork = true
            return SendResult.BroadcastFailed(BroadcastCause.Server(e))
        }
        publish(g) {
            keptTx.remove(walletId)
            publishUnsent()
        }
        return SendResult.Sent(txid, alreadyKnown)
    }

    /** "Try again" after a failed broadcast: the same transaction, to the chain server as it is now. */
    suspend fun retryBroadcast(walletId: String): SendResult = withContext(NonCancellable) {
        sendLock.withLock { broadcastKept(walletId, gen()) }
    }

    /**
     * Review note 2: after a broadcast that got no answer ([BroadcastCause.Server]) the server may have taken the transaction. Before
     * the member discards it or sends again (a double payment), `GET /tx/<txid>` asks the chain server: known there is [SendResult.Sent]
     * (the kept copy is dropped); [SendResult.NotOnServer] is the server's definite "no"; a server that cannot answer is
     * [SendResult.BroadcastFailed] again, and nothing is decided.
     */
    @Suppress("ReturnCount")
    suspend fun checkSent(walletId: String): SendResult = withContext(NonCancellable) {
        sendLock.withLock {
            val g = gen()
            val kept = synchronized(lock) { keptTx[walletId] } ?: return@withLock SendResult.NothingToSend
            val open = chain.open(kept.network)
            val spend = (open as? ChainOpen.Ready)?.spend ?: return@withLock SendResult.BroadcastFailed(BroadcastCause.NoChain(open))
            // A "not found" right after a timeout is not believed at once: the server may still be taking the transaction. It waits and
            // asks again; a second question that fails is no answer, and nothing is decided.
            val sent = try {
                spend.knows(kept.signed.txid) || (kept.mayBeOnNetwork && askAgain(spend, kept.signed.txid))
            } catch (e: ChainError) {
                return@withLock SendResult.BroadcastFailed(BroadcastCause.Server(e))
            }
            if (!sent) {
                // A reachable server, asked twice with time between, does not have it: the question is answered for this transaction.
                kept.mayBeOnNetwork = false
                return@withLock SendResult.NotOnServer
            }
            publish(g) {
                keptTx.remove(walletId)
                publishUnsent()
            }
            SendResult.Sent(kept.signed.txid, alreadyKnown = true)
        }
    }

    private suspend fun askAgain(spend: SpendAccess, txid: String): Boolean {
        delay(settleMs)
        return spend.knows(txid)
    }

    /**
     * "Try again" after a signing whose answer did not come: **the same request** (same inner id and body) once more, which the vault
     * answers from its cache, so a spending limit counts it once. Nothing is built or asked of the member again.
     */
    suspend fun retrySign(walletId: String): SendResult = withContext(NonCancellable) {
        sendLock.withLock {
            val g = gen()
            val p = synchronized(lock) { pendingSigns[walletId] } ?: return@withLock SendResult.NothingToSend
            val outcome = try {
                vaultGuard { p.resend() }
            } catch (e: VaultFailure) {
                // No answer yet: the request stays. Past its `exp` (and the margin) it cannot apply any more, but a late answer that
                // already came is taken before it is forgotten.
                if (!p.expired()) throw e
                try {
                    vaultGuard { p.resend() }
                } catch (_: VaultFailure) {
                    return@withLock lapse(walletId, g, p)
                }
            }
            when (outcome) {
                is SignOutcome.NotSent -> throw failureOf(outcome.failure)
                is SignOutcome.Unanswered -> {
                    // Still no answer: the newest request is kept, even if `credential.version` moved on (the vault may have applied
                    // it and answers a resend from its cache until `exp`); only past `exp` plus the margin is it given up.
                    val again = PendingSign(p.network, p.unsignedTxid, outcome.forget, outcome.expired, outcome.resend)
                    if (again.expired()) {
                        lapse(walletId, g, again)
                    } else {
                        finishSign(walletId, p.network, p.unsignedTxid, g, Outcome.Waiting(again.forget, again.expired, again.resend))
                    }
                }
                is SignOutcome.Answered -> answeredRetry(walletId, p, g, outcome.reply)
            }
        }
    }

    private suspend fun answeredRetry(walletId: String, p: PendingSign, g: Long, reply: SignReply): SendResult {
        val result = try {
            answered(walletId, p.unsignedTxid, g, reply)
        } catch (e: VaultFailure) {
            // A refusal the pipeline does not name is still the vault's final word.
            publish(g) {
                pendingSigns.remove(walletId)
                publishUnsent()
            }
            throw e
        }
        if (result is Outcome.Failed) {
            publish(g) {
                pendingSigns.remove(walletId)
                publishUnsent()
            }
        }
        return finishSign(walletId, p.network, p.unsignedTxid, g, result)
    }

    /**
     * Review note 1, as fixed: an unanswered signing that the vault's clock says is past its `exp` plus the margin cannot apply any
     * more. It is dropped for good, and [SendResult.SignLapsed] says it may have been signed. Not before: a signing the vault applied
     * (also when `credential.version` has moved on) answers a resend from its cache until then.
     */
    private fun lapse(walletId: String, g: Long, p: PendingSign): SendResult {
        val dropped = publish(g) {
            pendingSigns.remove(walletId)
            publishUnsent()
        }
        if (!dropped) return SendResult.Cleared
        p.forget()
        return SendResult.SignLapsed
    }

    /** Whether a signed transaction is waiting to be broadcast, or a signing waits for its answer: the screen offers Try again. */
    fun hasUnsent(walletId: String): Boolean = synchronized(lock) { walletId in keptTx || walletId in pendingSigns }

    /** Whether a kept transaction may be on the network and must be checked ([checkSent]) before it can be discarded. */
    fun mustCheck(walletId: String): Boolean = synchronized(lock) { keptTx[walletId]?.mayBeOnNetwork == true }

    /**
     * The member gives up on the unsent transaction or the unanswered signing of [walletId]: dropped from memory. Coins are not
     * lost; but an unanswered signing may have counted against the wallet's spending limit at the vault. **Refused while the kept
     * transaction may be on the network** ([DiscardResult.MUST_CHECK]): a payment that went out would be dropped and could be sent
     * again from other coins.
     */
    fun discardUnsent(walletId: String): DiscardResult = synchronized(lock) {
        if (keptTx[walletId]?.mayBeOnNetwork == true) return@synchronized DiscardResult.MUST_CHECK
        keptTx.remove(walletId)
        pendingSigns.remove(walletId)?.forget?.invoke()
        publishUnsent()
        DiscardResult.DROPPED
    }

    /**
     * Minimal guard for a payment whose broadcast ended without an answer before a lock or a restart (memory only, nothing on disk): the
     * vault's `wallet.history` of the last 24 hours (by `at`; one without a time counts as recent), each txid asked of the chain server
     * (`GET /tx/<txid>`). Confirmed ones are done. The worst of the rest: not found is [RecentSends.MAY_HAVE_GONE], a server or a history
     * that cannot be read is [RecentSends.UNREADABLE] (fail secure), in the mempool is [RecentSends.WAITING]. At most [RECENT_MAX] are
     * asked.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    suspend fun recentSends(walletId: String): RecentReport {
        val report = try {
            recentReport(walletId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A malformed txid or an answer that does not read must not crash anything: nothing is known.
            RecentReport(RecentSends.UNREADABLE)
        }
        synchronized(lock) { recentState[walletId] = RecentState(needsAck = report.kind != RecentSends.CLEAR, acknowledged = false) }
        return report
    }

    @Suppress("ReturnCount")
    private suspend fun recentReport(walletId: String): RecentReport {
        val unreadable = RecentReport(RecentSends.UNREADABLE)
        val wallet = walletsFlow.value.firstOrNull { it.id == walletId } ?: return unreadable
        val network = networkOf(wallet) ?: return unreadable
        if (!flags.privacyNoteShown(walletId)) return unreadable
        val txs = try {
            history(walletId, RECENT_HISTORY)
        } catch (_: VaultFailure) {
            return unreadable
        }
        val cutoff = clock().minus(RECENT_WINDOW)
        val recent = txs.filter { it.at == null || it.at.isAfter(cutoff) }.take(RECENT_MAX)
        if (recent.isEmpty()) return RecentReport(RecentSends.CLEAR)
        val spend = (chain.open(network) as? ChainOpen.Ready)?.spend ?: return unreadable
        return classify(spend, recent)
    }

    /** The worst state of [txs] at the server (not found, then unreadable, then in the mempool), naming the newest of that state. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun classify(spend: SpendAccess, txs: List<WalletTx>): RecentReport {
        val states = txs.map { tx ->
            try {
                tx to spend.state(tx.txid)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                tx to null
            }
        }
        fun named(kind: RecentSends, state: TxState): RecentReport {
            val same = states.filter { it.second == state }.map { it.first }
            // Newest first; one without a time counts as the newest.
            val sorted = same.sortedWith(compareByDescending<WalletTx> { it.at == null }.thenByDescending { it.at })
            return RecentReport(kind, sorted.first(), sorted.size - 1)
        }
        return when {
            states.any { it.second == TxState.NOT_FOUND } -> named(RecentSends.MAY_HAVE_GONE, TxState.NOT_FOUND)
            states.any { it.second == null } -> RecentReport(RecentSends.UNREADABLE)
            states.any { it.second == TxState.UNCONFIRMED } -> named(RecentSends.WAITING, TxState.UNCONFIRMED)
            else -> RecentReport(RecentSends.CLEAR)
        }
    }

    /**
     * "Send anyway": the member has seen what [recentSends] found. Also for a wallet whose check did not finish: they chose to go on, and
     * nothing blocks for good.
     */
    fun acknowledgeRecent(walletId: String) {
        synchronized(lock) { recentState[walletId] = RecentState(needsAck = false, acknowledged = true) }
    }

    /** Whether [prepareSend] may go on: the check ran in this session and, if it found something, was acknowledged. */
    private fun recentOk(walletId: String): Boolean = synchronized(lock) {
        recentState[walletId]?.let { !it.needsAck || it.acknowledged } ?: false
    }

    /** `wallet.history`: what this vault signed, newest first. */
    suspend fun history(walletId: String, limit: Int? = null): List<WalletTx> {
        settleFirst(walletId)
        return vaultGuard { ops().history(walletId, limit) }
    }

    /** The vault's refusal to sign as the pipeline names it; null: not one it names (the [VaultFailure] stands). */
    private fun signFailure(e: VaultFailure): SendResult? {
        val op = e.cause as? VaultOpException ?: return null
        return when (op.code) {
            SendCodes.CAP_EXCEEDED -> capExceeded(op)
            SendCodes.INVALID_PSBT -> invalidPsbtReason(e)?.let { SendResult.Refused(it, it == SendCodes.WRONG_SEED) }
            SendCodes.CREDENTIAL_LOCKED -> SendResult.CredentialLocked
            SendCodes.DUPLICATE_WALLET -> SendResult.DuplicateWallet
            else -> null
        }
    }

    /** `cap_exceeded` (§10.18): the body `{reason, limit_sats, remaining_sats?, allowed_after?}`; the message is the reason too. */
    private fun capExceeded(op: VaultOpException): SendResult.CapExceeded {
        val body = op.body
        val reason = when (body?.let { VaultJson.str(it, "reason") } ?: op.detail) {
            "per_tx" -> CapReason.PER_TX
            "per_period" -> CapReason.PER_PERIOD
            else -> CapReason.OTHER
        }
        fun n(k: String) = body?.let { b -> (b[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull() }
        return SendResult.CapExceeded(
            reason,
            limitSats = n("limit_sats") ?: n("limit"),
            remainingSats = if (reason == CapReason.PER_PERIOD) n("remaining_sats") else null,
            allowedAfter = if (reason == CapReason.PER_PERIOD) {
                body?.let { VaultJson.str(it, "allowed_after") }?.let { runCatching { Instant.parse(it) }.getOrNull() }
            } else {
                null
            },
        )
    }

    /** `invalid_psbt`'s reason (the error's message); null for any other failure. */
    @Suppress("ReturnCount")
    private fun invalidPsbtReason(e: VaultFailure): String? {
        val op = e.cause as? VaultOpException ?: return null
        if (op.code != SendCodes.INVALID_PSBT) return null
        return op.detail.ifEmpty { op.body?.let { VaultJson.str(it, "reason") } ?: "unknown" }
    }


    companion object {
        /** Refusals that opened nothing: the unlock window is still closed after them. */
        val DEFINITE_REFUSALS = setOf(
            FailureKind.BAD_PASSWORD, FailureKind.BACKOFF, FailureKind.CREDENTIAL_FROZEN, FailureKind.ROTATION_REQUIRED,
            FailureKind.OWNER_CHECK_REQUIRED, FailureKind.CREDENTIAL_UNSETTLED,
        )

        private val RECENT_WINDOW: java.time.Duration = java.time.Duration.ofHours(24)
        private const val RECENT_HISTORY = 50
        private const val RECENT_MAX = 10

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

        /** In [capsPending] when this phone's flags cannot be read: no wallet is known to be free of a limit still to be set. */
        const val UNKNOWN = "*"

        /** Whether [walletId] is pending in [pending] (a set from [capsPending]): named in it, or nothing is known ([UNKNOWN]). */
        fun isPending(pending: Set<String>, walletId: String): Boolean = UNKNOWN in pending || walletId in pending

        /** The vault's refusal of a limit on a wallet that shares its keys with another one (VAULT-MESSAGING 0.82.3). */
        const val DUPLICATE_WALLET = "duplicate_wallet"

        /** What [raiseCaps] answers (a [VaultFailure] code) while this phone's wallet settings cannot be read. */
        const val SETTINGS_UNREADABLE = "settings_unreadable"

        /** What [raiseCaps] answers (a [VaultFailure] code) while the wallet's chosen limit is still to be set. */
        const val CAPS_PENDING = "caps_pending"

        /** What [raiseCaps] answers when it just sent the wallet's pending `wallet.caps.set` instead: nothing was raised. */
        const val CAPS_SETTLED = "caps_settled"

        /** [raiseCaps]: the wallet's version or limits moved since the form was chosen; nothing was sent. */
        const val CAPS_CHANGED = "caps_changed"

        /** The vault's `bad_request` (VAULT-MESSAGING §10.1). */
        const val BAD_REQUEST = "bad_request"

        /** What a `bad_request` says (its message) for the reveal of a capped wallet's phrase without the PIN. */
        const val PIN_REQUIRED = "pin_required"

        /** The four chains: both accounts, receive and change. */
        val CHAINS = AccountType.entries.flatMap { t -> listOf(AddressChain(t.vaultName, false), AddressChain(t.vaultName, true)) }
    }
}

/** A wallet's version and limits as a raise form was chosen against them ([WalletsManager.raiseCaps]). */
data class CapsSeen(val version: Long, val perTx: Long?, val perPeriod: Long?)
