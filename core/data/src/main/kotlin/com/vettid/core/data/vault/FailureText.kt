package com.vettid.core.data.vault

import androidx.annotation.StringRes
import com.vettid.core.data.R

/** The member-facing message of a failure (strings in this module, `data_failure_*`). */
@Suppress("CyclomaticComplexMethod") // one string per kind
@StringRes
fun FailureKind.messageRes(): Int = when (this) {
    FailureKind.NETWORK -> R.string.data_failure_network
    FailureKind.UNAUTHORIZED -> R.string.data_failure_unauthorized
    FailureKind.TERMS_REQUIRED -> R.string.data_failure_terms
    FailureKind.RATE_LIMITED -> R.string.data_failure_rate_limited
    FailureKind.VAULT_UNAVAILABLE -> R.string.data_failure_vault_unavailable
    FailureKind.RELEASE_ENDED -> R.string.data_failure_release_ended
    FailureKind.BAD_PIN -> R.string.data_failure_bad_pin
    FailureKind.BAD_PASSWORD -> R.string.data_failure_bad_password
    FailureKind.BACKOFF -> R.string.data_failure_backoff
    FailureKind.CREDENTIAL_FROZEN -> R.string.data_failure_frozen
    FailureKind.ROTATION_REQUIRED -> R.string.data_failure_rotation_required
    FailureKind.NOT_SUPPORTED -> R.string.data_failure_not_supported
    FailureKind.NO_RESPONSE -> R.string.data_failure_no_response
    FailureKind.VAULT_EXISTS -> R.string.data_failure_vault_exists
    FailureKind.ATTESTATION -> R.string.data_failure_attestation
    FailureKind.ROLLBACK -> R.string.data_failure_rollback
    FailureKind.MANIFEST -> R.string.data_failure_manifest
    FailureKind.NOT_FOUND -> R.string.data_failure_not_found
    FailureKind.CONNECTION_UNAVAILABLE -> R.string.data_failure_connection_unavailable
    FailureKind.INVITE_INVALID -> R.string.data_failure_invite_invalid
    FailureKind.INVITE_EXPIRED -> R.string.data_failure_invite_expired
    FailureKind.INVITE_NOT_CONNECTION -> R.string.data_failure_invite_not_connection
    FailureKind.INVITE_UNAVAILABLE -> R.string.data_failure_invite_unavailable
    FailureKind.BLOCKED -> R.string.data_failure_blocked
    FailureKind.CREDENTIAL_LOCKED -> R.string.data_failure_credential_locked
    FailureKind.CONFLICT -> R.string.data_failure_conflict
    FailureKind.LIMIT -> R.string.data_failure_limit
    FailureKind.OTHER -> R.string.data_failure_other
}
