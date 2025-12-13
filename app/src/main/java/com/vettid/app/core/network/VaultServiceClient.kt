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
class VaultServiceClient @Inject constructor() {

    companion object {
        private const val BASE_URL_PROD = "https://api.vettid.dev/"
        private const val BASE_URL_DEV = "https://api-dev.vettid.dev/"
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
        createApi(BASE_URL_DEV)
    }

    // Current enrollment API URL (set when processing QR code)
    private var enrollmentApiUrl: String? = null
    private var enrollmentApi: VaultServiceApi? = null

    // Current enrollment JWT token (obtained from authenticate endpoint)
    private var enrollmentToken: String? = null

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
     * Step 1: Authenticate with session_token to get enrollment JWT
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
    // Enrollment - Step 1: Authenticate (public endpoint, no auth header)
    @POST("vault/enroll/authenticate")
    suspend fun enrollAuthenticate(@Body request: EnrollAuthenticateRequest): Response<EnrollAuthenticateResponse>

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
    val success: Boolean
)

data class FinalizeResponse(
    @SerializedName("credentialBlob") val credentialBlob: CredentialBlob,
    val lat: LAT,
    @SerializedName("userGuid") val userGuid: String,
    @SerializedName("backupPublicKey") val backupPublicKey: String? = null
)

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
 */
data class EnrollmentQRData(
    val type: String,
    val version: Int,
    @SerializedName("api_url") val apiUrl: String,
    @SerializedName("session_token") val sessionToken: String,
    @SerializedName("user_guid") val userGuid: String
) {
    companion object {
        /**
         * Parse QR code JSON data
         * Returns null if parsing fails or data is invalid
         */
        fun parse(qrData: String): EnrollmentQRData? {
            return try {
                val gson = com.google.gson.Gson()
                val parsed = gson.fromJson(qrData, EnrollmentQRData::class.java)
                // Validate required fields
                if (parsed.type == "vettid_enrollment" &&
                    parsed.apiUrl.isNotBlank() &&
                    parsed.sessionToken.isNotBlank()) {
                    parsed
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

// MARK: - Exceptions

class VaultServiceException(
    message: String,
    val code: Int? = null,
    val errorBody: String? = null
) : Exception(message)
