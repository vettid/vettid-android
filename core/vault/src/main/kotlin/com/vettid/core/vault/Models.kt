package com.vettid.core.vault

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Body models of VAULT-MESSAGING §10 that the v1 app reads. Unknown members
// are ignored (§10.1); members the app does not read stay in the raw bodies.

/** `vault.status` (§10.2). */
@Serializable
data class VaultStatusInfo(
    @SerialName("vault_id") val vaultId: String,
    @SerialName("state_seq") val stateSeq: Long = 0,
    @SerialName("header_seq") val headerSeq: Long = 0,
    val provisional: Boolean = false,
    val devices: Int = 0,
    val connections: Int = 0,
    /** The daily owner check (§3.6, 0.13.0); absent from a vault older than 0.13.0. */
    @SerialName("owner_check") val ownerCheck: OwnerCheckStatus? = null,
)

/**
 * `vault.status`'s `owner_check` (§3.6, §3.6.7): `state` is `ok`, `due` (past the deadline with the hold off: the
 * app is gated) or `held` (past the deadline with the hold on). [waiting] (VAULT-MESSAGING 0.19.0, while `due` or
 * `held`): what arrived since the deadline, as `vault.held` counts it; null from a vault that does not send it.
 */
@Serializable
data class OwnerCheckStatus(
    val state: String,
    val deadline: String? = null,
    @SerialName("interval_seconds") val intervalSeconds: Long? = null,
    val failures: Int = 0,
    val hold: Boolean = true,
    @SerialName("hold_off_until") val holdOffUntil: String? = null,
    val waiting: HeldCounts? = null,
)

/** `vault.owner_check`'s answer (§3.6.1), less the blob and UTKs the credential layer keeps. */
@Serializable
data class OwnerCheckPassed(
    val deadline: String? = null,
    @SerialName("interval_seconds") val intervalSeconds: Long? = null,
    val hold: Boolean = true,
    @SerialName("hold_off_until") val holdOffUntil: String? = null,
)

/** `vault.held` (§3.6.3): content-free counts of what waits since the deadline. */
@Serializable
data class HeldNotice(val deadline: String? = null, val waiting: HeldCounts = HeldCounts())

/** `vault.held`'s `waiting`. */
@Serializable
data class HeldCounts(val messages: Int = 0, val requests: Int = 0, val calls: Int = 0, val other: Int = 0)

/** `credential.version` (§10.6). */
@Serializable
data class CredentialInfo(
    val exists: Boolean,
    val version: Long? = null,
    val key: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val alarm: JsonObject? = null,
)

/** A connection (§10.4). `name` and `profile` are the peer's self-asserted values. */
@Serializable
data class Connection(
    val id: String,
    val kind: String = "connection",
    val state: String,
    val name: String = "",
    val ik: String = "",
    val profile: JsonObject? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_active_at") val lastActiveAt: String? = null,
    val version: Long = 0,
    val alias: String? = null,
    val note: String? = null,
    val tags: List<String> = emptyList(),
    val favorite: Boolean = false,
    val archived: Boolean = false,
    /** The connection's asks (VAULT-MESSAGING 0.23.0 §10.4.1); absent from an older vault. */
    val asks: ConnectionAsks? = null,
)

/** `<connection>.asks` (§10.4.1, 0.23.0): muted, paused (since [pausedAt]) and the number of cooldowns in force. */
@Serializable
data class ConnectionAsks(
    val muted: Boolean = false,
    val paused: Boolean = false,
    @SerialName("paused_at") val pausedAt: String? = null,
    val cooldowns: Int = 0,
)

/** `connection.invite.create` (§10.4): the link to show as a QR code or share. */
@Serializable
data class Invite(
    @SerialName("invite_id") val inviteId: String,
    val link: String,
    val exp: String,
    val remote: Boolean = false,
)

/**
 * `connection.invite.accept` response (§10.4, 0.10.3): an outgoing request in
 * state `waiting`; its SAS follows in `connection.request.outgoing` once the
 * handshake has run. [name] is the bundle's `hint.name`.
 */
@Serializable
data class AcceptedInvite(
    @SerialName("connection_id") val connectionId: String,
    val state: String = "waiting",
    val remote: Boolean = false,
    val exp: String? = null,
    val name: String? = null,
)

/**
 * An incoming connection request (§10.4): `connection.request.pending`, or an
 * entry of `connection.request.list`'s `incoming` ([peerApproved],
 * [createdAt] only there). [state] is `pending` or `approved`.
 */
@Serializable
data class IncomingRequest(
    @SerialName("pending_id") val pendingId: String,
    @SerialName("invite_id") val inviteId: String? = null,
    val sas: String,
    val remote: Boolean = false,
    val state: String = "pending",
    @SerialName("peer_approved") val peerApproved: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    val exp: String? = null,
    val profile: JsonObject? = null,
    @SerialName("introduced_by") val introducedBy: String? = null,
)

/**
 * An outgoing connection request (§10.4): `connection.request.outgoing`, or an
 * entry of `connection.request.list`'s `outgoing`. [state] is `waiting` (no
 * SAS yet), `pending` or `approved`.
 */
@Serializable
data class OutgoingRequest(
    @SerialName("connection_id") val connectionId: String,
    val sas: String? = null,
    val remote: Boolean = false,
    val state: String = "pending",
    @SerialName("peer_approved") val peerApproved: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    val exp: String? = null,
    val name: String? = null,
    @SerialName("introduced_by") val introducedBy: String? = null,
)

/** `connection.event` (§10.4). */
@Serializable
data class ConnectionEvent(@SerialName("connection_id") val connectionId: String, val event: String)

/** `message.new` and the history (§10.5). */
@Serializable
data class Message(
    @SerialName("connection_id") val connectionId: String,
    @SerialName("message_id") val messageId: String,
    val direction: String,
    val text: String,
    @SerialName("sent_at") val sentAt: String,
    val delivered: Boolean = false,
    val read: Boolean = false,
)

/** `message.send` response. */
@Serializable
data class SentMessage(@SerialName("message_id") val messageId: String, @SerialName("sent_at") val sentAt: String)

/** One field of an item (§10.7). [value] is a string, or an object for `address`; absent for hidden values. */
@Serializable
data class ItemField(
    @SerialName("field_id") val fieldId: String? = null,
    val label: String,
    val kind: String,
    val value: JsonElement? = null,
)

/** An item (§10.7); `secret` and `critical` items come without values and notes unless revealed. */
@Serializable
data class Item(
    @SerialName("item_id") val itemId: String,
    val version: Long,
    val name: String,
    val category: String = "other",
    val sensitivity: String = "data",
    val template: String? = null,
    val tags: List<String> = emptyList(),
    val fields: List<ItemField> = emptyList(),
    val notes: String? = null,
    @SerialName("has_notes") val hasNotes: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    /**
     * The item's size as the vault counts it (§10.7 Size; `item.get` since 0.21.0): null from an older vault, and for
     * a critical item the vault has not written or opened since it was upgraded.
     */
    val size: Long? = null,
)

/**
 * What an app writes in `item.put`. A field of a replacement without `value` keeps its stored value, and
 * [keepNotes] the stored notes (§10.7 Kept values, 0.21.0); [notes] and [keepNotes] never go together.
 */
data class ItemContent(
    val name: String,
    val category: String? = null,
    val template: String? = null,
    val fields: List<ItemField>? = null,
    val notes: String? = null,
    val keepNotes: Boolean = false,
)

/** `item.put` response; for a critical item also the credential's new version. */
@Serializable
data class ItemRef(
    @SerialName("item_id") val itemId: String,
    val version: Long,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("credential_version") val credentialVersion: Long? = null,
)

@Serializable
data class ItemPage(val items: List<Item> = emptyList(), val next: String? = null)

/** `tag.list` entry (§10.8). */
@Serializable
data class TagInfo(
    val tag: String,
    val color: String? = null,
    val icon: String? = null,
    val description: String? = null,
    val items: Int = 0,
    val rules: List<String> = emptyList(),
)

@Serializable
data class TagPage(val version: Long = 0, val tags: List<TagInfo> = emptyList(), val next: String? = null)

/**
 * `profile.get` (§10.8): the profile object (the display [name], absent as "", and the photo) and, since 0.18.0,
 * the shared profile's read-only core: the account's [firstName] and [lastName] from the vault's snapshot and the
 * vault's [ik]. The core is null from a vault before 0.18.0.
 */
@Serializable
data class Profile(
    val version: Long = 0,
    val name: String = "",
    val photo: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    val ik: String? = null,
)

/** `settings.get` (§10.8). */
@Serializable
data class Settings(val version: Long = 0, val settings: JsonObject = JsonObject(emptyMap()))

/** An activity feed item (§10.9). */
@Serializable
data class FeedItem(
    @SerialName("item_id") val itemId: String,
    val seq: Long,
    val kind: String,
    val at: String,
    val status: String,
    val priority: String = "normal",
    @SerialName("connection_id") val connectionId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    val ref: String? = null,
    val title: String? = null,
    val body: String? = null,
)

@Serializable
data class FeedPage(val items: List<FeedItem> = emptyList(), val seq: Long = 0)

/** An audit log entry (§10.9). */
@Serializable
data class AuditEntry(
    @SerialName("entry_id") val entryId: String,
    val seq: Long,
    val at: String,
    val kind: String,
    @SerialName("connection_id") val connectionId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    val ref: String? = null,
    val direction: String? = null,
    val prev: String,
    val hash: String,
)

@Serializable
data class AuditPage(
    val entries: List<AuditEntry> = emptyList(),
    val head: String? = null,
    val seq: Long = 0,
    @SerialName("next_before_seq") val nextBeforeSeq: Long? = null,
    @SerialName("next_after_seq") val nextAfterSeq: Long? = null,
    /** A search that ran out of its 2,000-entry scan budget (0.20.0): continue with the cursor. */
    val partial: Boolean = false,
)

/**
 * `audit.export`'s answer (VAULT-MESSAGING 0.22.0 §10.9 History export), a preview's and an export's: [count]
 * (0–10,000, newest first) entries match, [more] beyond the cap; [uptoSeq] bounds the export and [uptoHash] (base64,
 * 32 bytes) is its entry's hash; the range only when [count] > 0; [entrySeq] the `audit.exported` entry (an export,
 * not a preview).
 */
@Serializable
data class AuditExportAnswer(
    val count: Long,
    val more: Boolean = false,
    @SerialName("upto_seq") val uptoSeq: Long,
    @SerialName("upto_hash") val uptoHash: String,
    @SerialName("oldest_seq") val oldestSeq: Long? = null,
    @SerialName("newest_seq") val newestSeq: Long? = null,
    @SerialName("oldest_at") val oldestAt: String? = null,
    @SerialName("newest_at") val newestAt: String? = null,
    @SerialName("entry_seq") val entrySeq: Long? = null,
)

/** The filters of `audit.list` that `audit.export` takes (§10.9): no cursor, no `limit`. [since]/[until] as sent. */
data class AuditExportFilters(
    val connectionId: String? = null,
    val kinds: List<String>? = null,
    val q: String? = null,
    val since: String? = null,
    val until: String? = null,
)

/** `sync.event` (§10.1): [kind] and its members; devices fetch what changed. */
data class SyncEvent(val kind: String, val body: JsonObject)

/** `device.transfer.create`'s answer (§10.3): [link] is the pairing link the new phone scans (QR `t: "p"`). */
data class TransferOffer(val transferId: String, val link: String, val exp: String?)

/** `device.transfer.pending` (§10.3): the new app's self-asserted [name] and the SAS to compare. */
data class TransferPending(val transferId: String, val name: String, val sas: String)

/**
 * The member's account snapshot from the member API (§11.13, 0.15.0): display only, never a security signal.
 * `email` (VAULT-MESSAGING 0.20.0 / MEMBER-API 2.3.0) is the member's full verified address, shown only to the member;
 * it replaces the masked `email_hint` (`m***@example.com`) of 0.15.0. Both are optional here so that a snapshot of
 * either shape reads (a staging S4 vault stores what the member API sent); the app shows [email] and falls back to
 * [emailHint].
 */
@Serializable
data class AccountSnapshot(
    val v: Int = 1,
    @SerialName("as_of") val asOf: String? = null,
    @SerialName("email_hint") val emailHint: String? = null,
    /** The full address (0.20.0); absent from a vault before it. */
    val email: String? = null,
    /** `member` (or another account state). */
    val state: String? = null,
    /** `active` or `canceled`. */
    @SerialName("account_status") val accountStatus: String? = null,
    @SerialName("deletes_at") val deletesAt: String? = null,
    val terms: AccountTerms? = null,
    val subscription: AccountSubscription? = null,
    @SerialName("voting_rights") val votingRights: Boolean = false,
    /** The account's names (0.18.0): what every connection sees in the shared profile's core (§10.8). */
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    /** The member API's state of the name changes (0.18.0, §11.13). */
    @SerialName("name_change") val nameChange: AccountNameChange? = null,
)

/**
 * The snapshot's `name_change` (§11.13): [allowedAfter], when the next change may be applied (null: now), and
 * [last], the outcome of this vault's latest `account.name.set` the member API processed.
 */
@Serializable
data class AccountNameChange(
    @SerialName("allowed_after") val allowedAfter: String? = null,
    val last: AccountNameResult? = null,
)

/** `name_change.last` (§11.13, 0.19.0): [status] `applied` or `refused`; [reason] only with `refused`. */
@Serializable
data class AccountNameResult(val seq: Long, val status: String, val reason: String? = null)

/**
 * A name request (§10.8, 0.18.0): the latest `account.name.set`, as `account.name.set` and `account.get` answer it.
 * [state] is `pending`, `applied` or `refused`; [reason] (`too_soon`, `invalid`, `account`) only with `refused`.
 */
@Serializable
data class NameRequest(
    val seq: Long,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    @SerialName("requested_at") val requestedAt: String? = null,
    val state: String,
    val reason: String? = null,
)

@Serializable
data class AccountTerms(@SerialName("needs_acceptance") val needsAcceptance: Boolean = false)

@Serializable
data class AccountSubscription(
    @SerialName("type_name") val typeName: String? = null,
    /** `trial`, `active`, `expired` or `canceled`. */
    val status: String? = null,
    val paid: Boolean = false,
    @SerialName("expires_at") val expiresAt: String? = null,
)

/**
 * `account.get` (§10.2, 0.15.0): `account` null (and `version` 0) before any snapshot arrived. A change of
 * `name_request` alone repeats the stored `version` in `sync.event{account.changed}` (0.19.0): never skip one.
 */
@Serializable
data class AccountView(
    val account: AccountSnapshot? = null,
    val version: Long = 0,
    @SerialName("received_at") val receivedAt: String? = null,
    /** The latest `account.name.set` request (0.18.0, §10.8); absent before the first. */
    @SerialName("name_request") val nameRequest: NameRequest? = null,
)

/**
 * A received grant's fetch (§10.12): the opened content (`{item_id, version, name, category, fields, notes?}`, wipe it
 * after use) and the uses left, or the member's vault's refusal [error] (`not_found`, `revoked`, `expired`,
 * `exhausted`, `unavailable`).
 */
class GrantFetched(
    val grantId: String,
    val content: ByteArray?,
    val usesLeft: Long?,
    val error: String?,
    /** With `rate_limited` (0.23.0 §10.12): whole seconds until the fetch can succeed again. */
    val retryAfter: Long? = null,
) {
    override fun toString(): String = "GrantFetched($grantId, error=$error)"
}
