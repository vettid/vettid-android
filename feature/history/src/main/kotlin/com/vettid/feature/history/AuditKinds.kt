package com.vettid.feature.history

import androidx.annotation.StringRes
import com.vettid.core.data.vault.AuditCategory

/**
 * Member-facing text of the audit log's kinds (VAULT-MESSAGING §10.9): one title per kind the spec names, the
 * `drop.<reason>` family ("Message blocked", with the reason), the hourly `<kind>.summary` entries of agents
 * (§10.11), and a generic title for a kind this app does not know (a newer vault's), which shows the kind itself.
 */
object AuditKinds {
    /** The kinds whose `ref` is an `item_id` (VAULT-MESSAGING §10.9: the vault's search reads the item's name for them). */
    val ITEM_REF_KINDS = setOf(
        "item.added", "item.updated", "item.deleted", "item.sensitivity_changed", "item.revealed",
        "share.included", "share.declined", "share.withdrawn", "leash.item.read", "leash.item.used",
    )

    /** The item an entry refers to, if any. */
    fun itemOf(kind: String, ref: String?): String? = ref?.takeIf { kind in ITEM_REF_KINDS && it.isNotEmpty() }
    /** How [kind] reads: a known title, a known title as an hourly summary, a dropped message, or unknown. */
    sealed interface Title {
        data class Known(@param:StringRes val res: Int) : Title

        data class Summary(@param:StringRes val res: Int) : Title

        /** `drop.<reason>`: [reason] with its underscores as spaces. */
        data class Dropped(val reason: String) : Title

        data class Unknown(val kind: String) : Title
    }

    @Suppress("ReturnCount")
    fun title(kind: String): Title {
        TITLES[kind]?.let { return Title.Known(it) }
        if (kind.endsWith(SUMMARY)) TITLES[kind.removeSuffix(SUMMARY)]?.let { return Title.Summary(it) }
        if (kind.startsWith(DROP) && kind.length > DROP.length) return Title.Dropped(kind.removePrefix(DROP).replace('_', ' '))
        return Title.Unknown(kind)
    }

    /** The filter chip label of each category (ANDROID-PLAN 0.1.11). */
    @StringRes
    fun categoryLabel(c: AuditCategory): Int = when (c) {
        AuditCategory.VAULT_ACCESS -> R.string.history_category_vault_access
        AuditCategory.SECURITY -> R.string.history_category_security
        AuditCategory.DEVICES -> R.string.history_category_devices
        AuditCategory.CONNECTIONS -> R.string.history_category_connections
        AuditCategory.MESSAGES -> R.string.history_category_messages
        AuditCategory.ITEMS -> R.string.history_category_items
        AuditCategory.AGENTS -> R.string.history_category_agents
        AuditCategory.LOCATION -> R.string.history_category_location
        AuditCategory.ACCOUNT -> R.string.history_category_account
        AuditCategory.DROPPED -> R.string.history_category_dropped
        AuditCategory.OTHER -> R.string.history_category_other
    }

    private const val SUMMARY = ".summary"
    private const val DROP = "drop."

    internal val TITLES: Map<String, Int> = mapOf(
        "vault.unlocked" to R.string.history_kind_vault_unlocked,
        "vault.locked" to R.string.history_kind_vault_locked,
        "vault.pin_failed" to R.string.history_kind_vault_pin_failed,
        "owner_check.passed" to R.string.history_kind_owner_check_passed,
        "owner_check.held" to R.string.history_kind_owner_check_held,
        "owner_check.failed" to R.string.history_kind_owner_check_failed,
        "owner_check.locked" to R.string.history_kind_owner_check_locked,
        "owner_check.hold_changed" to R.string.history_kind_owner_check_hold_changed,
        "device.paired" to R.string.history_kind_device_paired,
        "device.unlinked" to R.string.history_kind_device_unlinked,
        "device.transfer.started" to R.string.history_kind_device_transfer_started,
        "device.transfer.approved" to R.string.history_kind_device_transfer_approved,
        "device.transferred" to R.string.history_kind_device_transferred,
        "device.transfer.aborted" to R.string.history_kind_device_transfer_aborted,
        "device.transfer.attestation_failed" to R.string.history_kind_device_transfer_attestation_failed,
        "device.replaced" to R.string.history_kind_device_replaced,
        "device.session.granted" to R.string.history_kind_device_session_granted,
        "device.session.ended" to R.string.history_kind_device_session_ended,
        "approval.granted" to R.string.history_kind_approval_granted,
        "approval.denied" to R.string.history_kind_approval_denied,
        "connection.added" to R.string.history_kind_connection_added,
        "connection.removed" to R.string.history_kind_connection_removed,
        "connection.stale" to R.string.history_kind_connection_stale,
        "connection.reconnected" to R.string.history_kind_connection_reconnected,
        "connection.blocked" to R.string.history_kind_connection_blocked,
        "connection.unblocked" to R.string.history_kind_connection_unblocked,
        "connection.request.peer_declined" to R.string.history_kind_connection_request_peer_declined,
        "connection.authenticate.requested" to R.string.history_kind_connection_authenticate_requested,
        "connection.authenticate.signed" to R.string.history_kind_connection_authenticate_signed,
        "connection.authenticate.denied" to R.string.history_kind_connection_authenticate_denied,
        "connection.authenticated" to R.string.history_kind_connection_authenticated,
        "connection.authenticate_failed" to R.string.history_kind_connection_authenticate_failed,
        "connection.authenticate.key_rotated" to R.string.history_kind_connection_authenticate_key_rotated,
        "connection.authenticate.rotation_rejected" to R.string.history_kind_connection_authenticate_rotation_rejected,
        "identity.rotated" to R.string.history_kind_identity_rotated,
        "intro.created" to R.string.history_kind_intro_created,
        "intro.offered" to R.string.history_kind_intro_offered,
        "intro.accepted" to R.string.history_kind_intro_accepted,
        "intro.declined" to R.string.history_kind_intro_declined,
        "intro.connecting" to R.string.history_kind_intro_connecting,
        "intro.closed" to R.string.history_kind_intro_closed,
        "credential.created" to R.string.history_kind_credential_created,
        "credential.rotated" to R.string.history_kind_credential_rotated,
        "credential.password_changed" to R.string.history_kind_credential_password_changed,
        "credential.password_failed" to R.string.history_kind_credential_password_failed,
        "credential.unlocked" to R.string.history_kind_credential_unlocked,
        "credential.recovered" to R.string.history_kind_credential_recovered,
        "credential.clone_detected" to R.string.history_kind_credential_clone_detected,
        "credential.alarm.confirmed" to R.string.history_kind_credential_alarm_confirmed,
        "credential.alarm.resolved" to R.string.history_kind_credential_alarm_resolved,
        "credential.reset" to R.string.history_kind_credential_reset,
        "recovery.requested" to R.string.history_kind_recovery_requested,
        "recovery.replaced" to R.string.history_kind_recovery_replaced,
        "recovery.bad_code" to R.string.history_kind_recovery_bad_code,
        "recovery.attestation_failed" to R.string.history_kind_recovery_attestation_failed,
        "recovery.registered" to R.string.history_kind_recovery_registered,
        "recovery.device_paired" to R.string.history_kind_recovery_device_paired,
        "recovery.completed" to R.string.history_kind_recovery_completed,
        "recovery.cancelled" to R.string.history_kind_recovery_cancelled,
        "recovery.expired" to R.string.history_kind_recovery_expired,
        "recovery.voided" to R.string.history_kind_recovery_voided,
        "item.added" to R.string.history_kind_item_added,
        "item.updated" to R.string.history_kind_item_updated,
        "item.deleted" to R.string.history_kind_item_deleted,
        "item.sensitivity_changed" to R.string.history_kind_item_sensitivity_changed,
        "item.revealed" to R.string.history_kind_item_revealed,
        "tag.changed" to R.string.history_kind_tag_changed,
        "share.rule.created" to R.string.history_kind_share_rule_created,
        "share.rule.updated" to R.string.history_kind_share_rule_updated,
        "share.rule.deleted" to R.string.history_kind_share_rule_deleted,
        "share.included" to R.string.history_kind_share_included,
        "share.declined" to R.string.history_kind_share_declined,
        "share.withdrawn" to R.string.history_kind_share_withdrawn,
        "grant.requested" to R.string.history_kind_grant_requested,
        "grant.denied" to R.string.history_kind_grant_denied,
        "grant.issued" to R.string.history_kind_grant_issued,
        "grant.received" to R.string.history_kind_grant_received,
        "grant.fetched" to R.string.history_kind_grant_fetched,
        "grant.revoked" to R.string.history_kind_grant_revoked,
        "critical-secret.use.requested" to R.string.history_kind_critical_secret_use_requested,
        "critical-secret.used" to R.string.history_kind_critical_secret_used,
        "critical-secret.use.denied" to R.string.history_kind_critical_secret_use_denied,
        "critical-secret.use.result" to R.string.history_kind_critical_secret_use_result,
        "wallet.created" to R.string.history_kind_wallet_created,
        "wallet.deleted" to R.string.history_kind_wallet_deleted,
        "wallet.address_issued" to R.string.history_kind_wallet_address_issued,
        "wallet.signed" to R.string.history_kind_wallet_signed,
        "message.sent" to R.string.history_kind_message_sent,
        "message.received" to R.string.history_kind_message_received,
        "call.outgoing" to R.string.history_kind_call_outgoing,
        "call.incoming" to R.string.history_kind_call_incoming,
        "call.answered" to R.string.history_kind_call_answered,
        "call.ended" to R.string.history_kind_call_ended,
        "location.share.started" to R.string.history_kind_location_share_started,
        "location.share.stopped" to R.string.history_kind_location_share_stopped,
        "location.share.received" to R.string.history_kind_location_share_received,
        "location.share.ended" to R.string.history_kind_location_share_ended,
        "location.requested" to R.string.history_kind_location_requested,
        "location.history.deleted" to R.string.history_kind_location_history_deleted,
        "location.history.shared" to R.string.history_kind_location_history_shared,
        "location.history.received" to R.string.history_kind_location_history_received,
        "leash.grant.issued" to R.string.history_kind_leash_grant_issued,
        "leash.grant.updated" to R.string.history_kind_leash_grant_updated,
        "leash.grant.revoked" to R.string.history_kind_leash_grant_revoked,
        "leash.rate_limited" to R.string.history_kind_leash_rate_limited,
        "leash.agent.suspended" to R.string.history_kind_leash_agent_suspended,
        "leash.agent.resumed" to R.string.history_kind_leash_agent_resumed,
        "leash.referrals_limited" to R.string.history_kind_leash_referrals_limited,
        "leash.allowed" to R.string.history_kind_leash_allowed,
        "leash.refused" to R.string.history_kind_leash_refused,
        "leash.item.read" to R.string.history_kind_leash_item_read,
        "leash.item.used" to R.string.history_kind_leash_item_used,
        "leash.throttled" to R.string.history_kind_leash_throttled,
        "action.configured" to R.string.history_kind_action_configured,
        "action.invoked" to R.string.history_kind_action_invoked,
        "action.approved" to R.string.history_kind_action_approved,
        "action.denied" to R.string.history_kind_action_denied,
        "action.completed" to R.string.history_kind_action_completed,
        "account.name_requested" to R.string.history_kind_account_name_requested,
        "account.name_applied" to R.string.history_kind_account_name_applied,
        "account.name_refused" to R.string.history_kind_account_name_refused,
        "profile.core_missing" to R.string.history_kind_profile_core_missing,
        "settings.changed" to R.string.history_kind_settings_changed,
        "drop.suppressed" to R.string.history_kind_drop_suppressed,
    )
}
