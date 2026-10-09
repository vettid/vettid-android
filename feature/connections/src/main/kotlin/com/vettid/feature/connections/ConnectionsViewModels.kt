package com.vettid.feature.connections

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.items.GrantAsk
import com.vettid.core.data.items.GrantView
import com.vettid.core.data.items.RuleDraft
import com.vettid.core.data.items.RuleOverlaps
import com.vettid.core.data.items.ShareRule
import com.vettid.core.data.items.SharingRepository
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.ApprovalsRepository
import com.vettid.core.data.social.AuthenticationState
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.social.ConnectionsRepository
import com.vettid.core.data.social.InviteInfo
import com.vettid.core.data.social.InviteLinks
import com.vettid.core.data.social.InviteTtl
import com.vettid.core.data.social.OutstandingInvite
import com.vettid.core.data.social.PeerDecline
import com.vettid.core.data.social.RequestEnd
import com.vettid.core.data.social.RequestState
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** Immutable UI state of the Connections screen. */
data class ConnectionsUiState(
    val loading: Boolean = true,
    val connections: List<ConnectionInfo> = emptyList(),
    val invites: List<OutstandingInvite> = emptyList(),
    val addSheet: Boolean = false,
    /** The invitation the member asked to cancel (confirmed first). */
    val cancelling: OutstandingInvite? = null,
    /** Requests the other member declined (0.10.5), until dismissed here or in Approvals. */
    val peerDeclines: List<PeerDecline> = emptyList(),
    val error: FailureKind? = null,
    /** The top bar's search by name (owner request 2026-10-08); while it is set only matching connections show. */
    val query: String = "",
    /** Whether there is any connection to search. */
    val searchable: Boolean = false,
) {
    val searching: Boolean get() = query.isNotBlank()
}

/** Whether [c] matches [query]: its title ("First Last") or display name, ignoring case. */
internal fun ConnectionInfo.matches(query: String): Boolean {
    val q = query.trim()
    return q.isEmpty() || displayName.contains(q, ignoreCase = true) || name.contains(q, ignoreCase = true)
}

/**
 * Connections (ANDROID-PLAN §4, Proton's contacts): favourites first, then
 * by name; a star toggles the owner's `favorite` flag (§10.4); outstanding
 * invitations on top, each cancellable; add = invite, scan or paste.
 */
@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val repo: ConnectionsRepository,
    private val approvals: ApprovalsRepository,
) : ViewModel() {
    private val local = MutableStateFlow(ConnectionsUiState())

    val uiState: StateFlow<ConnectionsUiState> = combine(local, repo.connections, approvals.peerDeclines) { s, cs, declines ->
        s.copy(
            connections = cs.filter { it.matches(s.query) }
                .sortedWith(compareByDescending<ConnectionInfo> { it.favorite }.thenBy { it.displayName.lowercase() }),
            peerDeclines = declines,
            searchable = cs.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionsUiState())

    init {
        refresh()
    }

    fun refresh() {
        local.update { it.copy(error = null) }
        viewModelScope.launch {
            try {
                repo.refresh()
                val invites = repo.outstandingInvites()
                local.update { it.copy(loading = false, invites = invites) }
            } catch (e: VaultFailure) {
                local.update { it.copy(loading = false, error = e.kind) }
            }
        }
    }

    fun setFavorite(id: String, favorite: Boolean) = act { repo.setFavorite(id, favorite) }

    fun showAddSheet(show: Boolean) = local.update { it.copy(addSheet = show) }

    fun setQuery(q: String) = local.update { it.copy(query = q) }

    fun askCancel(invite: OutstandingInvite?) = local.update { it.copy(cancelling = invite) }

    fun confirmCancel() {
        val i = local.value.cancelling ?: return
        local.update { it.copy(cancelling = null) }
        act {
            repo.cancelInvite(i.inviteId)
            local.update { s -> s.copy(invites = s.invites.filterNot { it.inviteId == i.inviteId }) }
        }
    }

    fun dismissError() = local.update { it.copy(error = null) }

    /** The member saw that the other member declined [requestId] (0.10.5): not shown again. */
    fun dismissPeerDecline(requestId: String) = act { approvals.dismissPeerDecline(requestId) }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: VaultFailure) {
                local.update { it.copy(error = e.kind) }
            }
        }
    }
}

/** The steps of inviting someone (§6.4); [DECLINED]: the other member declined (0.10.5). */
enum class InviteStep { CHOOSE, SHOWING, REQUEST, CONNECTING, CONNECTED, EXPIRED, DECLINED }

/** Immutable UI state of the invite screen. */
data class InviteUiState(
    val step: InviteStep = InviteStep.CHOOSE,
    val ttls: List<InviteTtl> = listOf(InviteTtl.TEN_MINUTES),
    val ttl: InviteTtl = InviteTtl.TEN_MINUTES,
    val invite: InviteInfo? = null,
    val request: Approval.ConnectionRequest? = null,
    val connectionId: String? = null,
    val connectionName: String? = null,
    /** [InviteStep.DECLINED]: the name the request showed. */
    val declinedName: String? = null,
    val busy: Boolean = false,
    val confirmBlock: Boolean = false,
    /** The request arrived already approved by in-person auto-approval (§6.4), not by this member here. */
    val autoApproved: Boolean = false,
    val error: FailureKind? = null,
)

/**
 * Invite a connection (§6.4, A4; 0.10.3): choose the lifetime (10 minutes in
 * person, longer for a remote link), show the QR code and the link; once the
 * other vault has completed the handshake the request arrives with its safety
 * code, which both members compare; each approves on their own phone, and the
 * connection appears once both have. Leaving the screen keeps the invitation
 * (Connections lists it until it is used, cancelled or expires) and the
 * request (Approvals).
 */
@HiltViewModel
class InviteViewModel @Inject constructor(
    private val connections: ConnectionsRepository,
    private val approvals: ApprovalsRepository,
) : ViewModel() {
    private val state = MutableStateFlow(InviteUiState())
    val uiState: StateFlow<InviteUiState> = state.asStateFlow()
    private var before: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            try {
                val ttls = connections.inviteTtls()
                state.update { it.copy(ttls = ttls) }
            } catch (_: VaultFailure) {
                // 10 minutes is always allowed (§6.4); the vault checks the relay's limits again
            }
        }
        viewModelScope.launch {
            approvals.approvals.collect { list ->
                val s = state.value
                val inv = s.invite ?: return@collect
                val req = list.filterIsInstance<Approval.ConnectionRequest>().firstOrNull { it.inviteId == inv.inviteId }
                when {
                    req != null && s.step == InviteStep.SHOWING -> {
                        before = connections.connections.value.map { it.id }.toSet()
                        // In-person auto-approval (§6.4): already approved here; the code is still shown to compare.
                        val step = if (req.state == RequestState.APPROVED) InviteStep.CONNECTING else InviteStep.REQUEST
                        state.update { it.copy(step = step, request = req, autoApproved = req.state == RequestState.APPROVED) }
                    }
                    req != null && (s.step == InviteStep.REQUEST || s.step == InviteStep.CONNECTING) ->
                        state.update { it.copy(request = req) }
                }
            }
        }
        viewModelScope.launch {
            // The other member declined (0.10.5): told here, once (the notice is dismissed).
            approvals.peerDeclines.collect { list ->
                val s = state.value
                if (s.step != InviteStep.REQUEST && s.step != InviteStep.CONNECTING) return@collect
                val req = s.request ?: return@collect
                val d = list.firstOrNull { !it.outgoing && it.requestId == req.pendingId } ?: return@collect
                state.update { it.copy(step = InviteStep.DECLINED, declinedName = d.name ?: req.name, busy = false) }
                runCatching { approvals.dismissPeerDecline(d.requestId) }
            }
        }
        viewModelScope.launch {
            connections.connections.collect { cs ->
                if (state.value.step != InviteStep.CONNECTING && state.value.step != InviteStep.REQUEST) return@collect
                val added = cs.firstOrNull { it.id !in before && it.state == ConnectionState.ACTIVE } ?: return@collect
                state.update {
                    it.copy(step = InviteStep.CONNECTED, connectionId = added.id, connectionName = added.displayName.ifBlank { null })
                }
            }
        }
    }

    fun setTtl(ttl: InviteTtl) = state.update { it.copy(ttl = ttl) }

    fun create() {
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val inv = connections.createInvite(state.value.ttl)
                state.update { it.copy(busy = false, step = InviteStep.SHOWING, invite = inv) }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, error = e.kind) }
            }
        }
    }

    /** The screen's clock: past the expiry, the code is no longer shown. */
    fun tick(now: Instant = Instant.now()) {
        val s = state.value
        if (s.step == InviteStep.SHOWING && s.invite != null && !s.invite.exp.isAfter(now)) {
            state.update { it.copy(step = InviteStep.EXPIRED) }
        }
    }

    /** This member's approval; the connection is made once the other member approves too (0.10.3). */
    fun approve() {
        val req = state.value.request ?: return
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                approvals.approveConnection(req.pendingId)
                state.update {
                    if (it.step == InviteStep.CONNECTED) it.copy(busy = false) else it.copy(busy = false, step = InviteStep.CONNECTING)
                }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, error = e.kind) }
            }
        }
    }

    fun decline() {
        val req = state.value.request ?: return
        decide(finish = true) { approvals.declineConnection(req.pendingId) }
    }

    fun askBlock(show: Boolean) = state.update { it.copy(confirmBlock = show) }

    fun block() {
        val req = state.value.request ?: return
        state.update { it.copy(confirmBlock = false) }
        decide(finish = true) { approvals.blockConnectionRequest(req.pendingId) }
    }

    /** Cancels the invitation before anyone used it (the vault revokes its token and deletes the claim). */
    fun cancelInvite() {
        val inv = state.value.invite ?: return
        decide(finish = true) { connections.cancelInvite(inv.inviteId) }
    }

    fun again() = state.update { InviteUiState(ttls = it.ttls, ttl = it.ttl) }

    private fun decide(finish: Boolean = false, block: suspend () -> Unit) {
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                state.update { if (finish) InviteUiState(ttls = it.ttls, ttl = it.ttl) else it.copy(busy = false) }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, error = e.kind) }
            }
        }
    }
}

/** The steps of accepting an invitation (§6.4, 0.10.3). */
enum class AcceptStep {
    /** Paste (or confirm an opened link). */
    INPUT,
    ACCEPTING,

    /** The vaults run the handshake; no safety code yet. */
    WAITING,

    /** The safety code is known: compare, then approve or decline. */
    COMPARE,

    /** This member approved; the inviter's approval is awaited. */
    APPROVED,
    CONNECTED,

    /** This vault is already connected to the inviter (or has asked to connect). */
    EXISTS,

    /** The request ended without a connection. */
    ENDED,
}

/** Immutable UI state of the accept screen. */
data class AcceptUiState(
    val step: AcceptStep = AcceptStep.INPUT,
    val input: String = "",
    /** The screen was opened from a link (App Link or `vettid://`): the member confirms first. */
    val fromLink: Boolean = false,
    val connectionId: String? = null,
    /** The inviter's name from the invitation, else the connection's. */
    val name: String? = null,
    val sas: String? = null,
    val remote: Boolean = false,
    val end: RequestEnd? = null,
    val connectionName: String? = null,
    val busy: Boolean = false,
    val error: FailureKind? = null,
)

/**
 * Accept an invitation (§6.4, 0.10.2/0.10.3): a pasted or opened link or a
 * scanned code. The vault fetches the claim and runs the handshake; once it
 * has, the safety code appears here (`connection.request.outgoing`) and the
 * member compares it with the inviter's screen and approves or declines their
 * own side. The connection is made once both have approved. A vault already
 * connected to the inviter says so (`exists`).
 */
@HiltViewModel
class AcceptViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: ConnectionsRepository,
    private val approvals: ApprovalsRepository,
) : ViewModel() {
    private val state = MutableStateFlow(
        AcceptUiState(
            input = savedState.get<String>(AcceptRoute.ARG).orEmpty(),
            fromLink = savedState.get<Boolean>(AcceptRoute.ARG_OPENED) ?: false,
        ),
    )
    val uiState: StateFlow<AcceptUiState> = state.asStateFlow()

    init {
        // A scanned code arrives with the route: accept it at once. An opened link waits for the member.
        if (state.value.input.isNotBlank() && !state.value.fromLink) accept()
        viewModelScope.launch {
            val sources = combine(approvals.approvals, repo.connections, approvals.requestEnds) { a, c, e -> Triple(a, c, e) }
            sources.collect { (list, cs, ends) ->
                val s = state.value
                val id = s.connectionId ?: return@collect
                if (s.step !in FOLLOWED) return@collect
                val active = cs.firstOrNull { it.id == id && it.state == ConnectionState.ACTIVE }
                val req = list.filterIsInstance<Approval.OutgoingRequest>().firstOrNull { it.connectionId == id }
                val end = ends[id]
                if (end == RequestEnd.PEER_DECLINED && req == null && active == null) {
                    // The inviter declined (0.10.5): told here, once (the notice is dismissed).
                    state.update { it.copy(step = AcceptStep.ENDED, end = end) }
                    runCatching { approvals.dismissPeerDecline(id) }
                    return@collect
                }
                state.update {
                    when {
                        active != null -> it.copy(step = AcceptStep.CONNECTED, connectionName = active.displayName.ifBlank { null })
                        end != null && req == null -> it.copy(step = AcceptStep.ENDED, end = end)
                        req == null -> it
                        else -> it.copy(
                            sas = req.sas ?: it.sas,
                            name = it.name ?: req.name,
                            remote = req.remote,
                            step = when (req.state) {
                                RequestState.WAITING -> AcceptStep.WAITING
                                RequestState.PENDING -> if (it.step == AcceptStep.APPROVED) AcceptStep.APPROVED else AcceptStep.COMPARE
                                RequestState.APPROVED -> AcceptStep.APPROVED
                            },
                        )
                    }
                }
            }
        }
    }

    fun setInput(v: String) = state.update { it.copy(input = v, error = null) }

    fun accept() {
        val text = state.value.input
        if (text.isBlank()) return
        state.update { it.copy(step = AcceptStep.ACCEPTING, error = null) }
        viewModelScope.launch {
            try {
                val r = repo.acceptInvite(text)
                if (r.exists) {
                    val c = repo.connections.value.firstOrNull { it.id == r.connectionId }
                    state.update {
                        it.copy(step = AcceptStep.EXISTS, connectionId = r.connectionId, connectionName = c?.displayName?.ifBlank { null })
                    }
                    return@launch
                }
                state.update { it.copy(step = AcceptStep.WAITING, connectionId = r.connectionId, name = r.name) }
                // The code may have arrived already (the handshake is quick): show it.
                runCatching { approvals.refreshRequests() }
            } catch (e: VaultFailure) {
                state.update { it.copy(step = AcceptStep.INPUT, error = e.kind) }
            }
        }
    }

    /** This member's approval of their side, after comparing the codes. */
    fun approve() {
        val id = state.value.connectionId ?: return
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                approvals.approveOutgoing(id)
                state.update {
                    if (it.step == AcceptStep.COMPARE) it.copy(busy = false, step = AcceptStep.APPROVED) else it.copy(busy = false)
                }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, error = e.kind) }
            }
        }
    }

    /** Declines (the codes differ, or the member changed their mind); the vault tells the inviter's vault (§6.4, 0.10.5). */
    fun decline() {
        val id = state.value.connectionId ?: return
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                approvals.declineOutgoing(id)
                state.update { it.copy(busy = false, step = AcceptStep.ENDED, end = RequestEnd.DECLINED) }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, error = e.kind) }
            }
        }
    }

    private companion object {
        val FOLLOWED = setOf(AcceptStep.WAITING, AcceptStep.COMPARE, AcceptStep.APPROVED)
    }
}

/** Immutable UI state of the scanner. */
data class ScanUiState(
    /** A code that is not a usable invitation: the scanner keeps looking and says why. */
    val problem: FailureKind? = null,
    /** A connection invitation's link: open the accept screen with it. */
    val link: String? = null,
)

/** The QR scanner (§6.4): checks what the camera read before anything is sent to the vault. */
@HiltViewModel
class ScanViewModel @Inject constructor() : ViewModel() {
    private val state = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = state.asStateFlow()

    fun onScanned(text: String) {
        if (state.value.link != null) return
        when (val p = InviteLinks.parse(text)) {
            is InviteLinks.Parsed.Ok -> state.update { ScanUiState(link = p.link) }
            InviteLinks.Parsed.Expired -> state.update { it.copy(problem = FailureKind.INVITE_EXPIRED) }
            InviteLinks.Parsed.NotAConnection -> state.update { it.copy(problem = FailureKind.INVITE_NOT_CONNECTION) }
            InviteLinks.Parsed.Invalid -> state.update { it.copy(problem = FailureKind.INVITE_INVALID) }
        }
    }

    /** The accept screen took the link over. */
    fun consumed() = state.update { ScanUiState() }
}

/** Immutable UI state of a connection's detail screen. */
data class ConnectionDetailUiState(
    val connectionId: String,
    val connection: ConnectionInfo? = null,
    val auth: AuthenticationState? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val confirm: DetailConfirm? = null,
    /** The connection is gone (removed here): the screen closes. */
    val gone: Boolean = false,
    val notice: DetailNotice? = null,
    val error: FailureKind? = null,
    /** Both directions of sharing with this connection (VAULT-ITEMS §6, VAULT-MESSAGING §10.12). */
    val sharing: DetailSharing = DetailSharing(),
    /** A share rule the member asked to delete, waiting for the confirmation. */
    val deleteRule: ShareRule? = null,
)

/**
 * Sharing with one connection, both ways (§10.12): what the member shares with it ([rules] for this connection and
 * the [given] grants in force) and what it shares with the member ([received] grants in force, and the member's
 * [asked] requests still waiting). [loaded] once read; [failed] when the vault could not say (the rest of the detail
 * still shows).
 */
data class DetailSharing(
    val rules: List<ShareRule> = emptyList(),
    val given: List<GrantView> = emptyList(),
    val received: List<GrantView> = emptyList(),
    val asked: List<GrantAsk> = emptyList(),
    val loaded: Boolean = false,
    val failed: Boolean = false,
) {
    /** How many items the connection can fetch now (each item once, however many grants name it). */
    val outgoingCount: Int get() = given.distinctBy { it.itemRef }.size

    /** How many of the connection's items the member can fetch now. */
    val incomingCount: Int get() = received.distinctBy { it.itemRef }.size

    val outgoingEmpty: Boolean get() = rules.isEmpty() && given.isEmpty()

    val incomingEmpty: Boolean get() = received.isEmpty()

    /** Each rule's id → the other rules of this connection covering the same tags or items (§10.12). */
    val overlaps: Map<String, List<ShareRule>> get() = RuleOverlaps.of(rules)

    /** The connection has the most rules it can have (§10.12 `share_rules_subject`, 64): no new one. */
    val atRuleLimit: Boolean get() = rules.size >= RuleDraft.MAX_RULES_PER_SUBJECT
}

enum class DetailConfirm { REMOVE }

enum class DetailNotice { AUTH_REQUESTED }

/**
 * A connection (ANDROID-PLAN §4): the profile it shares (self-asserted), sharing both ways (what the member shares
 * with it, what it shares with the member: §10.12), member authentication (§10.4), favourite and remove. No safety
 * code, alias, note or block here (owner decision 2026-10-08).
 */
@HiltViewModel
class ConnectionDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: ConnectionsRepository,
    private val sharing: SharingRepository,
) : ViewModel() {
    private val id: String = checkNotNull(savedState[ConnectionDetailRoute.ARG]) { "no connection" }
    private val local = MutableStateFlow(ConnectionDetailUiState(id))

    val uiState: StateFlow<ConnectionDetailUiState> = combine(local, repo.connections, repo.authentication) { s, cs, auth ->
        s.copy(connection = cs.firstOrNull { it.id == id } ?: s.connection, auth = auth[id])
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionDetailUiState(id))

    init {
        viewModelScope.launch {
            try {
                val c = repo.connection(id)
                local.update { it.copy(loading = false, connection = c) }
            } catch (e: VaultFailure) {
                local.update { it.copy(loading = false, error = e.kind, gone = e.kind == FailureKind.NOT_FOUND) }
            }
        }
        loadSharing()
    }

    /**
     * Reads both directions again (`share.rule.list` for this connection, `grant.list`), e.g. when the screen comes
     * back from the rule editor. Only grants in force count; a failure leaves the rest of the screen as it is.
     */
    fun loadSharing() {
        viewModelScope.launch {
            try {
                val rules = sharing.rules(id).filter { it.connectionId == id }
                val g = sharing.grants()
                local.update {
                    it.copy(
                        sharing = DetailSharing(
                            rules = rules,
                            given = g.given.filter { x -> x.connectionId == id && x.active }.sortedBy { x -> x.name.lowercase() },
                            received = g.received.filter { x -> x.connectionId == id && x.active }.sortedBy { x -> x.name.lowercase() },
                            asked = g.requested.filter { x -> x.connectionId == id && x.state == ASK_PENDING },
                            loaded = true,
                        ),
                    )
                }
            } catch (_: VaultFailure) {
                local.update { it.copy(sharing = it.sharing.copy(loaded = true, failed = true)) }
            }
        }
    }

    fun toggleFavorite() {
        val c = uiState.value.connection ?: return
        act { repo.setFavorite(id, !c.favorite) }
    }

    /** Mutes or unmutes this connection's asks (`connection.asks.mute`, 0.23.0 §10.4.1). */
    fun muteAsks(muted: Boolean) = act { repo.setAsksMuted(id, muted) }

    /** Resumes paused asks and clears the cooldowns of declined ones (`connection.asks.resume`, §10.4.1). */
    fun resumeAsks() = act { repo.resumeAsks(id) }

    fun requestAuthentication() = act {
        repo.requestAuthentication(id, null)
        local.update { it.copy(notice = DetailNotice.AUTH_REQUESTED) }
    }

    fun ask(confirm: DetailConfirm?) = local.update { it.copy(confirm = confirm) }

    fun confirm() {
        if (local.value.confirm != DetailConfirm.REMOVE) return
        local.update { it.copy(confirm = null) }
        act {
            repo.remove(id)
            local.update { it.copy(gone = true) }
        }
    }

    fun dismissNotice() = local.update { it.copy(notice = null, error = null) }

    /** Asks to delete a share rule of this connection (null: never mind). */
    fun askDeleteRule(rule: ShareRule?) = local.update { it.copy(deleteRule = rule) }

    /** Deletes the rule asked about (`share.rule.delete`, §10.12): its items are withdrawn, then both directions are read again. */
    fun deleteRule() {
        val r = local.value.deleteRule ?: return
        local.update { it.copy(deleteRule = null) }
        act {
            sharing.deleteRule(r.ruleId)
            loadSharing()
        }
    }

    private fun act(block: suspend () -> Unit) {
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                local.update { it.copy(busy = false) }
            } catch (e: VaultFailure) {
                local.update { it.copy(busy = false, error = e.kind) }
            }
        }
    }

    private companion object {
        const val ASK_PENDING = "pending"
    }
}
