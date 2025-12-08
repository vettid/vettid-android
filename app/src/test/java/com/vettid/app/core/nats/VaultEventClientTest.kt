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
            eq("events.messaging.send"),
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
            eq("events.profile.update"),
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
            eq("events.connection.create"),
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
    fun `submitEvent returns unique request IDs`() = runTest {
        // Arrange
        val requestIds = mutableListOf<String>()
        whenever(ownerSpaceClient.sendToVault(any(), any())).thenAnswer {
            val payload = it.arguments[1] as JsonObject
            val requestId = payload.get("request_id")?.asString ?: "unknown"
            requestIds.add(requestId)
            Result.success(requestId)
        }

        val event = VaultSubmitEvent.SendMessage("user", "message")

        // Act
        repeat(3) {
            vaultEventClient.submitEvent(event)
        }

        // Assert
        assertEquals(3, requestIds.distinct().size)
    }

    @Test
    fun `subscribeToResponses maps HandlerResult correctly`() = runTest {
        // Arrange
        val responses = mutableListOf<VaultEventResponse>()
        val job = backgroundScope.launch {
            vaultEventClient.subscribeToResponses().toList(responses)
        }

        // Act
        vaultResponses.emit(
            VaultResponse.HandlerResult(
                requestId = "req-123",
                handlerId = "handler-1",
                success = true,
                result = JsonObject(),
                error = null
            )
        )
        testScheduler.advanceUntilIdle()

        // Assert
        assertEquals(1, responses.size)
        assertEquals("req-123", responses[0].requestId)
        assertEquals("success", responses[0].status)
        assertNull(responses[0].error)

        job.cancel()
    }

    @Test
    fun `subscribeToResponses maps Error correctly`() = runTest {
        // Arrange
        val responses = mutableListOf<VaultEventResponse>()
        val job = backgroundScope.launch {
            vaultEventClient.subscribeToResponses().toList(responses)
        }

        // Act
        vaultResponses.emit(
            VaultResponse.Error(
                requestId = "req-456",
                code = "HANDLER_ERROR",
                message = "Handler failed"
            )
        )
        testScheduler.advanceUntilIdle()

        // Assert
        assertEquals(1, responses.size)
        assertEquals("req-456", responses[0].requestId)
        assertEquals("error", responses[0].status)
        assertEquals("Handler failed", responses[0].error)

        job.cancel()
    }

    @Test
    fun `Custom event uses provided type and payload`() = runTest {
        // Arrange
        var capturedPayload: JsonObject? = null
        whenever(ownerSpaceClient.sendToVault(any(), any())).thenAnswer {
            capturedPayload = it.arguments[1] as JsonObject
            Result.success("test-id")
        }

        val customPayload = JsonObject().apply {
            addProperty("custom_field", "custom_value")
        }
        val event = VaultSubmitEvent.Custom("custom.event", customPayload)

        // Act
        vaultEventClient.submitEvent(event)

        // Assert
        verify(ownerSpaceClient).sendToVault(
            eq("events.custom.event"),
            any()
        )
        assertNotNull(capturedPayload)
    }
}
