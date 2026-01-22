package com.vettid.app.features.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.vettid.app.core.nats.NatsAutoConnector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the service connection initiation screen.
 *
 * Handles:
 * - QR code scanning and parsing
 * - Deep link data processing
 * - Service offering fetching
 * - Navigation to contract review
 *
 * Issue #22 [AND-001] - Service connection initiation.
 */
@HiltViewModel
class ServiceInitiationViewModel @Inject constructor(
    private val natsAutoConnector: NatsAutoConnector
) : ViewModel() {

    private val gson = Gson()

    private val _state = MutableStateFlow<ServiceInitiationState>(ServiceInitiationState.Scanning)
    val state: StateFlow<ServiceInitiationState> = _state.asStateFlow()

    private val _manualCode = MutableStateFlow("")
    val manualCode: StateFlow<String> = _manualCode.asStateFlow()

    private val _effects = MutableSharedFlow<ServiceInitiationEffect>()
    val effects: SharedFlow<ServiceInitiationEffect> = _effects.asSharedFlow()

    // Cached data for contract flow
    private var currentServiceData: ServiceQrData? = null

    /**
     * Handle QR code scan result.
     */
    fun onQrCodeScanned(data: String) {
        viewModelScope.launch {
            _state.value = ServiceInitiationState.Fetching

            try {
                // Parse QR data
                val qrData = parseQrData(data)
                currentServiceData = qrData

                // Fetch full service details from the service's endpoint
                val preview = fetchServicePreview(qrData)
                _state.value = ServiceInitiationState.Preview(preview)

            } catch (e: Exception) {
                android.util.Log.e("ServiceInitiation", "Failed to process QR data", e)
                _state.value = ServiceInitiationState.Error(
                    e.message ?: "Failed to parse service data"
                )
            }
        }
    }

    /**
     * Parse QR code data.
     */
    private fun parseQrData(data: String): ServiceQrData {
        // Try parsing as JSON first
        return try {
            gson.fromJson(data, ServiceQrData::class.java)
        } catch (e: Exception) {
            // Try parsing as URL
            parseQrUrl(data)
        }
    }

    /**
     * Parse QR data from URL format.
     */
    private fun parseQrUrl(url: String): ServiceQrData {
        // Expected format: https://svc.vettid.com/connect/{service_id}?code={code}
        // Or: vettid://service?id={service_id}&code={code}

        val uri = android.net.Uri.parse(url)

        val serviceId = uri.getQueryParameter("id")
            ?: uri.pathSegments.lastOrNull()
            ?: throw IllegalArgumentException("Missing service ID")

        val code = uri.getQueryParameter("code")
            ?: throw IllegalArgumentException("Missing connection code")

        return ServiceQrData(
            serviceId = serviceId,
            code = code,
            endpoint = uri.getQueryParameter("endpoint")
        )
    }

    /**
     * Fetch full service preview from the service endpoint.
     */
    private suspend fun fetchServicePreview(qrData: ServiceQrData): ServicePreview {
        // TODO: Implement actual NATS call to fetch service offering
        // For now, return a preview based on QR data

        // This would normally be:
        // 1. Connect to service's NATS endpoint
        // 2. Request service.offering with the connection code
        // 3. Parse response into ServicePreview

        // Placeholder data for UI development
        return ServicePreview(
            serviceId = qrData.serviceId,
            serviceName = "Service ${qrData.serviceId.takeLast(8)}",
            organizationName = "VettID Partner",
            category = "Business Services",
            description = "This service would like to connect with your VettID vault to provide secure authentication and data services.",
            logoUrl = null,
            isVerified = true,
            capabilities = listOf(
                "Request authentication",
                "Store encrypted data",
                "Send secure messages"
            ),
            dataAccess = listOf(
                "Display name",
                "Email address (optional)"
            ),
            contractUrl = "https://api.vettid.com/services/${qrData.serviceId}/contract"
        )
    }

    /**
     * Update manual code input.
     */
    fun updateManualCode(code: String) {
        _manualCode.value = code
    }

    /**
     * Submit manual code.
     */
    fun submitManualCode() {
        val code = _manualCode.value.trim()
        if (code.isNotBlank()) {
            onQrCodeScanned(code)
        }
    }

    /**
     * Switch to manual entry mode.
     */
    fun switchToManualEntry() {
        _state.value = ServiceInitiationState.ManualEntry
    }

    /**
     * Switch back to scanning mode.
     */
    fun switchToScanning() {
        _state.value = ServiceInitiationState.Scanning
    }

    /**
     * Confirm connection and navigate to contract review.
     */
    fun confirmConnection() {
        viewModelScope.launch {
            val preview = (state.value as? ServiceInitiationState.Preview)?.preview
                ?: return@launch

            // Generate a temporary contract ID for navigation
            val contractId = "pending_${preview.serviceId}_${System.currentTimeMillis()}"

            // Store the pending contract data for the review screen
            // This would normally be done via a shared repository or saved state

            _effects.emit(ServiceInitiationEffect.NavigateToContractReview(contractId))
        }
    }

    /**
     * Reset to initial state.
     */
    fun reset() {
        _state.value = ServiceInitiationState.Scanning
        _manualCode.value = ""
        currentServiceData = null
    }
}

// MARK: - State Types

/**
 * State for the service initiation screen.
 */
sealed class ServiceInitiationState {
    object Scanning : ServiceInitiationState()
    object ManualEntry : ServiceInitiationState()
    object Fetching : ServiceInitiationState()
    data class Preview(val preview: ServicePreview) : ServiceInitiationState()
    data class Error(val message: String) : ServiceInitiationState()
}

/**
 * One-time effects for the service initiation screen.
 */
sealed class ServiceInitiationEffect {
    data class NavigateToContractReview(val contractId: String) : ServiceInitiationEffect()
    data class ShowError(val message: String) : ServiceInitiationEffect()
}

// MARK: - Data Classes

/**
 * QR code data structure for service connection.
 */
data class ServiceQrData(
    @SerializedName("service_id")
    val serviceId: String,
    val code: String,
    val endpoint: String? = null,
    @SerializedName("offering_version")
    val offeringVersion: Int? = null
)

/**
 * Service preview data for display.
 */
data class ServicePreview(
    val serviceId: String,
    val serviceName: String,
    val organizationName: String,
    val category: String,
    val description: String,
    val logoUrl: String?,
    val isVerified: Boolean,
    val capabilities: List<String>,
    val dataAccess: List<String>,
    val contractUrl: String
)
