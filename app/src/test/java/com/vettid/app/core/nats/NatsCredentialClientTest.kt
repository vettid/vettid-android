package com.vettid.app.core.nats

import com.google.gson.JsonObject
import com.vettid.app.core.network.NatsConnectionInfo
import com.vettid.app.core.storage.CredentialStore
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class NatsCredentialClientTest {

    private lateinit var ownerSpaceClient: OwnerSpaceClient
    private lateinit var credentialStore: CredentialStore
    private lateinit var credentialClient: NatsCredentialClient

    private val vaultResponsesFlow = MutableSharedFlow<VaultResponse>(extraBufferCapacity = 64)

    @Before
    fun setup() {
        ownerSpaceClient = mock()
        credentialStore = mock()

        whenever(ownerSpaceClient.vaultResponses).thenReturn(vaultResponsesFlow)

        credentialClient = NatsCredentialClient(ownerSpaceClient, credentialStore)
    }

    // MARK: - handleRotationEvent Tests

    @Test
    fun `handleRotationEvent parses valid rotation event`() {
        val payload = JsonObject().apply {
            addProperty("credentials", "-----BEGIN NATS USER JWT-----\ntest-jwt\n-----END NATS USER JWT-----")
            addProperty("expires_at", "2025-12-31T12:00:00Z")
            addProperty("ttl_seconds", 604800)
            addProperty("credential_id", "cred-12345")
            addProperty("reason", "scheduled_rotation")
            addProperty("old_credential_id", "cred-old-67890")
        }

        val result = credentialClient.handleRotationEvent(payload)

        assertNotNull(result)
        assertEquals("scheduled_rotation", result!!.reason)
        assertEquals("cred-12345", result.credentialId)
        assertEquals("cred-old-67890", result.oldCredentialId)
        assertEquals(604800L, result.ttlSeconds)
    }

    @Test
    fun `handleRotationEvent handles missing old_credential_id`() {
        val payload = JsonObject().apply {
            addProperty("credentials", "test-creds")
            addProperty("expires_at", "2025-12-31T12:00:00Z")
            addProperty("ttl_seconds", 86400)
            addProperty("credential_id", "cred-abc")
            addProperty("reason", "expiry_imminent")
        }

        val result = credentialClient.handleRotationEvent(payload)

        assertNotNull(result)
        assertNull(result!!.oldCredentialId)
        assertEquals("expiry_imminent", result.reason)
    }

    @Test
    fun `handleRotationEvent returns null for invalid payload`() {
        val payload = JsonObject().apply {
            addProperty("invalid", "data")
        }

        val result = credentialClient.handleRotationEvent(payload)

        assertNull(result)
    }

    @Test
    fun `handleRotationEvent uses default values for optional fields`() {
        val payload = JsonObject().apply {
            addProperty("credentials", "test-creds")
        }

        val result = credentialClient.handleRotationEvent(payload)

        assertNotNull(result)
        assertEquals("", result!!.expiresAt)
        assertEquals(604800L, result.ttlSeconds) // Default 7 days
        assertEquals("", result.credentialId)
        assertEquals("unknown", result.reason)
    }

    // MARK: - needsRefresh Tests

    @Test
    fun `needsRefresh returns true when no expiry time stored`() {
        whenever(credentialStore.getNatsCredentialsExpiryTime()).thenReturn(null)

        assertTrue(credentialClient.needsRefresh())
    }

    @Test
    fun `needsRefresh returns true when expiry within buffer`() {
        // Expiry in 1 hour, buffer is 2 hours (default)
        val expiryIn1Hour = System.currentTimeMillis() + (60 * 60 * 1000)
        whenever(credentialStore.getNatsCredentialsExpiryTime()).thenReturn(expiryIn1Hour)

        assertTrue(credentialClient.needsRefresh(bufferMinutes = 120))
    }

    @Test
    fun `needsRefresh returns false when expiry outside buffer`() {
        // Expiry in 3 hours, buffer is 2 hours
        val expiryIn3Hours = System.currentTimeMillis() + (3 * 60 * 60 * 1000)
        whenever(credentialStore.getNatsCredentialsExpiryTime()).thenReturn(expiryIn3Hours)

        assertFalse(credentialClient.needsRefresh(bufferMinutes = 120))
    }

    @Test
    fun `needsRefresh respects custom buffer`() {
        // Expiry in 30 minutes
        val expiryIn30Min = System.currentTimeMillis() + (30 * 60 * 1000)
        whenever(credentialStore.getNatsCredentialsExpiryTime()).thenReturn(expiryIn30Min)

        // With 60 minute buffer - needs refresh
        assertTrue(credentialClient.needsRefresh(bufferMinutes = 60))

        // With 15 minute buffer - doesn't need refresh
        assertFalse(credentialClient.needsRefresh(bufferMinutes = 15))
    }

    // MARK: - storeCredentials Tests

    @Test
    fun `storeCredentials updates credential store`() {
        val testCredentials = "-----BEGIN NATS USER JWT-----\ntest\n-----END NATS USER JWT-----"
        val testEndpoint = "tls://nats.vettid.dev:443"
        val testOwnerSpace = "OwnerSpace.test"
        val testMessageSpace = "MessageSpace.test"

        whenever(credentialStore.getNatsEndpoint()).thenReturn(testEndpoint)
        whenever(credentialStore.getNatsOwnerSpace()).thenReturn(testOwnerSpace)
        whenever(credentialStore.getNatsConnection()).thenReturn(
            NatsConnectionInfo(
                endpoint = testEndpoint,
                credentials = "old-creds",
                ownerSpace = testOwnerSpace,
                messageSpace = testMessageSpace
            )
        )

        credentialClient.storeCredentials(testCredentials, "cred-123")

        verify(credentialStore).storeNatsConnection(argThat<NatsConnectionInfo> { connectionInfo ->
            connectionInfo.credentials == testCredentials &&
            connectionInfo.endpoint == testEndpoint &&
            connectionInfo.ownerSpace == testOwnerSpace &&
            connectionInfo.messageSpace == testMessageSpace
        })
    }

    @Test
    fun `storeCredentials does nothing when endpoint is null`() {
        whenever(credentialStore.getNatsEndpoint()).thenReturn(null)

        credentialClient.storeCredentials("test-creds")

        verify(credentialStore, never()).storeNatsConnection(any<NatsConnectionInfo>())
    }

    // MARK: - CredentialRefreshResult Tests

    @Test
    fun `CredentialRefreshResult parses valid ISO date`() {
        val result = CredentialRefreshResult(
            credentials = "test",
            expiresAt = "2025-12-31T12:00:00Z",
            ttlSeconds = 604800,
            credentialId = "cred-123"
        )

        val instant = result.expiresAtInstant()
        assertNotNull(instant)
    }

    @Test
    fun `CredentialRefreshResult returns null for invalid date`() {
        val result = CredentialRefreshResult(
            credentials = "test",
            expiresAt = "invalid-date",
            ttlSeconds = 604800,
            credentialId = "cred-123"
        )

        val instant = result.expiresAtInstant()
        assertNull(instant)
    }

    // MARK: - CredentialRefreshResult Data Class Tests

    @Test
    fun `CredentialRefreshResult stores all fields correctly`() {
        val result = CredentialRefreshResult(
            credentials = "test-credentials",
            expiresAt = "2026-01-10T12:00:00Z",
            ttlSeconds = 604800,
            credentialId = "cred-abc123"
        )

        assertEquals("test-credentials", result.credentials)
        assertEquals("2026-01-10T12:00:00Z", result.expiresAt)
        assertEquals(604800L, result.ttlSeconds)
        assertEquals("cred-abc123", result.credentialId)
    }

    @Test
    fun `CredentialRefreshResult with short bootstrap TTL`() {
        // Bootstrap credentials have 5-minute TTL
        val result = CredentialRefreshResult(
            credentials = "short-lived-creds",
            expiresAt = "2026-01-03T12:05:00Z",
            ttlSeconds = 300, // 5 minutes
            credentialId = "bootstrap-cred"
        )

        assertEquals(300L, result.ttlSeconds)
        assertNotNull(result.expiresAtInstant())
    }

    @Test
    fun `CredentialRefreshResult with long-lived credentials TTL`() {
        // After refresh, credentials have 7-day TTL
        val result = CredentialRefreshResult(
            credentials = "long-lived-creds",
            expiresAt = "2026-01-10T12:00:00Z",
            ttlSeconds = 604800, // 7 days
            credentialId = "refreshed-cred"
        )

        assertEquals(604800L, result.ttlSeconds)
    }

    // MARK: - CredentialRotationEvent Tests

    @Test
    fun `CredentialRotationEvent stores all fields correctly`() {
        val event = CredentialRotationEvent(
            credentials = "new-creds",
            expiresAt = "2026-01-10T12:00:00Z",
            ttlSeconds = 604800,
            credentialId = "new-cred-123",
            reason = "scheduled_rotation",
            oldCredentialId = "old-cred-456"
        )

        assertEquals("new-creds", event.credentials)
        assertEquals("scheduled_rotation", event.reason)
        assertEquals("new-cred-123", event.credentialId)
        assertEquals("old-cred-456", event.oldCredentialId)
    }

    @Test
    fun `CredentialRotationEvent with expiry_imminent reason`() {
        val event = CredentialRotationEvent(
            credentials = "urgent-creds",
            expiresAt = "2026-01-03T12:30:00Z",
            ttlSeconds = 1800, // 30 minutes
            credentialId = "urgent-cred",
            reason = "expiry_imminent",
            oldCredentialId = null
        )

        assertEquals("expiry_imminent", event.reason)
        assertNull(event.oldCredentialId)
    }

    // MARK: - CredentialStatus Tests

    @Test
    fun `CredentialStatus stores all fields correctly`() {
        val status = CredentialStatus(
            valid = true,
            expiresAt = "2026-01-10T12:00:00Z",
            remainingSeconds = 172800
        )

        assertTrue(status.valid)
        assertEquals("2026-01-10T12:00:00Z", status.expiresAt)
        assertEquals(172800L, status.remainingSeconds)
    }

    @Test
    fun `CredentialStatus with expired credentials`() {
        val status = CredentialStatus(
            valid = false,
            expiresAt = "2026-01-01T12:00:00Z",
            remainingSeconds = 0
        )

        assertFalse(status.valid)
        assertEquals(0L, status.remainingSeconds)
    }
}
