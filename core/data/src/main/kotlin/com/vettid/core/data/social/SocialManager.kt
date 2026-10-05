package com.vettid.core.data.social

import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.vaultGuard
import com.vettid.core.keystore.KeystoreException
import com.vettid.core.vault.DeviceStateStore
import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.CoroutineScope
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
 * (share decisions, member authentication, desktop and agent requests) and the
 * safety codes this app showed are kept in [store], a Keystore-encrypted file.
 *
 * [onEvent] gets every event of the vault ([com.vettid.core.data.vault.VaultManager]
 * forwards them); nothing here touches transport or crypto beyond the typed API.
 */
@Suppress("TooManyFunctions", "LargeClass")
class SocialManager(
    private val scope: CoroutineScope,
    private val api: suspend () -> VaultApi,
    private val credential: CredentialRepository,
    private val store: DeviceStateStore,
    private val clock: Clock = Clock.systemUTC(),
) : ConnectionsRepository, MessagesRepository, ApprovalsRepository {
    private val mutex = Mutex()
    private var local: Local = load()

    private val connectionsFlow = MutableStateFlow<List<ConnectionInfo>>(emptyList())
    private val authFlow = MutableStateFlow<Map<String, AuthenticationState>>(emptyMap())
    private val messagesFlow = MutableStateFlow<Map<String, List<MessageInfo>>>(emptyMap())
    private val storedFlow = MutableStateFlow(local.events)
    private val listedFlow = MutableStateFlow<List<Approval>>(emptyList())
    private val requestsFlow = MutableStateFlow<List<Approval>>(emptyList())
    private val endsFlow = MutableStateFlow<Map<String, RequestEnd>>(emptyMap())
    private val nowFlow = MutableStateFlow(now())
    private var ttls: List<InviteTtl>? = null

    override val connections: StateFlow<List<ConnectionInfo>> = connectionsFlow.asStateFlow()
    override val authentication: StateFlow<Map<String, AuthenticationState>> = authFlow.asStateFlow()

    override val conversations: StateFlow<List<ConversationSummary>> =
        combine(connectionsFlow, messagesFlow) { cs, ms -> summaries(cs, ms) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val approvals: StateFlow<List<Approval>> =
        combine(storedFlow, listedFlow, requestsFlow, connectionsFlow, nowFlow) { stored, listed, requests, cs, now ->
            merge(stored, listed + requests, cs, now)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val requestEnds: StateFlow<Map<String, RequestEnd>> = endsFlow.asStateFlow()

    private fun now(): Instant = Instant.now(clock)

    // --- local state (encrypted file) ---

    @Serializable
    private data class StoredEvent(val type: String, val body: String, val at: Long)

    @Serializable
    private data class StoredSas(val sas: String, val at: Long)

    /**
     * [safety]: the safety code of each connection, as this app showed it, by connection id.
     * [requestSas]: the codes of open requests by request id (`pending_id` or the outgoing
     * `connection_id`), until `connection.event{added}` names the request it came from (0.10.2).
     */
    @Serializable
    private data class Local(
        val events: List<StoredEvent> = emptyList(),
        val safety: Map<String, StoredSas> = emptyMap(),
        val requestSas: Map<String, StoredSas> = emptyMap(),
    )

    private fun load(): Local = try {
        // Connection requests come from the vault's list since 0.10.2; drop ones stored from events by A4.
        store.load()?.let { json.decodeFromString(Local.serializer(), String(it, Charsets.UTF_8)) }
            ?.let { l -> l.copy(events = l.events.filterNot { it.type == "connection.request.pending" }) } ?: Local()
    } catch (_: KeystoreException) {
        Local()
    } catch (_: SerializationException) {
        Local()
    } catch (_: IllegalArgumentException) {
        Local()
    }

    private suspend fun edit(f: (Local) -> Local) = mutex.withLock {
        val next = f(local)
        if (next == local) return@withLock
        local = next
        storedFlow.value = next.events
        try {
            store.save(json.encodeToString(Local.serializer(), next).toByteArray(Charsets.UTF_8))
        } catch (_: KeystoreException) {
            // kept in memory; the next save retries
        }
    }

    /** Forgets everything (sign-out with a new vault, or a deleted vault). */
    fun clear() {
        local = Local()
        storedFlow.value = emptyList()
        store.clear()
        connectionsFlow.value = emptyList()
        authFlow.value = emptyMap()
        messagesFlow.value = emptyMap()
        listedFlow.value = emptyList()
        requestsFlow.value = emptyList()
        endsFlow.value = emptyMap()
        ttls = null
    }

    /** Re-reads connections, conversations and approvals; failures are left for the screens to retry. */
    fun refreshAllQuietly() {
        scope.launch {
            runCatching { refresh() }
            runCatching { refreshConversations() }
            runCatching { refreshApprovals() }
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
            in ApprovalParser.TYPES -> store(m.type, b)
            "connection.request.pending" -> ApprovalParser.incoming(b, now())?.let { onRequest(it, it.pendingId, it.sas) }
            "connection.request.outgoing" -> ApprovalParser.outgoing(b, now())?.let { onRequest(it, it.connectionId, it.sas) }
            "connection.event" -> onConnectionEvent(b)
            "connection.authenticate.result" -> {
                val conn = VaultJson.str(b, "connection_id") ?: return
                ApprovalParser.authResult(b, now(), authFlow.value[conn])?.let { s -> authFlow.update { it + (conn to s) } }
            }
            "sync.event" -> onSync(VaultJson.str(b, "kind") ?: "", b)
        }
        nowFlow.value = now()
    }

    private suspend fun store(type: String, body: JsonObject) {
        val parsed = ApprovalParser.parse(type, body, now()) ?: return
        val at = now().toEpochMilli()
        val text = VaultJson.json.encodeToString(JsonObject.serializer(), body)
        edit { l ->
            val others = l.events.filterNot { e -> keyOf(e) == parsed.key }
            val merged = if (parsed is Approval.ShareDecision) mergeShare(l.events, parsed, body) else text
            l.copy(events = others + StoredEvent(type, merged, at))
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
    private suspend fun onRequest(a: Approval, id: String, sas: String?) {
        requestsFlow.update { l ->
            val old = l.firstOrNull { requestId(it) == id }
            val next = when {
                // An event never takes back what a decision already moved on (approved stays approved).
                old is Approval.ConnectionRequest && a is Approval.ConnectionRequest ->
                    a.copy(state = maxOf(old.state, a.state), peerApproved = old.peerApproved || a.peerApproved)
                old is Approval.OutgoingRequest && a is Approval.OutgoingRequest ->
                    a.copy(state = maxOf(old.state, a.state), peerApproved = old.peerApproved || a.peerApproved, name = a.name ?: old.name)
                else -> a
            }
            l.filterNot { requestId(it) == id } + next
        }
        endsFlow.update { it - id }
        if (sas != null) rememberSas(id, sas)
    }

    private suspend fun rememberSas(id: String, sas: String) {
        if (local.requestSas[id]?.sas == sas) return
        edit { l -> l.copy(requestSas = l.requestSas + (id to StoredSas(sas, now().toEpochMilli()))) }
    }

    private fun patchRequest(id: String, f: (Approval) -> Approval) =
        requestsFlow.update { l -> l.map { if (requestId(it) == id) f(it) else it } }

    private suspend fun endRequest(id: String, end: RequestEnd?) {
        requestsFlow.update { l -> l.filterNot { requestId(it) == id } }
        if (end != null) endsFlow.update { it + (id to end) }
        edit { l -> l.copy(requestSas = l.requestSas - id) }
    }

    private suspend fun refreshRequestsQuietly() {
        try {
            refreshRequests()
        } catch (_: VaultFailure) {
            // a release before 0.10.2 has no request list; the screens retry
        }
    }

    override suspend fun refreshRequests() {
        val list = vaultGuard { api().requestList() }
        val t = now()
        fun entries(k: String) = (list[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val parsed = entries("incoming").mapNotNull { ApprovalParser.incoming(it, t) } +
            entries("outgoing").mapNotNull { ApprovalParser.outgoing(it, t) }
        requestsFlow.value = parsed
        val codes = parsed.mapNotNull { a ->
            when (a) {
                is Approval.ConnectionRequest -> a.pendingId to a.sas
                is Approval.OutgoingRequest -> a.sas?.let { a.connectionId to it }
                else -> null
            }
        }
        val missing = codes.filter { (id, sas) -> local.requestSas[id]?.sas != sas }
        if (missing.isNotEmpty()) {
            edit { l -> l.copy(requestSas = l.requestSas + missing.associate { (id, sas) -> id to StoredSas(sas, t.toEpochMilli()) }) }
        }
    }

    private suspend fun onConnectionEvent(b: JsonObject) {
        val conn = VaultJson.str(b, "connection_id") ?: return
        runCatching { refresh() }
        when (VaultJson.str(b, "event")) {
            "added" -> onAdded(conn, VaultJson.str(b, "pending_id") ?: conn)
            // An outgoing request ended without a connection other than by this member's decline (§6.4).
            "failed" -> endRequest(conn, RequestEnd.FAILED)
            "removed" -> {
                messagesFlow.update { it - conn }
                authFlow.update { it - conn }
                edit { l -> l.copy(safety = l.safety - conn) }
            }
        }
    }

    /** A new connection: the code of the request it came from becomes the connection's ([req], §10.4 `pending_id`). */
    private suspend fun onAdded(conn: String, req: String) {
        val t = now().toEpochMilli()
        val code = local.requestSas[req] ?: when (val a = requestsFlow.value.firstOrNull { requestId(it) == req }) {
            is Approval.ConnectionRequest -> StoredSas(a.sas, t)
            is Approval.OutgoingRequest -> a.sas?.let { StoredSas(it, t) }
            else -> null
        }
        requestsFlow.update { l -> l.filterNot { requestId(it) == req } }
        edit { l -> l.copy(safety = if (code != null) l.safety + (conn to code) else l.safety, requestSas = l.requestSas - req) }
        runCatching { load(conn) }
    }

    /** `sync.event{kind: "connection.request"}`: another device's decision, the peer's approval, or an expiry (§10.1). */
    private suspend fun onRequestSync(b: JsonObject) {
        val id = VaultJson.str(b, "pending_id") ?: VaultJson.str(b, "connection_id") ?: return
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
            "expired" -> endRequest(id, RequestEnd.EXPIRED)
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private suspend fun onSync(kind: String, b: JsonObject) {
        fun s(k: String) = VaultJson.str(b, k)
        when (kind) {
            "message.receipt" -> {
                val conn = s("connection_id") ?: return
                val id = s("message_id") ?: return
                val read = s("receipt") == "read"
                patch(conn, id) { it.copy(delivered = true, read = it.read || read) }
            }
            "message.read" -> patch(s("connection_id") ?: return, s("message_id") ?: return) { it.copy(read = true) }
            "connection.changed", "block.added", "block.removed" -> runCatching { refresh() }
            "connection.request" -> onRequestSync(b)
            "connection.authenticate.decided" -> s("request_id")?.let { dropApproval("auth:$it") }
            "grant.request.decided" -> s("request_id")?.let { id ->
                dropApproval("grant:$id")
                listedFlow.update { l -> l.filterNot { it.key == "grant:$id" } }
            }
            "critical-secret-use.decided" -> s("request_id")?.let { id ->
                dropApproval("critical:$id")
                listedFlow.update { l -> l.filterNot { it.key == "critical:$id" } }
            }
            "share.decided" -> s("rule_id")?.let { onShareDecided(it, b) }
            "approval.decided" -> s("approval_id")?.let { dropApproval("device:approval.pending:$it") }
        }
    }

    private suspend fun onShareDecided(rule: String, b: JsonObject) {
        val done = listOf("included", "declined").flatMap { k ->
            (b[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        }.toSet()
        edit { l ->
            l.copy(
                events = l.events.mapNotNull { e ->
                    if (keyOf(e) != "share:$rule") return@mapNotNull e
                    val o = parseBody(e.body) ?: return@mapNotNull null
                    val left = (o["items"] as? JsonArray).orEmpty().filter {
                        ((it as? JsonObject)?.get("item_id") as? JsonPrimitive)?.content !in done
                    }
                    if (left.isEmpty()) null else e.copy(body = enc(JsonObject(o + ("items" to JsonArray(left)))))
                },
            )
        }
    }

    // --- ConnectionsRepository ---

    override suspend fun refresh(): Unit = vaultGuard {
        val a = api()
        connectionsFlow.value = a.connectionList().filter { it.kind == "connection" }.map(ApprovalParser::connection)
        val states = try {
            (a.authenticateList()["states"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(ApprovalParser::authState) }
        } catch (_: com.vettid.core.vault.VaultOpException) {
            null
        }
        if (states != null) {
            authFlow.update { old ->
                states.associateBy { it.connectionId }.mapValues { (id, s) ->
                    val prev = old[id]
                    s.copy(waitingUntil = prev?.waitingUntil?.takeIf { it.isAfter(now()) }, keyChanged = prev?.keyChanged ?: false)
                } + old.filterKeys { k -> states.none { it.connectionId == k } }
            }
        }
    }

    override suspend fun connection(id: String): ConnectionInfo = vaultGuard {
        val c = ApprovalParser.connection(api().connectionGet(id))
        connectionsFlow.update { l -> if (l.any { it.id == id }) l.map { if (it.id == id) c else it } else l + c }
        c
    }

    override suspend fun inviteTtls(): List<InviteTtl> = ttls ?: vaultGuard {
        val l = api().relayLimits()
        val max = minOf(l.openTokenMaxLifetimeSeconds, l.claimTtlSeconds)
        InviteTtl.entries.filter { it.seconds <= max }.ifEmpty { listOf(InviteTtl.TEN_MINUTES) }
    }.also { ttls = it }

    override suspend fun createInvite(ttl: InviteTtl): InviteInfo = vaultGuard {
        val i = api().inviteCreate(ttl.seconds)
        InviteInfo(i.inviteId, i.link, InviteLinks.qrPayload(i.link), ApprovalParser.instant(i.exp) ?: InviteLinks.expiry(i.link), i.remote)
    }

    override suspend fun outstandingInvites(): List<OutstandingInvite> = vaultGuard {
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
        val r = vaultGuard {
            try {
                val a = api().inviteAccept(link)
                AcceptedConnection(a.connectionId, a.name?.takeIf { it.isNotBlank() }, exists = false, exp = ApprovalParser.instant(a.exp))
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
                introducedBy = null, receivedAt = now(), exp = r.exp,
            )
            // The outgoing event may already have arrived (it follows hs.resp): keep it then.
            requestsFlow.update { l -> if (l.any { requestId(it) == r.connectionId }) l else l + waiting }
            endsFlow.update { it - r.connectionId }
        }
        return r
    }

    private suspend fun updateMeta(id: String, change: suspend (VaultApi, Long) -> Unit) = vaultGuard {
        val a = api()
        val version = connectionsFlow.value.firstOrNull { it.id == id }?.version ?: a.connectionGet(id).version
        try {
            change(a, version)
        } catch (e: com.vettid.core.vault.VaultOpException) {
            if (e.code != "conflict") throw e
            change(a, a.connectionGet(id).version) // another device changed it first: retry once on the current version
        }
        val c = ApprovalParser.connection(a.connectionGet(id))
        connectionsFlow.update { l -> l.map { if (it.id == id) c else it } }
    }

    override suspend fun setFavorite(id: String, favorite: Boolean) =
        updateMeta(id) { a, v -> a.connectionUpdate(id, v, favorite = favorite) }

    override suspend fun updateNames(id: String, alias: String?, note: String?) {
        if (alias == null && note == null) return
        updateMeta(id) { a, v -> a.connectionUpdate(id, v, alias = alias, note = note) }
    }

    override suspend fun remove(id: String) {
        vaultGuard { api().connectionRemove(id) }
        forget(id)
    }

    override suspend fun block(id: String) {
        vaultGuard { api().blockAdd(connectionId = id) }
        forget(id)
    }

    private suspend fun forget(id: String) {
        connectionsFlow.update { l -> l.filterNot { it.id == id } }
        messagesFlow.update { it - id }
        authFlow.update { it - id }
        edit { l -> l.copy(safety = l.safety - id) }
    }

    override suspend fun requestAuthentication(id: String, context: String?): String = vaultGuard {
        val rid = api().authenticateRequest(id, context?.takeIf { it.isNotBlank() })
        authFlow.update { m ->
            val base = m[id] ?: AuthenticationState(id)
            m + (id to base.copy(lastResult = "requested", lastAt = now(), waitingUntil = now().plus(AUTH_WAIT)))
        }
        rid
    }

    override fun safetyCode(id: String): SafetyCodeRecord? = local.safety[id]?.let { SafetyCodeRecord(it.sas, Instant.ofEpochMilli(it.at)) }

    // --- MessagesRepository ---

    override fun messages(connectionId: String): Flow<List<MessageInfo>> =
        messagesFlow.map { it[connectionId] ?: emptyList() }.distinctUntilChanged()

    override suspend fun refreshConversations() {
        if (connectionsFlow.value.isEmpty()) refresh()
        connectionsFlow.value.filter { it.state == ConnectionState.ACTIVE }.forEach { load(it.id) }
    }

    override suspend fun load(connectionId: String) = vaultGuard {
        val list = api().messageList(connectionId, HISTORY).map(ApprovalParser::message)
        messagesFlow.update { m -> m + (connectionId to mergeMessages(list, m[connectionId].orEmpty())) }
    }

    /** The vault's list wins; messages that arrived meanwhile (newer than its last) are kept. */
    private fun mergeMessages(fromVault: List<MessageInfo>, current: List<MessageInfo>): List<MessageInfo> {
        val ids = fromVault.map { it.messageId }.toSet()
        val newest = fromVault.maxOfOrNull { it.sentAt } ?: Instant.EPOCH
        return (fromVault + current.filter { it.messageId !in ids && it.sentAt > newest }).sortedWith(ORDER)
    }

    private fun upsert(m: MessageInfo) = messagesFlow.update { map ->
        val list = map[m.connectionId].orEmpty()
        val next = if (list.any { it.messageId == m.messageId }) {
            list.map { if (it.messageId == m.messageId) m.copy(delivered = it.delivered || m.delivered, read = it.read || m.read) else it }
        } else {
            (list + m).sortedWith(ORDER)
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
        val listed = mutableListOf<Approval>()
        var grantsOk = false
        var criticalOk = false
        try {
            vaultGuard { a.grantList() }["pending"].let { it as? JsonArray }.orEmpty()
                .mapNotNullTo(listed) { (it as? JsonObject)?.let { o -> ApprovalParser.grant(o, t) } }
            grantsOk = true
        } catch (_: VaultFailure) {
            // an older release without grants: nothing listed
        }
        try {
            vaultGuard { a.criticalUseList() }["incoming"].let { it as? JsonArray }.orEmpty()
                .mapNotNullTo(listed) { (it as? JsonObject)?.let { o -> ApprovalParser.critical(o, t) } }
            criticalOk = true
        } catch (_: VaultFailure) {
            // as above
        }
        listedFlow.value = listed
        // The lists are authoritative for what they cover: drop decided or expired requests kept from events.
        val keys = listed.map { it.key }.toSet()
        edit { l ->
            l.copy(
                events = l.events.filter { e ->
                    val p = parsed(e) ?: return@filter false
                    val stale = (p is Approval.GrantRequest && grantsOk) || (p is Approval.CriticalUse && criticalOk)
                    val exp = p.exp
                    (!stale || p.key in keys) && (exp == null || exp.isAfter(t))
                },
            )
        }
        nowFlow.value = t
    }

    private fun merge(stored: List<StoredEvent>, listed: List<Approval>, cs: List<ConnectionInfo>, now: Instant): List<Approval> {
        val names = cs.associate { it.id to it.displayName }
        val fromEvents = stored.mapNotNull(::parsed)
        // Events carry what lists leave out (a critical request's payload): they win for the same key.
        val all = (fromEvents + listed.filter { l -> fromEvents.none { it.key == l.key } })
            .filter { a -> a.exp?.isAfter(now) ?: true }
            .distinctBy { it.key }
        return all.map { a -> withName(a, names) }.sortedByDescending { it.receivedAt }
    }

    private fun withName(a: Approval, names: Map<String, String>): Approval = when (a) {
        is Approval.ConnectionRequest -> a
        is Approval.OutgoingRequest -> a
        is Approval.Authentication -> a.copy(connectionName = names[a.connectionId])
        is Approval.GrantRequest -> a.copy(connectionName = names[a.connectionId])
        is Approval.CriticalUse -> a.copy(connectionName = names[a.connectionId])
        is Approval.ShareDecision -> a.copy(connectionName = a.subjectConnectionId?.let { names[it] })
        is Approval.DeviceRequest -> a
    }

    private fun find(key: String): Approval? = approvals.value.firstOrNull { it.key == key }
        ?: storedFlow.value.mapNotNull(::parsed).firstOrNull { it.key == key }

    /** Runs a decision; a request the vault no longer knows (`not_found`) is dropped too. */
    private suspend fun decide(key: String, block: suspend (VaultApi) -> Unit) {
        try {
            vaultGuard { block(api()) }
        } catch (e: VaultFailure) {
            if (e.kind == FailureKind.NOT_FOUND) {
                dropApproval(key)
                listedFlow.update { l -> l.filterNot { it.key == key } }
            }
            throw e
        }
        dropApproval(key)
        listedFlow.update { l -> l.filterNot { it.key == key } }
    }

    /** Runs a decision on a connection request; `not_found` means it already ended. */
    private suspend fun decideRequest(id: String, block: suspend (VaultApi) -> Unit) {
        try {
            vaultGuard { block(api()) }
        } catch (e: VaultFailure) {
            if (e.kind == FailureKind.NOT_FOUND) endRequest(id, null)
            throw e
        }
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

    override suspend fun blockConnectionRequest(pendingId: String) {
        decideRequest(pendingId) { it.blockAdd(pendingId = pendingId) }
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

    override suspend fun approveCriticalUse(requestId: String, password: String) {
        val req = find("critical:$requestId") as? Approval.CriticalUse ?: throw VaultFailure(FailureKind.NOT_FOUND)
        // §10.13: the approval is offered only for a payload the member saw and that matches payload_sha256;
        // the hash sealed with the password is the one computed from that payload.
        if (!req.payloadVerified) throw VaultFailure(FailureKind.OTHER, "payload_mismatch")
        val sha = try {
            com.vettid.core.crypto.Bytes.sha256(com.vettid.core.crypto.Base64s.decodeStd(req.payload))
        } catch (e: com.vettid.core.crypto.CryptoException) {
            throw VaultFailure(FailureKind.OTHER, "payload", cause = e)
        }
        decide("critical:$requestId") { it.criticalUseApprove(password, requestId, sha) }
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
        decide("share:$ruleId") { it.shareDecide(ruleId, req.items.map { i -> i.itemId }, approve) }
    }

    override suspend fun declineDeviceRequest(key: String) {
        val req = find(key) as? Approval.DeviceRequest ?: throw VaultFailure(FailureKind.NOT_FOUND)
        decide(key) {
            if (req.type == "approval.pending") it.approvalDecide(req.id, false) else it.sessionDeny(req.id)
        }
    }

    private fun summaries(cs: List<ConnectionInfo>, ms: Map<String, List<MessageInfo>>): List<ConversationSummary> =
        cs.filter { it.state == ConnectionState.ACTIVE || ms[it.id].orEmpty().isNotEmpty() }
            .map { c ->
                val list = ms[c.id].orEmpty()
                ConversationSummary(c, list.lastOrNull(), list.count { !it.outgoing && !it.read })
            }
            .sortedByDescending { it.last?.sentAt ?: it.connection.createdAt ?: Instant.EPOCH }

    companion object {
        private const val HISTORY = 200
        private val AUTH_WAIT: Duration = Duration.ofMinutes(10)
        private val ORDER = compareBy<MessageInfo>({ it.sentAt }, { it.messageId })
        private val json = Json { ignoreUnknownKeys = true }
    }
}
