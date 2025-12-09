package com.vettid.app.core.nats

import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VaultResponseHandlerTest {

    private lateinit var vaultEventClient: VaultEventClient
    private lateinit var vaultResponseHandler: VaultResponseHandler

    @Before
    fun setup() {
        vaultEventClient = mock()
    }

    @Test
    fun `submitAndAwait returns failure when event submission fails`() = runTest {
        // Arrange
        val responseFlow = MutableSharedFlow<VaultEventResponse>()
        whenever(vaultEventClient.subscribeToResponses()).thenReturn(responseFlow)
        whenever(vaultEventClient.submitEvent(any()))
            .thenReturn(Result.failure(NatsException("Submission failed")))

        vaultResponseHandler = VaultResponseHandler(vaultEventClient)

        val event = VaultSubmitEvent.SendMessage("user", "message")

        // Act
        val result = vaultResponseHandler.submitAndAwait(event, Duration.ofSeconds(1))

        // Assert - should fail immediately without waiting
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Submission failed") == true)
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
    fun `hasPendingRequests initially returns false`() = runTest {
        // Arrange
        val responseFlow = MutableSharedFlow<VaultEventResponse>()
        whenever(vaultEventClient.subscribeToResponses()).thenReturn(responseFlow)

        vaultResponseHandler = VaultResponseHandler(vaultEventClient)

        // Assert - no pending requests initially
        assertFalse(vaultResponseHandler.hasPendingRequests())
        assertEquals(0, vaultResponseHandler.pendingRequestCount())
    }

    @Test
    fun `cancelAllPending works with no pending requests`() = runTest {
        // Arrange
        val responseFlow = MutableSharedFlow<VaultEventResponse>()
        whenever(vaultEventClient.subscribeToResponses()).thenReturn(responseFlow)

        vaultResponseHandler = VaultResponseHandler(vaultEventClient)

        // cancelAllPending should work even with no pending requests
        vaultResponseHandler.cancelAllPending()

        // Assert
        assertFalse(vaultResponseHandler.hasPendingRequests())
    }

    @Test
    fun `stopListening clears pending requests`() = runTest {
        // Arrange
        val responseFlow = MutableSharedFlow<VaultEventResponse>()
        whenever(vaultEventClient.subscribeToResponses()).thenReturn(responseFlow)

        vaultResponseHandler = VaultResponseHandler(vaultEventClient)
        vaultResponseHandler.startListening()

        // Act
        vaultResponseHandler.stopListening()

        // Assert
        assertFalse(vaultResponseHandler.hasPendingRequests())
    }
}
