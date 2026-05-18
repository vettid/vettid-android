package com.vettid.app.features.devices

/**
 * UI models for the desktop device pairing + session lifecycle.
 * Protocol reference: vettid-dev/docs/DESKTOP-CONNECTION-FLOW.md.
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
    val displayName: String get() = deviceName.ifEmpty { hostname ?: "Desktop" }

    val platformLabel: String get() = when {
        platform?.contains("darwin", ignoreCase = true) == true -> "macOS"
        platform?.contains("linux", ignoreCase = true) == true -> "Linux"
        platform?.contains("windows", ignoreCase = true) == true -> "Windows"
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

/** Metadata shown to the user when they authorize a new desktop session. */
data class PendingDeviceInfo(
    val connectionId: String,
    val hostname: String,
    val platform: String,
    val osName: String,
    val osVersion: String,
    val appVersion: String,
    val clientIp: String,
    /** SHA-256 of the desktop binary, full 64-char hex (was truncated to 8 chars). */
    val binaryFingerprint: String,
    /** HMAC-SHA256 over stable machine attributes. May be empty on first
     *  desktop builds — we won't dim the row, we'll just show "—". */
    val machineFingerprint: String,
    val defaultDurationSeconds: Long,
    val maxDurationSeconds: Long,
    /** Existing connection.peer_alias if this is a re-authorize. The
     *  AuthorizeDeviceScreen shows the rename field only when this is
     *  null/blank — the name is set at original pairing and shouldn't
     *  change on every session refresh. */
    val existingAlias: String? = null,
)

/** QR payload shown by the desktop at stage 2 — user scans this in the app. */
data class ScannedDeviceAuthQr(
    val approvalToken: String,
    val connectionId: String
)

// -----------------------------------------------------------------------------
// UI states
// -----------------------------------------------------------------------------

sealed class DeviceManagementState {
    object Loading : DeviceManagementState()
    data class Loaded(val devices: List<ConnectedDevice>) : DeviceManagementState()
    object Empty : DeviceManagementState()
    data class Error(val message: String) : DeviceManagementState()
}

sealed class DevicePairingState {
    object Idle : DevicePairingState()
    object Creating : DevicePairingState()
    data class ShowingCode(val code: String, val remainingSeconds: Int) : DevicePairingState()
    data class DevicePending(val info: PendingDeviceInfo) : DevicePairingState()
    object Timeout : DevicePairingState()
    data class Error(val message: String) : DevicePairingState()
}

sealed class AuthorizeDeviceState {
    object Scanning : AuthorizeDeviceState()
    data class Ready(
        val scanned: ScannedDeviceAuthQr,
        val info: PendingDeviceInfo,
        val deviceName: String,
        val durationSeconds: Long
    ) : AuthorizeDeviceState()
    object Submitting : AuthorizeDeviceState()
    /**
     * Vault reported an existing active session on another device.
     * The screen renders a confirm prompt; user can end the existing
     * session(s) and continue, or cancel back to Ready.
     */
    data class ConfirmReplace(
        val scanned: ScannedDeviceAuthQr,
        val info: PendingDeviceInfo,
        val deviceName: String,
        val durationSeconds: Long,
        val existingDevices: List<ExistingDeviceSummary>
    ) : AuthorizeDeviceState()
    object Done : AuthorizeDeviceState()
    data class Error(val message: String) : AuthorizeDeviceState()
}

/** Existing-session summary surfaced to the user in the ConfirmReplace prompt. */
data class ExistingDeviceSummary(
    val connectionId: String,
    val deviceName: String,
    val lastActiveAt: Long,
    val expiresAt: Long,
)

// ---- Legacy per-operation approval (separate from stage-2 pairing) ---------

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
