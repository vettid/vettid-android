package com.vettid.app.ui.recovery

import com.vettid.app.core.network.RecoveryDownloadResponse
import com.vettid.app.core.network.RecoveryRequestResponse
import com.vettid.app.core.network.RecoveryStatusResponse
import com.vettid.app.core.network.VaultServiceClient
import com.vettid.app.core.storage.ProteanCredentialManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ProteanRecoveryViewModelTest {

    private lateinit var vaultServiceClient: VaultServiceClient
    private lateinit var proteanCredentialManager: ProteanCredentialManager
    private lateinit var viewModel: ProteanRecoveryViewModel

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        vaultServiceClient = mock()
        proteanCredentialManager = mock()
        viewModel = ProteanRecoveryViewModel(vaultServiceClient, proteanCredentialManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // MARK: - Initial State Tests

    @Test
    fun `initial state is EnteringCredentials`() {
        assertEquals(ProteanRecoveryState.EnteringCredentials, viewModel.state.value)
    }

    @Test
    fun `initial email is empty`() {
        assertEquals("", viewModel.email.value)
    }

    @Test
    fun `initial backupPin is empty`() {
        assertEquals("", viewModel.backupPin.value)
    }

    @Test
    fun `initial isValidInput is false`() {
        assertFalse(viewModel.isValidInput.value)
    }

    // MARK: - Email Input Tests

    @Test
    fun `setEmail updates email value`() {
        viewModel.setEmail("test@example.com")
        assertEquals("test@example.com", viewModel.email.value)
    }

    @Test
    fun `setEmail trims whitespace`() {
        viewModel.setEmail("  test@example.com  ")
        assertEquals("test@example.com", viewModel.email.value)
    }

    @Test
    fun `email without @ is invalid`() {
        viewModel.setEmail("testexample.com")
        viewModel.setBackupPin("123456")
        assertFalse(viewModel.isValidInput.value)
    }

    @Test
    fun `email without dot is invalid`() {
        viewModel.setEmail("test@examplecom")
        viewModel.setBackupPin("123456")
        assertFalse(viewModel.isValidInput.value)
    }

    // MARK: - Backup PIN Input Tests

    @Test
    fun `setBackupPin updates pin value`() {
        viewModel.setBackupPin("123456")
        assertEquals("123456", viewModel.backupPin.value)
    }

    @Test
    fun `setBackupPin filters non-digits`() {
        viewModel.setBackupPin("12ab34cd56")
        assertEquals("123456", viewModel.backupPin.value)
    }

    @Test
    fun `setBackupPin limits to 6 characters`() {
        viewModel.setBackupPin("12345678")
        assertEquals("123456", viewModel.backupPin.value)
    }

    @Test
    fun `backup pin with less than 6 digits is invalid`() {
        viewModel.setEmail("test@example.com")
        viewModel.setBackupPin("12345")
        assertFalse(viewModel.isValidInput.value)
    }

    // MARK: - Combined Validation Tests

    @Test
    fun `valid email and 6-digit pin makes input valid`() {
        viewModel.setEmail("test@example.com")
        viewModel.setBackupPin("123456")
        assertTrue(viewModel.isValidInput.value)
    }

    @Test
    fun `valid email alone is not enough`() {
        viewModel.setEmail("test@example.com")
        assertFalse(viewModel.isValidInput.value)
    }

    @Test
    fun `valid pin alone is not enough`() {
        viewModel.setBackupPin("123456")
        assertFalse(viewModel.isValidInput.value)
    }

    // MARK: - Request Recovery Tests

    @Test
    fun `requestRecovery does nothing when input is invalid`() = runTest {
        viewModel.setEmail("invalid")
        viewModel.setBackupPin("123")

        viewModel.requestRecovery()
        runCurrent()

        assertEquals(ProteanRecoveryState.EnteringCredentials, viewModel.state.value)
        verify(vaultServiceClient, never()).requestRecovery(any(), any())
    }

    @Test
    fun `requestRecovery transitions to Pending on success`() = runTest {
        val response = RecoveryRequestResponse(
            recoveryId = "recovery-123",
            requestedAt = "2026-01-02T12:00:00Z",
            availableAt = "2026-01-03T12:00:00Z",
            status = "pending"
        )
        whenever(vaultServiceClient.requestRecovery(any(), any())).thenReturn(Result.success(response))
        // Mock status check to fail (prevents polling from hanging)
        whenever(vaultServiceClient.getRecoveryStatus(any())).thenReturn(Result.failure(Exception("skip")))

        viewModel.setEmail("test@example.com")
        viewModel.setBackupPin("123456")
        viewModel.requestRecovery()
        runCurrent()

        val state = viewModel.state.value
        assertTrue("Expected Pending state but got $state", state is ProteanRecoveryState.Pending)
        assertEquals("recovery-123", (state as ProteanRecoveryState.Pending).recoveryId)

        // Stop polling to prevent OOM
        viewModel.reset()
    }

    @Test
    fun `requestRecovery transitions to Error on failure`() = runTest {
        whenever(vaultServiceClient.requestRecovery(any(), any())).thenReturn(Result.failure(Exception("Network error")))

        viewModel.setEmail("test@example.com")
        viewModel.setBackupPin("123456")
        viewModel.requestRecovery()
        runCurrent()

        val state = viewModel.state.value
        assertTrue("Expected Error state but got $state", state is ProteanRecoveryState.Error)
        assertEquals("Network error", (state as ProteanRecoveryState.Error).message)
        assertTrue(state.canRetry)
    }

    @Test
    fun `requestRecovery uses default error message when exception has no message`() = runTest {
        whenever(vaultServiceClient.requestRecovery(any(), any())).thenReturn(Result.failure(Exception()))

        viewModel.setEmail("test@example.com")
        viewModel.setBackupPin("123456")
        viewModel.requestRecovery()
        runCurrent()

        val state = viewModel.state.value as ProteanRecoveryState.Error
        assertEquals("Failed to request recovery", state.message)
    }

    // MARK: - Check Status Tests

    @Test
    fun `checkStatus does nothing when no recovery in progress`() = runTest {
        viewModel.checkStatus()
        runCurrent()

        verify(vaultServiceClient, never()).getRecoveryStatus(any())
    }

    @Test
    fun `checkStatus updates state to Ready when status is ready`() = runTest {
        // First set up a pending recovery
        val requestResponse = RecoveryRequestResponse(
            recoveryId = "recovery-123",
            requestedAt = "2026-01-02T12:00:00Z",
            availableAt = "2026-01-03T12:00:00Z",
            status = "pending"
        )
        whenever(vaultServiceClient.requestRecovery(any(), any())).thenReturn(Result.success(requestResponse))
        // Initially fail status check to prevent polling from completing
        whenever(vaultServiceClient.getRecoveryStatus(any())).thenReturn(Result.failure(Exception("skip")))

        viewModel.setEmail("test@example.com")
        viewModel.setBackupPin("123456")
        viewModel.requestRecovery()
        runCurrent()

        // Now mock status check to return ready
        val statusResponse = RecoveryStatusResponse(
            recoveryId = "recovery-123",
            status = "ready",
            availableAt = "2026-01-03T12:00:00Z",
            remainingSeconds = 0
        )
        whenever(vaultServiceClient.getRecoveryStatus(any())).thenReturn(Result.success(statusResponse))

        viewModel.checkStatus()
        runCurrent()

        val state = viewModel.state.value
        assertTrue("Expected Ready state but got $state", state is ProteanRecoveryState.Ready)
    }

    @Test
    fun `checkStatus updates state to Cancelled when status is cancelled`() = runTest {
        // First set up a pending recovery
        val requestResponse = RecoveryRequestResponse(
            recoveryId = "recovery-123",
            requestedAt = "2026-01-02T12:00:00Z",
            availableAt = "2026-01-03T12:00:00Z",
            status = "pending"
        )
        whenever(vaultServiceClient.requestRecovery(any(), any())).thenReturn(Result.success(requestResponse))
        whenever(vaultServiceClient.getRecoveryStatus(any())).thenReturn(Result.failure(Exception("skip")))

        viewModel.setEmail("test@example.com")
        viewModel.setBackupPin("123456")
        viewModel.requestRecovery()
        runCurrent()

        // Now mock status check to return cancelled
        val statusResponse = RecoveryStatusResponse(
            recoveryId = "recovery-123",
            status = "cancelled",
            availableAt = "2026-01-03T12:00:00Z",
            remainingSeconds = 0
        )
        whenever(vaultServiceClient.getRecoveryStatus(any())).thenReturn(Result.success(statusResponse))

        viewModel.checkStatus()
        runCurrent()

        assertEquals(ProteanRecoveryState.Cancelled, viewModel.state.value)
    }

    @Test
    fun `checkStatus updates state to Error when status is expired`() = runTest {
        // First set up a pending recovery
        val requestResponse = RecoveryRequestResponse(
            recoveryId = "recovery-123",
            requestedAt = "2026-01-02T12:00:00Z",
            availableAt = "2026-01-03T12:00:00Z",
            status = "pending"
        )
        whenever(vaultServiceClient.requestRecovery(any(), any())).thenReturn(Result.success(requestResponse))
        whenever(vaultServiceClient.getRecoveryStatus(any())).thenReturn(Result.failure(Exception("skip")))

        viewModel.setEmail("test@example.com")
        viewModel.setBackupPin("123456")
        viewModel.requestRecovery()
        runCurrent()

        // Now mock status check to return expired
        val statusResponse = RecoveryStatusResponse(
            recoveryId = "recovery-123",
            status = "expired",
            availableAt = "2026-01-03T12:00:00Z",
            remainingSeconds = 0
        )
        whenever(vaultServiceClient.getRecoveryStatus(any())).thenReturn(Result.success(statusResponse))

        viewModel.checkStatus()
        runCurrent()

        val state = viewModel.state.value
        assertTrue("Expected Error state but got $state", state is ProteanRecoveryState.Error)
        assertFalse((state as ProteanRecoveryState.Error).canRetry)
    }

    @Test
    fun `checkStatus does not change state on failure`() = runTest {
        // First set up a pending recovery
        val requestResponse = RecoveryRequestResponse(
            recoveryId = "recovery-123",
            requestedAt = "2026-01-02T12:00:00Z",
            availableAt = "2026-01-03T12:00:00Z",
            status = "pending"
        )
        whenever(vaultServiceClient.requestRecovery(any(), any())).thenReturn(Result.success(requestResponse))
        whenever(vaultServiceClient.getRecoveryStatus(any())).thenReturn(Result.failure(Exception("Network error")))

        viewModel.setEmail("test@example.com")
        viewModel.setBackupPin("123456")
        viewModel.requestRecovery()
        runCurrent()

        // State is Pending
        assertTrue(viewModel.state.value is ProteanRecoveryState.Pending)

        viewModel.checkStatus()
        runCurrent()

        // State should remain unchanged (still Pending)
        assertTrue(viewModel.state.value is ProteanRecoveryState.Pending)

        // Stop polling to prevent OOM
        viewModel.reset()
    }

    // MARK: - Resume Recovery Tests

    @Test
    fun `resumeRecovery sets state to Ready when status is ready`() = runTest {
        // Use "ready" status to avoid polling job startup
        val statusResponse = RecoveryStatusResponse(
            recoveryId = "recovery-456",
            status = "ready",
            availableAt = "2026-01-03T12:00:00Z",
            remainingSeconds = 0
        )
        whenever(vaultServiceClient.getRecoveryStatus(any())).thenReturn(Result.success(statusResponse))

        viewModel.resumeRecovery("recovery-456")
        runCurrent()

        val state = viewModel.state.value
        assertTrue("Expected Ready state but got $state", state is ProteanRecoveryState.Ready)
    }

    @Test
    fun `resumeRecovery sets state to Error on failure`() = runTest {
        whenever(vaultServiceClient.getRecoveryStatus(any())).thenReturn(Result.failure(Exception("Not found")))

        viewModel.resumeRecovery("recovery-456")
        runCurrent()

        val state = viewModel.state.value
        assertTrue("Expected Error state but got $state", state is ProteanRecoveryState.Error)
        assertTrue((state as ProteanRecoveryState.Error).message.contains("Failed to resume recovery"))
        assertFalse(state.canRetry)
    }

    // MARK: - Cancel Recovery Tests

    @Test
    fun `cancelRecovery does nothing when no recovery in progress`() = runTest {
        viewModel.cancelRecovery()
        runCurrent()

        verify(vaultServiceClient, never()).cancelRecovery(any())
    }

    @Test
    fun `cancelRecovery transitions to Cancelled on success`() = runTest {
        // First set up a pending recovery
        val requestResponse = RecoveryRequestResponse(
            recoveryId = "recovery-123",
            requestedAt = "2026-01-02T12:00:00Z",
            availableAt = "2026-01-03T12:00:00Z",
            status = "pending"
        )
        whenever(vaultServiceClient.requestRecovery(any(), any())).thenReturn(Result.success(requestResponse))
        whenever(vaultServiceClient.getRecoveryStatus(any())).thenReturn(Result.failure(Exception("skip")))
        whenever(vaultServiceClient.cancelRecovery(any())).thenReturn(Result.success(Unit))

        viewModel.setEmail("test@example.com")
        viewModel.setBackupPin("123456")
        viewModel.requestRecovery()
        runCurrent()

        viewModel.cancelRecovery()
        runCurrent()

        assertEquals(ProteanRecoveryState.Cancelled, viewModel.state.value)
    }

    @Test
    fun `cancelRecovery does not change state on failure`() = runTest {
        // First set up a pending recovery
        val requestResponse = RecoveryRequestResponse(
            recoveryId = "recovery-123",
            requestedAt = "2026-01-02T12:00:00Z",
            availableAt = "2026-01-03T12:00:00Z",
            status = "pending"
        )
        whenever(vaultServiceClient.requestRecovery(any(), any())).thenReturn(Result.success(requestResponse))
        whenever(vaultServiceClient.getRecoveryStatus(any())).thenReturn(Result.failure(Exception("skip")))
        whenever(vaultServiceClient.cancelRecovery(any())).thenReturn(Result.failure(Exception("Network error")))

        viewModel.setEmail("test@example.com")
        viewModel.setBackupPin("123456")
        viewModel.requestRecovery()
        runCurrent()

        viewModel.cancelRecovery()
        runCurrent()

        // State should remain unchanged
        assertTrue(viewModel.state.value is ProteanRecoveryState.Pending)

        // Stop polling to prevent OOM
        viewModel.reset()
    }

    // MARK: - Download Credential Tests

    @Test
    fun `downloadCredential does nothing when no recovery in progress`() = runTest {
        viewModel.downloadCredential()
        runCurrent()

        verify(vaultServiceClient, never()).downloadRecoveredCredential(any())
    }

    @Test
    fun `downloadCredential transitions to Complete on success`() = runTest {
        // First set up a ready recovery (use ready status to skip polling)
        val statusResponse = RecoveryStatusResponse(
            recoveryId = "recovery-123",
            status = "ready",
            availableAt = "2026-01-03T12:00:00Z",
            remainingSeconds = 0
        )
        val downloadResponse = RecoveryDownloadResponse(
            credentialBlob = "encrypted-blob",
            version = 1,
            userGuid = "user-guid-123"
        )
        whenever(vaultServiceClient.getRecoveryStatus(any())).thenReturn(Result.success(statusResponse))
        whenever(vaultServiceClient.downloadRecoveredCredential(any())).thenReturn(Result.success(downloadResponse))

        viewModel.resumeRecovery("recovery-123")
        runCurrent()

        viewModel.downloadCredential()
        runCurrent()

        assertEquals(ProteanRecoveryState.Complete, viewModel.state.value)
        verify(proteanCredentialManager).importRecoveredCredential(
            credentialBlob = "encrypted-blob",
            userGuid = "user-guid-123",
            version = 1
        )
    }

    @Test
    fun `downloadCredential transitions to Error on failure`() = runTest {
        // First set up a ready recovery
        val statusResponse = RecoveryStatusResponse(
            recoveryId = "recovery-123",
            status = "ready",
            availableAt = "2026-01-03T12:00:00Z",
            remainingSeconds = 0
        )
        whenever(vaultServiceClient.getRecoveryStatus(any())).thenReturn(Result.success(statusResponse))
        whenever(vaultServiceClient.downloadRecoveredCredential(any())).thenReturn(Result.failure(Exception("Download failed")))

        viewModel.resumeRecovery("recovery-123")
        runCurrent()

        viewModel.downloadCredential()
        runCurrent()

        val state = viewModel.state.value
        assertTrue("Expected Error state but got $state", state is ProteanRecoveryState.Error)
        assertEquals("Download failed", (state as ProteanRecoveryState.Error).message)
        assertTrue(state.canRetry)
    }

    @Test
    fun `downloadCredential uses default error message when exception has no message`() = runTest {
        // First set up a ready recovery
        val statusResponse = RecoveryStatusResponse(
            recoveryId = "recovery-123",
            status = "ready",
            availableAt = "2026-01-03T12:00:00Z",
            remainingSeconds = 0
        )
        whenever(vaultServiceClient.getRecoveryStatus(any())).thenReturn(Result.success(statusResponse))
        whenever(vaultServiceClient.downloadRecoveredCredential(any())).thenReturn(Result.failure(Exception()))

        viewModel.resumeRecovery("recovery-123")
        runCurrent()

        viewModel.downloadCredential()
        runCurrent()

        val state = viewModel.state.value as ProteanRecoveryState.Error
        assertEquals("Failed to download credential", state.message)
    }

    // MARK: - Reset Tests

    @Test
    fun `reset clears all state and returns to EnteringCredentials`() = runTest {
        // Set up some state using ready status to avoid polling
        val statusResponse = RecoveryStatusResponse(
            recoveryId = "recovery-123",
            status = "ready",
            availableAt = "2026-01-03T12:00:00Z",
            remainingSeconds = 0
        )
        whenever(vaultServiceClient.getRecoveryStatus(any())).thenReturn(Result.success(statusResponse))

        viewModel.setEmail("test@example.com")
        viewModel.setBackupPin("123456")
        viewModel.resumeRecovery("recovery-123")
        runCurrent()

        viewModel.reset()

        assertEquals(ProteanRecoveryState.EnteringCredentials, viewModel.state.value)
        assertEquals("", viewModel.email.value)
        assertEquals("", viewModel.backupPin.value)
        assertFalse(viewModel.isValidInput.value)
    }

    @Test
    fun `tryAgain calls reset`() = runTest {
        viewModel.setEmail("test@example.com")
        viewModel.setBackupPin("123456")

        viewModel.tryAgain()

        assertEquals(ProteanRecoveryState.EnteringCredentials, viewModel.state.value)
        assertEquals("", viewModel.email.value)
        assertEquals("", viewModel.backupPin.value)
    }

    // MARK: - Polling Tests

    @Test
    fun `polling updates state when status changes to ready`() = runTest {
        // Set up a pending recovery that will start polling
        val requestResponse = RecoveryRequestResponse(
            recoveryId = "recovery-123",
            requestedAt = "2026-01-02T12:00:00Z",
            availableAt = "2026-01-03T12:00:00Z",
            status = "pending"
        )
        val pendingStatus = RecoveryStatusResponse(
            recoveryId = "recovery-123",
            status = "pending",
            availableAt = "2026-01-03T12:00:00Z",
            remainingSeconds = 3600
        )
        val readyStatus = RecoveryStatusResponse(
            recoveryId = "recovery-123",
            status = "ready",
            availableAt = "2026-01-03T12:00:00Z",
            remainingSeconds = 0
        )

        whenever(vaultServiceClient.requestRecovery(any(), any())).thenReturn(Result.success(requestResponse))
        // First status check returns pending, second returns ready
        whenever(vaultServiceClient.getRecoveryStatus(any()))
            .thenReturn(Result.success(pendingStatus))
            .thenReturn(Result.success(readyStatus))

        viewModel.setEmail("test@example.com")
        viewModel.setBackupPin("123456")
        viewModel.requestRecovery()
        runCurrent()

        // Verify we're in pending state
        assertTrue(viewModel.state.value is ProteanRecoveryState.Pending)

        // Advance time to trigger first poll (60 seconds)
        advanceTimeBy(61_000)
        runCurrent()

        // Should still be pending after first poll
        assertTrue(viewModel.state.value is ProteanRecoveryState.Pending)

        // Advance time to trigger second poll
        advanceTimeBy(61_000)
        runCurrent()

        // Should now be ready
        val state = viewModel.state.value
        assertTrue("Expected Ready state but got $state", state is ProteanRecoveryState.Ready)
    }
}
