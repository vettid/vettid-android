package com.vettid.app.core.nats

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * API client for NATS-related backend endpoints.
 *
 * Endpoints:
 * - POST /vault/nats/account - Create NATS account
 * - POST /vault/nats/token - Generate NATS token
 * - GET /vault/nats/status - Get NATS status
 * - DELETE /vault/nats/token/{tokenId} - Revoke token
 */
@Singleton
class NatsApiClient @Inject constructor() {

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

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(NatsApi::class.java)

    /**
     * Create a NATS account for the authenticated user.
     */
    suspend fun createNatsAccount(authToken: String): Result<NatsAccount> {
        return safeApiCall {
            api.createNatsAccount("Bearer $authToken")
        }.map { response ->
            NatsAccount(
                ownerSpaceId = response.ownerSpaceId,
                messageSpaceId = response.messageSpaceId,
                natsEndpoint = response.natsEndpoint,
                status = NatsAccountStatus.fromString(response.status),
                createdAt = response.createdAt
            )
        }
    }

    /**
     * Generate a NATS token for connecting to the cluster.
     */
    suspend fun generateToken(
        authToken: String,
        clientType: NatsClientType,
        deviceId: String? = null
    ): Result<NatsCredentials> {
        val request = GenerateTokenRequest(
            clientType = clientType.toApiValue(),
            deviceId = deviceId
        )

        return safeApiCall {
            api.generateToken("Bearer $authToken", request)
        }.map { response ->
            NatsCredentials(
                tokenId = response.tokenId,
                jwt = response.natsJwt,
                seed = response.natsSeed,
                endpoint = response.natsEndpoint,
                expiresAt = Instant.parse(response.expiresAt),
                permissions = NatsPermissions(
                    publish = response.permissions.publish,
                    subscribe = response.permissions.subscribe
                )
            )
        }
    }

    /**
     * Get NATS status including account and active tokens.
     */
    suspend fun getNatsStatus(authToken: String): Result<NatsStatus> {
        return safeApiCall {
            api.getNatsStatus("Bearer $authToken")
        }.map { response ->
            NatsStatus(
                hasAccount = response.hasAccount,
                account = response.account?.let { acc ->
                    NatsAccount(
                        ownerSpaceId = acc.ownerSpaceId,
                        messageSpaceId = acc.messageSpaceId,
                        natsEndpoint = acc.natsEndpoint ?: "",
                        status = NatsAccountStatus.fromString(acc.status),
                        createdAt = acc.createdAt
                    )
                },
                activeTokens = response.activeTokens.map { token ->
                    NatsTokenInfo(
                        tokenId = token.tokenId,
                        clientType = NatsClientType.fromString(token.clientType),
                        deviceId = token.deviceId,
                        issuedAt = token.issuedAt,
                        expiresAt = token.expiresAt,
                        status = NatsTokenStatus.fromString(token.status)
                    )
                },
                natsEndpoint = response.natsEndpoint
            )
        }
    }

    /**
     * Revoke a NATS token.
     */
    suspend fun revokeToken(authToken: String, tokenId: String): Result<Unit> {
        return safeApiCall {
            api.revokeToken("Bearer $authToken", tokenId)
        }
    }

    private suspend fun <T> safeApiCall(call: suspend () -> Response<T>): Result<T> {
        return try {
            val response = call()
            if (response.isSuccessful) {
                response.body()?.let {
                    Result.success(it)
                } ?: Result.failure(NatsApiException("Empty response body"))
            } else {
                val errorBody = response.errorBody()?.string()
                Result.failure(NatsApiException(
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

interface NatsApi {
    @POST("vault/nats/account")
    suspend fun createNatsAccount(
        @Header("Authorization") authToken: String
    ): Response<CreateAccountResponse>

    @POST("vault/nats/token")
    suspend fun generateToken(
        @Header("Authorization") authToken: String,
        @Body request: GenerateTokenRequest
    ): Response<GenerateTokenResponse>

    @GET("vault/nats/status")
    suspend fun getNatsStatus(
        @Header("Authorization") authToken: String
    ): Response<NatsStatusResponse>

    @DELETE("vault/nats/token/{tokenId}")
    suspend fun revokeToken(
        @Header("Authorization") authToken: String,
        @Path("tokenId") tokenId: String
    ): Response<Unit>
}

// MARK: - Request Types

data class GenerateTokenRequest(
    @SerializedName("client_type") val clientType: String,
    @SerializedName("device_id") val deviceId: String? = null
)

// MARK: - Response Types

data class CreateAccountResponse(
    @SerializedName("owner_space_id") val ownerSpaceId: String,
    @SerializedName("message_space_id") val messageSpaceId: String,
    @SerializedName("nats_endpoint") val natsEndpoint: String,
    val status: String,
    @SerializedName("created_at") val createdAt: String? = null
)

data class GenerateTokenResponse(
    @SerializedName("token_id") val tokenId: String,
    @SerializedName("nats_jwt") val natsJwt: String,
    @SerializedName("nats_seed") val natsSeed: String,
    @SerializedName("nats_endpoint") val natsEndpoint: String,
    @SerializedName("expires_at") val expiresAt: String,
    val permissions: PermissionsResponse
)

data class PermissionsResponse(
    val publish: List<String>,
    val subscribe: List<String>
)

data class NatsStatusResponse(
    @SerializedName("has_account") val hasAccount: Boolean,
    val account: AccountResponse?,
    @SerializedName("active_tokens") val activeTokens: List<TokenInfoResponse>,
    @SerializedName("nats_endpoint") val natsEndpoint: String?
)

data class AccountResponse(
    @SerializedName("owner_space_id") val ownerSpaceId: String,
    @SerializedName("message_space_id") val messageSpaceId: String,
    @SerializedName("nats_endpoint") val natsEndpoint: String?,
    val status: String,
    @SerializedName("created_at") val createdAt: String?
)

data class TokenInfoResponse(
    @SerializedName("token_id") val tokenId: String,
    @SerializedName("client_type") val clientType: String,
    @SerializedName("device_id") val deviceId: String?,
    @SerializedName("issued_at") val issuedAt: String,
    @SerializedName("expires_at") val expiresAt: String,
    val status: String
)

// MARK: - Exception

class NatsApiException(
    message: String,
    val code: Int? = null,
    val errorBody: String? = null
) : Exception(message)
