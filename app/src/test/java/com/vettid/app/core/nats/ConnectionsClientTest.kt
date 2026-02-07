package com.vettid.app.core.nats

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsClientTest {

    private lateinit var ownerSpaceClient: OwnerSpaceClient
    private lateinit var connectionsClient: ConnectionsClient

    @Before
    fun setup() {
        ownerSpaceClient = mock()
        connectionsClient = ConnectionsClient(ownerSpaceClient)
    }

    // MARK: - createInvite tests

    @Test
    fun `createInvite sends correct message type and payload`() = runTest {
        // Arrange
        val response = VaultResponse.HandlerResult(
            requestId = "req-123",
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
        )
        whenever(ownerSpaceClient.sendAndAwaitResponse(any(), any(), any()))
            .thenReturn(response)

        // Act
        val result = connectionsClient.createInvite(
            peerGuid = "user-456",
            label = "Alice's Vault",
            expiresInMinutes = 48 * 60
        )

        // Assert
        assertTrue(result.isSuccess)
        verify(ownerSpaceClient).sendAndAwaitResponse(
            eq("connection.create-invite"),
            argThat { payload ->
                payload.get("peer_guid")?.asString == "user-456" &&
                payload.get("label")?.asString == "Alice's Vault" &&
                payload.get("expires_in_hours")?.asInt == 48
            },
            any()
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
        // Arrange - sendAndAwaitResponse throws exception
        whenever(ownerSpaceClient.sendAndAwaitResponse(any(), any(), any()))
            .thenAnswer { throw NatsException("Connection lost") }

        // Act
        val result = connectionsClient.createInvite("user-456", "Test")

        // Assert
        assertTrue(result.isFailure)
        assertEquals("Connection lost", result.exceptionOrNull()?.message)
    }

    @Test
    fun `createInvite returns failure when handler fails`() = runTest {
        // Arrange
        val response = VaultResponse.HandlerResult(
            requestId = "req-123",
            handlerId = null,
            success = false,
            result = null,
            error = "Peer not found"
        )
        whenever(ownerSpaceClient.sendAndAwaitResponse(any(), any(), any()))
            .thenReturn(response)

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
        val response = VaultResponse.HandlerResult(
            requestId = "req-456",
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
        )
        whenever(ownerSpaceClient.sendAndAwaitResponse(any(), any(), any()))
            .thenReturn(response)

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
        verify(ownerSpaceClient).sendAndAwaitResponse(
            eq("connection.store-credentials"),
            argThat { payload ->
                payload.get("connection_id")?.asString == "conn-xyz" &&
                payload.get("peer_guid")?.asString == "user-789" &&
                payload.get("nats_credentials")?.asString == "-----BEGIN NATS-----"
            },
            any()
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
        val response = VaultResponse.HandlerResult(
            requestId = "req-rotate",
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
        )
        whenever(ownerSpaceClient.sendAndAwaitResponse(any(), any(), any()))
            .thenReturn(response)

        // Act
        val result = connectionsClient.rotate("conn-abc")

        // Assert
        assertTrue(result.isSuccess)
        verify(ownerSpaceClient).sendAndAwaitResponse(
            eq("connection.rotate"),
            argThat { payload -> payload.get("connection_id")?.asString == "conn-abc" },
            any()
        )

        val record = result.getOrNull()!!
        assertEquals("2025-12-22T16:00:00Z", record.lastRotatedAt)
    }

    // MARK: - revoke tests

    @Test
    fun `revoke sends connection_id and returns success`() = runTest {
        // Arrange
        val response = VaultResponse.HandlerResult(
            requestId = "req-revoke",
            handlerId = null,
            success = true,
            result = JsonObject().apply {
                addProperty("success", true)
            },
            error = null
        )
        whenever(ownerSpaceClient.sendAndAwaitResponse(any(), any(), any()))
            .thenReturn(response)

        // Act
        val result = connectionsClient.revoke("conn-abc")

        // Assert
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!)
        verify(ownerSpaceClient).sendAndAwaitResponse(
            eq("connection.revoke"),
            argThat { payload -> payload.get("connection_id")?.asString == "conn-abc" },
            any()
        )
    }

    // MARK: - list tests

    @Test
    fun `list sends filters and parses paginated response`() = runTest {
        // Arrange
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
        val response = VaultResponse.HandlerResult(
            requestId = "req-list",
            handlerId = null,
            success = true,
            result = JsonObject().apply {
                add("items", items)
                addProperty("next_cursor", "cursor-abc")
            },
            error = null
        )
        whenever(ownerSpaceClient.sendAndAwaitResponse(any(), any(), any()))
            .thenReturn(response)

        // Act
        val result = connectionsClient.list(status = "active", limit = 10)

        // Assert
        assertTrue(result.isSuccess)
        verify(ownerSpaceClient).sendAndAwaitResponse(
            eq("connection.list"),
            argThat { payload ->
                payload.get("status")?.asString == "active" &&
                payload.get("limit")?.asInt == 10
            },
            any()
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
        val response = VaultResponse.HandlerResult(
            requestId = "req-list-all",
            handlerId = null,
            success = true,
            result = JsonObject().apply {
                add("items", JsonArray())
            },
            error = null
        )
        whenever(ownerSpaceClient.sendAndAwaitResponse(any(), any(), any()))
            .thenReturn(response)

        // Act
        val result = connectionsClient.list()

        // Assert
        assertTrue(result.isSuccess)
        verify(ownerSpaceClient).sendAndAwaitResponse(
            eq("connection.list"),
            argThat { payload ->
                !payload.has("status") &&
                payload.get("limit")?.asInt == 50
            },
            any()
        )
    }

    // MARK: - getCredentials tests

    @Test
    fun `getCredentials returns connection credentials`() = runTest {
        // Arrange
        val response = VaultResponse.HandlerResult(
            requestId = "req-creds",
            handlerId = null,
            success = true,
            result = JsonObject().apply {
                addProperty("nats_credentials", "-----BEGIN NATS USER JWT-----\ntest")
                addProperty("peer_message_space_id", "MessageSpace.peer123")
                addProperty("expires_at", "2025-12-25T00:00:00Z")
            },
            error = null
        )
        whenever(ownerSpaceClient.sendAndAwaitResponse(any(), any(), any()))
            .thenReturn(response)

        // Act
        val result = connectionsClient.getCredentials("conn-abc")

        // Assert
        assertTrue(result.isSuccess)
        verify(ownerSpaceClient).sendAndAwaitResponse(
            eq("connection.get-credentials"),
            argThat { payload -> payload.get("connection_id")?.asString == "conn-abc" },
            any()
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
        val response = VaultResponse.Error(
            requestId = "req-error",
            code = "NOT_FOUND",
            message = "Connection not found"
        )
        whenever(ownerSpaceClient.sendAndAwaitResponse(any(), any(), any()))
            .thenReturn(response)

        // Act
        val result = connectionsClient.getCredentials("conn-nonexistent")

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Connection not found") == true)
    }
}
