package com.vettid.feature.approvals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.ApprovalsRepository
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
    val error: FailureKind? = null,
)

/** Approvals (ANDROID-PLAN §4): everything waiting for the member's decision, newest first. */
@HiltViewModel
class ApprovalsViewModel @Inject constructor(private val repo: ApprovalsRepository) : ViewModel() {
    private val local = MutableStateFlow(ApprovalsUiState())

    val uiState: StateFlow<ApprovalsUiState> = combine(local, repo.approvals) { s, list -> s.copy(approvals = list) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ApprovalsUiState())

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
) {
    /** Critical actions ask for the credential password (ANDROID-PLAN §4). */
    val needs: Needs get() = when (approval) {
        is Approval.Authentication, is Approval.CriticalUse -> Needs.PASSWORD
        else -> Needs.NOTHING
    }

    val canApprove: Boolean get() = when (val a = approval) {
        null, is Approval.DeviceRequest -> false
        is Approval.GrantRequest -> a.grantable.isNotEmpty()
        // 0.10.3: only once the safety code is known, and only once per member.
        is Approval.ConnectionRequest -> a.state == RequestState.PENDING
        is Approval.OutgoingRequest -> a.state == RequestState.PENDING && a.sas != null
        // §10.13: only a payload that matches its hash, shown to the member, can be approved.
        is Approval.CriticalUse -> a.payloadVerified && password.isNotEmpty()
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
class ApprovalDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: ApprovalsRepository,
) : ViewModel() {
    private val key: String = checkNotNull(savedState[ApprovalDetailRoute.ARG]) { "no approval" }
    private val local = MutableStateFlow(ApprovalDetailUiState(key, approval = repo.approvals.value.firstOrNull { it.key == key }))

    init {
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
        s.copy(approval = a ?: s.approval, gone = a == null && s.approval != null && !s.done && !s.busy)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    fun setPassword(v: String) = local.update { it.copy(password = v, error = null) }

    fun askBlock(show: Boolean) = local.update { it.copy(confirmBlock = show) }

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
                is Approval.GrantRequest -> repo.decideGrant(a.requestId, approve = true)
                is Approval.CriticalUse -> repo.approveCriticalUse(a.requestId, pw)
                is Approval.ShareDecision -> repo.decideShare(a.ruleId, approve = true)
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
                local.update { it.copy(busy = false, done = true, password = "") }
            } catch (e: VaultFailure) {
                local.update { it.copy(busy = false, error = e.kind, password = "") }
            }
        }
    }
}
