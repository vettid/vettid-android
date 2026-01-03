package com.vettid.app.core.storage

import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

/**
 * Unit tests for the credential recovery flow.
 *
 * Tests cover:
 * - Recovery data classes (SealedCredential, RecoveryRequest, RecoveryStatus)
 * - RecoveryState enum behavior
 * - RecoveryException properties
 * - Time calculation helpers
 */
class RecoveryFlowTest {

    // MARK: - SealedCredential Tests

    @Test
    fun `SealedCredential stores user guid and data`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val credential = SealedCredential(
            userGuid = "user-123",
            sealedData = data
        )

        assertEquals("user-123", credential.userGuid)
        assertTrue(data.contentEquals(credential.sealedData))
    }

    @Test
    fun `SealedCredential has default version of 1`() {
        val credential = SealedCredential(
            userGuid = "user-123",
            sealedData = byteArrayOf()
        )

        assertEquals(1, credential.version)
    }

    @Test
    fun `SealedCredential stores custom version`() {
        val credential = SealedCredential(
            userGuid = "user-123",
            sealedData = byteArrayOf(),
            version = 5
        )

        assertEquals(5, credential.version)
    }

    @Test
    fun `SealedCredential has createdAt set to now by default`() {
        val before = Date()
        val credential = SealedCredential(
            userGuid = "user-123",
            sealedData = byteArrayOf()
        )
        val after = Date()

        assertTrue(credential.createdAt >= before)
        assertTrue(credential.createdAt <= after)
    }

    @Test
    fun `SealedCredential equals compares userGuid and sealedData`() {
        val data = byteArrayOf(1, 2, 3)
        val credential1 = SealedCredential("user-123", data)
        val credential2 = SealedCredential("user-123", data.copyOf())

        assertEquals(credential1, credential2)
    }

    @Test
    fun `SealedCredential not equal with different userGuid`() {
        val data = byteArrayOf(1, 2, 3)
        val credential1 = SealedCredential("user-123", data)
        val credential2 = SealedCredential("user-456", data)

        assertNotEquals(credential1, credential2)
    }

    @Test
    fun `SealedCredential not equal with different sealedData`() {
        val credential1 = SealedCredential("user-123", byteArrayOf(1, 2, 3))
        val credential2 = SealedCredential("user-123", byteArrayOf(4, 5, 6))

        assertNotEquals(credential1, credential2)
    }

    @Test
    fun `SealedCredential hashCode is consistent`() {
        val data = byteArrayOf(1, 2, 3)
        val credential1 = SealedCredential("user-123", data)
        val credential2 = SealedCredential("user-123", data.copyOf())

        assertEquals(credential1.hashCode(), credential2.hashCode())
    }

    // MARK: - RecoveryRequest Tests

    @Test
    fun `RecoveryRequest stores all properties`() {
        val availableAt = Instant.now().plus(24, ChronoUnit.HOURS)
        val request = RecoveryRequest(
            requestId = "req-123",
            availableAt = availableAt,
            email = "user@example.com"
        )

        assertEquals("req-123", request.requestId)
        assertEquals(availableAt, request.availableAt)
        assertEquals("user@example.com", request.email)
    }

    // MARK: - RecoveryState Tests

    @Test
    fun `RecoveryState has all expected values`() {
        val states = RecoveryState.values()

        assertEquals(4, states.size)
        assertTrue(states.contains(RecoveryState.PENDING))
        assertTrue(states.contains(RecoveryState.READY))
        assertTrue(states.contains(RecoveryState.CANCELLED))
        assertTrue(states.contains(RecoveryState.EXPIRED))
    }

    @Test
    fun `RecoveryState valueOf parses correctly`() {
        assertEquals(RecoveryState.PENDING, RecoveryState.valueOf("PENDING"))
        assertEquals(RecoveryState.READY, RecoveryState.valueOf("READY"))
        assertEquals(RecoveryState.CANCELLED, RecoveryState.valueOf("CANCELLED"))
        assertEquals(RecoveryState.EXPIRED, RecoveryState.valueOf("EXPIRED"))
    }

    // MARK: - RecoveryStatus Tests

    @Test
    fun `RecoveryStatus stores all properties`() {
        val availableAt = Instant.now().plus(24, ChronoUnit.HOURS)
        val expiresAt = Instant.now().plus(48, ChronoUnit.HOURS)

        val status = RecoveryStatus(
            requestId = "req-123",
            state = RecoveryState.PENDING,
            availableAt = availableAt,
            expiresAt = expiresAt
        )

        assertEquals("req-123", status.requestId)
        assertEquals(RecoveryState.PENDING, status.state)
        assertEquals(availableAt, status.availableAt)
        assertEquals(expiresAt, status.expiresAt)
    }

    @Test
    fun `RecoveryStatus isReady returns true for READY state`() {
        val status = RecoveryStatus(
            requestId = "req-123",
            state = RecoveryState.READY,
            availableAt = Instant.now(),
            expiresAt = Instant.now().plus(24, ChronoUnit.HOURS)
        )

        assertTrue(status.isReady)
        assertFalse(status.isPending)
    }

    @Test
    fun `RecoveryStatus isPending returns true for PENDING state`() {
        val status = RecoveryStatus(
            requestId = "req-123",
            state = RecoveryState.PENDING,
            availableAt = Instant.now().plus(24, ChronoUnit.HOURS),
            expiresAt = Instant.now().plus(48, ChronoUnit.HOURS)
        )

        assertTrue(status.isPending)
        assertFalse(status.isReady)
    }

    @Test
    fun `RecoveryStatus isReady returns false for CANCELLED state`() {
        val status = RecoveryStatus(
            requestId = "req-123",
            state = RecoveryState.CANCELLED,
            availableAt = Instant.now(),
            expiresAt = Instant.now()
        )

        assertFalse(status.isReady)
        assertFalse(status.isPending)
    }

    @Test
    fun `RecoveryStatus isReady returns false for EXPIRED state`() {
        val status = RecoveryStatus(
            requestId = "req-123",
            state = RecoveryState.EXPIRED,
            availableAt = Instant.now().minus(48, ChronoUnit.HOURS),
            expiresAt = Instant.now().minus(24, ChronoUnit.HOURS)
        )

        assertFalse(status.isReady)
        assertFalse(status.isPending)
    }

    // MARK: - RecoveryException Tests

    @Test
    fun `RecoveryException stores message`() {
        val exception = RecoveryException("Recovery failed")

        assertEquals("Recovery failed", exception.message)
    }

    @Test
    fun `RecoveryException stores status code`() {
        val exception = RecoveryException("Unauthorized", statusCode = 401)

        assertEquals("Unauthorized", exception.message)
        assertEquals(401, exception.statusCode)
    }

    @Test
    fun `RecoveryException stores cause`() {
        val cause = RuntimeException("Network error")
        val exception = RecoveryException("Recovery failed", cause = cause)

        assertEquals(cause, exception.cause)
    }

    @Test
    fun `RecoveryException with all parameters`() {
        val cause = RuntimeException("Network error")
        val exception = RecoveryException(
            message = "Recovery failed",
            statusCode = 500,
            cause = cause
        )

        assertEquals("Recovery failed", exception.message)
        assertEquals(500, exception.statusCode)
        assertEquals(cause, exception.cause)
    }

    @Test
    fun `RecoveryException statusCode is null by default`() {
        val exception = RecoveryException("Error")

        assertNull(exception.statusCode)
    }

    // MARK: - Time Calculation Tests

    @Test
    fun `getTimeUntilRecoveryAvailable returns positive duration when in future`() {
        val availableAt = Instant.now().plus(2, ChronoUnit.HOURS)

        val remaining = getTimeUntilRecoveryAvailable(availableAt)

        assertTrue(remaining > Duration.ZERO)
        assertTrue(remaining <= Duration.ofHours(2))
    }

    @Test
    fun `getTimeUntilRecoveryAvailable returns zero when in past`() {
        val availableAt = Instant.now().minus(1, ChronoUnit.HOURS)

        val remaining = getTimeUntilRecoveryAvailable(availableAt)

        assertEquals(Duration.ZERO, remaining)
    }

    @Test
    fun `getTimeUntilRecoveryAvailable returns zero when now`() {
        val availableAt = Instant.now()

        val remaining = getTimeUntilRecoveryAvailable(availableAt)

        // Could be zero or very small positive
        assertTrue(remaining <= Duration.ofMillis(100))
    }

    @Test
    fun `getTimeUntilRecoveryAvailable calculates 24 hours correctly`() {
        val availableAt = Instant.now().plus(24, ChronoUnit.HOURS)

        val remaining = getTimeUntilRecoveryAvailable(availableAt)

        // Should be approximately 24 hours (within a second)
        assertTrue(remaining >= Duration.ofHours(23).plusMinutes(59))
        assertTrue(remaining <= Duration.ofHours(24).plusSeconds(1))
    }

    // Helper function that mirrors ProteanCredentialManager.getTimeUntilRecoveryAvailable
    private fun getTimeUntilRecoveryAvailable(availableAt: Instant): Duration {
        val now = Instant.now()
        return if (now.isBefore(availableAt)) {
            Duration.between(now, availableAt)
        } else {
            Duration.ZERO
        }
    }

    // MARK: - Recovery Delay Constant Tests

    @Test
    fun `RECOVERY_DELAY is 24 hours`() {
        assertEquals(Duration.ofHours(24), ProteanCredentialManager.RECOVERY_DELAY)
    }
}
