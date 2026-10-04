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
)

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
)

/** `connection.invite.create` (§10.4): the link to show as a QR code or share. */
@Serializable
data class Invite(
    @SerialName("invite_id") val inviteId: String,
    val link: String,
    val exp: String,
    val remote: Boolean = false,
)

/** `connection.invite.accept` response (§10.4); [sas] only if the vault sends it (not in 0.10.1). */
@Serializable
data class AcceptedInvite(
    @SerialName("connection_id") val connectionId: String,
    val state: String = "pending",
    val sas: String? = null,
)

/** `connection.request.pending` (§10.4): approve after comparing [sas]. */
@Serializable
data class ConnectionRequest(
    @SerialName("pending_id") val pendingId: String,
    @SerialName("invite_id") val inviteId: String? = null,
    val sas: String,
    val remote: Boolean = false,
    val profile: JsonObject? = null,
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
)

/** What an app writes in `item.put`. */
data class ItemContent(
    val name: String,
    val category: String? = null,
    val template: String? = null,
    val fields: List<ItemField>? = null,
    val notes: String? = null,
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

/** `profile.get` (§10.8). */
@Serializable
data class Profile(val version: Long = 0, val name: String = "", val photo: String? = null)

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
)

/** `sync.event` (§10.1): [kind] and its members; devices fetch what changed. */
data class SyncEvent(val kind: String, val body: JsonObject)
