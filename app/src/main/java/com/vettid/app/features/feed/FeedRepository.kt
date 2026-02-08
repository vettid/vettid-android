package com.vettid.app.features.feed

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vettid.app.core.nats.FeedClient
import com.vettid.app.core.nats.FeedEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for feed events with local caching and sync support.
 *
 * Uses EncryptedSharedPreferences for local storage (consistent with codebase architecture).
 * Supports:
 * - Offline access to cached events
 * - Incremental sync via sequence numbers
 * - Background sync coordination
 */
@Singleton
class FeedRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val feedClient: FeedClient
) {
    private val gson = Gson()
    private val syncMutex = Mutex()

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        private const val TAG = "FeedRepository"
        private const val PREFS_NAME = "feed_cache"
        private const val KEY_EVENTS = "cached_events"
        private const val KEY_LAST_SEQUENCE = "last_sync_sequence"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_UNREAD_COUNT = "unread_count"

        // Sync thresholds
        private const val SYNC_STALE_THRESHOLD_MS = 5 * 60 * 1000L  // 5 minutes
        private const val MAX_CACHED_EVENTS = 500
        private const val SYNC_TIMEOUT_MS = 15_000L  // 15 seconds max for sync operation
    }

    // MARK: - Public API

    /**
     * Get feed events, using cache if available, with optional refresh.
     *
     * @param forceRefresh If true, fetch from server even if cache is fresh
     * @return List of feed events (from cache or server)
     */
    suspend fun getFeed(forceRefresh: Boolean = false): Result<List<FeedEvent>> {
        // Check if we have fresh cached data
        val cachedEvents = getCachedEvents()
        val lastSyncTime = getLastSyncTime()
        val isCacheStale = System.currentTimeMillis() - lastSyncTime > SYNC_STALE_THRESHOLD_MS

        if (!forceRefresh && cachedEvents.isNotEmpty() && !isCacheStale) {
            Log.d(TAG, "Returning ${cachedEvents.size} cached events")
            return Result.success(cachedEvents)
        }

        // Try to sync from server
        return syncMutex.withLock {
            _syncState.value = SyncState.SYNCING

            try {
                val result = if (getLastSequence() > 0 && !forceRefresh) {
                    // Incremental sync
                    incrementalSync()
                } else {
                    // Full fetch
                    fullFetch()
                }

                _syncState.value = if (result.isSuccess) SyncState.IDLE else SyncState.ERROR
                _isOnline.value = result.isSuccess

                // If sync failed but we have cache, return cache
                if (result.isFailure && cachedEvents.isNotEmpty()) {
                    Log.w(TAG, "Sync failed, returning cached events", result.exceptionOrNull())
                    return@withLock Result.success(cachedEvents)
                }

                result
            } catch (e: CancellationException) {
                // Reset sync state on cancellation so UI doesn't get stuck
                _syncState.value = SyncState.IDLE
                throw e
            }
        }
    }

    /**
     * Get cached events without network request.
     */
    fun getCachedEvents(): List<FeedEvent> {
        val json = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<FeedEvent>>() {}.type
            gson.fromJson<List<FeedEvent>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse cached events", e)
            emptyList()
        }
    }

    /**
     * Get cached unread count.
     */
    fun getCachedUnreadCount(): Int {
        return prefs.getInt(KEY_UNREAD_COUNT, 0)
    }

    /**
     * Perform incremental sync using sequence numbers.
     * Call this from background worker or pull-to-refresh.
     */
    suspend fun sync(): Result<SyncResult> {
        // Don't use mutex if job is already cancelled
        return try {
            syncMutex.withLock {
                _syncState.value = SyncState.SYNCING

                try {
                    // Wrap in timeout to prevent indefinite hanging
                    val syncResult = withTimeoutOrNull(SYNC_TIMEOUT_MS) {
                        val lastSequence = getLastSequence()
                        feedClient.sync(sinceSequence = lastSequence)
                    }

                    // Handle timeout
                    if (syncResult == null) {
                        Log.w(TAG, "Sync timed out after ${SYNC_TIMEOUT_MS}ms")
                        _syncState.value = SyncState.ERROR
                        _isOnline.value = false
                        return@withLock Result.failure(Exception("Sync timed out"))
                    }

                    val result = syncResult

                    result.fold(
                        onSuccess = { response ->
                            val cachedEvents = getCachedEvents().toMutableList()
                            val existingIds = cachedEvents.map { it.eventId }.toSet()

                            var newCount = 0
                            var updatedCount = 0
                            var deletedCount = 0

                            for (event in response.events) {
                                when {
                                    event.feedStatus == "deleted" -> {
                                        cachedEvents.removeAll { it.eventId == event.eventId }
                                        deletedCount++
                                    }
                                    event.eventId in existingIds -> {
                                        // Update existing event
                                        val index = cachedEvents.indexOfFirst { it.eventId == event.eventId }
                                        if (index >= 0) {
                                            cachedEvents[index] = event
                                            updatedCount++
                                        }
                                    }
                                    else -> {
                                        // New event
                                        cachedEvents.add(event)
                                        newCount++
                                    }
                                }
                            }

                            // Sort by priority (desc) then created_at (desc)
                            val sortedEvents = cachedEvents.sortedWith(
                                compareByDescending<FeedEvent> { it.priority }
                                    .thenByDescending { it.createdAt }
                            )

                            // Limit cache size
                            val trimmedEvents = sortedEvents.take(MAX_CACHED_EVENTS)

                            // Save updated cache
                            saveEvents(trimmedEvents)
                            setLastSequence(response.latestSequence)
                            setLastSyncTime(System.currentTimeMillis())

                            _syncState.value = SyncState.IDLE
                            _isOnline.value = true

                            Log.i(TAG, "Sync complete: +$newCount new, $updatedCount updated, -$deletedCount deleted")
                            Result.success(SyncResult(
                                newEvents = newCount,
                                updatedEvents = updatedCount,
                                deletedEvents = deletedCount,
                                totalEvents = trimmedEvents.size,
                                hasMore = response.hasMore
                            ))
                        },
                        onFailure = { error ->
                            // Rethrow cancellation exceptions
                            if (error is CancellationException) throw error
                            Log.e(TAG, "Sync failed", error)
                            _syncState.value = SyncState.ERROR
                            _isOnline.value = false
                            Result.failure(error)
                        }
                    )
                } catch (e: CancellationException) {
                    // Reset sync state on cancellation so UI doesn't get stuck
                    _syncState.value = SyncState.IDLE
                    throw e
                }
            }
        } catch (e: CancellationException) {
            // Let cancellation propagate normally
            Log.d(TAG, "Sync cancelled")
            throw e
        }
    }

    /**
     * Update a cached event in place.
     */
    fun updateEventLocally(eventId: String, transform: (FeedEvent) -> FeedEvent) {
        val events = getCachedEvents().map { if (it.eventId == eventId) transform(it) else it }
        saveEvents(events)
    }

    /**
     * Remove an event from local cache.
     */
    fun removeEventLocally(eventId: String) {
        val events = getCachedEvents().filterNot { it.eventId == eventId }
        saveEvents(events)
    }

    /**
     * Check if cache data is stale.
     */
    fun isCacheStale(): Boolean {
        val lastSyncTime = getLastSyncTime()
        return System.currentTimeMillis() - lastSyncTime > SYNC_STALE_THRESHOLD_MS
    }

    /**
     * Get last sync time as epoch millis.
     */
    fun getLastSyncTime(): Long {
        return prefs.getLong(KEY_LAST_SYNC_TIME, 0)
    }

    /**
     * Clear all cached data.
     */
    fun clearCache() {
        prefs.edit()
            .remove(KEY_EVENTS)
            .remove(KEY_LAST_SEQUENCE)
            .remove(KEY_LAST_SYNC_TIME)
            .remove(KEY_UNREAD_COUNT)
            .apply()
    }

    // MARK: - Private Helpers

    private suspend fun fullFetch(): Result<List<FeedEvent>> {
        Log.d(TAG, "Performing full fetch")
        return feedClient.listFeed(status = null, limit = 100).fold(
            onSuccess = { response ->
                val sortedEvents = response.events.sortedWith(
                    compareByDescending<FeedEvent> { it.priority }
                        .thenByDescending { it.createdAt }
                )
                saveEvents(sortedEvents)
                setLastSequence(sortedEvents.maxOfOrNull { it.syncSequence } ?: 0)
                setLastSyncTime(System.currentTimeMillis())
                _isOnline.value = true
                Result.success(sortedEvents)
            },
            onFailure = { error ->
                _isOnline.value = false
                Result.failure(error)
            }
        )
    }

    /**
     * Incremental sync without acquiring mutex (called from getFeed which already holds it).
     */
    private suspend fun incrementalSync(): Result<List<FeedEvent>> {
        Log.d(TAG, "Performing incremental sync from sequence ${getLastSequence()}")
        try {
            val syncResult = withTimeoutOrNull(SYNC_TIMEOUT_MS) {
                val lastSequence = getLastSequence()
                feedClient.sync(sinceSequence = lastSequence)
            }

            if (syncResult == null) {
                Log.w(TAG, "Incremental sync timed out after ${SYNC_TIMEOUT_MS}ms")
                return Result.failure(Exception("Sync timed out"))
            }

            return syncResult.fold(
                onSuccess = { response ->
                    val cachedEvents = getCachedEvents().toMutableList()
                    val existingIds = cachedEvents.map { it.eventId }.toSet()

                    var newCount = 0
                    var updatedCount = 0
                    var deletedCount = 0

                    for (event in response.events) {
                        when {
                            event.feedStatus == "deleted" -> {
                                cachedEvents.removeAll { it.eventId == event.eventId }
                                deletedCount++
                            }
                            event.eventId in existingIds -> {
                                val index = cachedEvents.indexOfFirst { it.eventId == event.eventId }
                                if (index >= 0) {
                                    cachedEvents[index] = event
                                    updatedCount++
                                }
                            }
                            else -> {
                                cachedEvents.add(event)
                                newCount++
                            }
                        }
                    }

                    val sortedEvents = cachedEvents.sortedWith(
                        compareByDescending<FeedEvent> { it.priority }
                            .thenByDescending { it.createdAt }
                    )
                    val trimmedEvents = sortedEvents.take(MAX_CACHED_EVENTS)

                    saveEvents(trimmedEvents)
                    setLastSequence(response.latestSequence)
                    setLastSyncTime(System.currentTimeMillis())
                    _isOnline.value = true

                    Log.i(TAG, "Incremental sync complete: +$newCount new, $updatedCount updated, -$deletedCount deleted")
                    Result.success(trimmedEvents)
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    Log.e(TAG, "Incremental sync failed", error)
                    _isOnline.value = false
                    Result.failure(error)
                }
            )
        } catch (e: CancellationException) {
            throw e
        }
    }

    private fun saveEvents(events: List<FeedEvent>) {
        val json = gson.toJson(events)
        val unreadCount = events.count { it.isUnread }
        prefs.edit()
            .putString(KEY_EVENTS, json)
            .putInt(KEY_UNREAD_COUNT, unreadCount)
            .apply()
    }

    private fun getLastSequence(): Long {
        return prefs.getLong(KEY_LAST_SEQUENCE, 0)
    }

    private fun setLastSequence(sequence: Long) {
        prefs.edit().putLong(KEY_LAST_SEQUENCE, sequence).apply()
    }

    private fun setLastSyncTime(timeMillis: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, timeMillis).apply()
    }
}

/**
 * Sync state for UI feedback.
 */
enum class SyncState {
    IDLE,
    SYNCING,
    ERROR
}

/**
 * Result of a sync operation.
 */
data class SyncResult(
    val newEvents: Int,
    val updatedEvents: Int,
    val deletedEvents: Int,
    val totalEvents: Int,
    val hasMore: Boolean
)
