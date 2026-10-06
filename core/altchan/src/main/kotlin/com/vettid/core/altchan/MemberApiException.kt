package com.vettid.core.altchan

import java.io.IOException

/**
 * A member API error (MEMBER-API "Conventions", "Vault errors"): [code] is
 * the `error` member (the vault routes repeat it as `code`).
 */
class MemberApiException(
    val status: Int,
    val code: String,
    message: String = "",
    /** `release_starting`: the release being started. */
    val release: String? = null,
    /** `retry_after` from the body, else the `Retry-After` header (seconds; 0 when absent). */
    val retryAfterSeconds: Int = 0,
    /** `vault_unavailable`: the body's `service` (1.2.0: `"paused"` while the operator paused the vault service). */
    val service: String? = null,
) : IOException("member API $status $code${if (message.isEmpty()) "" else ": $message"}") {
    /**
     * MEMBER-API 1.2.0 "Vault service pause": the operator paused the vault service. Temporary: try again
     * after [retryAfterSeconds]. The operator's reason is never sent to members (and never read here).
     */
    val servicePaused: Boolean get() = code == VAULT_UNAVAILABLE && service == SERVICE_PAUSED

    companion object {
        const val UNAUTHORIZED = "unauthorized"
        const val INVALID_CODE = "invalid_code"
        const val TERMS_REQUIRED = "terms_required"
        const val NOT_FOUND = "not_found"
        const val INSTANCE_MOVED = "instance_moved"
        const val VAULT_BUSY = "vault_busy"
        const val DUPLICATE_REQUEST = "duplicate_request"
        const val RELEASE_UNAVAILABLE = "release_unavailable"
        const val RELEASE_STARTING = "release_starting"
        const val VAULT_UNAVAILABLE = "vault_unavailable"
        const val RECOVERY_ACTIVE = "recovery_active"
        const val RECOVERY_NOT_AVAILABLE = "recovery_not_available"
        const val RATE_LIMITED = "rate_limited"
        const val MANIFEST_UNAVAILABLE = "manifest_unavailable"

        /** `service` of `vault_unavailable` and of `GET /api/vault/status` while the vault service is paused. */
        const val SERVICE_PAUSED = "paused"
    }
}
