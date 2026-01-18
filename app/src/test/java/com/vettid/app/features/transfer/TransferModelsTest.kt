package com.vettid.app.features.transfer

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.vettid.app.core.nats.DeviceInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Unit tests for Transfer data models.
 * Tests serialization, deserialization, and model properties.
 */
class TransferModelsTest {

    private lateinit var gson: Gson

    @Before
    fun setup() {
        gson = GsonBuilder()
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .create()
    }

    // MARK: - TransferStatus Tests

    @Test
    fun `TransferStatus enum has all expected values`() {
        val statuses = TransferStatus.values()
        assertEquals(6, statuses.size)
        assertTrue(statuses.contains(TransferStatus.PENDING))
        assertTrue(statuses.contains(TransferStatus.APPROVED))
        assertTrue(statuses.contains(TransferStatus.DENIED))
        assertTrue(statuses.contains(TransferStatus.COMPLETED))
        assertTrue(statuses.contains(TransferStatus.EXPIRED))
        assertTrue(statuses.contains(TransferStatus.FAILED))
    }

    @Test
    fun `TransferStatus serializes to lowercase`() {
        assertEquals("pending", gson.toJson(TransferStatus.PENDING).trim('"'))
        assertEquals("approved", gson.toJson(TransferStatus.APPROVED).trim('"'))
        assertEquals("denied", gson.toJson(TransferStatus.DENIED).trim('"'))
        assertEquals("completed", gson.toJson(TransferStatus.COMPLETED).trim('"'))
        assertEquals("expired", gson.toJson(TransferStatus.EXPIRED).trim('"'))
        assertEquals("failed", gson.toJson(TransferStatus.FAILED).trim('"'))
    }

    // MARK: - DeviceInfo Tests

    @Test
    fun `DeviceInfo creates with all fields`() {
        val deviceInfo = DeviceInfo(
            deviceId = "device-123",
            model = "Pixel 8 Pro",
            osVersion = "Android 14",
            location = "San Francisco, CA"
        )

        assertEquals("device-123", deviceInfo.deviceId)
        assertEquals("Pixel 8 Pro", deviceInfo.model)
        assertEquals("Android 14", deviceInfo.osVersion)
        assertEquals("San Francisco, CA", deviceInfo.location)
    }

    @Test
    fun `DeviceInfo location can be null`() {
        val deviceInfo = DeviceInfo(
            deviceId = "device-456",
            model = "Galaxy S24",
            osVersion = "Android 14",
            location = null
        )

        assertNull(deviceInfo.location)
    }

    @Test
    fun `DeviceInfo serializes correctly`() {
        val deviceInfo = DeviceInfo(
            deviceId = "test-device",
            model = "Test Phone",
            osVersion = "Android 13",
            location = "New York"
        )

        val json = gson.toJson(deviceInfo)
        assertTrue(json.contains("test-device"))
        assertTrue(json.contains("Test Phone"))
        assertTrue(json.contains("Android 13"))
        assertTrue(json.contains("New York"))
    }

    // MARK: - TransferRequest Tests

    @Test
    fun `TransferRequest creates with all fields`() {
        val now = Instant.now()
        val expiresAt = now.plusSeconds(900)
        val deviceInfo = DeviceInfo(
            deviceId = "new-device",
            model = "New Phone",
            osVersion = "Android 14",
            location = null
        )

        val request = TransferRequest(
            transferId = "transfer-abc",
            sourceDeviceId = "source-device",
            targetDeviceId = "target-device",
            deviceInfo = deviceInfo,
            createdAt = now,
            expiresAt = expiresAt,
            status = TransferStatus.PENDING
        )

        assertEquals("transfer-abc", request.transferId)
        assertEquals("source-device", request.sourceDeviceId)
        assertEquals("target-device", request.targetDeviceId)
        assertEquals(deviceInfo, request.deviceInfo)
        assertEquals(now, request.createdAt)
        assertEquals(expiresAt, request.expiresAt)
        assertEquals(TransferStatus.PENDING, request.status)
    }

    @Test
    fun `TransferRequest sourceDeviceId can be null`() {
        val request = TransferRequest(
            transferId = "transfer-xyz",
            sourceDeviceId = null,
            targetDeviceId = "target",
            deviceInfo = DeviceInfo("d", "m", "o", null),
            createdAt = Instant.now(),
            expiresAt = Instant.now().plusSeconds(900),
            status = TransferStatus.PENDING
        )

        assertNull(request.sourceDeviceId)
    }

    // MARK: - InitiateTransferRequest Tests

    @Test
    fun `InitiateTransferRequest creates correctly`() {
        val deviceInfo = DeviceInfo(
            deviceId = "device",
            model = "Phone",
            osVersion = "14",
            location = null
        )

        val request = InitiateTransferRequest(
            deviceAttestation = "attestation-data",
            deviceInfo = deviceInfo
        )

        assertEquals("attestation-data", request.deviceAttestation)
        assertEquals(deviceInfo, request.deviceInfo)
    }

    // MARK: - InitiateTransferResponse Tests

    @Test
    fun `InitiateTransferResponse success case`() {
        val transfer = TransferRequest(
            transferId = "new-transfer",
            sourceDeviceId = null,
            targetDeviceId = "target",
            deviceInfo = DeviceInfo("d", "m", "o", null),
            createdAt = Instant.now(),
            expiresAt = Instant.now().plusSeconds(900),
            status = TransferStatus.PENDING
        )

        val response = InitiateTransferResponse(
            success = true,
            transfer = transfer,
            error = null
        )

        assertTrue(response.success)
        assertNotNull(response.transfer)
        assertNull(response.error)
    }

    @Test
    fun `InitiateTransferResponse failure case`() {
        val response = InitiateTransferResponse(
            success = false,
            transfer = null,
            error = "No other devices available"
        )

        assertFalse(response.success)
        assertNull(response.transfer)
        assertEquals("No other devices available", response.error)
    }

    // MARK: - TransferDecisionRequest Tests

    @Test
    fun `TransferDecisionRequest approval with attestation`() {
        val request = TransferDecisionRequest(
            transferId = "decision-transfer",
            approved = true,
            deviceAttestation = "attestation-data"
        )

        assertEquals("decision-transfer", request.transferId)
        assertTrue(request.approved)
        assertEquals("attestation-data", request.deviceAttestation)
    }

    @Test
    fun `TransferDecisionRequest denial without attestation`() {
        val request = TransferDecisionRequest(
            transferId = "decision-transfer",
            approved = false,
            deviceAttestation = null
        )

        assertFalse(request.approved)
        assertNull(request.deviceAttestation)
    }

    // MARK: - TransferDecisionResponse Tests

    @Test
    fun `TransferDecisionResponse success`() {
        val response = TransferDecisionResponse(
            success = true,
            status = TransferStatus.APPROVED,
            error = null
        )

        assertTrue(response.success)
        assertEquals(TransferStatus.APPROVED, response.status)
        assertNull(response.error)
    }

    @Test
    fun `TransferDecisionResponse failure`() {
        val response = TransferDecisionResponse(
            success = false,
            status = null,
            error = "Transfer already expired"
        )

        assertFalse(response.success)
        assertNull(response.status)
        assertEquals("Transfer already expired", response.error)
    }

    // MARK: - TransferRequestState Tests

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
    fun `TransferRequestState WaitingForApproval has transfer and countdown`() {
        val transfer = TransferRequest(
            transferId = "waiting-transfer",
            sourceDeviceId = null,
            targetDeviceId = "target",
            deviceInfo = DeviceInfo("d", "m", "o", null),
            createdAt = Instant.now(),
            expiresAt = Instant.now().plusSeconds(900),
            status = TransferStatus.PENDING
        )

        val state = TransferRequestState.WaitingForApproval(
            transfer = transfer,
            remainingSeconds = 850
        )

        assertEquals(transfer, state.transfer)
        assertEquals(850, state.remainingSeconds)
    }

    @Test
    fun `TransferRequestState Failed has error and retryable flag`() {
        val stateRetryable = TransferRequestState.Failed(
            error = "Network error",
            retryable = true
        )

        assertEquals("Network error", stateRetryable.error)
        assertTrue(stateRetryable.retryable)

        val stateNotRetryable = TransferRequestState.Failed(
            error = "Device not found",
            retryable = false
        )

        assertFalse(stateNotRetryable.retryable)
    }

    @Test
    fun `TransferRequestState Completed has transferId`() {
        val state = TransferRequestState.Completed(transferId = "completed-123")
        assertEquals("completed-123", state.transferId)
    }

    @Test
    fun `TransferRequestState Denied has default message`() {
        val state = TransferRequestState.Denied(transferId = "denied-456")
        assertEquals("denied-456", state.transferId)
        assertEquals("Transfer was denied by the other device", state.message)
    }

    @Test
    fun `TransferRequestState Denied can have custom message`() {
        val state = TransferRequestState.Denied(
            transferId = "denied-789",
            message = "User declined the request"
        )
        assertEquals("User declined the request", state.message)
    }

    @Test
    fun `TransferRequestState Expired has default message`() {
        val state = TransferRequestState.Expired(transferId = "expired-123")
        assertEquals("Transfer request expired", state.message)
    }

    // MARK: - TransferApprovalState Tests

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

    @Test
    fun `TransferApprovalState Ready has transfer and countdown`() {
        val transfer = TransferRequest(
            transferId = "ready-transfer",
            sourceDeviceId = null,
            targetDeviceId = "target",
            deviceInfo = DeviceInfo("d", "m", "o", null),
            createdAt = Instant.now(),
            expiresAt = Instant.now().plusSeconds(900),
            status = TransferStatus.PENDING
        )

        val state = TransferApprovalState.Ready(
            transfer = transfer,
            remainingSeconds = 600
        )

        assertEquals(transfer, state.transfer)
        assertEquals(600, state.remainingSeconds)
    }

    @Test
    fun `TransferApprovalState Approved has default message`() {
        val state = TransferApprovalState.Approved(transferId = "approved-123")
        assertEquals("approved-123", state.transferId)
        assertEquals("Transfer approved successfully", state.message)
    }

    @Test
    fun `TransferApprovalState Error has message`() {
        val state = TransferApprovalState.Error(message = "Something went wrong")
        assertEquals("Something went wrong", state.message)
    }

    // MARK: - Event Classes Tests

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
    fun `TransferApprovalEvent LoadTransfer has transferId`() {
        val event = TransferApprovalEvent.LoadTransfer("load-123")
        assertEquals("load-123", event.transferId)
    }

    @Test
    fun `TransferApprovalEvent BiometricFailed has error`() {
        val event = TransferApprovalEvent.BiometricFailed("User cancelled")
        assertEquals("User cancelled", event.error)
    }

    // MARK: - Effect Classes Tests

    @Test
    fun `TransferRequestEffect ShowError has message`() {
        val effect = TransferRequestEffect.ShowError("Error occurred")
        assertEquals("Error occurred", effect.message)
    }

    @Test
    fun `TransferRequestEffect NavigateToMain is singleton`() {
        assertSame(TransferRequestEffect.NavigateToMain, TransferRequestEffect.NavigateToMain)
    }

    @Test
    fun `TransferRequestEffect NavigateBack is singleton`() {
        assertSame(TransferRequestEffect.NavigateBack, TransferRequestEffect.NavigateBack)
    }

    @Test
    fun `TransferApprovalEffect ShowError has message`() {
        val effect = TransferApprovalEffect.ShowError("Approval failed")
        assertEquals("Approval failed", effect.message)
    }

    @Test
    fun `TransferApprovalEffect ShowSuccess has message`() {
        val effect = TransferApprovalEffect.ShowSuccess("Transfer approved")
        assertEquals("Transfer approved", effect.message)
    }

    @Test
    fun `TransferApprovalEffect RequestBiometric is singleton`() {
        assertSame(TransferApprovalEffect.RequestBiometric, TransferApprovalEffect.RequestBiometric)
    }

    @Test
    fun `TransferApprovalEffect NavigateBack is singleton`() {
        assertSame(TransferApprovalEffect.NavigateBack, TransferApprovalEffect.NavigateBack)
    }

    // MARK: - Data Class Equality Tests

    @Test
    fun `DeviceInfo equals works correctly`() {
        val info1 = DeviceInfo("id", "model", "os", "loc")
        val info2 = DeviceInfo("id", "model", "os", "loc")
        val info3 = DeviceInfo("id", "model", "os", null)

        assertEquals(info1, info2)
        assertNotEquals(info1, info3)
    }

    @Test
    fun `TransferRequest equals works correctly`() {
        val now = Instant.now()
        val deviceInfo = DeviceInfo("d", "m", "o", null)

        val request1 = TransferRequest("id", null, null, deviceInfo, now, now, TransferStatus.PENDING)
        val request2 = TransferRequest("id", null, null, deviceInfo, now, now, TransferStatus.PENDING)
        val request3 = TransferRequest("id2", null, null, deviceInfo, now, now, TransferStatus.PENDING)

        assertEquals(request1, request2)
        assertNotEquals(request1, request3)
    }

    @Test
    fun `TransferRequest copy works correctly`() {
        val original = TransferRequest(
            transferId = "original",
            sourceDeviceId = "source",
            targetDeviceId = "target",
            deviceInfo = DeviceInfo("d", "m", "o", null),
            createdAt = Instant.now(),
            expiresAt = Instant.now().plusSeconds(900),
            status = TransferStatus.PENDING
        )

        val copied = original.copy(status = TransferStatus.APPROVED)

        assertEquals("original", copied.transferId)
        assertEquals(TransferStatus.APPROVED, copied.status)
        assertEquals(TransferStatus.PENDING, original.status)
    }
}
