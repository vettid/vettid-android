package com.vettid.app.core.network

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP client for communicating with the VettID Ledger Service (Legacy API)
 *
 * NOTE: This client is for the legacy Ledger Service API.
 * For vault operations, use VaultServiceClient instead.
 *
 * API Flow:
 * - Enrollment: start → set-password → finalize (multi-step)
 * - Authentication: action/request → auth/execute (action-specific)
 */
@Singleton
class ApiClient @Inject constructor() {

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.vettid.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(VettIDApi::class.java)

    // MARK: - Legacy Enrollment (Multi-Step) - Use VaultServiceClient instead

    /**
     * Step 1: Start enrollment with invitation code
     * @deprecated Use VaultServiceClient.enrollStart() instead
     */
    @Deprecated("Use VaultServiceClient.enrollStart() instead")
    suspend fun enrollStart(request: LegacyEnrollStartRequest): Result<LegacyEnrollStartResponse> {
        return safeApiCall { api.enrollStart(request) }
    }

    /**
     * Step 2: Set password during enrollment
     * @deprecated Use VaultServiceClient.setPassword() instead
     */
    @Deprecated("Use VaultServiceClient.setPassword() instead")
    suspend fun enrollSetPassword(request: LegacyEnrollSetPasswordRequest): Result<LegacyEnrollSetPasswordResponse> {
        return safeApiCall { api.enrollSetPassword(request) }
    }

    /**
     * Step 3: Finalize enrollment
     * @deprecated Use VaultServiceClient.finalize() instead
     */
    @Deprecated("Use VaultServiceClient.finalize() instead")
    suspend fun enrollFinalize(request: LegacyEnrollFinalizeRequest): Result<LegacyEnrollFinalizeResponse> {
        return safeApiCall { api.enrollFinalize(request) }
    }

    // MARK: - Legacy Authentication (Action-Specific) - Use VaultServiceClient instead

    /**
     * Step 1: Request action token
     * @deprecated Use VaultServiceClient.authRequest() instead
     */
    @Deprecated("Use VaultServiceClient.authRequest() instead")
    suspend fun requestAction(
        request: LegacyActionRequest,
        cognitoToken: String
    ): Result<LegacyActionResponse> {
        return safeApiCall { api.requestAction(request, "Bearer $cognitoToken") }
    }

    /**
     * Step 2: Execute authentication
     * @deprecated Use VaultServiceClient.authExecute() instead
     */
    @Deprecated("Use VaultServiceClient.authExecute() instead")
    suspend fun executeAuth(
        request: LegacyAuthExecuteRequest,
        actionToken: String
    ): Result<LegacyAuthExecuteResponse> {
        return safeApiCall { api.executeAuth(request, "Bearer $actionToken") }
    }

    // MARK: - Helper

    private suspend fun <T> safeApiCall(call: suspend () -> Response<T>): Result<T> {
        return try {
            val response = call()
            if (response.isSuccessful) {
                response.body()?.let {
                    Result.success(it)
                } ?: Result.failure(ApiException("Empty response body"))
            } else {
                Result.failure(ApiException("HTTP ${response.code()}: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

interface VettIDApi {
    // Legacy Enrollment endpoints
    @POST("api/v1/enroll/start")
    suspend fun enrollStart(@Body request: LegacyEnrollStartRequest): Response<LegacyEnrollStartResponse>

    @POST("api/v1/enroll/set-password")
    suspend fun enrollSetPassword(@Body request: LegacyEnrollSetPasswordRequest): Response<LegacyEnrollSetPasswordResponse>

    @POST("api/v1/enroll/finalize")
    suspend fun enrollFinalize(@Body request: LegacyEnrollFinalizeRequest): Response<LegacyEnrollFinalizeResponse>

    // Legacy Authentication endpoints
    @POST("api/v1/action/request")
    suspend fun requestAction(
        @Body request: LegacyActionRequest,
        @Header("Authorization") cognitoToken: String
    ): Response<LegacyActionResponse>

    @POST("api/v1/auth/execute")
    suspend fun executeAuth(
        @Body request: LegacyAuthExecuteRequest,
        @Header("Authorization") actionToken: String
    ): Response<LegacyAuthExecuteResponse>
}

// MARK: - Transaction Key Info (Used for credential storage)

/**
 * Transaction key information for credential storage.
 * Used internally by CredentialStore.
 */
data class TransactionKeyInfo(
    @SerializedName("key_id") val keyId: String,
    @SerializedName("public_key") val publicKey: String,  // Base64
    val algorithm: String  // "X25519"
)

// MARK: - Legacy Enrollment Request/Response Types

data class LegacyEnrollStartRequest(
    @SerializedName("invitation_code") val invitationCode: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("attestation_data") val attestationData: String  // Base64
)

data class LegacyEnrollStartResponse(
    @SerializedName("enrollment_session_id") val enrollmentSessionId: String,
    @SerializedName("user_guid") val userGuid: String,
    @SerializedName("transaction_keys") val transactionKeys: List<TransactionKeyInfo>,
    @SerializedName("password_prompt") val passwordPrompt: LegacyPasswordPrompt
)

data class LegacyPasswordPrompt(
    @SerializedName("use_key_id") val useKeyId: String,
    val message: String
)

data class LegacyEnrollSetPasswordRequest(
    @SerializedName("enrollment_session_id") val enrollmentSessionId: String,
    @SerializedName("encrypted_password_hash") val encryptedPasswordHash: String,  // Base64
    @SerializedName("key_id") val keyId: String,
    val nonce: String  // Base64
)

data class LegacyEnrollSetPasswordResponse(
    val status: String,  // "password_set"
    @SerializedName("next_step") val nextStep: String  // "finalize"
)

data class LegacyEnrollFinalizeRequest(
    @SerializedName("enrollment_session_id") val enrollmentSessionId: String
)

data class LegacyEnrollFinalizeResponse(
    val status: String,  // "enrolled"
    @SerializedName("credential_package") val credentialPackage: LegacyCredentialPackage,
    @SerializedName("vault_status") val vaultStatus: String  // "PROVISIONING"
)

data class LegacyCredentialPackage(
    @SerializedName("user_guid") val userGuid: String,
    @SerializedName("encrypted_blob") val encryptedBlob: String,  // Base64
    @SerializedName("cek_version") val cekVersion: Int,
    @SerializedName("ledger_auth_token") val ledgerAuthToken: LegacyLedgerAuthToken,
    @SerializedName("transaction_keys") val transactionKeys: List<TransactionKeyInfo>
)

data class LegacyLedgerAuthToken(
    @SerializedName("lat_id") val latId: String,
    val token: String,  // Hex
    val version: Int
)

// MARK: - Legacy Authentication Request/Response Types

data class LegacyActionRequest(
    @SerializedName("user_guid") val userGuid: String,
    @SerializedName("action_type") val actionType: String,  // "authenticate", "add_secret", etc.
    @SerializedName("device_fingerprint") val deviceFingerprint: String? = null
)

data class LegacyActionResponse(
    @SerializedName("action_token") val actionToken: String,  // JWT
    @SerializedName("action_token_expires_at") val actionTokenExpiresAt: String,  // ISO8601
    @SerializedName("ledger_auth_token") val ledgerAuthToken: LegacyLedgerAuthToken,
    @SerializedName("action_endpoint") val actionEndpoint: String,
    @SerializedName("use_key_id") val useKeyId: String  // UTK to use
)

data class LegacyAuthExecuteRequest(
    @SerializedName("encrypted_blob") val encryptedBlob: String,  // Base64
    @SerializedName("cek_version") val cekVersion: Int,
    @SerializedName("encrypted_password_hash") val encryptedPasswordHash: String,  // Base64
    @SerializedName("ephemeral_public_key") val ephemeralPublicKey: String,  // Base64
    val nonce: String,  // Base64
    @SerializedName("key_id") val keyId: String
)

data class LegacyAuthExecuteResponse(
    val status: String,  // "success"
    @SerializedName("action_result") val actionResult: LegacyActionResult,
    @SerializedName("credential_package") val credentialPackage: LegacyCredentialPackage,
    @SerializedName("used_key_id") val usedKeyId: String
)

data class LegacyActionResult(
    val authenticated: Boolean,
    val message: String,
    val timestamp: String  // ISO8601
)

// MARK: - Exceptions

class ApiException(message: String) : Exception(message)
