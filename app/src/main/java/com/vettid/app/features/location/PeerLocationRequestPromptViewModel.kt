package com.vettid.app.features.location

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.ConnectionsClient
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.PeerLocationShareTransition
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Top-level handler for peer-location-request pings (V6).
 *
 * A peer can ask us to send our location once via the connection
 * card's "Request location" action. The vault forwards the ping as
 * a `connection.peer-location-requested` event on the OwnerSpaceClient
 * flow. We need the prompt visible no matter which screen the user
 * happens to be on when the ping arrives — pinning the prompt inside
 * ConnectionDetail (as the earlier draft did) meant requests landing
 * while the user was on the feed or any other screen were invisible.
 *
 * The flow:
 *  1. Vault publishes the request → OwnerSpaceClient routes it →
 *     this VM's queue.
 *  2. VettIDApp observes [pendingRequest] and shows an AlertDialog.
 *  3. User taps Send → [fulfill] calls OwnerSpaceClient.sendLocationOnce.
 *     User taps Ignore → [dismiss] clears it.
 *  4. If multiple requests come in fast, we surface them in FIFO order
 *     — the next one shows when the current is resolved.
 */
@HiltViewModel
class PeerLocationRequestPromptViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val connectionsClient: ConnectionsClient,
) : ViewModel() {

    /**
     * One incoming request. peerLabel is the connection alias (peer's
     * display name) when known; null when we couldn't resolve it (the
     * dialog falls back to "A connection" in that case).
     */
    data class PendingRequest(
        val connectionId: String,
        val peerLabel: String?,
        val requestedAt: String,
    )

    private val _pendingRequest = MutableStateFlow<PendingRequest?>(null)
    val pendingRequest: StateFlow<PendingRequest?> = _pendingRequest.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val queued = ArrayDeque<PendingRequest>()

    init {
        viewModelScope.launch {
            ownerSpaceClient.peerLocationTransitions.collect { transition ->
                if (transition.transition != PeerLocationShareTransition.Transition.REQUESTED) return@collect
                val request = PendingRequest(
                    connectionId = transition.connectionId,
                    peerLabel = resolvePeerLabel(transition.connectionId),
                    requestedAt = transition.at,
                )
                if (_pendingRequest.value == null) {
                    _pendingRequest.value = request
                } else if (_pendingRequest.value?.connectionId != request.connectionId) {
                    // Don't dedupe same-connection retries — drop the
                    // older one and queue the newer for the next turn.
                    queued += request
                }
            }
        }
    }

    /**
     * Send our latest cached location once (no continuous sharing
     * enabled). Routes through location.send-once on the vault.
     */
    fun fulfill() {
        val request = _pendingRequest.value ?: return
        viewModelScope.launch {
            _isSending.value = true
            try {
                ownerSpaceClient.sendLocationOnce(request.connectionId).onFailure {
                    Log.w(TAG, "sendLocationOnce failed: ${it.message}")
                }
            } finally {
                _isSending.value = false
                advance()
            }
        }
    }

    /** User chose to ignore — discard this request and move to the next queued one (if any). */
    fun dismiss() {
        advance()
    }

    private fun advance() {
        _pendingRequest.value = queued.removeFirstOrNull()
    }

    private suspend fun resolvePeerLabel(connectionId: String): String? {
        // Best-effort lookup via the connections list. The list call is
        // cheap (vault-side it's an in-memory read) and gives us the
        // freshest label even if the cached one is stale.
        return try {
            connectionsClient.list().getOrNull()
                ?.items?.firstOrNull { it.connectionId == connectionId }?.label
        } catch (e: Exception) {
            Log.w(TAG, "resolvePeerLabel failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "LocationRequestPrompt"
    }
}
