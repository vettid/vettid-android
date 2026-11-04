package com.vettid.core.data.social

import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.MoveRepository
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.vaultGuard
import com.vettid.core.vault.DeviceStateStore
import com.vettid.core.vault.PendingPage
import com.vettid.core.vault.pendingList
import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.devicePairReject
import com.vettid.core.vault.connectionAsksMute
import com.vettid.core.vault.connectionAsksResume
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Connections, messages and approvals on this device (ANDROID-PLAN §4, A4),
 * over the vault client. Lists live in memory and are re-read from the vault
 * (which keeps the history) on unlock and on its events: connections,
 * connection requests (`connection.request.list`, VAULT-MESSAGING 0.10.2),
 * grant and critical-item requests. Approvals that only arrive as events
 * (share decisions, member authentication, desktop and agent requests), the names
 * requests showed and the peers' declines are kept in memory only (owner decision
 * 2026-10-27) and dropped with the open vault; the safety codes of open requests come
 * from the request list (`sas`), so nothing about them is stored.
 *
 * [onEvent] gets every event of the vault ([com.vettid.core.data.vault.VaultManager]
 * forwards them); nothing here touches transport or crypto beyond the typed API.
 */
@Suppress("TooManyFunctions", "LargeClass")
class SocialManager internal constructor(
    private val scope: CoroutineScope,
    private val api: suspend () -> VaultApi,
    private val credential: CredentialRepository,
    /**
     * The file this class kept events, request names and declines in before 2026-10-27 (`social.bin`): nothing is
     * written to it any more (owner decision: memory only, like every other vault content); it is deleted at
     * construction, i.e. at the next start.
     */
    private val obsoleteStore: DeviceStateStore? = null,
    /**
     * The pending-asks file (`pending-asks.bin`) of builds before 2026-10-30 (the "event-only" exception, dropped because
     * `pending.list` is in release 24): nothing is written to it any more; it is deleted at construction, and a delete
     * that failed is tried again at every lock clear. Best effort: nothing reads the file, so a failure is reported to
     * [onLeftoverFailed] and never thrown (a lock must always succeed).
     */
    private val obsoletePendingStore: DeviceStateStore? = null,
    private val onLeftoverFailed: (Throwable) -> Unit = {},
    private val clock: Clock = Clock.systemUTC(),
    /** The member's items by id (the Vault list): names for the share decisions read from the rules (§10.12). */
    private val itemNames: () -> Map<String, ShareItem> = { emptyMap() },
    /**
     * Marks feed items read (ANDROID-PLAN 0.1.23): an ask decided here ([com.vettid.core.data.feed.FeedAsks], by kind
     * and `ref`), a conversation opened (its `message.received` items, by connection).
     */
    private val feedRead: (kinds: Set<String>, ref: String?, connectionId: String?) -> Unit = { _, _, _ -> },
    /**
     * (VAULT-MESSAGING 0.80.0 §10.4) One ask of a connection's asks was withdrawn by the challenger: the feed marks the
     * batch's item read when no ask of the batch is still open, else re-posts its notification with the asks left.
     */
    private val feedWithdrawn: (com.vettid.core.data.feed.WithdrawnBatch) -> Unit = {},
    /** The three vault calls that decide a device request (a seam for tests; the vault client in the app). */
    private val deviceOps: suspend () -> DeviceDecisionOps = { VaultDeviceDecisions(api()) },
    /** The member's items with their field labels (the item cache): what a held `item.use` or `item.get` is shown against. */
    private val itemCache: () -> List<com.vettid.core.data.items.ItemSummary> = { emptyList() },
    /** Whether [deviceId] is in a `device.list` that was read (not this phone's own entry): a feed-only session request needs it. */
    private val deviceListed: (String) -> Boolean = { false },
    /** One `message.list` page, newest first, or the one [before] a position (a seam for tests; the vault client in the app). */
    private val readPage: suspend (connectionId: String, limit: Int, before: String?) -> com.vettid.core.vault.MessagePage =
        { id, limit, before -> api().messagePage(id, limit, before) },
    /** `connection.list` and `authenticate.list` (a seam for tests; the vault client in the app). */
    private val readConnections: suspend () -> ConnectionRead = { readConnectionsFrom(api()) },
    /** `connection.request.list` (a seam for tests; the vault client in the app). */
    private val readRequests: suspend () -> JsonObject = { api().requestList() },
) : ConnectionsRepository, MessagesRepository, ApprovalsRepository {
    /** Guards [local]; held only for memory work (nothing is written to disk), so a lock's clear cannot be raced. */
    private val localLock = Any()

    /** Whether the leftover pending-asks file is still there (its delete failed); guarded by [localLock]. */
    private var leftoverPending = true

    init {
        runCatching { obsoleteStore?.clear() }
        deleteLeftoverPending()
    }

    @Suppress("TooGenericExceptionCaught") // best effort: whatever the store throws, the caller goes on
    private fun deleteLeftoverPending() = synchronized(localLock) {
        if (!leftoverPending) return@synchronized
        try {
            obsoletePendingStore?.clear()
            leftoverPending = false
        } catch (e: Exception) {
            runCatching { onLeftoverFailed(e) }
        }
    }

    private var local: Local = Local()

    private val connectionsFlow = MutableStateFlow<List<ConnectionInfo>>(emptyList())
    private val authFlow = MutableStateFlow<Map<String, AuthenticationState>>(emptyMap())
    private val messagesFlow = MutableStateFlow<Map<String, List<MessageInfo>>>(emptyMap())

    /** The connections whose newest page was read since the last clear: a row of any other is "not loaded", never "empty". */
    private val loadedFlow = MutableStateFlow<Set<String>>(emptySet())
    private val reloadFailureFlow = MutableStateFlow<VaultFailure?>(null)

    /** Bumped by every lock and clear: a read in flight across one writes nothing back (messages, loaded set, failure). */
    private val lockEpoch = java.util.concurrent.atomic.AtomicInteger()

    /** Bumped at the start of every [refreshConversations]: only the latest-started reload writes the failure state. */
    private val reloadSeq = java.util.concurrent.atomic.AtomicInteger()

    /** Bumped by a hold ([forgetPending]) and a lock: a request or approval list read across either writes nothing. */
    private val holdEpoch = java.util.concurrent.atomic.AtomicInteger()
    private val reloadsRunning = java.util.concurrent.atomic.AtomicInteger()
    private val reloadingFlow = MutableStateFlow(false)

    /** Per conversation, where its loaded history stands (§10.5.2); dropped with its messages. */
    private val pagingFlow = MutableStateFlow<Map<String, MessagePaging>>(emptyMap())
    private val pages = Mutex()
    private val storedFlow = MutableStateFlow(local.events)
    private val listedFlow = MutableStateFlow<List<Approval>>(emptyList())
    private val requestsFlow = MutableStateFlow<List<Approval>>(emptyList())
    private val endsFlow = MutableStateFlow<Map<String, RequestEnd>>(emptyMap())
    private val declinesFlow = MutableStateFlow<List<PeerDecline>>(emptyList())

    /** `pending.list` (§10.4.2): the cut, the ticket of each read, what an older release answered. */
    private val pending = PendingAsks()
    private val pendingFailureFlow = MutableStateFlow<VaultFailure?>(null)

    /** The sign-in or approval challenges this run saw withdrawn (`withdrawn: true`, 0.80.0); their screens say so. */
    private val withdrawnFlow = MutableStateFlow<Set<String>>(emptySet())

    /** Challenge keys the vault answered `not_found` for in a decision: a withdrawal flag that comes after is still theirs. */
    private val notFoundKeys = LinkedHashSet<String>()

    override val pendingReadFailure: StateFlow<VaultFailure?> = pendingFailureFlow.asStateFlow()

    /** Peer declines noted in this run, so the `failed` that accompanies one never shows it again (0.10.5). */
    private val notedDeclines = mutableSetOf<String>()
    private val nowFlow = MutableStateFlow(now())
    private var ttls: List<InviteTtl>? = null

    override val connections: StateFlow<List<ConnectionInfo>> = connectionsFlow.asStateFlow()
    override val authentication: StateFlow<Map<String, AuthenticationState>> = authFlow.asStateFlow()

    override val reloadFailure: StateFlow<VaultFailure?> = reloadFailureFlow.asStateFlow()
    override val reloading: StateFlow<Boolean> = reloadingFlow.asStateFlow()

    override val conversations: StateFlow<List<ConversationSummary>> =
        combine(connectionsFlow, messagesFlow, loadedFlow) { cs, ms, loaded -> summaries(cs, ms, loaded) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The feed items (memory only), for the asks known only from them (AA5); set by the vault manager. */
    private val feedFlow = MutableStateFlow<List<com.vettid.core.vault.FeedItem>>(emptyList())

    /** A `pending.list` was merged since the unlock: it is authoritative for sessions and approvals, so none is made from a feed item. */
    private val listReadFlow = MutableStateFlow(false)
    private val feedClosedFlow = MutableStateFlow(FeedOnlyAsks.Closed())

    /** The feed's items, as they change (the vault manager forwards them). */
    fun setFeedItems(items: List<com.vettid.core.vault.FeedItem>) {
        feedFlow.value = items
    }

    private val listedApprovals: StateFlow<List<Approval>> =
        combine(storedFlow, listedFlow, requestsFlow, connectionsFlow, nowFlow) { stored, listed, requests, cs, now ->
            merge(stored, listed + requests, cs, now)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val approvals: StateFlow<List<Approval>> =
        combine(listedApprovals, feedFlow, listReadFlow, feedClosedFlow) { base, feed, listRead, closed ->
            // A merged pending.list is authoritative for the asks it lists; the vault does not list pairings yet
            // (0.83.0), so a pairing's entry is kept either way.
            val extra = FeedOnlyAsks.entries(feed, base.mapTo(HashSet()) { it.key }, closed, now())
                .filter { !listRead || it.isPairing }
            replaceSessions(if (extra.isEmpty()) base else (base + extra).sortedByDescending { it.receivedAt })
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The pairing with this `pending_id` was approved, rejected or ended: its entry (from the feed item) goes. */
    fun closePairing(pendingId: String) = closeFeedKey("device:${Approval.DeviceRequest.PAIR_PENDING}:$pendingId")

    /** A device has one access request (§6.8): of several shown, the newest stays. */
    private fun replaceSessions(list: List<Approval>): List<Approval> {
        val newest = list.mapNotNull { it as? Approval.DeviceRequest }.filter { it.sessionDeviceId != null }
            .groupBy { it.sessionDeviceId }.mapValues { (_, v) -> v.maxBy { it.receivedAt }.key }
        return list.filter { a -> val d = (a as? Approval.DeviceRequest)?.sessionDeviceId; d == null || newest[d] == a.key }
    }

    private fun closeFeedKey(key: String) = feedClosedFlow.update { it.copy(keys = it.keys + key) }

    private fun closeFeedDevice(deviceId: String, ts: Instant) = feedClosedFlow.update { it.copy(devices = it.devices + (deviceId to ts)) }

    override val requestEnds: StateFlow<Map<String, RequestEnd>> = endsFlow.asStateFlow()

    override val withdrawn: StateFlow<Set<String>> = withdrawnFlow.asStateFlow()

    override val peerDeclines: StateFlow<List<PeerDecline>> = declinesFlow.asStateFlow()

    private fun now(): Instant = Instant.now(clock)

    // --- local state (memory only; dropped with the open vault) ---

    @Serializable
    private data class StoredEvent(val type: String, val body: String, val at: Long)

    /** The name a request showed (0.10.5: kept until a peer's decline can name it). */
    private data class StoredName(val name: String, val at: Long)

    private data class StoredDecline(val id: String, val name: String? = null, val outgoing: Boolean, val at: Long) {
        fun model() = PeerDecline(id, name, outgoing, Instant.ofEpochMilli(at))
    }

    /**
     * Request codes are not kept here: the vault's request list supplies them again (`sas`), and a connection
     * keeps no code (it protects the moment of connecting only, 2026-10-08).
     * [requestNames]: the names open requests showed, by request id; [declines]: the other
     * member's declines not yet dismissed (0.10.5).
     */
    private data class Local(
        val events: List<StoredEvent> = emptyList(),
        val requestNames: Map<String, StoredName> = emptyMap(),
        val declines: List<StoredDecline> = emptyList(),
    )

    /**
     * Set by a lock or a wipe ([dropLocal] with `close`), cleared by [reopen] at the next unlock: while it is set no
     * `pending.list` merge happens, so a read that begins after the lock (the quiet refresh, the retry five seconds later)
     * cannot put asks back into memory. Events still update memory (one that arrives right after the unlock, before
     * [reopen], is shown). Guarded by [localLock].
     */
    private var closed = false

    /** Test hook: runs inside every [edit], under [localLock] (a test blocks here to queue other writes behind it). */
    @Volatile
    internal var editHook: (() -> Unit)? = null

    /** Test hook: runs between a page's read and its guarded write (a test locks here). */
    @Volatile
    internal var pageHook: (() -> Unit)? = null

    private fun edit(f: (Local) -> Local) = synchronized(localLock) {
        editHook?.invoke()
        val next = f(local)
        if (next == local) return@synchronized
        local = next
        storedFlow.value = next.events
        declinesFlow.value = next.declines.map { it.model() }
    }

    /** The vault is open again (an unlock): the `pending.list` read that follows may merge. */
    fun reopen() {
        synchronized(localLock) { closed = false }
    }

    /**
     * The vault is no longer open on this phone (locked, or a lock was asked for): the connections, messages and
     * requests read from it are dropped from memory, and so are the approvals the vault sent as events, the names
     * requests showed and the peers' declines (owner decision 2026-10-27: none of it is kept on the phone or across a
     * lock). An unlock reads the lists again.
     */
    fun forgetOpenVault() {
        // The pending.list state first, inside the lock that clears the events (see [dropLocal]); the file last, and its
        // failure is thrown to the lock's steps (retry, then end the process), after everything else is dropped.
        pendingFailureFlow.value = null
        withdrawnFlow.value = emptySet()
        synchronized(notFoundKeys) { notFoundKeys.clear() }
        dropReads()
        dropLocal(close = true)
    }

    /**
     * The vault is held or its check is due (§3.6.3) while the app is still open: the approvals and connection requests
     * (events, lists, names, declines) are dropped like the feed's. The connections and messages stay as they were
     * before this change; [refreshAllQuietly] reads the lists again after the check is passed. 
     */
    fun forgetPending() {
        pendingFailureFlow.value = null
        synchronized(localLock) {
            holdEpoch.incrementAndGet()
            listedFlow.value = emptyList()
            requestsFlow.value = emptyList()
        }
        endsFlow.value = emptyMap()
        dropLocal()
    }

    /**
     * The epoch is bumped and everything read from the vault (connections with their names, authentication states,
     * conversations, request and approval lists) dropped under [localLock], the monitor every write of such a read takes
     * with its epoch check: a read from before the lock cannot land after this (an equal, empty map would let a bare
     * compare-and-set succeed).
     */
    private fun dropReads() = synchronized(localLock) {
        lockEpoch.incrementAndGet()
        holdEpoch.incrementAndGet()
        connectionsFlow.value = emptyList()
        authFlow.value = emptyMap()
        listedFlow.value = emptyList()
        requestsFlow.value = emptyList()
        messagesFlow.value = emptyMap()
        loadedFlow.value = emptySet()
        reloadFailureFlow.value = null
        pagingFlow.value = emptyMap()
    }

    /** Forgets everything (sign-out with a new vault, or a deleted vault). */
    fun clear() {
        pendingFailureFlow.value = null
        withdrawnFlow.value = emptySet()
        synchronized(notFoundKeys) { notFoundKeys.clear() }
        dropReads()
        endsFlow.value = emptyMap()
        ttls = null
        runCatching { obsoleteStore?.clear() }
        dropLocal(close = true)
    }

    /**
     * The events, request names and declines held in memory: gone. The `pending.list`
     * state is reset FIRST and inside the lock: a read in flight whose merge takes this lock afterwards has a ticket at or
     * below the floor and merges nothing back (into memory or the file). The delete is not swallowed: a file that stays
     * would restore the pre-lock asks at the next unlock, so the failure is thrown to the caller (ClearSteps).
     */
    private fun dropLocal(close: Boolean = false) = synchronized(localLock) {
        pending.reset()
        listReadFlow.value = false
        feedClosedFlow.value = FeedOnlyAsks.Closed()
        if (close) closed = true
        local = Local()
        storedFlow.value = emptyList()
        declinesFlow.value = emptyList()
        notedDeclines.clear()
        deleteLeftoverPending() // best effort, never thrown
    }

    /** Re-reads connections, conversations and approvals; failures are left for the screens to retry. */
    fun refreshAllQuietly() {
        scope.launch {
            runCatching { refresh() }
            try {
                refreshConversations()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                // Not silent: refreshConversations noted it in [reloadFailure] for the Messages screen.
            }
            runCatching { refreshApprovals() }
            retryPendingOnce()
        }
    }

    // --- events ---

    /** Every event of the vault (§9.1). */
    @Suppress("CyclomaticComplexMethod")
    suspend fun onEvent(m: VaultMessage) {
        val b = m.body
        when (m.type) {
            "message.new" -> runCatching { VaultJson.decode(com.vettid.core.vault.Message.serializer(), b) }
                .getOrNull()?.let { upsert(ApprovalParser.message(it)) }
            in ApprovalParser.TYPES -> {
                // §10.4.2: a creation event not later than the last list is already reflected by it. Decided inside the
                // store's edit, so that no list merge falls between the decision and the write.
                val p = if (m.type in PendingAsks.LISTED_TYPES) ApprovalParser.parse(m.type, b, now()) else null
                val admit = p?.let { a ->
                    { pending.acceptCreate(a.key, m.ts, (a as? Approval.DeviceRequest)?.deviceId, m.type == PendingAsks.SESSION) }
                }
                store(m.type, b, admit, m.ts)
            }
            "connection.request.pending" -> ApprovalParser.incoming(b, now())?.let { onRequest(it, it.pendingId) }
            "connection.request.outgoing" -> ApprovalParser.outgoing(b, now())?.let { onRequest(it, it.connectionId) }
            "connection.event" -> onConnectionEvent(b)
            "connection.authenticate.result" -> {
                val conn = VaultJson.str(b, "connection_id") ?: return
                ApprovalParser.authResult(b, now(), authFlow.value[conn])?.let { s -> authFlow.update { it + (conn to s) } }
            }
            "sync.event" -> onSync(VaultJson.str(b, "kind") ?: "", b, m.ts)
        }
        nowFlow.value = now()
    }

    /** The held `device.session.pending` events of the same device as [parsed] (a session request), with their keys. */
    private fun sessionsOfSameDevice(events: List<StoredEvent>, parsed: Approval): List<Pair<String, StoredEvent>> {
        val req = parsed as? Approval.DeviceRequest
        val device = req?.sessionDeviceId ?: return emptyList()
        return events.mapNotNull { e ->
            val p = parsed(e) as? Approval.DeviceRequest
            if (p != null && p.sessionDeviceId == device && p.key != parsed.key) p.key to e else null
        }
    }

    private suspend fun store(type: String, body: JsonObject, admit: (() -> Boolean)? = null, ts: Instant? = null) {
        val parsed = ApprovalParser.parse(type, body, now()) ?: return
        val at = now().toEpochMilli()
        val text = VaultJson.json.encodeToString(JsonObject.serializer(), body)
        // Noted before the suspending write, so a read that reaches its prune step in the gap keeps the batch.
        if (parsed is Approval.ShareDecision) noteBatch(parsed)
        edit { l ->
            // A newer session request of the same device replaces an older (§6.8, by the event's ts): an older one that
            // arrives late is not shown, and the older ones already held go.
            val older = sessionsOfSameDevice(l.events, parsed)
            if (ts != null && older.any { (k, e) -> (pending.createdTs(k) ?: Instant.ofEpochMilli(e.at)).isAfter(ts) }) return@edit l
            if (admit != null && !admit()) return@edit l
            val others = l.events.filterNot { e -> keyOf(e) == parsed.key || older.any { (_, o) -> o === e } }
            val merged = if (parsed is Approval.ShareDecision) mergeShare(l.events, parsed, body) else text
            l.copy(events = others + StoredEvent(type, merged, at))
        }
        if (parsed is Approval.ShareDecision) addBatch(parsed)
    }

    /**
     * A `share.pending` batch for a rule whose question the list holds adds to it at once, not at the next read (§10.12):
     * the batch's items, keeping the listed item's name, `ask_rule_id` and `shared` where the batch lacks them.
     */
    private fun addBatch(batch: Approval.ShareDecision) = synchronized(readLock) {
        listedFlow.update { l ->
            l.map { a ->
                if (a !is Approval.ShareDecision || a.key != batch.key) return@map a
                a.copy(items = ShareQuestions.addBatch(a.items, batch.items), reason = a.reason ?: batch.reason)
            }
        }
    }

    /** Several `share.pending` batches of one rule add up (§10.12). */
    private fun mergeShare(events: List<StoredEvent>, parsed: Approval.ShareDecision, body: JsonObject): String {
        val old = events.firstOrNull { keyOf(it) == parsed.key }?.let { parseBody(it.body) } ?: return enc(body)
        val items = ((old["items"] as? JsonArray).orEmpty() + (body["items"] as? JsonArray).orEmpty())
            .distinctBy { (it as? JsonObject)?.get("item_id") }
        return enc(JsonObject(body + ("items" to JsonArray(items))))
    }

    private fun enc(o: JsonObject) = VaultJson.json.encodeToString(JsonObject.serializer(), o)

    private fun parseBody(s: String): JsonObject? = try {
        VaultJson.json.parseToJsonElement(s).jsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parsed(e: StoredEvent): Approval? =
        parseBody(e.body)?.let { ApprovalParser.parse(e.type, it, Instant.ofEpochMilli(e.at)) }

    private fun keyOf(e: StoredEvent): String? = parsed(e)?.key

    private suspend fun dropApproval(key: String) = edit { l -> l.copy(events = l.events.filterNot { keyOf(it) == key }) }

    // --- connection requests (§6.4, §10.4; 0.10.2, 0.10.3) ---

    private fun requestId(a: Approval): String? = when (a) {
        is Approval.ConnectionRequest -> a.pendingId
        is Approval.OutgoingRequest -> a.connectionId
        else -> null
    }

    /** A request event (`connection.request.pending` / `.outgoing`): shown at once, its code remembered. */
    private fun onRequest(a: Approval, id: String) {
        requestsFlow.update { l ->
            val old = l.firstOrNull { requestId(it) == id }
            val next = when {
                // An event never takes back what a decision already moved on (approved stays approved).
                old is Approval.ConnectionRequest && a is Approval.ConnectionRequest ->
                    a.copy(state = maxOf(old.state, a.state), peerApproved = old.peerApproved || a.peerApproved)
                old is Approval.OutgoingRequest && a is Approval.OutgoingRequest ->
                    a.copy(
                        state = maxOf(old.state, a.state), peerApproved = old.peerApproved || a.peerApproved, name = a.name ?: old.name,
                        service = old.service || a.service,
                    )
                else -> a
            }
            l.filterNot { requestId(it) == id } + next
        }
        endsFlow.update { it - id }
        nameOf(a)?.let { rememberNames(listOf(id to it)) }
    }

    private fun nameOf(a: Approval): String? = when (a) {
        is Approval.ConnectionRequest -> a.name
        is Approval.OutgoingRequest -> a.name
        else -> null
    }

    /** Keeps the names requests showed (0.10.5), dropping ones older than any request can live. */
    private fun rememberNames(names: List<Pair<String, String>>) {
        val fresh = names.filter { (id, n) -> local.requestNames[id]?.name != n }
        if (fresh.isEmpty()) return
        val t = now().toEpochMilli()
        val cutoff = now().minus(NAME_RETENTION).toEpochMilli()
        edit { l ->
            l.copy(requestNames = l.requestNames.filterValues { it.at >= cutoff } + fresh.associate { (id, n) -> id to StoredName(n, t) })
        }
    }

    private fun patchRequest(id: String, f: (Approval) -> Approval) =
        requestsFlow.update { l -> l.map { if (requestId(it) == id) f(it) else it } }

    private suspend fun endRequest(id: String, end: RequestEnd?) {
        requestsFlow.update { l -> l.filterNot { requestId(it) == id } }
        // The other member's decline is what the member is told, whatever end follows it.
        if (end != null) endsFlow.update { if (it[id] == RequestEnd.PEER_DECLINED) it else it + (id to end) }
        // A bare `failed` keeps the name: an older ordering may still bring the peer's decline (0.10.5).
        val keepName = end == RequestEnd.FAILED
        edit { l -> l.copy(requestNames = if (keepName) l.requestNames else l.requestNames - id) }
    }

    /**
     * The other member declined request [id] (`connection.declined`, 0.10.5, §6.4): it ends
     * and the member is told once, with the name the request showed. [outgoing]: this member's
     * own outgoing request (the accepter's side). Comes as `sync.event{peer_declined}` and, on
     * the accepter's side, also as `connection.event{failed, reason: "declined"}`, in either order.
     */
    private suspend fun onPeerDeclined(id: String, outgoing: Boolean) {
        val name = requestsFlow.value.firstOrNull { requestId(it) == id }?.let(::nameOf) ?: local.requestNames[id]?.name
        val first = notedDeclines.add(id) && local.declines.none { it.id == id }
        requestsFlow.update { l -> l.filterNot { requestId(it) == id } }
        endsFlow.update { it + (id to RequestEnd.PEER_DECLINED) }
        val at = now().toEpochMilli()
        edit { l ->
            l.copy(
                requestNames = l.requestNames - id,
                declines = if (first) l.declines + StoredDecline(id, name, outgoing, at) else l.declines,
            )
        }
    }

    override suspend fun dismissPeerDecline(requestId: String) =
        edit { l -> l.copy(declines = l.declines.filterNot { it.id == requestId }) }

    private suspend fun refreshRequestsQuietly() {
        try {
            refreshRequests()
        } catch (_: VaultFailure) {
            // a release before 0.10.2 has no request list; the screens retry
        }
    }

    /** Test hook: runs between a request-list read and its guarded write. */
    @Volatile
    internal var requestsHook: (() -> Unit)? = null

    override suspend fun refreshRequests() {
        val epoch = lockEpoch.get()
        val hold = holdEpoch.get()
        val list = vaultGuard(resend = true) { readRequests() }
        val t = now()
        fun entries(k: String) = (list[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val parsed = entries("incoming").mapNotNull { ApprovalParser.incoming(it, t) } +
            entries("outgoing").mapNotNull { ApprovalParser.outgoing(it, t) }
        requestsHook?.invoke()
        synchronized(localLock) {
            // A lock or a hold came while the list was read: nothing of it stays.
            if (epoch != lockEpoch.get() || hold != holdEpoch.get()) return
            // A request already known as a service stays one if the list entry lacks the marker.
            requestsFlow.value = RequestKinds.keepServices(requestsFlow.value, parsed)
            rememberNames(parsed.mapNotNull { a -> requestId(a)?.let { id -> nameOf(a)?.let { id to it } } })
        }
    }

    private suspend fun onConnectionEvent(b: JsonObject) {
        val conn = VaultJson.str(b, "connection_id") ?: return
        runCatching { refresh() }
        when (VaultJson.str(b, "event")) {
            "added" -> onAdded(conn, VaultJson.str(b, "pending_id") ?: conn)
            // An outgoing request ended without a connection other than by this member's decline (§6.4);
            // `reason: "declined"`: the inviter declined (0.10.5), told once with the sync.event that accompanies it.
            "failed" -> if (VaultJson.str(b, "reason") == "declined") {
                onPeerDeclined(conn, outgoing = true)
            } else {
                endRequest(conn, RequestEnd.FAILED)
            }
            "removed" -> {
                messagesFlow.update { it - conn }
                loadedFlow.update { it - conn }
                pagingFlow.update { it - conn }
                authFlow.update { it - conn }
            }
        }
    }

    /** A new connection: the request it came from ([req], §10.4 `pending_id`) and its code are done with. */
    private suspend fun onAdded(conn: String, req: String) {
        requestsFlow.update { l -> l.filterNot { requestId(it) == req } }
        edit { l -> l.copy(requestNames = l.requestNames - req) }
        val epoch = lockEpoch.get()
        try {
            load(conn)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            noteReloadFailure(e, epoch, reloadSeq.incrementAndGet())
        }
    }

    /**
     * `sync.event{kind: "connection.request"}`: another device's decision, the peer's approval
     * or decline (0.10.5), or an expiry (§10.1). `pending_id` names an incoming request,
     * `connection_id` an outgoing one.
     */
    private suspend fun onRequestSync(b: JsonObject) {
        val pending = VaultJson.str(b, "pending_id")
        val id = pending ?: VaultJson.str(b, "connection_id") ?: return
        when (VaultJson.str(b, "state")) {
            "approved" -> patchRequest(id) { a ->
                when (a) {
                    is Approval.ConnectionRequest -> a.copy(state = RequestState.APPROVED)
                    is Approval.OutgoingRequest -> a.copy(state = RequestState.APPROVED)
                    else -> a
                }
            }
            "peer_approved" -> patchRequest(id) { a ->
                when (a) {
                    is Approval.ConnectionRequest -> a.copy(peerApproved = true)
                    is Approval.OutgoingRequest -> a.copy(peerApproved = true)
                    else -> a
                }
            }
            "declined" -> endRequest(id, RequestEnd.DECLINED)
            "peer_declined" -> onPeerDeclined(id, outgoing = pending == null)
            "expired" -> endRequest(id, RequestEnd.EXPIRED)
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private suspend fun onSync(kind: String, b: JsonObject, ts: Instant) {
        fun s(k: String) = VaultJson.str(b, k)
        when (kind) {
            "message.receipt" -> {
                val conn = s("connection_id") ?: return
                val id = s("message_id") ?: return
                val read = s("receipt") == "read"
                patch(conn, id) { it.copy(delivered = true, read = it.read || read) }
            }
            "message.read" -> patch(s("connection_id") ?: return, s("message_id") ?: return) { it.copy(read = true) }
            // §10.5.1: each change of an attachment's state; the message itself is unchanged.
            "message.attachment" -> patch(s("connection_id") ?: return, s("message_id") ?: return) { m ->
                m.attachment?.let { a -> m.copy(attachment = a.copy(state = AttachmentState.of(s("state")), reason = s("reason"))) } ?: m
            }
            "connection.changed", "block.added", "block.removed" -> runCatching { refresh() }
            "connection.request" -> onRequestSync(b)
            "connection.authenticate.decided" -> s("request_id")?.let { onAuthenticateDecided(it, b, ts) }
            "device.session" -> s("device_id")?.let { onSessionClosed(it, ts) }
            "grant.request.decided" -> s("request_id")?.let { id ->
                dropApproval("grant:$id")
                listedFlow.update { l -> l.filterNot { it.key == "grant:$id" } }
            }
            "critical-secret-use.decided" -> s("request_id")?.let { id ->
                dropApproval("critical:$id")
                listedFlow.update { l -> l.filterNot { it.key == "critical:$id" } }
            }
            "share.decided" -> s("rule_id")?.let { onShareDecided(it, b) }
            "approval.decided" -> s("approval_id")?.let { closeAsk("device:approval.pending:$it", ts) }
        }
    }

    /**
     * `sync.event{connection.authenticate.decided}`. With `withdrawn: true` (0.80.0 §10.4) the challenger withdrew the ask:
     * it closes as for a decision elsewhere, and the open screen, the batch's entry and its notification say so (the
     * flag is noted first, so that the screen never reads "decided elsewhere" in between). A flag for an id this app
     * never knew does nothing. Without the flag it closes as before.
     */
    private suspend fun onAuthenticateDecided(id: String, b: JsonObject, ts: Instant) {
        val key = "auth:$id"
        val flagged = (b["withdrawn"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true &&
            (b["approved"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull != true
        if (!flagged) {
            closeAsk(key, ts)
            return
        }
        // Read from the stored state, not the published list (which may lag a moment behind the last event).
        val before = merge(storedFlow.value, listedFlow.value + requestsFlow.value, connectionsFlow.value, now())
        val ask = before.firstOrNull { it.key == key } as? Approval.Authentication
        if (ask != null || synchronized(notFoundKeys) { key in notFoundKeys }) {
            // Bounded: the last [MAX_WITHDRAWN] ids (a set keeps its insertion order).
            withdrawnFlow.update { s ->
                (s - id + id).let { if (it.size > MAX_WITHDRAWN) it.drop(it.size - MAX_WITHDRAWN).toSet() else it }
            }
        }
        closeAsk(key, ts)
        if (ask != null) {
            val batch = AskBatching.batchOf(before, key).ifEmpty { listOf(ask) }
            val now = now()
            val left = batch.filter { it.key != key && it.exp?.isAfter(now) != false }
            feedWithdrawn(
                com.vettid.core.data.feed.WithdrawnBatch(
                    connectionId = ask.connectionId,
                    askKeys = batch.map { it.key }.toSet(),
                    receivedAt = ask.receivedAt,
                    remaining = left.size,
                    openUntil = left.mapNotNull { it.exp }.minOrNull(),
                    askKey = key,
                    // The notification may go by itself only when every ask still open has ended: the latest exp, none if one has none.
                    closeAt = if (left.isEmpty() || left.any { it.exp == null }) null else left.mapNotNull { it.exp }.max(),
                    askTs = pending.createdTs(key),
                ),
            )
        }
    }

    /** A closing event of an ask `pending.list` carries: decided inside the edit, with the drop (§10.4.2 ts rule). */
    private suspend fun closeAsk(key: String, ts: Instant) {
        closeFeedKey(key)
        edit { l ->
            if (!pending.acceptClose(key, ts)) l else l.copy(events = l.events.filterNot { keyOf(it) == key })
        }
    }

    /**
     * `sync.event{device.session}` (granted, denied or ended; with `expires_at` a session goes on, and a deny of a
     * renewal keeps it): the device's session request is closed, only it (a device has at most one, §6.8; the held
     * approvals of an ended session arrive as their own `approval.decided`, 0.77.0). A late one for an earlier session
     * (not later than the last list) is ignored, and a request created after the event stays: it is not about it.
     */
    private suspend fun onSessionClosed(deviceId: String, ts: Instant) {
        closeFeedDevice(deviceId, ts)
        closeSessionEvents(deviceId, ts)
    }

    private suspend fun closeSessionEvents(deviceId: String, ts: Instant) = edit { l ->
        if (!pending.acceptDeviceClose(deviceId, ts)) return@edit l
        l.copy(
            events = l.events.filterNot { e ->
                val p = parsed(e) as? Approval.DeviceRequest
                p != null && p.type == PendingAsks.SESSION && p.deviceId == deviceId && !pending.createdAfter(p.key, ts)
            },
        )
    }

    private suspend fun onShareDecided(rule: String, b: JsonObject) {
        applyShareDecided(rule, listOf("included", "declined").flatMap { k -> ids(b, k) }.toSet())
    }

    private fun ids(b: JsonObject, k: String): List<String> =
        (b[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

    /**
     * The items [done] (decided here or on another device) leave the questions of [rule] and of the subject's other
     * rules, where they wait too (one answer per item and subject, §10.12); a question left empty goes. Kept events and
     * the list, and a read now on its way.
     */
    private suspend fun applyShareDecided(rule: String, done: Set<String>, subjectHint: String? = null): Set<String> {
        if (done.isEmpty()) return emptySet()
        val known = subjectHint ?: subjectOfRule(rule)
        // A rule of no known subject: a read now settles the rest.
        if (known == null) scope.launch { runCatching { refreshApprovals() } }
        val subject = known ?: "rule:$rule"
        val applies = { d: Approval.ShareDecision -> d.ruleId == rule || ShareQuestions.subjectKey(d) == subject }
        // The subject's questions before the removal: the one this empties has its feed item read below.
        val asked = (listedFlow.value + storedFlow.value.mapNotNull(::parsed)).filter { it is Approval.ShareDecision && applies(it) }
            .map { it.key }.toSet() + "share:$rule"
        removeShareItems(done, applies) {
            changes++
            if (readSinces.isNotEmpty()) {
                decidedLog += ShareMark(changes, known, rule, done)
                if (decidedLog.size > MAX_LOG) decidedLog.removeAt(0)
            }
        }
        // Every question of the subject now gone from the list and the kept events is read, not only the decided rule's:
        // an item decided under one rule also leaves the others, and the dedupe may have hidden it from their screens.
        return asked.filter { key -> listedFlow.value.none { it.key == key } && storedFlow.value.none { keyOf(it) == key } }
            .onEach(::decided).toSet()
    }

    /**
     * The items [ids] leave the share questions [applies] picks, in the list and the kept events; a question left empty
     * goes. [mark] runs with the list change, so a read taking its result in cannot interleave (it holds the same lock).
     */
    private suspend fun removeShareItems(ids: Set<String>, applies: (Approval.ShareDecision) -> Boolean, mark: () -> Unit = {}) {
        synchronized(readLock) {
            mark()
            listedFlow.update { l ->
                l.mapNotNull { a ->
                    if (a !is Approval.ShareDecision || !applies(a)) return@mapNotNull a
                    a.copy(items = a.items.filter { it.itemId !in ids }).takeIf { it.items.isNotEmpty() }
                }
            }
        }
        edit { l ->
            l.copy(
                events = l.events.mapNotNull { e ->
                    val d = parsed(e) as? Approval.ShareDecision
                    if (d == null || !applies(d)) return@mapNotNull e
                    val o = parseBody(e.body) ?: return@mapNotNull null
                    val left = (o["items"] as? JsonArray).orEmpty().filter {
                        ((it as? JsonObject)?.get("item_id") as? JsonPrimitive)?.content !in ids
                    }
                    if (left.isEmpty()) null else e.copy(body = enc(JsonObject(o + ("items" to JsonArray(left)))))
                },
            )
        }
    }

    /**
     * The subject of [rule]'s question: from the raw list first (the deduped [approvals] can hide a rule), then the kept
     * events, then a listed item that names the rule as its `ask_rule_id` (it is the same subject's). Null if unknown.
     */
    private fun subjectOfRule(rule: String): String? {
        val listed = listedFlow.value.filterIsInstance<Approval.ShareDecision>()
        val stored = storedFlow.value.mapNotNull(::parsed).filterIsInstance<Approval.ShareDecision>()
        (listed.firstOrNull { it.ruleId == rule } ?: stored.firstOrNull { it.ruleId == rule })
            ?.let { return ShareQuestions.subjectKey(it) }
        return listed.firstOrNull { d -> d.items.any { it.askRuleId == rule } }?.let(ShareQuestions::subjectOrNull)
    }

    // --- ConnectionsRepository ---

    /** What `connection.list` and `authenticate.list` answered; [authFailure] is thrown after the connections are taken in. */
    internal class ConnectionRead(
        val connections: List<ConnectionInfo>,
        val states: List<AuthenticationState>?,
        val authFailure: Exception? = null,
    )

    /** Test hook: runs between a connection read and its guarded write (a test locks here). */
    @Volatile
    internal var refreshHook: (() -> Unit)? = null

    override suspend fun refresh(): Unit {
        val epoch = lockEpoch.get() // before the read: a lock after this voids what it answers
        vaultGuard(resend = true) {
            val read = readConnections()
            refreshHook?.invoke()
            // Epoch check and writes under [localLock], where a lock bumps the epoch and clears (see [dropReads]).
            synchronized(localLock) {
                if (epoch == lockEpoch.get()) {
                    connectionsFlow.value = read.connections
                    read.states?.let { states ->
                        authFlow.update { old ->
                            states.associateBy { it.connectionId }.mapValues { (id, st) ->
                                val prev = old[id]
                                val waiting = prev?.waitingUntil?.takeIf { it.isAfter(now()) }
                                st.copy(waitingUntil = waiting, keyChanged = prev?.keyChanged ?: false)
                            } + old.filterKeys { k -> states.none { it.connectionId == k } }
                        }
                    }
                }
            }
            read.authFailure?.let { throw it }
        }
    }

    override suspend fun connection(id: String): ConnectionInfo {
        val epoch = lockEpoch.get()
        return vaultGuard(resend = true) {
            val c = ApprovalParser.connection(api().connectionGet(id))
            synchronized(localLock) {
                if (epoch == lockEpoch.get()) {
                    connectionsFlow.update { l -> if (l.any { it.id == id }) l.map { if (it.id == id) c else it } else l + c }
                }
            }
            c
        }
    }

    override suspend fun inviteTtls(): List<InviteTtl> = ttls ?: vaultGuard(resend = true) {
        val l = api().relayLimits()
        val max = minOf(l.openTokenMaxLifetimeSeconds, l.claimTtlSeconds)
        InviteTtl.entries.filter { it.seconds <= max }.ifEmpty { listOf(InviteTtl.TEN_MINUTES) }
    }.also { ttls = it }

    override suspend fun createInvite(ttl: InviteTtl): InviteInfo = vaultGuard {
        val i = api().inviteCreate(ttl.seconds)
        InviteInfo(i.inviteId, i.link, InviteLinks.qrPayload(i.link), ApprovalParser.instant(i.exp) ?: InviteLinks.expiry(i.link), i.remote)
    }

    override suspend fun outstandingInvites(): List<OutstandingInvite> = vaultGuard(resend = true) {
        (api().inviteList()["invites"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = VaultJson.str(o, "invite_id") ?: return@mapNotNull null
            OutstandingInvite(id, ApprovalParser.instant(VaultJson.str(o, "exp")), (o["remote"] as? JsonPrimitive)?.content == "true")
        }.filter { it.exp == null || it.exp.isAfter(now()) }
    }

    override suspend fun cancelInvite(inviteId: String) = vaultGuard { api().inviteCancel(inviteId) }

    override suspend fun acceptInvite(text: String): AcceptedConnection {
        val link = when (val p = InviteLinks.parse(text, now())) {
            is InviteLinks.Parsed.Ok -> p.link
            InviteLinks.Parsed.Expired -> throw VaultFailure(FailureKind.INVITE_EXPIRED)
            InviteLinks.Parsed.NotAConnection -> throw VaultFailure(FailureKind.INVITE_NOT_CONNECTION)
            InviteLinks.Parsed.Invalid -> throw VaultFailure(FailureKind.INVITE_INVALID)
        }
        val epoch = lockEpoch.get()
        val r = vaultGuard {
            try {
                val a = api().inviteAccept(link)
                AcceptedConnection(
                    a.connectionId, a.name?.takeIf { it.isNotBlank() }, exists = false, exp = ApprovalParser.instant(a.exp),
                    service = ApprovalParser.isService(a.peerKind),
                )
            } catch (e: com.vettid.core.vault.VaultOpException) {
                // §6.4 "Already connected" (0.10.2): the vault's own id for that peer; no handshake was made.
                val existing = e.body?.let { VaultJson.str(it, "connection_id") }
                if (e.code != "exists" || existing == null) throw e
                AcceptedConnection(existing, exists = true)
            }
        }
        if (!r.exists) {
            val waiting = Approval.OutgoingRequest(
                r.connectionId, null, remote = false, name = r.name, state = RequestState.WAITING, peerApproved = false,
                introducedBy = null, receivedAt = now(), exp = r.exp, service = r.service,
            )
            // The outgoing event may already have arrived (it follows hs.resp): keep it then.
            // An event that already arrived is kept, and learns the kind the accept's answer gave.
            synchronized(localLock) {
                if (epoch == lockEpoch.get()) {
                    requestsFlow.update { l -> RequestKinds.withAccepted(l, waiting) }
                    endsFlow.update { it - r.connectionId }
                    r.name?.let { rememberNames(listOf(r.connectionId to it)) }
                }
            }
        }
        return r
    }

    private suspend fun updateMeta(id: String, change: suspend (VaultApi, Long) -> Unit) {
        val epoch = lockEpoch.get()
        vaultGuard {
            updateMetaIn(id, epoch, change)
        }
    }

    private suspend fun updateMetaIn(id: String, epoch: Int, change: suspend (VaultApi, Long) -> Unit) {
        val a = api()
        val version = connectionsFlow.value.firstOrNull { it.id == id }?.version ?: a.connectionGet(id).version
        try {
            change(a, version)
        } catch (e: com.vettid.core.vault.VaultOpException) {
            if (e.code != "conflict") throw e
            change(a, a.connectionGet(id).version) // another device changed it first: retry once on the current version
        }
        val c = ApprovalParser.connection(a.connectionGet(id))
        synchronized(localLock) {
            if (epoch == lockEpoch.get()) connectionsFlow.update { l -> l.map { if (it.id == id) c else it } }
        }
    }

    override suspend fun setFavorite(id: String, favorite: Boolean) =
        updateMeta(id) { a, v -> a.connectionUpdate(id, v, favorite = favorite) }

    override suspend fun remove(id: String) {
        vaultGuard { api().connectionRemove(id) }
        forget(id)
    }

    private fun forget(id: String) {
        connectionsFlow.update { l -> l.filterNot { it.id == id } }
        messagesFlow.update { it - id }
        loadedFlow.update { it - id }
        pagingFlow.update { it - id }
        authFlow.update { it - id }
    }

    override suspend fun requestAuthentication(id: String, context: String?): String {
        val epoch = lockEpoch.get()
        return vaultGuard {
            val rid = api().authenticateRequest(id, context?.takeIf { it.isNotBlank() })
            synchronized(localLock) {
                if (epoch == lockEpoch.get()) {
                    authFlow.update { m ->
                        val base = m[id] ?: AuthenticationState(id)
                        m + (id to base.copy(lastResult = "requested", lastAt = now(), waitingUntil = now().plus(AUTH_WAIT)))
                    }
                }
            }
            rid
        }
    }

    override suspend fun setAsksMuted(id: String, muted: Boolean) {
        vaultGuard { api().connectionAsksMute(id, muted) }
        patchAsks(id) { it.muted(muted) }
        runCatching { refresh() }
    }

    override suspend fun resumeAsks(id: String) {
        vaultGuard { api().connectionAsksResume(id) }
        patchAsks(id) { it.resumed() }
        runCatching { refresh() }
    }

    /** The answer is in: shown at once, before `sync.event{connection.changed}` and the list say so (§10.4.1). */
    private fun patchAsks(id: String, f: (AskState) -> AskState) {
        connectionsFlow.update { l -> l.map { c -> if (c.id == id) c.copy(asks = f(c.asks ?: AskState())) else c } }
    }

    // --- MessagesRepository ---

    override fun messages(connectionId: String): Flow<List<MessageInfo>> =
        messagesFlow.map { it[connectionId] ?: emptyList() }.distinctUntilChanged()

    /** Test seam: the connections as `connection.list` would have left them. */
    internal fun seedConnections(list: List<ConnectionInfo>) {
        connectionsFlow.value = list
    }

    /**
     * Every caller's failure ends up in [reloadFailure] (set on failure, cleared on success); the latest-started reload
     * wins, and one that ran across a lock writes nothing.
     */
    override suspend fun refreshConversations() {
        val epoch = lockEpoch.get()
        val seq = reloadSeq.incrementAndGet()
        synchronized(localLock) { // count and flag change together: a late "false" cannot overwrite a newer reload's "true"
            reloadsRunning.incrementAndGet()
            reloadingFlow.value = true
        }
        val failed = try {
            if (connectionsFlow.value.isEmpty()) refresh()
            loadEveryThread()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            e
        } finally {
            synchronized(localLock) { reloadingFlow.value = reloadsRunning.decrementAndGet() > 0 }
        }
        if (failed == null) {
            synchronized(localLock) {
                if (epoch == lockEpoch.get() && seq == reloadSeq.get()) reloadFailureFlow.value = null
            }
        } else {
            noteReloadFailure(failed, epoch, seq)
            throw failed
        }
    }

    /**
     * Every thread is tried even when one fails (a vault just restarted may fail the first only); a thread that failed
     * stays "not loaded". Returns the held failure if any, else the first, or null.
     */
    private suspend fun loadEveryThread(): Exception? {
        var first: Exception? = null
        // Messages never lists a service, so no request is spent on one.
        for (c in connectionsFlow.value.filter { it.state == ConnectionState.ACTIVE && !it.service }) {
            try {
                load(c.id)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                if (first == null || e.isHeld() && !first.isHeld()) first = e
                // A failure that every thread would meet (held, offline, no answer, signed out): no more requests.
                if (e.meetsEveryThread()) break
            }
        }
        return first
    }

    /** The list's rows are the active, non-service connections (Messages never lists a service). */
    private fun allActiveLoaded() =
        connectionsFlow.value.none { it.state == ConnectionState.ACTIVE && !it.service && it.id !in loadedFlow.value }

    /**
     * Held, signed out, paused, offline, or a lock pending: every thread would meet it. Not `VAULT_UNAVAILABLE` (the
     * vault restarting for its update fails the first thread, and the others may well load) nor any other no-answer.
     */
    private fun Exception.meetsEveryThread(): Boolean {
        val f = this as? VaultFailure ?: return false
        // The code whatever the kind: through the API a pending lock arrives as OTHER (or NO_RESPONSE).
        return f.kind in ALL_THREADS || f.code == MoveRepository.CODE_LOCK_PENDING
    }

    private fun Exception.isHeld() = this is VaultFailure && kind == FailureKind.OWNER_CHECK_REQUIRED

    private fun noteReloadFailure(e: Exception, epoch: Int, seq: Int) {
        val failure = e as? VaultFailure ?: VaultFailure(FailureKind.OTHER, cause = e)
        synchronized(localLock) {
            if (epoch == lockEpoch.get() && seq == reloadSeq.get()) reloadFailureFlow.value = failure
        }
    }

    override fun olderAvailable(connectionId: String): Flow<Boolean> =
        pagingFlow.map { it[connectionId]?.hasOlder == true }.distinctUntilChanged()

    /**
     * The newest page (§10.5.2). Older pages the member already scrolled to stay when the new page joins them
     * ([MessagePages.newest]); [fresh] drops them first (a position the vault no longer holds).
     */
    override suspend fun load(connectionId: String) = load(connectionId, fresh = false)

    private suspend fun load(connectionId: String, fresh: Boolean) {
        val epoch = lockEpoch.get() // before the read, on the caller's thread: a lock after this point voids the page
        loadPage(connectionId, fresh, epoch)
    }

    private suspend fun loadPage(connectionId: String, fresh: Boolean, epoch: Int) = vaultGuard(resend = true) {
        val page = pageFitting { limit -> readPage(connectionId, limit, null) }
        val list = page.messages.map(ApprovalParser::message)
        pages.withLock {
            val current = messagesFlow.value[connectionId].orEmpty()
            val paging = if (fresh) MessagePaging() else pagingFlow.value[connectionId] ?: MessagePaging()
            val merged = MessagePages.newest(list, page.nextBefore, current, paging)
            pageHook?.invoke()
            // Epoch check and writes under [localLock], where a lock bumps the epoch and clears: a page read across a
            // lock writes nothing, whatever the maps held (an empty map equals its clear, so a bare update could land).
            synchronized(localLock) {
                if (epoch == lockEpoch.get()) {
                    messagesFlow.update { it + (connectionId to merged.messages) }
                    pagingFlow.update { it + (connectionId to merged.paging) }
                    val wasLoaded = connectionId in loadedFlow.value
                    loadedFlow.update { it + connectionId }
                    if (!wasLoaded && reloadFailureFlow.value != null && allActiveLoaded()) {
                        // This page was the last unloaded row: a load error no longer describes the list. (A page of a
                        // row that was loaded before says nothing about the failed reload.)
                        reloadFailureFlow.value = null
                    }
                }
            }
        }
    }

    /** Test hook: runs between an older page's read and its guarded write (a test locks here). */
    @Volatile
    internal var olderHook: (() -> Unit)? = null

    /**
     * The next older page, before the oldest loaded one (§10.5.2). A position the vault no longer holds
     * (`not_found`: the message was deleted meanwhile, on another device) starts again from the newest page.
     */
    override suspend fun loadOlder(connectionId: String) {
        val epoch = lockEpoch.get() // before anything is read, the position included
        val before = pagingFlow.value[connectionId]?.nextBefore ?: return
        val page = try {
            vaultGuard(resend = true) { pageFitting { limit -> readPage(connectionId, limit, before) } }
        } catch (e: VaultFailure) {
            if (e.kind != FailureKind.NOT_FOUND) throw e
            null
        }
        if (page == null) {
            load(connectionId, fresh = true)
        } else {
            val list = page.messages.map(ApprovalParser::message)
            olderHook?.invoke()
            pages.withLock {
                // Epoch check and writes under [localLock] (see [dropReads]): a page read across a lock writes nothing.
                synchronized(localLock) {
                    // A reload may have replaced the position meanwhile: then this page is not the next one.
                    if (epoch == lockEpoch.get() && pagingFlow.value[connectionId]?.nextBefore == before) {
                        val merged = MessagePages.older(list, page.nextBefore, messagesFlow.value[connectionId].orEmpty())
                        messagesFlow.update { it + (connectionId to merged.messages) }
                        pagingFlow.update { it + (connectionId to merged.paging) }
                    }
                }
            }
        }
    }

    /** A message this app sent another way (a photo, [AttachmentManager]) joins its conversation. */
    internal fun addSent(m: MessageInfo) = upsert(m)

    /**
     * A page asked for with [PAGE] messages and, when the answer could not be sealed (`response_too_large`, §8.1),
     * asked for again with half as many, down to one: a page of long messages still arrives.
     */
    private suspend fun pageFitting(ask: suspend (Int) -> com.vettid.core.vault.MessagePage): com.vettid.core.vault.MessagePage {
        var limit = PAGE
        while (true) {
            try {
                return ask(limit)
            } catch (e: com.vettid.core.vault.VaultOpException) {
                if (e.code != CODE_TOO_LARGE || limit == 1) throw e
                limit /= 2
            }
        }
    }

    private fun upsert(m: MessageInfo) = messagesFlow.update { map ->
        val list = map[m.connectionId].orEmpty()
        val next = if (list.any { it.messageId == m.messageId }) {
            list.map { if (it.messageId == m.messageId) m.copy(delivered = it.delivered || m.delivered, read = it.read || m.read) else it }
        } else {
            // A new message joins at the end, in the order it reached this phone (the vault's order), never by
            // `sent_at`, the sender's clock: the pages join by that order (MessagePages).
            list + m
        }
        map + (m.connectionId to next)
    }

    private fun patch(conn: String, id: String, f: (MessageInfo) -> MessageInfo) = messagesFlow.update { map ->
        val list = map[conn] ?: return@update map
        map + (conn to list.map { if (it.messageId == id) f(it) else it })
    }

    override suspend fun send(connectionId: String, text: String): MessageInfo {
        val size = text.toByteArray(Charsets.UTF_8).size
        if (size == 0 || size > MessagesRepository.MAX_TEXT_BYTES) throw VaultFailure(FailureKind.LIMIT)
        val sent = vaultGuard { api().messageSend(connectionId, text) }
        val m = MessageInfo(
            connectionId,
            sent.messageId,
            true,
            text,
            ApprovalParser.instant(sent.sentAt) ?: now(),
            delivered = false,
            read = false,
        )
        upsert(m)
        return m
    }

    override suspend fun markRead(connectionId: String) {
        feedRead(setOf(com.vettid.core.data.feed.FeedAsks.MESSAGE_RECEIVED), null, connectionId)
        val unread = messagesFlow.value[connectionId].orEmpty().filter { !it.outgoing && !it.read }
        if (unread.isEmpty()) return
        vaultGuard {
            val a = api()
            unread.forEach { m ->
                a.messageRead(connectionId, m.messageId)
                patch(connectionId, m.messageId) { it.copy(read = true) }
            }
        }
    }

    override suspend fun delete(connectionId: String, messageId: String) {
        vaultGuard { api().messageDelete(connectionId, messageId) }
        messagesFlow.update { map -> map + (connectionId to map[connectionId].orEmpty().filterNot { it.messageId == messageId }) }
    }

    // --- ApprovalsRepository ---

    override suspend fun refreshApprovals() {
        val t = now()
        val a = vaultGuard { api() }
        refreshRequestsQuietly()
        refreshAllLists(t, pendingRead = { readPending(a) }, lists = { readLists(a, t) })
    }

    /**
     * The other lists are read and taken in even when `pending.list` failed; its failure is thrown after them, so the
     * screen is told (no silent failure; a held vault shows as held).
     */
    internal suspend fun refreshAllLists(t: Instant, pendingRead: suspend () -> Unit, lists: suspend () -> ListRead) {
        val pendingFailure = try {
            pendingRead()
            null
        } catch (e: VaultFailure) {
            e
        }
        refreshApprovalsFrom(t, lists)
        pendingFailure?.let { throw it }
    }

    /**
     * Reads every page of `pending.list` (§10.4.2) and replaces the stored asks of its kinds with it, by ask id (an ask
     * not in it is gone: decided elsewhere, expired or withdrawn); events newer than the first page's `ts` stay. A
     * failed read changes nothing and is thrown (and noted in [pendingReadFailure]); `unsupported_type` (an older
     * release) is not a failure: the live events stay as they are until the next unlock. Reads may overlap; an older
     * list never replaces a newer one. A list is merged only whole: when the cursors do not reach the end (a stale
     * cursor starts again from the beginning, §10.4.2) the read starts over once with a fresh first page and `ts`.
     */
    internal suspend fun refreshPendingFrom(page: suspend (after: String?) -> PendingPage) {
        if (pending.isUnsupported) return
        val ticket = pending.begin()
        try {
            val list = readWhole(page) ?: readWhole(page)
                ?: throw VaultFailure(FailureKind.OTHER, "list_incomplete")
            if (takeInPending(ticket, list.ts, list.asks) && pending.current(ticket)) pendingFailureFlow.value = null
        } catch (e: VaultFailure) {
            // A read that is no longer current (a lock, or a later-started read merged) does not touch the notice.
            if (e.code == "unsupported_type") {
                pending.markUnsupported(ticket)
                if (pending.current(ticket)) pendingFailureFlow.value = null
            } else {
                if (pending.current(ticket)) pendingFailureFlow.value = e
                throw e
            }
        }
    }

    /** Every page from the first, with the first page's `ts`; null when the cursors do not reach the end. */
    private suspend fun readWhole(page: suspend (after: String?) -> PendingPage): PendingPage? {
        val first = page(null)
        val asks = first.asks.toMutableList()
        val seen = mutableSetOf<String>()
        var next = first.next
        while (next != null) {
            if (!seen.add(next) || seen.size > MAX_PENDING_PAGES) return null
            val p = page(next)
            asks += p.asks
            next = p.next
        }
        return PendingPage(asks, null, first.ts)
    }

    /** Reads `pending.list` with [a]; a failure is noted and thrown. */
    private suspend fun readPending(a: VaultApi) =
        refreshPendingFrom { after -> vaultGuard(resend = true) { a.pendingList(after, PENDING_PAGE) } }

    /** After a quiet read failed for want of a connection, once more a few seconds later (§10.4.2 is read after an unlock). */
    private suspend fun retryPendingOnce() {
        val kind = pendingFailureFlow.value?.kind
        if (kind != FailureKind.NETWORK && kind != FailureKind.NO_RESPONSE) return
        delay(PENDING_RETRY_MS)
        runCatching { readPending(vaultGuard { api() }) }
    }

    private suspend fun takeInPending(ticket: Long, ts: Instant, asks: List<JsonObject>): Boolean {
        val listed = asks.mapNotNull { a ->
            val type = PendingAsks.TYPES[VaultJson.str(a, "kind")] ?: return@mapNotNull null // action, location, unknown: skipped
            val body = a["body"] as? JsonObject ?: return@mapNotNull null
            val p = ApprovalParser.parse(type, body, now()) ?: return@mapNotNull null
            PendingAsks.Entry(type, body, p.key, (p as? Approval.DeviceRequest)?.deviceId)
        }.distinctBy { it.key }
        var merged = false
        edit { l ->
            if (closed) return@edit l // a read that began after the lock merges nothing (see [closed])
            val plan = pending.merge(ticket, ts, listed) ?: return@edit l
            merged = true
            listReadFlow.value = true
            val at = now().toEpochMilli()
            val (gone, kept) = l.events.partition { e ->
                e.type in PendingAsks.LISTED_TYPES && keyOf(e).let { k -> k == null || !plan.keepEvent(k) }
            }
            val stillThere = kept.mapNotNull { keyOf(it) }.toSet()
            val firstSeen = gone.mapNotNull { e -> keyOf(e)?.let { it to e.at } }.toMap()
            l.copy(
                events = kept + plan.entries.filter { it.key !in stillThere }
                    .map { StoredEvent(it.type, enc(it.body), firstSeen[it.key] ?: at) },
            )
        }
        return merged
    }

    /** What one read of the vault's lists brought; a flag is false when that list could not be read. */
    internal class ListRead(val listed: List<Approval>, val grantsOk: Boolean, val criticalOk: Boolean, val sharesOk: Boolean)

    private suspend fun readLists(a: VaultApi, t: Instant): ListRead {
        val listed = mutableListOf<Approval>()
        var grantsOk = false
        var criticalOk = false
        try {
            vaultGuard(resend = true) { a.grantList() }["pending"].let { it as? JsonArray }.orEmpty()
                .mapNotNullTo(listed) { (it as? JsonObject)?.let { o -> ApprovalParser.grant(o, t) } }
            grantsOk = true
        } catch (_: VaultFailure) {
            // an older release without grants: nothing listed
        }
        try {
            vaultGuard(resend = true) { a.criticalUseList() }["incoming"].let { it as? JsonArray }.orEmpty()
                .mapNotNullTo(listed) { (it as? JsonObject)?.let { o -> ApprovalParser.critical(o, t) } }
            criticalOk = true
        } catch (_: VaultFailure) {
            // as above
        }
        val sharesOk = try {
            listed += pendingShares(a, t)
            true
        } catch (_: VaultFailure) {
            false
        }
        return ListRead(listed, grantsOk, criticalOk, sharesOk)
    }

    /**
     * Runs a read of the lists and takes it in. What happens while it is on its way (the vault answers it as of when it
     * looked): share items decided meanwhile stay out of it, and a `share.pending` batch that arrived meanwhile stays in,
     * in the order the app received them (VAULT-MESSAGING 0.55.7 §10.12 "Share questions that change while the app reads
     * the lists"). Several reads may overlap.
     */
    /** Test hook: runs between an approvals read and its guarded write. */
    @Volatile
    internal var approvalsHook: (() -> Unit)? = null

    internal suspend fun refreshApprovalsFrom(t: Instant, read: suspend () -> ListRead) {
        val epoch = lockEpoch.get()
        val hold = holdEpoch.get()
        val since = beginRead()
        try {
            val r = read()
            approvalsHook?.invoke()
            // [localLock] outermost, then [readLock]: [edit] (which holds localLock) reaches keptItems (readLock) below.
            val listed = synchronized(localLock) {
                synchronized(readLock) {
                    // A list the vault did not answer covers nothing: what was known of it stays (§10.12).
                    // A read across a lock or a hold writes nothing, and prunes nothing (below).
                    if (epoch != lockEpoch.get() || hold != holdEpoch.get()) return
                    val kept = listedFlow.value.filterNot { covered(it, r) }
                    (shareChangesSince(r.listed, since) + kept).also { listedFlow.value = it }
                }
            }
            // The lists are authoritative for what they cover: drop decided or expired requests kept from events.
            val keys = listed.map { it.key }.toSet()
            val listedItems = listed.filterIsInstance<Approval.ShareDecision>()
                .associate { d -> d.key to d.items.mapTo(mutableSetOf()) { it.itemId } }
            val subjects = r.listed.filterIsInstance<Approval.ShareDecision>().associate { it.ruleId to ShareQuestions.subjectKey(it) }
            edit { l ->
                l.copy(
                    events = l.events.mapNotNull { e ->
                        val p = parsed(e) ?: return@mapNotNull null
                        val exp = p.exp
                        when {
                            exp != null && !exp.isAfter(t) -> null
                            !covered(p, r) -> e
                            p is Approval.ShareDecision -> withItems(e, keptItems(p, listedItems[p.key], since, subjects))
                            p.key in keys -> e
                            else -> null
                        }
                    },
                )
            }
        } finally {
            endRead(since)
        }
        nowFlow.value = t
    }

    /** Whether [a] is of a list [r] read: the vault answered that list, so it says what of it is still asked. */
    private fun covered(a: Approval, r: ListRead): Boolean =
        (a is Approval.GrantRequest && r.grantsOk) || (a is Approval.CriticalUse && r.criticalOk) ||
            (a is Approval.ShareDecision && r.sharesOk)

    // --- share changes during a read (§10.12) ---

    private class ShareMark(val at: Int, val subject: String?, val ruleId: String, val ids: Set<String>)

    private class ShareBatch(val at: Int, val key: String, val items: List<ShareItem>)

    private val readLock = Any()
    private var changes = 0
    private val readSinces = mutableListOf<Int>()
    private val decidedLog = mutableListOf<ShareMark>()
    private val batchLog = mutableListOf<ShareBatch>()

    private fun beginRead(): Int = synchronized(readLock) { changes.also { readSinces += it } }

    /** What was decided or batched stays noted until no read that began before it is on its way. */
    private fun endRead(since: Int) = synchronized(readLock) {
        readSinces.remove(since)
        val floor = readSinces.minOrNull() ?: Int.MAX_VALUE
        decidedLog.removeAll { it.at <= floor }
        batchLog.removeAll { it.at <= floor }
    }

    private fun noteBatch(batch: Approval.ShareDecision) = synchronized(readLock) {
        changes++
        if (readSinces.isNotEmpty()) {
            batchLog += ShareBatch(changes, batch.key, batch.items)
            if (batchLog.size > MAX_LOG) batchLog.removeAt(0)
        }
    }

    /** A question the vault answered `not_found` for loses its noted batches: none of them comes back (§10.12 rule 3). */
    private fun forgetBatches(key: String) = synchronized(readLock) { batchLog.removeAll { it.key == key } }

    /**
     * The decisions in [marks] about the question of [rule] for [subject]: any rule of the same subject (§10.12 rule 1).
     * A rule whose subject was not known when decided gets it from [subjects] (the read's own entries).
     */
    private fun marksFor(marks: List<ShareMark>, rule: String, subject: String, subjects: Map<String, String>) =
        marks.filter { m -> m.ruleId == rule || (m.subject ?: subjects[m.ruleId]) == subject }

    /** The items of [batches], in order, less those a decision of [marks] received after their batch dropped (rule 1). */
    private fun batchItems(batches: List<ShareBatch>, marks: List<ShareMark>): List<ShareItem> = batches.flatMap { b ->
        val later = marks.filter { it.at > b.at }.flatMapTo(mutableSetOf()) { it.ids }
        b.items.filter { it.itemId !in later }
    }

    /**
     * [listed] as a read that began at change [since] brought it, with what changed during the read (§10.12): a listed
     * share question has the read's items less those decided during the read, plus the items of the batches that arrived
     * after the read began, less those a decision received after their batch dropped. Nothing older is added.
     */
    private fun shareChangesSince(listed: List<Approval>, since: Int): List<Approval> {
        val (marks, batches) = synchronized(readLock) {
            decidedLog.filter { it.at > since } to batchLog.filter { it.at > since }
        }
        if (marks.isEmpty() && batches.isEmpty()) return listed
        val subjects = listed.filterIsInstance<Approval.ShareDecision>().associate { it.ruleId to ShareQuestions.subjectKey(it) }
        return listed.mapNotNull { a ->
            if (a !is Approval.ShareDecision) return@mapNotNull a
            val mine = marksFor(marks, a.ruleId, ShareQuestions.subjectKey(a), subjects)
            val gone = mine.flatMapTo(mutableSetOf()) { it.ids }
            var items = a.items.filter { it.itemId !in gone }
            val have = items.mapTo(mutableSetOf()) { it.itemId }
            items = items + batchItems(batches.filter { it.key == a.key }, mine).filter { have.add(it.itemId) }
            if (items.isEmpty()) null else a.copy(items = items)
        }
    }

    /**
     * The item ids the kept event of share question [d] may still hold after a read that began at change [since]
     * (§10.12): for a question the read lists, its [listed] items; for one it covers but does not list, none (rule 2);
     * either way plus the items of the batches noted after the read began, less those a decision received after their
     * batch dropped (rule 1). Taken when the event is pruned, so a batch or decision noted after the read took its
     * result in counts too; the event itself already lost what was decided after it ([applyShareDecided]). [subjects]: the
     * read's rules and their subjects, for a decision whose subject was not known when it was made.
     */
    private fun keptItems(d: Approval.ShareDecision, listed: Set<String>?, since: Int, subjects: Map<String, String>): Set<String> {
        val (marks, batches) = synchronized(readLock) {
            decidedLog.filter { it.at > since } to batchLog.filter { it.at > since && it.key == d.key }
        }
        val mine = marksFor(marks, d.ruleId, ShareQuestions.subjectKey(d), subjects)
        return listed.orEmpty() + batchItems(batches, mine).map { it.itemId }
    }

    /** [e] with only the items [ids] names, or null when it names none of them. */
    private fun withItems(e: StoredEvent, ids: Set<String>): StoredEvent? {
        val o = parseBody(e.body) ?: return null
        val left = (o["items"] as? JsonArray).orEmpty().filter {
            ((it as? JsonObject)?.get("item_id") as? JsonPrimitive)?.content in ids
        }
        val all = (o["items"] as? JsonArray).orEmpty()
        return when {
            left.isEmpty() -> null
            left.size == all.size -> e
            else -> e.copy(body = enc(JsonObject(o + ("items" to JsonArray(left)))))
        }
    }

    /** The share decisions waiting (§10.12, [SharePending.decisions]). */
    private suspend fun pendingShares(a: VaultApi, t: Instant): List<Approval.ShareDecision> = SharePending.decisions(
        pendingPage = { after -> vaultGuard(resend = true) { a.sharePendingList(after = after, limit = SharePending.PAGE) } },
        rulePage = { after -> vaultGuard(resend = true) { a.shareRuleList(after = after) } },
        names = itemNames(),
        t = t,
    )

    private fun merge(stored: List<StoredEvent>, listed: List<Approval>, cs: List<ConnectionInfo>, now: Instant): List<Approval> {
        val names = cs.filter { it.displayName.isNotBlank() }.associate { it.id to it.displayName }
        val services = cs.filter { it.service }.map { it.id }.toSet()
        val fromEvents = stored.mapNotNull(::parsed)
        // Events carry what lists leave out (a critical request's payload): they win for the same key. A share
        // decision's event names its items; the rule's list says which are still pending, with the rule's tags.
        val listedShares = listed.filterIsInstance<Approval.ShareDecision>().associateBy { it.key }
        val events = fromEvents.map { e ->
            val l = listedShares[e.key]
            if (e is Approval.ShareDecision && l != null) {
                val named = e.items.associateBy { it.itemId }
                val items = l.items.map { li ->
                    val ev = named[li.itemId] ?: return@map li
                    li.copy(
                        name = li.name.ifBlank { ev.name },
                        askRuleId = li.askRuleId ?: ev.askRuleId,
                        shared = li.shared || ev.shared,
                    )
                }
                e.copy(items = items, tags = l.tags, rules = l.rules)
            } else {
                e
            }
        }
        val all = (events + listed.filter { l -> events.none { it.key == l.key } })
            .filter { a -> a.exp?.isAfter(now) ?: true }
            .distinctBy { it.key }
        // One question per item and subject (0.23.0 §10.12).
        return ShareQuestions.dedupe(all.map { a -> withName(a, names, services) }).sortedByDescending { it.receivedAt }
    }

    private fun withName(a: Approval, names: Map<String, String>, services: Set<String>): Approval = when (a) {
        is Approval.ConnectionRequest -> a
        is Approval.OutgoingRequest -> a
        is Approval.Authentication -> a.copy(connectionName = names[a.connectionId], connectionService = a.connectionId in services)
        is Approval.GrantRequest -> a.copy(connectionName = names[a.connectionId], connectionService = a.connectionId in services)
        is Approval.CriticalUse -> a.copy(connectionName = names[a.connectionId], connectionService = a.connectionId in services)
        is Approval.ShareDecision -> a.copy(
            connectionName = a.subjectConnectionId?.let { names[it] },
            connectionService = a.subjectConnectionId in services,
        )
        is Approval.DeviceRequest -> a
    }

    private fun find(key: String): Approval? = approvals.value.firstOrNull { it.key == key }
        ?: storedFlow.value.mapNotNull(::parsed).firstOrNull { it.key == key }

    private fun noteNotFound(key: String) = synchronized(notFoundKeys) {
        notFoundKeys += key
        while (notFoundKeys.size > MAX_NOT_FOUND) notFoundKeys.remove(notFoundKeys.first())
    }

    /** Runs a decision; a request the vault no longer knows (`not_found`) is dropped too. */
    private suspend fun decide(key: String, block: suspend (VaultApi) -> Unit) = decideRun(key) { vaultGuard { block(api()) } }

    /**
     * [whole]: a decision answers the whole request, which goes and its feed item is read. A share decision
     * ([decideShareWith]) is not whole: only the items the vault's answer names leave, so an item a batch added meanwhile
     * stays (§10.12 rule 1), and the feed item is read only once the question is gone.
     */
    private suspend fun decideRun(key: String, whole: Boolean = true, run: suspend () -> Unit) {
        try {
            run()
        } catch (e: VaultFailure) {
            if (e.kind == FailureKind.NOT_FOUND) {
                // Only here: a decision the vault took keeps the question's batches (§10.12 rule 3).
                if (key.startsWith("auth:")) noteNotFound(key)
                forgetBatches(key)
                closeFeedKey(key)
                dropApproval(key)
                listedFlow.update { l -> l.filterNot { it.key == key } }
            }
            throw e
        }
        if (whole) {
            closeFeedKey(key)
            dropApproval(key)
            listedFlow.update { l -> l.filterNot { it.key == key } }
            decided(key)
        }
    }

    /** Deciding an ask marks its feed item read (ANDROID-PLAN 0.1.23, §9 question 2). */
    private fun decided(key: String) {
        com.vettid.core.data.feed.FeedAsks.of(key)?.let { (kinds, ref) -> feedRead(kinds, ref, null) }
    }

    /** Runs a decision on a connection request; `not_found` means it already ended. */
    private suspend fun decideRequest(id: String, block: suspend (VaultApi) -> Unit) {
        try {
            vaultGuard { block(api()) }
        } catch (e: VaultFailure) {
            if (e.kind == FailureKind.NOT_FOUND) endRequest(id, null)
            throw e
        }
        decided("connection:$id")
    }

    private fun markApproved(id: String) = patchRequest(id) { a ->
        when (a) {
            is Approval.ConnectionRequest -> a.copy(state = RequestState.APPROVED)
            is Approval.OutgoingRequest -> a.copy(state = RequestState.APPROVED)
            else -> a
        }
    }

    // The connection becomes active with both approvals (0.10.3): the request stays, approved, until `added`.
    override suspend fun approveConnection(pendingId: String) {
        decideRequest(pendingId) { it.connectionApprove(pendingId) }
        markApproved(pendingId)
    }

    override suspend fun declineConnection(pendingId: String) {
        decideRequest(pendingId) { it.connectionDecline(pendingId) }
        endRequest(pendingId, RequestEnd.DECLINED)
    }

    override suspend fun approveOutgoing(connectionId: String) {
        decideRequest(connectionId) { it.outgoingApprove(connectionId) }
        markApproved(connectionId)
    }

    override suspend fun declineOutgoing(connectionId: String) {
        decideRequest(connectionId) { it.outgoingDecline(connectionId) }
        endRequest(connectionId, RequestEnd.DECLINED)
    }

    override suspend fun approveAuthentication(requestId: String, password: String) {
        credential.openUnlockWindow(password)
        decide("auth:$requestId") { it.authenticateApprove(requestId) }
    }

    override suspend fun denyAuthentication(requestId: String) = decide("auth:$requestId") { it.authenticateDeny(requestId) }

    override suspend fun decideGrant(requestId: String, approve: Boolean) {
        val req = find("grant:$requestId") as? Approval.GrantRequest
        if (approve && req?.grantable.isNullOrEmpty()) throw VaultFailure(FailureKind.NOT_SUPPORTED)
        decide("grant:$requestId") { it.grantDecide(requestId, approve, items = if (approve) req?.grantable else null) }
    }

    override suspend fun decideGrant(requestId: String, decision: GrantDecision) {
        if (decision.items.isEmpty()) throw VaultFailure(FailureKind.OTHER, "bad_request")
        val answers = kotlinx.serialization.json.buildJsonArray {
            decision.answers.filterKeys { it in decision.items }.forEach { (i, id) ->
                add(kotlinx.serialization.json.buildJsonObject {
                    put("index", JsonPrimitive(i))
                    put("item_id", JsonPrimitive(id))
                })
            }
        }
        decide("grant:$requestId") {
            it.grantDecide(
                requestId,
                true,
                items = decision.items,
                answers = answers.takeIf { a -> a.isNotEmpty() },
                uses = decision.uses,
                expiresIn = decision.expiresInSeconds?.toInt(),
            )
        }
    }

    override suspend fun approveCriticalUse(requestId: String, password: String): String? {
        val req = find("critical:$requestId") as? Approval.CriticalUse ?: throw VaultFailure(FailureKind.NOT_FOUND)
        // §10.13: the approval is offered only for a payload the member saw and that matches payload_sha256;
        // the hash sealed with the password is the one computed from that payload.
        if (!req.payloadVerified) throw VaultFailure(FailureKind.OTHER, "payload_mismatch")
        val sha = try {
            com.vettid.core.crypto.Bytes.sha256(com.vettid.core.crypto.Base64s.decodeStd(req.payload))
        } catch (e: com.vettid.core.crypto.CryptoException) {
            throw VaultFailure(FailureKind.OTHER, "payload", cause = e)
        }
        var status: String? = null
        decide("critical:$requestId") { status = it.criticalUseApprove(password, requestId, sha) }
        return status
    }

    override suspend fun loadCriticalUse(requestId: String) {
        val key = "critical:$requestId"
        val body = try {
            vaultGuard { api().criticalUseGet(requestId) }
        } catch (e: VaultFailure) {
            if (e.kind == FailureKind.NOT_FOUND) {
                dropApproval(key)
                listedFlow.update { l -> l.filterNot { it.key == key } }
            }
            throw e
        }
        // Kept as the event would have been; the screen shows the payload only if it matches the listed hash.
        store("critical-secret-use.pending", body)
    }

    override suspend fun denyCriticalUse(requestId: String) = decide("critical:$requestId") { it.criticalUseDeny(requestId) }

    override suspend fun decideShare(ruleId: String, approve: Boolean) {
        val req = find("share:$ruleId") as? Approval.ShareDecision ?: throw VaultFailure(FailureKind.NOT_FOUND)
        val ids = req.items.map { i -> i.itemId }
        decideShare(ruleId, include = if (approve) ids else emptyList(), decline = if (approve) emptyList() else ids)
    }

    /**
     * Includes [include] and declines [decline] in one change (§10.12 `share.decide{include, decline}`, 0.21.0: one
     * flush, nothing changed on an error); declined items are not asked again. A vault before 0.21.0 refuses that
     * form (`bad_request`, nothing changed): the two lists then go as two `{items, approve}` calls.
     */
    override suspend fun decideShare(ruleId: String, include: List<String>, decline: List<String>) = decideShareWith(
        ruleId,
        include,
        decline,
        both = { vaultGuard { api().shareDecide(ruleId, include, decline) } },
        one = { items, approve -> vaultGuard { api().shareDecide(ruleId, items, approve) } },
    )

    internal suspend fun decideShareWith(
        ruleId: String,
        include: List<String>,
        decline: List<String>,
        both: suspend () -> JsonObject,
        one: suspend (items: List<String>, approve: Boolean) -> JsonObject,
    ) {
        if (include.isEmpty() && decline.isEmpty()) throw VaultFailure(FailureKind.OTHER, "bad_request")
        // The subject, before the decision takes the rule's question out of the list and the events.
        val subject = subjectOfRule(ruleId)
        val answers = mutableListOf<JsonObject>()
        decideRun("share:$ruleId", whole = false) {
            SharePending.decide(
                include,
                decline,
                both = { answers.add(both()) },
                one = { items, approve -> answers.add(one(items, approve)) },
            )
        }
        // The vault's answer says what the decision changed (it also reaches the subject's other rules); only an answer
        // without either list falls back to what was sent.
        val told = answers.filter { "included" in it || "declined" in it }
        val done = if (told.isEmpty()) {
            (include + decline).toSet()
        } else {
            told.flatMap { listOf("included", "declined").flatMap { k -> ids(it, k) } }.toSet()
        }
        val read = applyShareDecided(ruleId, done, subject)
        // Sent but not named by the answer: the vault did not hold them pending. They leave this rule's question here
        // only; that is not a decision, so nothing is noted for a read on its way.
        val ignored = (include + decline).toSet() - done
        if (ignored.isNotEmpty()) removeShareItems(ignored, { it.ruleId == ruleId })
        // The feed item is read only when nothing of the question is left (a batch may have added an item meanwhile).
        val key = "share:$ruleId"
        if (key !in read && listedFlow.value.none { it.key == key } && storedFlow.value.none { keyOf(it) == key }) decided(key)
    }

    override suspend fun declineDeviceRequest(key: String) {
        val req = find(key) as? Approval.DeviceRequest ?: throw VaultFailure(FailureKind.NOT_FOUND)
        // A pairing is rejected with rejectPairingRequest, never with a session deny.
        if (req.isPairing) throw VaultFailure(FailureKind.NOT_SUPPORTED)
        deviceDecide(key) { ops ->
            if (req.isHeld) ops.approvalDecide(req.id, false) else ops.sessionDeny(req.id)
        }
    }

    /**
     * A decision on a device request: only `not_found` (VAULT-MESSAGING 0.83.0 §10.3: an ended request) means it is no longer
     * waiting, and [decideRun] takes it out of the list; any other failure keeps the entry and is thrown.
     */
    private suspend fun deviceDecide(key: String, block: suspend (DeviceDecisionOps) -> Unit) {
        decideRun(key) { vaultGuard { block(deviceOps()) } }
    }

    /**
     * Rejects a pairing this phone can no longer check (owner decision 2026-11-03): `device.pair.reject` with the entry's
     * `ref` (the request's `pending_id`, VAULT-MESSAGING 0.83.0) in place of `pairing_id`. A key that matches no entry (so no ref)
     * sends nothing;
     * only `not_found` drops the entry as already gone, any other failure keeps it. Nothing is ever approved here.
     */
    override suspend fun rejectPairingRequest(key: String) {
        val req = find(key) as? Approval.DeviceRequest ?: throw VaultFailure(FailureKind.NOT_FOUND)
        if (!req.isPairing) throw VaultFailure(FailureKind.NOT_SUPPORTED)
        deviceDecide(key) { ops -> ops.pairReject(req.id) }
    }

    override suspend fun approveDeviceRequest(key: String, seconds: Int?): DeviceDecision {
        val req = find(key) as? Approval.DeviceRequest ?: throw VaultFailure(FailureKind.NOT_FOUND)
        // Never approve what the app cannot show in full: a held request without its body, or one it cannot read.
        val unshown = req.isHeld && (req.fromFeed || req.held?.approvable != true || !HeldRequests.nameable(req.held, itemCache()))
        // A session request known only from its feed item is allowed only for a device the list names.
        val unknownDevice = req.isSession && req.fromFeed && req.deviceId?.let(deviceListed) != true
        if (req.isPairing || unshown || unknownDevice) {
            throw VaultFailure(FailureKind.NOT_SUPPORTED)
        }
        var decision = DeviceDecision()
        deviceDecide(key) { ops ->
            decision = if (req.isSession) {
                DeviceDecision(expiresAt = ApprovalParser.instant(ops.sessionApprove(req.id, seconds)))
            } else {
                DeviceDecision(result = ops.approvalDecide(req.id, true))
            }
        }
        return decision
    }

    override suspend fun dropDeviceRequests(deviceId: String) {
        closeFeedDevice(deviceId, now())
        val keys = feedFlow.value.filter { it.deviceId == deviceId }.mapNotNull { i -> i.ref?.let { "device:${i.kind}:$it" } }
        feedClosedFlow.update { c -> c.copy(keys = c.keys + keys) }
        edit { l -> l.copy(events = l.events.filterNot { (parsed(it) as? Approval.DeviceRequest)?.deviceId == deviceId }) }
        listedFlow.update { l -> l.filterNot { it is Approval.DeviceRequest && it.deviceId == deviceId } }
    }

    private fun summaries(
        cs: List<ConnectionInfo>,
        ms: Map<String, List<MessageInfo>>,
        loaded: Set<String>,
    ): List<ConversationSummary> =
        cs.filter { it.state == ConnectionState.ACTIVE || ms[it.id].orEmpty().isNotEmpty() }
            .map { c ->
                val list = ms[c.id].orEmpty()
                ConversationSummary(c, list.lastOrNull(), list.count { !it.outgoing && !it.read }, loaded = c.id in loaded)
            }
            .sortedByDescending { it.last?.sentAt ?: it.connection.createdAt ?: Instant.EPOCH }

    companion object {
        /** Failures every thread would meet: a reload stops sending `message.list` after one of these. */
        private val ALL_THREADS = setOf(
            FailureKind.OWNER_CHECK_REQUIRED,
            FailureKind.NETWORK,
            FailureKind.UNAUTHORIZED,
            FailureKind.SERVICE_PAUSED,
        )

        /** Messages asked for per `message.list` page; the vault also stops at 131,072 bytes (§10.5.2). */
        private const val PAGE = 100

        /** `pending.list` entries asked for per page (§10.4.2: 1-100); the read stops at this many pages. */
        private const val PENDING_PAGE = 100
        private const val MAX_PENDING_PAGES = 1_000
        private const val PENDING_RETRY_MS = 5_000L

        /** At most this many share decisions and batches are remembered for reads on their way. */
        private const val MAX_LOG = 64
        private const val MAX_NOT_FOUND = 100
        private const val MAX_WITHDRAWN = 256
        private const val CODE_TOO_LARGE = "response_too_large"
        private val AUTH_WAIT: Duration = Duration.ofMinutes(10)

        /** Longer than any request lives (§6.4: an approved incoming one, 16 days). */
        private val NAME_RETENTION: Duration = Duration.ofDays(17)
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** The vault calls behind a decision on a device request (VAULT-MESSAGING §10.3): `device.session.approve`, `.deny`, `approval.decide`. */
interface DeviceDecisionOps {
    /** `device.session.approve{request_id, seconds}`: the answer's `expires_at` (RFC 3339), or null if it has none. */
    suspend fun sessionApprove(requestId: String, seconds: Int?): String?

    suspend fun sessionDeny(requestId: String)

    /** `device.pair.reject{pairing_id}` with a pairing's `pending_id` (VAULT-MESSAGING 0.83.0). */
    suspend fun pairReject(pendingId: String)

    /** `approval.decide{approval_id, approve}`: the answer's `result`. */
    suspend fun approvalDecide(approvalId: String, approve: Boolean): String?
}

internal class VaultDeviceDecisions(private val api: VaultApi) : DeviceDecisionOps {
    override suspend fun sessionApprove(requestId: String, seconds: Int?): String? =
        VaultJson.str(api.sessionApprove(requestId, seconds), "expires_at")

    override suspend fun sessionDeny(requestId: String) = api.sessionDeny(requestId)

    override suspend fun pairReject(pendingId: String) = api.devicePairReject(pendingId)

    override suspend fun approvalDecide(approvalId: String, approve: Boolean): String? = api.approvalDecide(approvalId, approve)
}

/** What the request list keeps of `peer_kind` whichever answer or event arrives first (VAULT-MESSAGING 0.69.0 §6.4). */
internal object RequestKinds {
    /** The accept's [waiting] request joins [list]; one the outgoing event already put there is kept and learns the kind. */
    fun withAccepted(list: List<Approval>, waiting: Approval.OutgoingRequest): List<Approval> =
        if (list.any { it is Approval.OutgoingRequest && it.connectionId == waiting.connectionId }) {
            list.map {
                val same = it is Approval.OutgoingRequest && it.connectionId == waiting.connectionId
                if (same) (it as Approval.OutgoingRequest).copy(service = it.service || waiting.service) else it
            }
        } else {
            list + waiting
        }

    /** A request list read replaces [old] with [read]; a request known as a service stays one when its entry lacks the marker. */
    fun keepServices(old: List<Approval>, read: List<Approval>): List<Approval> {
        val known = old.filterIsInstance<Approval.OutgoingRequest>().filter { it.service }.map { it.connectionId }.toSet()
        return read.map { if (it is Approval.OutgoingRequest && it.connectionId in known) it.copy(service = true) else it }
    }
}

/** `connection.list` and `authenticate.list` as the vault answered them; a failed state read is kept for the caller to throw. */
private suspend fun readConnectionsFrom(a: VaultApi): SocialManager.ConnectionRead {
    val connections = a.connectionList().filter { it.kind == "connection" }.map(ApprovalParser::connection)
    return try {
        val states = (a.authenticateList()["states"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(ApprovalParser::authState) }
        SocialManager.ConnectionRead(connections, states)
    } catch (_: com.vettid.core.vault.VaultOpException) {
        SocialManager.ConnectionRead(connections, null)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        SocialManager.ConnectionRead(connections, null, e)
    }
}
