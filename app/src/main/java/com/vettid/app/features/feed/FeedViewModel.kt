package com.vettid.app.features.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.FeedClient
import com.vettid.app.core.nats.FeedEvent
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.core.storage.PersonalDataStore
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
    private val natsAutoConnector: NatsAutoConnector,
    private val credentialStore: CredentialStore,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val personalDataStore: PersonalDataStore
) : ViewModel() {

    private val _state = MutableStateFlow<FeedState>(FeedState.Loading)
    val state: StateFlow<FeedState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<FeedEffect>()
    val effects: SharedFlow<FeedEffect> = _effects.asSharedFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Audit events toggle (hidden by default)
    private val _showAuditEvents = MutableStateFlow(false)
    val showAuditEvents: StateFlow<Boolean> = _showAuditEvents.asStateFlow()

    // Search query
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Expose sync state for UI
    val syncState: StateFlow<SyncState> = feedRepository.syncState
    val isOnline: StateFlow<Boolean> = feedRepository.isOnline

    // Check if user intentionally chose offline mode
    private val _isOfflineModeEnabled = MutableStateFlow(credentialStore.getOfflineMode())
    val isOfflineModeEnabled: StateFlow<Boolean> = _isOfflineModeEnabled.asStateFlow()

    // In-app notifications for snackbar display
    private val _inAppNotification = MutableSharedFlow<InAppFeedNotification>()
    val inAppNotification: SharedFlow<InAppFeedNotification> = _inAppNotification.asSharedFlow()

    // Track current refresh job to prevent multiple concurrent refreshes
    private var refreshJob: Job? = null

    // Track if initial loadFeed() is still in progress
    private var isInitialLoadComplete = false

    init {
        // Show cached data immediately while waiting for connection
        showCachedFeed()
        // Wait for NATS connection before syncing
        observeConnectionAndLoad()
        observeFeedUpdates()
        startPeriodicRefresh()
        observeFilterChanges()
    }

    /**
     * Show cached feed data immediately without network request.
     */
    private fun showCachedFeed() {
        val cached = feedRepository.getCachedEvents()
        if (cached.isNotEmpty()) {
            val filtered = filterForDisplay(cached)
            _state.value = if (filtered.isEmpty()) {
                FeedState.Empty
            } else {
                FeedState.Loaded(
                    events = filtered,
                    hasMore = false,
                    unreadCount = filtered.count { it.isUnread },
                    isOffline = true
                )
            }
            Log.d(TAG, "Showing ${filtered.size} of ${cached.size} cached feed events")
        }
    }

    fun toggleAuditEvents() {
        _showAuditEvents.value = !_showAuditEvents.value
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Filter events for display based on status, audit toggle, and search query.
     */
    private fun filterForDisplay(events: List<FeedEvent>): List<FeedEvent> {
        var filtered = events
        // Always exclude archived and deleted events
        filtered = filtered.filter { it.feedStatus != FeedStatus.ARCHIVED && it.feedStatus != FeedStatus.DELETED }
        // Exclude audit-only (hidden) events unless toggle is on
        if (!_showAuditEvents.value) {
            filtered = filtered.filter { it.feedStatus != FeedStatus.HIDDEN }
        }
        val query = _searchQuery.value.trim()
        if (query.isNotEmpty()) {
            filtered = filtered.filter { event ->
                event.title.contains(query, ignoreCase = true) ||
                    (event.message?.contains(query, ignoreCase = true) == true)
            }
        }
        return filtered
    }

    /**
     * Re-filter cached events when audit toggle or search query changes.
     */
    private fun observeFilterChanges() {
        viewModelScope.launch {
            combine(_showAuditEvents, _searchQuery.debounce(300)) { _, _ -> Unit }
                .collect {
                    val allCached = feedRepository.getCachedEvents()
                    if (allCached.isNotEmpty()) {
                        val filtered = filterForDisplay(allCached)
                        if (filtered.isEmpty()) {
                            _state.value = FeedState.Empty
                        } else {
                            _state.value = FeedState.Loaded(
                                events = filtered,
                                hasMore = false,
                                unreadCount = filtered.count { it.isUnread },
                                isOffline = !feedRepository.isOnline.value
                            )
                        }
                    }
                }
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
            natsAutoConnector.connectionState.collect { state ->
                if (state is NatsAutoConnector.AutoConnectState.Connected && !hasLoadedInitially) {
                    hasLoadedInitially = true
                    Log.d(TAG, "NATS connected, loading feed (initial)")
                    // Small delay to let connection stabilize
                    delay(1000)
                    // Sync guides first and wait for completion so events exist before loading feed
                    syncGuides()
                    loadFeedAndWait()
                    isInitialLoadComplete = true
                }
            }
        }
    }

    /**
     * Periodically refresh feed. When loaded, refreshes every 30s.
     * When not loaded (Loading/Error), retries every 5s for faster recovery.
     */
    private fun startPeriodicRefresh() {
        viewModelScope.launch {
            while (isActive) {
                val currentState = _state.value
                val delayMs = if (currentState is FeedState.Loaded) 30_000L else 5_000L
                delay(delayMs)

                val stateAfterDelay = _state.value
                if (stateAfterDelay is FeedState.Loaded) {
                    Log.d(TAG, "Periodic feed refresh (silent)")
                    silentRefresh()
                } else if (stateAfterDelay is FeedState.Loading || stateAfterDelay is FeedState.Error) {
                    Log.d(TAG, "Periodic retry: feed not loaded yet, retrying")
                    loadFeed()
                }
            }
        }
    }

    /**
     * Silent refresh for periodic/background syncs.
     * Does NOT set _isRefreshing, so the pull-to-refresh indicator won't show.
     */
    private fun silentRefresh() {
        viewModelScope.launch {
            try {
                feedRepository.sync()
                    .onSuccess { result ->
                        Log.d(TAG, "Silent sync complete: +${result.newEvents} new, ${result.updatedEvents} updated")
                        val events = feedRepository.getCachedEvents()
                        val filtered = filterForDisplay(events)
                        if (filtered.isEmpty()) {
                            _state.value = FeedState.Empty
                        } else {
                            _state.value = FeedState.Loaded(
                                events = filtered,
                                hasMore = result.hasMore,
                                unreadCount = filtered.count { it.isUnread },
                                isOffline = false
                            )
                        }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        Log.e(TAG, "Silent sync failed", error)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { }
        }
    }

    /**
     * Observe real-time feed updates from NATS.
     * Debounces NewEvent notifications to avoid a storm of refresh() calls
     * when many events arrive at once (e.g., 9 guide events on first enrollment).
     */
    private fun observeFeedUpdates() {
        // Real-time feed updates (new events, status changes)
        viewModelScope.launch {
            feedNotificationService.feedUpdates
                .collect { update ->
                    when (update) {
                        is FeedUpdate.NewEvent -> {
                            Log.d(TAG, "New feed event: ${update.eventId}")
                            // Skip refresh during initial load — loadFeed() will fetch everything
                            if (!isInitialLoadComplete) {
                                Log.d(TAG, "Skipping refresh during initial load")
                                return@collect
                            }
                            // Debounce: cancel pending refresh and wait before starting a new one
                            // so rapid-fire notifications (guide sync) become a single refresh
                            refreshJob?.cancel()
                            refreshJob = viewModelScope.launch {
                                delay(1000) // Wait 1s for more notifications to arrive
                                refresh()
                            }
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

    /**
     * Suspend version of loadFeed that waits for the result with retries.
     * Used by observeConnectionAndLoad() to ensure isInitialLoadComplete is set
     * only after data is actually loaded (or retries exhausted).
     * Retries up to 3 times with short delays to handle slow vault startup.
     */
    private suspend fun loadFeedAndWait() {
        val currentState = _state.value
        if (currentState !is FeedState.Loaded) {
            _state.value = FeedState.Loading
        }

        val delays = longArrayOf(2000, 3000, 5000) // delays between retries
        for (attempt in 0..2) {
            try {
                val result = feedRepository.getFeed(forceRefresh = attempt > 0)
                if (result.isSuccess) {
                    val events = result.getOrThrow()
                    val filtered = filterForDisplay(events)
                    _state.value = if (filtered.isEmpty()) {
                        FeedState.Empty
                    } else {
                        FeedState.Loaded(
                            events = filtered,
                            hasMore = false,
                            unreadCount = filtered.count { it.isUnread },
                            isOffline = !feedRepository.isOnline.value
                        )
                    }
                    Log.d(TAG, "loadFeedAndWait: loaded ${filtered.size} events on attempt ${attempt + 1}")
                    return
                }
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                Log.w(TAG, "loadFeedAndWait: attempt ${attempt + 1} failed: ${error?.message}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "loadFeedAndWait: attempt ${attempt + 1} exception: ${e.message}")
            }

            if (attempt < 2) delay(delays[attempt])
        }

        // All attempts failed — show cached data or error
        val cached = feedRepository.getCachedEvents()
        val filtered = filterForDisplay(cached)
        _state.value = if (filtered.isNotEmpty()) {
            Log.d(TAG, "loadFeedAndWait: showing ${filtered.size} cached events after retries exhausted")
            FeedState.Loaded(
                events = filtered,
                hasMore = false,
                unreadCount = filtered.count { it.isUnread },
                isOffline = true
            )
        } else {
            FeedState.Error("Unable to load feed. Pull down to retry.")
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
                        val filtered = filterForDisplay(events)
                        if (filtered.isEmpty()) {
                            _state.value = FeedState.Empty
                        } else {
                            _state.value = FeedState.Loaded(
                                events = filtered,
                                hasMore = false, // Repository handles pagination
                                unreadCount = filtered.count { it.isUnread },
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
                        val filtered = filterForDisplay(cached)
                        if (filtered.isNotEmpty()) {
                            _state.value = FeedState.Loaded(
                                events = filtered,
                                hasMore = false,
                                unreadCount = filtered.count { it.isUnread },
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
                        val filtered = filterForDisplay(events)
                        if (filtered.isEmpty()) {
                            _state.value = FeedState.Empty
                        } else {
                            _state.value = FeedState.Loaded(
                                events = filtered,
                                hasMore = result.hasMore,
                                unreadCount = filtered.count { it.isUnread },
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
                EventTypes.GUIDE -> {
                    val guideId = event.metadata?.get("guide_id")
                    val userName = event.metadata?.get("user_name") ?: ""
                    if (guideId != null) {
                        _effects.emit(FeedEffect.NavigateToGuide(guideId, event.eventId, userName))
                    } else {
                        _effects.emit(FeedEffect.ShowEventDetail(event))
                    }
                }
                EventTypes.AGENT_SECRET_REQUEST, EventTypes.AGENT_ACTION_REQUEST -> {
                    val requestId = event.metadata?.get("request_id")
                    if (requestId != null) {
                        _effects.emit(FeedEffect.NavigateToAgentApproval(requestId))
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
                    // Update local state with read status
                    val currentState = _state.value
                    if (currentState is FeedState.Loaded) {
                        val now = System.currentTimeMillis() / 1000
                        val updatedEvents = currentState.events.map { event ->
                            if (event.eventId == eventId) {
                                event.copy(feedStatus = FeedStatus.READ, readAt = now)
                            } else {
                                event
                            }
                        }
                        _state.value = currentState.copy(
                            events = updatedEvents,
                            unreadCount = updatedEvents.count { it.isUnread }
                        )
                        // Also update the repository cache
                        feedRepository.updateEventLocally(eventId) { it.copy(feedStatus = FeedStatus.READ, readAt = now) }
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
                    // Mark as archived in cache (keeps it so sync doesn't re-add as new)
                    val now = System.currentTimeMillis() / 1000
                    feedRepository.updateEventLocally(eventId) {
                        it.copy(feedStatus = FeedStatus.ARCHIVED, archivedAt = now)
                    }

                    // Update UI state - filterForDisplay excludes archived
                    val allCached = feedRepository.getCachedEvents()
                    val filtered = filterForDisplay(allCached)
                    _state.value = if (filtered.isEmpty()) {
                        FeedState.Empty
                    } else {
                        FeedState.Loaded(
                            events = filtered,
                            hasMore = false,
                            unreadCount = filtered.count { it.isUnread },
                            isOffline = !feedRepository.isOnline.value
                        )
                    }
                    _effects.emit(FeedEffect.ShowActionSuccess("Event archived"))
                }
                .onFailure { error ->
                    // If vault says "event not found", the event was already deleted/cleaned up
                    // server-side. Remove it locally so the user isn't stuck with an un-archivable item.
                    if (error.message?.contains("event not found", ignoreCase = true) == true) {
                        Log.w(TAG, "Event $eventId not found in vault, removing locally")
                        feedRepository.removeEventLocally(eventId)
                        val allCached = feedRepository.getCachedEvents()
                        val filtered = filterForDisplay(allCached)
                        _state.value = if (filtered.isEmpty()) {
                            FeedState.Empty
                        } else {
                            FeedState.Loaded(
                                events = filtered,
                                hasMore = false,
                                unreadCount = filtered.count { it.isUnread },
                                isOffline = !feedRepository.isOnline.value
                            )
                        }
                        _effects.emit(FeedEffect.ShowActionSuccess("Event archived"))
                    } else {
                        Log.e(TAG, "Failed to archive event", error)
                        _effects.emit(FeedEffect.ShowError("Failed to archive event"))
                    }
                }
        }
    }

    fun deleteEvent(eventId: String) {
        viewModelScope.launch {
            feedClient.deleteEvent(eventId)
                .onSuccess {
                    // Mark as deleted in cache (don't remove, or sync will re-add)
                    val now = System.currentTimeMillis() / 1000
                    feedRepository.updateEventLocally(eventId) {
                        it.copy(feedStatus = FeedStatus.DELETED)
                    }

                    // Re-filter from cache to update UI
                    val allCached = feedRepository.getCachedEvents()
                    val filtered = filterForDisplay(allCached)
                    _state.value = if (filtered.isEmpty()) {
                        FeedState.Empty
                    } else {
                        FeedState.Loaded(
                            events = filtered,
                            hasMore = false,
                            unreadCount = filtered.count { it.isUnread },
                            isOffline = !feedRepository.isOnline.value
                        )
                    }
                    _effects.emit(FeedEffect.ShowActionSuccess("Event deleted"))
                }
                .onFailure { error ->
                    if (error.message?.contains("event not found", ignoreCase = true) == true) {
                        Log.w(TAG, "Event $eventId not found in vault, removing locally")
                        feedRepository.removeEventLocally(eventId)
                        val allCached = feedRepository.getCachedEvents()
                        val filtered = filterForDisplay(allCached)
                        _state.value = if (filtered.isEmpty()) {
                            FeedState.Empty
                        } else {
                            FeedState.Loaded(
                                events = filtered,
                                hasMore = false,
                                unreadCount = filtered.count { it.isUnread },
                                isOffline = !feedRepository.isOnline.value
                            )
                        }
                        _effects.emit(FeedEffect.ShowActionSuccess("Event removed"))
                    } else {
                        Log.e(TAG, "Failed to delete event", error)
                        _effects.emit(FeedEffect.ShowError("Failed to delete event"))
                    }
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

    /**
     * Sync guide catalog to vault. Idempotent - vault only creates events
     * for new/updated guides. Waits for vault response before returning
     * so that subsequent feed loads see the newly created events.
     */
    private suspend fun syncGuides() {
        try {
            val systemFields = personalDataStore.getSystemFields()
            val userName = systemFields?.firstName ?: ""

            val guidesPayload = com.google.gson.JsonObject().apply {
                val guidesArray = com.google.gson.JsonArray()
                GuideCatalog.guides.forEach { guide ->
                    val guideObj = com.google.gson.JsonObject().apply {
                        addProperty("guide_id", guide.guideId)
                        addProperty("title", guide.title)
                        addProperty("message", guide.message)
                        addProperty("order", guide.order)
                        addProperty("priority", guide.priority)
                        addProperty("version", guide.version)
                        addProperty("user_name", userName)
                    }
                    guidesArray.add(guideObj)
                }
                add("guides", guidesArray)
            }

            val response = ownerSpaceClient.sendAndAwaitResponse(
                messageType = "guide.sync",
                payload = guidesPayload,
                timeoutMs = 15000
            )
            if (response != null) {
                Log.i(TAG, "Guide sync complete: $response")
            } else {
                Log.w(TAG, "Guide sync timed out")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error syncing guides", e)
        }
    }
}
