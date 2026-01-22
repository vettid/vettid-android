package com.vettid.app.core.crypto

import android.util.Base64
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.X25519
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.vettid.app.core.storage.ContractStore
import com.vettid.app.core.storage.StoredContract
import com.vettid.app.core.storage.ContractStatus
import com.vettid.app.core.storage.ContractCapability
import com.vettid.app.core.storage.CapabilityType
import com.vettid.app.core.storage.ServiceNatsCredentials
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles cryptographic operations for contract signing.
 *
 * Contract signing flow:
 * 1. Generate X25519 keypair for connection encryption
 * 2. Build contract sign request with user's connection public key
 * 3. Send to vault via NATS for signing
 * 4. Receive signed contract and store locally
 *
 * Issue #32 [AND-043] - Contract signing crypto implementation.
 */
@Singleton
class ContractSigner @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val contractStore: ContractStore
) {
    private val gson = Gson()

    companion object {
        private const val EVENT_TYPE_CONTRACT_SIGN = "service.contract.sign"
        private const val EVENT_TYPE_CONTRACT_CANCEL = "service.contract.cancel"
    }

    // MARK: - Key Generation

    /**
     * Generate X25519 keypair for a new service connection.
     *
     * The public key is sent to the service during contract signing.
     * The private key is stored in Android Keystore for message encryption.
     *
     * @return ConnectionKeyPair with both keys (from ConnectionCryptoManager)
     */
    fun generateConnectionKeyPair(): ConnectionKeyPair {
        val (privateKey, publicKey) = cryptoManager.generateX25519KeyPair()
        return ConnectionKeyPair(
            privateKey = privateKey,
            publicKey = publicKey
        )
    }

    /**
     * Generate Ed25519 signing keypair for a connection.
     *
     * Used for signing messages to prove they came from this device.
     *
     * @return ContractSigningKeyPair with both keys
     */
    fun generateSigningKeyPair(): ContractSigningKeyPair {
        val keyPair = Ed25519Sign.KeyPair.newKeyPair()
        return ContractSigningKeyPair(
            privateKey = keyPair.privateKey,
            publicKey = keyPair.publicKey,
            publicKeyBase64 = Base64.encodeToString(keyPair.publicKey, Base64.NO_WRAP)
        )
    }

    // MARK: - Contract Sign Request Building

    /**
     * Build a contract sign request for sending to the vault.
     *
     * @param contract The unsigned contract to sign
     * @param connectionKeyPair X25519 keypair for the connection
     * @return Contract sign request ready for NATS publish
     */
    fun buildSignRequest(
        contract: UnsignedContract,
        connectionKeyPair: ConnectionKeyPair
    ): ContractSignRequest {
        return ContractSignRequest(
            eventId = UUID.randomUUID().toString(),
            eventType = EVENT_TYPE_CONTRACT_SIGN,
            timestamp = Instant.now().toEpochMilli(),
            contract = contract.copy(
                userConnectionKey = connectionKeyPair.publicKeyBase64()
            )
        )
    }

    /**
     * Build a contract cancellation request.
     *
     * @param contractId The contract ID to cancel
     * @param reason Reason for cancellation
     * @return Contract cancel request ready for NATS publish
     */
    fun buildCancelRequest(
        contractId: String,
        reason: String
    ): ContractCancelRequest {
        return ContractCancelRequest(
            eventId = UUID.randomUUID().toString(),
            eventType = EVENT_TYPE_CONTRACT_CANCEL,
            timestamp = Instant.now().toEpochMilli(),
            contractId = contractId,
            reason = reason
        )
    }

    // MARK: - Contract Response Processing

    /**
     * Process a signed contract response from the vault.
     *
     * Validates the response and stores:
     * - Contract metadata in EncryptedSharedPreferences
     * - Connection private key in Android Keystore
     * - NATS credentials for the service
     *
     * @param response The signed contract response from vault
     * @param connectionKeyPair The keypair used in the sign request
     * @return StoredContract if successful
     */
    fun processSignedContract(
        response: ContractSignResponse,
        connectionKeyPair: ConnectionKeyPair
    ): StoredContract {
        // Validate signature chain
        if (!validateSignatureChain(response)) {
            throw ContractSigningException("Invalid signature chain")
        }

        // Store connection private key
        contractStore.storeConnectionKey(
            response.contract.contractId,
            connectionKeyPair.privateKey
        )

        // Store NATS credentials if provided
        response.natsCredentials?.let { creds ->
            contractStore.storeNatsCredentials(
                response.contract.contractId,
                ServiceNatsCredentials(
                    endpoint = creds.endpoint,
                    userJwt = creds.userJwt,
                    userSeed = creds.userSeed,
                    subject = creds.subject,
                    expiresAt = creds.expiresAt?.let { Instant.ofEpochMilli(it) }
                )
            )
        }

        // Build stored contract
        val storedContract = StoredContract(
            contractId = response.contract.contractId,
            serviceId = response.contract.serviceId,
            serviceName = response.contract.serviceName,
            serviceLogoUrl = response.contract.serviceLogoUrl,
            version = response.contract.version,
            title = response.contract.title,
            description = response.contract.description,
            termsUrl = response.contract.termsUrl,
            privacyUrl = response.contract.privacyUrl,
            capabilities = response.contract.capabilities.map { cap ->
                ContractCapability(
                    type = CapabilityType.valueOf(cap.type),
                    description = cap.description,
                    parameters = cap.parameters
                )
            },
            requiredFields = response.contract.requiredFields,
            optionalFields = response.contract.optionalFields,
            status = ContractStatus.ACTIVE,
            signedAt = Instant.ofEpochMilli(response.contract.signedAt),
            expiresAt = response.contract.expiresAt?.let { Instant.ofEpochMilli(it) },
            natsEndpoint = response.natsCredentials?.endpoint ?: "",
            natsSubject = response.natsCredentials?.subject ?: "",
            userConnectionPublicKey = connectionKeyPair.publicKeyBase64(),
            servicePublicKey = response.contract.servicePublicKey,
            signatureChain = response.signatureChain
        )

        // Save to storage
        contractStore.save(storedContract)

        android.util.Log.i("ContractSigner", "Contract signed and stored: ${storedContract.contractId}")

        return storedContract
    }

    /**
     * Validate the signature chain from the vault.
     *
     * The signature chain proves the contract was signed by:
     * 1. The user (via vault)
     * 2. The service
     * 3. VettID (optionally)
     */
    private fun validateSignatureChain(response: ContractSignResponse): Boolean {
        // Basic validation - ensure chain exists
        if (response.signatureChain.isEmpty()) {
            android.util.Log.w("ContractSigner", "Empty signature chain")
            return false
        }

        // TODO: Implement full signature verification using Ed25519
        // For now, trust the vault's response
        return true
    }

    // MARK: - Serialization

    /**
     * Serialize a sign request to JSON bytes for NATS.
     */
    fun serializeSignRequest(request: ContractSignRequest): ByteArray {
        return gson.toJson(request).toByteArray(Charsets.UTF_8)
    }

    /**
     * Deserialize a sign response from JSON bytes.
     */
    fun deserializeSignResponse(data: ByteArray): ContractSignResponse {
        return gson.fromJson(String(data, Charsets.UTF_8), ContractSignResponse::class.java)
    }

    /**
     * Serialize a cancel request to JSON bytes for NATS.
     */
    fun serializeCancelRequest(request: ContractCancelRequest): ByteArray {
        return gson.toJson(request).toByteArray(Charsets.UTF_8)
    }
}

// MARK: - Data Classes

/**
 * Ed25519 keypair for contract/message signing.
 */
data class ContractSigningKeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val publicKeyBase64: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ContractSigningKeyPair
        return publicKeyBase64 == other.publicKeyBase64
    }

    override fun hashCode(): Int = publicKeyBase64.hashCode()
}

/**
 * Unsigned contract received from service for review.
 */
data class UnsignedContract(
    @SerializedName("contract_id")
    val contractId: String,
    @SerializedName("service_id")
    val serviceId: String,
    @SerializedName("service_name")
    val serviceName: String,
    @SerializedName("service_logo_url")
    val serviceLogoUrl: String? = null,
    val version: Int,
    val title: String,
    val description: String,
    @SerializedName("terms_url")
    val termsUrl: String? = null,
    @SerializedName("privacy_url")
    val privacyUrl: String? = null,
    val capabilities: List<ContractCapabilitySpec> = emptyList(),
    @SerializedName("required_fields")
    val requiredFields: List<String> = emptyList(),
    @SerializedName("optional_fields")
    val optionalFields: List<String> = emptyList(),
    @SerializedName("service_public_key")
    val servicePublicKey: String,
    @SerializedName("user_connection_key")
    val userConnectionKey: String? = null,
    @SerializedName("expires_at")
    val expiresAt: Long? = null
)

/**
 * Capability specification in a contract.
 */
data class ContractCapabilitySpec(
    val type: String,
    val description: String,
    val parameters: Map<String, Any> = emptyMap()
)

/**
 * Contract sign request sent to vault.
 */
data class ContractSignRequest(
    @SerializedName("event_id")
    val eventId: String,
    @SerializedName("event_type")
    val eventType: String,
    val timestamp: Long,
    val contract: UnsignedContract
)

/**
 * Contract cancel request sent to vault.
 */
data class ContractCancelRequest(
    @SerializedName("event_id")
    val eventId: String,
    @SerializedName("event_type")
    val eventType: String,
    val timestamp: Long,
    @SerializedName("contract_id")
    val contractId: String,
    val reason: String
)

/**
 * Signed contract response from vault.
 */
data class ContractSignResponse(
    @SerializedName("event_id")
    val eventId: String,
    val success: Boolean,
    val error: String? = null,
    val contract: SignedContractData,
    @SerializedName("signature_chain")
    val signatureChain: List<String>,
    @SerializedName("nats_credentials")
    val natsCredentials: NatsCredentialsResponse? = null
)

/**
 * Signed contract data from vault.
 */
data class SignedContractData(
    @SerializedName("contract_id")
    val contractId: String,
    @SerializedName("service_id")
    val serviceId: String,
    @SerializedName("service_name")
    val serviceName: String,
    @SerializedName("service_logo_url")
    val serviceLogoUrl: String? = null,
    val version: Int,
    val title: String,
    val description: String,
    @SerializedName("terms_url")
    val termsUrl: String? = null,
    @SerializedName("privacy_url")
    val privacyUrl: String? = null,
    val capabilities: List<ContractCapabilitySpec> = emptyList(),
    @SerializedName("required_fields")
    val requiredFields: List<String> = emptyList(),
    @SerializedName("optional_fields")
    val optionalFields: List<String> = emptyList(),
    @SerializedName("service_public_key")
    val servicePublicKey: String,
    @SerializedName("user_connection_key")
    val userConnectionKey: String,
    @SerializedName("signed_at")
    val signedAt: Long,
    @SerializedName("expires_at")
    val expiresAt: Long? = null
)

/**
 * NATS credentials for the service connection.
 */
data class NatsCredentialsResponse(
    val endpoint: String,
    @SerializedName("user_jwt")
    val userJwt: String,
    @SerializedName("user_seed")
    val userSeed: String,
    val subject: String,
    @SerializedName("expires_at")
    val expiresAt: Long? = null
)

/**
 * Exception thrown during contract signing.
 */
class ContractSigningException(message: String, cause: Throwable? = null) : Exception(message, cause)
