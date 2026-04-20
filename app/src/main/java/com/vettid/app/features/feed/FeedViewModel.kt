package com.vettid.app.features.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.ConnectionsClient
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
    private val personalDataStore: PersonalDataStore,
    private val connectionsClient: ConnectionsClient,
    private val callManager: com.vettid.app.features.calling.CallManager
) : ViewModel() {

    private val _state = MutableStateFlow<FeedState>(FeedState.Loading)
    val state: StateFlow<FeedState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<FeedEffect>()
    val effects: SharedFlow<FeedEffect> = _effects.asSharedFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Audit events toggle (hidden by default) - kept for backward compatibility but no longer shown in feed
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

    // Connection event types that get grouped under a single ConnectionItem
    // IMPORTANT: must be declared before init{} since showCachedFeed() uses it
    // Cached connections from connection.list (primary data source for cards)
    private var cachedConnections: List<com.vettid.app.core.nats.ConnectionRecord> = emptyList()

    // Event types that belong in activity feed (not grouped under connections)
    private val activityEventTypes = setOf(
        EventTypes.GUIDE,
        EventTypes.SECURITY_ALERT,
        EventTypes.SECURITY_MIGRATION,
        EventTypes.BACKUP_COMPLETE,
        EventTypes.VAULT_STATUS,
        EventTypes.HANDLER_COMPLETE,
        EventTypes.AGENT_SECRET_REQUEST,
        EventTypes.AGENT_ACTION_REQUEST,
        EventTypes.AGENT_APPROVAL_REQUESTED
    )

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
        // Load cached connections for instant display
        val cachedConns = feedRepository.getCachedConnections()
        if (cachedConns.isNotEmpty()) {
            cachedConnections = cachedConns
        }

        val cached = feedRepository.getCachedEvents()
        if (cached.isNotEmpty() || cachedConns.isNotEmpty()) {
            val displayItems = buildDisplayItems(cached)
            _state.value = if (displayItems.isEmpty()) {
                FeedState.Empty
            } else {
                FeedState.Loaded(
                    items = displayItems,
                    hasMore = false,
                    unreadCount = displayItems.count { it.isUnread },
                    isOffline = true
                )
            }
            Log.d(TAG, "Showing ${displayItems.size} of ${cached.size} cached feed items")
        }
    }

    fun toggleAuditEvents() {
        _showAuditEvents.value = !_showAuditEvents.value
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Build the complete display from two independent sources:
     * - Connection cards from cachedConnections (connection.list with message previews)
     * - Activity items from feed events (non-connection events only)
     *
     * These two sections are INDEPENDENT — no timing dependencies.
     */
    private fun buildDisplayItems(events: List<FeedEvent>): List<FeedDisplayItem> {
        return buildConnectionCards() + buildActivityItems(events)
    }

    /**
     * Build connection cards from cachedConnections ONLY.
     * No dependency on feed events — message previews come from the vault's connection.list.
     */
    private fun buildConnectionCards(): List<FeedDisplayItem.ConnectionCard> {
        val query = _searchQuery.value.trim()

        return cachedConnections.mapNotNull { conn ->
            // Defensive filter: connection IDs with a "/" collide with
            // the Compose nav route template `connections/{connectionId}`
            // and crash the app on tap. The v3 enclave shipped the
            // VettID system connection as "system/vettid"; v4 renames
            // it and cleans up the orphan, but caches sometimes linger.
            // Skip any such row rather than rendering an untappable card.
            if (conn.connectionId.contains('/')) {
                return@mapNotNull null
            }
            val peerName = listOfNotNull(
                conn.peerProfile?.firstName, conn.peerProfile?.lastName
            ).joinToString(" ").trim().ifEmpty { conn.label }

            // Search filter
            if (query.isNotEmpty()) {
                val matchesQuery = peerName.contains(query, ignoreCase = true) ||
                    (conn.peerProfile?.email?.contains(query, ignoreCase = true) == true)
                if (!matchesQuery) return@mapNotNull null
            }

            val needsReview = conn.status == "pending" && conn.needsAttention
            val hasAccepted = conn.status == "pending" && !conn.needsAttention

            // Preview comes from vault's connection.list (no event dependency)
            val preview = when {
                needsReview -> "Wants to connect"
                hasAccepted -> "Waiting for response"
                conn.lastMessagePreview != null -> conn.lastMessagePreview
                conn.status == "active" -> "Connected"
                conn.status == "revoked" -> "Connection revoked"
                conn.status == "rejected" -> "Declined"
                else -> ""
            }

            val sortTime = conn.lastMessageAt?.let {
                try { java.time.Instant.parse(it).toEpochMilli() } catch (_: Exception) { null }
            } ?: try { java.time.Instant.parse(conn.createdAt).toEpochMilli() } catch (_: Exception) { System.currentTimeMillis() }

            // Prefer the merged last-activity fields from the vault
            // (messages + calls); fall back to the legacy message-only
            // fields for older enclaves that don't send them.
            val activityType = conn.lastActivityType
                ?: if (conn.lastMessagePreview != null) "message" else "connection"
            val activityDirection = conn.lastActivityDirection ?: conn.lastMessageDirection

            FeedDisplayItem.ConnectionCard(
                connectionId = conn.connectionId,
                peerName = peerName,
                peerPhotoBase64 = conn.peerProfile?.photo,
                peerEmail = conn.peerProfile?.email,
                connectionStatus = conn.status,
                direction = conn.direction,
                needsReview = needsReview,
                hasAccepted = hasAccepted,
                connectionType = conn.connectionType,
                e2eReady = conn.e2eReady,
                lastActivityPreview = preview,
                lastActivityType = activityType,
                lastActivityDirection = activityDirection,
                lastActivitySubtype = conn.lastActivitySubtype,
                lastActivityOutcome = conn.lastActivityOutcome,
                missedCallCount = conn.missedCallCount,
                unreadCount = conn.unreadMessageCount,
                sortTimestamp = sortTime,
                // Unread badges the card if: pending review, unread
                // messages exist, or there's an unacknowledged missed
                // incoming call.
                isUnread = needsReview || conn.unreadMessageCount > 0 || conn.missedCallCount > 0
            )
        }.sortedWith(
            compareByDescending<FeedDisplayItem.ConnectionCard> { if (it.needsReview) 1 else 0 }
                .thenByDescending { it.sortTimestamp }
        )
    }

    /**
     * Build activity items from feed events ONLY.
     * Excludes ALL connection and message events — those belong in connection cards.
     */
    private fun buildActivityItems(events: List<FeedEvent>): List<FeedDisplayItem.EventItem> {
        val query = _searchQuery.value.trim()

        return events
            .filter { it.feedStatus != FeedStatus.ARCHIVED && it.feedStatus != FeedStatus.DELETED }
            .filter { event ->
                if (!_showAuditEvents.value && event.feedStatus == FeedStatus.HIDDEN) return@filter false
                // Only include activity events (not connection/message events)
                if (event.eventType !in activityEventTypes) return@filter false
                // Search filter
                if (query.isNotEmpty()) {
                    return@filter event.title.contains(query, ignoreCase = true) ||
                        (event.message?.contains(query, ignoreCase = true) == true)
                }
                true
            }
            .map { event ->
                FeedDisplayItem.EventItem(
                    event = event,
                    sortTimestamp = event.createdAt,
                    isUnread = event.isUnread
                )
            }
            .sortedByDescending { it.sortTimestamp }
    }

    /**
     * Extract peer name from event title.
     * Title format: "From Al Liebl" or "To Al Liebl" — strip the prefix.
     */
    private fun extractPeerName(event: FeedEvent): String {
        val title = event.title
        return when {
            title.startsWith("From ", ignoreCase = true) -> title.removePrefix("From ").removePrefix("from ")
            title.startsWith("To ", ignoreCase = true) -> title.removePrefix("To ").removePrefix("to ")
            else -> event.metadata?.get("peer_name") ?: title
        }
    }

    /**
     * Build a short preview string for the latest activity in a connection.
     */
    private fun buildActivityPreview(event: FeedEvent): String {
        return when (event.eventType) {
            EventTypes.MESSAGE_RECEIVED, EventTypes.MESSAGE_SENT -> {
                event.message ?: "New message"
            }
            EventTypes.CALL_INCOMING -> "Incoming call"
            EventTypes.CALL_COMPLETED -> "Call ended"
            EventTypes.CALL_MISSED -> "Missed call"
            EventTypes.TRANSFER_REQUEST -> event.message ?: "Payment request"
            EventTypes.CONNECTION_ACCEPTED -> "Connection established"
            else -> event.message ?: event.title
        }
    }

    /**
     * Map event type to a general activity type for the connection card.
     */
    private fun mapActivityType(eventType: String): String {
        return when (eventType) {
            EventTypes.MESSAGE_RECEIVED, EventTypes.MESSAGE_SENT -> "message"
            EventTypes.CALL_INCOMING, EventTypes.CALL_COMPLETED, EventTypes.CALL_MISSED -> "call"
            EventTypes.TRANSFER_REQUEST -> "payment"
            EventTypes.CONNECTION_ACCEPTED -> "connection"
            else -> "other"
        }
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
                        val displayItems = buildDisplayItems(allCached)
                        if (displayItems.isEmpty()) {
                            _state.value = FeedState.Empty
                        } else {
                            _state.value = FeedState.Loaded(
                                items = displayItems,
                                hasMore = false,
                                unreadCount = displayItems.count { it.isUnread },
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
                    delay(200)
                    // Sync guides in parallel — don't block feed loading
                    launch { syncGuides() }
                    // Connections are loaded in loadFeed() via refreshConnections()
                    loadFeedAndWait()
                    isInitialLoadComplete = true
                }
            }
        }
    }

    // Connection photos now come from cachedConnections (loaded in refreshConnections)

    /**
     * Rebuild display items from cache and update UI state.
     * Used after loading connection photos or other cache updates.
     */
    private fun rebuildDisplayItems() {
        val currentState = _state.value
        if (currentState is FeedState.Loaded) {
            val allCached = feedRepository.getCachedEvents()
            val displayItems = buildDisplayItems(allCached)
            _state.value = currentState.copy(
                items = displayItems,
                unreadCount = displayItems.count { it.isUnread }
            )
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
                // Refresh connections FIRST — never rebuild display without connection context
                val connectionsLoaded = refreshConnections()
                if (!connectionsLoaded && cachedConnections.isEmpty()) {
                    Log.w(TAG, "Skipping feed rebuild — no connections available")
                    return@launch
                }

                // Sync events
                feedRepository.sync()
                    .onSuccess { result ->
                        Log.d(TAG, "Silent sync complete: +${result.newEvents} new, ${result.updatedEvents} updated")
                    }

                // Rebuild display with guaranteed connection context
                val events = feedRepository.getCachedEvents()
                val newDisplayItems = buildDisplayItems(events)

                // Diff check — only update UI if items actually changed
                val currentItems = (_state.value as? FeedState.Loaded)?.items
                if (currentItems != null && itemsEqual(currentItems, newDisplayItems)) {
                    return@launch // No change, skip recomposition
                }

                if (newDisplayItems.isEmpty()) {
                    _state.value = FeedState.Empty
                } else {
                    _state.value = FeedState.Loaded(
                        items = newDisplayItems,
                        hasMore = false,
                        unreadCount = newDisplayItems.count { it.isUnread },
                        isOffline = false
                    )
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

        // Connection acceptance notifications — refresh connections immediately
        viewModelScope.launch {
            ownerSpaceClient.connectionAcceptances.collect { acceptance ->
                Log.d(TAG, "Peer accepted connection: ${acceptance.connectionId}")
                refreshConnections()
                rebuildDisplayItems()
            }
        }

        // Connection status updates (activated, key-exchanged, rejected)
        viewModelScope.launch {
            ownerSpaceClient.connectionStatusUpdates.collect { update ->
                Log.d(TAG, "Connection status update: ${update.connectionId} -> ${update.type}")
                refreshConnections()
                rebuildDisplayItems()
            }
        }

        // Incoming messages — update connection card immediately (no wait for sync)
        viewModelScope.launch {
            ownerSpaceClient.incomingMessages.collect { message ->
                Log.d(TAG, "Incoming message for ${message.connectionId} — updating card")
                // Update cached connection's unread count and preview in memory
                cachedConnections = cachedConnections.map { conn ->
                    if (conn.connectionId == message.connectionId) {
                        conn.copy(
                            unreadMessageCount = conn.unreadMessageCount + 1,
                            lastMessagePreview = message.content?.take(100) ?: "New message",
                            lastMessageAt = message.sentAt
                        )
                    } else conn
                }
                rebuildDisplayItems()
            }
        }
    }

    private fun handleStatusChange(eventId: String, newStatus: String) {
        // Update repository cache
        when (newStatus) {
            "archived", "deleted" -> feedRepository.removeEventLocally(eventId)
        }

        // Re-build display items from cache to reflect the change
        val currentState = _state.value
        if (currentState is FeedState.Loaded) {
            when (newStatus) {
                "archived", "deleted" -> {
                    // Re-filter from cache (the event is removed or marked)
                    val allCached = feedRepository.getCachedEvents()
                    val displayItems = buildDisplayItems(allCached)
                    _state.value = if (displayItems.isEmpty()) {
                        FeedState.Empty
                    } else {
                        currentState.copy(
                            items = displayItems,
                            unreadCount = displayItems.count { it.isUnread }
                        )
                    }
                }
                "read" -> {
                    // Re-filter from cache to recalculate connection unread counts
                    val allCached = feedRepository.getCachedEvents()
                    val displayItems = buildDisplayItems(allCached)
                    _state.value = currentState.copy(
                        items = displayItems,
                        unreadCount = displayItems.count { it.isUnread }
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
                    val displayItems = buildDisplayItems(events)
                    _state.value = if (displayItems.isEmpty()) {
                        FeedState.Empty
                    } else {
                        FeedState.Loaded(
                            items = displayItems,
                            hasMore = false,
                            unreadCount = displayItems.count { it.isUnread },
                            isOffline = !feedRepository.isOnline.value
                        )
                    }
                    Log.d(TAG, "loadFeedAndWait: loaded ${displayItems.size} items on attempt ${attempt + 1}")
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
        val displayItems = buildDisplayItems(cached)
        _state.value = if (displayItems.isNotEmpty()) {
            Log.d(TAG, "loadFeedAndWait: showing ${displayItems.size} cached items after retries exhausted")
            FeedState.Loaded(
                items = displayItems,
                hasMore = false,
                unreadCount = displayItems.count { it.isUnread },
                isOffline = true
            )
        } else {
            FeedState.Error("Unable to load feed. Pull down to retry.")
        }
    }

    /**
     * Compare two display item lists by key to avoid unnecessary recomposition.
     */
    private fun itemsEqual(a: List<FeedDisplayItem>, b: List<FeedDisplayItem>): Boolean {
        if (a.size != b.size) return false
        return a.zip(b).all { (itemA, itemB) ->
            when {
                itemA is FeedDisplayItem.ConnectionCard && itemB is FeedDisplayItem.ConnectionCard ->
                    itemA.connectionId == itemB.connectionId &&
                    itemA.connectionStatus == itemB.connectionStatus &&
                    itemA.needsReview == itemB.needsReview &&
                    itemA.lastActivityPreview == itemB.lastActivityPreview &&
                    itemA.unreadCount == itemB.unreadCount
                itemA is FeedDisplayItem.EventItem && itemB is FeedDisplayItem.EventItem ->
                    itemA.event.eventId == itemB.event.eventId &&
                    itemA.event.feedStatus == itemB.event.feedStatus
                else -> false
            }
        }
    }

    /**
     * Refresh the connection list cache from the vault.
     * Returns true if connections were successfully loaded.
     */
    private suspend fun refreshConnections(): Boolean {
        return feedRepository.getConnections()
            .onSuccess { connections ->
                cachedConnections = connections
                feedRepository.cacheConnections(connections)
                Log.d(TAG, "Loaded ${connections.size} connections")
            }
            .onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.w(TAG, "Failed to load connections: ${error.message}")
            }
            .isSuccess
    }

    fun loadFeed() {
        viewModelScope.launch {
            // Only show loading if we don't have cached data
            val currentState = _state.value
            if (currentState !is FeedState.Loaded) {
                _state.value = FeedState.Loading
            }
            try {
                // Fetch connections first (primary data source for cards)
                refreshConnections()

                // Use repository which provides offline caching
                feedRepository.getFeed(forceRefresh = false)
                    .onSuccess { events ->
                        val displayItems = buildDisplayItems(events)
                        if (displayItems.isEmpty()) {
                            _state.value = FeedState.Empty
                        } else {
                            _state.value = FeedState.Loaded(
                                items = displayItems,
                                hasMore = false, // Repository handles pagination
                                unreadCount = displayItems.count { it.isUnread },
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
                        val displayItems = buildDisplayItems(cached)
                        if (displayItems.isNotEmpty()) {
                            _state.value = FeedState.Loaded(
                                items = displayItems,
                                hasMore = false,
                                unreadCount = displayItems.count { it.isUnread },
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
                        val displayItems = buildDisplayItems(events)
                        if (displayItems.isEmpty()) {
                            _state.value = FeedState.Empty
                        } else {
                            _state.value = FeedState.Loaded(
                                items = displayItems,
                                hasMore = result.hasMore,
                                unreadCount = displayItems.count { it.isUnread },
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

    /**
     * Navigate to an event by ID (used from notification snackbar "View" action).
     */
    fun navigateToEventById(eventId: String) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState is FeedState.Loaded) {
                // Search through items for the event
                val eventItem = currentState.items.filterIsInstance<FeedDisplayItem.EventItem>()
                    .find { it.event.eventId == eventId }
                if (eventItem != null) {
                    onEventClick(eventItem.event)
                } else {
                    // Could be a connection event — look up in cache
                    val cachedEvent = feedRepository.getCachedEvents().find { it.eventId == eventId }
                    if (cachedEvent != null) {
                        onEventClick(cachedEvent)
                    } else {
                        _effects.emit(FeedEffect.ShowError("Event not found"))
                    }
                }
            }
        }
    }

    /**
     * Handle click on a FeedDisplayItem.
     * ConnectionItem navigates to conversation; EventItem delegates to onEventClick.
     */
    fun onDisplayItemClick(item: FeedDisplayItem) {
        viewModelScope.launch {
            when (item) {
                is FeedDisplayItem.ConnectionCard -> {
                    when {
                        item.needsReview -> _effects.emit(FeedEffect.NavigateToConnectionReview(item.connectionId))
                        item.connectionStatus == "active" -> _effects.emit(FeedEffect.NavigateToConversation(item.connectionId))
                        item.hasAccepted -> _effects.emit(FeedEffect.NavigateToConnectionDetail(item.connectionId))
                        else -> _effects.emit(FeedEffect.NavigateToConnectionDetail(item.connectionId))
                    }
                }
                is FeedDisplayItem.EventItem -> {
                    onEventClick(item.event)
                }
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
                EventTypes.MESSAGE_RECEIVED, EventTypes.MESSAGE_SENT -> {
                    val connectionId = event.metadata?.get("connection_id") ?: sourceId
                    if (connectionId != null) {
                        _effects.emit(FeedEffect.NavigateToConversation(connectionId))
                    } else {
                        _effects.emit(FeedEffect.ShowEventDetail(event))
                    }
                }
                EventTypes.CONNECTION_ACCEPTED -> {
                    val connectionId = event.metadata?.get("connection_id") ?: sourceId
                    if (connectionId != null) {
                        _effects.emit(FeedEffect.NavigateToConnectionReview(connectionId, event.eventId))
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
                "security.migration" -> {
                    // Deferred vault-update reminder — bounce back into
                    // the migration flow so the user can review the
                    // details and actually approve the update instead of
                    // just reading a stale event detail dialog.
                    _effects.emit(FeedEffect.ShowVaultUpdatePrompt)
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
                    // Update the repository cache first
                    val now = System.currentTimeMillis() / 1000
                    feedRepository.updateEventLocally(eventId) { it.copy(feedStatus = FeedStatus.READ, readAt = now) }

                    // Re-build display items from cache to reflect updated read status
                    val currentState = _state.value
                    if (currentState is FeedState.Loaded) {
                        val allCached = feedRepository.getCachedEvents()
                        val displayItems = buildDisplayItems(allCached)
                        _state.value = currentState.copy(
                            items = displayItems,
                            unreadCount = displayItems.count { it.isUnread }
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
                // Get unread event IDs from the cache (items may be grouped)
                val unreadIds = feedRepository.getCachedEvents()
                    .filter { it.isUnread }
                    .map { it.eventId }
                if (unreadIds.isNotEmpty()) {
                    feedClient.markMultipleRead(unreadIds)
                        .onSuccess {
                            // Re-build display items
                            val now = System.currentTimeMillis() / 1000
                            unreadIds.forEach { id ->
                                feedRepository.updateEventLocally(id) { it.copy(feedStatus = FeedStatus.READ, readAt = now) }
                            }
                            val allCached = feedRepository.getCachedEvents()
                            val displayItems = buildDisplayItems(allCached)
                            _state.value = currentState.copy(
                                items = displayItems,
                                unreadCount = 0
                            )
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
                    val displayItems = buildDisplayItems(allCached)
                    _state.value = if (displayItems.isEmpty()) {
                        FeedState.Empty
                    } else {
                        FeedState.Loaded(
                            items = displayItems,
                            hasMore = false,
                            unreadCount = displayItems.count { it.isUnread },
                            isOffline = !feedRepository.isOnline.value
                        )
                    }
                    _effects.emit(FeedEffect.ShowActionSuccess("Event archived"))
                    // No immediate silentRefresh — the local cache is already updated.
                    // A premature sync can overwrite the local "archived" status with the
                    // server's "active" status if the vault hasn't finished processing yet.
                    // The periodic 30s refresh will pick up server-side changes.
                }
                .onFailure { error ->
                    // If vault says "event not found", the event was already deleted/cleaned up
                    // server-side. Remove it locally so the user isn't stuck with an un-archivable item.
                    if (error.message?.contains("event not found", ignoreCase = true) == true) {
                        Log.w(TAG, "Event $eventId not found in vault, removing locally")
                        feedRepository.removeEventLocally(eventId)
                        val allCached = feedRepository.getCachedEvents()
                        val displayItems = buildDisplayItems(allCached)
                        _state.value = if (displayItems.isEmpty()) {
                            FeedState.Empty
                        } else {
                            FeedState.Loaded(
                                items = displayItems,
                                hasMore = false,
                                unreadCount = displayItems.count { it.isUnread },
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

    fun togglePriority(eventId: String) {
        viewModelScope.launch {
            val event = feedRepository.getCachedEvents().find { it.eventId == eventId } ?: return@launch
            val newPriority = if (event.priority >= 1) 0 else 1 // Toggle between NORMAL and HIGH

            feedClient.setEventPriority(eventId, newPriority)
                .onSuccess {
                    feedRepository.updateEventLocally(eventId) { it.copy(priority = newPriority) }
                    val allCached = feedRepository.getCachedEvents()
                    val displayItems = buildDisplayItems(allCached)
                    _state.value = if (displayItems.isEmpty()) {
                        FeedState.Empty
                    } else {
                        FeedState.Loaded(
                            items = displayItems,
                            hasMore = false,
                            unreadCount = displayItems.count { it.isUnread },
                            isOffline = !feedRepository.isOnline.value
                        )
                    }
                    _effects.emit(FeedEffect.ShowActionSuccess(
                        if (newPriority >= 1) "Pinned to top" else "Unpinned"
                    ))
                }
                .onFailure {
                    Log.e(TAG, "Failed to update priority", it)
                    _effects.emit(FeedEffect.ShowError("Failed to update priority"))
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
                    val displayItems = buildDisplayItems(allCached)
                    _state.value = if (displayItems.isEmpty()) {
                        FeedState.Empty
                    } else {
                        FeedState.Loaded(
                            items = displayItems,
                            hasMore = false,
                            unreadCount = displayItems.count { it.isUnread },
                            isOffline = !feedRepository.isOnline.value
                        )
                    }
                    _effects.emit(FeedEffect.ShowActionSuccess("Event deleted"))

                    // Sync after short delay to pick up the new audit event
                    viewModelScope.launch {
                        delay(500)
                        silentRefresh()
                    }
                }
                .onFailure { error ->
                    if (error.message?.contains("event not found", ignoreCase = true) == true) {
                        Log.w(TAG, "Event $eventId not found in vault, removing locally")
                        feedRepository.removeEventLocally(eventId)
                        val allCached = feedRepository.getCachedEvents()
                        val displayItems = buildDisplayItems(allCached)
                        _state.value = if (displayItems.isEmpty()) {
                            FeedState.Empty
                        } else {
                            FeedState.Loaded(
                                items = displayItems,
                                hasMore = false,
                                unreadCount = displayItems.count { it.isUnread },
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

    /**
     * Accept a pending connection directly via connection.respond.
     */
    /**
     * Start a voice call with a connection.
     * Called after permission is granted by the UI.
     */
    fun startVoiceCall(connectionId: String) {
        viewModelScope.launch {
            val conn = cachedConnections.find { it.connectionId == connectionId }
            if (conn == null || conn.status != "active") {
                _effects.emit(FeedEffect.ShowError("Connection not available for calls"))
                return@launch
            }
            callManager.startCall(connectionId, conn.peerGuid, conn.label, com.vettid.app.features.calling.CallType.VOICE).fold(
                onSuccess = { },
                onFailure = { error ->
                    _effects.emit(FeedEffect.ShowError(error.message ?: "Failed to start call"))
                }
            )
        }
    }

    /**
     * Start a video call with a connection.
     * Called after permission is granted by the UI.
     */
    fun startVideoCall(connectionId: String) {
        viewModelScope.launch {
            val conn = cachedConnections.find { it.connectionId == connectionId }
            if (conn == null || conn.status != "active") {
                _effects.emit(FeedEffect.ShowError("Connection not available for calls"))
                return@launch
            }
            callManager.startCall(connectionId, conn.peerGuid, conn.label, com.vettid.app.features.calling.CallType.VIDEO).fold(
                onSuccess = { },
                onFailure = { error ->
                    _effects.emit(FeedEffect.ShowError(error.message ?: "Failed to start video call"))
                }
            )
        }
    }

    fun acceptConnection(connectionId: String) {
        viewModelScope.launch {
            connectionsClient.respond(connectionId, "accept")
                .onSuccess {
                    refreshConnections()
                    refresh()
                    _effects.emit(FeedEffect.ShowActionSuccess("Connection accepted"))
                }
                .onFailure {
                    Log.e(TAG, "Failed to accept connection", it)
                    _effects.emit(FeedEffect.ShowError("Accept failed: ${it.message}"))
                }
        }
    }

    /**
     * Decline a pending connection directly via connection.respond.
     */
    fun declineConnection(connectionId: String) {
        viewModelScope.launch {
            connectionsClient.respond(connectionId, "reject")
                .onSuccess {
                    refreshConnections()
                    refresh()
                    _effects.emit(FeedEffect.ShowActionSuccess("Connection declined"))
                }
                .onFailure {
                    Log.e(TAG, "Failed to decline connection", it)
                    _effects.emit(FeedEffect.ShowError("Decline failed: ${it.message}"))
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
