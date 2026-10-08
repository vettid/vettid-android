package com.vettid.core.data.vault

import android.content.res.Resources
import androidx.annotation.StringRes
import com.vettid.core.data.R

/** The member-facing message of a failure (strings in this module, `data_failure_*`). */
@Suppress("CyclomaticComplexMethod") // one string per kind
@StringRes
fun FailureKind.messageRes(): Int = when (this) {
    FailureKind.NETWORK -> R.string.data_failure_network
    FailureKind.UNAUTHORIZED -> R.string.data_failure_unauthorized
    FailureKind.SETUP_CODE_INVALID -> R.string.data_failure_setup_code
    FailureKind.TERMS_REQUIRED -> R.string.data_failure_terms
    FailureKind.RATE_LIMITED -> R.string.data_failure_rate_limited
    FailureKind.VAULT_UNAVAILABLE -> R.string.data_failure_vault_unavailable
    FailureKind.SERVICE_PAUSED -> R.string.data_failure_service_paused
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
    FailureKind.IN_USE -> R.string.data_failure_in_use
    FailureKind.REJECTED -> R.string.data_failure_rejected
    FailureKind.OWNER_CHECK_REQUIRED -> R.string.data_failure_owner_check_required
    FailureKind.OTHER -> R.string.data_failure_other
}

/** A limit's member-facing text: [res] formatted with [args] (counts, or kilobytes for a size; at most two). */
data class LimitText(@param:StringRes val res: Int, val args: List<Long> = emptyList()) {
    fun format(resources: Resources): String = when (args.size) {
        0 -> resources.getString(res)
        1 -> resources.getString(res, args[0])
        else -> resources.getString(res, args[0], args[1])
    }
}

/** A named limit in the member's words ([text], formatted). */
fun VaultLimit.message(resources: Resources): String = text().format(resources)

/**
 * A `limit` error in the member's words (VAULT-MESSAGING 0.21.0 §10.1): one text per name of the spec's table, with
 * its bound ([VaultLimit.max]; kilobytes for a `*_size` limit, with the refused [VaultLimit.size] when given). A name
 * this app does not know, or a limit without its bound, reads as the generic "limit reached".
 */
@Suppress("CyclomaticComplexMethod") // one text per limit name
fun VaultLimit.text(): LimitText {
    val m = max ?: return LimitText(R.string.data_failure_limit)
    val kb = { b: Long -> (b + KB - 1) / KB }
    val sized = { withSize: Int, without: Int ->
        size?.let { LimitText(withSize, listOf(kb(it), kb(m))) } ?: LimitText(without, listOf(kb(m)))
    }
    val count = { res: Int -> LimitText(res, listOf(m)) }
    return when (name) {
        "items" -> count(R.string.data_limit_items)
        "critical_items" -> count(R.string.data_limit_critical_items)
        "item_size" -> sized(R.string.data_limit_item_size_of, R.string.data_limit_item_size)
        "credential_size" -> sized(R.string.data_limit_credential_size_of, R.string.data_limit_credential_size)
        "profile_items" -> count(R.string.data_limit_profile_items)
        "profile_size" -> sized(R.string.data_limit_profile_size_of, R.string.data_limit_profile_size)
        "tag_registry" -> count(R.string.data_limit_tag_registry)
        "app_settings" -> count(R.string.data_limit_app_settings)
        "share_rules_subject" -> count(R.string.data_limit_share_rules_subject)
        "share_rules" -> count(R.string.data_limit_share_rules)
        "share_pending" -> count(R.string.data_limit_share_pending)
        "grants_given" -> count(R.string.data_limit_grants_given)
        "grant_requests" -> count(R.string.data_limit_grant_requests)
        "catalog_requests" -> count(R.string.data_limit_catalog_requests)
        "grant_fetches" -> count(R.string.data_limit_grant_fetches)
        "held_approvals" -> count(R.string.data_limit_held_approvals)
        "connection_requests" -> count(R.string.data_limit_connection_requests)
        "blocks" -> count(R.string.data_limit_blocks)
        "auth_challenges" -> count(R.string.data_limit_auth_challenges)
        "agent_grants" -> count(R.string.data_limit_agent_grants)
        "critical_use_requests" -> count(R.string.data_limit_critical_use_requests)
        "action_invocations" -> count(R.string.data_limit_action_invocations)
        "introductions" -> count(R.string.data_limit_introductions)
        "location_shares" -> count(R.string.data_limit_location_shares)
        "location_requests" -> LimitText(R.string.data_limit_location_requests)
        "wallets" -> count(R.string.data_limit_wallets)
        "wallet_addresses" -> count(R.string.data_limit_wallet_addresses)
        else -> LimitText(R.string.data_failure_limit)
    }
}

/** The limit names of VAULT-MESSAGING 0.21.0 §10.1, as the vault sends them. */
val LIMIT_NAMES: List<String> = listOf(
    "items", "critical_items", "item_size", "credential_size", "profile_items", "profile_size", "tag_registry",
    "app_settings", "share_rules_subject", "share_rules", "share_pending", "grants_given", "grant_requests",
    "catalog_requests", "grant_fetches", "held_approvals", "connection_requests", "blocks", "auth_challenges",
    "agent_grants", "critical_use_requests", "action_invocations", "introductions", "location_shares",
    "location_requests", "wallets", "wallet_addresses",
)

private const val KB = 1024L
