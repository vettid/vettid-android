package com.vettid.app.features.devices

/**
 * Data models for desktop device connection management.
 */

data class ConnectedDevice(
    val connectionId: String,
    val deviceName: String,
    val hostname: String?,
    val platform: String?,
    val status: String,
    val sessionId: String?,
    val sessionStatus: String?,
    val sessionExpires: Long?,
    val connectedAt: String,
    val lastActiveAt: String?
) {
    val displayName: String get() = hostname?.takeIf { it.isNotEmpty() } ?: deviceName

    val platformLabel: String get() = when {
        platform?.contains("darwin") == true -> "macOS"
        platform?.contains("linux") == true -> "Linux"
        platform?.contains("windows") == true -> "Windows"
        else -> platform ?: "Unknown"
    }

    val isSessionActive: Boolean get() =
        sessionStatus == "active" && (sessionExpires?.let { it > System.currentTimeMillis() / 1000 } ?: false)

    val sessionTimeRemainingSeconds: Long? get() {
        val expires = sessionExpires ?: return null
        val remaining = expires - System.currentTimeMillis() / 1000
        return if (remaining > 0) remaining else 0
    }
}

data class DeviceListResponse(
    val devices: List<ConnectedDevice>,
    val count: Int
)

data class DeviceApprovalRequest(
    val requestId: String,
    val connectionId: String,
    val deviceName: String,
    val hostname: String?,
    val operation: String,
    val secretName: String?,
    val category: String?,
    val requestedAt: String
)

// Management state
sealed class DeviceManagementState {
    object Loading : DeviceManagementState()
    data class Loaded(val devices: List<ConnectedDevice>) : DeviceManagementState()
    object Empty : DeviceManagementState()
    data class Error(val message: String) : DeviceManagementState()
}

// Pairing state
sealed class DevicePairingState {
    object Idle : DevicePairingState()
    object Creating : DevicePairingState()
    data class ShowingCode(val code: String, val remainingSeconds: Int) : DevicePairingState()
    object WaitingApproval : DevicePairingState()
    data class Approved(val deviceName: String) : DevicePairingState()
    data class Denied(val message: String = "Pairing was denied") : DevicePairingState()
    object Timeout : DevicePairingState()
    data class Error(val message: String) : DevicePairingState()
}

// Approval state
sealed class DeviceApprovalState {
    object Idle : DeviceApprovalState()
    data class Ready(val request: DeviceApprovalRequest, val elapsedSeconds: Int) : DeviceApprovalState()
    object ProcessingApproval : DeviceApprovalState()
    object ProcessingDenial : DeviceApprovalState()
    data class Approved(val message: String = "Request approved") : DeviceApprovalState()
    data class Denied(val message: String = "Request denied") : DeviceApprovalState()
    object Timeout : DeviceApprovalState()
    data class Error(val message: String) : DeviceApprovalState()
}
