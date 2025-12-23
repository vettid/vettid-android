package com.vettid.app.core.nats

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsClientTest {

    private lateinit var ownerSpaceClient: OwnerSpaceClient
    private lateinit var connectionsClient: ConnectionsClient
    private lateinit var vaultResponses: MutableSharedFlow<VaultResponse>

    @Before
    fun setup() {
        ownerSpaceClient = mock()
        vaultResponses = MutableSharedFlow()
        whenever(ownerSpaceClient.vaultResponses).thenReturn(vaultResponses)
        connectionsClient = ConnectionsClient(ownerSpaceClient)
    }

    // MARK: - createInvite tests

    @Test
    fun `createInvite sends correct message type and payload`() = runTest {
        // Arrange
        val requestId = "req-123"
        whenever(ownerSpaceClient.sendToVault(any(), any()))
            .thenReturn(Result.success(requestId))

        // Emit response in background
        backgroundScope.launch {
            vaultResponses.emit(VaultResponse.HandlerResult(
                requestId = requestId,
                handlerId = null,
                success = true,
                result = JsonObject().apply {
                    addProperty("connection_id", "conn-abc")
                    addProperty("nats_credentials", "-----BEGIN NATS USER JWT-----")
                    addProperty("owner_space_id", "OwnerSpace.123")
                    addProperty("message_space_id", "MessageSpace.123")
                    addProperty("expires_at", "2025-12-23T15:30:00Z")
                },
                error = null
            ))
        }

        // Act
        val result = connectionsClient.createInvite(
            peerGuid = "user-456",
            label = "Alice's Vault",
            expiresInHours = 48
        )

        // Assert
        assertTrue(result.isSuccess)
        verify(ownerSpaceClient).sendToVault(
            eq("connection.create-invite"),
            argThat { payload ->
                payload.get("peer_guid")?.asString == "user-456" &&
                payload.get("label")?.asString == "Alice's Vault" &&
                payload.get("expires_in_hours")?.asInt == 48
            }
        )

        val invitation = result.getOrNull()!!
        assertEquals("conn-abc", invitation.connectionId)
        assertEquals("user-456", invitation.peerGuid)
        assertEquals("Alice's Vault", invitation.label)
        assertEquals("-----BEGIN NATS USER JWT-----", invitation.natsCredentials)
        assertEquals("OwnerSpace.123", invitation.ownerSpaceId)
        assertEquals("MessageSpace.123", invitation.messageSpaceId)
        assertEquals("2025-12-23T15:30:00Z", invitation.expiresAt)
    }

    @Test
    fun `createInvite returns failure when send fails`() = runTest {
        // Arrange
        whenever(ownerSpaceClient.sendToVault(any(), any()))
            .thenReturn(Result.failure(NatsException("Connection lost")))

        // Act
        val result = connectionsClient.createInvite("user-456", "Test")

        // Assert
        assertTrue(result.isFailure)
        assertEquals("Connection lost", result.exceptionOrNull()?.message)
    }

    @Test
    fun `createInvite returns failure when handler fails`() = runTest {
        // Arrange
        val requestId = "req-123"
        whenever(ownerSpaceClient.sendToVault(any(), any()))
            .thenReturn(Result.success(requestId))

        backgroundScope.launch {
            vaultResponses.emit(VaultResponse.HandlerResult(
                requestId = requestId,
                handlerId = null,
                success = false,
                result = null,
                error = "Peer not found"
            ))
        }

        // Act
        val result = connectionsClient.createInvite("user-456", "Test")

        // Assert
        assertTrue(result.isFailure)
        assertEquals("Peer not found", result.exceptionOrNull()?.message)
    }

    // MARK: - storeCredentials tests

    @Test
    fun `storeCredentials sends correct payload and parses response`() = runTest {
        // Arrange
        val requestId = "req-456"
        whenever(ownerSpaceClient.sendToVault(any(), any()))
            .thenReturn(Result.success(requestId))

        backgroundScope.launch {
            vaultResponses.emit(VaultResponse.HandlerResult(
                requestId = requestId,
                handlerId = null,
                success = true,
                result = JsonObject().apply {
                    addProperty("connection_id", "conn-xyz")
                    addProperty("peer_guid", "user-789")
                    addProperty("label", "Bob's Vault")
                    addProperty("status", "active")
                    addProperty("direction", "inbound")
                    addProperty("created_at", "2025-12-22T10:00:00Z")
                },
                error = null
            ))
        }

        // Act
        val result = connectionsClient.storeCredentials(
            connectionId = "conn-xyz",
            peerGuid = "user-789",
            label = "Bob's Vault",
            natsCredentials = "-----BEGIN NATS-----",
            peerOwnerSpaceId = "OwnerSpace.789",
            peerMessageSpaceId = "MessageSpace.789"
        )

        // Assert
        assertTrue(result.isSuccess)
        verify(ownerSpaceClient).sendToVault(
            eq("connection.store-credentials"),
            argThat { payload ->
                payload.get("connection_id")?.asString == "conn-xyz" &&
                payload.get("peer_guid")?.asString == "user-789" &&
                payload.get("nats_credentials")?.asString == "-----BEGIN NATS-----"
            }
        )

        val record = result.getOrNull()!!
        assertEquals("conn-xyz", record.connectionId)
        assertEquals("user-789", record.peerGuid)
        assertEquals("active", record.status)
        assertEquals("inbound", record.direction)
    }

    // MARK: - rotate tests

    @Test
    fun `rotate sends connection_id and returns updated record`() = runTest {
        // Arrange
        val requestId = "req-rotate"
        whenever(ownerSpaceClient.sendToVault(any(), any()))
            .thenReturn(Result.success(requestId))

        backgroundScope.launch {
            vaultResponses.emit(VaultResponse.HandlerResult(
                requestId = requestId,
                handlerId = null,
                success = true,
                result = JsonObject().apply {
                    addProperty("connection_id", "conn-abc")
                    addProperty("peer_guid", "user-123")
                    addProperty("label", "Test Connection")
                    addProperty("status", "active")
                    addProperty("direction", "outbound")
                    addProperty("created_at", "2025-12-20T10:00:00Z")
                    addProperty("last_rotated_at", "2025-12-22T16:00:00Z")
                },
                error = null
            ))
        }

        // Act
        val result = connectionsClient.rotate("conn-abc")

        // Assert
        assertTrue(result.isSuccess)
        verify(ownerSpaceClient).sendToVault(
            eq("connection.rotate"),
            argThat { payload -> payload.get("connection_id")?.asString == "conn-abc" }
        )

        val record = result.getOrNull()!!
        assertEquals("2025-12-22T16:00:00Z", record.lastRotatedAt)
    }

    // MARK: - revoke tests

    @Test
    fun `revoke sends connection_id and returns success`() = runTest {
        // Arrange
        val requestId = "req-revoke"
        whenever(ownerSpaceClient.sendToVault(any(), any()))
            .thenReturn(Result.success(requestId))

        backgroundScope.launch {
            vaultResponses.emit(VaultResponse.HandlerResult(
                requestId = requestId,
                handlerId = null,
                success = true,
                result = JsonObject().apply {
                    addProperty("success", true)
                },
                error = null
            ))
        }

        // Act
        val result = connectionsClient.revoke("conn-abc")

        // Assert
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!)
        verify(ownerSpaceClient).sendToVault(
            eq("connection.revoke"),
            argThat { payload -> payload.get("connection_id")?.asString == "conn-abc" }
        )
    }

    // MARK: - list tests

    @Test
    fun `list sends filters and parses paginated response`() = runTest {
        // Arrange
        val requestId = "req-list"
        whenever(ownerSpaceClient.sendToVault(any(), any()))
            .thenReturn(Result.success(requestId))

        backgroundScope.launch {
            val items = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("connection_id", "conn-1")
                    addProperty("peer_guid", "user-1")
                    addProperty("label", "Connection 1")
                    addProperty("status", "active")
                    addProperty("direction", "outbound")
                    addProperty("created_at", "2025-12-20T10:00:00Z")
                })
                add(JsonObject().apply {
                    addProperty("connection_id", "conn-2")
                    addProperty("peer_guid", "user-2")
                    addProperty("label", "Connection 2")
                    addProperty("status", "active")
                    addProperty("direction", "inbound")
                    addProperty("created_at", "2025-12-21T10:00:00Z")
                })
            }
            vaultResponses.emit(VaultResponse.HandlerResult(
                requestId = requestId,
                handlerId = null,
                success = true,
                result = JsonObject().apply {
                    add("items", items)
                    addProperty("next_cursor", "cursor-abc")
                },
                error = null
            ))
        }

        // Act
        val result = connectionsClient.list(status = "active", limit = 10)

        // Assert
        assertTrue(result.isSuccess)
        verify(ownerSpaceClient).sendToVault(
            eq("connection.list"),
            argThat { payload ->
                payload.get("status")?.asString == "active" &&
                payload.get("limit")?.asInt == 10
            }
        )

        val listResult = result.getOrNull()!!
        assertEquals(2, listResult.items.size)
        assertEquals("conn-1", listResult.items[0].connectionId)
        assertEquals("conn-2", listResult.items[1].connectionId)
        assertEquals("cursor-abc", listResult.nextCursor)
    }

    @Test
    fun `list with no filters sends only limit`() = runTest {
        // Arrange
        val requestId = "req-list-all"
        whenever(ownerSpaceClient.sendToVault(any(), any()))
            .thenReturn(Result.success(requestId))

        backgroundScope.launch {
            vaultResponses.emit(VaultResponse.HandlerResult(
                requestId = requestId,
                handlerId = null,
                success = true,
                result = JsonObject().apply {
                    add("items", JsonArray())
                },
                error = null
            ))
        }

        // Act
        val result = connectionsClient.list()

        // Assert
        assertTrue(result.isSuccess)
        verify(ownerSpaceClient).sendToVault(
            eq("connection.list"),
            argThat { payload ->
                !payload.has("status") &&
                payload.get("limit")?.asInt == 50
            }
        )
    }

    // MARK: - getCredentials tests

    @Test
    fun `getCredentials returns connection credentials`() = runTest {
        // Arrange
        val requestId = "req-creds"
        whenever(ownerSpaceClient.sendToVault(any(), any()))
            .thenReturn(Result.success(requestId))

        backgroundScope.launch {
            vaultResponses.emit(VaultResponse.HandlerResult(
                requestId = requestId,
                handlerId = null,
                success = true,
                result = JsonObject().apply {
                    addProperty("nats_credentials", "-----BEGIN NATS USER JWT-----\ntest")
                    addProperty("peer_message_space_id", "MessageSpace.peer123")
                    addProperty("expires_at", "2025-12-25T00:00:00Z")
                },
                error = null
            ))
        }

        // Act
        val result = connectionsClient.getCredentials("conn-abc")

        // Assert
        assertTrue(result.isSuccess)
        verify(ownerSpaceClient).sendToVault(
            eq("connection.get-credentials"),
            argThat { payload -> payload.get("connection_id")?.asString == "conn-abc" }
        )

        val creds = result.getOrNull()!!
        assertEquals("conn-abc", creds.connectionId)
        assertEquals("-----BEGIN NATS USER JWT-----\ntest", creds.natsCredentials)
        assertEquals("MessageSpace.peer123", creds.peerMessageSpaceId)
        assertEquals("2025-12-25T00:00:00Z", creds.expiresAt)
    }

    // MARK: - Error handling tests

    @Test
    fun `returns failure when vault returns error response`() = runTest {
        // Arrange
        val requestId = "req-error"
        whenever(ownerSpaceClient.sendToVault(any(), any()))
            .thenReturn(Result.success(requestId))

        backgroundScope.launch {
            vaultResponses.emit(VaultResponse.Error(
                requestId = requestId,
                code = "NOT_FOUND",
                message = "Connection not found"
            ))
        }

        // Act
        val result = connectionsClient.getCredentials("conn-nonexistent")

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("NOT_FOUND") == true)
    }
}
