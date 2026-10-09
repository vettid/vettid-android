package com.vettid.feature.approvals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.ApprovalsRepository
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionsRepository
import com.vettid.core.data.social.GrantDecision
import com.vettid.core.data.items.ItemSummary
import com.vettid.core.data.items.ItemsRepository
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.social.PeerDecline
import com.vettid.core.data.social.RequestState
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Immutable UI state of the Approvals screen. */
data class ApprovalsUiState(
    val loading: Boolean = true,
    val approvals: List<Approval> = emptyList(),
    /** Requests the other member declined (0.10.5): told once, until dismissed. */
    val peerDeclines: List<PeerDecline> = emptyList(),
    val error: FailureKind? = null,
    /** Connections whose asks are paused after several declines (0.23.0 §10.4.1): resume, or remove the connection. */
    val paused: List<ConnectionInfo> = emptyList(),
    /** A paused connection the member asked to remove (confirmed first). */
    val confirmRemove: ConnectionInfo? = null,
    val busy: Boolean = false,
) {
    /** The rows: one per approval, a connection's asks within 10 minutes as one batch (§10.4.1). */
    val entries: List<ApprovalEntry> get() = AskBatches.group(approvals)
}

/** Approvals (ANDROID-PLAN §4): everything waiting for the member's decision, newest first. */
@HiltViewModel
class ApprovalsViewModel @Inject constructor(
    private val repo: ApprovalsRepository,
    private val connections: ConnectionsRepository,
) : ViewModel() {
    private val local = MutableStateFlow(ApprovalsUiState())

    val uiState: StateFlow<ApprovalsUiState> =
        combine(local, repo.approvals, repo.peerDeclines, connections.connections) { s, list, declines, cs ->
            s.copy(approvals = list, peerDeclines = declines, paused = cs.filter { it.asks?.paused == true })
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ApprovalsUiState())

    init {
        refresh()
    }

    fun refresh() {
        local.update { it.copy(error = null) }
        viewModelScope.launch {
            try {
                repo.refreshApprovals()
                local.update { it.copy(loading = false) }
            } catch (e: VaultFailure) {
                local.update { it.copy(loading = false, error = e.kind) }
            }
        }
    }

    /** Resumes a paused connection's asks (`connection.asks.resume`, 0.23.0 §10.4.1). */
    fun resumeAsks(connectionId: String) = act { connections.resumeAsks(connectionId) }

    fun askRemove(c: ConnectionInfo?) = local.update { it.copy(confirmRemove = c) }

    /** Removes the paused connection the member confirmed (§10.4.1 offers it with the pause). */
    fun remove() {
        val c = local.value.confirmRemove ?: return
        local.update { it.copy(confirmRemove = null) }
        act { connections.remove(c.id) }
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

    /** The member saw that the other member declined [requestId] (0.10.5): not shown again. */
    fun dismissPeerDecline(requestId: String) {
        viewModelScope.launch {
            try {
                repo.dismissPeerDecline(requestId)
            } catch (e: VaultFailure) {
                local.update { it.copy(error = e.kind) }
            }
        }
    }
}

/** What a decision needs from the member. */
enum class Needs { NOTHING, PASSWORD }

/** Immutable UI state of one approval. */
data class ApprovalDetailUiState(
    val key: String,
    val approval: Approval? = null,
    val password: String = "",
    val busy: Boolean = false,
    val confirmBlock: Boolean = false,
    /** Decided here: the screen closes. */
    val done: Boolean = false,
    /** It left the list without a decision here (decided on another device, or expired). */
    val gone: Boolean = false,
    val error: FailureKind? = null,
    /** A share decision's items the member unticked: declined, the rest included (§10.12). */
    val shareExcluded: Set<String> = emptySet(),
    /** The member's `data` and `secret` items (§10.12: only they are granted), to name and answer grant entries. */
    val items: List<ItemSummary> = emptyList(),
    /** A grant request's entries the member chose (indexes); null: the default (every named item that exists). */
    val grantSelected: Set<Int>? = null,
    /** A category entry's answer: index → the member's item id. */
    val grantAnswers: Map<Int, String> = emptyMap(),
    /** Uses and lifetime the member set (null: the request's). */
    val grantUses: Int? = null,
    val grantExpiresIn: Long? = null,
    /** A critical-item use was approved: the status the connection received (`ok`, `unsuitable`, `unavailable`). */
    val criticalResult: String? = null,
    /** The end of a password backoff (`retry_after`, VAULT-MESSAGING 0.17.0). */
    val retryUntil: java.time.Instant? = null,
    /** The limit a `limit` error named (VAULT-MESSAGING 0.21.0 §10.1: `grants_given`, say). */
    val limit: com.vettid.core.data.vault.VaultLimit? = null,
) {
    /** The grant entries that will be granted: chosen, and for a category answered. */
    val grantIndexes: Set<Int>
        get() {
            val g = approval as? Approval.GrantRequest ?: return emptySet()
            val chosen = grantSelected ?: g.grantable.toSet()
            fun ok(i: Int) = g.entries.getOrNull(i)?.let { it.kind == "item" && it.available || grantAnswers[i] != null } == true
            return chosen.filter(::ok).toSet()
        }

    /** Items the member may answer with: `data` and `secret` (never `critical`, §10.12). */
    val answerable: List<ItemSummary> get() = items.filter { it.sensitivity != Sensitivity.CRITICAL }

    /** Critical actions ask for the credential password (ANDROID-PLAN §4). */
    val needs: Needs get() = when (approval) {
        is Approval.Authentication, is Approval.CriticalUse -> Needs.PASSWORD
        else -> Needs.NOTHING
    }

    val canApprove: Boolean get() = when (val a = approval) {
        null, is Approval.DeviceRequest -> false
        is Approval.GrantRequest -> grantIndexes.isNotEmpty()
        // 0.10.3: only once the safety code is known, and only once per member.
        is Approval.ConnectionRequest -> a.state == RequestState.PENDING
        is Approval.OutgoingRequest -> a.state == RequestState.PENDING && a.sas != null
        // §10.13: only a payload that matches its hash, shown to the member, can be approved; a field that cannot
        // hold a seed (0.21.0: never asked by a 0.21.0 vault) only denied.
        is Approval.CriticalUse -> a.suitable != false &&
            a.payloadVerified && password.isNotEmpty() && (retryUntil == null || java.time.Instant.now().isAfter(retryUntil))
        is Approval.ShareDecision -> a.items.any { it.itemId !in shareExcluded }
        else -> needs == Needs.NOTHING || password.isNotEmpty()
    }

    override fun toString(): String = "ApprovalDetailUiState(key=$key, busy=$busy, done=$done, error=$error)"
}

/**
 * One approval (ANDROID-PLAN §4): what is asked, by whom, and approve / deny.
 * Member authentication and critical-item uses take the credential password
 * (§10.4, §10.13); a connection request shows its safety code and can be
 * blocked; a desktop's or agent's request can only be declined in v1.
 */
@HiltViewModel
@Suppress("TooManyFunctions")
class ApprovalDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: ApprovalsRepository,
    private val items: ItemsRepository,
) : ViewModel() {
    private val key: String = checkNotNull(savedState[ApprovalDetailRoute.ARG]) { "no approval" }
    private val local = MutableStateFlow(ApprovalDetailUiState(key, approval = repo.approvals.value.firstOrNull { it.key == key }))

    init {
        viewModelScope.launch { items.items.collect { l -> local.update { it.copy(items = l.sortedBy { i -> i.name.lowercase() }) } } }
        if (items.load.value == ListLoad.NOT_LOADED) viewModelScope.launch { runCatching { items.refresh() } }
        // A critical-item request from the list has only the payload's hash: fetch the payload (§10.13).
        val a = local.value.approval
        if (a is Approval.CriticalUse && a.payload.isEmpty()) {
            viewModelScope.launch {
                try {
                    repo.loadCriticalUse(a.requestId)
                } catch (e: VaultFailure) {
                    local.update { it.copy(error = e.kind) }
                }
            }
        }
    }

    val uiState: StateFlow<ApprovalDetailUiState> = combine(local, repo.approvals) { s, list ->
        val a = list.firstOrNull { it.key == key }
        s.copy(approval = a ?: s.approval, gone = a == null && s.approval != null && !s.done && !s.busy && s.criticalResult == null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    fun setPassword(v: String) = local.update { it.copy(password = v, error = null) }

    fun askBlock(show: Boolean) = local.update { it.copy(confirmBlock = show) }

    /** Ticks or unticks one entry of a grant request. */
    private fun chosen(s: ApprovalDetailUiState): Set<Int> =
        s.grantSelected ?: (uiState.value.approval as? Approval.GrantRequest)?.grantable?.toSet() ?: emptySet()

    fun toggleGrantEntry(index: Int) = local.update { s ->
        val cur = chosen(s)
        s.copy(grantSelected = if (index in cur) cur - index else cur + index)
    }

    /** Answers a category entry with one of the member's items (and ticks it). */
    fun answerGrantEntry(index: Int, itemId: String) = local.update { s ->
        val cur = chosen(s)
        s.copy(grantAnswers = s.grantAnswers + (index to itemId), grantSelected = cur + index)
    }

    fun setGrantUses(uses: Int?) = local.update { it.copy(grantUses = uses) }

    fun setGrantExpiresIn(seconds: Long?) = local.update { it.copy(grantExpiresIn = seconds) }

    /** Ticks or unticks one item of a share decision. */
    fun toggleShareItem(itemId: String) = local.update { s ->
        s.copy(shareExcluded = if (itemId in s.shareExcluded) s.shareExcluded - itemId else s.shareExcluded + itemId)
    }

    fun approve() {
        val s = local.value.copy(approval = uiState.value.approval ?: local.value.approval)
        val a = s.approval ?: return
        if (!s.canApprove) return
        val pw = s.password
        decide {
            when (a) {
                is Approval.ConnectionRequest -> repo.approveConnection(a.pendingId)
                is Approval.OutgoingRequest -> repo.approveOutgoing(a.connectionId)
                is Approval.Authentication -> repo.approveAuthentication(a.requestId, pw)
                is Approval.GrantRequest -> repo.decideGrant(
                    a.requestId,
                    GrantDecision(
                        s.grantIndexes.sorted(),
                        s.grantAnswers.filterKeys { it in s.grantIndexes },
                        s.grantUses,
                        s.grantExpiresIn,
                    ),
                )
                is Approval.CriticalUse -> {
                    val status = repo.approveCriticalUse(a.requestId, pw)
                    local.update { it.copy(criticalResult = status ?: "ok") }
                }
                is Approval.ShareDecision -> {
                    val ids = a.items.map { it.itemId }
                    repo.decideShare(a.ruleId, include = ids - s.shareExcluded, decline = ids.filter { it in s.shareExcluded })
                }
                is Approval.DeviceRequest -> Unit
            }
        }
    }

    fun deny() {
        val a = uiState.value.approval ?: return
        decide {
            when (a) {
                is Approval.ConnectionRequest -> repo.declineConnection(a.pendingId)
                is Approval.OutgoingRequest -> repo.declineOutgoing(a.connectionId)
                is Approval.Authentication -> repo.denyAuthentication(a.requestId)
                is Approval.GrantRequest -> repo.decideGrant(a.requestId, approve = false)
                is Approval.CriticalUse -> repo.denyCriticalUse(a.requestId)
                is Approval.ShareDecision -> repo.decideShare(a.ruleId, approve = false)
                is Approval.DeviceRequest -> repo.declineDeviceRequest(a.key)
            }
        }
    }

    fun block() {
        val a = uiState.value.approval as? Approval.ConnectionRequest ?: return
        local.update { it.copy(confirmBlock = false) }
        decide { repo.blockConnectionRequest(a.pendingId) }
    }

    private fun decide(block: suspend () -> Unit) {
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                // A critical use stays on screen with its result; everything else closes.
                local.update { it.copy(busy = false, done = it.criticalResult == null, password = "") }
            } catch (e: VaultFailure) {
                val backoff = e.kind == FailureKind.BACKOFF && e.retryAfterSeconds > 0
                val until = if (backoff) java.time.Instant.now().plusSeconds(e.retryAfterSeconds) else null
                local.update { it.copy(busy = false, error = e.kind, limit = e.limit, password = "", retryUntil = until) }
            }
        }
    }

    /** The member read the critical-use result. */
    fun finish() = local.update { it.copy(done = true) }
}
