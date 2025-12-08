package com.vettid.app.features.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.crypto.ConnectionCryptoManager
import com.vettid.app.core.crypto.ConnectionKeyPair
import com.vettid.app.core.network.ConnectionApiClient
import com.vettid.app.core.network.ConnectionInvitation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for creating connection invitations.
 *
 * Flow:
 * 1. User selects expiration time
 * 2. Create invitation via API
 * 3. Display QR code and share options
 * 4. Wait for acceptance or expiration
 */
@HiltViewModel
class CreateInvitationViewModel @Inject constructor(
    private val connectionApiClient: ConnectionApiClient,
    private val connectionCryptoManager: ConnectionCryptoManager
) : ViewModel() {

    private val _state = MutableStateFlow<CreateInvitationState>(CreateInvitationState.Idle)
    val state: StateFlow<CreateInvitationState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<CreateInvitationEffect>()
    val effects: SharedFlow<CreateInvitationEffect> = _effects.asSharedFlow()

    // Selected expiration time in minutes
    private val _expirationMinutes = MutableStateFlow(60)
    val expirationMinutes: StateFlow<Int> = _expirationMinutes.asStateFlow()

    // Store key pair temporarily until invitation is accepted
    private var pendingKeyPair: ConnectionKeyPair? = null

    /**
     * Available expiration options.
     */
    val expirationOptions = listOf(
        ExpirationOption(15, "15 minutes"),
        ExpirationOption(60, "1 hour"),
        ExpirationOption(1440, "24 hours")
    )

    /**
     * Set the expiration time.
     */
    fun setExpirationMinutes(minutes: Int) {
        _expirationMinutes.value = minutes
    }

    /**
     * Create a new connection invitation.
     */
    fun createInvitation() {
        viewModelScope.launch {
            _state.value = CreateInvitationState.Creating

            // Generate key pair for this invitation
            pendingKeyPair = connectionCryptoManager.generateConnectionKeyPair()

            connectionApiClient.createInvitation(_expirationMinutes.value).fold(
                onSuccess = { invitation ->
                    _state.value = CreateInvitationState.Created(
                        invitation = invitation,
                        expiresInSeconds = calculateRemainingSeconds(invitation.expiresAt)
                    )
                    startExpirationTimer(invitation.expiresAt)
                },
                onFailure = { error ->
                    pendingKeyPair?.clearPrivateKey()
                    pendingKeyPair = null
                    _state.value = CreateInvitationState.Error(
                        message = error.message ?: "Failed to create invitation"
                    )
                }
            )
        }
    }

    /**
     * Share the invitation via system share sheet.
     */
    fun shareInvitation() {
        val currentState = _state.value
        if (currentState is CreateInvitationState.Created) {
            viewModelScope.launch {
                _effects.emit(CreateInvitationEffect.ShareInvitation(
                    deepLink = currentState.invitation.deepLinkUrl,
                    displayName = currentState.invitation.creatorDisplayName
                ))
            }
        }
    }

    /**
     * Copy the invitation link to clipboard.
     */
    fun copyLink() {
        val currentState = _state.value
        if (currentState is CreateInvitationState.Created) {
            viewModelScope.launch {
                _effects.emit(CreateInvitationEffect.CopyToClipboard(
                    text = currentState.invitation.deepLinkUrl
                ))
            }
        }
    }

    /**
     * Get the pending key pair for when invitation is accepted.
     */
    fun getPendingKeyPair(): ConnectionKeyPair? = pendingKeyPair

    /**
     * Clear the pending key pair after use.
     */
    fun clearPendingKeyPair() {
        pendingKeyPair?.clearPrivateKey()
        pendingKeyPair = null
    }

    /**
     * Cancel the current invitation.
     */
    fun cancel() {
        clearPendingKeyPair()
        _state.value = CreateInvitationState.Idle
    }

    /**
     * Retry after error.
     */
    fun retry() {
        _state.value = CreateInvitationState.Idle
    }

    /**
     * Start countdown timer for expiration.
     */
    private fun startExpirationTimer(expiresAt: Long) {
        viewModelScope.launch {
            while (true) {
                val remaining = calculateRemainingSeconds(expiresAt)
                if (remaining <= 0) {
                    _state.value = CreateInvitationState.Expired
                    clearPendingKeyPair()
                    break
                }

                val currentState = _state.value
                if (currentState is CreateInvitationState.Created) {
                    _state.value = currentState.copy(expiresInSeconds = remaining)
                } else {
                    break
                }

                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun calculateRemainingSeconds(expiresAt: Long): Int {
        val now = System.currentTimeMillis()
        // Handle both seconds and milliseconds timestamps
        val expiresAtMillis = if (expiresAt < 10000000000L) expiresAt * 1000 else expiresAt
        return ((expiresAtMillis - now) / 1000).toInt().coerceAtLeast(0)
    }

    override fun onCleared() {
        super.onCleared()
        clearPendingKeyPair()
    }
}

// MARK: - State Types

/**
 * Create invitation state.
 */
sealed class CreateInvitationState {
    object Idle : CreateInvitationState()
    object Creating : CreateInvitationState()

    data class Created(
        val invitation: ConnectionInvitation,
        val expiresInSeconds: Int
    ) : CreateInvitationState()

    object Expired : CreateInvitationState()

    data class Error(val message: String) : CreateInvitationState()
}

/**
 * Expiration option for UI.
 */
data class ExpirationOption(
    val minutes: Int,
    val label: String
)

// MARK: - Effects

/**
 * One-time effects from the ViewModel.
 */
sealed class CreateInvitationEffect {
    data class ShareInvitation(
        val deepLink: String,
        val displayName: String
    ) : CreateInvitationEffect()

    data class CopyToClipboard(val text: String) : CreateInvitationEffect()
}
