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
 * HTTP client for Vault Handler Management API
 *
 * Manages handlers on the user's vault:
 * - Install/uninstall handlers
 * - List installed handlers
 * - Execute handlers with input
 */
@Singleton
class VaultHandlerClient @Inject constructor() {

    companion object {
        private const val BASE_URL_PROD = "https://api.vettid.dev/"
        private const val BASE_URL_DEV = "https://api-dev.vettid.dev/"
        private const val TIMEOUT_SECONDS = 60L // Longer timeout for handler execution
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

    private val api = retrofit.create(VaultHandlerApi::class.java)

    /**
     * Install a handler on the vault.
     */
    suspend fun installHandler(
        authToken: String,
        handlerId: String,
        version: String
    ): Result<InstallHandlerResponse> {
        val request = InstallHandlerRequest(handlerId = handlerId, version = version)
        return safeApiCall { api.installHandler("Bearer $authToken", request) }
    }

    /**
     * Uninstall a handler from the vault.
     */
    suspend fun uninstallHandler(
        authToken: String,
        handlerId: String
    ): Result<UninstallHandlerResponse> {
        val request = UninstallHandlerRequest(handlerId = handlerId)
        return safeApiCall { api.uninstallHandler("Bearer $authToken", request) }
    }

    /**
     * List all installed handlers on the vault.
     */
    suspend fun listInstalledHandlers(
        authToken: String
    ): Result<InstalledHandlersResponse> {
        return safeApiCall { api.listInstalledHandlers("Bearer $authToken") }
    }

    /**
     * Execute a handler with the given input.
     */
    suspend fun executeHandler(
        authToken: String,
        handlerId: String,
        input: JsonObject,
        timeoutMs: Long = 30000
    ): Result<ExecuteHandlerResponse> {
        val request = ExecuteHandlerRequest(input = input, timeoutMs = timeoutMs)
        return safeApiCall { api.executeHandler("Bearer $authToken", handlerId, request) }
    }

    /**
     * Get handler execution history.
     */
    suspend fun getExecutionHistory(
        authToken: String,
        handlerId: String,
        limit: Int = 20
    ): Result<ExecutionHistoryResponse> {
        return safeApiCall { api.getExecutionHistory("Bearer $authToken", handlerId, limit) }
    }

    /**
     * Get handler configuration (if configurable).
     */
    suspend fun getHandlerConfig(
        authToken: String,
        handlerId: String
    ): Result<HandlerConfigResponse> {
        return safeApiCall { api.getHandlerConfig("Bearer $authToken", handlerId) }
    }

    /**
     * Update handler configuration.
     */
    suspend fun updateHandlerConfig(
        authToken: String,
        handlerId: String,
        config: JsonObject
    ): Result<HandlerConfigResponse> {
        return safeApiCall { api.updateHandlerConfig("Bearer $authToken", handlerId, config) }
    }

    private suspend fun <T> safeApiCall(call: suspend () -> Response<T>): Result<T> {
        return try {
            val response = call()
            if (response.isSuccessful) {
                response.body()?.let {
                    Result.success(it)
                } ?: Result.failure(VaultHandlerException("Empty response body"))
            } else {
                val errorBody = response.errorBody()?.string()
                Result.failure(VaultHandlerException(
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

interface VaultHandlerApi {
    @POST("vault/handlers/install")
    suspend fun installHandler(
        @Header("Authorization") authToken: String,
        @Body request: InstallHandlerRequest
    ): Response<InstallHandlerResponse>

    @POST("vault/handlers/uninstall")
    suspend fun uninstallHandler(
        @Header("Authorization") authToken: String,
        @Body request: UninstallHandlerRequest
    ): Response<UninstallHandlerResponse>

    @GET("vault/handlers")
    suspend fun listInstalledHandlers(
        @Header("Authorization") authToken: String
    ): Response<InstalledHandlersResponse>

    @POST("vault/handlers/{id}/execute")
    suspend fun executeHandler(
        @Header("Authorization") authToken: String,
        @Path("id") handlerId: String,
        @Body request: ExecuteHandlerRequest
    ): Response<ExecuteHandlerResponse>

    @GET("vault/handlers/{id}/history")
    suspend fun getExecutionHistory(
        @Header("Authorization") authToken: String,
        @Path("id") handlerId: String,
        @Query("limit") limit: Int = 20
    ): Response<ExecutionHistoryResponse>

    @GET("vault/handlers/{id}/config")
    suspend fun getHandlerConfig(
        @Header("Authorization") authToken: String,
        @Path("id") handlerId: String
    ): Response<HandlerConfigResponse>

    @PUT("vault/handlers/{id}/config")
    suspend fun updateHandlerConfig(
        @Header("Authorization") authToken: String,
        @Path("id") handlerId: String,
        @Body config: JsonObject
    ): Response<HandlerConfigResponse>
}

// MARK: - Request Types

data class InstallHandlerRequest(
    @SerializedName("handler_id") val handlerId: String,
    val version: String
)

data class UninstallHandlerRequest(
    @SerializedName("handler_id") val handlerId: String
)

data class ExecuteHandlerRequest(
    val input: JsonObject,
    @SerializedName("timeout_ms") val timeoutMs: Long = 30000
)

// MARK: - Response Types

data class InstallHandlerResponse(
    val status: String, // "installed", "failed", "pending"
    @SerializedName("handler_id") val handlerId: String,
    val version: String,
    @SerializedName("installed_at") val installedAt: String?,
    val error: String? = null
)

data class UninstallHandlerResponse(
    val status: String, // "uninstalled", "failed"
    @SerializedName("handler_id") val handlerId: String,
    @SerializedName("uninstalled_at") val uninstalledAt: String?,
    val error: String? = null
)

data class InstalledHandlersResponse(
    val handlers: List<InstalledHandler>
)

data class InstalledHandler(
    val id: String,
    val name: String,
    val version: String,
    val category: String,
    @SerializedName("icon_url") val iconUrl: String?,
    @SerializedName("installed_at") val installedAt: String,
    @SerializedName("last_executed_at") val lastExecutedAt: String?,
    @SerializedName("execution_count") val executionCount: Int = 0,
    val enabled: Boolean = true,
    @SerializedName("has_update") val hasUpdate: Boolean = false,
    @SerializedName("latest_version") val latestVersion: String? = null
)

data class ExecuteHandlerResponse(
    @SerializedName("request_id") val requestId: String,
    val status: String, // "success", "error", "timeout"
    val output: JsonObject?,
    val error: String?,
    @SerializedName("execution_time_ms") val executionTimeMs: Long
)

data class ExecutionHistoryResponse(
    val executions: List<ExecutionRecord>
)

data class ExecutionRecord(
    @SerializedName("request_id") val requestId: String,
    @SerializedName("executed_at") val executedAt: String,
    val status: String,
    @SerializedName("execution_time_ms") val executionTimeMs: Long,
    val error: String? = null
)

data class HandlerConfigResponse(
    @SerializedName("handler_id") val handlerId: String,
    val config: JsonObject,
    @SerializedName("config_schema") val configSchema: JsonObject?,
    @SerializedName("updated_at") val updatedAt: String?
)

// MARK: - Exceptions

class VaultHandlerException(
    message: String,
    val code: Int? = null,
    val errorBody: String? = null
) : Exception(message)
