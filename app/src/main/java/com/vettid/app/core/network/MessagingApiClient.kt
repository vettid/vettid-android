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
 * HTTP client for Messaging API
 *
 * Handles encrypted messaging:
 * - Send encrypted messages
 * - Get message history
 * - Get unread counts
 * - Mark messages as read
 */
@Singleton
class MessagingApiClient @Inject constructor(
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

    private val api = retrofit.create(MessagingApi::class.java)

    /**
     * Send an encrypted message.
     *
     * @param connectionId The connection to send to
     * @param encryptedContent Base64-encoded encrypted content
     * @param nonce Base64-encoded nonce
     * @param contentType Message content type (default: TEXT)
     * @return The sent message with server metadata
     */
    suspend fun sendMessage(
        connectionId: String,
        encryptedContent: ByteArray,
        nonce: ByteArray,
        contentType: MessageContentType = MessageContentType.TEXT
    ): Result<Message> {
        val encryptedBase64 = android.util.Base64.encodeToString(
            encryptedContent,
            android.util.Base64.NO_WRAP
        )
        val nonceBase64 = android.util.Base64.encodeToString(
            nonce,
            android.util.Base64.NO_WRAP
        )
        return safeApiCall {
            api.sendMessage(SendMessageRequest(connectionId, encryptedBase64, nonceBase64, contentType))
        }.map { it.message }
    }

    /**
     * Get message history for a connection.
     *
     * @param connectionId The connection ID
     * @param limit Number of messages to fetch (default 50)
     * @param before Cursor for pagination (fetch messages before this cursor)
     * @return Message history response with pagination info
     */
    suspend fun getMessageHistory(
        connectionId: String,
        limit: Int = 50,
        before: String? = null
    ): Result<MessageHistoryResponse> {
        return safeApiCall {
            api.getMessageHistory(connectionId, limit, before)
        }
    }

    /**
     * Get unread message counts for all connections.
     *
     * @return Map of connection ID to unread count
     */
    suspend fun getUnreadCount(): Result<Map<String, Int>> {
        return safeApiCall {
            api.getUnreadCount()
        }.map { it.unreadCounts }
    }

    /**
     * Get total unread message count.
     *
     * @return Total unread count across all connections
     */
    suspend fun getTotalUnreadCount(): Result<Int> {
        return safeApiCall {
            api.getUnreadCount()
        }.map { it.total }
    }

    /**
     * Mark a message as read.
     *
     * @param messageId The message ID to mark as read
     */
    suspend fun markAsRead(messageId: String): Result<Unit> {
        return safeApiCall {
            api.markAsRead(messageId, MarkAsReadRequest(messageId))
        }
    }

    /**
     * Mark all messages in a conversation as read up to a certain message.
     *
     * @param connectionId The connection ID
     * @param upToMessageId Mark all messages up to and including this message as read
     */
    suspend fun markAllAsRead(
        connectionId: String,
        upToMessageId: String
    ): Result<Unit> {
        return safeApiCall {
            api.markAllAsRead(connectionId, MarkAllAsReadRequest(upToMessageId))
        }
    }

    private suspend fun <T> safeApiCall(call: suspend () -> Response<T>): Result<T> {
        return try {
            val response = call()
            if (response.isSuccessful) {
                response.body()?.let {
                    Result.success(it)
                } ?: Result.failure(MessagingApiException("Empty response body"))
            } else {
                val errorBody = response.errorBody()?.string()
                Result.failure(MessagingApiException(
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

interface MessagingApi {
    @POST("messages/send")
    suspend fun sendMessage(
        @Body request: SendMessageRequest
    ): Response<SendMessageResponse>

    @GET("messages/{connectionId}")
    suspend fun getMessageHistory(
        @Path("connectionId") connectionId: String,
        @Query("limit") limit: Int = 50,
        @Query("before") before: String? = null
    ): Response<MessageHistoryResponse>

    @GET("messages/unread")
    suspend fun getUnreadCount(): Response<UnreadCountResponse>

    @POST("messages/{id}/read")
    suspend fun markAsRead(
        @Path("id") messageId: String,
        @Body request: MarkAsReadRequest
    ): Response<Unit>

    @POST("messages/{connectionId}/read-all")
    suspend fun markAllAsRead(
        @Path("connectionId") connectionId: String,
        @Body request: MarkAllAsReadRequest
    ): Response<Unit>
}

// MARK: - Additional Request Types

data class MarkAllAsReadRequest(
    @com.google.gson.annotations.SerializedName("up_to_message_id") val upToMessageId: String
)

// MARK: - Exceptions

class MessagingApiException(
    message: String,
    val code: Int? = null,
    val errorBody: String? = null
) : Exception(message)
