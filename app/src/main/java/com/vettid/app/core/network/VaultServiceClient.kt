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

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL_DEV) // Use dev URL for testing
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(VaultServiceApi::class.java)

    // MARK: - Enrollment Flow

    /**
     * Step 1: Start enrollment after scanning QR code
     * Returns attestation challenge and initial transaction keys
     */
    suspend fun enrollStart(inviteCode: String, deviceInfo: DeviceInfo): Result<EnrollStartResponse> {
        val request = VaultEnrollStartRequest(
            inviteCode = inviteCode,
            deviceInfo = deviceInfo
        )
        return safeApiCall { api.enrollStart(request) }
    }

    /**
     * Step 2: Submit device attestation
     */
    suspend fun submitAttestation(
        sessionId: String,
        certificateChain: List<ByteArray>
    ): Result<AttestationResponse> {
        val request = VaultAttestationRequest(
            sessionId = sessionId,
            platform = "android",
            attestationCertChain = certificateChain.map {
                android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
            }
        )
        return safeApiCall { api.submitAttestation(request) }
    }

    /**
     * Step 3: Set enrollment password (encrypted with transaction key)
     */
    suspend fun setPassword(
        sessionId: String,
        encryptedPassword: String,
        transactionKeyId: String
    ): Result<SetPasswordResponse> {
        val request = VaultSetPasswordRequest(
            sessionId = sessionId,
            encryptedPassword = encryptedPassword,
            transactionKeyId = transactionKeyId
        )
        return safeApiCall { api.setPassword(request) }
    }

    /**
     * Step 4: Finalize enrollment
     * Returns credential blob, LAT, and user GUID
     */
    suspend fun finalize(sessionId: String): Result<FinalizeResponse> {
        val request = VaultFinalizeRequest(sessionId = sessionId)
        return safeApiCall { api.finalize(request) }
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
    @POST("vault/enroll/start")
    suspend fun enrollStart(@Body request: VaultEnrollStartRequest): Response<EnrollStartResponse>

    @POST("vault/enroll/attestation")
    suspend fun submitAttestation(@Body request: VaultAttestationRequest): Response<AttestationResponse>

    @POST("vault/enroll/password")
    suspend fun setPassword(@Body request: VaultSetPasswordRequest): Response<SetPasswordResponse>

    @POST("vault/enroll/finalize")
    suspend fun finalize(@Body request: VaultFinalizeRequest): Response<FinalizeResponse>

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

data class VaultEnrollStartRequest(
    @SerializedName("inviteCode") val inviteCode: String,
    @SerializedName("deviceInfo") val deviceInfo: DeviceInfo
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
    @SerializedName("sessionId") val sessionId: String,
    val platform: String,
    @SerializedName("attestationCertChain") val attestationCertChain: List<String>
)

data class VaultSetPasswordRequest(
    @SerializedName("sessionId") val sessionId: String,
    @SerializedName("encryptedPassword") val encryptedPassword: String,
    @SerializedName("transactionKeyId") val transactionKeyId: String
)

data class VaultFinalizeRequest(
    @SerializedName("sessionId") val sessionId: String
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

data class EnrollStartResponse(
    @SerializedName("sessionId") val sessionId: String,
    @SerializedName("attestationChallenge") val attestationChallenge: String, // Base64
    @SerializedName("transactionKeys") val transactionKeys: List<TransactionKeyPublic>
)

data class TransactionKeyPublic(
    @SerializedName("keyId") val keyId: String,
    @SerializedName("publicKey") val publicKey: String, // Base64 X25519 public key
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

// MARK: - Exceptions

class VaultServiceException(
    message: String,
    val code: Int? = null,
    val errorBody: String? = null
) : Exception(message)
