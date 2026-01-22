package com.vettid.app.features.services

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.vettid.app.core.crypto.ConnectionKeyPair
import com.vettid.app.core.crypto.ContractSigner
import com.vettid.app.core.crypto.UnsignedContract
import com.vettid.app.core.crypto.ContractSignResponse
import com.vettid.app.core.crypto.SignedContractData
import com.vettid.app.core.crypto.ContractCapabilitySpec
import com.vettid.app.core.crypto.NatsCredentialsResponse
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import com.vettid.app.core.storage.ContractStore
import com.vettid.app.core.storage.StoredContract
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * ViewModel for the contract signing flow.
 *
 * Handles:
 * - Connection keypair generation
 * - Sending sign request to vault via NATS
 * - Processing signed contract response
 * - Storing contract locally
 *
 * Issue #24 [AND-003] - Contract signing flow.
 */
@HiltViewModel
class ContractSigningViewModel @Inject constructor(
    private val contractSigner: ContractSigner,
    @Suppress("unused") private val contractStore: ContractStore,
    private val natsAutoConnector: NatsAutoConnector,
    private val ownerSpaceClient: OwnerSpaceClient,
    @Suppress("unused") savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow<ContractSigningState>(ContractSigningState.Idle)
    val state: StateFlow<ContractSigningState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ContractSigningEffect>()
    val effects: SharedFlow<ContractSigningEffect> = _effects.asSharedFlow()

    // Cached data for signing flow
    private var currentContract: UnsignedContract? = null
    private var connectionKeyPair: ConnectionKeyPair? = null

    companion object {
        private const val TAG = "ContractSigningVM"
        private const val MESSAGE_TYPE_CONTRACT_SIGN = "service.contract.sign"
        private const val SIGN_TIMEOUT_MS = 30000L
    }

    /**
     * Start the contract signing flow.
     *
     * @param contract The unsigned contract to sign
     */
    fun signContract(contract: UnsignedContract) {
        if (_state.value is ContractSigningState.Signing) {
            return // Already signing
        }

        currentContract = contract

        viewModelScope.launch {
            _state.value = ContractSigningState.Signing(
                step = SigningStep.GENERATING_KEYS,
                serviceName = contract.serviceName
            )

            try {
                // Step 1: Generate connection keypair
                val keyPair = contractSigner.generateConnectionKeyPair()
                connectionKeyPair = keyPair

                _state.value = ContractSigningState.Signing(
                    step = SigningStep.SENDING_REQUEST,
                    serviceName = contract.serviceName
                )

                // Step 2: Build sign request payload
                val signRequest = contractSigner.buildSignRequest(contract, keyPair)
                val payload = JsonObject().apply {
                    addProperty("event_id", signRequest.eventId)
                    addProperty("event_type", signRequest.eventType)
                    addProperty("timestamp", signRequest.timestamp)
                    add("contract", com.google.gson.Gson().toJsonTree(signRequest.contract))
                }

                // Check NATS connection
                if (!natsAutoConnector.isConnected()) {
                    throw ContractSigningException("Not connected to vault")
                }

                // Send request to vault
                val requestIdResult = ownerSpaceClient.sendToVault(MESSAGE_TYPE_CONTRACT_SIGN, payload)
                val requestId = requestIdResult.getOrElse { error ->
                    throw ContractSigningException("Failed to send request: ${error.message}")
                }

                android.util.Log.d(TAG, "Sign request sent with ID: $requestId")

                // Wait for response with timeout
                val response = withTimeout(SIGN_TIMEOUT_MS) {
                    ownerSpaceClient.vaultResponses.first { it.requestId == requestId }
                }

                _state.value = ContractSigningState.Signing(
                    step = SigningStep.PROCESSING_RESPONSE,
                    serviceName = contract.serviceName
                )

                // Step 3: Process response
                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (!response.success) {
                            throw ContractSigningException(response.error ?: "Signing failed")
                        }

                        val signResponse = parseSignResponse(response.result, requestId)

                        // Step 4: Store signed contract
                        val storedContract = contractSigner.processSignedContract(signResponse, keyPair)

                        _state.value = ContractSigningState.Success(storedContract)
                        _effects.emit(ContractSigningEffect.SigningComplete(storedContract.contractId))
                    }
                    is VaultResponse.Error -> {
                        throw ContractSigningException("${response.code}: ${response.message}")
                    }
                    else -> {
                        throw ContractSigningException("Unexpected response type")
                    }
                }

            } catch (e: TimeoutCancellationException) {
                android.util.Log.e(TAG, "Contract signing timed out", e)
                _state.value = ContractSigningState.Error(
                    message = "Request timed out. Please try again.",
                    canRetry = true
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Contract signing failed", e)
                _state.value = ContractSigningState.Error(
                    message = e.message ?: "Contract signing failed",
                    canRetry = true
                )
            }
        }
    }

    /**
     * Parse the sign response from vault result JSON.
     */
    private fun parseSignResponse(result: JsonObject?, eventId: String): ContractSignResponse {
        if (result == null) {
            throw ContractSigningException("Empty response from vault")
        }

        // Parse contract data
        val contractJson = result.getAsJsonObject("contract")
            ?: throw ContractSigningException("Missing contract in response")

        val signedContract = SignedContractData(
            contractId = contractJson.get("contract_id")?.asString ?: "",
            serviceId = contractJson.get("service_id")?.asString ?: "",
            serviceName = contractJson.get("service_name")?.asString ?: "",
            serviceLogoUrl = contractJson.get("service_logo_url")?.asString,
            version = contractJson.get("version")?.asInt ?: 1,
            title = contractJson.get("title")?.asString ?: "",
            description = contractJson.get("description")?.asString ?: "",
            termsUrl = contractJson.get("terms_url")?.asString,
            privacyUrl = contractJson.get("privacy_url")?.asString,
            capabilities = contractJson.getAsJsonArray("capabilities")?.map { cap ->
                val capObj = cap.asJsonObject
                ContractCapabilitySpec(
                    type = capObj.get("type")?.asString ?: "",
                    description = capObj.get("description")?.asString ?: "",
                    parameters = emptyMap()
                )
            } ?: emptyList(),
            requiredFields = contractJson.getAsJsonArray("required_fields")?.map { it.asString } ?: emptyList(),
            optionalFields = contractJson.getAsJsonArray("optional_fields")?.map { it.asString } ?: emptyList(),
            servicePublicKey = contractJson.get("service_public_key")?.asString ?: "",
            userConnectionKey = contractJson.get("user_connection_key")?.asString ?: "",
            signedAt = contractJson.get("signed_at")?.asLong ?: System.currentTimeMillis(),
            expiresAt = contractJson.get("expires_at")?.asLong
        )

        // Parse signature chain
        val signatureChain = result.getAsJsonArray("signature_chain")?.map { it.asString } ?: emptyList()

        // Parse NATS credentials if present
        val natsCredsJson = result.getAsJsonObject("nats_credentials")
        val natsCredentials = if (natsCredsJson != null) {
            NatsCredentialsResponse(
                endpoint = natsCredsJson.get("endpoint")?.asString ?: "",
                userJwt = natsCredsJson.get("user_jwt")?.asString ?: "",
                userSeed = natsCredsJson.get("user_seed")?.asString ?: "",
                subject = natsCredsJson.get("subject")?.asString ?: "",
                expiresAt = natsCredsJson.get("expires_at")?.asLong
            )
        } else null

        return ContractSignResponse(
            eventId = eventId,
            success = true,
            error = null,
            contract = signedContract,
            signatureChain = signatureChain,
            natsCredentials = natsCredentials
        )
    }

    /**
     * Retry signing after an error.
     */
    fun retry() {
        currentContract?.let { signContract(it) }
    }

    /**
     * Cancel the signing flow.
     */
    fun cancel() {
        _state.value = ContractSigningState.Idle
        currentContract = null
        connectionKeyPair = null

        viewModelScope.launch {
            _effects.emit(ContractSigningEffect.Cancelled)
        }
    }

    /**
     * Reset to idle state.
     */
    fun reset() {
        _state.value = ContractSigningState.Idle
        currentContract = null
        connectionKeyPair = null
    }
}

// MARK: - State Types

/**
 * State for contract signing flow.
 */
sealed class ContractSigningState {
    object Idle : ContractSigningState()

    data class Signing(
        val step: SigningStep,
        val serviceName: String
    ) : ContractSigningState()

    data class Success(
        val contract: StoredContract
    ) : ContractSigningState()

    data class Error(
        val message: String,
        val canRetry: Boolean
    ) : ContractSigningState()
}

/**
 * Steps in the signing process.
 */
enum class SigningStep(val displayText: String) {
    GENERATING_KEYS("Generating secure keys..."),
    SENDING_REQUEST("Sending to vault..."),
    PROCESSING_RESPONSE("Processing response...")
}

/**
 * One-time effects from contract signing.
 */
sealed class ContractSigningEffect {
    data class SigningComplete(val contractId: String) : ContractSigningEffect()
    object Cancelled : ContractSigningEffect()
}

/**
 * Exception during contract signing.
 */
class ContractSigningException(message: String) : Exception(message)
