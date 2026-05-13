package com.vettid.app.features.grants

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.features.feed.ApprovalNotificationKind
import com.vettid.app.features.feed.FeedNotificationService
import com.vettid.app.features.feed.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "DataGrantApprovalVM"

/**
 * Backs the full-screen approval prompt for an incoming data/secret
 * access request. Unlike critical-secret use and identity-verify
 * (both password-gated), regular grants only need explicit approve /
 * deny with the requested expiry + max-uses. The vault enforces those
 * server-side at fetch time.
 *
 * Approval sends `grant.approve` with the values the user picked (or
 * leaves the requester's defaults). Denial sends `grant.deny`.
 */
@HiltViewModel
class DataGrantApprovalViewModel @Inject constructor(
    private val grants: GrantsRepository,
    private val feedRepository: FeedRepository,
    private val notificationService: FeedNotificationService,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val requestId: String = savedStateHandle["requestId"] ?: ""
    val connectionId: String = savedStateHandle["connectionId"] ?: ""
    val itemKind: String = savedStateHandle["itemKind"] ?: ""
    val itemRef: String = savedStateHandle["itemRef"] ?: ""
    val itemLabel: String = savedStateHandle["itemLabel"] ?: ""
    val requestedMode: String = savedStateHandle["requestedMode"] ?: GrantModes.ONE_SHOT
    val requestedExpiresAt: Long = savedStateHandle["requestedExpiresAt"] ?: 0L
    val requestedMaxUses: Int = savedStateHandle["requestedMaxUses"] ?: 1
    val reason: String = savedStateHandle["reason"] ?: ""

    val peerName: String = resolvePeerName(connectionId)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private fun resolvePeerName(connID: String): String {
        if (connID.isBlank()) return "A connection"
        val connection = feedRepository.getCachedConnections()
            .firstOrNull { it.connectionId == connID } ?: return "A connection"
        val first = connection.peerProfile?.firstName.orEmpty().trim()
        val last = connection.peerProfile?.lastName.orEmpty().trim()
        val full = listOf(first, last).filter { it.isNotEmpty() }.joinToString(" ")
        if (full.isNotEmpty()) return full
        return connection.label.trim().takeIf { it.isNotEmpty() } ?: "A connection"
    }

    fun approve() {
        if (requestId.isEmpty()) {
            _state.value = State.Error("Missing request_id")
            return
        }
        viewModelScope.launch {
            _state.value = State.Submitting
            grants.approve(requestId, requestedExpiresAt, requestedMaxUses, requestedMode)
                .onSuccess {
                    notificationService.clearApprovalNotification(ApprovalNotificationKind.DataRequest, requestId)
                    _state.value = State.Approved
                }
                .onFailure {
                    Log.e(TAG, "approve", it)
                    _state.value = State.Error(it.message ?: "Approve failed")
                }
        }
    }

    fun deny() {
        if (requestId.isEmpty()) return
        viewModelScope.launch {
            _state.value = State.Submitting
            grants.deny(requestId, "")
                .onSuccess {
                    notificationService.clearApprovalNotification(ApprovalNotificationKind.DataRequest, requestId)
                    _state.value = State.Denied
                }
                .onFailure {
                    Log.e(TAG, "deny", it)
                    _state.value = State.Error(it.message ?: "Deny failed")
                }
        }
    }

    sealed class State {
        object Idle : State()
        object Submitting : State()
        object Approved : State()
        object Denied : State()
        data class Error(val message: String) : State()
    }
}
