package com.vettid.app.core.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.vettid.app.core.storage.CredentialStore
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
 * Client for vault lifecycle operations using action tokens.
 *
 * This client provides mobile-friendly vault lifecycle management without
 * requiring Cognito authentication. It uses the action token flow:
 *
 * 1. Request action token via /api/v1/action/request
 * 2. Execute action with action token in Bearer header
 *
 * Supported operations:
 * - Start vault: action_type = "vault_start"
 * - Stop vault: action_type = "vault_stop"
 * - Get status: action_type = "vault_status"
 */
@Singleton
class VaultLifecycleClient @Inject constructor(
    private val credentialStore: CredentialStore
) {
    companion object {
        private const val TAG = "VaultLifecycleClient"
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

    private val api: VaultLifecycleApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(VaultLifecycleApi::class.java)
    }

    /**
     * Start the user's vault EC2 instance.
     *
     * @return VaultStartResponse on success
     */
    suspend fun startVault(): Result<VaultStartResponse> {
        Log.i(TAG, "Starting vault")
        return executeVaultAction("vault_start") { actionToken ->
            api.startVault("Bearer $actionToken")
        }
    }

    /**
     * Stop the user's vault EC2 instance.
     *
     * @return VaultStopResponse on success
     */
    suspend fun stopVault(): Result<VaultStopResponse> {
        Log.i(TAG, "Stopping vault")
        return executeVaultAction("vault_stop") { actionToken ->
            api.stopVault("Bearer $actionToken")
        }
    }

    /**
     * Get the user's vault status.
     *
     * @return VaultLifecycleStatusResponse on success
     */
    suspend fun getVaultStatus(): Result<VaultLifecycleStatusResponse> {
        Log.i(TAG, "Getting vault status")
        return executeVaultAction("vault_status") { actionToken ->
            api.getVaultStatus("Bearer $actionToken")
        }
    }

    /**
     * Execute a vault lifecycle action using the action token flow.
     *
     * @param actionType The action type (vault_start, vault_stop, vault_status)
     * @param executeAction Lambda that executes the action with the token
     */
    private suspend fun <T> executeVaultAction(
        actionType: String,
        executeAction: suspend (String) -> Response<T>
    ): Result<T> {
        // Get user GUID from credential store
        val userGuid = credentialStore.getUserGuid()
            ?: return Result.failure(VaultLifecycleException("User not enrolled - no user GUID found"))

        Log.i(TAG, "Requesting action token for $actionType with userGuid=$userGuid")

        // Step 1: Request action token
        val tokenResult = requestActionToken(userGuid, actionType)
        if (tokenResult.isFailure) {
            return Result.failure(tokenResult.exceptionOrNull()!!)
        }

        val actionResponse = tokenResult.getOrThrow()
        val actionToken = actionResponse.actionToken

        Log.d(TAG, "Got action token, executing $actionType")

        // Step 2: Execute action with token
        return safeApiCall { executeAction(actionToken) }
    }

    /**
     * Request an action token for a vault lifecycle operation.
     */
    private suspend fun requestActionToken(
        userGuid: String,
        actionType: String
    ): Result<ActionTokenResponse> {
        val request = ActionTokenRequest(
            userGuid = userGuid,
            actionType = actionType
        )
        return safeApiCall { api.requestActionToken(request) }
    }

    private suspend fun <T> safeApiCall(call: suspend () -> Response<T>): Result<T> {
        return try {
            val response = call()
            if (response.isSuccessful) {
                response.body()?.let {
                    Result.success(it)
                } ?: Result.failure(VaultLifecycleException("Empty response body"))
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMessage = parseErrorMessage(errorBody) ?: response.message()
                Result.failure(VaultLifecycleException(
                    "HTTP ${response.code()}: $errorMessage",
                    code = response.code(),
                    errorBody = errorBody
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "API call failed", e)
            Result.failure(e)
        }
    }

    private fun parseErrorMessage(errorBody: String?): String? {
        if (errorBody.isNullOrBlank()) return null
        return try {
            val gson = Gson()
            val error = gson.fromJson(errorBody, ErrorResponse::class.java)
            error.message ?: error.error
        } catch (e: Exception) {
            null
        }
    }
}

// MARK: - Retrofit Interface

interface VaultLifecycleApi {
    /**
     * Request an action token for vault lifecycle operations.
     * This is a public endpoint that doesn't require Cognito auth.
     */
    @POST("api/v1/action/request")
    suspend fun requestActionToken(
        @Body request: ActionTokenRequest
    ): Response<ActionTokenResponse>

    /**
     * Start a stopped vault instance.
     * Requires action token with vault_start scope.
     */
    @POST("api/v1/vault/start")
    suspend fun startVault(
        @Header("Authorization") actionToken: String
    ): Response<VaultStartResponse>

    /**
     * Stop a running vault instance.
     * Requires action token with vault_stop scope.
     */
    @POST("api/v1/vault/stop")
    suspend fun stopVault(
        @Header("Authorization") actionToken: String
    ): Response<VaultStopResponse>

    /**
     * Get vault status.
     * Requires action token with vault_status scope.
     */
    @GET("api/v1/vault/status")
    suspend fun getVaultStatus(
        @Header("Authorization") actionToken: String
    ): Response<VaultLifecycleStatusResponse>
}

// MARK: - Request Types

data class ActionTokenRequest(
    @SerializedName("user_guid") val userGuid: String,
    @SerializedName("action_type") val actionType: String
)

// MARK: - Response Types

data class ActionTokenResponse(
    @SerializedName("action_token") val actionToken: String,
    @SerializedName("action_token_expires_at") val actionTokenExpiresAt: String? = null,
    @SerializedName("action_endpoint") val actionEndpoint: String? = null,
    @SerializedName("expires_in") val expiresIn: Int? = null
)

data class VaultStartResponse(
    val status: String, // "starting", "already_running", "failed"
    @SerializedName("instance_id") val instanceId: String? = null,
    val message: String? = null
) {
    val isStarting: Boolean get() = status == "starting"
    val isAlreadyRunning: Boolean get() = status == "already_running" || status == "running"
}

data class VaultStopResponse(
    val status: String, // "stopping", "already_stopped", "failed"
    @SerializedName("instance_id") val instanceId: String? = null,
    val message: String? = null
) {
    val isStopping: Boolean get() = status == "stopping"
    val isAlreadyStopped: Boolean get() = status == "already_stopped" || status == "stopped"
}

data class VaultLifecycleStatusResponse(
    @SerializedName("enrollment_status") val enrollmentStatus: String, // "active", "pending", etc.
    @SerializedName("user_guid") val userGuid: String? = null,
    @SerializedName("transaction_keys_remaining") val transactionKeysRemaining: Int? = null,
    @SerializedName("instance_status") val instanceStatus: String? = null, // "running", "stopped", "pending", etc.
    @SerializedName("instance_id") val instanceId: String? = null,
    @SerializedName("instance_ip") val instanceIp: String? = null,
    @SerializedName("nats_endpoint") val natsEndpoint: String? = null
) {
    val isVaultRunning: Boolean get() = instanceStatus == "running"
    val isVaultStopped: Boolean get() = instanceStatus == "stopped"
    val isVaultPending: Boolean get() = instanceStatus == "pending"
}

data class ErrorResponse(
    val message: String? = null,
    val error: String? = null
)

// MARK: - Exception

class VaultLifecycleException(
    message: String,
    val code: Int? = null,
    val errorBody: String? = null
) : Exception(message)
