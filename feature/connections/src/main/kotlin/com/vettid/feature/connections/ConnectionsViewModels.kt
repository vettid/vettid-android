package com.vettid.feature.connections

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.vettid.core.data.social.RequestEnd
import com.vettid.core.data.social.RequestState
import com.vettid.core.data.social.SafetyCodeRecord
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
    val error: FailureKind? = null,
)

/**
 * Connections (ANDROID-PLAN §4, Proton's contacts): favourites first, then
 * by name; a star toggles the owner's `favorite` flag (§10.4); outstanding
 * invitations on top, each cancellable; add = invite, scan or paste.
 */
@HiltViewModel
class ConnectionsViewModel @Inject constructor(private val repo: ConnectionsRepository) : ViewModel() {
    private val local = MutableStateFlow(ConnectionsUiState())

    val uiState: StateFlow<ConnectionsUiState> = combine(local, repo.connections) { s, cs ->
        s.copy(connections = cs.sortedWith(compareByDescending<ConnectionInfo> { it.favorite }.thenBy { it.displayName.lowercase() }))
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

/** The steps of inviting someone (§6.4). */
enum class InviteStep { CHOOSE, SHOWING, REQUEST, CONNECTING, CONNECTED, EXPIRED }

/** Immutable UI state of the invite screen. */
data class InviteUiState(
    val step: InviteStep = InviteStep.CHOOSE,
    val ttls: List<InviteTtl> = listOf(InviteTtl.TEN_MINUTES),
    val ttl: InviteTtl = InviteTtl.TEN_MINUTES,
    val invite: InviteInfo? = null,
    val request: Approval.ConnectionRequest? = null,
    val connectionId: String? = null,
    val connectionName: String? = null,
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
            connections.connections.collect { cs ->
                if (state.value.step != InviteStep.CONNECTING && state.value.step != InviteStep.REQUEST) return@collect
                val added = cs.firstOrNull { it.id !in before && it.state == ConnectionState.ACTIVE } ?: return@collect
                state.update { it.copy(step = InviteStep.CONNECTED, connectionId = added.id, connectionName = added.displayName) }
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
                state.update {
                    when {
                        active != null -> it.copy(step = AcceptStep.CONNECTED, connectionName = active.displayName)
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
                    state.update { it.copy(step = AcceptStep.EXISTS, connectionId = r.connectionId, connectionName = c?.displayName) }
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

    /** Declines (the codes differ, or the member changed their mind); the inviter is not told (§6.4). */
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
    val safetyCode: SafetyCodeRecord? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val editing: Boolean = false,
    val aliasInput: String = "",
    val noteInput: String = "",
    val confirm: DetailConfirm? = null,
    /** The connection is gone (removed or blocked here): the screen closes. */
    val gone: Boolean = false,
    val notice: DetailNotice? = null,
    val error: FailureKind? = null,
)

enum class DetailConfirm { REMOVE, BLOCK }

enum class DetailNotice { AUTH_REQUESTED, SAVED }

/**
 * A connection (ANDROID-PLAN §4): the profile it shares (self-asserted), the
 * safety code shown when it was made, member authentication (§10.4), the
 * owner's alias and note, favourite, block and remove.
 */
@HiltViewModel
class ConnectionDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: ConnectionsRepository,
) : ViewModel() {
    private val id: String = checkNotNull(savedState[ConnectionDetailRoute.ARG]) { "no connection" }
    private val local = MutableStateFlow(ConnectionDetailUiState(id, safetyCode = repo.safetyCode(id)))

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
    }

    fun toggleFavorite() {
        val c = uiState.value.connection ?: return
        act { repo.setFavorite(id, !c.favorite) }
    }

    fun requestAuthentication() = act {
        repo.requestAuthentication(id, null)
        local.update { it.copy(notice = DetailNotice.AUTH_REQUESTED) }
    }

    fun edit(show: Boolean) {
        val c = uiState.value.connection
        local.update { it.copy(editing = show, aliasInput = c?.alias.orEmpty(), noteInput = c?.note.orEmpty()) }
    }

    fun setAlias(v: String) = local.update { it.copy(aliasInput = v.take(MAX_ALIAS)) }

    fun setNote(v: String) = local.update { it.copy(noteInput = v.take(MAX_NOTE)) }

    fun saveNames() {
        val c = uiState.value.connection ?: return
        val s = local.value
        val alias = s.aliasInput.trim().takeIf { it != c.alias.orEmpty() }
        val note = s.noteInput.trim().takeIf { it != c.note.orEmpty() }
        local.update { it.copy(editing = false) }
        if (alias == null && note == null) return
        act {
            repo.updateNames(id, alias, note)
            local.update { it.copy(notice = DetailNotice.SAVED) }
        }
    }

    fun ask(confirm: DetailConfirm?) = local.update { it.copy(confirm = confirm) }

    fun confirm() {
        val what = local.value.confirm ?: return
        local.update { it.copy(confirm = null) }
        act {
            if (what == DetailConfirm.BLOCK) repo.block(id) else repo.remove(id)
            local.update { it.copy(gone = true) }
        }
    }

    fun dismissNotice() = local.update { it.copy(notice = null, error = null) }

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
        /** §10.4: alias at most 128 bytes, note 1,024 (characters here; the vault checks bytes). */
        const val MAX_ALIAS = 64
        const val MAX_NOTE = 500
    }
}
