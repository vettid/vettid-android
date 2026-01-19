package com.vettid.app.features.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.FeedClient
import com.vettid.app.core.nats.FeedEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "FeedViewModel"

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val feedClient: FeedClient,
    private val feedNotificationService: FeedNotificationService
) : ViewModel() {

    private val _state = MutableStateFlow<FeedState>(FeedState.Loading)
    val state: StateFlow<FeedState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<FeedEffect>()
    val effects: SharedFlow<FeedEffect> = _effects.asSharedFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Track last sync sequence for incremental updates
    private var lastSyncSequence: Long = 0

    // In-app notifications for snackbar display
    private val _inAppNotification = MutableSharedFlow<InAppFeedNotification>()
    val inAppNotification: SharedFlow<InAppFeedNotification> = _inAppNotification.asSharedFlow()

    init {
        loadFeed()
        observeFeedUpdates()
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
            _state.value = FeedState.Loading
            try {
                feedClient.listFeed(status = null, limit = 50)
                    .onSuccess { response ->
                        if (response.events.isEmpty()) {
                            _state.value = FeedState.Empty
                        } else {
                            // Sort by priority (desc) then created_at (desc)
                            val sortedEvents = response.events.sortedWith(
                                compareByDescending<FeedEvent> { it.priority }
                                    .thenByDescending { it.createdAt }
                            )
                            _state.value = FeedState.Loaded(
                                events = sortedEvents,
                                hasMore = response.total > response.events.size,
                                unreadCount = sortedEvents.count { it.isUnread }
                            )
                            // Update sync sequence
                            lastSyncSequence = sortedEvents.maxOfOrNull { it.syncSequence } ?: 0
                        }
                    }
                    .onFailure { error ->
                        Log.e(TAG, "Failed to load feed", error)
                        _state.value = FeedState.Error(error.message ?: "Failed to load feed")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load feed", e)
                _state.value = FeedState.Error(e.message ?: "Failed to load feed")
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                // Use sync for incremental updates
                feedClient.sync(sinceSequence = lastSyncSequence)
                    .onSuccess { response ->
                        val currentState = _state.value
                        if (currentState is FeedState.Loaded && response.events.isNotEmpty()) {
                            // Merge new events with existing
                            val existingIds = currentState.events.map { it.eventId }.toSet()
                            val newEvents = response.events.filter { it.eventId !in existingIds }
                            val updatedEvents = (newEvents + currentState.events).sortedWith(
                                compareByDescending<FeedEvent> { it.priority }
                                    .thenByDescending { it.createdAt }
                            )
                            _state.value = currentState.copy(
                                events = updatedEvents,
                                unreadCount = updatedEvents.count { it.isUnread }
                            )
                            lastSyncSequence = response.latestSequence
                        } else if (response.events.isEmpty() && currentState !is FeedState.Loaded) {
                            loadFeed() // Full reload if no state
                        }
                    }
                    .onFailure {
                        Log.e(TAG, "Sync failed, doing full reload", it)
                        loadFeed() // Fallback to full reload
                    }
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
                    sourceId?.let { _effects.emit(FeedEffect.NavigateToConversation(it)) }
                }
                EventTypes.CONNECTION_REQUEST -> {
                    sourceId?.let { _effects.emit(FeedEffect.NavigateToConnectionRequest(it)) }
                }
                EventTypes.CALL_INCOMING, EventTypes.CALL_MISSED -> {
                    sourceId?.let { _effects.emit(FeedEffect.NavigateToCall(it)) }
                }
                EventTypes.HANDLER_COMPLETE -> {
                    event.metadata?.get("handler_id")?.let {
                        _effects.emit(FeedEffect.NavigateToHandler(it))
                    }
                }
                EventTypes.BACKUP_COMPLETE -> {
                    event.metadata?.get("backup_id")?.let {
                        _effects.emit(FeedEffect.NavigateToBackup(it))
                    }
                }
                EventTypes.TRANSFER_REQUEST -> {
                    event.metadata?.get("transfer_id")?.let {
                        _effects.emit(FeedEffect.NavigateToTransfer(it))
                    }
                }
                EventTypes.SECURITY_ALERT, EventTypes.SECURITY_MIGRATION -> {
                    // Security events - navigate to security audit log
                }
                else -> {
                    // Default: just mark as read, no navigation
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
                    // Remove from local state
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
                    // Remove from local state
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
