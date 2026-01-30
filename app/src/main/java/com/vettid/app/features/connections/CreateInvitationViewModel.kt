package com.vettid.app.features.connections

import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.vettid.app.core.crypto.ConnectionCryptoManager
import com.vettid.app.core.crypto.ConnectionKeyPair
import com.vettid.app.core.nats.ConnectionsClient
import com.vettid.app.core.security.SecureClipboard
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.features.feed.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for creating connection invitations.
 *
 * Flow:
 * 1. User selects expiration time
 * 2. Create invitation via NATS to vault
 * 3. Display QR code and share options
 * 4. Wait for acceptance or expiration
 */
@HiltViewModel
class CreateInvitationViewModel @Inject constructor(
    private val connectionsClient: ConnectionsClient,
    private val connectionCryptoManager: ConnectionCryptoManager,
    private val credentialStore: CredentialStore,
    private val secureClipboard: SecureClipboard,
    private val feedRepository: FeedRepository
) : ViewModel() {

    companion object {
        private const val TAG = "CreateInvitationVM"
    }

    private val _state = MutableStateFlow<CreateInvitationState>(CreateInvitationState.Idle)
    val state: StateFlow<CreateInvitationState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<CreateInvitationEffect>()
    val effects: SharedFlow<CreateInvitationEffect> = _effects.asSharedFlow()

    // Selected expiration time in minutes (default: 15 minutes)
    private val _expirationMinutes = MutableStateFlow(15)
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
     * Create a new connection invitation via NATS to vault.
     */
    fun createInvitation() {
        viewModelScope.launch {
            _state.value = CreateInvitationState.Creating

            // Generate key pair for this invitation
            pendingKeyPair = connectionCryptoManager.generateConnectionKeyPair()

            // Get user's display name from profile (or use a default)
            val displayName = credentialStore.getUserGuid()?.take(8) ?: "VettID User"

            val expirationMinutes = _expirationMinutes.value
            Log.d(TAG, "Creating invitation via NATS, expires in $expirationMinutes minutes")

            // Create invitation via vault NATS handler
            // Note: peer_guid is set to "pending" since we don't know who will accept yet
            connectionsClient.createInvite(
                peerGuid = "pending",
                label = displayName,
                expiresInMinutes = expirationMinutes
            ).fold(
                onSuccess = { natsInvitation ->
                    Log.d(TAG, "Invitation created: ${natsInvitation.connectionId}")
                    Log.d(TAG, "DEBUG - NATS creds length: ${natsInvitation.natsCredentials.length}")
                    Log.d(TAG, "DEBUG - owner_space: ${natsInvitation.ownerSpaceId}")
                    Log.d(TAG, "DEBUG - message_space: ${natsInvitation.messageSpaceId}")
                    Log.d(TAG, "DEBUG - expires_at: ${natsInvitation.expiresAt}")

                    // Build QR code data for sharing
                    val qrData = buildQrCodeData(natsInvitation)
                    Log.i(TAG, "DEBUG - QR data: $qrData")
                    val deepLink = buildDeepLink(natsInvitation)

                    // Parse expiration timestamp
                    val expiresAtMillis = parseIsoTimestamp(natsInvitation.expiresAt)

                    val invitation = VaultConnectionInvitation(
                        connectionId = natsInvitation.connectionId,
                        natsCredentials = natsInvitation.natsCredentials,
                        ownerSpaceId = natsInvitation.ownerSpaceId,
                        messageSpaceId = natsInvitation.messageSpaceId,
                        expiresAt = expiresAtMillis,
                        creatorDisplayName = displayName,
                        qrCodeData = qrData,
                        deepLinkUrl = deepLink
                    )

                    _state.value = CreateInvitationState.Created(
                        invitation = invitation,
                        expiresInSeconds = calculateRemainingSeconds(expiresAtMillis)
                    )
                    startExpirationTimer(expiresAtMillis)

                    // Trigger feed sync so the new invitation appears in activity feed
                    viewModelScope.launch {
                        feedRepository.sync()
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to create invitation: ${error.message}")
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
     * Build QR code data from invitation.
     */
    private fun buildQrCodeData(invitation: com.vettid.app.core.nats.ConnectionInvitation): String {
        val data = mapOf(
            "type" to "vettid_connection",
            "version" to 1,
            "connection_id" to invitation.connectionId,
            "credentials" to invitation.natsCredentials,
            "owner_space" to invitation.ownerSpaceId,
            "message_space" to invitation.messageSpaceId,
            "expires_at" to invitation.expiresAt
        )
        return Gson().toJson(data)
    }

    /**
     * Build deep link URL from invitation.
     */
    private fun buildDeepLink(invitation: com.vettid.app.core.nats.ConnectionInvitation): String {
        // Encode invitation data in base64 for the deep link
        val qrData = buildQrCodeData(invitation)
        val encoded = Base64.encodeToString(qrData.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return "https://vettid.com/connect?data=$encoded"
    }

    /**
     * Parse ISO 8601 timestamp to milliseconds.
     */
    private fun parseIsoTimestamp(timestamp: String): Long {
        return try {
            java.time.Instant.parse(timestamp).toEpochMilli()
        } catch (e: Exception) {
            // Fallback: assume it's already in milliseconds or seconds
            timestamp.toLongOrNull()?.let {
                if (it < 10000000000L) it * 1000 else it
            } ?: (System.currentTimeMillis() + 3600000) // Default 1 hour
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
                    displayName = currentState.invitation.creatorDisplayName ?: "VettID User"
                ))
            }
        }
    }

    /**
     * Copy the invitation link to clipboard securely.
     * Uses SecureClipboard which auto-clears after 30 seconds.
     */
    fun copyLink() {
        val currentState = _state.value
        if (currentState is CreateInvitationState.Created) {
            // Use SecureClipboard for auto-clear after timeout
            secureClipboard.copyText(
                text = currentState.invitation.deepLinkUrl,
                isSensitive = false // Invitation links are not highly sensitive
            )
            viewModelScope.launch {
                _effects.emit(CreateInvitationEffect.LinkCopied)
            }
        }
    }

    /**
     * Get QR code data for display.
     */
    fun getQrCodeData(): String? {
        val currentState = _state.value
        return if (currentState is CreateInvitationState.Created) {
            currentState.invitation.qrCodeData
        } else null
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
        val invitation: VaultConnectionInvitation,
        val expiresInSeconds: Int
    ) : CreateInvitationState()

    object Expired : CreateInvitationState()

    data class Error(val message: String) : CreateInvitationState()
}

/**
 * Connection invitation created via vault NATS handler.
 */
data class VaultConnectionInvitation(
    val connectionId: String,
    val natsCredentials: String,
    val ownerSpaceId: String,
    val messageSpaceId: String,
    val expiresAt: Long,
    val creatorDisplayName: String?,
    val qrCodeData: String,
    val deepLinkUrl: String
)

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

    /** Link was copied to clipboard (auto-clears after 30 seconds) */
    object LinkCopied : CreateInvitationEffect()
}
