package com.vettid.app.features.connections.offline

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages offline operation queue for connections.
 *
 * Queues operations when offline and processes them when connectivity is restored.
 */
@Singleton
class OfflineQueueManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()

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

    private val _pendingOperations = MutableStateFlow<List<PendingOperation>>(emptyList())
    val pendingOperations: StateFlow<List<PendingOperation>> = _pendingOperations.asStateFlow()

    private val _syncStatus = MutableStateFlow(SyncStatus.IDLE)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    init {
        loadQueue()
    }

    /**
     * Queue an operation for later processing.
     */
    fun enqueue(operation: PendingOperation): String {
        val ops = _pendingOperations.value.toMutableList()
        ops.add(operation)
        _pendingOperations.value = ops
        saveQueue()
        return operation.id
    }

    /**
     * Queue accepting a connection for offline processing.
     */
    fun queueAcceptConnection(connectionId: String, payload: String): String {
        return enqueue(
            PendingOperation(
                id = UUID.randomUUID().toString(),
                type = OperationType.ACCEPT_CONNECTION,
                connectionId = connectionId,
                payload = payload,
                createdAt = Instant.now()
            )
        )
    }

    /**
     * Queue rejecting a connection for offline processing.
     */
    fun queueRejectConnection(connectionId: String, reason: String? = null): String {
        val payload = reason?.let { """{"reason":"$it"}""" } ?: "{}"
        return enqueue(
            PendingOperation(
                id = UUID.randomUUID().toString(),
                type = OperationType.REJECT_CONNECTION,
                connectionId = connectionId,
                payload = payload,
                createdAt = Instant.now()
            )
        )
    }

    /**
     * Queue a profile update for offline processing.
     */
    fun queueProfileUpdate(payload: String): String {
        return enqueue(
            PendingOperation(
                id = UUID.randomUUID().toString(),
                type = OperationType.UPDATE_PROFILE,
                connectionId = null,
                payload = payload,
                createdAt = Instant.now()
            )
        )
    }

    /**
     * Queue credential rotation for offline processing.
     */
    fun queueRotateCredentials(connectionId: String): String {
        return enqueue(
            PendingOperation(
                id = UUID.randomUUID().toString(),
                type = OperationType.ROTATE_CREDENTIALS,
                connectionId = connectionId,
                payload = "{}",
                createdAt = Instant.now()
            )
        )
    }

    /**
     * Mark an operation as completed and remove from queue.
     */
    fun markCompleted(operationId: String) {
        val ops = _pendingOperations.value.toMutableList()
        ops.removeAll { it.id == operationId }
        _pendingOperations.value = ops
        saveQueue()
    }

    /**
     * Mark an operation as failed and increment retry count.
     */
    fun markFailed(operationId: String, error: String) {
        val ops = _pendingOperations.value.toMutableList()
        val index = ops.indexOfFirst { it.id == operationId }
        if (index >= 0) {
            val op = ops[index]
            ops[index] = op.copy(
                retryCount = op.retryCount + 1,
                lastError = error,
                lastAttemptAt = Instant.now()
            )
            _pendingOperations.value = ops
            saveQueue()
        }
    }

    /**
     * Get operations ready for processing.
     *
     * Excludes operations that have been retried too many times or
     * were attempted too recently.
     */
    fun getProcessableOperations(): List<PendingOperation> {
        val now = Instant.now()
        return _pendingOperations.value.filter { op ->
            // Max 5 retries
            op.retryCount < MAX_RETRIES &&
                    // Exponential backoff: wait longer between retries
                    (op.lastAttemptAt == null ||
                            op.lastAttemptAt.plusSeconds(getBackoffSeconds(op.retryCount)).isBefore(now))
        }.sortedBy { it.createdAt }
    }

    /**
     * Check if there are pending operations.
     */
    fun hasPendingOperations(): Boolean = _pendingOperations.value.isNotEmpty()

    /**
     * Get count of pending operations.
     */
    fun pendingCount(): Int = _pendingOperations.value.size

    /**
     * Update sync status.
     */
    fun setSyncStatus(status: SyncStatus) {
        _syncStatus.value = status
    }

    /**
     * Clear all pending operations.
     */
    fun clearAll() {
        _pendingOperations.value = emptyList()
        saveQueue()
    }

    private fun loadQueue() {
        val json = prefs.getString(KEY_QUEUE, null) ?: return
        try {
            val type = object : TypeToken<List<PendingOperationJson>>() {}.type
            val jsonList: List<PendingOperationJson> = gson.fromJson(json, type)
            _pendingOperations.value = jsonList.map { it.toPendingOperation() }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to load offline queue", e)
        }
    }

    private fun saveQueue() {
        val jsonList = _pendingOperations.value.map { it.toJson() }
        val json = gson.toJson(jsonList)
        prefs.edit().putString(KEY_QUEUE, json).apply()
    }

    private fun getBackoffSeconds(retryCount: Int): Long {
        // Exponential backoff: 30s, 1m, 2m, 4m, 8m
        return (30L * (1L shl retryCount.coerceAtMost(4)))
    }

    companion object {
        private const val TAG = "OfflineQueueManager"
        private const val PREFS_NAME = "vettid_offline_queue"
        private const val KEY_QUEUE = "pending_operations"
        private const val MAX_RETRIES = 5
    }
}

// MARK: - Data Models

data class PendingOperation(
    val id: String,
    val type: OperationType,
    val connectionId: String?,
    val payload: String,
    val createdAt: Instant,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val lastAttemptAt: Instant? = null
)

enum class OperationType {
    ACCEPT_CONNECTION,
    REJECT_CONNECTION,
    UPDATE_PROFILE,
    SEND_MESSAGE,
    ROTATE_CREDENTIALS
}

enum class SyncStatus {
    IDLE,
    SYNCING,
    SUCCESS,
    ERROR
}

// JSON serialization helpers (Instant doesn't serialize well with Gson)
private data class PendingOperationJson(
    val id: String,
    val type: String,
    val connectionId: String?,
    val payload: String,
    val createdAtEpochSecond: Long,
    val retryCount: Int,
    val lastError: String?,
    val lastAttemptAtEpochSecond: Long?
)

private fun PendingOperation.toJson() = PendingOperationJson(
    id = id,
    type = type.name,
    connectionId = connectionId,
    payload = payload,
    createdAtEpochSecond = createdAt.epochSecond,
    retryCount = retryCount,
    lastError = lastError,
    lastAttemptAtEpochSecond = lastAttemptAt?.epochSecond
)

private fun PendingOperationJson.toPendingOperation() = PendingOperation(
    id = id,
    type = OperationType.valueOf(type),
    connectionId = connectionId,
    payload = payload,
    createdAt = Instant.ofEpochSecond(createdAtEpochSecond),
    retryCount = retryCount,
    lastError = lastError,
    lastAttemptAt = lastAttemptAtEpochSecond?.let { Instant.ofEpochSecond(it) }
)
