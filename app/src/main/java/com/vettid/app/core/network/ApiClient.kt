package com.vettid.app.core.network

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP client for communicating with the VettID Ledger Service
 */
@Singleton
class ApiClient @Inject constructor() {

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.vettid.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(VettIDApi::class.java)

    // MARK: - Enrollment

    suspend fun enroll(request: EnrollmentRequest): Result<EnrollmentResponse> {
        return safeApiCall { api.enroll(request) }
    }

    // MARK: - Authentication

    suspend fun authenticate(request: AuthenticationRequest): Result<AuthenticationResponse> {
        return safeApiCall { api.authenticate(request) }
    }

    // MARK: - Vault Operations

    suspend fun getVaultStatus(vaultId: String, authToken: String): Result<VaultStatusResponse> {
        return safeApiCall { api.getVaultStatus(vaultId, "Bearer $authToken") }
    }

    suspend fun vaultAction(
        vaultId: String,
        action: VaultActionRequest,
        authToken: String
    ): Result<VaultActionResponse> {
        return safeApiCall { api.vaultAction(vaultId, action, "Bearer $authToken") }
    }

    // MARK: - Key Rotation

    suspend fun rotateCEK(request: CEKRotationRequest, authToken: String): Result<CEKRotationResponse> {
        return safeApiCall { api.rotateCEK(request, "Bearer $authToken") }
    }

    suspend fun replenishTransactionKeys(
        request: TKReplenishRequest,
        authToken: String
    ): Result<TKReplenishResponse> {
        return safeApiCall { api.replenishTransactionKeys(request, "Bearer $authToken") }
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
    @POST("v1/enroll")
    suspend fun enroll(@Body request: EnrollmentRequest): Response<EnrollmentResponse>

    @POST("v1/auth")
    suspend fun authenticate(@Body request: AuthenticationRequest): Response<AuthenticationResponse>

    @GET("v1/vaults/{vaultId}/status")
    suspend fun getVaultStatus(
        @Path("vaultId") vaultId: String,
        @Header("Authorization") authToken: String
    ): Response<VaultStatusResponse>

    @POST("v1/vaults/{vaultId}/actions")
    suspend fun vaultAction(
        @Path("vaultId") vaultId: String,
        @Body action: VaultActionRequest,
        @Header("Authorization") authToken: String
    ): Response<VaultActionResponse>

    @POST("v1/keys/cek/rotate")
    suspend fun rotateCEK(
        @Body request: CEKRotationRequest,
        @Header("Authorization") authToken: String
    ): Response<CEKRotationResponse>

    @POST("v1/keys/tk/replenish")
    suspend fun replenishTransactionKeys(
        @Body request: TKReplenishRequest,
        @Header("Authorization") authToken: String
    ): Response<TKReplenishResponse>
}

// MARK: - Request/Response Types

data class EnrollmentRequest(
    val invitationCode: String,
    val deviceId: String,
    val cekPublicKey: String,           // Base64 encoded
    val signingPublicKey: String,       // Base64 encoded
    val transactionPublicKeys: List<String>, // Base64 encoded
    val attestationCertChain: List<String>   // Base64 encoded certificates
)

data class EnrollmentResponse(
    val credentialId: String,
    val vaultId: String,
    val lat: String,                    // Base64 encoded LAT token
    val encryptedCredentialBlob: String // Base64 encoded
)

data class AuthenticationRequest(
    val credentialId: String,
    val lat: String,                    // Base64 encoded
    val signature: String,              // Base64 encoded
    val timestamp: Long
)

data class AuthenticationResponse(
    val authToken: String,              // Short-lived JWT
    val newLat: String,                 // Base64 encoded rotated LAT
    val newCekPublicKey: String?        // Base64, if CEK rotation required
)

data class VaultStatusResponse(
    val vaultId: String,
    val status: String,
    val instanceId: String?,
    val publicIP: String?,
    val lastHeartbeat: Long?
)

data class VaultActionRequest(
    val action: String  // start, stop, restart, terminate
)

data class VaultActionResponse(
    val success: Boolean,
    val message: String
)

data class CEKRotationRequest(
    val credentialId: String,
    val newCekPublicKey: String,
    val signature: String
)

data class CEKRotationResponse(
    val success: Boolean,
    val acknowledgedAt: Long
)

data class TKReplenishRequest(
    val credentialId: String,
    val newPublicKeys: List<String>
)

data class TKReplenishResponse(
    val success: Boolean,
    val keysAccepted: Int
)

class ApiException(message: String) : Exception(message)
