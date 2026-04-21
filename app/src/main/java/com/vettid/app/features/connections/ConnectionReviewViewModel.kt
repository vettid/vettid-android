package com.vettid.app.features.connections

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.ConnectionsClient
import com.vettid.app.core.nats.FeedClient
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.features.connections.components.PeerProfilePreview
import com.vettid.app.features.connections.components.WalletPreview
import com.vettid.app.features.personaldata.PublishedProfileData
import com.vettid.app.features.personaldata.peerProfileToPublishedProfileData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ConnectionReviewVM"

@HiltViewModel
class ConnectionReviewViewModel @Inject constructor(
    private val connectionsClient: ConnectionsClient,
    private val natsAutoConnector: NatsAutoConnector,
    private val feedClient: FeedClient,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val connectionId: String = savedStateHandle["connectionId"]
        ?: throw IllegalArgumentException("connectionId is required")
    private val eventId: String? = savedStateHandle["eventId"]

    private val _state = MutableStateFlow<ConnectionReviewState>(ConnectionReviewState.Loading)
    val state: StateFlow<ConnectionReviewState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ConnectionReviewEffect>()
    val effects: SharedFlow<ConnectionReviewEffect> = _effects.asSharedFlow()

    init {
        loadPeerProfile()
    }

    private fun loadPeerProfile() {
        viewModelScope.launch {
            if (!natsAutoConnector.isConnected()) {
                _state.value = ConnectionReviewState.Error("Not connected to vault")
                return@launch
            }

            connectionsClient.list().fold(
                onSuccess = { listResult ->
                    val record = listResult.items.find { it.connectionId == connectionId }
                    if (record != null) {
                        val profile = record.peerProfile
                        val displayName = listOfNotNull(
                            profile?.firstName, profile?.lastName
                        ).joinToString(" ").trim().ifEmpty { record.label }

                        // Build profile fields from peer profile data
                        val profileFields = mutableMapOf<String, Map<String, String>>()
                        profile?.fields?.forEach { (key, fieldData) ->
                            if (!key.startsWith("_system_")) {
                                profileFields[key] = fieldData
                            }
                        }

                        // Build wallet previews
                        val wallets = profile?.wallets?.map { w ->
                            WalletPreview(
                                label = w.label,
                                address = w.address,
                                network = w.network
                            )
                        } ?: emptyList()

                        val peerPreview = PeerProfilePreview(
                            displayName = displayName,
                            email = profile?.email,
                            photoBase64 = profile?.photo,
                            publicKey = profile?.publicKey ?: record.e2ePublicKey,
                            profileFields = profileFields.ifEmpty { null },
                            wallets = wallets
                        )

                        // Build a PublishedProfileData so the review
                        // screen can render through the same
                        // BusinessCardView the user sees for their own
                        // public-profile preview.
                        val publishedProfile = profile?.let {
                            peerProfileToPublishedProfileData(
                                peer = it,
                                fallbackDisplayName = displayName.takeIf { n -> n.isNotBlank() },
                                fallbackEmail = profile?.email,
                            )
                        } ?: PublishedProfileData(items = emptyList(), isFromVault = true)

                        _state.value = ConnectionReviewState.Loaded(
                            peerProfile = peerPreview,
                            publishedProfile = publishedProfile,
                            connectionId = connectionId,
                            status = record.status,
                            direction = record.direction,
                            createdAt = record.createdAt
                        )
                    } else {
                        _state.value = ConnectionReviewState.Error("Connection not found")
                    }
                },
                onFailure = { error ->
                    _state.value = ConnectionReviewState.Error(
                        error.message ?: "Failed to load connection"
                    )
                }
            )
        }
    }

    fun acceptConnection() {
        viewModelScope.launch {
            val currentState = _state.value as? ConnectionReviewState.Loaded ?: return@launch
            _state.value = currentState.copy(isProcessing = true)

            // Execute the accept action via feed event or direct respond
            if (eventId != null) {
                feedClient.executeAction(eventId, "accept")
            } else {
                // Direct connection respond
                connectionsClient.respond(connectionId, "accept").fold(
                    onSuccess = { },
                    onFailure = { error ->
                        Log.e(TAG, "Accept failed: ${error.message}")
                    }
                )
            }

            _effects.emit(ConnectionReviewEffect.Accepted)
        }
    }

    fun declineConnection() {
        viewModelScope.launch {
            val currentState = _state.value as? ConnectionReviewState.Loaded ?: return@launch
            _state.value = currentState.copy(isProcessing = true)

            if (eventId != null) {
                feedClient.executeAction(eventId, "decline")
            } else {
                connectionsClient.respond(connectionId, "reject").fold(
                    onSuccess = { },
                    onFailure = { error ->
                        Log.e(TAG, "Decline failed: ${error.message}")
                    }
                )
            }

            _effects.emit(ConnectionReviewEffect.Declined)
        }
    }
}

sealed class ConnectionReviewState {
    object Loading : ConnectionReviewState()
    data class Loaded(
        val peerProfile: PeerProfilePreview,
        val publishedProfile: PublishedProfileData,
        val connectionId: String,
        val status: String,
        val direction: String,
        val createdAt: String,
        val isProcessing: Boolean = false
    ) : ConnectionReviewState()
    data class Error(val message: String) : ConnectionReviewState()
}

sealed class ConnectionReviewEffect {
    object Accepted : ConnectionReviewEffect()
    object Declined : ConnectionReviewEffect()
}
