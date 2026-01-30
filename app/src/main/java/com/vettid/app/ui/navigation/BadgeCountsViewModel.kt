package com.vettid.app.ui.navigation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.ConnectionsClient
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.NatsConnectionState
import com.vettid.app.features.feed.FeedNotificationService
import com.vettid.app.features.feed.FeedRepository
import com.vettid.app.features.feed.FeedUpdate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "BadgeCountsViewModel"

/**
 * ViewModel that provides badge counts for the bottom navigation.
 *
 * Observes:
 * - Feed unread count (events with status "active" and readAt == null)
 * - Connections pending count (connection requests awaiting action)
 *
 * This ViewModel is separate from FeedViewModel/ConnectionsViewModel to allow
 * independent observation at the MainScreen level without creating duplicate
 * data fetching.
 */
@HiltViewModel
class BadgeCountsViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
    private val feedNotificationService: FeedNotificationService,
    private val connectionsClient: ConnectionsClient,
    private val connectionManager: NatsConnectionManager
) : ViewModel() {

    private val _unreadFeedCount = MutableStateFlow(0)
    val unreadFeedCount: StateFlow<Int> = _unreadFeedCount.asStateFlow()

    private val _pendingConnectionsCount = MutableStateFlow(0)
    val pendingConnectionsCount: StateFlow<Int> = _pendingConnectionsCount.asStateFlow()

    // Track if we've done initial refresh to avoid duplicate refreshes
    private var hasRefreshedInitially = false

    init {
        observeConnectionState()
        observeFeedUpdates()
    }

    /**
     * Start observing when NATS connects.
     * Only refreshes once on initial connection.
     */
    private fun observeConnectionState() {
        viewModelScope.launch {
            connectionManager.connectionState.collect { state ->
                if (state is NatsConnectionState.Connected && !hasRefreshedInitially) {
                    hasRefreshedInitially = true
                    Log.d(TAG, "NATS connected, refreshing badge counts (initial)")
                    // Delay to let PIN unlock and other initial requests complete first.
                    delay(2000)
                    refreshCounts()
                }
            }
        }
    }

    /**
     * Observe real-time feed updates for badge updates.
     */
    private fun observeFeedUpdates() {
        viewModelScope.launch {
            feedNotificationService.feedUpdates.collect { update ->
                when (update) {
                    is FeedUpdate.NewEvent -> {
                        Log.d(TAG, "New feed event, refreshing unread count")
                        refreshFeedCount()
                    }
                    is FeedUpdate.StatusChanged -> {
                        if (update.newStatus in listOf("read", "archived", "deleted")) {
                            Log.d(TAG, "Event status changed to ${update.newStatus}, updating count")
                            refreshFeedCount()
                        }
                    }
                }
            }
        }
    }

    /**
     * Refresh all badge counts.
     */
    fun refreshCounts() {
        refreshFeedCount()
        refreshConnectionsCount()
    }

    /**
     * Refresh feed unread count.
     */
    private fun refreshFeedCount() {
        viewModelScope.launch {
            try {
                // Get cached events first for quick display
                val cachedEvents = feedRepository.getCachedEvents()
                val cachedCount = cachedEvents.count { it.isUnread }
                _unreadFeedCount.value = cachedCount

                // Then try to sync for fresh data
                feedRepository.sync()
                    .onSuccess {
                        val freshEvents = feedRepository.getCachedEvents()
                        val freshCount = freshEvents.count { it.isUnread }
                        _unreadFeedCount.value = freshCount
                        Log.d(TAG, "Feed unread count: $freshCount")
                    }
                    .onFailure { error ->
                        // Don't log cancellation as error
                        if (error is CancellationException) throw error
                        Log.w(TAG, "Failed to sync feed for count: ${error.message}")
                        // Keep cached count
                    }
            } catch (e: CancellationException) {
                Log.d(TAG, "Feed count refresh cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing feed count", e)
            }
        }
    }

    /**
     * Refresh pending connections count.
     */
    private fun refreshConnectionsCount() {
        viewModelScope.launch {
            try {
                // Get all connections and count pending ones
                connectionsClient.list(status = null, limit = 100)
                    .onSuccess { result ->
                        // Count connections with pending status
                        val pendingCount = result.items.count { connection ->
                            connection.status.equals("pending", ignoreCase = true)
                        }
                        _pendingConnectionsCount.value = pendingCount
                        Log.d(TAG, "Pending connections count: $pendingCount (total: ${result.items.size})")
                    }
                    .onFailure { error ->
                        // Don't log cancellation as error
                        if (error is CancellationException) throw error
                        Log.w(TAG, "Failed to load connections for count: ${error.message}")
                    }
            } catch (e: CancellationException) {
                Log.d(TAG, "Connections count refresh cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing connections count", e)
            }
        }
    }

    /**
     * Called when user views the feed tab - refresh count.
     */
    fun onFeedViewed() {
        refreshFeedCount()
    }

    /**
     * Called when user views the connections tab - refresh count.
     */
    fun onConnectionsViewed() {
        refreshConnectionsCount()
    }
}
