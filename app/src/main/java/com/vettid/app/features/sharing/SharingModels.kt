package com.vettid.app.features.sharing

/**
 * One row in the peer-catalog list — sourced from the peer's
 * published data_catalog + secret_catalog. Status reflects the
 * local user's outstanding capability requests for the item.
 */
data class SharedItem(
    val key: String,            // "data:contact.email" / "secret:9d3f-..." / "wallet:bc1q..."
    val displayName: String,
    val category: String,
    val kind: Kind,
    val status: RequestStatus,
    val requestId: String?,     // last request id, used for retries / detail
) {
    enum class Kind { DATA, SECRET, WALLET }
}

enum class RequestStatus {
    AVAILABLE,    // never asked
    PENDING,      // asked; awaiting peer response
    APPROVED,     // peer approved
    DENIED,       // peer denied
    EXPIRED,      // capability expired
}

/**
 * One row in the my-sharing editor — represents a single decision
 * the local user has made about what THIS peer can request.
 */
data class SharePolicyRow(
    val key: String,             // "<kind>:<id>" matching the vault store
    val displayName: String,
    val category: String,
    val allowed: Boolean,
    val tier: String,            // "required" | "optional" | "on_demand" | "consent"
    val retention: String,       // "session" | "time_limited" | "until_revoked"
    val rateLimitPerHour: Int,   // 0 = unlimited
    val expiresAt: Long,         // unix; 0 = never
)
