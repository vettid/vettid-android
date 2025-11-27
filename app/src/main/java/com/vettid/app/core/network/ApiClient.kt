package com.vettid.app.core.network

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP client for communicating with the VettID Ledger Service
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

    // MARK: - Enrollment (Multi-Step)

    /**
     * Step 1: Start enrollment with invitation code
     * Returns session ID, UTKs, and password prompt
     */
    suspend fun enrollStart(request: EnrollStartRequest): Result<EnrollStartResponse> {
        return safeApiCall { api.enrollStart(request) }
    }

    /**
     * Step 2: Set password during enrollment
     * Password hash encrypted with UTK before sending
     */
    suspend fun enrollSetPassword(request: EnrollSetPasswordRequest): Result<EnrollSetPasswordResponse> {
        return safeApiCall { api.enrollSetPassword(request) }
    }

    /**
     * Step 3: Finalize enrollment
     * Returns credential package with encrypted blob, LAT, and remaining UTKs
     */
    suspend fun enrollFinalize(request: EnrollFinalizeRequest): Result<EnrollFinalizeResponse> {
        return safeApiCall { api.enrollFinalize(request) }
    }

    // MARK: - Authentication (Action-Specific)

    /**
     * Step 1: Request action token
     * Returns scoped JWT and LAT for verification
     */
    suspend fun requestAction(
        request: ActionRequest,
        cognitoToken: String
    ): Result<ActionResponse> {
        return safeApiCall { api.requestAction(request, "Bearer $cognitoToken") }
    }

    /**
     * Step 2: Execute authentication
     * Uses action token (not Cognito token)
     */
    suspend fun executeAuth(
        request: AuthExecuteRequest,
        actionToken: String
    ): Result<AuthExecuteResponse> {
        return safeApiCall { api.executeAuth(request, "Bearer $actionToken") }
    }

    // MARK: - Vault Operations (Phase 5 - Not Yet Deployed)

    suspend fun getVaultStatus(vaultId: String, authToken: String): Result<VaultStatusResponse> {
        return safeApiCall { api.getVaultStatus(vaultId, "Bearer $authToken") }
    }

    suspend fun startVault(vaultId: String, authToken: String): Result<VaultActionResponse> {
        return safeApiCall { api.startVault(vaultId, "Bearer $authToken") }
    }

    suspend fun stopVault(vaultId: String, authToken: String): Result<VaultActionResponse> {
        return safeApiCall { api.stopVault(vaultId, "Bearer $authToken") }
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
    // Enrollment endpoints
    @POST("api/v1/enroll/start")
    suspend fun enrollStart(@Body request: EnrollStartRequest): Response<EnrollStartResponse>

    @POST("api/v1/enroll/set-password")
    suspend fun enrollSetPassword(@Body request: EnrollSetPasswordRequest): Response<EnrollSetPasswordResponse>

    @POST("api/v1/enroll/finalize")
    suspend fun enrollFinalize(@Body request: EnrollFinalizeRequest): Response<EnrollFinalizeResponse>

    // Authentication endpoints
    @POST("api/v1/action/request")
    suspend fun requestAction(
        @Body request: ActionRequest,
        @Header("Authorization") cognitoToken: String
    ): Response<ActionResponse>

    @POST("api/v1/auth/execute")
    suspend fun executeAuth(
        @Body request: AuthExecuteRequest,
        @Header("Authorization") actionToken: String
    ): Response<AuthExecuteResponse>

    // Vault endpoints (Phase 5)
    @GET("member/vaults/{vaultId}/status")
    suspend fun getVaultStatus(
        @Path("vaultId") vaultId: String,
        @Header("Authorization") authToken: String
    ): Response<VaultStatusResponse>

    @POST("member/vaults/{vaultId}/start")
    suspend fun startVault(
        @Path("vaultId") vaultId: String,
        @Header("Authorization") authToken: String
    ): Response<VaultActionResponse>

    @POST("member/vaults/{vaultId}/stop")
    suspend fun stopVault(
        @Path("vaultId") vaultId: String,
        @Header("Authorization") authToken: String
    ): Response<VaultActionResponse>
}

// MARK: - Enrollment Request/Response Types

data class EnrollStartRequest(
    @SerializedName("invitation_code") val invitationCode: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("attestation_data") val attestationData: String  // Base64
)

data class EnrollStartResponse(
    @SerializedName("enrollment_session_id") val enrollmentSessionId: String,
    @SerializedName("user_guid") val userGuid: String,
    @SerializedName("transaction_keys") val transactionKeys: List<TransactionKeyInfo>,
    @SerializedName("password_prompt") val passwordPrompt: PasswordPrompt
)

data class TransactionKeyInfo(
    @SerializedName("key_id") val keyId: String,
    @SerializedName("public_key") val publicKey: String,  // Base64
    val algorithm: String  // "X25519"
)

data class PasswordPrompt(
    @SerializedName("use_key_id") val useKeyId: String,
    val message: String
)

data class EnrollSetPasswordRequest(
    @SerializedName("enrollment_session_id") val enrollmentSessionId: String,
    @SerializedName("encrypted_password_hash") val encryptedPasswordHash: String,  // Base64
    @SerializedName("key_id") val keyId: String,
    val nonce: String  // Base64
)

data class EnrollSetPasswordResponse(
    val status: String,  // "password_set"
    @SerializedName("next_step") val nextStep: String  // "finalize"
)

data class EnrollFinalizeRequest(
    @SerializedName("enrollment_session_id") val enrollmentSessionId: String
)

data class EnrollFinalizeResponse(
    val status: String,  // "enrolled"
    @SerializedName("credential_package") val credentialPackage: CredentialPackage,
    @SerializedName("vault_status") val vaultStatus: String  // "PROVISIONING"
)

data class CredentialPackage(
    @SerializedName("user_guid") val userGuid: String,
    @SerializedName("encrypted_blob") val encryptedBlob: String,  // Base64
    @SerializedName("cek_version") val cekVersion: Int,
    @SerializedName("ledger_auth_token") val ledgerAuthToken: LedgerAuthToken,
    @SerializedName("transaction_keys") val transactionKeys: List<TransactionKeyInfo>
)

data class LedgerAuthToken(
    @SerializedName("lat_id") val latId: String,
    val token: String,  // Hex
    val version: Int
)

// MARK: - Authentication Request/Response Types

data class ActionRequest(
    @SerializedName("user_guid") val userGuid: String,
    @SerializedName("action_type") val actionType: String,  // "authenticate", "add_secret", etc.
    @SerializedName("device_fingerprint") val deviceFingerprint: String? = null
)

data class ActionResponse(
    @SerializedName("action_token") val actionToken: String,  // JWT
    @SerializedName("action_token_expires_at") val actionTokenExpiresAt: String,  // ISO8601
    @SerializedName("ledger_auth_token") val ledgerAuthToken: LedgerAuthToken,
    @SerializedName("action_endpoint") val actionEndpoint: String,
    @SerializedName("use_key_id") val useKeyId: String  // UTK to use
)

data class AuthExecuteRequest(
    @SerializedName("encrypted_blob") val encryptedBlob: String,  // Base64
    @SerializedName("cek_version") val cekVersion: Int,
    @SerializedName("encrypted_password_hash") val encryptedPasswordHash: String,  // Base64
    @SerializedName("ephemeral_public_key") val ephemeralPublicKey: String,  // Base64
    val nonce: String,  // Base64
    @SerializedName("key_id") val keyId: String
)

data class AuthExecuteResponse(
    val status: String,  // "success"
    @SerializedName("action_result") val actionResult: ActionResult,
    @SerializedName("credential_package") val credentialPackage: CredentialPackage,
    @SerializedName("used_key_id") val usedKeyId: String
)

data class ActionResult(
    val authenticated: Boolean,
    val message: String,
    val timestamp: String  // ISO8601
)

// MARK: - Vault Types (Phase 5)

data class VaultStatusResponse(
    @SerializedName("vault_id") val vaultId: String,
    val status: String,
    @SerializedName("instance_id") val instanceId: String?,
    @SerializedName("public_ip") val publicIP: String?,
    @SerializedName("last_heartbeat") val lastHeartbeat: Long?
)

data class VaultActionResponse(
    val success: Boolean,
    val message: String
)

// MARK: - Exceptions

class ApiException(message: String) : Exception(message)
