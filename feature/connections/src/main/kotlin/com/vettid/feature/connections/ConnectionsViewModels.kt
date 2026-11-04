package com.vettid.feature.connections

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.calls.CallAccess
import com.vettid.core.data.calls.CallAvailability
import com.vettid.core.data.calls.NoCallAccess
import com.vettid.core.data.items.GrantAsk
import com.vettid.core.data.items.GrantView
import com.vettid.core.data.items.RuleDraft
import com.vettid.core.data.items.RuleOverlaps
import com.vettid.core.data.items.ShareRule
import com.vettid.core.data.items.SharingRepository
import com.vettid.core.data.location.AskResult
import com.vettid.core.data.location.IncomingShare
import com.vettid.core.data.location.LocationAvailability
import com.vettid.core.data.location.LocationRepository
import com.vettid.core.data.location.NoLocation
import com.vettid.core.data.location.SendIssue
import com.vettid.core.data.prefs.InMemoryPreferencesRepository
import com.vettid.core.data.prefs.PreferencesRepository
import com.vettid.core.data.presence.NoPresence
import com.vettid.core.data.presence.PresenceAvailability
import com.vettid.core.data.presence.PresencePolicy
import com.vettid.core.data.presence.PresenceReading
import com.vettid.core.data.presence.PresenceRepository
import com.vettid.core.data.presence.PresenceShare
import com.vettid.core.data.presence.PresenceState
import com.vettid.core.data.presence.queryWhileAvailable
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
import com.vettid.core.data.votes.NoVotes
import com.vettid.core.data.votes.VoteRules
import com.vettid.core.data.votes.VotesAvailability
import com.vettid.core.data.votes.VotesRepository
import com.vettid.core.data.vault.OwnerCheckRepository
import com.vettid.core.data.vault.VaultCodes
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.checkCleared
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** Immutable UI state of the Connections screen. */
data class ConnectionsUiState(
    val loading: Boolean = true,
    val connections: List<ConnectionInfo> = emptyList(),
    /** The services among the connections (ANDROID-PLAN 0.1.90), starred ones first; they have a section of their own. */
    val services: List<ConnectionInfo> = emptyList(),
    val invites: List<OutstandingInvite> = emptyList(),
    val addSheet: Boolean = false,
    /** The invitation the member asked to cancel (confirmed first). */
    val cancelling: OutstandingInvite? = null,
    /** Requests the other member declined (0.10.5), until dismissed here or in Approvals. */
    val peerDeclines: List<PeerDecline> = emptyList(),
    val error: FailureKind? = null,
    /** (ANDROID-PLAN 0.1.57 (2)) The code `error_vault_code` names with [error], or null. */
    val errorCode: String? = null,
    /** The top bar's search by name (owner request 2026-10-08); while it is set only matching connections show. */
    val query: String = "",
    /** Whether there is any connection to search. */
    val searchable: Boolean = false,
    /**
     * The one-time card "Let your connections see when you're around?" (ANDROID-PLAN §4 "Presence", question 23): the vault has
     * presence, shares with everyone (its default) and the member has not answered on this phone.
     */
    val presenceCard: Boolean = false,
    /** The connections that share their location with the member (ANDROID-PLAN §4 "Location", the glance), by connection id. */
    val glances: Map<String, LocationGlance> = emptyMap(),
) {
    val searching: Boolean get() = query.isNotBlank()

    /** There is no person and no service to list. */
    val noneListed: Boolean get() = connections.isEmpty() && services.isEmpty()
}

/** Whether [c] matches [query]: its title ("First Last") or display name, ignoring case. */
internal fun ConnectionInfo.matches(query: String): Boolean {
    val q = query.trim()
    return q.isEmpty() ||
        listOfNotNull(displayName, name).any { AccountNames.cleaned(it).orEmpty().contains(q, ignoreCase = true) }
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
    private val ownerCheck: OwnerCheckRepository,
    private val presence: PresenceRepository = NoPresence,
    private val prefs: PreferencesRepository = InMemoryPreferencesRepository(),
    private val location: LocationRepository = NoLocation,
) : ViewModel() {
    private val local = MutableStateFlow(ConnectionsUiState())

    private val cardOn = combine(presence.availability, presence.policy, prefs.preferences) { a, p, pref ->
        a == PresenceAvailability.AVAILABLE && p?.share == PresenceShare.ALL && !pref.presenceCardAnswered
    }

    val uiState: StateFlow<ConnectionsUiState> = combine(
        local,
        repo.connections,
        approvals.peerDeclines,
        combine(cardOn, location.incoming) { card, shares -> card to shares },
    ) { s, cs, declines, (card, shares) ->
        val order = compareByDescending<ConnectionInfo> { it.favorite }
            .thenBy { AccountNames.cleaned(it.displayName).orEmpty().lowercase() }
        val shown = cs.filter { it.matches(s.query) }.sortedWith(order)
        s.copy(
            connections = shown.filterNot { it.service },
            services = shown.filter { it.service },
            peerDeclines = declines,
            searchable = cs.isNotEmpty(),
            presenceCard = card,
            glances = shares.associate { it.connectionId to LocationGlance(it.last?.at) },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionsUiState())

    init {
        refresh()
        // The daily check passed (or the hold ended): a list refused because it was due reads again by itself.
        viewModelScope.launch {
            ownerCheck.checkCleared().collect {
                if (local.value.error == FailureKind.OWNER_CHECK_REQUIRED) refresh()
            }
        }
    }

    fun refresh() {
        local.update { it.copy(error = null) }
        viewModelScope.launch {
            try {
                repo.refresh()
                val invites = repo.outstandingInvites()
                local.update { it.copy(loading = false, invites = invites) }
            } catch (e: VaultFailure) {
                local.update { it.copy(loading = false, error = e.kind, errorCode = e.shownCode) }
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

    /** "Share with everyone": the vault's default is already `share: all`, so nothing is sent; the card is answered. */
    fun shareWithEveryone() {
        viewModelScope.launch { prefs.setPresenceCardAnswered() }
    }

    /** "Not now": `share: none`, then the card is answered. A refused change keeps the card and says why. */
    fun notNow() = act {
        presence.update { it.withShare(PresenceShare.NONE) }
        prefs.setPresenceCardAnswered()
    }

    /** The member saw that the other member declined [requestId] (0.10.5): not shown again. */
    fun dismissPeerDecline(requestId: String) = act { approvals.dismissPeerDecline(requestId) }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: VaultFailure) {
                local.update { it.copy(error = e.kind, errorCode = e.shownCode) }
            }
        }
    }
}

/** The steps of inviting someone (§6.4); [DECLINED]: the other member declined (0.10.5). */
enum class InviteStep { CHOOSE, SHOWING, REQUEST, CONNECTING, CONNECTED, EXPIRED, DECLINED }

/** Immutable UI state of the invite screen. */
data class InviteUiState(
    val step: InviteStep = InviteStep.CHOOSE,
    /** [InviteStep.CONNECTED]: the new connection declared itself a service (it cannot message the member). */
    val connectedService: Boolean = false,
    val ttls: List<InviteTtl> = listOf(InviteTtl.TEN_MINUTES),
    val ttl: InviteTtl = InviteTtl.TEN_MINUTES,
    val invite: InviteInfo? = null,
    val request: Approval.ConnectionRequest? = null,
    val connectionId: String? = null,
    val connectionName: String? = null,
    /** [InviteStep.DECLINED]: the name the decline carried (the screen falls back to the request's). */
    val declinedName: String? = null,
    val busy: Boolean = false,
    /** The request arrived already approved by in-person auto-approval (§6.4), not by this member here. */
    val autoApproved: Boolean = false,
    val error: FailureKind? = null,
    /** (ANDROID-PLAN 0.1.57 (2)) The code `error_vault_code` names with [error], or null. */
    val errorCode: String? = null,
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
                state.update { it.copy(step = InviteStep.DECLINED, declinedName = d.name, busy = false) }
                runCatching { approvals.dismissPeerDecline(d.requestId) }
            }
        }
        viewModelScope.launch {
            connections.connections.collect { cs ->
                if (state.value.step != InviteStep.CONNECTING && state.value.step != InviteStep.REQUEST) return@collect
                val added = cs.firstOrNull { it.id !in before && it.state == ConnectionState.ACTIVE } ?: return@collect
                state.update {
                    it.copy(
                        step = InviteStep.CONNECTED,
                        connectionId = added.id,
                        connectionName = added.displayName.ifBlank { null },
                        connectedService = added.service,
                    )
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
                state.update { it.copy(busy = false, error = e.kind, errorCode = e.shownCode) }
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
                state.update { it.copy(busy = false, error = e.kind, errorCode = e.shownCode) }
            }
        }
    }

    fun decline() {
        val req = state.value.request ?: return
        decide(finish = true) { approvals.declineConnection(req.pendingId) }
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
                state.update { it.copy(busy = false, error = e.kind, errorCode = e.shownCode) }
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
    /** [AcceptStep.CONNECTED]: the new connection declared itself a service (it cannot message the member). */
    val connectedService: Boolean = false,
    /**
     * (VAULT-MESSAGING 0.69.0 §6.4) The request is from a service (`peer_kind` of the accept's answer or of
     * `connection.request.outgoing`): the safety code screen says so, and the name is "Name given by the service".
     */
    val service: Boolean = false,
    /**
     * The fingerprint of the service's `ik`: the request's own (0.83.7 §10.4), else the connection list's (§10.8); null
     * otherwise. Shown for a [service] only.
     */
    val keyFingerprint: String? = null,
    val input: String = "",
    /** The member typed or pasted [input]; a value that came with a link or a scan is theirs to confirm, not input of theirs. */
    val typed: Boolean = false,
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
    /** (ANDROID-PLAN 0.1.57 (2)) The code `error_vault_code` names with [error], or null. */
    val errorCode: String? = null,
) {
    /** Unsent input: an invitation typed or pasted, not yet sent. */
    val holdsInput: Boolean get() = step == AcceptStep.INPUT && typed && input.isNotEmpty()
}

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
        // A scanned code arrives with the route: accept it at once. An opened link waits for the member. Once only:
        // a ViewModel rebuilt from saved state (the activity was destroyed in the background) shows the code and
        // waits for the member, as the invitation was sent to the vault already and is single use (A6 audit R5).
        if (state.value.input.isNotBlank() && !state.value.fromLink && savedState.get<Boolean>(KEY_SENT) != true) {
            savedState[KEY_SENT] = true
            accept()
        }
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
                        active != null -> it.copy(
                            step = AcceptStep.CONNECTED,
                            connectionName = active.displayName.ifBlank { null },
                            connectedService = active.service,
                        )
                        end != null && req == null -> it.copy(step = AcceptStep.ENDED, end = end)
                        req == null -> it
                        else -> it.copy(
                            service = it.service || req.service,
                            // An invalid `ik` clears it, as in Approvals: no fallback to an earlier or the list's fingerprint.
                            keyFingerprint = if (req.peerIkInvalid) {
                                null
                            } else {
                                req.peerFingerprint ?: cs.firstOrNull { c -> c.id == id }?.keyFingerprint ?: it.keyFingerprint
                            },
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

    fun setInput(v: String) = state.update { it.copy(input = v, typed = true, error = null) }

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
                state.update { it.copy(step = AcceptStep.WAITING, connectionId = r.connectionId, name = r.name, service = r.service) }
                // The code may have arrived already (the handshake is quick): show it.
                runCatching { approvals.refreshRequests() }
            } catch (e: VaultFailure) {
                state.update { it.copy(step = AcceptStep.INPUT, error = e.kind, errorCode = e.shownCode) }
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
                state.update { it.copy(busy = false, error = e.kind, errorCode = e.shownCode) }
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
                state.update { it.copy(busy = false, error = e.kind, errorCode = e.shownCode) }
            }
        }
    }

    private companion object {
        val FOLLOWED = setOf(AcceptStep.WAITING, AcceptStep.COMPARE, AcceptStep.APPROVED)

        /** Saved state: the route's scanned code was sent to the vault by an earlier instance of this ViewModel. */
        const val KEY_SENT = "accept_sent"
    }
}

/** A connection shares its location with the member: the time of its latest position, or null before the first. Never a position. */
data class LocationGlance(val at: Instant?)

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
    /** (ANDROID-PLAN 0.1.57 (2)) The code `error_vault_code` names with [error], or null. */
    val errorCode: String? = null,
    /** Both directions of sharing with this connection (VAULT-ITEMS §6, VAULT-MESSAGING §10.12). */
    val sharing: DetailSharing = DetailSharing(),
    /** This build and this vault have calls and none is going on: the pill has "Call". */
    val canCall: Boolean = false,
    /** A share rule the member asked to delete, waiting for the confirmation. */
    val deleteRule: ShareRule? = null,
    /** This connection's votes (ANDROID-PLAN 0.1.102 "Votes"); nothing where the vault has none. */
    val votes: ConnectionVotes = ConnectionVotes(),
    /**
     * The vault answered `forbidden` to an authentication request (ANDROID-PLAN 0.1.90: the connection is a service this app
     * did not know): the text was shown once (as [error]) and the action is gone, whatever the connection list says.
     */
    val authRefused: Boolean = false,
    /**
     * Presence (ANDROID-PLAN §4 "Presence"): null for a service, for a connection that is not active, and wherever the vault
     * has no presence (nothing of it is shown then).
     */
    val presence: DetailPresence? = null,
    /** The member shares their location with this connection until [DetailLocation.until] (ANDROID-PLAN §4 "Location"). */
    val location: DetailLocation? = null,
    /** Not offered on a vault known to have no location (shown while that is not known yet). */
    val locationOffered: Boolean = true,
    /** The member's once share to this connection ended without a position being found. */
    val locationMissed: Boolean = false,
    /** This connection shares its location with the member (the incoming card), if it does. */
    val incoming: DetailIncoming? = null,
    /** **Ask for location**: the dialog and its outcome. */
    val locationAsk: LocationAsk = LocationAsk(),
    /** **Stop receiving** is out. */
    val stoppingIncoming: Boolean = false,
) {
    /** The daily owner check waits for the typed note (VAULT-MESSAGING §3.6.5, `HoldsInput`). */
    val holdsInput: Boolean get() = locationAsk.open
}

/**
 * The incoming card: "{First}'s location · {age} · within {accuracy}" / "Until {time}". [at] and [accuracyM] are the latest
 * position's time and accuracy, null before one arrives; the coordinates are not here.
 */
data class DetailIncoming(val shareId: String, val until: Instant, val at: Instant?, val accuracyM: Double?)

/** The **Ask for location** dialog: the optional note (at most 256 bytes) and the outcome. */
data class LocationAsk(
    val open: Boolean = false,
    val note: String = "",
    val busy: Boolean = false,
    /** The request went out. */
    val sent: Boolean = false,
    /** One request per connection per 10 minutes: the minutes until the next. */
    val limitedMinutes: Int? = null,
    val error: FailureKind? = null,
    val errorCode: String? = null,
)

/** An active outgoing location share with the connection: "Sharing your location until {time}" with **Stop**. */
data class DetailLocation(
    val shareId: String,
    val until: Instant,
    /** The daily check is due or held: nothing is sent ("Paused until your daily check."). */
    val paused: Boolean = false,
    /** Why nothing is being sent although the share is active, or [SendIssue.NONE]. */
    val issue: SendIssue = SendIssue.NONE,
    /** The member stopped it and the vault has not taken the stop yet: "Stopping…" (Stop stays, it is tried again). */
    val stopping: Boolean = false,
)

/**
 * What the connection's page shows of presence: the [reading] under the name, and whether the member's own status is
 * [shared] with this connection (the switch; null while the policy is not read, then no switch).
 */
data class DetailPresence(
    val reading: PresenceReading,
    val shared: Boolean?,
    /** The member's own status is Invisible: nothing is shared, whatever the switch would say. */
    val invisible: Boolean = false,
    /** The refresh icon works: the connection was not asked in the last minute (asking again would send nothing). */
    val canRefresh: Boolean = true,
)

/**
 * What the Votes feature knows of one connection: [count] votes from it (a service connection gets the row "Votes from
 * <name>"), whether one is [unfinished] (removing the connection asks first), and whether VettID's service list names this
 * person connection as a service ([reconnect]: "Connect again to vote").
 */
data class ConnectionVotes(
    val count: Int = 0,
    val unfinished: Boolean = false,
    val reconnect: Boolean = false,
    /** The vault has the vote feature ([VotesAvailability.AVAILABLE]): a service's "what it can ask" line mentions voting. */
    val available: Boolean = false,
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
@Suppress("TooManyFunctions") // one function per action of the screen
@HiltViewModel
class ConnectionDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: ConnectionsRepository,
    private val sharing: SharingRepository,
    private val calls: CallAccess = NoCallAccess,
    private val votes: VotesRepository = NoVotes,
    private val presence: PresenceRepository = NoPresence,
    private val location: LocationRepository = NoLocation,
) : ViewModel() {
    private val id: String = checkNotNull(savedState[ConnectionDetailRoute.ARG]) { "no connection" }
    private val local = MutableStateFlow(ConnectionDetailUiState(id))

    val uiState: StateFlow<ConnectionDetailUiState> =
        combine(
            local,
            repo.connections,
            repo.authentication,
            combine(calls.availability, ownVotes()) { c, v -> c to v },
            combine(ownPresence(), ownLocation(), location.availability, location.incoming, ::Extras),
        ) { s, cs, auth, (call, v), extras ->
            val p = extras.presence
            val shared = extras.location
            val offered = extras.availability
            val incoming = extras.incoming.firstOrNull { it.connectionId == id }
            val c = cs.firstOrNull { it.id == id } ?: s.connection
            s.copy(
                connection = c,
                auth = auth[id],
                canCall = call == CallAvailability.READY,
                votes = v,
                presence = p?.takeIf { c?.askable == true && it.reading != PresenceReading.Closed }?.let {
                    // Not asked yet is "Checking…" (it is being asked); closed shows no line at all.
                    DetailPresence(it.reading ?: PresenceReading.Checking, it.shared, it.invisible, it.canRefresh)
                },
                location = shared.location,
                locationMissed = shared.missed,
                locationOffered = offered != LocationAvailability.UNSUPPORTED,
                incoming = incoming?.let { DetailIncoming(it.shareId, it.expiresAt, it.last?.at, it.last?.accuracyM) },
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionDetailUiState(id))

    /** The presence and location parts of the page, read together. */
    private class Extras(
        val presence: PresenceView?,
        val location: LocationView,
        val availability: LocationAvailability,
        val incoming: List<IncomingShare>,
    )

    /** What presence knows of this connection, while the vault has presence; null otherwise. */
    private class PresenceView(val reading: PresenceReading?, val shared: Boolean?, val invisible: Boolean, val canRefresh: Boolean)

    private fun ownPresence() = combine(presence.availability, presence.policy, presence.readings, presence.recent) {
        a, policy, r, recent ->
        if (a == PresenceAvailability.AVAILABLE) {
            PresenceView(r[id], policy?.sharesWith(id), policy?.state == PresenceState.INVISIBLE, id !in recent)
        } else {
            null
        }
    }

    /** The member's outgoing location share with this connection, if one is active (ANDROID-PLAN §4 "Location"). */
    private class LocationView(val location: DetailLocation?, val missed: Boolean)

    private fun ownLocation() = combine(location.outgoing, location.serviceState, location.missedOnce) { shares, service, missed ->
        // A once share whose position went out has ended (the vault ends it with its first position).
        val share = shares.firstOrNull { it.connectionId == id && !(it.once && it.lastSentAt != null) }
        val sending = share?.let { service.targets.any { t -> t.connectionId == id } } == true
        val stopping = id in service.stopping
        LocationView(
            share?.let {
                val issue = if (sending) service.issue else SendIssue.NONE
                DetailLocation(it.shareId, it.expiresAt, paused = sending && service.paused, issue = issue, stopping = stopping)
            },
            id in missed,
        )
    }

    /** **Stop receiving**: `location.share.stop` of the incoming share; the share and its position go from this phone. */
    fun stopReceiving() {
        val share = uiState.value.incoming ?: return
        if (uiState.value.stoppingIncoming) return
        local.update { it.copy(stoppingIncoming = true, error = null) }
        viewModelScope.launch {
            try {
                location.stopReceiving(share.shareId)
                local.update { it.copy(stoppingIncoming = false) }
            } catch (e: VaultFailure) {
                local.update { it.copy(stoppingIncoming = false, error = e.kind, errorCode = e.shownCode) }
            }
        }
    }

    /** **Ask for location**: opens the dialog (a service connection is never asked). */
    fun openAsk() {
        if (uiState.value.connection?.service == true) return
        local.update { it.copy(locationAsk = LocationAsk(open = true)) }
    }

    fun closeAsk() = local.update { it.copy(locationAsk = LocationAsk()) }

    /** The note is kept to 256 bytes of UTF-8 (the vault's bound); text over it is not taken. */
    fun setAskNote(text: String) = local.update {
        if (text.toByteArray(Charsets.UTF_8).size > NOTE_MAX_BYTES) {
            it
        } else {
            it.copy(locationAsk = it.locationAsk.copy(note = text, error = null))
        }
    }

    /** Sends `location.request`; a second ask within 10 minutes is answered by "You asked {First} a few minutes ago…". */
    fun sendAsk() {
        val ask = local.value.locationAsk
        if (!ask.open || ask.busy || ask.sent) return
        local.update { it.copy(locationAsk = ask.copy(busy = true, error = null, errorCode = null, limitedMinutes = null)) }
        viewModelScope.launch {
            try {
                when (val r = location.ask(id, ask.note)) {
                    AskResult.Sent -> local.update { it.copy(locationAsk = it.locationAsk.copy(busy = false, sent = true)) }
                    is AskResult.Limited ->
                        local.update { it.copy(locationAsk = it.locationAsk.copy(busy = false, limitedMinutes = r.minutes)) }
                }
            } catch (e: VaultFailure) {
                local.update { it.copy(locationAsk = it.locationAsk.copy(busy = false, error = e.kind, errorCode = e.shownCode)) }
            }
        }
    }

    /** **Stop** on "Sharing your location until …": sending ends at once, the vault is told (a refused stop shows its error). */
    fun stopSharingLocation() {
        val share = uiState.value.location ?: return
        act { location.stop(share.shareId) }
    }

    /** Presence is asked of a person who is connected, never of a service (a service is not a person). */
    private val ConnectionInfo.askable: Boolean get() = state == ConnectionState.ACTIVE && !service

    private fun ownVotes() = combine(votes.votes, votes.reconnect, votes.availability) { v, r, a ->
        val mine = v.filter { it.connectionId == id }
        ConnectionVotes(
            count = mine.size,
            unfinished = mine.any(VoteRules::unfinished),
            reconnect = r.any { it.connectionId == id },
            available = a == VotesAvailability.AVAILABLE,
        )
    }

    /** The pill's "Call": the call screen follows from the call's state. */
    fun call() {
        if (uiState.value.canCall && uiState.value.connection?.state == ConnectionState.ACTIVE) calls.call(id)
    }

    /**
     * Whether the page is shown ([setVisible]). Declared above `init`: `viewModelScope` runs on `Main.immediate`, so the
     * presence query can start inside the constructor when the connection is already cached, before a field below `init`
     * is set.
     */
    private val shown = MutableStateFlow(true)

    init {
        viewModelScope.launch {
            try {
                val c = repo.connection(id)
                local.update { it.copy(loading = false, connection = c) }
            } catch (e: VaultFailure) {
                local.update { it.copy(loading = false, error = e.kind, errorCode = e.shownCode, gone = e.kind == FailureKind.NOT_FOUND) }
            }
        }
        loadSharing()
        // The incoming share's latest position is read when the page opens (and when a new share appears).
        viewModelScope.launch {
            location.incoming.map { l -> l.firstOrNull { it.connectionId == id }?.shareId }.distinctUntilChanged().filterNotNull()
                .collect {
                    try {
                        location.open(id)
                    } catch (_: VaultFailure) {
                        // What the list brought stays; a held vault reads again when it opens.
                    }
                }
        }
        // Presence is asked when the page opens (and on refresh), never from a list (ANDROID-PLAN §4 "Presence").
        viewModelScope.launch {
            val c = uiState.map { it.connection }.filterNotNull().first()
            if (c.askable) presence.queryWhileAvailable(id, shown)
        }
    }

    /** The screen is at least STARTED ([shown]) or stopped: presence is asked only while it is shown. */
    fun setVisible(visible: Boolean) {
        shown.value = visible
    }

    /** The refresh icon: asks again (the vault answers a second question within the minute with the first answer). */
    fun refreshPresence() {
        if (uiState.value.presence?.canRefresh == true) viewModelScope.launch { presence.query(id) }
    }

    /** The switch "Share my status with <First>": adds the connection to or takes it out of the policy's `except`. */
    fun setPresenceShared(shared: Boolean) = act {
        presence.update { p ->
            val next = p.withSharedWith(id, shared)
            // At most 1,024 exceptions (§10.17): refused here, with the vault's limit text, rather than sent.
            val over = next.except.size > PresencePolicy.MAX_EXCEPT && next.except.size > p.except.size
            if (over) throw VaultFailure(FailureKind.LIMIT, "limit")
            next
        }
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
        try {
            repo.requestAuthentication(id, null)
            local.update { it.copy(notice = DetailNotice.AUTH_REQUESTED) }
        } catch (e: VaultFailure) {
            if (e.code != VaultCodes.FORBIDDEN) throw e
            // Read the connections again so the vault's `peer_kind` arrives and the removal holds after leaving the screen.
            // `forbidden` can also come from a recovering app (VAULT-MESSAGING 0.51.2): then the read may fail, and a person's
            // connection keeps its action and the generic failure.
            runCatching { repo.refresh() }
            val known = uiState.value.connection?.service == true
            val service = known || repo.connections.value.firstOrNull { it.id == id }?.service == true
            if (!service) throw e
            // ANDROID-PLAN 0.1.90: `forbidden` to something sent to a service: "Services can't do that." and the action goes.
            local.update { it.copy(authRefused = true, error = FailureKind.SERVICE_FORBIDDEN, errorCode = null) }
        }
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
                local.update { it.copy(busy = false, error = e.kind, errorCode = e.shownCode) }
            }
        }
    }

    private companion object {
        const val ASK_PENDING = "pending"
        const val NOTE_MAX_BYTES = 256
    }
}
