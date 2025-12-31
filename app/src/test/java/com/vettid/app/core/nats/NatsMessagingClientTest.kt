package com.vettid.app.core.nats

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
class NatsMessagingClientTest {

    private lateinit var ownerSpaceClient: OwnerSpaceClient
    private lateinit var messagingClient: NatsMessagingClient

    private val vaultResponsesFlow = MutableSharedFlow<VaultResponse>(extraBufferCapacity = 64)

    @Before
    fun setup() {
        ownerSpaceClient = mock()
        whenever(ownerSpaceClient.vaultResponses).thenReturn(vaultResponsesFlow)
        messagingClient = NatsMessagingClient(ownerSpaceClient)
    }

    // MARK: - sendMessage Tests

    @Test
    fun `sendMessage sends correct payload`() = runTest {
        val requestId = "test-request-123"
        whenever(ownerSpaceClient.sendToVault(eq("message.send"), any())).thenReturn(
            Result.success(requestId)
        )

        // Emit response in background
        backgroundScope.launch {
            testScheduler.advanceTimeBy(100)
            vaultResponsesFlow.emit(
                VaultResponse.HandlerResult(
                    requestId = requestId,
                    handlerId = "message.send",
                    success = true,
                    result = JsonObject().apply {
                        addProperty("message_id", "msg-456")
                        addProperty("timestamp", "2025-12-30T12:00:00Z")
                        addProperty("status", "sent")
                    },
                    error = null
                )
            )
        }

        val result = messagingClient.sendMessage(
            connectionId = "conn-123",
            encryptedContent = "base64-encrypted-content",
            nonce = "base64-nonce",
            contentType = "text"
        )

        assertTrue(result.isSuccess)
        val sentMessage = result.getOrThrow()
        assertEquals("msg-456", sentMessage.messageId)
        assertEquals("conn-123", sentMessage.connectionId)
        assertEquals("2025-12-30T12:00:00Z", sentMessage.timestamp)
        assertEquals("sent", sentMessage.status)

        verify(ownerSpaceClient).sendToVault(eq("message.send"), argThat { payload ->
            payload.get("connection_id")?.asString == "conn-123" &&
            payload.get("encrypted_content")?.asString == "base64-encrypted-content" &&
            payload.get("nonce")?.asString == "base64-nonce" &&
            payload.get("content_type")?.asString == "text"
        })
    }

    @Test
    fun `sendMessage returns failure when send fails`() = runTest {
        whenever(ownerSpaceClient.sendToVault(eq("message.send"), any())).thenReturn(
            Result.failure(NatsException("Connection failed"))
        )

        val result = messagingClient.sendMessage(
            connectionId = "conn-123",
            encryptedContent = "content",
            nonce = "nonce"
        )

        assertTrue(result.isFailure)
        assertEquals("Connection failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun `sendMessage returns failure when handler returns error`() = runTest {
        val requestId = "test-request-456"
        whenever(ownerSpaceClient.sendToVault(eq("message.send"), any())).thenReturn(
            Result.success(requestId)
        )

        backgroundScope.launch {
            testScheduler.advanceTimeBy(100)
            vaultResponsesFlow.emit(
                VaultResponse.HandlerResult(
                    requestId = requestId,
                    handlerId = "message.send",
                    success = false,
                    result = null,
                    error = "Recipient not found"
                )
            )
        }

        val result = messagingClient.sendMessage(
            connectionId = "conn-invalid",
            encryptedContent = "content",
            nonce = "nonce"
        )

        assertTrue(result.isFailure)
        assertEquals("Recipient not found", result.exceptionOrNull()?.message)
    }

    // MARK: - sendReadReceipt Tests

    @Test
    fun `sendReadReceipt sends correct payload`() = runTest {
        val requestId = "read-receipt-req-123"
        whenever(ownerSpaceClient.sendToVault(eq("message.read-receipt"), any())).thenReturn(
            Result.success(requestId)
        )

        backgroundScope.launch {
            testScheduler.advanceTimeBy(100)
            vaultResponsesFlow.emit(
                VaultResponse.HandlerResult(
                    requestId = requestId,
                    handlerId = "message.read-receipt",
                    success = true,
                    result = JsonObject().apply {
                        addProperty("success", true)
                    },
                    error = null
                )
            )
        }

        val result = messagingClient.sendReadReceipt(
            connectionId = "conn-123",
            messageId = "msg-456"
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())

        verify(ownerSpaceClient).sendToVault(eq("message.read-receipt"), argThat { payload ->
            payload.get("connection_id")?.asString == "conn-123" &&
            payload.get("message_id")?.asString == "msg-456"
        })
    }

    @Test
    fun `sendReadReceipt returns failure when send fails`() = runTest {
        whenever(ownerSpaceClient.sendToVault(eq("message.read-receipt"), any())).thenReturn(
            Result.failure(NatsException("Network error"))
        )

        val result = messagingClient.sendReadReceipt(
            connectionId = "conn-123",
            messageId = "msg-456"
        )

        assertTrue(result.isFailure)
    }

    // MARK: - broadcastProfileUpdate Tests

    @Test
    fun `broadcastProfileUpdate sends all fields`() = runTest {
        val requestId = "profile-broadcast-123"
        whenever(ownerSpaceClient.sendToVault(eq("profile.broadcast"), any())).thenReturn(
            Result.success(requestId)
        )

        backgroundScope.launch {
            testScheduler.advanceTimeBy(100)
            vaultResponsesFlow.emit(
                VaultResponse.HandlerResult(
                    requestId = requestId,
                    handlerId = "profile.broadcast",
                    success = true,
                    result = JsonObject().apply {
                        addProperty("notified_count", 5)
                    },
                    error = null
                )
            )
        }

        val result = messagingClient.broadcastProfileUpdate(
            displayName = "John Doe",
            avatarUrl = "https://example.com/avatar.png",
            status = "Online"
        )

        assertTrue(result.isSuccess)
        assertEquals(5, result.getOrThrow())

        verify(ownerSpaceClient).sendToVault(eq("profile.broadcast"), argThat { payload ->
            val profile = payload.getAsJsonObject("profile")
            profile.get("display_name")?.asString == "John Doe" &&
            profile.get("avatar_url")?.asString == "https://example.com/avatar.png" &&
            profile.get("status")?.asString == "Online"
        })
    }

    @Test
    fun `broadcastProfileUpdate sends only changed fields`() = runTest {
        val requestId = "profile-broadcast-456"
        whenever(ownerSpaceClient.sendToVault(eq("profile.broadcast"), any())).thenReturn(
            Result.success(requestId)
        )

        backgroundScope.launch {
            testScheduler.advanceTimeBy(100)
            vaultResponsesFlow.emit(
                VaultResponse.HandlerResult(
                    requestId = requestId,
                    handlerId = "profile.broadcast",
                    success = true,
                    result = JsonObject().apply {
                        addProperty("notified_count", 3)
                    },
                    error = null
                )
            )
        }

        val result = messagingClient.broadcastProfileUpdate(
            displayName = "Jane Doe"
            // avatarUrl and status are null - should not be included
        )

        assertTrue(result.isSuccess)

        verify(ownerSpaceClient).sendToVault(eq("profile.broadcast"), argThat { payload ->
            val profile = payload.getAsJsonObject("profile")
            profile.has("display_name") &&
            !profile.has("avatar_url") &&
            !profile.has("status")
        })
    }

    // MARK: - notifyConnectionRevoked Tests

    @Test
    fun `notifyConnectionRevoked sends correct payload`() = runTest {
        val requestId = "revoke-notify-123"
        whenever(ownerSpaceClient.sendToVault(eq("connection.notify-revoke"), any())).thenReturn(
            Result.success(requestId)
        )

        backgroundScope.launch {
            testScheduler.advanceTimeBy(100)
            vaultResponsesFlow.emit(
                VaultResponse.HandlerResult(
                    requestId = requestId,
                    handlerId = "connection.notify-revoke",
                    success = true,
                    result = JsonObject().apply {
                        addProperty("success", true)
                    },
                    error = null
                )
            )
        }

        val result = messagingClient.notifyConnectionRevoked(
            connectionId = "conn-to-revoke",
            reason = "User requested"
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())

        verify(ownerSpaceClient).sendToVault(eq("connection.notify-revoke"), argThat { payload ->
            payload.get("connection_id")?.asString == "conn-to-revoke" &&
            payload.get("reason")?.asString == "User requested"
        })
    }

    @Test
    fun `notifyConnectionRevoked sends without reason when null`() = runTest {
        val requestId = "revoke-notify-456"
        whenever(ownerSpaceClient.sendToVault(eq("connection.notify-revoke"), any())).thenReturn(
            Result.success(requestId)
        )

        backgroundScope.launch {
            testScheduler.advanceTimeBy(100)
            vaultResponsesFlow.emit(
                VaultResponse.HandlerResult(
                    requestId = requestId,
                    handlerId = "connection.notify-revoke",
                    success = true,
                    result = JsonObject().apply {
                        addProperty("success", true)
                    },
                    error = null
                )
            )
        }

        val result = messagingClient.notifyConnectionRevoked(
            connectionId = "conn-to-revoke"
        )

        assertTrue(result.isSuccess)

        verify(ownerSpaceClient).sendToVault(eq("connection.notify-revoke"), argThat { payload ->
            payload.get("connection_id")?.asString == "conn-to-revoke" &&
            !payload.has("reason")
        })
    }

    // MARK: - Data Model Tests

    @Test
    fun `SentMessage data class holds correct values`() {
        val message = SentMessage(
            messageId = "msg-123",
            connectionId = "conn-456",
            timestamp = "2025-12-30T10:00:00Z",
            status = "delivered"
        )

        assertEquals("msg-123", message.messageId)
        assertEquals("conn-456", message.connectionId)
        assertEquals("2025-12-30T10:00:00Z", message.timestamp)
        assertEquals("delivered", message.status)
    }

    @Test
    fun `IncomingMessage data class holds correct values`() {
        val message = IncomingMessage(
            messageId = "msg-789",
            connectionId = "conn-123",
            senderGuid = "user-abc",
            encryptedContent = "base64-content",
            nonce = "base64-nonce",
            contentType = "text",
            sentAt = "2025-12-30T09:00:00Z"
        )

        assertEquals("msg-789", message.messageId)
        assertEquals("conn-123", message.connectionId)
        assertEquals("user-abc", message.senderGuid)
        assertEquals("base64-content", message.encryptedContent)
        assertEquals("base64-nonce", message.nonce)
        assertEquals("text", message.contentType)
        assertEquals("2025-12-30T09:00:00Z", message.sentAt)
    }

    @Test
    fun `ReadReceipt data class holds correct values`() {
        val receipt = ReadReceipt(
            messageId = "msg-123",
            connectionId = "conn-456",
            readerGuid = "user-reader",
            readAt = "2025-12-30T11:00:00Z"
        )

        assertEquals("msg-123", receipt.messageId)
        assertEquals("conn-456", receipt.connectionId)
        assertEquals("user-reader", receipt.readerGuid)
        assertEquals("2025-12-30T11:00:00Z", receipt.readAt)
    }

    @Test
    fun `ProfileUpdate data class holds correct values`() {
        val update = ProfileUpdate(
            connectionId = "conn-123",
            peerGuid = "user-peer",
            displayName = "New Name",
            avatarUrl = "https://avatar.url",
            status = "Away",
            updatedAt = "2025-12-30T12:00:00Z"
        )

        assertEquals("conn-123", update.connectionId)
        assertEquals("user-peer", update.peerGuid)
        assertEquals("New Name", update.displayName)
        assertEquals("https://avatar.url", update.avatarUrl)
        assertEquals("Away", update.status)
        assertEquals("2025-12-30T12:00:00Z", update.updatedAt)
    }

    @Test
    fun `ConnectionRevoked data class holds correct values`() {
        val revoked = ConnectionRevoked(
            connectionId = "conn-revoked",
            peerGuid = "user-peer",
            reason = "User blocked",
            revokedAt = "2025-12-30T13:00:00Z"
        )

        assertEquals("conn-revoked", revoked.connectionId)
        assertEquals("user-peer", revoked.peerGuid)
        assertEquals("User blocked", revoked.reason)
        assertEquals("2025-12-30T13:00:00Z", revoked.revokedAt)
    }

    @Test
    fun `ConnectionRevoked reason can be null`() {
        val revoked = ConnectionRevoked(
            connectionId = "conn-revoked",
            peerGuid = "user-peer",
            reason = null,
            revokedAt = "2025-12-30T13:00:00Z"
        )

        assertNull(revoked.reason)
    }
}
