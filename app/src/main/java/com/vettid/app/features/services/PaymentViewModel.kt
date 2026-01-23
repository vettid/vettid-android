package com.vettid.app.features.services

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.features.services.models.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for payment request handling.
 *
 * Manages payment flow including method selection and biometric confirmation.
 *
 * Issue #37 [AND-024] - Payment request prompt.
 */
@HiltViewModel
class PaymentViewModel @Inject constructor(
    @ApplicationContext private val context: Context
    // TODO: Inject payment service, biometric manager
) : ViewModel() {

    private val _request = MutableStateFlow<PaymentRequest?>(null)
    val request: StateFlow<PaymentRequest?> = _request.asStateFlow()

    private val _paymentMethods = MutableStateFlow<List<PaymentMethod>>(emptyList())
    val paymentMethods: StateFlow<List<PaymentMethod>> = _paymentMethods.asStateFlow()

    private val _selectedMethod = MutableStateFlow<PaymentMethod?>(null)
    val selectedMethod: StateFlow<PaymentMethod?> = _selectedMethod.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _requiresBiometric = MutableStateFlow(true)
    val requiresBiometric: StateFlow<Boolean> = _requiresBiometric.asStateFlow()

    private val _effectChannel = Channel<PaymentEffect>(Channel.BUFFERED)
    val effects: Flow<PaymentEffect> = _effectChannel.receiveAsFlow()

    private var currentRequestId: String? = null

    fun loadRequest(requestId: String) {
        if (currentRequestId == requestId) return
        currentRequestId = requestId

        viewModelScope.launch {
            try {
                // TODO: Load payment request from service
                // For now, using placeholder data
                _request.value = null

                // TODO: Load saved payment methods
                loadPaymentMethods()
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    private fun loadPaymentMethods() {
        viewModelScope.launch {
            try {
                // TODO: Load from secure storage
                _paymentMethods.value = emptyList()

                // Auto-select default method
                _paymentMethods.value.find { it.isDefault }?.let {
                    _selectedMethod.value = it
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun selectMethod(method: PaymentMethod) {
        _selectedMethod.value = method

        // Crypto payments may have different requirements
        _requiresBiometric.value = when (method.type) {
            PaymentMethodType.CRYPTO -> true // Always require biometric for crypto
            else -> _request.value?.amount?.amount?.let { it > 10000 } ?: true // > $100
        }
    }

    fun approve() {
        val request = _request.value ?: return
        val method = _selectedMethod.value ?: return

        viewModelScope.launch {
            _isProcessing.value = true

            try {
                // Step 1: Biometric verification if required
                if (_requiresBiometric.value) {
                    val biometricResult = performBiometricAuth()
                    if (!biometricResult) {
                        _isProcessing.value = false
                        return@launch
                    }
                }

                // Step 2: Process payment
                val result = processPayment(request, method)

                // Step 3: Send result
                _effectChannel.send(PaymentEffect.PaymentCompleted(result))

            } catch (e: Exception) {
                val result = PaymentResult(
                    requestId = request.requestId,
                    status = PaymentResultStatus.FAILED,
                    errorMessage = e.message
                )
                _effectChannel.send(PaymentEffect.PaymentCompleted(result))
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun deny() {
        viewModelScope.launch {
            _effectChannel.send(PaymentEffect.PaymentCancelled)
        }
    }

    private suspend fun performBiometricAuth(): Boolean {
        // TODO: Implement biometric authentication using BiometricAuthManager
        // This would show the biometric prompt and return true/false
        return true
    }

    private suspend fun processPayment(
        request: PaymentRequest,
        method: PaymentMethod
    ): PaymentResult {
        // TODO: Implement actual payment processing
        // This would:
        // 1. Create payment transaction
        // 2. Sign with user's key
        // 3. Send to service via NATS
        // 4. Wait for confirmation

        return PaymentResult(
            requestId = request.requestId,
            status = PaymentResultStatus.SUCCESS,
            transactionId = java.util.UUID.randomUUID().toString(),
            confirmedAt = java.time.Instant.now()
        )
    }
}

/**
 * Side effects from payment processing.
 */
sealed class PaymentEffect {
    data class PaymentCompleted(val result: PaymentResult) : PaymentEffect()
    data object PaymentCancelled : PaymentEffect()
}
