package com.vettid.app.features.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.features.services.models.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for capability management.
 *
 * Issue #41 [AND-032] - Capability Management UI.
 */
@HiltViewModel
class CapabilityManagementViewModel @Inject constructor(
    // TODO: Inject contract service
) : ViewModel() {

    private val _capabilities = MutableStateFlow<List<GrantedCapability>>(emptyList())
    val capabilities: StateFlow<List<GrantedCapability>> = _capabilities.asStateFlow()

    private val _pendingChanges = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val pendingChanges: StateFlow<Map<String, Boolean>> = _pendingChanges.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private var currentContractId: String? = null

    fun loadCapabilities(contractId: String) {
        currentContractId = contractId

        viewModelScope.launch {
            _isLoading.value = true

            try {
                // TODO: Load capabilities from storage/NATS
                _capabilities.value = emptyList()
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun toggleCapability(capabilityId: String, enabled: Boolean) {
        val capability = _capabilities.value.find { it.id == capabilityId } ?: return

        // Can't toggle required capabilities
        if (capability.required) return

        // Track the change
        val changes = _pendingChanges.value.toMutableMap()

        // If returning to original state, remove from pending
        if (capability.enabled == enabled) {
            changes.remove(capabilityId)
        } else {
            changes[capabilityId] = enabled
        }

        _pendingChanges.value = changes
    }

    fun discardChanges() {
        _pendingChanges.value = emptyMap()
    }

    fun saveChanges() {
        val contractId = currentContractId ?: return
        val changes = _pendingChanges.value

        if (changes.isEmpty()) return

        viewModelScope.launch {
            _isSaving.value = true

            try {
                // TODO: Sign and send capability amendment
                // TODO: Update local capabilities

                // Apply changes locally
                _capabilities.update { caps ->
                    caps.map { cap ->
                        if (cap.id in changes) {
                            cap.copy(enabled = changes[cap.id]!!)
                        } else {
                            cap
                        }
                    }
                }

                _pendingChanges.value = emptyMap()
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isSaving.value = false
            }
        }
    }
}
