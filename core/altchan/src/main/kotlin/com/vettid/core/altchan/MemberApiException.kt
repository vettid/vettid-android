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

        /**
         * Made by the client, never sent by the API: a `401` whose `Date` header is more than [CLOCK_SKEW_S] away
         * from the phone's clock. The API refuses a signature whose `ts` is not within 300 s of its own time with a
         * plain `401`, so this is the likely cause, not a key the vault no longer has (A6 audit N4, N5).
         */
        const val CLOCK_SKEW = "clock_skew"

        /** Below the API's 300 s, so that a request signed a moment before the answer's `Date` is not missed. */
        const val CLOCK_SKEW_S = 240L
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

        /** 2.1.0: the vault keeps no backup copy of its credential and cannot be recovered (`reason: "no_backup"`). */
        const val RECOVERY_UNAVAILABLE = "recovery_unavailable"
        const val RATE_LIMITED = "rate_limited"
        const val MANIFEST_UNAVAILABLE = "manifest_unavailable"

        /** `service` of `vault_unavailable` and of `GET /api/vault/status` while the vault service is paused. */
        const val SERVICE_PAUSED = "paused"
    }
}
