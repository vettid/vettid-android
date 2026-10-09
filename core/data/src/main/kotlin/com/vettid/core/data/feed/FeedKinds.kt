package com.vettid.core.data.feed

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.vettid.core.data.R
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.ConnectionInfo
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
)

/**
 * Where a feed item leads (ANDROID-PLAN 0.1.23, 4); the app maps each to its screen. [Sheet] is the detail sheet,
 * for kinds with no screen, and for a target that is gone.
 */
sealed interface FeedTarget {
    data class ApprovalEntry(val key: String) : FeedTarget

    data class Connection(val connectionId: String) : FeedTarget

    data object Connections : FeedTarget

    data class Conversation(val connectionId: String) : FeedTarget

    data class SharedWithYou(val connectionId: String) : FeedTarget

    data class ShareRule(val connectionId: String, val ruleId: String) : FeedTarget

    data class Item(val itemId: String) : FeedTarget

    /** Settings → Vault (the devices). */
    data object Devices : FeedTarget

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
        "message.received", "call.missed",
        "grant.request", "grant.shared", "grant.revoked", "share.pending", "share.rate_limited",
        "critical-secret.use.request", "item.revealed",
        "device.pair.pending", "device.paired", "device.unlinked", "device.transferred", "device.replaced",
        "device.session.pending", "approval.pending",
        "credential.alarm", "credential.password_failed", "credential.rotated", "credential.reset",
        "owner_check.failed", "owner_check.locked", "owner_check.hold_changed",
        "guide",
        "leash.rate_limited", "leash.item.read", "leash.agent.suspended", "leash.referrals_limited",
        "action.request", "intro.request", "location.shared", "location.request", "wallet.signed",
    )

    /** Kinds whose screens come later (§6): listed, with "Not available in this version of the app". */
    fun notAvailable(kind: String): Boolean =
        kind.startsWith("leash.") || kind in LATER

    private val LATER = setOf("action.request", "intro.request", "location.shared", "location.request", "wallet.signed")

    fun known(kind: String): Boolean = kind in KNOWN || kind.startsWith("leash.")

    /** An ask (a decision waits in Approvals): "This is no longer waiting" when it is gone. */
    fun isAsk(kind: String): Boolean = FeedAsks.approvalKey(kind, "x") != null

    private const val OFF_UNTIL = "off_until:"

    /** The connection's "First Last" (never an alias), the request's sender, or "Removed connection". */
    fun connectionName(item: FeedItem, names: FeedNames): FeedText {
        val c = item.connectionId?.let { names.connections[it] }
        val sender = FeedAsks.approvalKey(item.kind, item.ref)?.let { names.approvals[it] }?.let { a -> senderOf(a) }
        val name = c?.accountName ?: c?.name?.takeIf { it.isNotBlank() } ?: sender
        return when {
            name != null -> FeedText(literal = name)
            c != null -> FeedText(R.string.data_feed_name_not_shared)
            item.connectionId != null -> FeedText(R.string.data_feed_removed_connection)
            else -> FeedText(R.string.data_feed_someone)
        }
    }

    /** The connection's first name (the short labels), else as [connectionName]. */
    fun firstName(item: FeedItem, names: FeedNames): FeedText {
        val c = item.connectionId?.let { names.connections[it] }
        c?.firstName?.takeIf { it.isNotBlank() }?.let { return FeedText(literal = it) }
        return connectionName(item, names)
    }

    private fun senderOf(a: Approval): String? = when (a) {
        is Approval.ConnectionRequest -> a.name ?: a.displayName
        else -> a.connectionName
    }?.takeIf { it.isNotBlank() }

    /** The item's name (never a value), when the item or the ask names one. */
    fun itemName(item: FeedItem, names: FeedNames): String? = when (item.kind) {
        "item.revealed" -> item.ref?.let { names.items[it] }
        "grant.request" -> (names.approvals[FeedAsks.approvalKey(item.kind, item.ref) ?: ""] as? Approval.GrantRequest)
            ?.entries?.singleOrNull()?.let { it.name ?: it.label }
        "critical-secret.use.request" ->
            (names.approvals[FeedAsks.approvalKey(item.kind, item.ref) ?: ""] as? Approval.CriticalUse)?.itemName
        else -> null
    }?.takeIf { it.isNotBlank() }

    /** The tag a `share.pending` rule is named by (0.1.22); null when unknown. */
    private fun ruleTag(item: FeedItem, names: FeedNames): String? =
        (names.approvals[FeedAsks.approvalKey(item.kind, item.ref) ?: ""] as? Approval.ShareDecision)?.let { d ->
            d.rule?.tags?.firstOrNull() ?: d.tags.firstOrNull()
        }

    /** The row's title (the label of the plan's table). */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // one label per kind
    fun title(item: FeedItem, names: FeedNames): FeedText {
        val who = connectionName(item, names)
        val first = firstName(item, names)
        val thing = itemName(item, names)
        val count = item.count ?: 0
        return when (item.kind) {
            "connection.request" -> FeedText(R.string.data_feed_connection_request, listOf(who))
            "connection.request.peer_declined" -> FeedText(R.string.data_feed_connection_peer_declined, listOf(who))
            "connection.added" -> FeedText(R.string.data_feed_connection_added, listOf(who))
            "connection.removed" -> FeedText(R.string.data_feed_connection_removed, listOf(who))
            "connection.stale" -> FeedText(R.string.data_feed_connection_stale, listOf(who))
            "connection.asks_paused" -> FeedText(R.string.data_feed_asks_paused, listOf(first))
            "connection.authenticate.requested" -> FeedText(R.string.data_feed_authenticate, listOf(who))
            "message.received" -> FeedText(R.string.data_feed_message, listOf(who))
            "call.missed" -> FeedText(R.string.data_feed_call_missed, listOf(who))
            "grant.request" -> when {
                count > 1 -> FeedText(plural = R.plurals.data_feed_grant_request_batch, quantity = count, args = listOf(first, count))
                thing != null -> FeedText(R.string.data_feed_grant_request, listOf(first, thing))
                else -> FeedText(R.string.data_feed_grant_request_any, listOf(first))
            }
            "grant.shared" -> FeedText(R.string.data_feed_grant_shared, listOf(first))
            "grant.revoked" -> FeedText(R.string.data_feed_grant_revoked, listOf(first))
            "share.pending" -> ruleTag(item, names)?.let { FeedText(R.string.data_feed_share_pending, listOf(it, first)) }
                ?: FeedText(R.string.data_feed_share_pending_any, listOf(first))
            "share.rate_limited" -> FeedText(R.string.data_feed_share_rate_limited, listOf(first))
            "critical-secret.use.request" -> thing?.let { FeedText(R.string.data_feed_critical_use, listOf(first, it)) }
                ?: FeedText(R.string.data_feed_critical_use_any, listOf(first))
            "item.revealed" -> {
                val name = thing?.let { FeedText(literal = it) } ?: FeedText(R.string.data_feed_deleted_item)
                FeedText(R.string.data_feed_item_revealed, listOf(name))
            }
            "device.pair.pending" -> FeedText(R.string.data_feed_device_pair_pending)
            "device.paired" -> FeedText(R.string.data_feed_device_paired)
            "device.unlinked" -> FeedText(R.string.data_feed_device_unlinked)
            "device.transferred" -> FeedText(R.string.data_feed_device_transferred)
            "device.replaced" -> FeedText(R.string.data_feed_device_replaced)
            "device.session.pending" -> FeedText(R.string.data_feed_device_session)
            "approval.pending" -> FeedText(R.string.data_feed_device_approval)
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
            "leash.rate_limited" -> FeedText(R.string.data_feed_leash_rate_limited)
            "leash.item.read" -> FeedText(R.string.data_feed_leash_item_read)
            "leash.agent.suspended" -> FeedText(R.string.data_feed_leash_suspended)
            "leash.referrals_limited" -> FeedText(R.string.data_feed_leash_referrals)
            "action.request" -> FeedText(R.string.data_feed_action_request, listOf(first))
            "intro.request" -> FeedText(R.string.data_feed_intro_request, listOf(first))
            "location.shared" -> FeedText(R.string.data_feed_location_shared, listOf(first))
            "location.request" -> FeedText(R.string.data_feed_location_request, listOf(first))
            "wallet.signed" -> FeedText(R.string.data_feed_wallet_signed)
            else -> if (item.kind.startsWith("leash.")) FeedText(R.string.data_feed_leash) else unknown(item.kind)
        }
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

    /** The row's second line: the item's name or the batch's "3 things"; null for none. */
    fun supporting(item: FeedItem, names: FeedNames): FeedText? = when {
        (item.count ?: 0) > 1 -> things(item.count ?: 0)
        item.kind == "guide" -> item.body?.takeIf { it.isNotBlank() }?.let { FeedText(literal = it.lineSequence().first()) }
        else -> itemName(item, names)?.let { FeedText(literal = it) }
    }

    private fun things(n: Int) = FeedText(plural = R.plurals.data_feed_things, quantity = n, args = listOf(n))

    /** Where [item] leads, before checking that it still exists (the screen's ViewModel does that). */
    @Suppress("CyclomaticComplexMethod") // one target per kind
    fun target(item: FeedItem): FeedTarget {
        val c = item.connectionId
        val ref = item.ref
        FeedAsks.approvalKey(item.kind, item.ref)?.let { return FeedTarget.ApprovalEntry(it) }
        return when (item.kind) {
            "connection.request.peer_declined" -> c?.let { FeedTarget.Connection(it) } ?: FeedTarget.Connections
            "connection.added", "connection.stale", "connection.asks_paused" -> c?.let { FeedTarget.Connection(it) } ?: FeedTarget.Sheet
            "message.received", "call.missed" -> c?.let { FeedTarget.Conversation(it) } ?: FeedTarget.Sheet
            "grant.shared", "grant.revoked" -> c?.let { FeedTarget.SharedWithYou(it) } ?: FeedTarget.Sheet
            "share.rate_limited" -> if (c != null && !ref.isNullOrEmpty()) FeedTarget.ShareRule(c, ref) else FeedTarget.Sheet
            "item.revealed" -> if (!ref.isNullOrEmpty()) FeedTarget.Item(ref) else FeedTarget.Sheet
            "device.pair.pending", "device.paired", "device.unlinked", "device.transferred", "device.replaced" -> FeedTarget.Devices
            "credential.alarm" -> FeedTarget.Alarm
            "credential.password_failed" -> FeedTarget.History
            "credential.rotated", "credential.reset" -> FeedTarget.Credential
            "owner_check.failed", "owner_check.locked", "owner_check.hold_changed" -> FeedTarget.Security
            else -> FeedTarget.Sheet
        }
    }

    /** `urgent` (the clone alarm, the ten-failure lock): Needs attention while unread, a red badge. */
    fun urgent(item: FeedItem): Boolean = item.priority == FeedManager.PRIORITY_URGENT
}
