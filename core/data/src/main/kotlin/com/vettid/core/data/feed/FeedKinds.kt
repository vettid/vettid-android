package com.vettid.core.data.feed

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.vettid.core.data.R
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.AskBatching
import com.vettid.core.data.social.AuthPurpose
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.vault.RecoveryRefs
import com.vettid.core.data.vault.SignerClass
import com.vettid.core.vault.FeedItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Member-facing text built from string resources: [res] with [args], or [plural] for [quantity]; an [Instant] argument
 * is shown as a local date and time. [literal] is shown as is (an unknown kind, a guide's title).
 */
data class FeedText(
    @param:StringRes val res: Int = 0,
    val args: List<Any> = emptyList(),
    @param:PluralsRes val plural: Int = 0,
    val quantity: Int = 0,
    val literal: String? = null,
) {
    @Suppress("SpreadOperator") // a handful of format arguments
    fun resolve(r: Resources): String {
        literal?.let { return it }
        val a = args.map { v ->
            when (v) {
                is FeedText -> v.resolve(r)
                is Instant -> dateTime.format(v.atZone(ZoneId.systemDefault()))
                else -> v
            }
        }.toTypedArray()
        return if (plural != 0) r.getQuantityString(plural, quantity, *a) else r.getString(res, *a)
    }

    private val dateTime: DateTimeFormatter
        get() = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault())
}

/**
 * The names a feed item's text uses, from the app's in-memory caches as History resolves them (ANDROID-PLAN 0.1.23,
 * 4): the connections ("First Last"), the items of the Vault list (names only, never values), and the Approvals
 * entries (a request's sender before it is a connection, a grant's item, a share rule's tag).
 */
data class FeedNames(
    val connections: Map<String, ConnectionInfo> = emptyMap(),
    val items: Map<String, String> = emptyMap(),
    val approvals: Map<String, Approval> = emptyMap(),
    /** Vote titles from the Votes list in memory, by [VoteTitles.key] (cleaned, one line); the second line of a vote item. */
    val votes: Map<String, String> = emptyMap(),
    /**
     * The votes of the list in memory whose check is `gave_up`, by [VoteTitles.key]: their `vote.result` item is the give-up
     * one (VAULT-MESSAGING 0.86.4 §10.19.6 step 4; the item itself carries no check state). A vote not in the list reads as
     * a result.
     */
    val votesGaveUp: Set<String> = emptySet(),
    /** The devices and agents of `device.list` (memory), by id: their own, self-asserted names (cleaned, not yet isolated). */
    val devices: Map<String, String> = emptyMap(),
    /** The name the pairing this phone holds gave itself (`device.pair.pending`, AA2); null when none is held. */
    val pairingName: String? = null,
    /** The `pending_id` of that pairing: only the feed item whose `ref` is this one is named by [pairingName]. */
    val pairingPendingId: String? = null,
) {
    companion object {
        /** [votesGaveUp] from the Votes list in memory: the keys of the votes whose check is `gave_up`. */
        fun votesGaveUp(list: List<com.vettid.core.data.votes.VoteSummary>): Set<String> =
            list.filter { it.check?.state == com.vettid.core.data.votes.CheckState.GAVE_UP }
                .mapTo(HashSet()) { VoteTitles.key(it.connectionId, it.proposalId) }

        /** Names for [devices]: the list's own names; this phone's entry too; an empty name is left out. */
        fun deviceNames(list: List<com.vettid.core.data.devices.DeviceInfo>): Map<String, String> =
            list.filter { it.name.isNotEmpty() }.associate { it.id to it.name }

        /** The name of the pairing being compared on this phone, if one is. */
        fun pairingName(state: com.vettid.core.data.devices.PairingState): String? =
            (state.stage as? com.vettid.core.data.devices.PairingStage.Compare)?.pending?.name?.takeIf { it.isNotEmpty() }

        /** The `pending_id` of the pairing being compared; null when none is, or when its event did not say. */
        fun pairingPendingId(state: com.vettid.core.data.devices.PairingState): String? =
            (state.stage as? com.vettid.core.data.devices.PairingStage.Compare)?.pending?.pendingId
    }
}

/**
 * Where a feed item leads (ANDROID-PLAN 0.1.23, 4); the app maps each to its screen. [Sheet] is the detail sheet,
 * for kinds with no screen, and for a target that is gone.
 */
sealed interface FeedTarget {
    data class ApprovalEntry(val key: String) : FeedTarget

    /** The Approvals list: a batch of asks, or an ask whose first ask is no longer open. */
    data object Approvals : FeedTarget

    data class Connection(val connectionId: String) : FeedTarget

    data object Connections : FeedTarget

    data class Conversation(val connectionId: String) : FeedTarget

    data class SharedWithYou(val connectionId: String) : FeedTarget

    data class ShareRule(val connectionId: String, val ruleId: String) : FeedTarget

    data class Item(val itemId: String) : FeedTarget

    /** A wallet's Spending limits (`wallet.cap_refused`, `wallet.caps_lowered`, `wallet.caps_raised`; `ref` = the wallet's item id). */
    data class WalletLimits(val walletId: String) : FeedTarget

    /** A transaction the vault signed (`wallet.signed`; `ref` = the `txid`, no wallet id: the screen finds the wallet). */
    data class WalletTransaction(val txid: String) : FeedTarget

    /** Settings → Vault (the transfer and the signer's notices). */
    data object Devices : FeedTarget

    /** Devices & agents (ANDROID-PLAN 0.1.53 AA1): the list. */
    data object DeviceList : FeedTarget

    /** The pairing's compare screen (`device.pair.pending`, AA2); with no pairing held, it says it cannot be checked. */
    data object Pairing : FeedTarget

    /** A device's page in Devices & agents (`device.paired` with its `device_id`). */
    data class Device(val deviceId: String) : FeedTarget

    /** A vote's screen in Votes (ANDROID-PLAN 0.1.102): the pair names it, never the proposal id alone. */
    data class Vote(val connectionId: String, val proposalId: String) : FeedTarget

    /** The Votes list (a vote item without the ids that name its vote). */
    data object VoteList : FeedTarget

    /** The clone alarm's screen. */
    data object Alarm : FeedTarget

    /** History (its Security entries). */
    data object History : FeedTarget

    /** Settings → Security → Credential. */
    data object Credential : FeedTarget

    /** Settings → Security (the daily check and the hold). */
    data object Security : FeedTarget

    data object Sheet : FeedTarget
}

/**
 * The feed kinds of VAULT-MESSAGING §10.9 (0.23.1) in the app (ANDROID-PLAN 0.1.23, 4): each kind's text and target.
 * A kind this app does not know (a newer vault's) shows the kind itself and opens the sheet; it is never hidden.
 */
@Suppress("TooManyFunctions")
object FeedKinds {
    /** Every kind §10.9 names (0.23.1). */
    val KNOWN = setOf(
        "connection.request", "connection.request.peer_declined", "connection.added", "connection.removed",
        "connection.stale", "connection.asks_paused", "connection.authenticate.requested",
        "message.received", "call.missed", "message.attachment_failed", "message.attachment_refused",
        "grant.request", "grant.shared", "grant.revoked", "share.pending", "share.rate_limited",
        "critical-secret.use.request", "item.revealed",
        "device.pair.pending", "device.paired", "device.unlinked", "device.transferred", "device.replaced",
        "device.signer_changed",
        "device.session.pending", "approval.pending",
        // VAULT-MESSAGING 0.87.0 §10.9, §6.3.1 "An app from before release 29": another device's app lacks `hs_confirm`.
        "device.update_needed",
        "credential.alarm", "credential.password_failed", "credential.rotated", "credential.reset",
        "owner_check.failed", "owner_check.locked", "owner_check.hold_changed",
        "guide",
        "leash.rate_limited", "leash.item.read", "leash.agent.suspended", "leash.referrals_limited",
        "action.request", "intro.request", "location.shared", "location.request", "wallet.signed",
        // VAULT-MESSAGING 0.49.1 (as built since release R), ANDROID-PLAN 0.1.52 §4 "Event titles".
        "vault.pin_changed", "device.secret_renewed", "credential.recovered", "recovery.device_paired",
        "recovery.completed", "recovery.attempt", "recovery.change_required", "recovery.counter_unavailable",
        "identity.rotation_failed", "identity.rotate_conflict", "leash.item.run", "leash.item.proxy",
        // VAULT-MESSAGING 0.50.0 (owner decision of 2026-10-20): a `rollback` closure's urgent item.
        "recovery.generation_closed",
        // VAULT-MESSAGING 0.56.0 §10.18 (ANDROID-PLAN 0.1.74 "Notifications"): `ref` = `wallet_id`.
        "wallet.cap_refused", "wallet.caps_lowered", "wallet.caps_raised",
        // VAULT-MESSAGING 0.81.0 §10.19.7 (ANDROID-PLAN 0.1.102 "Votes"): each carries `connection_id` with `ref` = `proposal_id`.
        "vote.open", "vote.closing", "vote.result", "vote.problem",
    )

    /**
     * Feed kinds the app neither lists nor notifies (ANDROID-PLAN 0.1.52 §4 "Event titles", de-duplication): one recovery
     * raises one notification, `recovery.completed`, and a PIN change is History only. Their audit entries show in
     * History; the feed items stay as the vault keeps them.
     */
    val HISTORY_ONLY = setOf("vault.pin_changed", "credential.recovered", "recovery.device_paired")

    fun historyOnly(kind: String): Boolean = kind in HISTORY_ONLY

    /** Kinds whose screens come later (§6): listed, with "Not available in this version of the app". */
    fun notAvailable(kind: String): Boolean =
        (kind.startsWith("leash.") && kind !in AGENT_PAGE && kind !in AGENT_USE) || kind in LATER

    /**
     * (AA5) The `leash.*` kinds that open the agent's page. AA6: the agent-use kinds have their detail sheet,
     * with the item opened from it.
     */
    private val AGENT_PAGE = setOf("leash.rate_limited", "leash.referrals_limited", "leash.agent.suspended")

    /** The device's (or agent's) own name for the item's `device_id`, isolated; null when not known. */
    private fun deviceName(item: FeedItem, names: FeedNames): FeedText? =
        item.deviceId?.let { names.devices[it] }?.let { AccountNames.isolateOrNull(it) }?.let { FeedText(literal = it) }

    private fun named(res: Int, any: Int, name: FeedText?): FeedText = name?.let { FeedText(res, listOf(it)) } ?: FeedText(any)

    private val LATER = setOf("action.request", "intro.request")

    fun known(kind: String): Boolean = kind in KNOWN || kind.startsWith("leash.")

    /** An ask (a decision waits in Approvals): "This is no longer waiting" when it is gone. */
    fun isAsk(kind: String): Boolean = FeedAsks.approvalKey(kind, "x") != null

    private const val OFF_UNTIL = "off_until:"

    /** The connection's "First Last" (never an alias), the request's sender, or "Removed connection". */
    fun connectionName(item: FeedItem, names: FeedNames): FeedText {
        val c = item.connectionId?.let { names.connections[it] }
        val name = knownName(item, names)
        return when {
            name != null -> FeedText(literal = name)
            c != null -> FeedText(R.string.data_feed_name_not_shared)
            item.connectionId != null -> FeedText(R.string.data_feed_removed_connection)
            else -> FeedText(R.string.data_feed_someone)
        }
    }

    /** The connection's "First Last" or the request's sender; null when neither is known. */
    private fun knownName(item: FeedItem, names: FeedNames): String? {
        val c = item.connectionId?.let { names.connections[it] }
        val sender = FeedAsks.approvalKey(item.kind, item.ref)?.let { names.approvals[it] }?.let { a -> senderOf(a) }
        return AccountNames.isolateOrNull(AccountNames.cleaned(c?.accountName) ?: AccountNames.cleaned(c?.name) ?: sender)
    }

    /**
     * A challenge's purpose (VAULT-MESSAGING 0.65.0 §10.4), from its pending approval of the same connection: the feed
     * item does not carry it, so an item whose approval is no longer pending (or names another connection) reads as one
     * without a purpose.
     */
    fun authenticationPurpose(item: FeedItem, names: FeedNames): AuthPurpose {
        val a = FeedAsks.approvalKey(item.kind, item.ref)?.let { names.approvals[it] } as? Approval.Authentication
        return if (a != null && a.connectionId == item.connectionId) a.purpose else AuthPurpose.NONE
    }

    /**
     * A challenge's title (§10.4): a batch (§10.4.1) reads "Sam asks for 3 things" whatever the purpose; else per purpose,
     * the name bidirectionally isolated (§10.8) in the purpose's sentences.
     */
    private fun authenticationTitle(item: FeedItem, names: FeedNames): FeedText {
        val count = item.count ?: 0
        if (count > 1) {
            return FeedText(
                plural = R.plurals.data_feed_grant_request_batch,
                quantity = count,
                args = listOf(firstName(item, names), count),
            )
        }
        val known = knownName(item, names)?.let { FeedText(literal = it) }
        val aConnection = FeedText(R.string.data_feed_a_connection_inline)
        return when (authenticationPurpose(item, names)) {
            AuthPurpose.SIGNIN -> FeedText(R.string.data_feed_authenticate_signin, listOf(known ?: aConnection))
            AuthPurpose.APPROVAL -> FeedText(R.string.data_feed_authenticate_approval, listOf(known ?: aConnection))
            AuthPurpose.NONE -> FeedText(R.string.data_feed_authenticate, listOf(connectionName(item, names)))
        }
    }

    /** The connection's first name (the short labels), else as [connectionName]. */
    fun firstName(item: FeedItem, names: FeedNames): FeedText {
        val c = item.connectionId?.let { names.connections[it] }
        AccountNames.isolateOrNull(c?.firstName)?.let { return FeedText(literal = it) }
        return connectionName(item, names)
    }

    private fun senderOf(a: Approval): String? = when (a) {
        is Approval.ConnectionRequest -> AccountNames.cleaned(a.name) ?: AccountNames.cleaned(a.displayName)
        else -> AccountNames.cleaned(a.connectionName)
    }

    /** The item's name (never a value), when the item or the ask names one. */
    @Suppress("CyclomaticComplexMethod") // one per kind
    fun itemName(item: FeedItem, names: FeedNames): String? = when (item.kind) {
        "item.revealed" -> item.ref?.let { names.items[it] }
        "grant.request" -> (names.approvals[FeedAsks.approvalKey(item.kind, item.ref) ?: ""] as? Approval.GrantRequest)
            ?.entries?.singleOrNull()?.let { it.name ?: it.label }
        "critical-secret.use.request" ->
            (names.approvals[FeedAsks.approvalKey(item.kind, item.ref) ?: ""] as? Approval.CriticalUse)?.itemName
        "leash.item.run", "leash.item.proxy", "leash.item.read" -> item.ref?.let { names.items[it] }
        in WALLET_CAPS -> item.ref?.let { names.items[it] }
        else -> null
    }.let { AccountNames.isolateOrNull(it) }

    /** The tag a `share.pending` rule is named by (0.1.22); null when unknown. */
    private fun ruleTag(item: FeedItem, names: FeedNames): String? =
        (names.approvals[FeedAsks.approvalKey(item.kind, item.ref) ?: ""] as? Approval.ShareDecision)?.let { d ->
            AccountNames.isolateOrNull(d.rule?.tags?.firstOrNull() ?: d.tags.firstOrNull())
        }

    /**
     * The row's title (the label of the plan's table). [details] false (a notification with "Names", ANDROID-PLAN
     * 0.1.23, Notification modes 8): the connection's or device's name and the kind only, no item names or tags.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // one label per kind
    fun title(item: FeedItem, names: FeedNames, details: Boolean = true): FeedText {
        val who = connectionName(item, names)
        val first = firstName(item, names)
        val thing = if (details) itemName(item, names) else null
        val count = item.count ?: 0
        return when (item.kind) {
            "connection.request" -> FeedText(R.string.data_feed_connection_request, listOf(who))
            "connection.request.peer_declined" -> FeedText(R.string.data_feed_connection_peer_declined, listOf(who))
            "connection.added" -> FeedText(R.string.data_feed_connection_added, listOf(who))
            "connection.removed" -> FeedText(R.string.data_feed_connection_removed, listOf(who))
            "connection.stale" -> FeedText(R.string.data_feed_connection_stale, listOf(who))
            "connection.asks_paused" -> FeedText(R.string.data_feed_asks_paused, listOf(first))
            "connection.authenticate.requested" -> authenticationTitle(item, names)
            "message.received" -> FeedText(R.string.data_feed_message, listOf(who))
            // §10.5.1 (0.32.0): an outgoing photo that failed, an incoming one refused because the vault is full.
            "message.attachment_failed" -> FeedText(R.string.data_feed_photo_failed, listOf(who))
            "message.attachment_refused" -> FeedText(R.string.data_feed_photo_refused, listOf(who))
            "call.missed" -> FeedText(R.string.data_feed_call_missed, listOf(who))
            "grant.request" -> when {
                count > 1 -> FeedText(plural = R.plurals.data_feed_grant_request_batch, quantity = count, args = listOf(first, count))
                thing != null -> FeedText(R.string.data_feed_grant_request, listOf(first, thing))
                else -> FeedText(R.string.data_feed_grant_request_any, listOf(first))
            }
            "grant.shared" -> FeedText(R.string.data_feed_grant_shared, listOf(first))
            "grant.revoked" -> FeedText(R.string.data_feed_grant_revoked, listOf(first))
            "share.pending" -> ruleTag(item, names)?.takeIf { details }
                ?.let { FeedText(R.string.data_feed_share_pending, listOf(it, first)) }
                ?: FeedText(R.string.data_feed_share_pending_any, listOf(first))
            "share.rate_limited" -> FeedText(R.string.data_feed_share_rate_limited, listOf(first))
            "critical-secret.use.request" -> thing?.let { FeedText(R.string.data_feed_critical_use, listOf(first, it)) }
                ?: FeedText(R.string.data_feed_critical_use_any, listOf(first))
            "item.revealed" -> revealed(thing, details)
            "device.pair.pending" -> named(
                R.string.data_feed_device_pair_pending_named, R.string.data_feed_device_pair_pending,
                names.pairingName?.takeIf { item.ref != null && item.ref == names.pairingPendingId }
                    ?.let { AccountNames.isolateOrNull(it) }?.let { FeedText(literal = it) },
            )
            "device.paired" -> named(R.string.data_feed_device_paired_named, R.string.data_feed_device_paired, deviceName(item, names))
            "device.unlinked" ->
                named(R.string.data_feed_device_unlinked_named, R.string.data_feed_device_unlinked, deviceName(item, names))
            "device.transferred" -> FeedText(R.string.data_feed_device_transferred)
            "device.replaced" -> FeedText(R.string.data_feed_device_replaced)
            "device.signer_changed" -> FeedText(R.string.data_feed_device_signer_changed)
            // (0.87.0 §6.3.1, the owner's wording of 2026-11-06 and 2026-11-10) The device by its name in `device.list`; without
            // one (the list not read, the device not listed, or listed without a name) the wording that names no device.
            "device.update_needed" -> named(
                R.string.data_feed_device_update_needed_named, R.string.data_feed_device_update_needed, deviceName(item, names),
            )
            "device.session.pending" ->
                named(R.string.data_feed_device_session_named, R.string.data_feed_device_session, deviceName(item, names))
            "approval.pending" ->
                named(R.string.data_feed_device_approval_named, R.string.data_feed_device_approval, deviceName(item, names))
            "credential.alarm" -> FeedText(R.string.data_feed_credential_alarm)
            "credential.password_failed" -> FeedText(R.string.data_feed_password_failed)
            "credential.rotated" -> FeedText(R.string.data_feed_credential_rotated)
            "credential.reset" -> FeedText(R.string.data_feed_credential_reset)
            "owner_check.failed" -> FeedText(
                when (item.ref) {
                    "pin" -> R.string.data_feed_check_failed_pin
                    "password" -> R.string.data_feed_check_failed_password
                    else -> R.string.data_feed_check_failed
                },
            )
            "owner_check.locked" -> FeedText(R.string.data_feed_check_locked)
            "owner_check.hold_changed" -> holdChanged(item.ref)
            "guide" -> item.title?.takeIf { it.isNotBlank() }?.let { FeedText(literal = it) } ?: FeedText(R.string.data_feed_guide)
            "leash.rate_limited" ->
                named(R.string.data_feed_leash_rate_limited_named, R.string.data_feed_leash_rate_limited, deviceName(item, names))
            "leash.item.read" -> thing?.let { FeedText(R.string.data_feed_leash_item_read, listOf(it)) }
                ?: FeedText(R.string.data_feed_leash_item_read_any)
            "leash.agent.suspended" ->
                named(R.string.data_feed_leash_suspended_named, R.string.data_feed_leash_suspended, deviceName(item, names))
            "leash.referrals_limited" ->
                named(R.string.data_feed_leash_referrals_named, R.string.data_feed_leash_referrals, deviceName(item, names))
            "action.request" -> FeedText(R.string.data_feed_action_request, listOf(first))
            "intro.request" -> FeedText(R.string.data_feed_intro_request, listOf(first))
            "location.shared" -> FeedText(R.string.data_feed_location_shared, listOf(first))
            "location.request" -> FeedText(R.string.data_feed_location_request, listOf(first))
            "wallet.signed" -> FeedText(R.string.data_feed_wallet_signed)
            "wallet.cap_refused" -> FeedText(R.string.data_feed_wallet_cap_refused)
            "wallet.caps_lowered" ->
                walletCaps(R.string.data_feed_wallet_caps_lowered, R.string.data_feed_wallet_caps_lowered_any, thing, details)
            "wallet.caps_raised" ->
                walletCaps(R.string.data_feed_wallet_caps_raised, R.string.data_feed_wallet_caps_raised_any, thing, details)
            "vault.pin_changed" -> FeedText(R.string.data_feed_pin_changed)
            "device.secret_renewed" -> FeedText(R.string.data_feed_secret_renewed)
            "credential.recovered" -> FeedText(R.string.data_feed_credential_recovered)
            "recovery.device_paired" -> FeedText(R.string.data_feed_recovery_device_paired)
            "recovery.completed" -> FeedText(R.string.data_feed_recovery_completed)
            "recovery.attempt" -> recoveryAttempt(item)
            "recovery.change_required" -> FeedText(
                if (item.ref == REF_PASSWORD) R.string.data_feed_change_required_password else R.string.data_feed_change_required,
            )
            "recovery.counter_unavailable" -> FeedText(R.string.data_feed_counter_unavailable)
            // VAULT-MESSAGING 0.50.0 (owner decision of 2026-10-20): the vault makes this item only for `rollback`.
            "recovery.generation_closed" -> FeedText(R.string.data_feed_recovery_rollback)
            "vote.open" -> FeedText(R.string.data_feed_vote_open)
            "vote.closing" -> FeedText(R.string.data_feed_vote_closing)
            "vote.result" ->
                FeedText(if (voteGaveUp(item, names)) R.string.data_feed_vote_result_gave_up else R.string.data_feed_vote_result)
            "vote.problem" -> FeedText(R.string.data_feed_vote_problem)
            "identity.rotation_failed" -> FeedText(R.string.data_feed_rotation_failed)
            "identity.rotate_conflict" -> FeedText(R.string.data_feed_rotate_conflict, listOf(who))
            "leash.item.run", "leash.item.proxy" -> thing?.let { FeedText(R.string.data_feed_leash_item_used, listOf(it)) }
                ?: FeedText(R.string.data_feed_leash_item_used_any)
            else -> if (item.kind.startsWith("leash.")) FeedText(R.string.data_feed_leash) else unknown(item.kind)
        }
    }

    /**
     * `recovery.attempt` (decision 2 of 2026-10-19): urgent and not successful, "Someone tried to recover your vault";
     * urgent and successful, "Your vault was recovered"; not urgent (the member completed a recovery in that generation
     * since), "Recovery attempt n of 10". Without a `ref` that reads, the same words without the number.
     */
    private fun recoveryAttempt(item: FeedItem): FeedText {
        val a = RecoveryRefs.attempt(item.ref)
        val urgent = urgent(item)
        return when {
            a == null && urgent -> FeedText(R.string.data_feed_recovery_attempt_someone_any)
            a == null -> FeedText(R.string.data_feed_recovery_attempt_any)
            !urgent -> FeedText(R.string.data_feed_recovery_attempt, listOf(a.n, RecoveryRefs.ATTEMPTS))
            a.success -> FeedText(R.string.data_feed_recovery_attempt_recovered, listOf(a.n, RecoveryRefs.ATTEMPTS))
            else -> FeedText(R.string.data_feed_recovery_attempt_someone, listOf(a.n, RecoveryRefs.ATTEMPTS))
        }
    }

    /** A spending limit changed: with the wallet's name ("Deleted item" when it is gone), or, without details, none. */
    private fun walletCaps(named: Int, any: Int, thing: String?, details: Boolean): FeedText = when {
        !details -> FeedText(any)
        else -> FeedText(named, listOf(thing?.let { FeedText(literal = it) } ?: FeedText(R.string.data_feed_deleted_item)))
    }

    private val WALLET_CAPS = setOf("wallet.cap_refused", "wallet.caps_lowered", "wallet.caps_raised")

    /** `item.revealed`: the item's name, "Deleted item", or (no details) none. */
    private fun revealed(thing: String?, details: Boolean): FeedText = when {
        !details -> FeedText(R.string.data_feed_item_revealed_any)
        else -> FeedText(
            R.string.data_feed_item_revealed,
            listOf(thing?.let { FeedText(literal = it) } ?: FeedText(R.string.data_feed_deleted_item)),
        )
    }

    /** A kind this app does not know: the kind itself. */
    private fun unknown(kind: String) = FeedText(R.string.data_feed_unknown, listOf(kind))

    /** `owner_check.hold_changed`'s `ref` (§3.6.7): `on`, `off`, `off_until:<ts>` or `on:expired`. */
    private fun holdChanged(ref: String?): FeedText {
        val until = ref?.takeIf { it.startsWith(OFF_UNTIL) }?.let {
            try {
                Instant.parse(it.removePrefix(OFF_UNTIL))
            } catch (_: DateTimeParseException) {
                null
            }
        }
        return when {
            until != null -> FeedText(R.string.data_feed_hold_off_until, listOf(until))
            ref == "off" || ref?.startsWith(OFF_UNTIL) == true -> FeedText(R.string.data_feed_hold_off)
            ref == "on:expired" -> FeedText(R.string.data_feed_hold_back_on)
            else -> FeedText(R.string.data_feed_hold_on)
        }
    }

    /**
     * `device.signer_changed`'s `ref` (VAULT-MESSAGING 0.27.0 §11.7.1): `<old class>:<new class>`, as "From … to
     * …" in the words of ANDROID-PLAN §7; null when it does not read (the title alone then).
     */
    fun signerChange(ref: String?): FeedText? {
        val parts = ref?.split(':')?.takeIf { it.size == 2 } ?: return null
        return FeedText(R.string.data_feed_signer_from_to, parts.map { FeedText(signerLabel(it)) })
    }

    /** A signer class in the member's words (the vault names the class only); a class this app does not know reads "another signer". */
    @StringRes
    fun signerLabel(signerClass: String?): Int = when (SignerClass.of(signerClass)) {
        SignerClass.GOOGLE_PLAY -> R.string.data_signer_google_play
        SignerClass.VETTID -> R.string.data_signer_vettid
        SignerClass.DEVELOPMENT -> R.string.data_signer_development
        null -> R.string.data_signer_other
    }

    /** The detail sheet's own line under the title (AA6): what an agent's use of a secret did, as History says it; null for other kinds. */
    fun sheetDetail(item: FeedItem, names: FeedNames = FeedNames()): FeedText? = when (item.kind) {
        // (0.83.6) The give-up item asks nothing of the member (owner decision a, 2026-11-06, thread 01M6RH58PE19ZBEHX3JK5ZW06A).
        "vote.result" -> if (voteGaveUp(item, names)) FeedText(R.string.data_feed_vote_result_gave_up_detail) else null
        "leash.item.run" -> FeedText(R.string.data_feed_leash_item_run_detail)
        "leash.item.proxy" -> FeedText(R.string.data_feed_leash_item_proxy_detail)
        "leash.item.read" -> FeedText(R.string.data_feed_leash_item_read_detail)
        else -> null
    }

    /** The item the sheet can open (AA6): the agent-use kinds' `ref`, only while [FeedNames.items] still knows it. */
    fun sheetItemId(item: FeedItem, names: FeedNames): String? =
        item.ref?.takeIf { item.kind in AGENT_USE && it in names.items }

    private val AGENT_USE = setOf("leash.item.run", "leash.item.proxy", "leash.item.read")

    /** The row's second line: the item's name or the batch's "3 things"; null for none. */
    fun supporting(item: FeedItem, names: FeedNames): FeedText? = when {
        (item.count ?: 0) > 1 -> things(item.count ?: 0)
        item.kind == "guide" -> item.body?.takeIf { it.isNotBlank() }?.let { FeedText(literal = it.lineSequence().first()) }
        item.kind == "device.signer_changed" -> signerChange(item.ref)
        item.kind in VOTE_NAMED -> voteLine(item, names)
        item.kind == "vote.problem" -> FeedText(R.string.data_feed_vote_problem_body)
        item.kind == "connection.authenticate.requested" && authenticationPurpose(item, names) == AuthPurpose.SIGNIN ->
            FeedText(R.string.data_feed_authenticate_signin_body)
        else -> eventLine(item) ?: itemName(item, names)?.let { FeedText(literal = it) }
    }

    private val VOTE_NAMED = setOf("vote.open", "vote.closing", "vote.result")

    /** A `vote.result` item whose vote the list in memory has with its check `gave_up` (§10.19.6 step 4). */
    private fun voteGaveUp(item: FeedItem, names: FeedNames): Boolean =
        item.kind == "vote.result" && VoteTitles.key(item.connectionId, item.ref) in names.votesGaveUp

    /**
     * A vote item's second line (ANDROID-PLAN 0.1.102 "Votes"): "<service>: <proposal title>", the title from the Votes list in
     * memory; the service alone without it. Never the member's choice (no feed item has it).
     */
    private fun voteLine(item: FeedItem, names: FeedNames): FeedText? {
        val who = connectionName(item, names)
        val title = names.votes[VoteTitles.key(item.connectionId, item.ref)] ?: return who
        return FeedText(R.string.data_feed_vote_line, listOf(who, title))
    }

    /** The second line of ANDROID-PLAN 0.1.52 §4 "Event titles", for the kinds that have one. */
    private fun eventLine(item: FeedItem): FeedText? = when (item.kind) {
        "recovery.change_required" -> FeedText(
            if (item.ref == REF_PASSWORD) R.string.data_feed_change_required_password_body else R.string.data_feed_change_required_body,
        )
        "identity.rotation_failed" -> FeedText(R.string.data_feed_rotation_failed_body)
        "identity.rotate_conflict" -> FeedText(R.string.data_feed_rotate_conflict_body)
        "recovery.generation_closed" -> FeedText(R.string.data_feed_recovery_rollback_body)
        else -> null
    }

    private const val REF_PASSWORD = "password"

    private fun things(n: Int) = FeedText(plural = R.plurals.data_feed_things, quantity = n, args = listOf(n))

    /** Where [item] leads, before checking that it still exists (the screen's ViewModel does that). */
    @Suppress("CyclomaticComplexMethod") // one target per kind
    fun target(item: FeedItem, open: Collection<Approval>? = null): FeedTarget {
        val c = item.connectionId
        val ref = item.ref
        FeedAsks.approvalKey(item.kind, item.ref)?.let { return askTarget(item, it, open) }
        return when (item.kind) {
            "connection.request.peer_declined" -> c?.let { FeedTarget.Connection(it) } ?: FeedTarget.Connections
            "connection.added", "connection.stale", "connection.asks_paused" -> c?.let { FeedTarget.Connection(it) } ?: FeedTarget.Sheet
            "message.received", "call.missed", "message.attachment_failed", "message.attachment_refused" ->
                c?.let { FeedTarget.Conversation(it) } ?: FeedTarget.Sheet
            "grant.shared", "grant.revoked" -> c?.let { FeedTarget.SharedWithYou(it) } ?: FeedTarget.Sheet
            // L1b: "{First} is sharing their location with you" opens the connection.
            "location.shared" -> c?.let { FeedTarget.Connection(it) } ?: FeedTarget.Sheet
            "share.rate_limited" -> if (c != null && !ref.isNullOrEmpty()) FeedTarget.ShareRule(c, ref) else FeedTarget.Sheet
            "item.revealed" -> if (!ref.isNullOrEmpty()) FeedTarget.Item(ref) else FeedTarget.Sheet
            "wallet.cap_refused", "wallet.caps_lowered", "wallet.caps_raised" ->
                if (!ref.isNullOrEmpty()) FeedTarget.WalletLimits(ref) else FeedTarget.Sheet
            // ANDROID-PLAN §4 "Notifications": "Bitcoin payment signed" opens the transaction.
            "wallet.signed" -> if (!ref.isNullOrEmpty()) FeedTarget.WalletTransaction(ref) else FeedTarget.Sheet
            // 0.1.53: the pairing (AA2 opens its comparison), the device and the list are in Devices & agents.
            "device.paired" -> item.deviceId?.takeIf { it.isNotEmpty() }?.let { FeedTarget.Device(it) } ?: FeedTarget.DeviceList
            "device.pair.pending" -> FeedTarget.Pairing
            "device.unlinked" -> FeedTarget.DeviceList
            // §10.9 (0.87.0): another of the member's devices needs an update; Devices & agents lists it.
            "device.update_needed" -> FeedTarget.DeviceList
            // AA5: the agent's page (the Resume banner for a paused agent); the list when the item names no device.
            "leash.agent.suspended", "leash.referrals_limited", "leash.rate_limited" ->
                item.deviceId?.takeIf { it.isNotEmpty() }?.let { FeedTarget.Device(it) } ?: FeedTarget.DeviceList
            "device.transferred", "device.replaced", "device.signer_changed" -> FeedTarget.Devices
            "vote.open", "vote.closing", "vote.result", "vote.problem" ->
                if (c != null && !ref.isNullOrEmpty()) FeedTarget.Vote(c, ref) else FeedTarget.VoteList
            "credential.alarm" -> FeedTarget.Alarm
            "credential.password_failed" -> FeedTarget.History
            "credential.rotated", "credential.reset" -> FeedTarget.Credential
            "owner_check.failed", "owner_check.locked", "owner_check.hold_changed" -> FeedTarget.Security
            else -> FeedTarget.Sheet
        }
    }

    /**
     * An ask's item leads to its ask. A batch's item stands for several asks and has the first one's `ref`, which may have
     * been decided or withdrawn since (0.80.0): with [open] (the asks now waiting) such an item leads to the one ask left,
     * or to the Approvals list when several are, never to an ask that is gone while others wait.
     */
    private fun askTarget(item: FeedItem, key: String, open: Collection<Approval>?): FeedTarget {
        val c = item.connectionId
        val applies = open != null && c != null && item.kind in FeedAsks.ASK_KINDS && (item.count ?: 0) > 1
        val mine = if (applies) open.orEmpty().filter { AskBatching.connectionOf(it) == c } else emptyList()
        val own = mine.size == 1 && mine.single().key == key
        return when {
            // A batch whose asks are not listed (yet: a cold start before pending.list) opens the list, which fills as it loads.
            applies && mine.isEmpty() -> FeedTarget.Approvals
            own || mine.isEmpty() -> FeedTarget.ApprovalEntry(key)
            mine.size == 1 -> FeedTarget.ApprovalEntry(mine.single().key)
            else -> FeedTarget.Approvals
        }
    }

    /** `urgent` (the clone alarm, the ten-failure lock): Needs attention while unread, a red badge. */
    fun urgent(item: FeedItem): Boolean = item.priority == FeedManager.PRIORITY_URGENT
}

/** The key of a vote's title in [FeedNames.votes] and History's rows: the pair (0.81.2), never the proposal id alone. */
object VoteTitles {
    fun key(connectionId: String?, proposalId: String?): String = "${connectionId.orEmpty()}/${proposalId.orEmpty()}"
}
