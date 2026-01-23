package com.vettid.app.features.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.features.services.models.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the service detail screen.
 *
 * Handles:
 * - Loading service details
 * - Offering selection
 * - Initiating connection flow
 *
 * Issue #34 [AND-011] - Service detail view.
 */
@HiltViewModel
class ServiceDetailViewModel @Inject constructor(
    // TODO: Inject directory service API client
) : ViewModel() {

    private val _state = MutableStateFlow<ServiceDetailState>(ServiceDetailState.Loading)
    val state: StateFlow<ServiceDetailState> = _state.asStateFlow()

    private val _selectedOffering = MutableStateFlow<ServiceOffering?>(null)
    val selectedOffering: StateFlow<ServiceOffering?> = _selectedOffering.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _effects = MutableSharedFlow<ServiceDetailEffect>()
    val effects: SharedFlow<ServiceDetailEffect> = _effects.asSharedFlow()

    private var currentServiceId: String? = null

    /**
     * Load service details.
     */
    fun loadService(serviceId: String) {
        if (currentServiceId == serviceId && _state.value is ServiceDetailState.Loaded) {
            return // Already loaded
        }

        currentServiceId = serviceId

        viewModelScope.launch {
            _state.value = ServiceDetailState.Loading

            try {
                // TODO: Implement actual API call to fetch service details
                // For now, return error state until backend is ready
                _state.value = ServiceDetailState.Error("Service not found")
            } catch (e: Exception) {
                _state.value = ServiceDetailState.Error(e.message ?: "Failed to load service")
            }
        }
    }

    /**
     * Select an offering/plan.
     */
    fun selectOffering(offering: ServiceOffering) {
        _selectedOffering.value = offering
    }

    /**
     * Initiate connection with selected offering.
     */
    fun connect() {
        val serviceId = currentServiceId ?: return
        val offering = _selectedOffering.value ?: return

        viewModelScope.launch {
            _isConnecting.value = true

            try {
                // TODO: Validate offering and prepare connection request
                _effects.emit(ServiceDetailEffect.NavigateToContractReview(serviceId, offering.id))
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isConnecting.value = false
            }
        }
    }
}

/**
 * State for the service detail screen.
 */
sealed class ServiceDetailState {
    object Loading : ServiceDetailState()

    data class Loaded(
        val service: DirectoryService
    ) : ServiceDetailState()

    data class Error(val message: String) : ServiceDetailState()
}

/**
 * One-time effects from the service detail screen.
 */
sealed class ServiceDetailEffect {
    data class NavigateToContractReview(
        val serviceId: String,
        val offeringId: String
    ) : ServiceDetailEffect()
}
