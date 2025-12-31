package com.vettid.app.core.nats

import com.google.gson.JsonObject
import com.vettid.app.core.network.NatsConnectionInfo
import com.vettid.app.core.storage.CredentialStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
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
            addProperty("credentials", "-----BEGIN NATS USER JWT-----\ntest-jwt\n------END NATS USER JWT------")
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
        val testCredentials = "-----BEGIN NATS USER JWT-----\ntest\n------END NATS USER JWT------"
        val testEndpoint = "tls://nats.vettid.dev:4222"
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
}
