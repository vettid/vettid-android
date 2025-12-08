package com.vettid.app.core.network

import com.google.gson.JsonObject
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
 * HTTP client for Handler Registry API
 *
 * Provides access to the handler marketplace:
 * - List available handlers (with category filtering)
 * - Get handler details (permissions, schema, metadata)
 */
@Singleton
class HandlerRegistryClient @Inject constructor() {

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
        .baseUrl(BASE_URL_DEV)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(HandlerRegistryApi::class.java)

    /**
     * List available handlers with optional category filter.
     */
    suspend fun listHandlers(
        category: String? = null,
        page: Int = 1,
        limit: Int = 20
    ): Result<HandlerListResponse> {
        return safeApiCall { api.listHandlers(category, page, limit) }
    }

    /**
     * Get detailed handler information.
     */
    suspend fun getHandler(handlerId: String): Result<HandlerDetailResponse> {
        return safeApiCall { api.getHandler(handlerId) }
    }

    /**
     * Search handlers by query.
     */
    suspend fun searchHandlers(
        query: String,
        page: Int = 1,
        limit: Int = 20
    ): Result<HandlerListResponse> {
        return safeApiCall { api.searchHandlers(query, page, limit) }
    }

    /**
     * Get featured handlers.
     */
    suspend fun getFeaturedHandlers(): Result<HandlerListResponse> {
        return safeApiCall { api.getFeaturedHandlers() }
    }

    /**
     * Get available categories.
     */
    suspend fun getCategories(): Result<CategoryListResponse> {
        return safeApiCall { api.getCategories() }
    }

    private suspend fun <T> safeApiCall(call: suspend () -> Response<T>): Result<T> {
        return try {
            val response = call()
            if (response.isSuccessful) {
                response.body()?.let {
                    Result.success(it)
                } ?: Result.failure(HandlerRegistryException("Empty response body"))
            } else {
                val errorBody = response.errorBody()?.string()
                Result.failure(HandlerRegistryException(
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

interface HandlerRegistryApi {
    @GET("registry/handlers")
    suspend fun listHandlers(
        @Query("category") category: String? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): Response<HandlerListResponse>

    @GET("registry/handlers/{id}")
    suspend fun getHandler(
        @Path("id") handlerId: String
    ): Response<HandlerDetailResponse>

    @GET("registry/handlers/search")
    suspend fun searchHandlers(
        @Query("q") query: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): Response<HandlerListResponse>

    @GET("registry/handlers/featured")
    suspend fun getFeaturedHandlers(): Response<HandlerListResponse>

    @GET("registry/categories")
    suspend fun getCategories(): Response<CategoryListResponse>
}

// MARK: - Response Types

data class HandlerListResponse(
    val handlers: List<HandlerSummary>,
    val total: Int,
    val page: Int,
    @SerializedName("has_more") val hasMore: Boolean
)

data class HandlerSummary(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val category: String,
    @SerializedName("icon_url") val iconUrl: String?,
    val publisher: String,
    val installed: Boolean = false,
    @SerializedName("installed_version") val installedVersion: String? = null,
    val rating: Float? = null,
    @SerializedName("install_count") val installCount: Int = 0
)

data class HandlerDetailResponse(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val category: String,
    @SerializedName("icon_url") val iconUrl: String?,
    val publisher: String,
    @SerializedName("published_at") val publishedAt: String,
    @SerializedName("size_bytes") val sizeBytes: Long,
    val permissions: List<HandlerPermission>,
    @SerializedName("input_schema") val inputSchema: JsonObject,
    @SerializedName("output_schema") val outputSchema: JsonObject,
    val changelog: String?,
    val installed: Boolean = false,
    @SerializedName("installed_version") val installedVersion: String? = null,
    val readme: String? = null,
    val screenshots: List<String>? = null,
    val rating: Float? = null,
    @SerializedName("rating_count") val ratingCount: Int = 0,
    @SerializedName("install_count") val installCount: Int = 0
)

data class HandlerPermission(
    val type: String, // "network", "storage", "crypto", "messaging"
    val scope: String, // e.g., "api.example.com" for network
    val description: String
)

data class CategoryListResponse(
    val categories: List<HandlerCategory>
)

data class HandlerCategory(
    val id: String,
    val name: String,
    val description: String?,
    @SerializedName("icon_name") val iconName: String?,
    val count: Int = 0
)

// MARK: - Exceptions

class HandlerRegistryException(
    message: String,
    val code: Int? = null,
    val errorBody: String? = null
) : Exception(message)
