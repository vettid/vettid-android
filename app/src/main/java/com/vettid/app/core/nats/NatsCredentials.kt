package com.vettid.app.core.nats

import java.time.Instant

/**
 * NATS authentication credentials received from the backend.
 *
 * The mobile app uses these credentials to connect to the NATS cluster
 * and communicate with its vault instance.
 */
data class NatsCredentials(
    val tokenId: String,
    val jwt: String,
    val seed: String,
    val endpoint: String,
    val expiresAt: Instant,
    val permissions: NatsPermissions
) {
    /**
     * Check if credentials are expired or about to expire.
     *
     * @param bufferMinutes Minutes before expiration to consider credentials stale
     * @return true if credentials need refresh
     */
    fun needsRefresh(bufferMinutes: Long = 60): Boolean {
        val threshold = Instant.now().plusSeconds(bufferMinutes * 60)
        return expiresAt.isBefore(threshold)
    }

    /**
     * Check if credentials are completely expired.
     */
    fun isExpired(): Boolean {
        return expiresAt.isBefore(Instant.now())
    }
}

/**
 * NATS permissions defining publish and subscribe capabilities.
 */
data class NatsPermissions(
    val publish: List<String>,
    val subscribe: List<String>
)

/**
 * NATS account information for a member.
 */
data class NatsAccount(
    val ownerSpaceId: String,
    val messageSpaceId: String,
    val natsEndpoint: String,
    val status: NatsAccountStatus,
    val createdAt: String? = null
)

/**
 * Status of a NATS account.
 */
enum class NatsAccountStatus {
    ACTIVE,
    SUSPENDED,
    TERMINATED;

    companion object {
        fun fromString(value: String): NatsAccountStatus {
            return when (value.lowercase()) {
                "active" -> ACTIVE
                "suspended" -> SUSPENDED
                "terminated" -> TERMINATED
                else -> ACTIVE
            }
        }
    }
}

/**
 * Token information for tracking active NATS tokens.
 */
data class NatsTokenInfo(
    val tokenId: String,
    val clientType: NatsClientType,
    val deviceId: String?,
    val issuedAt: String,
    val expiresAt: String,
    val status: NatsTokenStatus
)

/**
 * Type of NATS client.
 */
enum class NatsClientType {
    APP,
    VAULT;

    fun toApiValue(): String = name.lowercase()

    companion object {
        fun fromString(value: String): NatsClientType {
            return when (value.lowercase()) {
                "app" -> APP
                "vault" -> VAULT
                else -> APP
            }
        }
    }
}

/**
 * Status of a NATS token.
 */
enum class NatsTokenStatus {
    ACTIVE,
    REVOKED,
    EXPIRED;

    companion object {
        fun fromString(value: String): NatsTokenStatus {
            return when (value.lowercase()) {
                "active" -> ACTIVE
                "revoked" -> REVOKED
                "expired" -> EXPIRED
                else -> ACTIVE
            }
        }
    }
}

/**
 * Full NATS status response from the API.
 */
data class NatsStatus(
    val hasAccount: Boolean,
    val account: NatsAccount?,
    val activeTokens: List<NatsTokenInfo>,
    val natsEndpoint: String?
)
