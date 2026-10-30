package com.vettid.feature.approvals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.devices.DevicesRepository
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
import com.vettid.core.data.vault.OwnerCheckRepository
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.checkCleared
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** Immutable UI state of the Approvals screen. */
data class ApprovalsUiState(
    val loading: Boolean = true,
    val approvals: List<Approval> = emptyList(),
    /** Requests the other member declined (0.10.5): told once, until dismissed. */
    val peerDeclines: List<PeerDecline> = emptyList(),
    val error: FailureKind? = null,
    /** (ANDROID-PLAN 0.1.57 (2)) The code `error_vault_code` names with [error], or null. */
    val errorCode: String? = null,
    /**
     * Why the last read of the open asks (`pending.list`, §10.4.2) failed, or null: shown as a notice with Retry, and as
     * held for [FailureKind.OWNER_CHECK_REQUIRED]. Also set by the quiet reads after an unlock or a passed check.
     */
    val openAsksError: FailureKind? = null,
    /** The code `error_vault_code` names with [openAsksError], or null (not the code of the screen's own [error]). */
    val openAsksCode: String? = null,
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
    private val ownerCheck: OwnerCheckRepository,
) : ViewModel() {
    private val local = MutableStateFlow(ApprovalsUiState())

    private val resumed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Ticks at each expiry still to come among the listed requests. */
    private val clock = expiryClock(
        repo.approvals.map { l -> l.mapNotNull { it.exp }.filter { it.isAfter(Instant.now()) } },
        resumed,
    )

    /** The screen came back to the front: the time is read again (a sleep stops in deep sleep). */
    fun tick() {
        resumed.tryEmit(Unit)
    }

    val uiState: StateFlow<ApprovalsUiState> =
        combine(
            combine(local, repo.approvals, repo.peerDeclines, connections.connections, clock) { s, list, declines, cs, now ->
                // An expired request is not offered (the list drops it itself only at the next vault event).
                s.copy(
                    approvals = list.filterNot { it.expiredAt(now) },
                    peerDeclines = declines,
                    paused = cs.filter { it.asks?.paused == true },
                )
            },
            repo.pendingReadFailure,
        ) { s, openAsks ->
            s.copy(openAsksError = openAsks?.kind, openAsksCode = openAsks?.shownCode)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ApprovalsUiState())

    init {
        refresh()
        // The daily check passed (or the hold ended): a list refused because it was due reads again by itself.
        viewModelScope.launch {
            ownerCheck.checkCleared().collect {
                val held = FailureKind.OWNER_CHECK_REQUIRED
                if (local.value.error == held || uiState.value.openAsksError == held) refresh()
            }
        }
    }

    fun refresh() {
        local.update { it.copy(error = null) }
        viewModelScope.launch {
            try {
                repo.refreshApprovals()
                local.update { it.copy(loading = false) }
            } catch (e: VaultFailure) {
                local.update { it.copy(loading = false, error = e.kind, errorCode = e.shownCode) }
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
                local.update { it.copy(busy = false, error = e.kind, errorCode = e.shownCode) }
            }
        }
    }

    /** The member saw that the other member declined [requestId] (0.10.5): not shown again. */
    fun dismissPeerDecline(requestId: String) {
        viewModelScope.launch {
            try {
                repo.dismissPeerDecline(requestId)
            } catch (e: VaultFailure) {
                local.update { it.copy(error = e.kind, errorCode = e.shownCode) }
            }
        }
    }
}

/** What a decision needs from the member. */
enum class Needs { NOTHING, PASSWORD }

/** Why "Deny and end access now" did not end access: the failure's kind and the code its message names (or null). */
data class EndFailure(val kind: FailureKind, val code: String?)

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
    /** (ANDROID-PLAN 0.1.57 (2)) The code `error_vault_code` names with [error], or null. */
    val errorCode: String? = null,
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
    /**
     * A renewal request (VAULT-MESSAGING 0.78.0 §6.8 step 3): when the session the device already has ends, or null for a
     * first request. Set only while that session has not expired and the device list was read when the request was shown.
     */
    val renewalUntil: java.time.Instant? = null,
    /** "End access for <device>?" is open (from "Deny and end access now"). */
    val confirmEnd: Boolean = false,
    /** Ending access failed: said plainly with Try again; the request stays open. */
    val endFailure: EndFailure? = null,
    /** The device list was read when the request was shown (a failed read leaves this false: a first request). */
    val deviceListRead: Boolean = false,
    /** The vault is held (§3.6.3) and this is a session request: it left the screen, and a confirmation ends nothing. */
    val held: Boolean = false,
    /** Ending access is being sent (`busy` too) from the gone screen (the request had left): that screen stays. */
    val ending: Boolean = false,
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
        null -> false
        // A desktop's or agent's request can only be declined here until AA5 (Allow with a length).
        is Approval.DeviceRequest -> false
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
 * blocked; a desktop's or agent's request can only be declined (approving comes with AA5), and a renewal can also deny and end access
 * (VAULT-MESSAGING 0.78.0 §6.8 step 3).
 */
@HiltViewModel
@Suppress("TooManyFunctions")
class ApprovalDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: ApprovalsRepository,
    private val items: ItemsRepository,
    private val devices: DevicesRepository,
    private val ownerCheck: OwnerCheckRepository,
) : ViewModel() {
    private val key: String = checkNotNull(savedState[ApprovalDetailRoute.ARG]) { "no approval" }
    private val local = MutableStateFlow(ApprovalDetailUiState(key, approval = repo.approvals.value.firstOrNull { it.key == key }))

    init {
        viewModelScope.launch { items.items.collect { l -> local.update { it.copy(items = l.sortedBy { i -> i.name.lowercase() }) } } }
        if (items.load.value == ListLoad.NOT_LOADED) viewModelScope.launch { runCatching { items.refresh() } }
        // A session request: whether it renews a live session comes from the device list, read now (§6.8 step 3). The
        // read never holds the request back: if it fails the request is shown as a first request.
        if ((local.value.approval as? Approval.DeviceRequest)?.sessionDeviceId != null) {
            viewModelScope.launch {
                try {
                    devices.refresh()
                    local.update { it.copy(deviceListRead = true) }
                } catch (_: VaultFailure) {
                    // Nothing is logged or shown: the request is a first request, until a later read succeeds (a
                    // `sync.event` of kind `device.session` re-reads the list in the repository).
                    devices.load.first { it == ListLoad.LOADED }
                    local.update { it.copy(deviceListRead = true) }
                }
            }
        }
        // A critical-item request from the list has only the payload's hash: fetch the payload (§10.13).
        val a = local.value.approval
        if (a is Approval.CriticalUse && a.payload.isEmpty()) {
            viewModelScope.launch {
                try {
                    repo.loadCriticalUse(a.requestId)
                } catch (e: VaultFailure) {
                    local.update { it.copy(error = e.kind, errorCode = e.shownCode) }
                }
            }
        }
    }

    private val resumed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The end of the session this request renews, as the device list says now; null for a first request. */
    private val sessionEnd = combine(
        repo.approvals,
        devices.devices,
        local.map { it.deviceListRead }.distinctUntilChanged(),
    ) { l, ds, read ->
        Renewal.sessionEnd(l.firstOrNull { it.key == key } ?: local.value.approval, ds, read)
    }

    /** Ticks at this request's expiry and at the end of the session it renews, so an open screen follows both. */
    private val clock = expiryClock(
        combine(repo.approvals, sessionEnd) { l, end -> listOfNotNull(l.firstOrNull { it.key == key }?.exp, end) },
        resumed,
    )

    /** The screen came back to the front: the time is read again (a sleep stops in deep sleep). */
    fun tick() {
        resumed.tryEmit(Unit)
    }

    /** A decision on a request that has expired by the clock: nothing is sent, the screen reads as expired. */
    private fun expiredNow(a: Approval): Boolean = a.expiredAt(Instant.now()).also { if (it) tick() }

    /** Whether the vault is held (§3.6.3): a session request leaves the screen, and a confirmation open ends nothing. */
    private fun isHeld(): Boolean = ownerCheck.ownerCheck.value?.state == OwnerCheckState.HELD

    val uiState: StateFlow<ApprovalDetailUiState> =
        combine(local, repo.approvals, clock, devices.devices, ownerCheck.ownerCheck) { s, list, now, ds, check ->
            val a = list.firstOrNull { it.key == key }
            val shown = a ?: s.approval
            // Gone from the list, or expired while open: both read as "Already decided … or it expired" (no actions).
            val over = (a == null || a.expiredAt(now)) && shown != null
            // The hold drops a session request (§3.6.3): it leaves the screen even before the list says so.
            val held = check?.state == OwnerCheckState.HELD && (shown as? Approval.DeviceRequest)?.isSession == true
            s.copy(
                approval = shown,
                // While access is being ended the gone screen stays (it does not flicker back to the request).
                gone = (over || held) && !s.done && (!s.busy || s.ending) && s.criticalResult == null,
                held = held,
                // Past the session's end the request reads as a first request: no notice, only Deny.
                renewalUntil = Renewal.expiresAt(shown, ds, s.deviceListRead, now),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    init {
        // A failure about a session that then ended has nothing left to retry: cleared on that change only, so a failure
        // recorded after the session had already passed is still shown.
        viewModelScope.launch {
            var live = false
            // The triggers are the hold, the list's load state, the list and the clock's view of the session; the session
            // itself is read from `devices.devices` when one fires (the manager sets the list before it says LOADED), not
            // from `uiState`, which may not have caught up yet.
            combine(
                uiState.map { it.held },
                devices.load,
                devices.devices,
                uiState.map { it.renewalUntil != null },
            ) { held, load, _, _ -> held to (load == ListLoad.LOADED) }.collect { (held, loaded) ->
                // While held, or before the list is read again, nothing is known: the hold clears the list although the
                // session is only suspended. `live` stays as it was, so a real end during the hold is seen once it lifts.
                if (held || !loaded) return@collect
                val now = Renewal.expiresAt(local.value.approval, devices.devices.value, local.value.deviceListRead, Instant.now()) != null
                if (live && !now) local.update { it.copy(endFailure = null) }
                live = now
            }
        }
        // The hold closes a confirmation: nothing is ended while held (§6.8).
        viewModelScope.launch {
            uiState.map { it.held }.distinctUntilChanged().collect { held ->
                if (held) local.update { it.copy(confirmEnd = false) }
            }
        }
    }

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

    /**
     * [shown]: for a share decision, the item ids the screen showed when the member tapped. Only those are decided, so a
     * `share.pending` batch that lands just before the tap is not included or declined unseen (§10.12).
     */
    fun approve(shown: List<String>? = null) {
        val s = local.value.copy(approval = uiState.value.approval ?: local.value.approval)
        val a = s.approval ?: return
        if (!s.canApprove || expiredNow(a)) return
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
                    val ids = shown ?: a.items.map { it.itemId }
                    repo.decideShare(a.ruleId, include = ids - s.shareExcluded, decline = ids.filter { it in s.shareExcluded })
                }
                is Approval.DeviceRequest -> Unit
            }
        }
    }

    /** [shown]: as for [approve]; only the share items the screen showed are declined. */
    fun deny(shown: List<String>? = null) {
        val a = uiState.value.approval ?: return
        if (expiredNow(a)) return
        decide {
            when (a) {
                is Approval.ConnectionRequest -> repo.declineConnection(a.pendingId)
                is Approval.OutgoingRequest -> repo.declineOutgoing(a.connectionId)
                is Approval.Authentication -> repo.denyAuthentication(a.requestId)
                is Approval.GrantRequest -> repo.decideGrant(a.requestId, approve = false)
                is Approval.CriticalUse -> repo.denyCriticalUse(a.requestId)
                is Approval.ShareDecision -> repo.decideShare(a.ruleId, include = emptyList(), decline = shown ?: a.items.map { it.itemId })
                is Approval.DeviceRequest -> repo.declineDeviceRequest(a.key)
            }
        }
    }

    /** "Deny and end access now" on a renewal request: asks "End access for <device>?" first; nothing is sent yet. */
    fun askEndAccess() {
        val s = uiState.value
        // From the gone screen (the request left) only a failure's Try again, and only while the session is live.
        if (s.busy || s.held || s.renewalUntil == null) return
        if (s.gone && s.endFailure == null) return
        local.update { it.copy(confirmEnd = true, endFailure = null) }
    }

    fun dismissEndAccess() = local.update { it.copy(confirmEnd = false) }

    fun dismissEndFailure() = local.update { it.copy(endFailure = null) }

    /**
     * The member confirmed: `device.session.end{device_id}` alone (§6.8 step 3), never `device.session.deny` as well: an
     * end drops the pending request, and is audited and synced once, as an end. A confirmation already open stays and
     * ends access when confirmed, even after the session passed; nothing is sent while the vault is held. A failure
     * says so and leaves the request open; Try again asks the confirmation again.
     */
    fun endAccess() {
        val s = local.value
        if (!s.confirmEnd || s.busy || isHeld()) return
        val id = (uiState.value.approval as? Approval.DeviceRequest)?.sessionDeviceId ?: return
        local.update { it.copy(confirmEnd = false, busy = true, ending = uiState.value.gone, endFailure = null) }
        viewModelScope.launch {
            try {
                devices.endAccess(id)
                // The request left the list with the end (the repository declines it); the screen closes.
                local.update { it.copy(busy = false, ending = false, done = true) }
            } catch (e: VaultFailure) {
                local.update { it.copy(busy = false, ending = false, endFailure = EndFailure(e.kind, e.shownCode)) }
                // What the vault says now settles whether the device still has a session.
                devices.refreshQuietly()
            }
        }
    }

    fun block() {
        val a = uiState.value.approval as? Approval.ConnectionRequest ?: return
        local.update { it.copy(confirmBlock = false) }
        if (expiredNow(a)) return
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
                local.update {
                    it.copy(busy = false, error = e.kind, errorCode = e.shownCode, limit = e.limit, password = "", retryUntil = until)
                }
            }
        }
    }

    /** The member read the critical-use result. */
    fun finish() = local.update { it.copy(done = true) }
}
