package com.vettid.app.features.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.features.services.models.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * ViewModel for contract cancellation flow.
 *
 * Issue #40 [AND-031] - Contract Cancellation Flow.
 */
@HiltViewModel
class ContractCancellationViewModel @Inject constructor(
    // TODO: Inject contract service
) : ViewModel() {

    private val _selectedReason = MutableStateFlow<CancellationReason?>(null)
    val selectedReason: StateFlow<CancellationReason?> = _selectedReason.asStateFlow()

    private val _deleteData = MutableStateFlow(false)
    val deleteData: StateFlow<Boolean> = _deleteData.asStateFlow()

    private val _feedback = MutableStateFlow("")
    val feedback: StateFlow<String> = _feedback.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _effects = MutableSharedFlow<CancellationEffect>()
    val effects: SharedFlow<CancellationEffect> = _effects.asSharedFlow()

    fun setReason(reason: CancellationReason) {
        _selectedReason.value = reason
    }

    fun setDeleteData(delete: Boolean) {
        _deleteData.value = delete
    }

    fun setFeedback(text: String) {
        _feedback.value = text
    }

    fun cancel(contractId: String) {
        viewModelScope.launch {
            _isProcessing.value = true

            try {
                val request = CancellationRequest(
                    contractId = contractId,
                    reason = _selectedReason.value,
                    effectiveDate = Instant.now(),
                    deleteData = _deleteData.value,
                    feedback = _feedback.value.ifEmpty { null }
                )

                // TODO: Send cancellation request via NATS
                // TODO: Update local contract status
                // TODO: Disconnect from service NATS

                _effects.emit(CancellationEffect.Cancelled)
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isProcessing.value = false
            }
        }
    }
}

sealed class CancellationEffect {
    object Cancelled : CancellationEffect()
}
