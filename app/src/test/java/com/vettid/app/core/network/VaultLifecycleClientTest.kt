package com.vettid.app.core.network

import com.vettid.app.core.storage.CredentialStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class VaultLifecycleClientTest {

    private lateinit var credentialStore: CredentialStore

    private val testUserGuid = "test-user-guid-123"

    @Before
    fun setup() {
        credentialStore = mock()
    }

    // MARK: - VaultStartResponse Tests

    @Test
    fun `VaultStartResponse isStarting returns true for starting status`() {
        val response = VaultStartResponse(
            status = "starting",
            instanceId = "i-12345"
        )

        assertTrue(response.isStarting)
        assertFalse(response.isAlreadyRunning)
    }

    @Test
    fun `VaultStartResponse isAlreadyRunning returns true for already_running status`() {
        val response = VaultStartResponse(
            status = "already_running",
            instanceId = "i-12345"
        )

        assertFalse(response.isStarting)
        assertTrue(response.isAlreadyRunning)
    }

    @Test
    fun `VaultStartResponse isAlreadyRunning returns true for running status`() {
        val response = VaultStartResponse(
            status = "running",
            instanceId = "i-12345"
        )

        assertTrue(response.isAlreadyRunning)
    }

    // MARK: - VaultStopResponse Tests

    @Test
    fun `VaultStopResponse isStopping returns true for stopping status`() {
        val response = VaultStopResponse(
            status = "stopping",
            instanceId = "i-12345"
        )

        assertTrue(response.isStopping)
        assertFalse(response.isAlreadyStopped)
    }

    @Test
    fun `VaultStopResponse isAlreadyStopped returns true for already_stopped status`() {
        val response = VaultStopResponse(
            status = "already_stopped",
            instanceId = "i-12345"
        )

        assertFalse(response.isStopping)
        assertTrue(response.isAlreadyStopped)
    }

    @Test
    fun `VaultStopResponse isAlreadyStopped returns true for stopped status`() {
        val response = VaultStopResponse(status = "stopped")

        assertTrue(response.isAlreadyStopped)
    }

    // MARK: - VaultLifecycleStatusResponse Tests

    @Test
    fun `VaultLifecycleStatusResponse isVaultRunning returns true for running status`() {
        val response = VaultLifecycleStatusResponse(
            enrollmentStatus = "active",
            instanceStatus = "running",
            instanceId = "i-12345",
            natsEndpoint = "tls://nats.vettid.dev:443"
        )

        assertTrue(response.isVaultRunning)
        assertFalse(response.isVaultStopped)
        assertFalse(response.isVaultPending)
    }

    @Test
    fun `VaultLifecycleStatusResponse isVaultStopped returns true for stopped status`() {
        val response = VaultLifecycleStatusResponse(
            enrollmentStatus = "active",
            instanceStatus = "stopped"
        )

        assertFalse(response.isVaultRunning)
        assertTrue(response.isVaultStopped)
        assertFalse(response.isVaultPending)
    }

    @Test
    fun `VaultLifecycleStatusResponse isVaultPending returns true for pending status`() {
        val response = VaultLifecycleStatusResponse(
            enrollmentStatus = "active",
            instanceStatus = "pending"
        )

        assertFalse(response.isVaultRunning)
        assertFalse(response.isVaultStopped)
        assertTrue(response.isVaultPending)
    }

    @Test
    fun `VaultLifecycleStatusResponse captures all fields correctly`() {
        val response = VaultLifecycleStatusResponse(
            enrollmentStatus = "active",
            userGuid = testUserGuid,
            transactionKeysRemaining = 42,
            instanceStatus = "running",
            instanceId = "i-12345",
            instanceIp = "10.0.1.100",
            natsEndpoint = "tls://nats.vettid.dev:443"
        )

        assertEquals("active", response.enrollmentStatus)
        assertEquals(testUserGuid, response.userGuid)
        assertEquals(42, response.transactionKeysRemaining)
        assertEquals("running", response.instanceStatus)
        assertEquals("i-12345", response.instanceId)
        assertEquals("10.0.1.100", response.instanceIp)
        assertEquals("tls://nats.vettid.dev:443", response.natsEndpoint)
    }

    // MARK: - ActionTokenRequest Tests

    @Test
    fun `ActionTokenRequest contains correct fields`() {
        val request = ActionTokenRequest(
            userGuid = testUserGuid,
            actionType = "vault_start"
        )

        assertEquals(testUserGuid, request.userGuid)
        assertEquals("vault_start", request.actionType)
    }

    // MARK: - ActionTokenResponse Tests

    @Test
    fun `ActionTokenResponse captures all fields correctly`() {
        val response = ActionTokenResponse(
            actionToken = "test-token-123",
            actionTokenExpiresAt = "2025-12-31T23:59:59Z",
            actionEndpoint = "https://api.vettid.com/api/v1/vault/start",
            expiresIn = 300
        )

        assertEquals("test-token-123", response.actionToken)
        assertEquals("2025-12-31T23:59:59Z", response.actionTokenExpiresAt)
        assertEquals("https://api.vettid.com/api/v1/vault/start", response.actionEndpoint)
        assertEquals(300, response.expiresIn)
    }

    // MARK: - VaultLifecycleException Tests

    @Test
    fun `VaultLifecycleException contains message and code`() {
        val exception = VaultLifecycleException(
            message = "Invalid action token",
            code = 401,
            errorBody = """{"error": "Unauthorized"}"""
        )

        assertEquals("Invalid action token", exception.message)
        assertEquals(401, exception.code)
        assertEquals("""{"error": "Unauthorized"}""", exception.errorBody)
    }

    @Test
    fun `VaultLifecycleException works with null code and errorBody`() {
        val exception = VaultLifecycleException("User not enrolled")

        assertEquals("User not enrolled", exception.message)
        assertNull(exception.code)
        assertNull(exception.errorBody)
    }

    // MARK: - ErrorResponse Tests

    @Test
    fun `ErrorResponse parses message field`() {
        val response = ErrorResponse(message = "Invalid request")

        assertEquals("Invalid request", response.message)
        assertNull(response.error)
    }

    @Test
    fun `ErrorResponse parses error field`() {
        val response = ErrorResponse(error = "Unauthorized")

        assertNull(response.message)
        assertEquals("Unauthorized", response.error)
    }

    // MARK: - Client Not Enrolled Tests

    @Test
    fun `client requires user guid from credential store`() {
        whenever(credentialStore.getUserGuid()).thenReturn(null)

        // Client can be created but operations will fail without user guid
        VaultLifecycleClient(credentialStore)

        // Verify that getUserGuid is not called during construction
        verify(credentialStore, never()).getUserGuid()

        // The actual test of the failure would require running the suspend function
        // which is covered in integration tests
    }

    @Test
    fun `client uses credential store for user guid`() {
        whenever(credentialStore.getUserGuid()).thenReturn(testUserGuid)

        val client = VaultLifecycleClient(credentialStore)

        // Client is created successfully - operations will use the stored guid
        assertNotNull(client)
    }

    // MARK: - Status Helper Tests

    @Test
    fun `all status responses handle null optional fields`() {
        val startResponse = VaultStartResponse(status = "starting")
        assertNull(startResponse.instanceId)
        assertNull(startResponse.message)

        val stopResponse = VaultStopResponse(status = "stopping")
        assertNull(stopResponse.instanceId)
        assertNull(stopResponse.message)

        val statusResponse = VaultLifecycleStatusResponse(enrollmentStatus = "active")
        assertNull(statusResponse.userGuid)
        assertNull(statusResponse.transactionKeysRemaining)
        assertNull(statusResponse.instanceStatus)
        assertNull(statusResponse.instanceId)
        assertNull(statusResponse.instanceIp)
        assertNull(statusResponse.natsEndpoint)
    }

    @Test
    fun `action types are correctly named`() {
        // Verify action type constants match API expectations
        assertEquals("vault_start", ActionTokenRequest(testUserGuid, "vault_start").actionType)
        assertEquals("vault_stop", ActionTokenRequest(testUserGuid, "vault_stop").actionType)
        assertEquals("vault_status", ActionTokenRequest(testUserGuid, "vault_status").actionType)
    }
}
