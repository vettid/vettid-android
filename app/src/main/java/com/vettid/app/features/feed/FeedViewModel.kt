package com.vettid.app.features.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.FeedClient
import com.vettid.app.core.nats.FeedEvent
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.NatsConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "FeedViewModel"

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val feedClient: FeedClient,
    private val feedRepository: FeedRepository,
    private val feedNotificationService: FeedNotificationService,
    private val connectionManager: NatsConnectionManager
) : ViewModel() {

    private val _state = MutableStateFlow<FeedState>(FeedState.Loading)
    val state: StateFlow<FeedState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<FeedEffect>()
    val effects: SharedFlow<FeedEffect> = _effects.asSharedFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Expose sync state for UI
    val syncState: StateFlow<SyncState> = feedRepository.syncState
    val isOnline: StateFlow<Boolean> = feedRepository.isOnline

    // In-app notifications for snackbar display
    private val _inAppNotification = MutableSharedFlow<InAppFeedNotification>()
    val inAppNotification: SharedFlow<InAppFeedNotification> = _inAppNotification.asSharedFlow()

    // Track current refresh job to prevent multiple concurrent refreshes
    private var refreshJob: Job? = null

    init {
        // Show cached data immediately while waiting for connection
        showCachedFeed()
        // Wait for NATS connection before syncing
        observeConnectionAndLoad()
        observeFeedUpdates()
        startPeriodicRefresh()
    }

    /**
     * Show cached feed data immediately without network request.
     */
    private fun showCachedFeed() {
        val cached = feedRepository.getCachedEvents()
        if (cached.isNotEmpty()) {
            _state.value = FeedState.Loaded(
                events = cached,
                hasMore = false,
                unreadCount = cached.count { it.isUnread },
                isOffline = true
            )
            Log.d(TAG, "Showing ${cached.size} cached feed events")
        }
    }

    // Track if we've done initial load
    private var hasLoadedInitially = false

    /**
     * Wait for NATS connection before loading feed from server.
     * Only loads once when first connected.
     */
    private fun observeConnectionAndLoad() {
        viewModelScope.launch {
            connectionManager.connectionState.collect { state ->
                if (state is NatsConnectionState.Connected && !hasLoadedInitially) {
                    hasLoadedInitially = true
                    Log.d(TAG, "NATS connected, loading feed (initial)")
                    // Small delay to let connection stabilize
                    delay(1000)
                    loadFeed()
                }
            }
        }
    }

    /**
     * Periodically refresh feed every 30 seconds when online.
     */
    private fun startPeriodicRefresh() {
        viewModelScope.launch {
            while (isActive) {
                delay(30_000) // 30 seconds
                if (isOnline.value && _state.value is FeedState.Loaded) {
                    Log.d(TAG, "Periodic feed refresh")
                    refresh()
                }
            }
        }
    }

    /**
     * Observe real-time feed updates from NATS.
     */
    private fun observeFeedUpdates() {
        // Real-time feed updates (new events, status changes)
        viewModelScope.launch {
            feedNotificationService.feedUpdates.collect { update ->
                when (update) {
                    is FeedUpdate.NewEvent -> {
                        Log.d(TAG, "New feed event: ${update.eventId}")
                        refresh() // Refresh to get new event
                    }
                    is FeedUpdate.StatusChanged -> {
                        Log.d(TAG, "Event status changed: ${update.eventId} -> ${update.newStatus}")
                        handleStatusChange(update.eventId, update.newStatus)
                    }
                }
            }
        }

        // In-app notifications (when app is in foreground)
        viewModelScope.launch {
            feedNotificationService.inAppNotifications.collect { notification ->
                _inAppNotification.emit(notification)
            }
        }
    }

    private fun handleStatusChange(eventId: String, newStatus: String) {
        // Update repository cache
        when (newStatus) {
            "archived", "deleted" -> feedRepository.removeEventLocally(eventId)
        }

        // Update UI state
        val currentState = _state.value
        if (currentState is FeedState.Loaded) {
            when (newStatus) {
                "archived", "deleted" -> {
                    // Remove from list
                    val updatedEvents = currentState.events.filter { it.eventId != eventId }
                    _state.value = if (updatedEvents.isEmpty()) {
                        FeedState.Empty
                    } else {
                        currentState.copy(
                            events = updatedEvents,
                            unreadCount = updatedEvents.count { it.isUnread }
                        )
                    }
                }
                "read" -> {
                    // Update unread count
                    _state.value = currentState.copy(
                        unreadCount = currentState.events.count { it.isUnread && it.eventId != eventId }
                    )
                }
            }
        }
    }

    fun loadFeed() {
        viewModelScope.launch {
            // Only show loading if we don't have cached data
            val currentState = _state.value
            if (currentState !is FeedState.Loaded) {
                _state.value = FeedState.Loading
            }
            try {
                // Use repository which provides offline caching
                feedRepository.getFeed(forceRefresh = false)
                    .onSuccess { events ->
                        if (events.isEmpty()) {
                            _state.value = FeedState.Empty
                        } else {
                            _state.value = FeedState.Loaded(
                                events = events,
                                hasMore = false, // Repository handles pagination
                                unreadCount = events.count { it.isUnread },
                                isOffline = !feedRepository.isOnline.value
                            )
                        }
                    }
                    .onFailure { error ->
                        // Don't log cancellation as error - it's expected during navigation
                        if (error is CancellationException) throw error

                        Log.e(TAG, "Failed to load feed", error)
                        // Try to show cached data even on error
                        val cached = feedRepository.getCachedEvents()
                        if (cached.isNotEmpty()) {
                            _state.value = FeedState.Loaded(
                                events = cached,
                                hasMore = false,
                                unreadCount = cached.count { it.isUnread },
                                isOffline = true
                            )
                        } else {
                            _state.value = FeedState.Error(error.message ?: "Failed to load feed")
                        }
                    }
            } catch (e: CancellationException) {
                // Cancellation is expected during navigation - don't treat as error
                Log.d(TAG, "Feed load cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load feed", e)
                _state.value = FeedState.Error(e.message ?: "Failed to load feed")
            }
        }
    }

    fun refresh() {
        // Cancel any existing refresh job to prevent queueing
        refreshJob?.cancel()

        refreshJob = viewModelScope.launch {
            _isRefreshing.value = true
            try {
                // Use repository sync for incremental updates
                feedRepository.sync()
                    .onSuccess { result ->
                        Log.d(TAG, "Sync complete: +${result.newEvents} new, ${result.updatedEvents} updated")
                        val events = feedRepository.getCachedEvents()
                        if (events.isEmpty()) {
                            _state.value = FeedState.Empty
                        } else {
                            _state.value = FeedState.Loaded(
                                events = events,
                                hasMore = result.hasMore,
                                unreadCount = events.count { it.isUnread },
                                isOffline = false
                            )
                        }
                    }
                    .onFailure { error ->
                        // Don't log cancellation as error - it's expected
                        if (error is CancellationException) throw error

                        Log.e(TAG, "Sync failed", error)
                        // Keep current state but mark as potentially offline
                        val currentState = _state.value
                        if (currentState is FeedState.Loaded) {
                            _state.value = currentState.copy(isOffline = true)
                        }
                    }
            } catch (e: CancellationException) {
                // Cancellation is expected - don't treat as error
                Log.d(TAG, "Feed refresh cancelled")
                throw e
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun onEventClick(event: FeedEvent) {
        viewModelScope.launch {
            // Mark as read
            markAsRead(event.eventId)

            // Navigate based on event type
            val sourceId = event.sourceId
            when (event.eventType) {
                EventTypes.MESSAGE_RECEIVED -> {
                    if (sourceId != null) {
                        _effects.emit(FeedEffect.NavigateToConversation(sourceId))
                    } else {
                        _effects.emit(FeedEffect.ShowEventDetail(event))
                    }
                }
                EventTypes.CONNECTION_REQUEST -> {
                    if (sourceId != null) {
                        _effects.emit(FeedEffect.NavigateToConnectionRequest(sourceId))
                    } else {
                        _effects.emit(FeedEffect.ShowEventDetail(event))
                    }
                }
                EventTypes.CALL_INCOMING, EventTypes.CALL_MISSED -> {
                    if (sourceId != null) {
                        _effects.emit(FeedEffect.NavigateToCall(sourceId))
                    } else {
                        _effects.emit(FeedEffect.ShowEventDetail(event))
                    }
                }
                EventTypes.HANDLER_COMPLETE -> {
                    val handlerId = event.metadata?.get("handler_id")
                    if (handlerId != null) {
                        _effects.emit(FeedEffect.NavigateToHandler(handlerId))
                    } else {
                        _effects.emit(FeedEffect.ShowEventDetail(event))
                    }
                }
                EventTypes.BACKUP_COMPLETE -> {
                    val backupId = event.metadata?.get("backup_id")
                    if (backupId != null) {
                        _effects.emit(FeedEffect.NavigateToBackup(backupId))
                    } else {
                        _effects.emit(FeedEffect.ShowEventDetail(event))
                    }
                }
                EventTypes.TRANSFER_REQUEST -> {
                    val transferId = event.metadata?.get("transfer_id")
                    if (transferId != null) {
                        _effects.emit(FeedEffect.NavigateToTransfer(transferId))
                    } else {
                        _effects.emit(FeedEffect.ShowEventDetail(event))
                    }
                }
                else -> {
                    // Default: show event detail dialog
                    _effects.emit(FeedEffect.ShowEventDetail(event))
                }
            }
        }
    }

    fun markAsRead(eventId: String) {
        viewModelScope.launch {
            feedClient.markRead(eventId)
                .onSuccess {
                    // Update local state
                    val currentState = _state.value
                    if (currentState is FeedState.Loaded) {
                        val updatedEvents = currentState.events.map { event ->
                            if (event.eventId == eventId) {
                                // Create a copy with read status (using copy would require data class changes)
                                event
                            } else {
                                event
                            }
                        }
                        _state.value = currentState.copy(
                            events = updatedEvents,
                            unreadCount = updatedEvents.count { it.isUnread }
                        )
                    }
                }
                .onFailure {
                    Log.e(TAG, "Failed to mark event as read", it)
                }
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState is FeedState.Loaded) {
                val unreadIds = currentState.events.filter { it.isUnread }.map { it.eventId }
                if (unreadIds.isNotEmpty()) {
                    feedClient.markMultipleRead(unreadIds)
                        .onSuccess {
                            _state.value = currentState.copy(unreadCount = 0)
                        }
                        .onFailure {
                            Log.e(TAG, "Failed to mark all as read", it)
                            _effects.emit(FeedEffect.ShowError("Failed to mark all as read"))
                        }
                }
            }
        }
    }

    fun archiveEvent(eventId: String) {
        viewModelScope.launch {
            feedClient.archiveEvent(eventId)
                .onSuccess {
                    // Update local repository cache
                    feedRepository.removeEventLocally(eventId)

                    // Update UI state
                    val currentState = _state.value
                    if (currentState is FeedState.Loaded) {
                        val updatedEvents = currentState.events.filter { it.eventId != eventId }
                        _state.value = if (updatedEvents.isEmpty()) {
                            FeedState.Empty
                        } else {
                            currentState.copy(
                                events = updatedEvents,
                                unreadCount = updatedEvents.count { it.isUnread }
                            )
                        }
                    }
                    _effects.emit(FeedEffect.ShowActionSuccess("Event archived"))
                }
                .onFailure {
                    Log.e(TAG, "Failed to archive event", it)
                    _effects.emit(FeedEffect.ShowError("Failed to archive event"))
                }
        }
    }

    fun deleteEvent(eventId: String) {
        viewModelScope.launch {
            feedClient.deleteEvent(eventId)
                .onSuccess {
                    // Update local repository cache
                    feedRepository.removeEventLocally(eventId)

                    // Update UI state
                    val currentState = _state.value
                    if (currentState is FeedState.Loaded) {
                        val updatedEvents = currentState.events.filter { it.eventId != eventId }
                        _state.value = if (updatedEvents.isEmpty()) {
                            FeedState.Empty
                        } else {
                            currentState.copy(
                                events = updatedEvents,
                                unreadCount = updatedEvents.count { it.isUnread }
                            )
                        }
                    }
                    _effects.emit(FeedEffect.ShowActionSuccess("Event deleted"))
                }
                .onFailure {
                    Log.e(TAG, "Failed to delete event", it)
                    _effects.emit(FeedEffect.ShowError("Failed to delete event"))
                }
        }
    }

    fun executeAction(eventId: String, action: String, data: Map<String, String>? = null) {
        viewModelScope.launch {
            feedClient.executeAction(eventId, action, data)
                .onSuccess {
                    // Refresh to get updated state
                    refresh()
                    _effects.emit(FeedEffect.ShowActionSuccess("Action completed"))
                }
                .onFailure {
                    Log.e(TAG, "Failed to execute action", it)
                    _effects.emit(FeedEffect.ShowError("Action failed: ${it.message}"))
                }
        }
    }
}
