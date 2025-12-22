package com.vettid.app.core.nats

import com.google.gson.JsonObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class VaultEventClientTest {

    private lateinit var ownerSpaceClient: OwnerSpaceClient
    private lateinit var vaultEventClient: VaultEventClient
    private lateinit var vaultResponses: MutableSharedFlow<VaultResponse>

    @Before
    fun setup() {
        ownerSpaceClient = mock()
        vaultResponses = MutableSharedFlow()
        whenever(ownerSpaceClient.vaultResponses).thenReturn(vaultResponses)
        vaultEventClient = VaultEventClient(ownerSpaceClient)
    }

    @Test
    fun `submitEvent sends to correct topic for SendMessage`() = runTest {
        // Arrange
        whenever(ownerSpaceClient.sendToVault(any(), any()))
            .thenReturn(Result.success("test-request-id"))

        val event = VaultSubmitEvent.SendMessage(
            recipient = "user-123",
            content = "Hello world"
        )

        // Act
        val result = vaultEventClient.submitEvent(event)

        // Assert
        assertTrue(result.isSuccess)
        verify(ownerSpaceClient).sendToVault(
            eq("messaging.send"),  // No events. prefix per vault-manager format
            any()
        )
    }

    @Test
    fun `submitEvent sends to correct topic for UpdateProfile`() = runTest {
        // Arrange
        whenever(ownerSpaceClient.sendToVault(any(), any()))
            .thenReturn(Result.success("test-request-id"))

        val event = VaultSubmitEvent.UpdateProfile(
            updates = mapOf("name" to "John", "bio" to "Developer")
        )

        // Act
        val result = vaultEventClient.submitEvent(event)

        // Assert
        assertTrue(result.isSuccess)
        verify(ownerSpaceClient).sendToVault(
            eq("profile.update"),  // No events. prefix per vault-manager format
            any()
        )
    }

    @Test
    fun `submitEvent sends to correct topic for CreateConnection`() = runTest {
        // Arrange
        whenever(ownerSpaceClient.sendToVault(any(), any()))
            .thenReturn(Result.success("test-request-id"))

        val event = VaultSubmitEvent.CreateConnection(inviteCode = "ABC123")

        // Act
        val result = vaultEventClient.submitEvent(event)

        // Assert
        assertTrue(result.isSuccess)
        verify(ownerSpaceClient).sendToVault(
            eq("connection.create"),  // No events. prefix per vault-manager format
            any()
        )
    }

    @Test
    fun `submitEvent returns failure when ownerSpaceClient fails`() = runTest {
        // Arrange
        whenever(ownerSpaceClient.sendToVault(any(), any()))
            .thenReturn(Result.failure(NatsException("Connection failed")))

        val event = VaultSubmitEvent.SendMessage(
            recipient = "user-123",
            content = "Hello"
        )

        // Act
        val result = vaultEventClient.submitEvent(event)

        // Assert
        assertTrue(result.isFailure)
        assertEquals("Connection failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun `submitEvent returns generated request ID on success`() = runTest {
        // Arrange - sendToVault returns a request ID that gets mapped
        whenever(ownerSpaceClient.sendToVault(any(), any()))
            .thenReturn(Result.success("some-id"))

        val event = VaultSubmitEvent.SendMessage("user", "message")

        // Act
        val result = vaultEventClient.submitEvent(event)

        // Assert - should return a UUID (generated internally)
        assertTrue(result.isSuccess)
        assertNotNull(result.getOrNull())
        // UUID format check
        assertTrue(result.getOrNull()!!.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun `subscribeToResponses maps HandlerResult correctly`() = runTest {
        // Arrange
        val response = VaultResponse.HandlerResult(
            requestId = "req-123",
            handlerId = "handler-1",
            success = true,
            result = JsonObject(),
            error = null
        )

        // Act - emit first, then collect
        backgroundScope.launch {
            vaultResponses.emit(response)
        }

        val mappedResponse = vaultEventClient.subscribeToResponses().first()

        // Assert
        assertEquals("req-123", mappedResponse.requestId)
        assertEquals("success", mappedResponse.status)
        assertNull(mappedResponse.error)
    }

    @Test
    fun `subscribeToResponses maps Error correctly`() = runTest {
        // Arrange
        val response = VaultResponse.Error(
            requestId = "req-456",
            code = "HANDLER_ERROR",
            message = "Handler failed"
        )

        // Act - emit first, then collect
        backgroundScope.launch {
            vaultResponses.emit(response)
        }

        val mappedResponse = vaultEventClient.subscribeToResponses().first()

        // Assert
        assertEquals("req-456", mappedResponse.requestId)
        assertEquals("error", mappedResponse.status)
        assertEquals("Handler failed", mappedResponse.error)
    }

    @Test
    fun `Custom event uses provided type and payload`() = runTest {
        // Arrange
        whenever(ownerSpaceClient.sendToVault(any(), any())).thenReturn(Result.success("test-id"))

        val customPayload = JsonObject().apply {
            addProperty("custom_field", "custom_value")
        }
        val event = VaultSubmitEvent.Custom("custom.event", customPayload)

        // Act
        val result = vaultEventClient.submitEvent(event)

        // Assert
        assertTrue(result.isSuccess)
        verify(ownerSpaceClient).sendToVault(
            eq("custom.event"),  // No events. prefix per vault-manager format
            any()
        )
    }
}
