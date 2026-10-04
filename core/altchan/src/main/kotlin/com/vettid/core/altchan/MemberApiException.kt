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
    val retryAfterSeconds: Int = 0,
) : IOException("member API $status $code${if (message.isEmpty()) "" else ": $message"}") {
    companion object {
        const val UNAUTHORIZED = "unauthorized"
        const val CSRF = "csrf"
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
    }
}
