package com.vettid.app.core.network

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
 * HTTP client for Connection API
 *
 * Handles connection management:
 * - Create and accept connection invitations
 * - List and view connection details
 * - Revoke connections
 */
@Singleton
class ConnectionApiClient @Inject constructor(
    private val credentialStore: com.vettid.app.core.storage.CredentialStore
) {

    companion object {
        private const val BASE_URL_PROD = "https://api.vettid.dev/"
        private const val BASE_URL_DEV = "https://api-dev.vettid.dev/"
        private const val TIMEOUT_SECONDS = 30L
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val token = credentialStore.getAuthToken()
            val request = if (token != null) {
                chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            } else {
                chain.request()
            }
            chain.proceed(request)
        }
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL_DEV)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(ConnectionApi::class.java)

    /**
     * Create a connection invitation.
     *
     * @param expiresInMinutes How long the invitation is valid (default 60 minutes)
     * @return Connection invitation with QR code data and deep link
     */
    suspend fun createInvitation(expiresInMinutes: Int = 60): Result<ConnectionInvitation> {
        return safeApiCall {
            api.createInvitation(CreateInvitationRequest(expiresInMinutes))
        }
    }

    /**
     * Accept a connection invitation.
     *
     * @param invitationCode The invitation code (from QR or deep link)
     * @param publicKey Base64-encoded X25519 public key for key exchange
     * @return The established connection and peer's public key
     */
    suspend fun acceptInvitation(
        invitationCode: String,
        publicKey: ByteArray
    ): Result<AcceptInvitationResponse> {
        val publicKeyBase64 = android.util.Base64.encodeToString(
            publicKey,
            android.util.Base64.NO_WRAP
        )
        return safeApiCall {
            api.acceptInvitation(AcceptInvitationRequest(invitationCode, publicKeyBase64))
        }
    }

    /**
     * Revoke a connection.
     *
     * @param connectionId The connection to revoke
     */
    suspend fun revokeConnection(connectionId: String): Result<Unit> {
        return safeApiCall {
            api.revokeConnection(RevokeConnectionRequest(connectionId))
        }
    }

    /**
     * List all connections.
     *
     * @param page Page number (1-indexed)
     * @param limit Number of connections per page
     * @return List of connections
     */
    suspend fun listConnections(
        page: Int = 1,
        limit: Int = 50
    ): Result<ConnectionListResponse> {
        return safeApiCall {
            api.listConnections(page, limit)
        }
    }

    /**
     * Get connection details.
     *
     * @param connectionId The connection ID
     * @return Connection details
     */
    suspend fun getConnection(connectionId: String): Result<Connection> {
        return safeApiCall {
            api.getConnection(connectionId)
        }
    }

    /**
     * Get a connection's profile.
     *
     * @param connectionId The connection ID
     * @return The peer's profile
     */
    suspend fun getConnectionProfile(connectionId: String): Result<Profile> {
        return safeApiCall {
            api.getConnectionProfile(connectionId)
        }
    }

    private suspend fun <T> safeApiCall(call: suspend () -> Response<T>): Result<T> {
        return try {
            val response = call()
            if (response.isSuccessful) {
                response.body()?.let {
                    Result.success(it)
                } ?: Result.failure(ConnectionApiException("Empty response body"))
            } else {
                val errorBody = response.errorBody()?.string()
                Result.failure(ConnectionApiException(
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

interface ConnectionApi {
    @POST("connections/invite")
    suspend fun createInvitation(
        @Body request: CreateInvitationRequest
    ): Response<ConnectionInvitation>

    @POST("connections/accept")
    suspend fun acceptInvitation(
        @Body request: AcceptInvitationRequest
    ): Response<AcceptInvitationResponse>

    @POST("connections/revoke")
    suspend fun revokeConnection(
        @Body request: RevokeConnectionRequest
    ): Response<Unit>

    @GET("connections")
    suspend fun listConnections(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50
    ): Response<ConnectionListResponse>

    @GET("connections/{id}")
    suspend fun getConnection(
        @Path("id") connectionId: String
    ): Response<Connection>

    @GET("connections/{id}/profile")
    suspend fun getConnectionProfile(
        @Path("id") connectionId: String
    ): Response<Profile>
}

// MARK: - Exceptions

class ConnectionApiException(
    message: String,
    val code: Int? = null,
    val errorBody: String? = null
) : Exception(message)
