package com.vettid.app.features.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.network.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * ViewModel for vault health monitoring with automatic polling.
 *
 * Features:
 * - 30-second health check polling
 * - Provisioning with progress tracking
 * - Detailed component health status
 */
@HiltViewModel
class VaultHealthViewModel @Inject constructor(
    private val vaultService: VaultServiceClient,
    private val natsConnectionManager: NatsConnectionManager
) : ViewModel() {

    private val _healthState = MutableStateFlow<VaultHealthState>(VaultHealthState.Loading)
    val healthState: StateFlow<VaultHealthState> = _healthState.asStateFlow()

    private val _effects = MutableSharedFlow<VaultHealthEffect>()
    val effects: SharedFlow<VaultHealthEffect> = _effects.asSharedFlow()

    private var healthCheckJob: Job? = null
    private var provisioningJob: Job? = null

    // Current action token for authenticated operations
    private var currentActionToken: String? = null

    companion object {
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L // 30 seconds
        private const val PROVISIONING_POLL_INTERVAL_MS = 2_000L // 2 seconds
        private const val PROVISIONING_MAX_ATTEMPTS = 60 // Max 2 minutes
    }

    init {
        // Don't start monitoring automatically - wait for token
    }

    /**
     * Set action token and start health monitoring.
     */
    fun setActionToken(token: String) {
        currentActionToken = token
        startHealthMonitoring()
    }

    /**
     * Start health monitoring with 30-second polling.
     */
    fun startHealthMonitoring() {
        if (currentActionToken == null) {
            _healthState.value = VaultHealthState.Error("Authentication required")
            return
        }

        healthCheckJob?.cancel()
        healthCheckJob = viewModelScope.launch {
            while (isActive) {
                checkHealth()
                delay(HEALTH_CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop health monitoring.
     */
    fun stopHealthMonitoring() {
        healthCheckJob?.cancel()
        healthCheckJob = null
    }

    /**
     * Manually refresh health status.
     */
    fun refresh() {
        viewModelScope.launch {
            checkHealth()
        }
    }

    /**
     * Check vault health from API.
     */
    private suspend fun checkHealth() {
        val token = currentActionToken
        if (token == null) {
            _healthState.value = VaultHealthState.Error("Authentication required")
            return
        }

        try {
            val healthResult = vaultService.getVaultHealth(token)

            healthResult.fold(
                onSuccess = { health ->
                    _healthState.value = mapHealthResponse(health)
                },
                onFailure = { error ->
                    handleHealthError(error)
                }
            )
        } catch (e: Exception) {
            _healthState.value = VaultHealthState.Error(e.message ?: "Health check failed")
        }
    }

    /**
     * Handle health check error.
     */
    private suspend fun handleHealthError(error: Throwable) {
        when {
            error is VaultServiceException && error.code == 404 -> {
                _healthState.value = VaultHealthState.NotProvisioned
            }
            error is VaultServiceException && error.code == 401 -> {
                _healthState.value = VaultHealthState.Error("Authentication expired")
                _effects.emit(VaultHealthEffect.RequireReauth)
            }
            else -> {
                _healthState.value = VaultHealthState.Error(error.message ?: "Health check failed")
            }
        }
    }

    /**
     * Map API response to state.
     */
    private fun mapHealthResponse(health: VaultHealthResponse): VaultHealthState {
        return VaultHealthState.Loaded(
            status = when (health.status) {
                "healthy" -> HealthStatus.Healthy
                "degraded" -> HealthStatus.Degraded
                else -> HealthStatus.Unhealthy
            },
            uptime = health.uptimeSeconds?.let { Duration.ofSeconds(it) },
            localNats = health.localNats?.let {
                NatsComponentHealth(
                    status = it.status == "running",
                    connections = it.connections
                )
            },
            centralNats = health.centralNats?.let {
                CentralNatsComponentHealth(
                    connected = it.status == "connected",
                    latencyMs = it.latencyMs
                )
            },
            vaultManager = health.vaultManager?.let {
                VaultManagerComponentHealth(
                    running = it.status == "running",
                    memoryMb = it.memoryMb,
                    cpuPercent = it.cpuPercent,
                    handlersLoaded = it.handlersLoaded
                )
            },
            lastEventAt = health.lastEventAt?.let {
                try { Instant.parse(it) } catch (e: Exception) { null }
            },
            // Include legacy health data if available
            legacyMemoryPercent = health.memoryUsagePercent,
            legacyCpuPercent = health.cpuUsagePercent,
            legacyNatsConnected = health.natsConnected
        )
    }

    /**
     * Start vault provisioning.
     */
    fun provisionVault() {
        val token = currentActionToken
        if (token == null) {
            viewModelScope.launch {
                _effects.emit(VaultHealthEffect.RequireReauth)
            }
            return
        }

        provisioningJob?.cancel()
        provisioningJob = viewModelScope.launch {
            _healthState.value = VaultHealthState.Provisioning(progress = 0.1f, message = "Starting provisioning...")

            try {
                val result = vaultService.provisionVault(token)

                result.fold(
                    onSuccess = { response ->
                        _healthState.value = VaultHealthState.Provisioning(
                            progress = 0.2f,
                            message = "Instance starting...",
                            instanceId = response.instanceId
                        )
                        _effects.emit(VaultHealthEffect.ShowMessage("Provisioning started"))

                        // Poll for completion
                        pollForProvisioning(response.instanceId ?: response.vaultId)
                    },
                    onFailure = { error ->
                        _healthState.value = VaultHealthState.Error(
                            error.message ?: "Provisioning failed"
                        )
                        _effects.emit(VaultHealthEffect.ShowError("Provisioning failed"))
                    }
                )
            } catch (e: Exception) {
                _healthState.value = VaultHealthState.Error(e.message ?: "Provisioning failed")
            }
        }
    }

    /**
     * Poll for provisioning completion.
     */
    private suspend fun pollForProvisioning(instanceId: String) {
        val token = currentActionToken ?: return

        repeat(PROVISIONING_MAX_ATTEMPTS) { attempt ->
            delay(PROVISIONING_POLL_INTERVAL_MS)

            val progress = 0.2f + (attempt.toFloat() / PROVISIONING_MAX_ATTEMPTS) * 0.7f
            _healthState.value = VaultHealthState.Provisioning(
                progress = progress,
                message = "Setting up vault... (${attempt + 1}/$PROVISIONING_MAX_ATTEMPTS)",
                instanceId = instanceId
            )

            try {
                val health = vaultService.getVaultHealth(token)
                health.fold(
                    onSuccess = { response ->
                        if (response.status == "healthy" || response.status == "degraded") {
                            _healthState.value = mapHealthResponse(response)
                            _effects.emit(VaultHealthEffect.ShowMessage("Vault ready"))
                            return
                        }
                    },
                    onFailure = {
                        // Still provisioning, continue polling
                    }
                )
            } catch (e: Exception) {
                // Still provisioning, continue polling
            }
        }

        // Timeout
        _healthState.value = VaultHealthState.Error("Provisioning timeout - please check vault status")
        _effects.emit(VaultHealthEffect.ShowError("Provisioning timeout"))
    }

    /**
     * Initialize vault after provisioning.
     */
    fun initializeVault() {
        val token = currentActionToken
        if (token == null) {
            viewModelScope.launch {
                _effects.emit(VaultHealthEffect.RequireReauth)
            }
            return
        }

        viewModelScope.launch {
            _healthState.value = VaultHealthState.Provisioning(
                progress = 0.9f,
                message = "Initializing vault..."
            )

            val result = vaultService.initializeVault(token)

            result.fold(
                onSuccess = { response ->
                    if (response.status == "initialized") {
                        _effects.emit(VaultHealthEffect.ShowMessage("Vault initialized"))
                        // Refresh health after initialization
                        checkHealth()
                    } else {
                        _healthState.value = VaultHealthState.Error("Initialization failed: ${response.status}")
                    }
                },
                onFailure = { error ->
                    _healthState.value = VaultHealthState.Error(error.message ?: "Initialization failed")
                }
            )
        }
    }

    /**
     * Terminate vault.
     */
    fun terminateVault() {
        val token = currentActionToken
        if (token == null) {
            viewModelScope.launch {
                _effects.emit(VaultHealthEffect.RequireReauth)
            }
            return
        }

        viewModelScope.launch {
            val result = vaultService.terminateVault(token)

            result.fold(
                onSuccess = { response ->
                    _healthState.value = VaultHealthState.NotProvisioned
                    _effects.emit(VaultHealthEffect.ShowMessage("Vault terminated"))
                },
                onFailure = { error ->
                    _effects.emit(VaultHealthEffect.ShowError(error.message ?: "Termination failed"))
                }
            )
        }
    }

    override fun onCleared() {
        healthCheckJob?.cancel()
        provisioningJob?.cancel()
        super.onCleared()
    }
}

// MARK: - State Types

/**
 * Vault health state.
 */
sealed class VaultHealthState {
    object Loading : VaultHealthState()
    object NotProvisioned : VaultHealthState()

    data class Provisioning(
        val progress: Float,
        val message: String = "",
        val instanceId: String? = null
    ) : VaultHealthState()

    data class Loaded(
        val status: HealthStatus,
        val uptime: Duration?,
        val localNats: NatsComponentHealth?,
        val centralNats: CentralNatsComponentHealth?,
        val vaultManager: VaultManagerComponentHealth?,
        val lastEventAt: Instant?,
        // Legacy fields
        val legacyMemoryPercent: Float? = null,
        val legacyCpuPercent: Float? = null,
        val legacyNatsConnected: Boolean? = null
    ) : VaultHealthState()

    data class Error(val message: String) : VaultHealthState()
}

/**
 * Health status enum.
 */
enum class HealthStatus {
    Healthy,
    Degraded,
    Unhealthy
}

/**
 * Local NATS component health.
 */
data class NatsComponentHealth(
    val status: Boolean,
    val connections: Int
)

/**
 * Central NATS component health.
 */
data class CentralNatsComponentHealth(
    val connected: Boolean,
    val latencyMs: Long
)

/**
 * Vault manager component health.
 */
data class VaultManagerComponentHealth(
    val running: Boolean,
    val memoryMb: Int,
    val cpuPercent: Float,
    val handlersLoaded: Int
)

// MARK: - Effects

/**
 * One-time effects from the ViewModel.
 */
sealed class VaultHealthEffect {
    object RequireReauth : VaultHealthEffect()
    data class ShowMessage(val message: String) : VaultHealthEffect()
    data class ShowError(val message: String) : VaultHealthEffect()
}
