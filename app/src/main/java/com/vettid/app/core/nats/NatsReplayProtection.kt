package com.vettid.app.core.nats

import android.util.Log
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NATS message replay protection (#47).
 *
 * Implements client-side protections against message replay attacks:
 * 1. Response deduplication - tracks processed event IDs
 * 2. Timestamp validation - rejects old messages (>5 minutes)
 * 3. Sequence tracking - detects gaps in message sequence
 *
 * Per NATS-MESSAGING-ARCHITECTURE.md security requirements.
 */
@Singleton
class NatsReplayProtection @Inject constructor() {

    companion object {
        private const val TAG = "NatsReplayProtection"

        // Maximum age for valid messages (5 minutes per spec)
        private const val MAX_MESSAGE_AGE_MINUTES = 5L

        // How long to keep processed event IDs in cache (10 minutes)
        private const val EVENT_CACHE_TTL_MINUTES = 10L

        // Maximum cache size to prevent memory issues
        private const val MAX_CACHE_SIZE = 1000
    }

    // Track processed event IDs with their timestamps
    private val processedEvents = ConcurrentHashMap<String, Instant>()

    // Track sequence numbers per session/topic for gap detection
    private val sequenceTrackers = ConcurrentHashMap<String, Long>()

    /**
     * Validate a NATS message for replay attacks.
     *
     * @param eventId Unique event identifier
     * @param timestamp Message timestamp (ISO8601)
     * @param sequence Optional sequence number for gap detection
     * @param sessionKey Key for sequence tracking (e.g., topic or session ID)
     * @return ValidationResult indicating if message should be processed
     */
    fun validateMessage(
        eventId: String,
        timestamp: String?,
        sequence: Long? = null,
        sessionKey: String? = null
    ): ValidationResult {
        // Clean expired entries periodically
        cleanExpiredEntries()

        // 1. Check for duplicate event ID
        if (isDuplicate(eventId)) {
            Log.w(TAG, "SECURITY: Duplicate message detected - eventId: $eventId")
            return ValidationResult.Duplicate(eventId)
        }

        // 2. Validate timestamp if provided
        if (timestamp != null) {
            val timestampResult = validateTimestamp(timestamp)
            if (!timestampResult.isValid) {
                val reason = timestampResult.reason ?: "Unknown validation failure"
                Log.w(TAG, "SECURITY: Invalid timestamp - $timestamp: $reason")
                return ValidationResult.InvalidTimestamp(timestamp, reason)
            }
        }

        // 3. Check sequence number if provided
        if (sequence != null && sessionKey != null) {
            val sequenceResult = validateSequence(sessionKey, sequence)
            if (sequenceResult is SequenceResult.Gap) {
                Log.w(TAG, "SECURITY: Sequence gap detected - expected: ${sequenceResult.expected}, got: $sequence")
                // Log warning but don't reject - gaps can occur legitimately
            }
        }

        // Mark event as processed
        markProcessed(eventId)

        return ValidationResult.Valid
    }

    /**
     * Check if an event ID has already been processed.
     */
    fun isDuplicate(eventId: String): Boolean {
        return processedEvents.containsKey(eventId)
    }

    /**
     * Mark an event as processed.
     */
    fun markProcessed(eventId: String) {
        // Enforce max cache size
        if (processedEvents.size >= MAX_CACHE_SIZE) {
            cleanExpiredEntries()
            // If still too large, remove oldest entries
            if (processedEvents.size >= MAX_CACHE_SIZE) {
                val oldest = processedEvents.entries
                    .sortedBy { it.value }
                    .take(MAX_CACHE_SIZE / 4)
                    .map { it.key }
                oldest.forEach { processedEvents.remove(it) }
            }
        }
        processedEvents[eventId] = Instant.now()
    }

    /**
     * Validate message timestamp.
     *
     * @param timestamp ISO8601 timestamp string
     * @return TimestampValidation result
     */
    fun validateTimestamp(timestamp: String): TimestampValidation {
        return try {
            val messageTime = Instant.parse(timestamp)
            val now = Instant.now()
            val age = Duration.between(messageTime, now)

            when {
                // Future message (with 1 minute tolerance for clock skew)
                age.isNegative && age.abs().toMinutes() > 1 -> {
                    TimestampValidation(
                        isValid = false,
                        reason = "Message timestamp is in the future"
                    )
                }
                // Too old
                age.toMinutes() > MAX_MESSAGE_AGE_MINUTES -> {
                    TimestampValidation(
                        isValid = false,
                        reason = "Message is ${age.toMinutes()} minutes old (max: $MAX_MESSAGE_AGE_MINUTES)"
                    )
                }
                else -> TimestampValidation(isValid = true)
            }
        } catch (e: Exception) {
            TimestampValidation(
                isValid = false,
                reason = "Invalid timestamp format: ${e.message}"
            )
        }
    }

    /**
     * Validate sequence number for gap detection.
     */
    private fun validateSequence(sessionKey: String, sequence: Long): SequenceResult {
        val lastSequence = sequenceTrackers[sessionKey]

        return if (lastSequence == null) {
            // First message in session
            sequenceTrackers[sessionKey] = sequence
            SequenceResult.Valid
        } else if (sequence <= lastSequence) {
            // Replay or out-of-order
            SequenceResult.Replay(expected = lastSequence + 1, received = sequence)
        } else if (sequence > lastSequence + 1) {
            // Gap detected
            sequenceTrackers[sessionKey] = sequence
            SequenceResult.Gap(expected = lastSequence + 1, received = sequence)
        } else {
            // Normal sequential message
            sequenceTrackers[sessionKey] = sequence
            SequenceResult.Valid
        }
    }

    /**
     * Clean expired entries from the cache.
     */
    private fun cleanExpiredEntries() {
        val cutoff = Instant.now().minus(Duration.ofMinutes(EVENT_CACHE_TTL_MINUTES))
        processedEvents.entries.removeIf { it.value.isBefore(cutoff) }
    }

    /**
     * Reset all tracking state (for testing or session reset).
     */
    fun reset() {
        processedEvents.clear()
        sequenceTrackers.clear()
    }

    /**
     * Get cache statistics for monitoring.
     */
    fun getStats(): CacheStats {
        return CacheStats(
            processedEventCount = processedEvents.size,
            trackedSessions = sequenceTrackers.size
        )
    }
}

/**
 * Result of message validation.
 */
sealed class ValidationResult {
    /** Message is valid and should be processed */
    object Valid : ValidationResult()

    /** Message is a duplicate (already processed) */
    data class Duplicate(val eventId: String) : ValidationResult()

    /** Message has invalid timestamp */
    data class InvalidTimestamp(val timestamp: String, val reason: String) : ValidationResult()

    /** Helper to check if valid */
    val isValid: Boolean get() = this is Valid
}

/**
 * Result of timestamp validation.
 */
data class TimestampValidation(
    val isValid: Boolean,
    val reason: String? = null
)

/**
 * Result of sequence validation.
 */
sealed class SequenceResult {
    object Valid : SequenceResult()
    data class Gap(val expected: Long, val received: Long) : SequenceResult()
    data class Replay(val expected: Long, val received: Long) : SequenceResult()
}

/**
 * Cache statistics for monitoring.
 */
data class CacheStats(
    val processedEventCount: Int,
    val trackedSessions: Int
)
