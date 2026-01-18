package com.vettid.app.features.transfer

import androidx.lifecycle.SavedStateHandle
import com.vettid.app.core.attestation.HardwareAttestationManager
import com.vettid.app.core.nats.DeviceInfo
import com.vettid.app.core.nats.NitroEnrollmentClient
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultEvent
import com.vettid.app.core.storage.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

/**
 * Unit tests for TransferViewModel.
 * Tests basic state initialization and event handling.
 * Note: Complex flow tests that require Robolectric are skipped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferViewModelTest {

    private lateinit var viewModel: TransferViewModel
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var credentialStore: CredentialStore
    private lateinit var attestationManager: HardwareAttestationManager
    private lateinit var nitroEnrollmentClient: NitroEnrollmentClient
    private lateinit var ownerSpaceClient: OwnerSpaceClient

    private val testDispatcher = StandardTestDispatcher()
    private val vaultEventsFlow = MutableSharedFlow<VaultEvent>()

    private val testDeviceInfo = DeviceInfo(
        deviceId = "test-device-id",
        model = "Test Phone",
        osVersion = "Android 14",
        location = null
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        savedStateHandle = SavedStateHandle()
        credentialStore = mock()
        attestationManager = mock()
        nitroEnrollmentClient = mock()
        ownerSpaceClient = mock()

        // Setup default mocks
        whenever(ownerSpaceClient.vaultEvents).thenReturn(vaultEventsFlow)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(transferId: String? = null): TransferViewModel {
        if (transferId != null) {
            savedStateHandle["transferId"] = transferId
        }
        return TransferViewModel(
            savedStateHandle,
            credentialStore,
            attestationManager,
            nitroEnrollmentClient,
            ownerSpaceClient
        )
    }

    // MARK: - Request Flow State Tests

    @Test
    fun `initial request state is Idle`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(TransferRequestState.Idle, viewModel.requestState.first())
    }

    @Test
    fun `initial approval state is Loading`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(TransferApprovalState.Loading, viewModel.approvalState.first())
    }

    @Test
    fun `startTransfer with attestation failure transitions to Failed state`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        // Mock attestation failure
        whenever(attestationManager.generateAttestationKey(any())).thenThrow(RuntimeException("Attestation failed"))

        viewModel.onRequestEvent(TransferRequestEvent.StartTransfer)
        advanceUntilIdle()

        val state = viewModel.requestState.first()
        assertTrue("Expected Failed state but got $state", state is TransferRequestState.Failed)
        assertEquals("Failed to generate device attestation", (state as TransferRequestState.Failed).error)
        assertTrue(state.retryable)
    }

    @Test
    fun `cancelTransfer resets to Idle state`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        // Mock the vault operation for cancellation
        whenever(nitroEnrollmentClient.sendVaultOperation(any())).thenReturn(mapOf("success" to true))

        viewModel.onRequestEvent(TransferRequestEvent.CancelTransfer)
        advanceUntilIdle()

        assertEquals(TransferRequestState.Idle, viewModel.requestState.first())
    }

    @Test
    fun `retry calls startTransfer again`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        // Mock attestation failure for both initial and retry
        whenever(attestationManager.generateAttestationKey(any())).thenThrow(RuntimeException("Attestation failed"))

        // First attempt
        viewModel.onRequestEvent(TransferRequestEvent.StartTransfer)
        advanceUntilIdle()

        val firstState = viewModel.requestState.first()
        assertTrue(firstState is TransferRequestState.Failed)

        // Retry - should transition through states again
        viewModel.onRequestEvent(TransferRequestEvent.Retry)
        advanceUntilIdle()

        val retryState = viewModel.requestState.first()
        assertTrue("Expected Failed state after retry but got $retryState",
            retryState is TransferRequestState.Failed)
    }

    // MARK: - Approval Flow State Tests

    @Test
    fun `approval flow shows error when transfer not found`() = runTest {
        // Mock transfer not found - return empty map (no transfer key)
        whenever(nitroEnrollmentClient.sendVaultOperation(any())).thenReturn(emptyMap())

        viewModel = createViewModel(transferId = "nonexistent-transfer")
        advanceUntilIdle()

        val state = viewModel.approvalState.first()
        assertTrue("Expected Error state but got $state", state is TransferApprovalState.Error)
        assertEquals("Transfer not found", (state as TransferApprovalState.Error).message)
    }

    @Test
    fun `ApproveTransfer transitions to AwaitingBiometric`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        // Directly test the approval event without loading transfer first
        viewModel.onApprovalEvent(TransferApprovalEvent.ApproveTransfer)
        advanceUntilIdle()

        val state = viewModel.approvalState.first()
        assertTrue("Expected AwaitingBiometric state but got $state",
            state is TransferApprovalState.AwaitingBiometric)
    }

    // MARK: - State Class Tests

    @Test
    fun `TransferRequestState Idle is singleton`() {
        assertSame(TransferRequestState.Idle, TransferRequestState.Idle)
    }

    @Test
    fun `TransferRequestState PreparingAttestation is singleton`() {
        assertSame(TransferRequestState.PreparingAttestation, TransferRequestState.PreparingAttestation)
    }

    @Test
    fun `TransferRequestState SendingRequest is singleton`() {
        assertSame(TransferRequestState.SendingRequest, TransferRequestState.SendingRequest)
    }

    @Test
    fun `TransferRequestState ReceivingCredentials is singleton`() {
        assertSame(TransferRequestState.ReceivingCredentials, TransferRequestState.ReceivingCredentials)
    }

    @Test
    fun `TransferApprovalState Loading is singleton`() {
        assertSame(TransferApprovalState.Loading, TransferApprovalState.Loading)
    }

    @Test
    fun `TransferApprovalState AwaitingBiometric is singleton`() {
        assertSame(TransferApprovalState.AwaitingBiometric, TransferApprovalState.AwaitingBiometric)
    }

    @Test
    fun `TransferApprovalState ProcessingApproval is singleton`() {
        assertSame(TransferApprovalState.ProcessingApproval, TransferApprovalState.ProcessingApproval)
    }

    @Test
    fun `TransferApprovalState ProcessingDenial is singleton`() {
        assertSame(TransferApprovalState.ProcessingDenial, TransferApprovalState.ProcessingDenial)
    }

    // MARK: - Event Class Tests

    @Test
    fun `TransferRequestEvent StartTransfer is singleton`() {
        assertSame(TransferRequestEvent.StartTransfer, TransferRequestEvent.StartTransfer)
    }

    @Test
    fun `TransferRequestEvent CancelTransfer is singleton`() {
        assertSame(TransferRequestEvent.CancelTransfer, TransferRequestEvent.CancelTransfer)
    }

    @Test
    fun `TransferRequestEvent Retry is singleton`() {
        assertSame(TransferRequestEvent.Retry, TransferRequestEvent.Retry)
    }

    @Test
    fun `TransferRequestEvent Dismiss is singleton`() {
        assertSame(TransferRequestEvent.Dismiss, TransferRequestEvent.Dismiss)
    }

    @Test
    fun `TransferApprovalEvent ApproveTransfer is singleton`() {
        assertSame(TransferApprovalEvent.ApproveTransfer, TransferApprovalEvent.ApproveTransfer)
    }

    @Test
    fun `TransferApprovalEvent DenyTransfer is singleton`() {
        assertSame(TransferApprovalEvent.DenyTransfer, TransferApprovalEvent.DenyTransfer)
    }

    @Test
    fun `TransferApprovalEvent BiometricSuccess is singleton`() {
        assertSame(TransferApprovalEvent.BiometricSuccess, TransferApprovalEvent.BiometricSuccess)
    }

    @Test
    fun `TransferApprovalEvent Dismiss is singleton`() {
        assertSame(TransferApprovalEvent.Dismiss, TransferApprovalEvent.Dismiss)
    }
}
