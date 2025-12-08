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
 * HTTP client for Profile API
 *
 * Handles profile management:
 * - Get own profile
 * - Update profile
 * - Publish profile to connections
 */
@Singleton
class ProfileApiClient @Inject constructor(
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

    private val api = retrofit.create(ProfileApi::class.java)

    /**
     * Get own profile.
     *
     * @return The user's profile
     */
    suspend fun getProfile(): Result<Profile> {
        return safeApiCall {
            api.getProfile()
        }
    }

    /**
     * Update own profile.
     *
     * @param displayName Display name (required)
     * @param avatarUrl Avatar URL (optional)
     * @param bio Bio text (optional)
     * @param location Location (optional)
     * @return Updated profile
     */
    suspend fun updateProfile(
        displayName: String,
        avatarUrl: String? = null,
        bio: String? = null,
        location: String? = null
    ): Result<Profile> {
        return safeApiCall {
            api.updateProfile(ProfileUpdateRequest(displayName, avatarUrl, bio, location))
        }
    }

    /**
     * Update own profile with a Profile object.
     *
     * @param profile Profile object with updated fields
     * @return Updated profile
     */
    suspend fun updateProfile(profile: Profile): Result<Profile> {
        return updateProfile(
            displayName = profile.displayName,
            avatarUrl = profile.avatarUrl,
            bio = profile.bio,
            location = profile.location
        )
    }

    /**
     * Publish profile to all connections.
     *
     * This sends profile updates to all active connections.
     */
    suspend fun publishProfile(): Result<Unit> {
        return safeApiCall {
            api.publishProfile()
        }
    }

    private suspend fun <T> safeApiCall(call: suspend () -> Response<T>): Result<T> {
        return try {
            val response = call()
            if (response.isSuccessful) {
                response.body()?.let {
                    Result.success(it)
                } ?: Result.failure(ProfileApiException("Empty response body"))
            } else {
                val errorBody = response.errorBody()?.string()
                Result.failure(ProfileApiException(
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

interface ProfileApi {
    @GET("profile")
    suspend fun getProfile(): Response<Profile>

    @PUT("profile")
    suspend fun updateProfile(
        @Body request: ProfileUpdateRequest
    ): Response<Profile>

    @POST("profile/publish")
    suspend fun publishProfile(): Response<Unit>
}

// MARK: - Exceptions

class ProfileApiException(
    message: String,
    val code: Int? = null,
    val errorBody: String? = null
) : Exception(message)
