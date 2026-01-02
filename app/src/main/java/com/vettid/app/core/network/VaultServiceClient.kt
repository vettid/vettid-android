package com.vettid.app.core.network

import android.os.Build
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP client for Vault Services API
 *
 * Implements vault-services-api.yaml endpoints:
 * - Enrollment: start → attestation → password → finalize
 * - Authentication: request → execute
 * - Transaction keys management
 *
 * Supports dynamic API URLs parsed from QR codes for enrollment.
 */
@Singleton
open class VaultServiceClient @Inject constructor() {

    companion object {
        private const val BASE_URL = "https://tiqpij5mue.execute-api.us-east-1.amazonaws.com/"
        private const val TIMEOUT_SECONDS = 30L
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    // Cache for Retrofit instances by base URL
    private val apiCache = mutableMapOf<String, VaultServiceApi>()

    // Default API for non-enrollment operations
    private val defaultApi: VaultServiceApi by lazy {
        createApi(BASE_URL)
    }

    // Current enrollment API URL (set when processing QR code)
    private var enrollmentApiUrl: String? = null
    private var enrollmentApi: VaultServiceApi? = null

    // Current enrollment JWT token (obtained from authenticate endpoint)
    private var enrollmentToken: String? = null

    /**
     * Get the current enrollment token for storing after finalize.
     * This token can be used for member API calls.
     */
    fun getEnrollmentToken(): String? = enrollmentToken

    /**
     * Set the API URL for enrollment operations (from QR code)
     */
    fun setEnrollmentApiUrl(apiUrl: String) {
        val normalizedUrl = if (apiUrl.endsWith("/")) apiUrl else "$apiUrl/"
        enrollmentApiUrl = normalizedUrl
        enrollmentApi = createApi(normalizedUrl)
        // Clear any previous enrollment token when switching APIs
        enrollmentToken = null
    }

    /**
     * Get the current enrollment API, or default if not set
     */
    private fun getEnrollmentApi(): VaultServiceApi {
        return enrollmentApi ?: defaultApi
    }

    /**
     * Get the Bearer token header for enrollment requests
     */
    private fun getEnrollmentAuthHeader(): String {
        return "Bearer ${enrollmentToken ?: throw IllegalStateException("Enrollment token not set. Call authenticate() first.")}"
    }

    private fun createApi(baseUrl: String): VaultServiceApi {
        return apiCache.getOrPut(baseUrl) {
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(VaultServiceApi::class.java)
        }
    }

    // Legacy reference for backward compatibility
    private val api: VaultServiceApi get() = defaultApi

    // MARK: - Enrollment Flow

    /**
     * Step 1a: Authenticate with session_token to get enrollment JWT (production flow)
     * This is a PUBLIC endpoint - no Authorization header needed
     *
     * Note: The API URL must be set via setEnrollmentApiUrl() before calling this
     */
    suspend fun enrollAuthenticate(
        sessionToken: String,
        deviceId: String
    ): Result<EnrollAuthenticateResponse> {
        val request = EnrollAuthenticateRequest(
            sessionToken = sessionToken,
            deviceId = deviceId,
            deviceType = "android"
        )
        val result = safeApiCall { getEnrollmentApi().enrollAuthenticate(request) }

        // Store the enrollment token on success
        result.onSuccess { response ->
            enrollmentToken = response.enrollmentToken
        }

        return result
    }

    /**
     * Step 1b: Start enrollment directly with invitation_code (test/invitation flow)
     * This is a PUBLIC endpoint - no Authorization header needed
     *
     * This bypasses the authenticate step and uses the invitation_code directly.
     * Use this flow when the QR code contains invitation_code instead of session_token.
     *
     * Note: The API URL must be set via setEnrollmentApiUrl() before calling this
     */
    suspend fun enrollStartDirect(
        invitationCode: String,
        deviceId: String,
        skipAttestation: Boolean = true
    ): Result<EnrollStartDirectResponse> {
        val request = EnrollStartDirectRequest(
            invitationCode = invitationCode,
            deviceId = deviceId,
            deviceType = "android",
            skipAttestation = skipAttestation
        )
        val result = safeApiCall { getEnrollmentApi().enrollStartDirect(request) }

        // Store the enrollment token on success (if provided)
        result.onSuccess { response ->
            response.enrollmentToken?.let { enrollmentToken = it }
        }

        return result
    }

    /**
     * Step 2: Start enrollment (requires enrollment JWT from authenticate)
     * Returns transaction keys for password encryption
     */
    suspend fun enrollStart(skipAttestation: Boolean = true): Result<EnrollStartResponse> {
        val request = VaultEnrollStartRequest(skipAttestation = skipAttestation)
        return safeApiCall {
            getEnrollmentApi().enrollStart(getEnrollmentAuthHeader(), request)
        }
    }

    /**
     * Step 3: Submit device attestation (optional if skip_attestation=true)
     */
    suspend fun submitAttestation(
        certificateChain: List<ByteArray>
    ): Result<AttestationResponse> {
        val request = VaultAttestationRequest(
            platform = "android",
            attestationCertChain = certificateChain.map {
                android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
            }
        )
        return safeApiCall {
            getEnrollmentApi().submitAttestation(getEnrollmentAuthHeader(), request)
        }
    }

    /**
     * Step 4: Set enrollment password (encrypted with transaction key)
     *
     * @param encryptedPasswordHash Base64-encoded encrypted password hash
     * @param keyId The password_key_id from /enroll/start response
     * @param nonce Base64-encoded nonce used for encryption
     * @param ephemeralPublicKey Base64-encoded ephemeral X25519 public key
     */
    suspend fun setPassword(
        encryptedPasswordHash: String,
        keyId: String,
        nonce: String,
        ephemeralPublicKey: String
    ): Result<SetPasswordResponse> {
        val request = VaultSetPasswordRequest(
            encryptedPasswordHash = encryptedPasswordHash,
            keyId = keyId,
            nonce = nonce,
            ephemeralPublicKey = ephemeralPublicKey
        )
        return safeApiCall {
            getEnrollmentApi().setPassword(getEnrollmentAuthHeader(), request)
        }
    }

    /**
     * Step 5: Finalize enrollment
     * Returns credential blob, LAT, and user GUID
     */
    suspend fun finalize(): Result<FinalizeResponse> {
        return safeApiCall {
            getEnrollmentApi().finalize(getEnrollmentAuthHeader())
        }
    }

    // MARK: - Authentication Flow

    /**
     * Request authentication to perform an action
     */
    suspend fun authRequest(userGuid: String, action: String): Result<AuthRequestResponse> {
        val request = VaultAuthRequest(
            userGuid = userGuid,
            action = action,
            deviceInfo = DeviceInfo.current()
        )
        return safeApiCall { api.authRequest(request) }
    }

    /**
     * Execute authentication with encrypted credentials
     */
    suspend fun authExecute(
        authSessionId: String,
        credentialBlob: CredentialBlob,
        encryptedPasswordHash: String,
        transactionKeyId: String
    ): Result<AuthExecuteResponse> {
        val request = VaultAuthExecuteRequest(
            authSessionId = authSessionId,
            credentialBlob = credentialBlob,
            encryptedPasswordHash = encryptedPasswordHash,
            transactionKeyId = transactionKeyId
        )
        return safeApiCall { api.authExecute(request) }
    }

    /**
     * Validate LAT to prevent phishing
     */
    suspend fun validateLat(userGuid: String, expectedLat: String): Result<Unit> {
        val request = ValidateLATRequest(userGuid = userGuid, expectedLat = expectedLat)
        return safeApiCall { api.validateLat(request) }
    }

    // MARK: - Transaction Keys

    /**
     * Get available transaction keys
     */
    suspend fun getTransactionKeys(authToken: String): Result<TransactionKeysResponse> {
        return safeApiCall { api.getTransactionKeys("Bearer $authToken") }
    }

    /**
     * Request more transaction keys when pool is low
     */
    suspend fun replenishTransactionKeys(
        authToken: String,
        count: Int
    ): Result<TransactionKeysResponse> {
        val request = ReplenishKeysRequest(count = count)
        return safeApiCall { api.replenishTransactionKeys("Bearer $authToken", request) }
    }

    // MARK: - Vault Lifecycle

    /**
     * Get vault status
     */
    suspend fun getVaultStatus(authToken: String): Result<VaultStatusResponse> {
        return safeApiCall { api.getVaultStatus("Bearer $authToken") }
    }

    /**
     * Get vault health
     */
    suspend fun getVaultHealth(authToken: String): Result<VaultHealthResponse> {
        return safeApiCall { api.getVaultHealth("Bearer $authToken") }
    }

    /**
     * Provision vault instance
     */
    suspend fun provisionVault(authToken: String): Result<ProvisionResponse> {
        return safeApiCall { api.provisionVault("Bearer $authToken") }
    }

    /**
     * Initialize vault after EC2 is running
     */
    suspend fun initializeVault(authToken: String): Result<InitializeResponse> {
        return safeApiCall { api.initializeVault("Bearer $authToken") }
    }

    /**
     * Start a stopped vault
     */
    suspend fun startVault(authToken: String): Result<Unit> {
        return safeApiCall { api.startVault("Bearer $authToken") }
    }

    /**
     * Stop a running vault
     */
    suspend fun stopVault(authToken: String): Result<Unit> {
        return safeApiCall { api.stopVault("Bearer $authToken") }
    }

    /**
     * Terminate vault (cleanup)
     */
    suspend fun terminateVault(authToken: String): Result<TerminateResponse> {
        return safeApiCall { api.terminateVault("Bearer $authToken") }
    }

    /**
     * Trigger manual backup
     */
    suspend fun triggerBackup(authToken: String): Result<BackupStatusResponse> {
        return safeApiCall { api.triggerBackup("Bearer $authToken") }
    }

    /**
     * Get list of backups
     */
    suspend fun listBackups(authToken: String): Result<VaultBackupListResponse> {
        return safeApiCall { api.listBackups("Bearer $authToken") }
    }

    // MARK: - PCR (Platform Configuration Register) Values

    /**
     * Get current PCR values for Nitro Enclave attestation verification.
     * PCRs identify the exact code running in the enclave.
     * These values are signed by VettID and should be verified before use.
     */
    suspend fun getPcrValues(): Result<PcrValuesResponse> {
        return safeApiCall { api.getPcrValues() }
    }

    // MARK: - Protean Credential Backup

    /**
     * Backup the Protean Credential to VettID.
     *
     * @param authToken Member JWT for authorization
     * @param credentialBlob Base64-encoded encrypted credential blob
     * @param version Credential version number
     */
    suspend fun backupCredential(
        authToken: String,
        credentialBlob: String,
        version: Int
    ): Result<ProteanBackupResponse> {
        val request = ProteanBackupRequest(
            credentialBlob = credentialBlob,
            version = version
        )
        return safeApiCall { api.backupCredential("Bearer $authToken", request) }
    }

    /**
     * Get backup history for the user's credential.
     */
    suspend fun getCredentialBackups(authToken: String): Result<ProteanBackupListResponse> {
        return safeApiCall { api.getCredentialBackups("Bearer $authToken") }
    }

    // MARK: - Credential Recovery (24-hour delayed)

    /**
     * Request credential recovery.
     * This initiates a 24-hour security delay before the credential can be downloaded.
     *
     * @param email User's email for verification
     * @param backupPin 6-digit backup PIN set during enrollment
     */
    suspend fun requestRecovery(
        email: String,
        backupPin: String
    ): Result<RecoveryRequestResponse> {
        val request = RecoveryRequest(email = email, backupPin = backupPin)
        return safeApiCall { api.requestRecovery(request) }
    }

    /**
     * Check the status of a recovery request.
     *
     * @param recoveryId Recovery request ID from requestRecovery
     */
    suspend fun getRecoveryStatus(recoveryId: String): Result<RecoveryStatusResponse> {
        return safeApiCall { api.getRecoveryStatus(recoveryId) }
    }

    /**
     * Cancel a pending recovery request.
     *
     * @param recoveryId Recovery request ID to cancel
     */
    suspend fun cancelRecovery(recoveryId: String): Result<Unit> {
        val request = CancelRecoveryRequest(recoveryId = recoveryId)
        return safeApiCall { api.cancelRecovery(request) }
    }

    /**
     * Download the recovered credential after the 24-hour delay.
     *
     * @param recoveryId Recovery request ID
     */
    suspend fun downloadRecoveredCredential(recoveryId: String): Result<RecoveryDownloadResponse> {
        return safeApiCall { api.downloadRecoveredCredential(recoveryId) }
    }

    // MARK: - Helper

    private suspend fun <T> safeApiCall(call: suspend () -> Response<T>): Result<T> {
        return try {
            val response = call()
            if (response.isSuccessful) {
                response.body()?.let {
                    Result.success(it)
                } ?: Result.failure(VaultServiceException("Empty response body"))
            } else {
                val errorBody = response.errorBody()?.string()
                Result.failure(VaultServiceException(
                    "HTTP ${response.code()}: ${response.message()}",
                    code = response.code(),
                    errorBody = errorBody
                ))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// MARK: - Retrofit Interface

interface VaultServiceApi {
    // Enrollment - Step 1a: Authenticate (public endpoint, no auth header) - production flow
    @POST("vault/enroll/authenticate")
    suspend fun enrollAuthenticate(@Body request: EnrollAuthenticateRequest): Response<EnrollAuthenticateResponse>

    // Enrollment - Step 1b: Start Direct (public endpoint, no auth header) - invitation/test flow
    @POST("vault/enroll/start-direct")
    suspend fun enrollStartDirect(@Body request: EnrollStartDirectRequest): Response<EnrollStartDirectResponse>

    // Enrollment - Step 2: Start (requires Bearer token)
    @POST("vault/enroll/start")
    suspend fun enrollStart(
        @Header("Authorization") authToken: String,
        @Body request: VaultEnrollStartRequest
    ): Response<EnrollStartResponse>

    // Enrollment - Step 3: Attestation (optional, requires Bearer token)
    @POST("vault/enroll/attestation")
    suspend fun submitAttestation(
        @Header("Authorization") authToken: String,
        @Body request: VaultAttestationRequest
    ): Response<AttestationResponse>

    // Enrollment - Step 4: Set password (requires Bearer token)
    @POST("vault/enroll/set-password")
    suspend fun setPassword(
        @Header("Authorization") authToken: String,
        @Body request: VaultSetPasswordRequest
    ): Response<SetPasswordResponse>

    // Enrollment - Step 5: Finalize (requires Bearer token)
    @POST("vault/enroll/finalize")
    suspend fun finalize(
        @Header("Authorization") authToken: String
    ): Response<FinalizeResponse>

    @POST("vault/auth/request")
    suspend fun authRequest(@Body request: VaultAuthRequest): Response<AuthRequestResponse>

    @POST("vault/auth/execute")
    suspend fun authExecute(@Body request: VaultAuthExecuteRequest): Response<AuthExecuteResponse>

    @POST("vault/auth/validate-lat")
    suspend fun validateLat(@Body request: ValidateLATRequest): Response<Unit>

    @GET("vault/transaction-keys")
    suspend fun getTransactionKeys(
        @Header("Authorization") authToken: String
    ): Response<TransactionKeysResponse>

    @POST("vault/transaction-keys")
    suspend fun replenishTransactionKeys(
        @Header("Authorization") authToken: String,
        @Body request: ReplenishKeysRequest
    ): Response<TransactionKeysResponse>

    // Vault Lifecycle
    @GET("member/vault/status")
    suspend fun getVaultStatus(
        @Header("Authorization") authToken: String
    ): Response<VaultStatusResponse>

    @GET("vault/health")
    suspend fun getVaultHealth(
        @Header("Authorization") authToken: String
    ): Response<VaultHealthResponse>

    @POST("vault/provision")
    suspend fun provisionVault(
        @Header("Authorization") authToken: String
    ): Response<ProvisionResponse>

    @POST("vault/initialize")
    suspend fun initializeVault(
        @Header("Authorization") authToken: String
    ): Response<InitializeResponse>

    @POST("vault/start")
    suspend fun startVault(
        @Header("Authorization") authToken: String
    ): Response<Unit>

    @POST("vault/stop")
    suspend fun stopVault(
        @Header("Authorization") authToken: String
    ): Response<Unit>

    @POST("vault/terminate")
    suspend fun terminateVault(
        @Header("Authorization") authToken: String
    ): Response<TerminateResponse>

    @POST("vault/backup")
    suspend fun triggerBackup(
        @Header("Authorization") authToken: String
    ): Response<BackupStatusResponse>

    @GET("vault/backup")
    suspend fun listBackups(
        @Header("Authorization") authToken: String
    ): Response<VaultBackupListResponse>

    // PCR (Platform Configuration Register) Values for attestation
    @GET("vault/pcrs/current")
    suspend fun getPcrValues(): Response<PcrValuesResponse>

    // Protean Credential Backup
    @POST("vault/backup/credential")
    suspend fun backupCredential(
        @Header("Authorization") authToken: String,
        @Body request: ProteanBackupRequest
    ): Response<ProteanBackupResponse>

    @GET("vault/backup/credentials")
    suspend fun getCredentialBackups(
        @Header("Authorization") authToken: String
    ): Response<ProteanBackupListResponse>

    // Credential Recovery (24-hour delayed)
    @POST("vault/recovery/request")
    suspend fun requestRecovery(
        @Body request: RecoveryRequest
    ): Response<RecoveryRequestResponse>

    @GET("vault/recovery/status")
    suspend fun getRecoveryStatus(
        @Query("recovery_id") recoveryId: String
    ): Response<RecoveryStatusResponse>

    @POST("vault/recovery/cancel")
    suspend fun cancelRecovery(
        @Body request: CancelRecoveryRequest
    ): Response<Unit>

    @GET("vault/recovery/download")
    suspend fun downloadRecoveredCredential(
        @Query("recovery_id") recoveryId: String
    ): Response<RecoveryDownloadResponse>
}

// MARK: - Request Types

/**
 * Request to authenticate with session_token and get enrollment JWT
 */
data class EnrollAuthenticateRequest(
    @SerializedName("session_token") val sessionToken: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_type") val deviceType: String = "android"
)

/**
 * Request to start enrollment (after authentication)
 */
data class VaultEnrollStartRequest(
    @SerializedName("skip_attestation") val skipAttestation: Boolean = true
)

/**
 * Request to start enrollment directly with invitation_code (test/invitation flow)
 * This bypasses the authenticate step
 */
data class EnrollStartDirectRequest(
    @SerializedName("invitation_code") val invitationCode: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_type") val deviceType: String = "android",
    @SerializedName("skip_attestation") val skipAttestation: Boolean = true
)

data class DeviceInfo(
    val platform: String,
    val osVersion: String,
    val appVersion: String,
    val deviceModel: String? = null
) {
    companion object {
        fun current() = DeviceInfo(
            platform = "android",
            osVersion = "Android ${Build.VERSION.RELEASE}",
            appVersion = "1.0.0", // TODO: Get from BuildConfig
            deviceModel = Build.MODEL
        )
    }
}

data class VaultAttestationRequest(
    val platform: String,
    @SerializedName("attestation_cert_chain") val attestationCertChain: List<String>
)

data class VaultSetPasswordRequest(
    @SerializedName("encrypted_password_hash") val encryptedPasswordHash: String,
    @SerializedName("key_id") val keyId: String,
    @SerializedName("nonce") val nonce: String,
    @SerializedName("ephemeral_public_key") val ephemeralPublicKey: String
)

data class VaultAuthRequest(
    @SerializedName("userGuid") val userGuid: String,
    val action: String,
    @SerializedName("deviceInfo") val deviceInfo: DeviceInfo? = null
)

data class VaultAuthExecuteRequest(
    @SerializedName("authSessionId") val authSessionId: String,
    @SerializedName("credentialBlob") val credentialBlob: CredentialBlob,
    @SerializedName("encryptedPasswordHash") val encryptedPasswordHash: String,
    @SerializedName("transactionKeyId") val transactionKeyId: String
)

data class ValidateLATRequest(
    @SerializedName("userGuid") val userGuid: String,
    @SerializedName("expectedLat") val expectedLat: String
)

data class ReplenishKeysRequest(
    val count: Int = 10
)

// MARK: - Response Types

/**
 * Response from /vault/enroll/authenticate
 */
data class EnrollAuthenticateResponse(
    @SerializedName("enrollment_token") val enrollmentToken: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("expires_in") val expiresIn: Int,
    @SerializedName("expires_at") val expiresAt: String,
    @SerializedName("enrollment_session_id") val enrollmentSessionId: String,
    @SerializedName("user_guid") val userGuid: String
)

/**
 * Response from /vault/enroll/start
 */
data class EnrollStartResponse(
    @SerializedName("enrollment_session_id") val enrollmentSessionId: String? = null,
    @SerializedName("user_guid") val userGuid: String? = null,
    @SerializedName("transaction_keys") val transactionKeys: List<TransactionKeyPublic>,
    @SerializedName("password_key_id") val passwordKeyId: String, // Key ID to use for password encryption
    @SerializedName("next_step") val nextStep: String? = null,
    @SerializedName("attestation_required") val attestationRequired: Boolean = false,
    @SerializedName("attestation_challenge") val attestationChallenge: String? = null // Only if attestation required
)

/**
 * Response from /vault/enroll/start-direct
 * Similar to EnrollStartResponse but includes enrollment_token for subsequent calls
 */
data class EnrollStartDirectResponse(
    @SerializedName("enrollment_session_id") val enrollmentSessionId: String,
    @SerializedName("user_guid") val userGuid: String,
    @SerializedName("enrollment_token") val enrollmentToken: String? = null, // JWT for subsequent calls
    @SerializedName("transaction_keys") val transactionKeys: List<TransactionKeyPublic>,
    @SerializedName("password_key_id") val passwordKeyId: String,
    @SerializedName("next_step") val nextStep: String? = null,
    @SerializedName("attestation_required") val attestationRequired: Boolean = false,
    @SerializedName("attestation_challenge") val attestationChallenge: String? = null
)

data class TransactionKeyPublic(
    @SerializedName("key_id") val keyId: String,
    @SerializedName("public_key") val publicKey: String, // Base64 X25519 public key
    val algorithm: String = "X25519"
)

data class AttestationResponse(
    val verified: Boolean,
    @SerializedName("securityLevel") val securityLevel: String, // strongbox, tee, software
    val platform: String?,
    val osVersion: String?
)

data class SetPasswordResponse(
    val status: String,
    @SerializedName("next_step") val nextStep: String? = null
) {
    /** Check if password was set successfully */
    val isSuccess: Boolean get() = status == "password_set"
}

/**
 * Response from /vault/enroll/finalize
 */
data class FinalizeResponse(
    val status: String,
    @SerializedName("credential_package") val credentialPackage: CredentialPackage,
    @SerializedName("vault_status") val vaultStatus: String,
    @SerializedName("vault_bootstrap") val natsConnection: VaultBootstrap? = null,
    @SerializedName("vault_instance_id") val vaultInstanceId: String? = null
)

/**
 * Vault bootstrap info from enrollment finalize.
 * Contains NATS credentials for vault communication.
 *
 * Bootstrap credentials have limited permissions - only the bootstrap_topic
 * and response_topic are accessible until full credentials are obtained.
 */
data class VaultBootstrap(
    val credentials: String,  // NATS credential file content (JWT + seed)
    @SerializedName("nats_endpoint") val endpoint: String,
    @SerializedName("owner_space") val ownerSpace: String,
    @SerializedName("message_space") val messageSpace: String,
    @SerializedName("bootstrap_topic") val bootstrapTopic: String? = null,  // Publish topic for bootstrap
    @SerializedName("response_topic") val responseTopic: String? = null,    // Subscribe topic for bootstrap
    @SerializedName("credentials_ttl_seconds") val credentialsTtlSeconds: Int? = null,
    @SerializedName("ca_certificate") val caCertificate: String? = null
)

/**
 * NATS connection info for storage.
 * Valid for 24 hours from enrollment.
 */
data class NatsConnectionInfo(
    val endpoint: String,
    val credentials: String,  // NATS credential file content (JWT + seed)
    @SerializedName("owner_space") val ownerSpace: String,
    @SerializedName("message_space") val messageSpace: String,
    val topics: NatsTopics? = null,
    @SerializedName("ca_certificate") val caCertificate: String? = null
)

/**
 * NATS pub/sub topics for vault communication.
 */
data class NatsTopics(
    @SerializedName("send_to_vault") val sendToVault: String,
    @SerializedName("receive_from_vault") val receiveFromVault: String
)

/**
 * Credential package from finalize response
 */
data class CredentialPackage(
    @SerializedName("user_guid") val userGuid: String,
    @SerializedName("credential_id") val credentialId: String,
    @SerializedName("encrypted_blob") val encryptedBlob: String,
    @SerializedName("ephemeral_public_key") val ephemeralPublicKey: String,
    val nonce: String,
    @SerializedName("cek_version") val cekVersion: Int,
    @SerializedName("ledger_auth_token") val ledgerAuthToken: LedgerAuthToken,
    @SerializedName("transaction_keys") val transactionKeys: List<TransactionKey>
)

/**
 * Ledger auth token for anti-phishing verification
 */
data class LedgerAuthToken(
    val token: String, // lat_xxx... format
    val version: Int
)

/**
 * Transaction key from credential package
 */
data class TransactionKey(
    @SerializedName("key_id") val keyId: String,
    @SerializedName("public_key") val publicKey: String,
    val algorithm: String
)

// Legacy types for backward compatibility with existing auth flow
data class CredentialBlob(
    val data: String, // Base64 encrypted blob
    val version: Int,
    @SerializedName("cekVersion") val cekVersion: Int
)

data class LAT(
    @SerializedName("latId") val latId: String,
    val token: String, // Hex token
    val version: Int
)

data class AuthRequestResponse(
    @SerializedName("authSessionId") val authSessionId: String,
    val lat: LAT,
    val endpoint: String
)

data class AuthExecuteResponse(
    val success: Boolean,
    @SerializedName("newCredentialBlob") val newCredentialBlob: CredentialBlob,
    @SerializedName("newLat") val newLat: LAT,
    @SerializedName("actionToken") val actionToken: String,
    @SerializedName("newTransactionKeys") val newTransactionKeys: List<TransactionKeyPublic>?
)

data class TransactionKeysResponse(
    val keys: List<TransactionKeyPublic>
)

// MARK: - Vault Lifecycle Response Types

data class VaultStatusResponse(
    @SerializedName("vaultId") val vaultId: String,
    val status: String, // pending_enrollment, enrolled, provisioning, running, stopped, terminated
    @SerializedName("instanceId") val instanceId: String? = null,
    val region: String? = null,
    @SerializedName("enrolledAt") val enrolledAt: String? = null,
    @SerializedName("lastBackup") val lastBackup: String? = null,
    val health: VaultHealthResponse? = null
)

data class VaultHealthResponse(
    val status: String, // healthy, degraded, unhealthy, unknown
    @SerializedName("uptime_seconds") val uptimeSeconds: Long? = null,
    @SerializedName("local_nats") val localNats: NatsHealthResponse? = null,
    @SerializedName("central_nats") val centralNats: CentralNatsHealthResponse? = null,
    @SerializedName("vault_manager") val vaultManager: VaultManagerHealthResponse? = null,
    @SerializedName("last_event_at") val lastEventAt: String? = null,
    // Legacy fields for backward compatibility
    @SerializedName("memoryUsagePercent") val memoryUsagePercent: Float? = null,
    @SerializedName("diskUsagePercent") val diskUsagePercent: Float? = null,
    @SerializedName("cpuUsagePercent") val cpuUsagePercent: Float? = null,
    @SerializedName("natsConnected") val natsConnected: Boolean? = null,
    @SerializedName("lastChecked") val lastChecked: String? = null
)

data class NatsHealthResponse(
    val status: String, // running, stopped, error
    val connections: Int = 0
)

data class CentralNatsHealthResponse(
    val status: String, // connected, disconnected, error
    @SerializedName("latency_ms") val latencyMs: Long = 0
)

data class VaultManagerHealthResponse(
    val status: String, // running, stopped, error
    @SerializedName("memory_mb") val memoryMb: Int = 0,
    @SerializedName("cpu_percent") val cpuPercent: Float = 0f,
    @SerializedName("handlers_loaded") val handlersLoaded: Int = 0
)

data class ProvisionResponse(
    @SerializedName("vaultId") val vaultId: String,
    @SerializedName("instance_id") val instanceId: String? = null,
    val status: String, // provisioning, running, failed
    val region: String? = null,
    @SerializedName("availability_zone") val availabilityZone: String? = null,
    @SerializedName("private_ip") val privateIp: String? = null,
    @SerializedName("estimatedReadyTime") val estimatedReadyTime: String? = null,
    @SerializedName("estimated_ready_at") val estimatedReadyAt: String? = null
)

data class InitializeResponse(
    val status: String, // initialized, failed
    @SerializedName("local_nats_status") val localNatsStatus: String? = null,
    @SerializedName("central_nats_status") val centralNatsStatus: String? = null,
    @SerializedName("owner_space_id") val ownerSpaceId: String? = null,
    @SerializedName("message_space_id") val messageSpaceId: String? = null
)

data class TerminateResponse(
    val status: String, // terminated, failed
    @SerializedName("terminatedAt") val terminatedAt: String? = null
)

data class BackupStatusResponse(
    @SerializedName("backupId") val backupId: String,
    val status: String, // in_progress, completed, failed
    @SerializedName("startedAt") val startedAt: String? = null,
    @SerializedName("completedAt") val completedAt: String? = null
)

data class VaultBackupListResponse(
    val backups: List<BackupInfo>
)

data class BackupInfo(
    @SerializedName("backupId") val backupId: String,
    @SerializedName("createdAt") val createdAt: String,
    @SerializedName("sizeBytes") val sizeBytes: Long? = null,
    val type: String? = null // automatic, manual, pre_stop
)

// MARK: - QR Code Data

/**
 * Parsed data from VettID enrollment QR code
 *
 * Supports two flows:
 * 1. Production flow: Uses session_token → /vault/enroll/authenticate → JWT → /vault/enroll/start
 * 2. Test/invitation flow: Uses invitation_code → /vault/enroll/start-direct (no auth header)
 */
data class EnrollmentQRData(
    val type: String,
    val version: Int,
    @SerializedName("api_url") val apiUrl: String,
    @SerializedName("session_token") val sessionToken: String? = null,
    @SerializedName("user_guid") val userGuid: String? = null,
    @SerializedName("invitation_code") val invitationCode: String? = null,
    @SerializedName("skip_attestation") val skipAttestation: Boolean = false
) {
    /**
     * Returns true if this QR data uses the invitation_code flow (test/direct enrollment)
     */
    val isDirectEnrollment: Boolean
        get() = invitationCode != null && sessionToken == null

    /**
     * Returns true if this QR data uses the session_token flow (production enrollment)
     */
    val isSessionTokenEnrollment: Boolean
        get() = sessionToken != null

    companion object {
        // Invitation code pattern: TEST-XXXX-XXXX-XXXX or similar
        private val INVITATION_CODE_PATTERN = Regex("^[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$")

        // Default test API URL for invitation code flow
        private const val DEFAULT_TEST_API_URL = "https://tiqpij5mue.execute-api.us-east-1.amazonaws.com"

        /**
         * Parse QR code data (JSON or plain invitation code)
         * Returns null if parsing fails or data is invalid
         *
         * Supports:
         * - JSON format: {"type":"vettid_enrollment","api_url":"...","session_token":"..."}
         * - Plain invitation code: TEST-XXXX-XXXX-XXXX
         */
        fun parse(qrData: String): EnrollmentQRData? {
            val trimmed = qrData.trim()

            // First try JSON parsing
            try {
                val gson = com.google.gson.Gson()
                val parsed = gson.fromJson(trimmed, EnrollmentQRData::class.java)
                // Validate required fields - need either session_token or invitation_code
                if (parsed.type == "vettid_enrollment" &&
                    parsed.apiUrl.isNotBlank() &&
                    (parsed.sessionToken?.isNotBlank() == true || parsed.invitationCode?.isNotBlank() == true)) {
                    return parsed
                }
            } catch (e: Exception) {
                // JSON parsing failed, try other formats
            }

            // Try plain invitation code format (TEST-XXXX-XXXX-XXXX)
            if (INVITATION_CODE_PATTERN.matches(trimmed)) {
                return EnrollmentQRData(
                    type = "vettid_enrollment",
                    version = 1,
                    apiUrl = DEFAULT_TEST_API_URL,
                    invitationCode = trimmed,
                    skipAttestation = true
                )
            }

            return null
        }
    }
}

// MARK: - Protean Credential Backup Request/Response Types

/**
 * Request to backup the Protean Credential.
 */
data class ProteanBackupRequest(
    @SerializedName("credential_blob") val credentialBlob: String,
    val version: Int
)

/**
 * Response from Protean Credential backup.
 */
data class ProteanBackupResponse(
    @SerializedName("backup_id") val backupId: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("size_bytes") val sizeBytes: Int
)

/**
 * List of Protean Credential backups.
 */
data class ProteanBackupListResponse(
    val backups: List<ProteanBackupInfo>
)

/**
 * Information about a single Protean Credential backup.
 */
data class ProteanBackupInfo(
    @SerializedName("backup_id") val backupId: String,
    val version: Int,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("size_bytes") val sizeBytes: Int
)

// MARK: - Credential Recovery Request/Response Types

/**
 * Request to initiate credential recovery.
 */
data class RecoveryRequest(
    val email: String,
    @SerializedName("backup_pin") val backupPin: String
)

/**
 * Response from recovery request.
 */
data class RecoveryRequestResponse(
    @SerializedName("recovery_id") val recoveryId: String,
    @SerializedName("requested_at") val requestedAt: String,
    @SerializedName("available_at") val availableAt: String,
    val status: String // "pending"
)

/**
 * Response from recovery status check.
 */
data class RecoveryStatusResponse(
    @SerializedName("recovery_id") val recoveryId: String,
    val status: String, // "pending" | "ready" | "cancelled" | "expired"
    @SerializedName("available_at") val availableAt: String,
    @SerializedName("remaining_seconds") val remainingSeconds: Long
)

/**
 * Request to cancel recovery.
 */
data class CancelRecoveryRequest(
    @SerializedName("recovery_id") val recoveryId: String
)

/**
 * Response from recovery download.
 */
data class RecoveryDownloadResponse(
    @SerializedName("credential_blob") val credentialBlob: String,
    val version: Int,
    @SerializedName("user_guid") val userGuid: String
)

// MARK: - PCR Response Types

/**
 * Response from /vault/pcrs/current
 * Contains signed PCR values for Nitro Enclave attestation verification.
 */
data class PcrValuesResponse(
    /** PCR values (SHA-384 hashes) */
    val pcrs: PcrValueSet,
    /** Version of this PCR set */
    val version: String,
    /** When these PCRs were published */
    @SerializedName("published_at") val publishedAt: String,
    /** Ed25519 signature over canonical JSON of pcrs */
    val signature: String,
    /** Signing key ID (for key rotation) */
    @SerializedName("key_id") val keyId: String? = null
)

/**
 * Individual PCR values from API response.
 */
data class PcrValueSet(
    /** PCR0: Hash of enclave image (96 hex chars = 48 bytes SHA-384) */
    @SerializedName("PCR0") val pcr0: String,
    /** PCR1: Hash of Linux kernel and bootstrap */
    @SerializedName("PCR1") val pcr1: String,
    /** PCR2: Hash of application */
    @SerializedName("PCR2") val pcr2: String,
    /** PCR3: Hash of IAM role (optional) */
    @SerializedName("PCR3") val pcr3: String? = null
)

// MARK: - Exceptions

class VaultServiceException(
    message: String,
    val code: Int? = null,
    val errorBody: String? = null
) : Exception(message)
