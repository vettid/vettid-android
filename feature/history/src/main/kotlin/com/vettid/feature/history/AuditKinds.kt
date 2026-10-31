package com.vettid.feature.history

import androidx.annotation.StringRes
import com.vettid.core.data.vault.AuditCategory
import com.vettid.core.data.vault.RecoveryRefs

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
        // ANDROID-PLAN 0.1.52 §4 "Event titles" (VAULT-MESSAGING 0.42.0 §10.9: `ref` = `item_id`).
        "leash.item.run", "leash.item.proxy", "leash.item.changed",
    )

    /** The item an entry refers to, if any. */
    fun itemOf(kind: String, ref: String?): String? = ref?.takeIf { kind in ITEM_REF_KINDS && it.isNotEmpty() }

    /**
     * An `audit.exported` entry's format and count from its `ref` (VAULT-MESSAGING 0.22.0 §10.9:
     * `format=<csv|json>;count=<n>;seqs=…;filters=…`), or null for another kind or a summary that does not read.
     */
    fun exportSummary(kind: String, ref: String?): Pair<String, Int>? {
        val pairs = ref?.takeIf { kind == KIND_EXPORTED }?.split(';')
            ?.mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap()
        val format = pairs?.get("format")?.takeIf { it == "csv" || it == "json" }
        val count = pairs?.get("count")?.toIntOrNull()?.takeIf { it >= 0 }
        return if (format != null && count != null) format to count else null
    }

    const val KIND_EXPORTED = "audit.exported"

    const val KIND_LOCKED = "vault.locked"

    /** (Release L) `vault.locked`'s `ref` for the idle lock (§3.6.8). */
    const val REF_IDLE = "idle"

    /**
     * What a signer entry's `ref` says (VAULT-MESSAGING 0.27.0 §10.9, §11.7.1; classes, never digests):
     * `device.signer_bound` the class, `device.signer_changed` `<old class>:<new class>`, `device.signer_refused`
     * `unpinned` or `unbound`. Null for another kind or a `ref` that does not read (the title alone then).
     */
    sealed interface SignerDetail {
        data class Bound(val signerClass: String) : SignerDetail

        data class Changed(val from: String, val to: String) : SignerDetail

        /** [unbound]: no signer was recorded for the installation; otherwise its signer is not one the release accepts. */
        data class Refused(val unbound: Boolean) : SignerDetail
    }

    fun signerDetail(kind: String, ref: String?): SignerDetail? = when (kind) {
        KIND_SIGNER_BOUND -> ref?.takeIf { it.isNotEmpty() && ':' !in it }?.let { SignerDetail.Bound(it) }
        KIND_SIGNER_CHANGED -> ref?.split(':')?.takeIf { p -> p.size == 2 && p.all { it.isNotEmpty() } }
            ?.let { SignerDetail.Changed(it[0], it[1]) }
        KIND_SIGNER_REFUSED -> when (ref) {
            "unpinned" -> SignerDetail.Refused(unbound = false)
            "unbound" -> SignerDetail.Refused(unbound = true)
            else -> null
        }
        else -> null
    }

    const val KIND_SIGNER_BOUND = "device.signer_bound"
    const val KIND_SIGNER_CHANGED = "device.signer_changed"
    const val KIND_SIGNER_REFUSED = "device.signer_refused"

    /** How [kind] reads: a known title, a known title as an hourly summary, a dropped message, or unknown. */
    sealed interface Title {
        data class Known(@param:StringRes val res: Int) : Title

        data class Summary(@param:StringRes val res: Int) : Title

        /** `drop.<reason>`: [reason] with its underscores as spaces. */
        data class Dropped(val reason: String) : Title

        data class Unknown(val kind: String) : Title

        /** [res] with a number read from the entry's `ref` ([n]) and the number of recovery attempts ([of]). */
        data class Counted(@param:StringRes val res: Int, val n: Int, val of: Int = RecoveryRefs.ATTEMPTS) : Title

        /**
         * [res] with the name of the entry's connection, device or item ([who]); [anyRes] when that name is not known or
         * the connection, device or item is gone (the title drops the name).
         */
        data class Named(@param:StringRes val res: Int, @param:StringRes val anyRes: Int, val who: Who) : Title

        /**
         * A vote entry (ANDROID-PLAN 0.1.102 "Votes"): [res] names the vote's title in quotes, [anyRes] is the same without it
         * (the Votes list is not in memory). [service] puts the service's name first; [reason] is a refusal's reason, last.
         */
        data class Vote(
            @param:StringRes val res: Int,
            @param:StringRes val anyRes: Int,
            val service: Boolean = false,
            @param:StringRes val reason: Int? = null,
        ) : Title
    }

    /** Whose name a [Title.Named] shows. */
    enum class Who { CONNECTION, DEVICE, ITEM }

    /**
     * How the entry of [kind] with [ref] reads (ANDROID-PLAN 0.1.52 §4 "Event titles"): titles that depend on `ref`
     * first, then those that name the connection, device or item, then the fixed titles.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun title(kind: String, ref: String? = null): Title {
        when (kind) {
            KIND_ATTEMPT -> return RecoveryRefs.attempt(ref)?.let { Title.Counted(R.string.history_kind_recovery_attempt, it.n) }
                ?: Title.Known(R.string.history_kind_recovery_attempt_any)
            KIND_ATTEMPTS_USED -> return RecoveryRefs.attemptsUsed(ref)
                ?.let { Title.Counted(R.string.history_kind_recovery_attempts_used, it) }
                ?: Title.Known(R.string.history_kind_recovery_attempts_used_any)
            KIND_CLOSED -> return Title.Known(closedTitle(RecoveryRefs.closure(ref)?.reason))
            // (VAULT-MESSAGING 0.29.0 §3.6.8, release L) The vault locked itself: `vault.locked` with `ref` `idle`.
            KIND_LOCKED -> if (ref == REF_IDLE) return Title.Known(R.string.history_kind_vault_locked_idle)
        }
        voteTitle(kind, ref)?.let { return it }
        NAMED[kind]?.let { return it }
        TITLES[kind]?.let { return Title.Known(it) }
        if (kind.endsWith(SUMMARY)) TITLES[kind.removeSuffix(SUMMARY)]?.let { return Title.Summary(it) }
        if (kind.startsWith(DROP) && kind.endsWith(MALFORMED)) return Title.Known(R.string.history_kind_drop_malformed)
        if (kind.startsWith(DROP) && kind.length > DROP.length) return Title.Dropped(kind.removePrefix(DROP).replace('_', ' '))
        return Title.Unknown(kind)
    }

    /** The proposal id of a vote entry's `ref` (`<proposal_id>` or `<proposal_id>:<detail>`); the pair is with the entry's connection. */
    fun voteOf(kind: String, ref: String?): String? =
        ref?.substringBefore(':')?.takeIf { it.isNotEmpty() && kind in VOTE_KINDS }

    private val VOTE_KINDS = setOf(
        "vote.cast", "vote.recorded", "vote.refused", "vote.conflict", "vote.receipt_rejected", "vote.extended",
        "vote.extension_rejected", "vote.check_ok", "vote.check_unconfirmed", "vote.check_failed", "vote.check_gave_up",
        "vote.released",
    )

    /** The vote kinds of ANDROID-PLAN 0.1.102 "Votes" (History): the title depends on `ref`'s suffix for some. */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun voteTitle(kind: String, ref: String?): Title? {
        val detail = ref?.substringAfter(':', "")
        return when (kind) {
            "vote.cast" -> Title.Vote(R.string.history_kind_vote_cast, R.string.history_kind_vote_cast_any)
            "vote.recorded" -> Title.Vote(R.string.history_kind_vote_recorded, R.string.history_kind_vote_recorded_any, service = true)
            "vote.refused" -> Title.Vote(
                R.string.history_kind_vote_refused, R.string.history_kind_vote_refused_any, service = true, reason = refusedReason(detail),
            )
            "vote.conflict" -> Title.Vote(R.string.history_kind_vote_conflict, R.string.history_kind_vote_conflict_any, service = true)
            "vote.receipt_rejected" ->
                Title.Vote(R.string.history_kind_vote_receipt_rejected, R.string.history_kind_vote_receipt_rejected_any)
            "vote.extended" -> Title.Vote(R.string.history_kind_vote_extended, R.string.history_kind_vote_extended_any)
            "vote.extension_rejected" ->
                Title.Vote(R.string.history_kind_vote_extension_rejected, R.string.history_kind_vote_extension_rejected_any)
            "vote.check_ok" -> Title.Vote(R.string.history_kind_vote_check_ok, R.string.history_kind_vote_check_ok_any)
            "vote.check_unconfirmed" ->
                Title.Vote(R.string.history_kind_vote_check_unconfirmed, R.string.history_kind_vote_check_unconfirmed_any)
            "vote.check_failed" -> Title.Vote(R.string.history_kind_vote_check_failed, R.string.history_kind_vote_check_failed_any)
            "vote.check_gave_up" -> Title.Vote(R.string.history_kind_vote_check_gave_up, R.string.history_kind_vote_check_gave_up_any)
            "vote.released" -> Title.Vote(R.string.history_kind_vote_released, R.string.history_kind_vote_released_any)
            "vote.proposal_rejected" ->
                serviceTitle(R.string.history_kind_vote_proposal_rejected, R.string.history_kind_vote_proposal_rejected_any)
            "vote.proposal_conflict" -> when (detail) {
                "mismatch" -> serviceTitle(R.string.history_kind_vote_site_mismatch, R.string.history_kind_vote_site_mismatch_any)
                "not_listed" -> serviceTitle(R.string.history_kind_vote_not_listed, R.string.history_kind_vote_not_listed_any)
                "unpublished" -> serviceTitle(R.string.history_kind_vote_unpublished, R.string.history_kind_vote_unpublished_any)
                else -> serviceTitle(R.string.history_kind_vote_two_versions, R.string.history_kind_vote_two_versions_any)
            }
            else -> null
        }
    }

    /** A title that names the service (the entry's connection) and drops it when it is gone. */
    private fun serviceTitle(@StringRes res: Int, @StringRes anyRes: Int) = Title.Named(res, anyRes, Who.CONNECTION)

    /** A refusal's reason in words; a code this app does not know is "not accepted". */
    @StringRes
    private fun refusedReason(code: String?): Int = when (code) {
        "closed", "late" -> R.string.history_vote_reason_closed
        "not_active" -> R.string.history_vote_reason_not_active
        "not_eligible" -> R.string.history_vote_reason_not_eligible
        "connection_ended" -> R.string.history_vote_reason_connection_ended
        else -> R.string.history_vote_reason_not_accepted
    }

    @StringRes
    private fun closedTitle(reason: String?): Int = when (reason) {
        "pin" -> R.string.history_kind_recovery_closed_pin
        "password" -> R.string.history_kind_recovery_closed_password
        "reset" -> R.string.history_kind_recovery_closed_reset
        "backup_off" -> R.string.history_kind_recovery_closed_backup_off
        "exhausted" -> R.string.history_kind_recovery_closed_exhausted
        "delete" -> R.string.history_kind_recovery_closed_delete
        RecoveryRefs.REASON_ROLLBACK -> R.string.history_kind_recovery_closed_rollback
        else -> R.string.history_kind_recovery_closed
    }

    /**
     * Entries History does not list (ANDROID-PLAN 0.1.52 §4 "Event titles", decision 4): `recovery.open` (its outcome is
     * the matching attempt's detail), `device.unlinked_sent`, `drop.call_glare`, and (owner decision of 2026-10-20) a
     * generation closed as `stale`, the harmless case. They stay in the log, in the chain check and in an export. A
     * generation closed as `rollback` (a host served an older recovery record) is shown.
     */
    fun hidden(kind: String, ref: String?): Boolean = when (kind) {
        RecoveryRefs.KIND_OPEN, "device.unlinked_sent", "drop.call_glare" -> true
        KIND_CLOSED -> RecoveryRefs.closure(ref)?.reason == RecoveryRefs.REASON_STALE
        else -> false
    }

    /** One line of an entry's **Details**. */
    sealed interface Detail {
        data class Line(@param:StringRes val res: Int) : Detail

        /**
         * A line that names a duration the vault's time scale divides: [res] in real time, else [scaledRes] with
         * [realSeconds] as the vault runs it (STAGING-TIME-SCALE).
         */
        data class Scaled(@param:StringRes val res: Int, @param:StringRes val scaledRes: Int, val realSeconds: Long) : Detail

        /** [res] with a name the vault recorded ([name]). */
        data class Named(@param:StringRes val res: Int, val name: String) : Detail

        /** [res] with the name of the entry's connection, shown only when that name is known. */
        data class Connection(@param:StringRes val res: Int) : Detail
    }

    /** What is known of a `not_success` attempt's outcome (the matching `recovery.open`, [RecoveryRefs.outcomes]). */
    sealed interface Outcome {
        /** [phoneName]: the attempting phone's self-reported name, shown only as such; null when the entry has none. */
        data class Matched(val outcome: String, val phoneName: String? = null) : Outcome

        /** The entries were read and none matches: "Details not available." */
        data object None : Outcome

        /** Not read (yet), or the read failed: no outcome line. */
        data object Unknown : Outcome

        /** The vault wrote this attempt again as `success`: "Later successful." instead of the not-success lines. */
        data object LaterSuccessful : Outcome
    }

    /** The detail lines of ANDROID-PLAN 0.1.52 §4 "Event titles" for the entry of [kind] with [ref]. */
    @Suppress("CyclomaticComplexMethod")
    fun details(kind: String, ref: String?, outcome: Outcome = Outcome.Unknown): List<Detail> = when (kind) {
        KIND_ATTEMPT -> attemptDetails(ref, outcome)
        KIND_ATTEMPTS_USED -> listOf(Detail.Line(R.string.history_detail_recovery_attempts_used))
        "recovery.armed" -> listOf(Detail.Line(R.string.history_detail_recovery_armed))
        KIND_CLOSED -> closedDetails(ref)
        "recovery.presence_unrecorded" -> listOf(
            Detail.Scaled(R.string.history_detail_presence_unrecorded, R.string.history_detail_presence_unrecorded_scaled, QUIET_SECONDS),
        )
        // VAULT-MESSAGING 0.68.0 §11.14.10: a rotation made for a transfer carries the `transfer_id` as its ref.
        "identity.rotated" ->
            if (ref.isNullOrBlank() || ref == "recovery") emptyList() else listOf(Detail.Line(R.string.history_detail_rotated_transfer))
        "identity.rotation_failed" -> listOf(Detail.Line(R.string.history_detail_rotation_failed))
        "identity.rotate_conflict" -> listOf(Detail.Line(R.string.history_detail_rotate_conflict))
        "push.registered" -> when (ref) {
            "fcm" -> listOf(Detail.Line(R.string.history_detail_push_google))
            "apns" -> listOf(Detail.Line(R.string.history_detail_push_apple))
            else -> emptyList()
        }
        "push.unregistered" -> when (ref) {
            "app" -> listOf(Detail.Line(R.string.history_detail_push_off_app))
            "transferred" -> listOf(Detail.Line(R.string.history_detail_push_off_transferred))
            "replaced" -> listOf(Detail.Line(R.string.history_detail_push_off_replaced))
            else -> emptyList()
        }
        "push.token_gone" -> listOf(Detail.Line(R.string.history_detail_push_token_gone))
        "leash.item.run" -> listOf(Detail.Line(R.string.history_detail_leash_item_run))
        "leash.item.proxy" -> listOf(Detail.Line(R.string.history_detail_leash_item_proxy))
        "credential.utk_resync" -> listOf(Detail.Line(R.string.history_detail_credential_utk_resync))
        "credential.holder_cleared" -> listOf(Detail.Line(R.string.history_detail_credential_holder_cleared))
        KIND_AUTHN_WITHDRAWN -> listOf(Detail.Connection(R.string.history_detail_authn_withdrawn))
        else -> emptyList()
    }

    @Suppress("ReturnCount")
    private fun attemptDetails(ref: String?, outcome: Outcome): List<Detail> {
        val a = RecoveryRefs.attempt(ref) ?: return emptyList()
        if (a.success) return listOf(Detail.Line(R.string.history_detail_attempt_success))
        if (outcome == Outcome.LaterSuccessful) return listOf(Detail.Line(R.string.history_detail_attempt_later_success))
        val line = when (outcome) {
            is Outcome.Matched -> outcomeLine(outcome.outcome)
            Outcome.None -> Detail.Line(R.string.history_detail_attempt_no_details)
            Outcome.Unknown, Outcome.LaterSuccessful -> null
        }
        // Self-reported: shown in that form only, and never changing the title, the priority or the channel.
        val phone = (outcome as? Outcome.Matched)?.phoneName?.let { Detail.Named(R.string.history_detail_attempt_phone, it) }
        return listOfNotNull(Detail.Line(R.string.history_detail_attempt_not_success), line, phone)
    }

    /** A counted open's outcome in words (§11.14.11); null for one this app does not know. */
    fun outcomeLine(outcome: String): Detail? = when (outcome) {
        "wrong" -> Detail.Line(R.string.history_detail_outcome_wrong)
        "attempt_lost" -> Detail.Line(R.string.history_detail_outcome_attempt_lost)
        "quiet" -> Detail.Scaled(R.string.history_detail_outcome_quiet, R.string.history_detail_outcome_quiet_scaled, QUIET_SECONDS)
        "wait" -> Detail.Line(R.string.history_detail_outcome_wait)
        "exhausted" -> Detail.Line(R.string.history_detail_outcome_exhausted)
        "closed" -> Detail.Line(R.string.history_detail_outcome_closed)
        else -> null
    }

    @Suppress("ReturnCount")
    private fun closedDetails(ref: String?): List<Detail> {
        val c = RecoveryRefs.closure(ref) ?: return emptyList()
        val first = when (c.reason) {
            "pin", "password", "reset" -> R.string.history_detail_recovery_closed_reset
            "backup_off" -> R.string.history_detail_recovery_closed_backup_off
            "exhausted" -> R.string.history_detail_recovery_closed_exhausted
            "delete" -> R.string.history_detail_recovery_closed_delete
            RecoveryRefs.REASON_ROLLBACK -> R.string.history_detail_recovery_closed_rollback
            else -> return emptyList()
        }
        return listOfNotNull(Detail.Line(first), Detail.Line(R.string.history_detail_recovery_closed_unconfirmed).takeIf { c.unconfirmed })
    }

    /** (VAULT-MESSAGING 0.80.0 §10.9; `ref` = `request_id`) The challenger withdrew a sign-in or approval challenge. */
    const val KIND_AUTHN_WITHDRAWN = "connection.authenticate.withdrawn"

    const val KIND_ATTEMPT = RecoveryRefs.KIND_ATTEMPT
    const val KIND_ATTEMPTS_USED = "recovery.attempts_used"
    const val KIND_CLOSED = "recovery.generation_closed"

    /** The quiet period (§11.14.5): three days, divided by the vault's time scale. */
    private const val QUIET_SECONDS = 3 * 86_400L
    private const val MALFORMED = "_malformed"

    private val SECRET_USED =
        Title.Named(R.string.history_kind_leash_item_secret_used, R.string.history_kind_leash_item_secret_used_any, Who.ITEM)

    /** The titles that name the entry's connection, device or item (ANDROID-PLAN 0.1.52 §4 "Event titles"). */
    private val NAMED: Map<String, Title.Named> = mapOf(
        "identity.rotate_conflict" to
            Title.Named(R.string.history_kind_rotate_conflict, R.string.history_kind_rotate_conflict_any, Who.CONNECTION),
        "identity.rotate_resolved" to
            Title.Named(R.string.history_kind_rotate_resolved, R.string.history_kind_rotate_resolved_any, Who.CONNECTION),
        "call.permission_off" to
            Title.Named(R.string.history_kind_call_permission_off, R.string.history_kind_call_permission_off_any, Who.CONNECTION),
        "call.permission_on" to
            Title.Named(R.string.history_kind_call_permission_on, R.string.history_kind_call_permission_on_any, Who.CONNECTION),
        "push.registered" to Title.Named(R.string.history_kind_push_registered, R.string.history_kind_push_registered_any, Who.DEVICE),
        "push.unregistered" to
            Title.Named(R.string.history_kind_push_unregistered, R.string.history_kind_push_unregistered_any, Who.DEVICE),
        "push.token_gone" to
            Title.Named(R.string.history_kind_push_token_gone, R.string.history_kind_push_token_gone_any, Who.DEVICE),
        "leash.item.run" to SECRET_USED,
        "leash.item.proxy" to SECRET_USED,
        "leash.item.changed" to
            Title.Named(R.string.history_kind_leash_item_changed, R.string.history_kind_leash_item_changed_any, Who.ITEM),
    )

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
        KIND_SIGNER_BOUND to R.string.history_kind_device_signer_bound,
        KIND_SIGNER_CHANGED to R.string.history_kind_device_signer_changed,
        KIND_SIGNER_REFUSED to R.string.history_kind_device_signer_refused,
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
        KIND_AUTHN_WITHDRAWN to R.string.history_kind_connection_authenticate_withdrawn,
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
        // ANDROID-PLAN 0.1.60 (VAULT-MESSAGING 0.52.0 §3.5.4): History only, no feed item.
        "credential.utk_resync" to R.string.history_kind_credential_utk_resync,
        // ANDROID-PLAN 0.1.64: History only, no feed item.
        "credential.holder_cleared" to R.string.history_kind_credential_holder_cleared,
        "audit.exported" to R.string.history_kind_audit_exported,
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
        "message.attachment_sent" to R.string.history_kind_photo_sent,
        "message.attachment_received" to R.string.history_kind_photo_received,
        "message.attachment_failed" to R.string.history_kind_photo_failed,
        "message.attachment_refused" to R.string.history_kind_photo_refused,
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
        "connection.asks_paused" to R.string.history_kind_connection_asks_paused,
        "connection.asks_resumed" to R.string.history_kind_connection_asks_resumed,
        "connection.asks_muted" to R.string.history_kind_connection_asks_muted,
        "connection.asks_unmuted" to R.string.history_kind_connection_asks_unmuted,
        "drop.ask_muted" to R.string.history_kind_drop_ask_muted,
        "drop.ask_paused" to R.string.history_kind_drop_ask_paused,
        "drop.ask_cooldown" to R.string.history_kind_drop_ask_cooldown,
        "drop.ask_pending" to R.string.history_kind_drop_ask_pending,
        "drop.ask_rate" to R.string.history_kind_drop_ask_rate,
        "drop.attachment_rate" to R.string.history_kind_drop_attachment_rate,
        "drop.attachment_malformed" to R.string.history_kind_drop_attachment_malformed,
        "drop.grant_rate_limited" to R.string.history_kind_drop_grant_rate_limited,
        // ANDROID-PLAN 0.1.52 §4 "Event titles" (VAULT-MESSAGING 0.49.1 §10.9, §11.14.11).
        "vault.pin_changed" to R.string.history_kind_vault_pin_changed,
        "device.secret_renewed" to R.string.history_kind_device_secret_renewed,
        "recovery.armed" to R.string.history_kind_recovery_armed,
        "recovery.presence_unrecorded" to R.string.history_kind_recovery_presence_unrecorded,
        "identity.rotation_failed" to R.string.history_kind_identity_rotation_failed,
        "connection.request.approved" to R.string.history_kind_connection_request_approved,
        "connection.request.declined" to R.string.history_kind_connection_request_declined,
        "drop.call_off" to R.string.history_kind_drop_call_off,
        "drop.call_rate" to R.string.history_kind_drop_call_rate,
        "drop.call_share" to R.string.history_kind_drop_call_share,
        "drop.one_app" to R.string.history_kind_drop_one_app,
        "drop.owner_check" to R.string.history_kind_drop_owner_check,
        "drop.profile_ik_mismatch" to R.string.history_kind_drop_profile_ik_mismatch,
        "drop.grant_duplicate" to R.string.history_kind_drop_grant_duplicate,
        "drop.grant_limit" to R.string.history_kind_drop_grant_limit,
        "drop.critical_signature" to R.string.history_kind_drop_critical_signature,
        "drop.rate_limited" to R.string.history_kind_drop_rate_limited,
        // ANDROID-PLAN 0.1.102 "Votes": a vote message the vault ignored, and the list of services it did not accept.
        "drop.vote_malformed" to R.string.history_kind_vote_ignored,
        "drop.vote_limit" to R.string.history_kind_vote_ignored,
        "drop.vote_not_service" to R.string.history_kind_vote_ignored,
        "vote.list_rejected" to R.string.history_kind_vote_list_rejected,
    )
}
