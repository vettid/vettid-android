package com.vettid.app.features.location

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.ConnectionsClient
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.SharedLocationUpdate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "SharedLocationsVM"
private const val STALE_THRESHOLD_MS = 60 * 60 * 1000L // 1 hour

/**
 * A shared location entry displayed in the UI.
 */
data class SharedLocationEntry(
    val connectionId: String,
    val peerName: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val timestamp: Long,
    val isStale: Boolean
)

/**
 * State for the Shared Locations screen.
 */
data class SharedLocationsState(
    val entries: List<SharedLocationEntry> = emptyList(),
    val isLoading: Boolean = true
)

/**
 * ViewModel for viewing connections' shared locations.
 * Subscribes to locationUpdates flow from OwnerSpaceClient and maintains
 * the latest location per connection in-memory (ephemeral).
 */
@HiltViewModel
class SharedLocationsViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val connectionsClient: ConnectionsClient
) : ViewModel() {

    private val _state = MutableStateFlow(SharedLocationsState())
    val state: StateFlow<SharedLocationsState> = _state.asStateFlow()

    // In-memory map of latest location per connection
    private val latestLocations = mutableMapOf<String, SharedLocationUpdate>()
    // Cached connection names
    private val connectionNames = mutableMapOf<String, String>()

    init {
        loadConnectionNames()
        subscribeToLocationUpdates()
    }

    private fun loadConnectionNames() {
        viewModelScope.launch {
            try {
                connectionsClient.list(status = "active").fold(
                    onSuccess = { result ->
                        for (item in result.items) {
                            connectionNames[item.connectionId] = item.label
                        }
                        _state.update { it.copy(isLoading = false) }
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to load connections", error)
                        _state.update { it.copy(isLoading = false) }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error loading connections", e)
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun subscribeToLocationUpdates() {
        viewModelScope.launch {
            ownerSpaceClient.locationUpdates.collect { update ->
                latestLocations[update.connectionId] = update
                refreshEntries()
            }
        }
    }

    private fun refreshEntries() {
        val now = System.currentTimeMillis()
        val entries = latestLocations.values.map { update ->
            val timestampMs = if (update.timestamp < 10_000_000_000L) {
                update.timestamp * 1000
            } else {
                update.timestamp
            }
            SharedLocationEntry(
                connectionId = update.connectionId,
                peerName = connectionNames[update.connectionId] ?: update.connectionId,
                latitude = update.latitude,
                longitude = update.longitude,
                accuracy = update.accuracy,
                timestamp = update.timestamp,
                isStale = (now - timestampMs) > STALE_THRESHOLD_MS
            )
        }.sortedByDescending { it.timestamp }

        _state.update { it.copy(entries = entries) }
    }
}
