package com.vettid.app.features.connections

import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.vettid.app.core.crypto.ConnectionCryptoManager
import com.vettid.app.core.crypto.ConnectionKeyPair
import com.vettid.app.core.nats.ConnectionsClient
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.security.SecureClipboard
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.features.feed.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
    private val feedRepository: FeedRepository,
    private val ownerSpaceClient: OwnerSpaceClient
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

    // Track expiration timer to cancel on new invitation
    private var expirationTimerJob: Job? = null

    /**
     * Available expiration options.
     */
    val expirationOptions = listOf(
        ExpirationOption(5, "5 minutes"),
        ExpirationOption(15, "15 minutes"),
        ExpirationOption(60, "1 hour")
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

            // Send empty label - vault will use profile name (first + last name)
            val displayName = ""

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
                    Log.d(TAG, "DEBUG - inviter_profile fields: ${natsInvitation.inviterProfile.keys}")

                    // Use vault profile for display name if available
                    val profile = natsInvitation.inviterProfile
                    val vaultDisplayName = listOfNotNull(
                        profile["_system_first_name"],
                        profile["_system_last_name"]
                    ).joinToString(" ").trim().ifEmpty { displayName }

                    // Build QR code data for sharing
                    val qrData = buildQrCodeData(natsInvitation)
                    Log.i(TAG, "DEBUG - QR data length: ${qrData.length}")
                    val deepLink = buildDeepLink(natsInvitation)

                    // Parse expiration timestamp
                    val expiresAtMillis = parseIsoTimestamp(natsInvitation.expiresAt)

                    val invitation = VaultConnectionInvitation(
                        connectionId = natsInvitation.connectionId,
                        natsCredentials = natsInvitation.natsCredentials,
                        ownerSpaceId = natsInvitation.ownerSpaceId,
                        messageSpaceId = natsInvitation.messageSpaceId,
                        expiresAt = expiresAtMillis,
                        creatorDisplayName = vaultDisplayName,
                        qrCodeData = qrData,
                        deepLinkUrl = deepLink,
                        inviteCode = natsInvitation.inviteCode
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

                    // Listen for peer acceptance while QR is showing
                    viewModelScope.launch {
                        ownerSpaceClient.connectionAcceptances.collect { accepted ->
                            if (accepted.connectionId == natsInvitation.connectionId) {
                                // Show immediately with what we have
                                _state.value = CreateInvitationState.PeerAccepted(
                                    peerAlias = accepted.peerAlias ?: "Someone",
                                    connectionId = accepted.connectionId,
                                    peerPhoto = accepted.peerPhoto,
                                    peerEmail = accepted.peerProfile?.get("_system_email"),
                                    peerFields = accepted.peerFields
                                )
                                // Then reload connection list to get full profile (photo, fields)
                                connectionsClient.list().fold(
                                    onSuccess = { listResult ->
                                        val record = listResult.items.find {
                                            it.connectionId == accepted.connectionId
                                        }
                                        if (record?.peerProfile != null) {
                                            val pp = record.peerProfile!!
                                            _state.value = CreateInvitationState.PeerAccepted(
                                                peerAlias = accepted.peerAlias ?: record.label,
                                                connectionId = accepted.connectionId,
                                                peerPhoto = pp.photo ?: accepted.peerPhoto,
                                                peerEmail = pp.email
                                                    ?: accepted.peerProfile?.get("_system_email"),
                                                peerFields = pp.fields ?: accepted.peerFields
                                            )
                                        }
                                    },
                                    onFailure = { /* Keep what we have */ }
                                )
                            }
                        }
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
     * When invite_code is available (broker published), QR is just code + endpoint (~65 chars).
     * The scanner's vault resolves the code to get full credentials.
     */
    private fun buildQrCodeData(invitation: com.vettid.app.core.nats.ConnectionInvitation): String {
        if (invitation.inviteCode.isNotEmpty()) {
            // Compact broker format — scanner resolves via vault
            val data = mutableMapOf<String, Any>(
                "c" to invitation.inviteCode
            )
            credentialStore.getNatsEndpoint()?.let { endpoint ->
                data["e"] = endpoint
            }
            return Gson().toJson(data)
        }

        // Fallback: inline credentials (if broker publish failed)
        val data = mutableMapOf<String, Any>(
            "type" to "vettid_connection",
            "connection_id" to invitation.connectionId,
            "owner_space" to invitation.ownerSpaceId,
            "message_space" to invitation.messageSpaceId,
            "expires_at" to invitation.expiresAt
        )
        credentialStore.getNatsEndpoint()?.let { endpoint ->
            data["nats_endpoint"] = endpoint
        }
        return Gson().toJson(data)
    }

    /**
     * Build deep link URL from invitation.
     *
     * https://vettid.dev/connect?c=<invite_code>
     *
     *   - https:// so email clients auto-linkify it (the vettid://
     *     custom scheme gets rendered as plain text in most mail
     *     apps and the recipient can't tap it).
     *   - The App Link intent filter on the connect path opens the
     *     app directly on devices where it's installed.
     *   - Just the short broker code (~16 chars) — the scanner's
     *     vault resolves it via the INVITATIONS NATS stream to get
     *     the full credentials, so we don't need to stuff base64
     *     JSON into the URL. Keeps the link short and paste-friendly.
     *
     * The fallback path (inline credentials, when the broker publish
     * failed) still ships the full base64 blob under `?data=` since
     * there's no broker code to resolve.
     */
    private fun buildDeepLink(invitation: com.vettid.app.core.nats.ConnectionInvitation): String {
        if (invitation.inviteCode.isNotEmpty()) {
            return "https://vettid.dev/connect?c=${invitation.inviteCode}"
        }
        val qrData = buildQrCodeData(invitation)
        val encoded = Base64.encodeToString(qrData.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return "https://vettid.dev/connect?data=$encoded"
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
     * Copy just the raw broker code (e.g. "ABCD-EFGH") to the
     * clipboard so the peer can paste it into the manual-entry
     * field. Separate from copyLink() because users who want to
     * dictate or type the code don't need the full URL.
     */
    fun copyInviteCode() {
        val currentState = _state.value
        if (currentState is CreateInvitationState.Created) {
            val code = currentState.invitation.inviteCode
            if (code.isEmpty()) return
            secureClipboard.copyText(text = code, isSensitive = false)
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
     * Accept or reject the peer's connection from the QR screen.
     */
    fun respondToConnection(accept: Boolean) {
        val currentState = _state.value as? CreateInvitationState.PeerAccepted ?: return
        viewModelScope.launch {
            val response = if (accept) "accept" else "reject"
            connectionsClient.respond(currentState.connectionId, response).fold(
                onSuccess = {
                    Log.i(TAG, "Connection ${if (accept) "accepted" else "rejected"}")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to respond: ${error.message}")
                }
            )
        }
    }

    /**
     * Cancel the current invitation.
     */
    fun cancel() {
        expirationTimerJob?.cancel()
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
        expirationTimerJob?.cancel()
        expirationTimerJob = viewModelScope.launch {
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
        expirationTimerJob?.cancel()
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

    data class PeerAccepted(
        val peerAlias: String,
        val connectionId: String,
        val peerPhoto: String? = null,
        val peerEmail: String? = null,
        val peerFields: Map<String, Map<String, String>>? = null
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
    val deepLinkUrl: String,
    // Short broker code (e.g. "ABCD-EFGH") from the vault. Displayed
    // alongside the QR/link so the peer can type it manually if the
    // share-link path doesn't work (email filtering, paper-sharing,
    // etc.). Empty when the invitation fell back to inline creds.
    val inviteCode: String = ""
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
