package com.vettid.core.testing

import com.vettid.core.data.social.AcceptedConnection
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.ApprovalsRepository
import com.vettid.core.data.social.AuthenticationState
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.social.ConnectionsRepository
import com.vettid.core.data.social.ConversationSummary
import com.vettid.core.data.social.InviteInfo
import com.vettid.core.data.social.InviteLinks
import com.vettid.core.data.social.InviteTtl
import com.vettid.core.data.social.MessageInfo
import com.vettid.core.data.social.MessagesRepository
import com.vettid.core.data.social.OutstandingInvite
import com.vettid.core.data.social.PeerDecline
import com.vettid.core.data.social.RequestEnd
import com.vettid.core.data.social.RequestState
import com.vettid.core.data.social.SafetyCodeRecord
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant

/**
 * TEST ONLY. In-memory connections, messages and approvals for ViewModel
 * tests, like [FakeVault]: every call is recorded in [calls]; [fail] makes the
 * next call of a name throw.
 */
@Suppress("TooManyFunctions")
class FakeSocial : ConnectionsRepository, MessagesRepository, ApprovalsRepository {
    val calls = mutableListOf<String>()
    val fail = mutableMapOf<String, VaultFailure>()
    var lastPassword: String? = null
    var lastText: String? = null
    var acceptResult = AcceptedConnection("conn-new", "Inviter")
    var ttls = InviteTtl.entries.toList()
    var invite = InviteInfo("01JINVITE0000000000000000A", "link", "{}", Instant.parse("2026-10-04T12:10:00Z"), remote = false)
    var outstanding = listOf<OutstandingInvite>()
    val safety = mutableMapOf<String, SafetyCodeRecord>()
    private var seq = 0

    override val connections = MutableStateFlow<List<ConnectionInfo>>(emptyList())
    override val authentication = MutableStateFlow<Map<String, AuthenticationState>>(emptyMap())
    val messageMap = MutableStateFlow<Map<String, List<MessageInfo>>>(emptyMap())
    override val approvals = MutableStateFlow<List<Approval>>(emptyList())
    override val requestEnds = MutableStateFlow<Map<String, RequestEnd>>(emptyMap())
    override val peerDeclines = MutableStateFlow<List<PeerDecline>>(emptyList())
    override val conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())

    /** Sets the connections and messages and recomputes [conversations]. */
    fun seed(connections: List<ConnectionInfo>, messages: Map<String, List<MessageInfo>> = emptyMap()) {
        this.connections.value = connections
        messageMap.value = messages
        recompute()
    }

    private fun recompute() {
        conversations.value = summaries()
    }

    private fun summaries() = connections.value.filter { it.state == ConnectionState.ACTIVE }.map { c ->
        val l = messageMap.value[c.id].orEmpty()
        ConversationSummary(c, l.lastOrNull(), l.count { !it.outgoing && !it.read })
    }.sortedByDescending { it.last?.sentAt ?: Instant.EPOCH }

    private fun call(name: String) {
        calls += name
        fail.remove(name)?.let { throw it }
    }

    override suspend fun refresh() = call("refresh")

    override suspend fun connection(id: String): ConnectionInfo {
        call("connection")
        return connections.value.firstOrNull { it.id == id } ?: throw VaultFailure(FailureKind.NOT_FOUND)
    }

    override suspend fun inviteTtls(): List<InviteTtl> {
        call("inviteTtls")
        return ttls
    }

    override suspend fun createInvite(ttl: InviteTtl): InviteInfo {
        call("createInvite")
        return invite.copy(remote = ttl.remote)
    }

    override suspend fun outstandingInvites(): List<OutstandingInvite> {
        call("outstandingInvites")
        return outstanding
    }

    override suspend fun cancelInvite(inviteId: String) {
        call("cancelInvite")
        outstanding = outstanding.filterNot { it.inviteId == inviteId }
    }

    override suspend fun acceptInvite(text: String): AcceptedConnection {
        call("acceptInvite")
        lastText = text
        when (InviteLinks.parse(text)) {
            is InviteLinks.Parsed.Ok -> Unit
            InviteLinks.Parsed.Expired -> throw VaultFailure(FailureKind.INVITE_EXPIRED)
            InviteLinks.Parsed.NotAConnection -> throw VaultFailure(FailureKind.INVITE_NOT_CONNECTION)
            InviteLinks.Parsed.Invalid -> if (text != "test-link") throw VaultFailure(FailureKind.INVITE_INVALID)
        }
        return acceptResult
    }

    private fun edit(id: String, f: (ConnectionInfo) -> ConnectionInfo) =
        connections.update { l -> l.map { if (it.id == id) f(it).copy(version = it.version + 1) else it } }.also { recompute() }

    override suspend fun setFavorite(id: String, favorite: Boolean) {
        call("setFavorite")
        edit(id) { it.copy(favorite = favorite) }
    }

    override suspend fun updateNames(id: String, alias: String?, note: String?) {
        call("updateNames")
        edit(id) { c ->
            c.copy(alias = alias?.let { it.ifEmpty { null } } ?: c.alias, note = note?.let { it.ifEmpty { null } } ?: c.note)
        }
    }

    override suspend fun remove(id: String) {
        call("remove")
        connections.update { l -> l.filterNot { it.id == id } }
    }

    override suspend fun block(id: String) {
        call("block")
        connections.update { l -> l.filterNot { it.id == id } }
    }

    override suspend fun requestAuthentication(id: String, context: String?): String {
        call("requestAuthentication")
        authentication.update { it + (id to AuthenticationState(id, lastResult = "requested")) }
        return "auth-request"
    }

    override fun safetyCode(id: String): SafetyCodeRecord? = safety[id]

    override fun messages(connectionId: String): Flow<List<MessageInfo>> = messageMap.map { it[connectionId].orEmpty() }

    override suspend fun refreshConversations() = call("refreshConversations")

    override suspend fun load(connectionId: String) = call("load")

    override suspend fun send(connectionId: String, text: String): MessageInfo {
        call("send")
        lastText = text
        val m = MessageInfo(
            connectionId,
            "m${++seq}",
            true,
            text,
            Instant.parse("2026-10-04T12:00:00Z").plusSeconds(seq.toLong()),
            false,
            false,
        )
        messageMap.update { it + (connectionId to it[connectionId].orEmpty() + m) }
        recompute()
        return m
    }

    override suspend fun markRead(connectionId: String) {
        call("markRead")
        messageMap.update { map -> map + (connectionId to map[connectionId].orEmpty().map { it.copy(read = true) }) }
        recompute()
    }

    override suspend fun delete(connectionId: String, messageId: String) {
        call("delete")
        messageMap.update { map -> map + (connectionId to map[connectionId].orEmpty().filterNot { it.messageId == messageId }) }
    }

    override suspend fun refreshApprovals() = call("refreshApprovals")

    override suspend fun refreshRequests() = call("refreshRequests")

    private fun drop(key: String) = approvals.update { l -> l.filterNot { it.key == key } }

    private fun approved(key: String) = approvals.update { l ->
        l.map {
            when {
                it.key != key -> it
                it is Approval.ConnectionRequest -> it.copy(state = RequestState.APPROVED)
                it is Approval.OutgoingRequest -> it.copy(state = RequestState.APPROVED)
                else -> it
            }
        }
    }

    override suspend fun approveConnection(pendingId: String) {
        call("approveConnection")
        approved("connection:$pendingId")
    }

    /** The other member declined request [id] (0.10.5): it ends and the member is told once. */
    fun peerDeclined(id: String, name: String?, outgoing: Boolean) {
        drop(if (outgoing) "outgoing:$id" else "connection:$id")
        requestEnds.update { it + (id to RequestEnd.PEER_DECLINED) }
        peerDeclines.update { it + PeerDecline(id, name, outgoing, Instant.parse("2026-10-05T12:00:00Z")) }
    }

    override suspend fun dismissPeerDecline(requestId: String) {
        call("dismissPeerDecline")
        peerDeclines.update { l -> l.filterNot { it.requestId == requestId } }
    }

    override suspend fun declineConnection(pendingId: String) {
        call("declineConnection")
        drop("connection:$pendingId")
        requestEnds.update { it + (pendingId to RequestEnd.DECLINED) }
    }

    override suspend fun approveOutgoing(connectionId: String) {
        call("approveOutgoing")
        approved("outgoing:$connectionId")
    }

    override suspend fun declineOutgoing(connectionId: String) {
        call("declineOutgoing")
        drop("outgoing:$connectionId")
        requestEnds.update { it + (connectionId to RequestEnd.DECLINED) }
    }

    /** Payloads [loadCriticalUse] returns, by request id. */
    val criticalPayloads = mutableMapOf<String, String>()

    override suspend fun loadCriticalUse(requestId: String) {
        call("loadCriticalUse")
        val p = criticalPayloads[requestId] ?: return
        approvals.update { l -> l.map { if (it is Approval.CriticalUse && it.requestId == requestId) it.copy(payload = p) else it } }
    }

    override suspend fun blockConnectionRequest(pendingId: String) {
        call("blockConnectionRequest")
        drop("connection:$pendingId")
    }

    override suspend fun approveAuthentication(requestId: String, password: String) {
        call("approveAuthentication")
        lastPassword = password
        drop("auth:$requestId")
    }

    override suspend fun denyAuthentication(requestId: String) {
        call("denyAuthentication")
        drop("auth:$requestId")
    }

    override suspend fun decideGrant(requestId: String, approve: Boolean) {
        call(if (approve) "approveGrant" else "denyGrant")
        drop("grant:$requestId")
    }

    override suspend fun approveCriticalUse(requestId: String, password: String) {
        call("approveCriticalUse")
        lastPassword = password
        drop("critical:$requestId")
    }

    override suspend fun denyCriticalUse(requestId: String) {
        call("denyCriticalUse")
        drop("critical:$requestId")
    }

    override suspend fun decideShare(ruleId: String, approve: Boolean) {
        call(if (approve) "includeShare" else "declineShare")
        drop("share:$ruleId")
    }

    override suspend fun declineDeviceRequest(key: String) {
        call("declineDeviceRequest")
        drop(key)
    }

    companion object {
        fun connection(id: String, name: String, favorite: Boolean = false, state: ConnectionState = ConnectionState.ACTIVE) =
            ConnectionInfo(id = id, name = name, state = state, favorite = favorite, createdAt = Instant.parse("2026-10-01T09:00:00Z"))

        fun message(conn: String, id: String, text: String, outgoing: Boolean, read: Boolean = outgoing, minute: Long = 0) =
            MessageInfo(
                conn,
                id,
                outgoing,
                text,
                Instant.parse("2026-10-04T12:00:00Z").plusSeconds(minute * 60),
                delivered = true,
                read = read,
            )
    }
}
