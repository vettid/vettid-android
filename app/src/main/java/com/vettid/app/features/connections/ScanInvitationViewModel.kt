package com.vettid.app.features.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.crypto.ConnectionCryptoManager
import com.vettid.app.core.nats.ConnectionsClient
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.network.Connection
import com.vettid.app.core.network.ConnectionStatus
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.core.storage.PersonalDataStore
import com.google.gson.Gson
import com.google.gson.JsonObject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for scanning and accepting connection invitations.
 *
 * Flow (NATS-based):
 * 1. Scan QR code containing peer's NATS credentials and space IDs
 * 2. Store credentials via vault handler to enable messaging
 * 3. Connection is immediately available for communication
 *
 * Note: X25519 key exchange happens within vault JetStream, not during invitation acceptance.
 */
@HiltViewModel
class ScanInvitationViewModel @Inject constructor(
    private val connectionsClient: ConnectionsClient,
    private val natsAutoConnector: NatsAutoConnector,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val connectionCryptoManager: ConnectionCryptoManager,
    private val credentialStore: CredentialStore,
    private val personalDataStore: PersonalDataStore
) : ViewModel() {

    private val _state = MutableStateFlow<ScanInvitationState>(ScanInvitationState.Scanning)
    val state: StateFlow<ScanInvitationState> = _state.asStateFlow()

    private val _manualCode = MutableStateFlow("")
    val manualCode: StateFlow<String> = _manualCode.asStateFlow()

    private val _effects = MutableSharedFlow<ScanInvitationEffect>()
    val effects: SharedFlow<ScanInvitationEffect> = _effects.asSharedFlow()

    private val gson = Gson()

    // Parsed invitation data (from QR)
    private var parsedInvitation: ConnectionInvitationData? = null
    // Fetched peer profile (for passing to storeCredentials)
    private var fetchedPeerProfile: Map<String, String>? = null

    /**
     * Called when QR code is scanned.
     */
    fun onQrCodeScanned(data: String) {
        android.util.Log.d("ScanInvitationVM", "onQrCodeScanned called with data length: ${data.length}")

        viewModelScope.launch {
            try {
                val json = gson.fromJson(data, JsonObject::class.java)

                // Compact broker format: {"c":"<invite_code>","e":"<nats_endpoint>"}
                if (json.has("c")) {
                    val inviteCode = json.get("c").asString
                    android.util.Log.d("ScanInvitationVM", "Compact QR detected, resolving invite code: $inviteCode")
                    _state.value = ScanInvitationState.Processing
                    resolveInviteCode(inviteCode)
                    return@launch
                }

                // Full inline format (fallback)
                val invitation = parseInvitationData(data)
                if (invitation != null) {
                    android.util.Log.d("ScanInvitationVM", "Parsed invitation: connectionId=${invitation.connectionId}")
                    parsedInvitation = invitation
                    _state.value = ScanInvitationState.Processing
                    fetchAndShowPeerProfile(invitation)
                } else {
                    _effects.emit(ScanInvitationEffect.ShowError("Invalid QR code"))
                }
            } catch (e: Exception) {
                android.util.Log.e("ScanInvitationVM", "Failed to parse QR data: ${e.message}")
                _effects.emit(ScanInvitationEffect.ShowError("Invalid QR code"))
            }
        }
    }

    /**
     * Resolve an invite code by asking our vault to fetch from the NATS broker.
     */
    private suspend fun resolveInviteCode(inviteCode: String) {
        connectionsClient.resolveInvite(inviteCode).fold(
            onSuccess = { invitation ->
                android.util.Log.d("ScanInvitationVM", "Invite resolved: connectionId=${invitation.connectionId}, profile keys=${invitation.inviterProfile?.keys}")
                parsedInvitation = ConnectionInvitationData(
                    connectionId = invitation.connectionId,
                    peerGuid = invitation.ownerSpaceId,
                    label = invitation.label.ifEmpty { "Unknown" },
                    natsCredentials = invitation.natsCredentials,
                    ownerSpaceId = invitation.ownerSpaceId,
                    messageSpaceId = invitation.messageSpaceId,
                    natsEndpoint = credentialStore.getNatsEndpoint()
                )

                // Use inviter profile from broker payload if available
                val profile = invitation.inviterProfile
                if (!profile.isNullOrEmpty()) {
                    android.util.Log.d("ScanInvitationVM", "Using profile from broker: ${profile.keys}")
                    fetchedPeerProfile = profile
                    showProfilePreview(profile, invitation.label)
                } else {
                    // No profile in broker — show preview with label immediately,
                    // then try async JetStream fetch as enhancement
                    android.util.Log.d("ScanInvitationVM", "No profile in broker, showing label: ${invitation.label}")
                    _state.value = ScanInvitationState.Preview(
                        creatorName = invitation.label.ifEmpty { "VettID User" },
                        creatorAvatarUrl = null
                    )
                    // Try JetStream fetch in background to enhance the preview
                    fetchAndShowPeerProfile(parsedInvitation!!)
                }
            },
            onFailure = { error ->
                android.util.Log.e("ScanInvitationVM", "Failed to resolve invite: ${error.message}")
                _state.value = ScanInvitationState.Error(
                    message = error.message ?: "Failed to resolve invitation"
                )
            }
        )
    }

    /**
     * Show profile preview from a profile map (e.g., from broker payload).
     */
    private fun showProfilePreview(profile: Map<String, String>, fallbackLabel: String) {
        val displayName = listOfNotNull(
            profile["_system_first_name"],
            profile["_system_last_name"]
        ).joinToString(" ").trim().ifEmpty { fallbackLabel }

        val internalKeys = setOf("photo", "user_guid", "public_key")
        val profileFields = mutableMapOf<String, Map<String, String>>()
        profile.forEach { (key, value) ->
            if (!key.startsWith("_system_") && key !in internalKeys && value.isNotBlank()) {
                val fieldDisplayName = key.substringAfterLast(".")
                    .replace("_", " ")
                    .replaceFirstChar { it.titlecase() }
                profileFields[key] = mapOf(
                    "display_name" to fieldDisplayName,
                    "value" to value
                )
            }
        }

        _state.value = ScanInvitationState.Preview(
            creatorName = displayName,
            creatorAvatarUrl = null,
            creatorEmail = profile["_system_email"],
            creatorPhoto = profile["photo"],
            profileFields = profileFields.ifEmpty { null }
        )
    }

    /**
     * Fetch the peer's published profile via NATS using invitation credentials,
     * then transition to Preview state with real profile data.
     */
    private suspend fun fetchAndShowPeerProfile(invitation: ConnectionInvitationData) {
        val endpoint = invitation.natsEndpoint
            ?: credentialStore.getNatsEndpoint()

        if (endpoint == null || invitation.natsCredentials.isEmpty()) {
            android.util.Log.w("ScanInvitationVM", "No NATS endpoint or credentials")
            _state.value = ScanInvitationState.Preview(
                creatorName = invitation.label,
                creatorAvatarUrl = null
            )
            return
        }

        android.util.Log.d("ScanInvitationVM", "Fetching peer profile from $endpoint for ${invitation.ownerSpaceId}")

        connectionsClient.fetchPeerProfile(
            natsCredentials = invitation.natsCredentials,
            natsEndpoint = endpoint,
            ownerSpace = invitation.ownerSpaceId
        ).fold(
            onSuccess = { profile ->
                android.util.Log.d("ScanInvitationVM", "Peer profile fetched: ${profile.keys}")
                fetchedPeerProfile = profile
                val displayName = listOfNotNull(
                    profile["_system_first_name"],
                    profile["_system_last_name"]
                ).joinToString(" ").trim().ifEmpty { invitation.label }

                // Build profile fields from non-system, non-internal entries
                val internalKeys = setOf("photo", "user_guid", "public_key")
                val profileFields = mutableMapOf<String, Map<String, String>>()
                profile.forEach { (key, value) ->
                    if (!key.startsWith("_system_") && key !in internalKeys && value.isNotBlank()) {
                        // For dotted keys like "other.custom.mini", use last segment as display name
                        val displayName = key.substringAfterLast(".")
                            .replace("_", " ")
                            .replaceFirstChar { it.titlecase() }
                        profileFields[key] = mapOf(
                            "display_name" to displayName,
                            "value" to value
                        )
                    }
                }

                _state.value = ScanInvitationState.Preview(
                    creatorName = displayName,
                    creatorAvatarUrl = null,
                    creatorEmail = profile["_system_email"],
                    creatorPhoto = profile["photo"],
                    profileFields = profileFields.ifEmpty { null }
                )
            },
            onFailure = { error ->
                android.util.Log.w("ScanInvitationVM", "Failed to fetch peer profile: ${error.message}")
                _state.value = ScanInvitationState.Preview(
                    creatorName = invitation.label,
                    creatorAvatarUrl = null
                )
            }
        )
    }

    /**
     * Update manual code input.
     */
    fun onManualCodeChanged(code: String) {
        _manualCode.value = code
    }

    /**
     * Submit manually entered code.
     * Note: Manual entry is limited - full invitation data is in QR code.
     */
    fun onManualCodeEntered() {
        viewModelScope.launch {
            _effects.emit(ScanInvitationEffect.ShowError(
                "Please scan the QR code to accept an invitation"
            ))
        }
    }

    /**
     * Process the invitation by storing credentials via NATS vault handler.
     */
    private fun processInvitation(invitation: ConnectionInvitationData) {
        android.util.Log.d("ScanInvitationVM", "processInvitation called for ${invitation.connectionId}")
        viewModelScope.launch {
            _state.value = ScanInvitationState.Processing

            // Wait for NATS connection (with timeout)
            android.util.Log.d("ScanInvitationVM", "Waiting for NATS connection...")

            var attempts = 0
            val maxAttempts = 30 // 15 seconds (500ms each)
            while (attempts < maxAttempts) {
                if (natsAutoConnector.isConnected()) {
                    break
                }
                android.util.Log.d("ScanInvitationVM", "Check #$attempts: connected=false")
                kotlinx.coroutines.delay(500)
                attempts++
            }

            if (!natsAutoConnector.isConnected()) {
                android.util.Log.e("ScanInvitationVM", "NATS connection timeout")
                _state.value = ScanInvitationState.Error(
                    message = "Not connected to vault - please try again"
                )
                return@launch
            }

            android.util.Log.d("ScanInvitationVM", "NATS connected and E2E enabled, checking for duplicates...")
            _state.value = ScanInvitationState.Processing

            // Check for duplicate connections before storing
            connectionsClient.list().fold(
                onSuccess = { listResult ->
                    val existingById = listResult.items.find { it.connectionId == invitation.connectionId }
                    if (existingById != null) {
                        _state.value = ScanInvitationState.Error(
                            message = "This invitation has already been accepted"
                        )
                        return@launch
                    }
                    val existingByPeer = listResult.items.find {
                        it.peerGuid == invitation.peerGuid && it.status != "revoked" && it.status != "rejected"
                    }
                    if (existingByPeer != null) {
                        _state.value = ScanInvitationState.Error(
                            message = "You already have a connection with this user"
                        )
                        return@launch
                    }
                },
                onFailure = { error ->
                    android.util.Log.w("ScanInvitationVM", "Could not check for duplicates: ${error.message}")
                    // Continue anyway - vault will reject duplicates server-side
                }
            )

            // Store the peer's credentials via vault handler
            connectionsClient.storeCredentials(
                connectionId = invitation.connectionId,
                peerGuid = invitation.peerGuid,
                label = invitation.label,
                natsCredentials = invitation.natsCredentials,
                peerOwnerSpaceId = invitation.ownerSpaceId,
                peerMessageSpaceId = invitation.messageSpaceId,
                peerProfile = fetchedPeerProfile
            ).fold(
                onSuccess = { connectionRecord ->
                    // Acceptance notification is handled by the vault in HandleStoreCredentials
                    android.util.Log.i("ScanInvitationVM", "Connection stored, vault will notify peer")

                    val connection = Connection(
                        connectionId = connectionRecord.connectionId,
                        peerGuid = connectionRecord.peerGuid,
                        peerDisplayName = connectionRecord.label,
                        peerAvatarUrl = null,
                        status = ConnectionStatus.PENDING,
                        createdAt = System.currentTimeMillis(),
                        lastMessageAt = null,
                        unreadCount = 0
                    )
                    _state.value = ScanInvitationState.Success(connection = connection)
                    // Auto-navigate back after brief display
                    _effects.emit(ScanInvitationEffect.NavigateToConnection(connectionRecord.connectionId))
                },
                onFailure = { error ->
                    _state.value = ScanInvitationState.Error(
                        message = parseError(error)
                    )
                }
            )
        }
    }

    /**
     * Accept the previewed invitation.
     */
    fun acceptInvitation() {
        val invitation = parsedInvitation
        if (invitation != null && _state.value is ScanInvitationState.Preview) {
            processInvitation(invitation)
        }
    }

    /**
     * Decline the previewed invitation.
     */
    fun declineInvitation() {
        parsedInvitation = null
        _state.value = ScanInvitationState.Scanning
    }

    /**
     * Retry after error.
     */
    fun retry() {
        parsedInvitation = null
        _manualCode.value = ""
        _state.value = ScanInvitationState.Scanning
    }

    /**
     * Switch to manual code entry.
     */
    fun switchToManualEntry() {
        _state.value = ScanInvitationState.ManualEntry
    }

    /**
     * Switch to scanner.
     */
    fun switchToScanner() {
        _state.value = ScanInvitationState.Scanning
    }

    /**
     * Parse invitation data from QR code.
     *
     * Format:
     * {
     *   "type": "vettid_connection",
     *   "connection_id": "conn-xxx",
     *   "jwt": "<NATS user JWT>",
     *   "seed": "<NATS user seed>",
     *   "owner_space": "...",
     *   "message_space": "...",
     *   "expires_at": "...",
     *   "nats_endpoint": "..."
     * }
     *
     * Scanner uses the JWT+seed to connect to inviter's message space
     * and fetch their published profile via NATS.
     */
    private fun parseInvitationData(data: String): ConnectionInvitationData? {
        return try {
            val json = gson.fromJson(data, JsonObject::class.java)

            val type = json.get("type")?.asString
            if (type != null && type != "vettid_connection") {
                return null
            }

            val connectionId = json.get("connection_id")?.asString ?: return null
            val jwt = json.get("jwt")?.asString ?: return null
            val seed = json.get("seed")?.asString ?: return null
            val ownerSpaceId = json.get("owner_space")?.asString ?: return null
            val messageSpaceId = json.get("message_space")?.asString ?: return null
            val natsEndpoint = json.get("nats_endpoint")?.asString

            // Reconstruct .creds format for NATS client
            val natsCredentials = "-----BEGIN NATS USER JWT-----\n$jwt\n------END NATS USER JWT------\n\n-----BEGIN USER NKEY SEED-----\n$seed\n------END USER NKEY SEED------\n"

            val peerGuid = extractUserGuidFromMessageSpace(messageSpaceId) ?: "unknown"

            ConnectionInvitationData(
                connectionId = connectionId,
                peerGuid = peerGuid,
                label = peerGuid.take(8),
                natsCredentials = natsCredentials,
                ownerSpaceId = ownerSpaceId,
                messageSpaceId = messageSpaceId,
                natsEndpoint = natsEndpoint
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract user GUID from message space ID.
     * Format: MessageSpace.<guid>.forOwner.> -> <guid>
     */
    private fun extractUserGuidFromMessageSpace(messageSpace: String): String? {
        // Pattern: MessageSpace.<anything>.forOwner
        val regex = Regex("MessageSpace\\.([^.]+)\\.forOwner")
        val match = regex.find(messageSpace)
        return match?.groupValues?.get(1)?.let { guid ->
            guid
        }
    }

    /**
     * Parse error for user-friendly display.
     */
    private fun parseError(error: Throwable): String {
        val message = error.message ?: "Unknown error"

        return when {
            message.contains("expired", ignoreCase = true) -> "This invitation has expired"
            message.contains("already", ignoreCase = true) -> "This invitation has already been used"
            message.contains("not found", ignoreCase = true) -> "Invalid invitation code"
            message.contains("revoked", ignoreCase = true) -> "This invitation has been revoked"
            message.contains("self", ignoreCase = true) -> "You cannot connect with yourself"
            message.contains("not connected", ignoreCase = true) -> "Not connected to vault"
            else -> message
        }
    }
}

/**
 * Parsed connection invitation data from QR code.
 */
private data class ConnectionInvitationData(
    val connectionId: String,
    val peerGuid: String,
    val label: String,
    val natsCredentials: String,
    val ownerSpaceId: String,
    val messageSpaceId: String,
    val natsEndpoint: String? = null
)

// MARK: - State Types

/**
 * Scan invitation state.
 */
sealed class ScanInvitationState {
    object Scanning : ScanInvitationState()
    object ManualEntry : ScanInvitationState()
    object Processing : ScanInvitationState()

    data class Preview(
        val creatorName: String,
        val creatorAvatarUrl: String?,
        val creatorEmail: String? = null,
        val creatorPhoto: String? = null,
        val isEmailVerified: Boolean = false,
        val publicKeyFingerprint: String? = null,
        val trustLevel: String = "New",
        val capabilities: List<String> = emptyList(),
        val sharedDataCategories: List<String> = emptyList(),
        val profileFields: Map<String, Map<String, String>>? = null
    ) : ScanInvitationState()

    data class Success(
        val connection: Connection
    ) : ScanInvitationState()

    data class Error(val message: String) : ScanInvitationState()
}

// MARK: - Effects

/**
 * One-time effects from the ViewModel.
 */
sealed class ScanInvitationEffect {
    data class ShowError(val message: String) : ScanInvitationEffect()
    data class NavigateToConnection(val connectionId: String) : ScanInvitationEffect()
}
