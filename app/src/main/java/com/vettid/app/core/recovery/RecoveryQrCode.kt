package com.vettid.app.core.recovery

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/**
 * Recovery QR code content as displayed by the Account Portal.
 *
 * After the 24-hour security delay, the Account Portal displays a QR code
 * containing recovery information that the app scans to restore credentials.
 *
 * QR Code Format:
 * ```json
 * {
 *   "type": "vettid_recovery",
 *   "token": "recovery_token_here",
 *   "vault": "nats://vault.vettid.dev:4222",
 *   "nonce": "random_nonce",
 *   "owner_space": "user.abc123",
 *   "credentials": "-----BEGIN NATS USER JWT-----\n..."
 * }
 * ```
 */
data class RecoveryQrCode(
    val type: String,
    val token: String,
    val vault: String,
    val nonce: String,
    @SerializedName("owner_space") val ownerSpace: String,
    val credentials: String
) {
    companion object {
        private const val TAG = "RecoveryQrCode"
        private const val EXPECTED_TYPE = "vettid_recovery"
        private val gson = Gson()

        /**
         * Parse QR code content into RecoveryQrCode.
         *
         * @param content Raw QR code content (JSON string)
         * @return Parsed RecoveryQrCode or null if invalid
         */
        fun parse(content: String): RecoveryQrCode? {
            return try {
                val json = gson.fromJson(content, JsonObject::class.java)

                // Validate type
                val type = json.get("type")?.asString
                if (type != EXPECTED_TYPE) {
                    Log.w(TAG, "Invalid QR code type: $type (expected $EXPECTED_TYPE)")
                    return null
                }

                // Extract required fields
                val token = json.get("token")?.asString
                val vault = json.get("vault")?.asString
                val nonce = json.get("nonce")?.asString
                val ownerSpace = json.get("owner_space")?.asString
                val credentials = json.get("credentials")?.asString

                if (token.isNullOrBlank() || vault.isNullOrBlank() ||
                    nonce.isNullOrBlank() || ownerSpace.isNullOrBlank() ||
                    credentials.isNullOrBlank()
                ) {
                    Log.w(TAG, "Missing required fields in recovery QR code")
                    return null
                }

                RecoveryQrCode(
                    type = type,
                    token = token,
                    vault = vault,
                    nonce = nonce,
                    ownerSpace = ownerSpace,
                    credentials = credentials
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse recovery QR code", e)
                null
            }
        }

        /**
         * Validate if a string looks like a VettID recovery QR code.
         * Quick check before full parsing.
         */
        fun isRecoveryQrCode(content: String): Boolean {
            return content.contains("\"type\"") &&
                    content.contains("\"vettid_recovery\"") &&
                    content.contains("\"token\"")
        }
    }

    /**
     * Extract NATS endpoint from vault URL.
     * Converts "nats://vault.vettid.dev:4222" to "vault.vettid.dev:4222"
     */
    fun getNatsEndpoint(): String {
        return vault.removePrefix("nats://").removePrefix("tls://")
    }

    /**
     * Get the topic for recovery request.
     * Format: {ownerSpace}.forVault.recovery.claim
     */
    fun getRecoveryTopic(): String {
        return "$ownerSpace.forVault.recovery.claim"
    }

    /**
     * Get the topic for recovery response.
     * Format: {ownerSpace}.forApp.recovery.result
     */
    fun getResponseTopic(): String {
        return "$ownerSpace.forApp.recovery.result"
    }
}

/**
 * Result of recovery token exchange via NATS.
 */
data class RecoveryExchangeResult(
    val success: Boolean,
    val message: String,
    val credentials: String? = null,
    @SerializedName("nats_endpoint") val natsEndpoint: String? = null,
    @SerializedName("owner_space") val ownerSpace: String? = null,
    @SerializedName("message_space") val messageSpace: String? = null,
    @SerializedName("credential_id") val credentialId: String? = null,
    @SerializedName("user_guid") val userGuid: String? = null,
    @SerializedName("credential_version") val credentialVersion: Int? = null,
    @SerializedName("sealed_credential") val sealedCredential: String? = null
) {
    companion object {
        fun fromJson(json: JsonObject): RecoveryExchangeResult {
            return RecoveryExchangeResult(
                success = json.get("success")?.asBoolean ?: false,
                message = json.get("message")?.asString ?: "",
                credentials = json.get("credentials")?.asString,
                natsEndpoint = json.get("nats_endpoint")?.asString,
                ownerSpace = json.get("owner_space")?.asString,
                messageSpace = json.get("message_space")?.asString,
                credentialId = json.get("credential_id")?.asString,
                userGuid = json.get("user_guid")?.asString,
                credentialVersion = json.get("credential_version")?.asInt,
                sealedCredential = json.get("sealed_credential")?.asString
            )
        }
    }
}
