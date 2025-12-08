package com.vettid.app.core.nats

import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class VaultResponseHandlerTest {

    private lateinit var vaultEventClient: VaultEventClient
    private lateinit var vaultResponseHandler: VaultResponseHandler

    @Before
    fun setup() {
        vaultEventClient = mock()
    }

    @Test
    fun `submitAndAwait matches request to response`() = runTest {
        // Arrange
        val responseFlow = MutableSharedFlow<VaultEventResponse>()
        whenever(vaultEventClient.subscribeToResponses()).thenReturn(responseFlow)
        whenever(vaultEventClient.submitEvent(any())).thenReturn(Result.success("req-123"))

        vaultResponseHandler = VaultResponseHandler(vaultEventClient)
        vaultResponseHandler.startListening()

        val event = VaultSubmitEvent.SendMessage("user", "message")

        // Act
        val resultDeferred = async {
            vaultResponseHandler.submitAndAwait(event, Duration.ofSeconds(5))
        }

        // Simulate response
        delay(100)
        responseFlow.emit(
            VaultEventResponse(
                requestId = "req-123",
                status = "success",
                result = JsonObject(),
                error = null,
                processedAt = "2025-01-01T00:00:00Z"
            )
        )

        val result = resultDeferred.await()

        // Assert
        assertTrue(result.isSuccess)
        assertEquals("req-123", result.getOrNull()?.requestId)
        assertEquals("success", result.getOrNull()?.status)

        vaultResponseHandler.stopListening()
    }

    @Test
    fun `submitAndAwait times out after duration`() = runTest {
        // Arrange
        val responseFlow = MutableSharedFlow<VaultEventResponse>()
        whenever(vaultEventClient.subscribeToResponses()).thenReturn(responseFlow)
        whenever(vaultEventClient.submitEvent(any())).thenReturn(Result.success("req-456"))

        vaultResponseHandler = VaultResponseHandler(vaultEventClient)
        vaultResponseHandler.startListening()

        val event = VaultSubmitEvent.SendMessage("user", "message")

        // Act - Use a very short timeout
        val result = vaultResponseHandler.submitAndAwait(
            event,
            Duration.ofMillis(100)
        )

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VaultTimeoutException)

        vaultResponseHandler.stopListening()
    }

    @Test
    fun `submitAndAwait returns failure when event submission fails`() = runTest {
        // Arrange
        val responseFlow = MutableSharedFlow<VaultEventResponse>()
        whenever(vaultEventClient.subscribeToResponses()).thenReturn(responseFlow)
        whenever(vaultEventClient.submitEvent(any()))
            .thenReturn(Result.failure(NatsException("Connection failed")))

        vaultResponseHandler = VaultResponseHandler(vaultEventClient)
        vaultResponseHandler.startListening()

        val event = VaultSubmitEvent.SendMessage("user", "message")

        // Act
        val result = vaultResponseHandler.submitAndAwait(event)

        // Assert
        assertTrue(result.isFailure)
        assertEquals("Connection failed", result.exceptionOrNull()?.message)

        vaultResponseHandler.stopListening()
    }

    @Test
    fun `submitFireAndForget returns request ID without waiting`() = runTest {
        // Arrange
        whenever(vaultEventClient.submitEvent(any()))
            .thenReturn(Result.success("fire-forget-id"))

        vaultResponseHandler = VaultResponseHandler(vaultEventClient)

        val event = VaultSubmitEvent.SendMessage("user", "message")

        // Act
        val result = vaultResponseHandler.submitFireAndForget(event)

        // Assert
        assertTrue(result.isSuccess)
        assertEquals("fire-forget-id", result.getOrNull())
    }

    @Test
    fun `hasPendingRequests returns correct state`() = runTest {
        // Arrange
        val responseFlow = MutableSharedFlow<VaultEventResponse>()
        whenever(vaultEventClient.subscribeToResponses()).thenReturn(responseFlow)
        whenever(vaultEventClient.submitEvent(any())).thenReturn(Result.success("pending-req"))

        vaultResponseHandler = VaultResponseHandler(vaultEventClient)
        vaultResponseHandler.startListening()

        // Initially no pending requests
        assertFalse(vaultResponseHandler.hasPendingRequests())

        // Submit event without response
        val event = VaultSubmitEvent.SendMessage("user", "message")
        val job = backgroundScope.launch {
            vaultResponseHandler.submitAndAwait(event, Duration.ofSeconds(30))
        }

        delay(50)

        // Should have pending request
        assertTrue(vaultResponseHandler.hasPendingRequests())
        assertEquals(1, vaultResponseHandler.pendingRequestCount())

        job.cancel()
        vaultResponseHandler.stopListening()
    }

    @Test
    fun `cancelAllPending cancels pending requests`() = runTest {
        // Arrange
        val responseFlow = MutableSharedFlow<VaultEventResponse>()
        whenever(vaultEventClient.subscribeToResponses()).thenReturn(responseFlow)
        whenever(vaultEventClient.submitEvent(any())).thenReturn(Result.success("cancel-req"))

        vaultResponseHandler = VaultResponseHandler(vaultEventClient)
        vaultResponseHandler.startListening()

        val event = VaultSubmitEvent.SendMessage("user", "message")

        // Submit event
        var caughtException: Throwable? = null
        val job = backgroundScope.launch {
            try {
                vaultResponseHandler.submitAndAwait(event, Duration.ofSeconds(30))
            } catch (e: Throwable) {
                caughtException = e
            }
        }

        delay(50)

        // Cancel all pending
        vaultResponseHandler.cancelAllPending()

        delay(50)

        // Should have no pending requests
        assertFalse(vaultResponseHandler.hasPendingRequests())

        job.cancel()
        vaultResponseHandler.stopListening()
    }

    @Test
    fun `multiple concurrent requests are handled independently`() = runTest {
        // Arrange
        val responseFlow = MutableSharedFlow<VaultEventResponse>()
        whenever(vaultEventClient.subscribeToResponses()).thenReturn(responseFlow)

        var requestCounter = 0
        whenever(vaultEventClient.submitEvent(any())).thenAnswer {
            requestCounter++
            Result.success("req-$requestCounter")
        }

        vaultResponseHandler = VaultResponseHandler(vaultEventClient)
        vaultResponseHandler.startListening()

        // Act - Submit multiple events concurrently
        val event1 = VaultSubmitEvent.SendMessage("user1", "message1")
        val event2 = VaultSubmitEvent.SendMessage("user2", "message2")

        val result1Deferred = async {
            vaultResponseHandler.submitAndAwait(event1, Duration.ofSeconds(5))
        }
        val result2Deferred = async {
            vaultResponseHandler.submitAndAwait(event2, Duration.ofSeconds(5))
        }

        delay(100)

        // Respond to first request
        responseFlow.emit(
            VaultEventResponse(
                requestId = "req-1",
                status = "success",
                result = JsonObject().apply { addProperty("msg", "response1") },
                error = null,
                processedAt = "2025-01-01T00:00:00Z"
            )
        )

        delay(50)

        // Respond to second request
        responseFlow.emit(
            VaultEventResponse(
                requestId = "req-2",
                status = "success",
                result = JsonObject().apply { addProperty("msg", "response2") },
                error = null,
                processedAt = "2025-01-01T00:00:01Z"
            )
        )

        val result1 = result1Deferred.await()
        val result2 = result2Deferred.await()

        // Assert
        assertTrue(result1.isSuccess)
        assertTrue(result2.isSuccess)
        assertEquals("req-1", result1.getOrNull()?.requestId)
        assertEquals("req-2", result2.getOrNull()?.requestId)

        vaultResponseHandler.stopListening()
    }
}
