package com.vettid.app.features.vault

/**
 * Vault status states following vault-services-api.yaml
 *
 * Status flow:
 * NotEnrolled → Enrolled → Provisioning → Running ↔ Stopped
 *                                              ↓
 *                                         Terminated
 */
sealed class VaultStatusState {

    /**
     * Initial/loading state
     */
    object Loading : VaultStatusState()

    /**
     * User has not enrolled a vault yet
     */
    object NotEnrolled : VaultStatusState()

    /**
     * Vault is enrolled but not yet provisioned
     */
    data class Enrolled(
        val vaultId: String,
        val enrolledAt: String
    ) : VaultStatusState()

    /**
     * Vault is being provisioned (EC2 instance starting)
     */
    data class Provisioning(
        val vaultId: String,
        val estimatedReadyTime: String? = null,
        val progress: Float = 0f
    ) : VaultStatusState()

    /**
     * Vault is running and active
     */
    data class Running(
        val vaultId: String,
        val instanceId: String?,
        val region: String?,
        val health: VaultHealth,
        val lastBackup: String?,
        val lastSync: String?
    ) : VaultStatusState()

    /**
     * Vault is stopped (EC2 instance stopped)
     */
    data class Stopped(
        val vaultId: String,
        val instanceId: String?,
        val lastBackup: String?
    ) : VaultStatusState()

    /**
     * Vault has been terminated
     */
    data class Terminated(
        val vaultId: String,
        val terminatedAt: String?
    ) : VaultStatusState()

    /**
     * Error state
     */
    data class Error(
        val message: String,
        val code: VaultErrorCode = VaultErrorCode.UNKNOWN,
        val retryable: Boolean = true
    ) : VaultStatusState()
}

/**
 * Vault health status from /vault/health endpoint
 */
data class VaultHealth(
    val status: HealthLevel,
    val memoryUsagePercent: Float?,
    val diskUsagePercent: Float?,
    val cpuUsagePercent: Float?,
    val natsConnected: Boolean?,
    val lastChecked: String?
)

/**
 * Health status levels
 */
enum class HealthLevel {
    HEALTHY,
    DEGRADED,
    UNHEALTHY,
    UNKNOWN
}

/**
 * Vault error codes
 */
enum class VaultErrorCode {
    NETWORK_ERROR,
    NOT_AUTHENTICATED,
    NOT_FOUND,
    PROVISION_FAILED,
    HEALTH_CHECK_FAILED,
    SYNC_FAILED,
    UNKNOWN
}

/**
 * Events for vault status screen
 */
sealed class VaultStatusEvent {
    /**
     * Load/refresh vault status
     */
    object Refresh : VaultStatusEvent()

    /**
     * Start enrollment flow
     */
    object StartEnrollment : VaultStatusEvent()

    /**
     * Provision the vault (after enrollment)
     */
    object ProvisionVault : VaultStatusEvent()

    /**
     * Start a stopped vault
     */
    object StartVault : VaultStatusEvent()

    /**
     * Stop a running vault
     */
    object StopVault : VaultStatusEvent()

    /**
     * Trigger manual sync
     */
    object SyncVault : VaultStatusEvent()

    /**
     * Trigger manual backup
     */
    object TriggerBackup : VaultStatusEvent()

    /**
     * Retry after error
     */
    object Retry : VaultStatusEvent()

    /**
     * View vault settings
     */
    object ViewSettings : VaultStatusEvent()
}

/**
 * Side effects from vault status operations
 */
sealed class VaultStatusEffect {
    /**
     * Navigate to enrollment flow
     */
    object NavigateToEnrollment : VaultStatusEffect()

    /**
     * Navigate to authentication for protected action
     */
    data class RequireAuth(val action: String) : VaultStatusEffect()

    /**
     * Show success message
     */
    data class ShowSuccess(val message: String) : VaultStatusEffect()

    /**
     * Show error message
     */
    data class ShowError(val message: String) : VaultStatusEffect()

    /**
     * Navigate to vault settings
     */
    object NavigateToSettings : VaultStatusEffect()

    /**
     * Vault status updated (for home screen refresh)
     */
    data class StatusUpdated(val status: VaultStatusState) : VaultStatusEffect()
}

/**
 * Sync status for background operations
 */
sealed class SyncStatus {
    object Idle : SyncStatus()
    object Syncing : SyncStatus()
    data class Success(val timestamp: String) : SyncStatus()
    data class Failed(val error: String) : SyncStatus()
}
