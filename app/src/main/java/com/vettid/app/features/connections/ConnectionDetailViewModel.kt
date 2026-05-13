package com.vettid.app.features.connections

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.vettid.app.core.crypto.ConnectionCryptoManager
import com.vettid.app.core.nats.ConnectionsClient
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import com.vettid.app.core.network.Connection
import com.vettid.app.core.network.ConnectionStatus
import com.vettid.app.core.network.Profile
import com.vettid.app.features.calling.CallManager
import com.vettid.app.features.calling.CallType
import com.vettid.app.features.grants.GrantsRepository
import com.vettid.app.features.personaldata.PublishedProfileData
import com.vettid.app.features.personaldata.peerProfileToPublishedProfileData
import com.vettid.app.core.nats.GrantEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for connection detail screen.
 *
 * Features:
 * - Display connection details and profile
 * - Revoke connection via NATS vault handler
 * - Navigate to messaging
 *
 * Note: Connection data comes from vault via NATS, not HTTP API.
 */
private const val TAG = "ConnectionDetailVM"

@HiltViewModel
class ConnectionDetailViewModel @Inject constructor(
    private val connectionsClient: ConnectionsClient,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val natsAutoConnector: NatsAutoConnector,
    private val connectionCryptoManager: ConnectionCryptoManager,
    private val callManager: CallManager,
    private val presenceAggregator: com.vettid.app.core.nats.PresenceAggregator,
    private val grants: GrantsRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _verifyResult = MutableStateFlow<VerifyResult?>(null)
    /**
     * Latest connection.authenticate result for this connection.
     * Surfaces on the detail screen as a Snackbar; UI clears it on
     * dismiss. Null = no recent challenge.
     */
    val verifyResult: StateFlow<VerifyResult?> = _verifyResult.asStateFlow()

    private val _verifying = MutableStateFlow(false)
    val verifying: StateFlow<Boolean> = _verifying.asStateFlow()

    /**
     * Persistent verify-identity state, hydrated from the vault and
     * updated on every AuthenticateResult. Outlives screen entries
     * (the vault is the source of truth) so the Detail row can render
     * "Verified 3 minutes ago" without a fresh challenge.
     */
    private val _verifyState = MutableStateFlow<com.vettid.app.features.grants.VerifyStatePayload?>(null)
    val verifyState: StateFlow<com.vettid.app.features.grants.VerifyStatePayload?> = _verifyState.asStateFlow()

    /**
     * Re-fetch the cached verify state from the vault. Called on
     * ON_RESUME so backgrounding + a verify result arriving while we
     * were away still produces a fresh row when the user returns.
     */
    fun refreshVerifyState() {
        viewModelScope.launch {
            grants.getVerifyState(connectionId)
                .onSuccess { _verifyState.value = it }
                .onFailure { Log.d(TAG, "verify-state refresh skipped: ${it.message}") }
        }
    }

    init {
        // Hydrate cached verify state on entry. Best-effort — a vault
        // miss leaves verifyState null and the UI shows "Not yet verified".
        refreshVerifyState()
        // Listen for connection.authenticate verdicts. Updates two
        // things: the transient Snackbar (still useful when the user is
        // looking at the Detail screen) AND the persistent state row
        // (which the row affordance reads on every recomposition).
        viewModelScope.launch {
            ownerSpaceClient.grantEvents.collect { ev ->
                if (ev is GrantEvent.AuthenticateResult && ev.connectionId == connectionId) {
                    _verifyResult.value = VerifyResult(
                        ok = ev.authenticated,
                        message = if (ev.authenticated) "Identity verified"
                            else "Verification failed: ${ev.failureReason.ifEmpty { "unknown" }}",
                    )
                    _verifying.value = false
                    val nowIso = java.time.Instant.now().toString()
                    val prior = _verifyState.value
                    _verifyState.value = (prior ?: com.vettid.app.features.grants.VerifyStatePayload(
                        lastOutboundAt = "",
                        lastOutboundOk = false,
                        lastOutboundReason = "",
                        lastInboundAt = "",
                        lastInboundOk = false,
                        lastInboundReason = "",
                    )).copy(
                        lastOutboundAt = nowIso,
                        lastOutboundOk = ev.authenticated,
                        lastOutboundReason = ev.failureReason,
                    )
                }
            }
        }
    }

    /** Fire a connection.authenticate challenge to this peer. */
    fun verifyIdentity() {
        if (_verifying.value) return
        viewModelScope.launch {
            _verifying.value = true
            grants.requestAuthenticate(connectionId, "user-initiated verification")
                .onFailure {
                    _verifyResult.value = VerifyResult(ok = false, message = it.message ?: "Verify failed")
                    _verifying.value = false
                }
        }
    }

    fun dismissVerifyResult() { _verifyResult.value = null }

    data class VerifyResult(val ok: Boolean, val message: String)

    private val connectionId: String = savedStateHandle["connectionId"]
        ?: throw IllegalArgumentException("connectionId is required")

    /**
     * Optional focus hint passed via the route's `focus` query param.
     * "verify" means the screen was opened from a verify-result OS
     * notification — the UI should select the Them tab and pulse the
     * verify row so the user sees what just happened. Cleared by the
     * UI once the focus animation finishes so it doesn't re-pulse on
     * configuration change.
     */
    private val _initialFocus = MutableStateFlow<String?>(
        savedStateHandle.get<String>("focus")?.takeIf { it.isNotBlank() }
    )
    val initialFocus: StateFlow<String?> = _initialFocus.asStateFlow()
    fun consumeFocus() { _initialFocus.value = null }

    private val _state = MutableStateFlow<ConnectionDetailState>(ConnectionDetailState.Loading)
    val state: StateFlow<ConnectionDetailState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ConnectionDetailEffect>()
    val effects: SharedFlow<ConnectionDetailEffect> = _effects.asSharedFlow()

    /**
     * Live online/offline indicator for this connection — derived
     * from the presence aggregator's heartbeat map and recomputed
     * whenever it changes. Drives the green ring on the peer's hero
     * avatar so the same signal that paints the connections list
     * card is visible inside the detail screen.
     */
    val isPeerOnline: StateFlow<Boolean> = presenceAggregator.online
        .map { it.containsKey(connectionId) && presenceAggregator.isOnline(connectionId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Peer profile data from cached vault storage
    private val _peerPhoto = MutableStateFlow<String?>(null)
    val peerPhoto: StateFlow<String?> = _peerPhoto.asStateFlow()

    private val _peerEmail = MutableStateFlow<String?>(null)
    val peerEmail: StateFlow<String?> = _peerEmail.asStateFlow()

    private val _peerFields = MutableStateFlow<Map<String, Map<String, String>>?>(null)
    val peerFields: StateFlow<Map<String, Map<String, String>>?> = _peerFields.asStateFlow()

    private val _peerPublicKey = MutableStateFlow<String?>(null)
    val peerPublicKey: StateFlow<String?> = _peerPublicKey.asStateFlow()

    private val _peerUserGuid = MutableStateFlow<String?>(null)
    val peerUserGuid: StateFlow<String?> = _peerUserGuid.asStateFlow()

    private val _peerIdentityKey = MutableStateFlow<String?>(null)
    val peerIdentityKey: StateFlow<String?> = _peerIdentityKey.asStateFlow()

    private val _peerWallets = MutableStateFlow<List<com.vettid.app.core.nats.PeerWalletInfo>>(emptyList())
    val peerWallets: StateFlow<List<com.vettid.app.core.nats.PeerWalletInfo>> = _peerWallets.asStateFlow()

    // Peer's published handler catalog (vault capabilities). Fuels
    // the "Handlers" badge on the profile view; empty for older
    // vaults that don't publish their catalog.
    private val _peerHandlers = MutableStateFlow<List<com.vettid.app.core.nats.PeerHandlerInfo>>(emptyList())
    val peerHandlers: StateFlow<List<com.vettid.app.core.nats.PeerHandlerInfo>> = _peerHandlers.asStateFlow()

    // Peer's public-secret metadata (name/type/category — never
    // values). Fuels the "Secrets" badge on the profile view; empty
    // when the peer hasn't opted anything into public sharing.
    private val _peerPublicSecrets = MutableStateFlow<List<com.vettid.app.core.nats.PeerPublicSecretMetadata>>(emptyList())
    val peerPublicSecrets: StateFlow<List<com.vettid.app.core.nats.PeerPublicSecretMetadata>> = _peerPublicSecrets.asStateFlow()

    private val _peerDataCatalog = MutableStateFlow<List<com.vettid.app.core.nats.PeerDataCatalogEntry>?>(null)
    val peerDataCatalog: StateFlow<List<com.vettid.app.core.nats.PeerDataCatalogEntry>?> = _peerDataCatalog.asStateFlow()

    private val _peerSecretCatalog = MutableStateFlow<List<com.vettid.app.core.nats.PeerPublicSecretMetadata>?>(null)
    val peerSecretCatalog: StateFlow<List<com.vettid.app.core.nats.PeerPublicSecretMetadata>?> = _peerSecretCatalog.asStateFlow()

    // Peer profile rendered through the same BusinessCardView the user
    // sees for their own public-profile preview — gives one canonical
    // layout across scanner review, inviter review, and this detail
    // screen. Rebuilt each time the connection record reloads.
    private val _peerPublishedProfile = MutableStateFlow(
        PublishedProfileData(items = emptyList(), isFromVault = false)
    )
    val peerPublishedProfile: StateFlow<PublishedProfileData> = _peerPublishedProfile.asStateFlow()

    // Dialog state for revoke confirmation
    private val _showRevokeDialog = MutableStateFlow(false)
    val showRevokeDialog: StateFlow<Boolean> = _showRevokeDialog.asStateFlow()

    // Shared-capabilities state. shareableHandlers is the user's globally-shared
    // catalog (loaded once); grants is which of those are exposed to THIS peer.
    private val _shareableHandlers = MutableStateFlow<List<com.vettid.app.core.nats.VaultHandler>>(emptyList())
    val shareableHandlers: StateFlow<List<com.vettid.app.core.nats.VaultHandler>> = _shareableHandlers.asStateFlow()

    private val _connectionGrants = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val connectionGrants: StateFlow<Map<String, Boolean>> = _connectionGrants.asStateFlow()

    private val _grantPendingId = MutableStateFlow<String?>(null)
    val grantPendingId: StateFlow<String?> = _grantPendingId.asStateFlow()

    /**
     * Per-connection presence override:
     *   null = follow the user-wide default (set in Vault Preferences)
     *   true = always share online presence with this peer
     *   false = never share with this peer regardless of default
     * Loaded from the connection record via connection.list and
     * mutated via OwnerSpaceClient.setPresenceOverride.
     */
    private val _presenceOverride = MutableStateFlow<Boolean?>(null)
    val presenceOverride: StateFlow<Boolean?> = _presenceOverride.asStateFlow()

    private val _presenceOverrideInFlight = MutableStateFlow(false)
    val presenceOverrideInFlight: StateFlow<Boolean> = _presenceOverrideInFlight.asStateFlow()

    /**
     * Cached peer location: written when the peer shares with us and
     * cleared when they stop. null when no current cache row exists
     * (peer is not sharing, or we haven't observed them yet).
     * Backed by the vault's connections/<id>/_peer_location row;
     * hydrated via location.peer.get at load time and refreshed by
     * V3/V5 transition notifications + live location-update events
     * that match this connection.
     */
    private val _peerLocation = MutableStateFlow<com.vettid.app.core.nats.CachedPeerLocation?>(null)
    val peerLocation: StateFlow<com.vettid.app.core.nats.CachedPeerLocation?> = _peerLocation.asStateFlow()

    private val _isRequestingPeerLocation = MutableStateFlow(false)
    val isRequestingPeerLocation: StateFlow<Boolean> = _isRequestingPeerLocation.asStateFlow()

    init {
        loadConnection()
        observePeerLocationStream()
    }

    /**
     * Wire up the three vault-driven flows that mutate the peer
     * location cache:
     *   - Live location-update broadcasts (vault already caches them
     *     under connections/<id>/_peer_location, but the app needs to
     *     refresh its in-memory copy to keep the UI live).
     *   - V3 start-sharing transition — re-fetch.
     *   - V5 stop-sharing transition — clear in-memory.
     * Each filters to events for THIS connection.
     */
    private fun observePeerLocationStream() {
        viewModelScope.launch {
            ownerSpaceClient.locationUpdates
                .filter { it.connectionId == connectionId }
                .collect { update ->
                    _peerLocation.value = com.vettid.app.core.nats.CachedPeerLocation(
                        latitude = update.latitude,
                        longitude = update.longitude,
                        accuracy = update.accuracy,
                        timestamp = update.timestamp,
                        updatedAt = update.updatedAt,
                        firstReceivedAt = _peerLocation.value?.firstReceivedAt ?: update.updatedAt
                    )
                }
        }
        viewModelScope.launch {
            ownerSpaceClient.peerLocationTransitions
                .filter { it.connectionId == connectionId }
                .collect { transition ->
                    when (transition.transition) {
                        com.vettid.app.core.nats.PeerLocationShareTransition.Transition.STARTED -> {
                            loadPeerLocation()
                        }
                        com.vettid.app.core.nats.PeerLocationShareTransition.Transition.STOPPED -> {
                            _peerLocation.value = null
                        }
                        com.vettid.app.core.nats.PeerLocationShareTransition.Transition.REQUESTED -> {
                            // Handled by the screen-level effect — the
                            // peer is asking for OUR location, not
                            // signaling that they've shared theirs.
                            _effects.emit(ConnectionDetailEffect.PeerRequestedLocation)
                        }
                    }
                }
        }
    }

    /**
     * Load connection details from vault via NATS.
     */
    fun loadConnection() {
        viewModelScope.launch {
            _state.value = ConnectionDetailState.Loading

            // Check if NATS is connected
            if (!natsAutoConnector.isConnected()) {
                _state.value = ConnectionDetailState.Error(
                    message = "Not connected to vault"
                )
                return@launch
            }

            // Load connection list and find the matching one
            connectionsClient.list().fold(
                onSuccess = { listResult ->
                    val record = listResult.items.find { it.connectionId == connectionId }
                    if (record != null) {
                        val status = when (record.status.lowercase()) {
                            "active" -> ConnectionStatus.ACTIVE
                            "pending" -> ConnectionStatus.PENDING
                            "revoked" -> ConnectionStatus.REVOKED
                            else -> ConnectionStatus.ACTIVE
                        }
                        val createdAtMillis = try {
                            java.time.Instant.parse(record.createdAt).toEpochMilli()
                        } catch (e: Exception) {
                            System.currentTimeMillis()
                        }
                        val connection = Connection(
                            connectionId = record.connectionId,
                            peerGuid = record.peerGuid,
                            peerDisplayName = record.label,
                            peerAvatarUrl = null,
                            status = status,
                            createdAt = createdAtMillis,
                            lastMessageAt = null,
                            unreadCount = 0
                        )
                        _peerPhoto.value = record.peerProfile?.photo
                        _peerEmail.value = record.peerProfile?.email
                        _peerFields.value = record.peerProfile?.fields
                        _peerPublicKey.value = record.e2ePublicKey
                        _peerUserGuid.value = record.peerProfile?.userGuid ?: record.peerGuid
                        _peerIdentityKey.value = record.peerProfile?.publicKey
                        _peerWallets.value = record.peerProfile?.wallets ?: emptyList()
                        _peerHandlers.value = record.peerProfile?.handlers ?: emptyList()
                        _peerPublicSecrets.value = record.peerProfile?.publicSecrets ?: emptyList()
                        _peerDataCatalog.value = record.peerProfile?.dataCatalog
                        _peerSecretCatalog.value = record.peerProfile?.secretCatalog
                        _presenceOverride.value = record.presenceShareOverride
                        _peerPublishedProfile.value = record.peerProfile?.let { peer ->
                            peerProfileToPublishedProfileData(
                                peer = peer,
                                fallbackDisplayName = record.label,
                                fallbackEmail = record.peerProfile?.email,
                            )
                        } ?: PublishedProfileData(
                            items = emptyList(),
                            isFromVault = false,
                            photo = record.peerProfile?.photo,
                        )
                        _state.value = ConnectionDetailState.Loaded(
                            connection = connection,
                            profile = null
                        )
                        // Load location sharing status after connection loads
                        loadLocationSharingStatus()
                        // Hydrate the peer-location cache (V2) so the
                        // Location row renders without waiting for the
                        // next live update.
                        loadPeerLocation()
                        // Load handler grants for this peer + the catalog so
                        // the Shared Capabilities section has its data.
                        loadShareHandlers()
                    } else {
                        _state.value = ConnectionDetailState.Error(
                            message = "Connection not found"
                        )
                    }
                },
                onFailure = { error ->
                    _state.value = ConnectionDetailState.Error(
                        message = error.message ?: "Failed to load connection"
                    )
                }
            )
        }
    }

    /**
     * Navigate to messaging.
     */
    fun onMessageClick() {
        viewModelScope.launch {
            _effects.emit(ConnectionDetailEffect.NavigateToMessages(connectionId))
        }
    }

    /**
     * Show revoke confirmation dialog.
     */
    fun onRevokeClick() {
        _showRevokeDialog.value = true
    }

    /**
     * Dismiss revoke confirmation dialog.
     */
    fun dismissRevokeDialog() {
        _showRevokeDialog.value = false
    }

    /**
     * Confirm and execute revocation via NATS vault handler.
     */
    fun confirmRevoke() {
        _showRevokeDialog.value = false

        viewModelScope.launch {
            val currentState = _state.value
            if (currentState is ConnectionDetailState.Loaded) {
                _state.value = currentState.copy(isRevoking = true)
            }

            connectionsClient.revoke(connectionId).fold(
                onSuccess = {
                    // Delete the connection key
                    connectionCryptoManager.deleteConnectionKey(connectionId)

                    _effects.emit(ConnectionDetailEffect.ShowSuccess("Connection revoked"))
                    _effects.emit(ConnectionDetailEffect.NavigateBack)
                },
                onFailure = { error ->
                    if (currentState is ConnectionDetailState.Loaded) {
                        _state.value = currentState.copy(isRevoking = false)
                    }
                    _effects.emit(ConnectionDetailEffect.ShowError(
                        error.message ?: "Failed to revoke connection"
                    ))
                }
            )
        }
    }

    /**
     * Rotate E2E keys for this connection.
     */
    fun rotateKeys() {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState is ConnectionDetailState.Loaded) {
                _state.value = currentState.copy(isRotating = true)
            }

            connectionsClient.rotate(connectionId).fold(
                onSuccess = {
                    _effects.emit(ConnectionDetailEffect.ShowSuccess("Keys rotated successfully"))
                    loadConnection()
                },
                onFailure = { error ->
                    if (currentState is ConnectionDetailState.Loaded) {
                        _state.value = currentState.copy(isRotating = false)
                    }
                    _effects.emit(ConnectionDetailEffect.ShowError(
                        error.message ?: "Failed to rotate keys"
                    ))
                }
            )
        }
    }

    /**
     * View profile details.
     */
    fun onViewProfileClick() {
        viewModelScope.launch {
            _effects.emit(ConnectionDetailEffect.NavigateToProfile(connectionId))
        }
    }

    /**
     * Start a voice call with the connection.
     */
    fun onVoiceCallClick() {
        viewModelScope.launch {
            val connection = (_state.value as? ConnectionDetailState.Loaded)?.connection
            if (connection == null) {
                _effects.emit(ConnectionDetailEffect.ShowError("Connection not loaded"))
                return@launch
            }
            callManager.startCall(connectionId, connection.peerGuid, connection.peerDisplayName, CallType.VOICE).fold(
                onSuccess = {
                    // CallManager will emit showCallUI event which navigates to call screen
                },
                onFailure = { error ->
                    _effects.emit(ConnectionDetailEffect.ShowError(
                        error.message ?: "Failed to start call"
                    ))
                }
            )
        }
    }

    /**
     * Start a video call with the connection.
     */
    fun onVideoCallClick() {
        viewModelScope.launch {
            val connection = (_state.value as? ConnectionDetailState.Loaded)?.connection
            if (connection == null) {
                _effects.emit(ConnectionDetailEffect.ShowError("Connection not loaded"))
                return@launch
            }
            callManager.startCall(connectionId, connection.peerGuid, connection.peerDisplayName, CallType.VIDEO).fold(
                onSuccess = {
                    // CallManager will emit showCallUI event which navigates to call screen
                },
                onFailure = { error ->
                    _effects.emit(ConnectionDetailEffect.ShowError(
                        error.message ?: "Failed to start video call"
                    ))
                }
            )
        }
    }

    /**
     * Load the current location sharing status for this connection.
     */
    /**
     * Apply a tri-state presence override for this connection.
     *   null  → follow user-wide default
     *   true  → always share with this peer
     *   false → never share with this peer
     * Optimistic update; reverts on vault failure.
     */
    fun setPresenceOverride(override: Boolean?) {
        val previous = _presenceOverride.value
        if (previous == override) return
        _presenceOverride.value = override
        viewModelScope.launch {
            _presenceOverrideInFlight.value = true
            try {
                ownerSpaceClient.setPresenceOverride(connectionId, override).onFailure { err ->
                    _presenceOverride.value = previous
                    _effects.emit(ConnectionDetailEffect.ShowError(err.message ?: "Failed to update presence sharing"))
                }
            } finally {
                _presenceOverrideInFlight.value = false
            }
        }
    }

    /**
     * Load the user's globally-shared handlers and the per-connection
     * grant blob in parallel. Refreshes whenever the connection screen
     * re-enters loadConnection() so any toggles flipped on the
     * Capabilities screen propagate.
     */
    private fun loadShareHandlers() {
        viewModelScope.launch {
            try {
                val all = ownerSpaceClient.listHandlers()
                val shareable = all.filter { it.shareable && it.enabled && it.shareGlobally }
                _shareableHandlers.value = shareable
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load shareable handlers", e)
                _shareableHandlers.value = emptyList()
            }
            ownerSpaceClient.getConnectionShareHandlers(connectionId).fold(
                onSuccess = { _connectionGrants.value = it },
                onFailure = { Log.w(TAG, "Failed to load connection grants: ${it.message}") },
            )
        }
    }

    /**
     * Flip a per-connection grant. Optimistic update; reverts on
     * vault failure so the UI never lies about persisted state.
     */
    fun setShareHandlerForConnection(handlerId: String, granted: Boolean) {
        viewModelScope.launch {
            val previous = _connectionGrants.value
            _grantPendingId.value = handlerId
            _connectionGrants.value = previous + (handlerId to granted)
            val nextMap = _connectionGrants.value.toMutableMap().apply {
                if (!granted) remove(handlerId) else this[handlerId] = true
            }
            ownerSpaceClient.setConnectionShareHandlers(connectionId, nextMap).fold(
                onSuccess = { _grantPendingId.value = null },
                onFailure = { err ->
                    _connectionGrants.value = previous
                    _grantPendingId.value = null
                    _effects.emit(ConnectionDetailEffect.ShowError(err.message ?: "Failed to update sharing"))
                },
            )
        }
    }

    /**
     * Read the cached peer location for this connection. Sets
     * peerLocation to null if the peer isn't currently sharing.
     */
    fun loadPeerLocation() {
        viewModelScope.launch {
            try {
                _peerLocation.value = ownerSpaceClient.getPeerLocation(connectionId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load peer location", e)
                _peerLocation.value = null
            }
        }
    }

    /**
     * Send a one-shot location-request ping to this peer (V6).
     * The peer's app receives a `connection.peer-location-requested`
     * notification and may fulfill via `location.send-once`.
     */
    fun requestPeerLocation() {
        viewModelScope.launch {
            _isRequestingPeerLocation.value = true
            try {
                ownerSpaceClient.requestPeerLocation(connectionId).fold(
                    onSuccess = {
                        _effects.emit(ConnectionDetailEffect.ShowSuccess("Location request sent"))
                    },
                    onFailure = { err ->
                        _effects.emit(ConnectionDetailEffect.ShowError(err.message ?: "Failed to request location"))
                    }
                )
            } finally {
                _isRequestingPeerLocation.value = false
            }
        }
    }

    /**
     * Fulfill an inbound peer-location-requested notification by
     * sending our latest cached location once to the requester. Does
     * NOT modify the sharing index — the peer gets a single point
     * and no further updates unless continuous sharing is enabled
     * separately.
     */
    fun fulfillPeerLocationRequest() {
        viewModelScope.launch {
            ownerSpaceClient.sendLocationOnce(connectionId).fold(
                onSuccess = {
                    _effects.emit(ConnectionDetailEffect.ShowSuccess("Location sent"))
                },
                onFailure = { err ->
                    _effects.emit(ConnectionDetailEffect.ShowError(err.message ?: "Failed to send location"))
                }
            )
        }
    }

    private fun loadLocationSharingStatus() {
        viewModelScope.launch {
            try {
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    "location.sharing.list", JsonObject(), 10000L
                )
                if (response is VaultResponse.HandlerResult && response.success) {
                    val result = response.result
                    val sharedWith = result?.getAsJsonArray("shared_with")
                    val isEnabled = sharedWith?.any {
                        it.asString == connectionId
                    } ?: false

                    val currentState = _state.value
                    if (currentState is ConnectionDetailState.Loaded) {
                        _state.value = currentState.copy(isLocationSharingEnabled = isEnabled)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load location sharing status", e)
            }
        }
    }

    /**
     * Toggle location sharing for this connection.
     */
    fun toggleLocationSharing(enabled: Boolean) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState is ConnectionDetailState.Loaded) {
                _state.value = currentState.copy(isTogglingLocationSharing = true)
            }

            try {
                val payload = JsonObject().apply {
                    addProperty("connection_id", connectionId)
                    addProperty("enabled", enabled)
                }
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    "location.sharing.toggle", payload, 10000L
                )

                if (response is VaultResponse.HandlerResult && response.success) {
                    val loaded = _state.value as? ConnectionDetailState.Loaded
                    if (loaded != null) {
                        _state.value = loaded.copy(
                            isLocationSharingEnabled = enabled,
                            isTogglingLocationSharing = false
                        )
                    }
                    _effects.emit(ConnectionDetailEffect.ShowSuccess(
                        if (enabled) "Location sharing enabled" else "Location sharing disabled"
                    ))
                } else {
                    val loaded = _state.value as? ConnectionDetailState.Loaded
                    if (loaded != null) {
                        _state.value = loaded.copy(isTogglingLocationSharing = false)
                    }
                    _effects.emit(ConnectionDetailEffect.ShowError("Failed to toggle location sharing"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle location sharing", e)
                val loaded = _state.value as? ConnectionDetailState.Loaded
                if (loaded != null) {
                    _state.value = loaded.copy(isTogglingLocationSharing = false)
                }
                _effects.emit(ConnectionDetailEffect.ShowError(
                    e.message ?: "Failed to toggle location sharing"
                ))
            }
        }
    }
}

// MARK: - State Types

/**
 * Connection detail state.
 */
sealed class ConnectionDetailState {
    object Loading : ConnectionDetailState()

    data class Loaded(
        val connection: Connection,
        val profile: Profile?,
        val isRevoking: Boolean = false,
        val isRotating: Boolean = false,
        val isLocationSharingEnabled: Boolean = false,
        val isTogglingLocationSharing: Boolean = false
    ) : ConnectionDetailState()

    data class Error(val message: String) : ConnectionDetailState()
}

// MARK: - Effects

/**
 * One-time effects from the ViewModel.
 */
sealed class ConnectionDetailEffect {
    data class NavigateToMessages(val connectionId: String) : ConnectionDetailEffect()
    data class NavigateToProfile(val connectionId: String) : ConnectionDetailEffect()
    object NavigateBack : ConnectionDetailEffect()
    data class ShowSuccess(val message: String) : ConnectionDetailEffect()
    data class ShowError(val message: String) : ConnectionDetailEffect()
    /** Peer asked us to share our location once (V6). Screen prompts the user. */
    object PeerRequestedLocation : ConnectionDetailEffect()
}
