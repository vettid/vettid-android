package com.vettid.core.data.social

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CredentialKeyFingerprint
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.IkFingerprint
import com.vettid.core.data.account.AccountNames
import com.vettid.core.vault.Connection
import com.vettid.core.vault.Message
import com.vettid.core.vault.VaultJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Turns vault bodies (VAULT-MESSAGING §10.4, §10.5, §10.12, §10.13, §6.8)
 * into the app's models. Bodies come from the member's own vault inside an
 * authenticated session; unknown members are ignored (§10.1) and a body
 * missing a required member yields null.
 */
@Suppress("TooManyFunctions")
object ApprovalParser {
    /** The event types the Approvals screen shows. */
    val TYPES = setOf(
        "connection.authenticate.pending", "grant.pending", "critical-secret-use.pending",
        "share.pending", "approval.pending", "device.session.pending", "location.request.pending",
    )

    private fun JsonObject.s(k: String): String? = VaultJson.str(this, k)

    private fun JsonObject.l(k: String): Long? = VaultJson.long(this, k)

    private fun JsonObject.b(k: String): Boolean? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.o(k: String): JsonObject? = this[k] as? JsonObject

    private fun JsonObject.a(k: String): List<JsonObject> = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    /** A device's own name, cleaned and capped as `device.list`'s are (`DeviceWire.cleanName`); null if nothing shows. */
    private fun name(body: JsonObject): String? =
        com.vettid.core.data.devices.DeviceWire.cleanName(body.s("name")).takeIf { it.isNotEmpty() }

    fun instant(s: String?): Instant? = s?.let {
        try {
            Instant.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** One approval from an event of [type] received at [at]. */
    @Suppress("CyclomaticComplexMethod")
    fun parse(type: String, body: JsonObject, at: Instant): Approval? = when (type) {
        "connection.request.pending" -> incoming(body, at)
        "connection.request.outgoing" -> outgoing(body, at)
        "connection.authenticate.pending" -> {
            val id = body.s("request_id")
            val conn = body.s("connection_id")
            if (id == null || conn == null) {
                null
            } else {
                val purpose = AuthPurpose.of(body.s("purpose"))
                Approval.Authentication(
                    id, conn, body.s("context"), at, instant(body.s("exp")), purpose = purpose,
                    matchCode = AuthPurpose.matchCode(purpose, body.s("match_code")),
                )
            }
        }
        "location.request.pending" -> locationRequest(body, at)
        "grant.pending" -> grant(body, at)
        "critical-secret-use.pending" -> critical(body, at)
        "share.pending" -> {
            val rule = body.s("rule_id")
            if (rule == null) {
                null
            } else {
                val subject = body.o("subject")
                Approval.ShareDecision(
                    ruleId = rule,
                    subjectConnectionId = subject?.s("connection_id"),
                    subjectAgentId = subject?.s("agent_id"),
                    items = body.a("items").mapNotNull(::shareItem),
                    reason = body.s("reason"),
                    receivedAt = at,
                )
            }
        }
        "approval.pending" -> body.s("approval_id")?.let {
            Approval.DeviceRequest(
                type, it, name(body), body.s("role"), body.s("type"), at, instant(body.s("exp")), deviceId = body.s("device_id"),
                held = HeldRequests.of(body.s("role"), body.s("type"), body.o("body")),
            )
        }
        "device.session.pending" -> body.s("request_id")?.let {
            Approval.DeviceRequest(
                type, it, name(body), body.s("role"), null, at, instant(body.s("exp")), deviceId = body.s("device_id"),
                seconds = body.l("seconds")?.takeIf { s -> s in 1..Int.MAX_VALUE }?.toInt(),
            )
        }
        else -> null
    }

    /**
     * `location.request.pending`: `{request_id, connection_id, note?, exp}`. A `note` that is present and not a string, or over 256
     * bytes, makes the request unreadable; a request without `exp` is not shown (the vault always sends it, and it ends the ask).
     */
    fun locationRequest(body: JsonObject, at: Instant): Approval.LocationRequest? {
        val id = body.s("request_id")?.takeIf { it.isNotEmpty() }
        val conn = body.s("connection_id")?.takeIf { it.isNotEmpty() }
        val exp = instant(body.s("exp"))
        val noteRaw = body["note"]
        val note = body.s("note")
        val max = com.vettid.core.vault.LocationRequestLimits.NOTE_MAX_BYTES
        val noteOk = noteRaw == null || (note != null && note.toByteArray(Charsets.UTF_8).size <= max)
        val whole = id != null && conn != null && exp != null
        return if (whole && noteOk) Approval.LocationRequest(id!!, conn!!, note, at, exp!!) else null
    }

    /** An item of `share.pending` or an entry of `share.pending.list` (§10.12; 0.23.0 `ask_rule_id`, `shared`). */
    fun shareItem(i: JsonObject): ShareItem? = i.s("item_id")?.let {
        ShareItem(
            it, i.s("name") ?: "", i.s("category") ?: "other", i.s("sensitivity") ?: "data",
            askRuleId = i.s("ask_rule_id")?.takeIf { r -> r.isNotEmpty() },
            shared = i.b("shared") ?: false,
        )
    }

    /**
     * `connection.request.pending`, or an entry of `connection.request.list`'s
     * `incoming` (§10.4): approve after comparing the safety code.
     */
    @Suppress("ReturnCount")
    fun incoming(body: JsonObject, at: Instant): Approval.ConnectionRequest? {
        val id = body.s("pending_id") ?: return null
        val sas = body.s("sas")?.takeIf { SAS.matches(it) } ?: return null
        return Approval.ConnectionRequest(
            pendingId = id,
            inviteId = body.s("invite_id"),
            sas = sas,
            remote = body.b("remote") ?: false,
            name = PeerProfile.accountName(body.o("profile")),
            introducedBy = body.s("introduced_by"),
            receivedAt = instant(body.s("created_at")) ?: at,
            exp = instant(body.s("exp")) ?: at.plus(CONNECTION_REQUEST_TTL),
            state = RequestState.of(body.s("state")),
            peerApproved = body.b("peer_approved") ?: false,
            displayName = PeerProfile.displayName(body.o("profile"))?.takeIf { it != PeerProfile.accountName(body.o("profile")) },
        )
    }

    /**
     * `connection.request.outgoing` (state `pending`: the SAS is known), or an
     * entry of `connection.request.list`'s `outgoing` (§10.4, 0.10.3).
     */
    fun outgoing(body: JsonObject, at: Instant): Approval.OutgoingRequest? {
        val id = body.s("connection_id") ?: return null
        val sas = body.s("sas")?.takeIf { SAS.matches(it) }
        val state = if (sas == null) RequestState.WAITING else RequestState.of(body.s("state")).takeIf { it != RequestState.WAITING }
        // 0.83.7: `ik` comes with `sas` only; whether it is shown is decided by the screens (a service's), not here.
        val ik = body.s("ik")?.takeIf { sas != null }
        return Approval.OutgoingRequest(
            connectionId = id,
            sas = sas,
            remote = body.b("remote") ?: false,
            name = body.s("name")?.takeIf { it.isNotBlank() },
            state = state ?: RequestState.PENDING,
            peerApproved = body.b("peer_approved") ?: false,
            introducedBy = body.s("introduced_by"),
            receivedAt = instant(body.s("created_at")) ?: at,
            exp = instant(body.s("exp")),
            service = isService(body.s("peer_kind")),
            peerFingerprint = ik?.let(IkFingerprint::formatB64),
            peerIkInvalid = ik != null && IkFingerprint.formatB64(ik) == null,
        )
    }

    /** `peer_kind` (0.69.0): only `"service"` marks a service; absent or any other value is a person. */
    fun isService(peerKind: String?): Boolean = peerKind == "service"

    /** `grant.pending`, or an entry of `grant.list`'s `pending`. */
    @Suppress("ReturnCount")
    fun grant(body: JsonObject, at: Instant): Approval.GrantRequest? {
        val id = body.s("request_id") ?: return null
        val conn = body.s("connection_id") ?: return null
        return Approval.GrantRequest(
            requestId = id,
            connectionId = conn,
            entries = body.a("items").mapNotNull { i ->
                val kind = i.s("kind") ?: return@mapNotNull null
                GrantEntry(
                    kind, i.s("ref") ?: "", i.s("label"), i.b("available") ?: false,
                    name = i.s("name"),
                    category = i.s("category"),
                    labels = if (i["labels"] is JsonArray) com.vettid.core.data.items.SharingManager.labels(i) else null,
                )
            },
            uses = body.l("uses")?.toInt(),
            expiresIn = body.l("expires_in"),
            reason = body.s("reason"),
            receivedAt = at,
            exp = instant(body.s("exp")),
        )
    }

    /** `critical-secret-use.pending`, or an entry of `critical-secret-use.list`'s `incoming` (which has no `payload`). */
    @Suppress("ReturnCount")
    fun critical(body: JsonObject, at: Instant): Approval.CriticalUse? {
        val id = body.s("request_id") ?: return null
        val conn = body.s("connection_id") ?: return null
        val sha = body.s("payload_sha256") ?: return null
        return Approval.CriticalUse(
            requestId = id,
            connectionId = conn,
            itemName = body.s("name") ?: "",
            fieldLabel = body.s("label") ?: "",
            operation = body.s("operation") ?: "",
            payload = body.s("payload") ?: "",
            payloadSha256 = sha,
            context = body.s("context"),
            receivedAt = at,
            exp = instant(body.s("exp")),
            kind = body.s("kind"),
        )
    }

    /** SHA-256 of a base64 payload, standard base64; null if it is not base64. */
    fun payloadSha256(payloadB64: String): String? = try {
        Base64s.encodeStd(Bytes.sha256(Base64s.decodeStd(payloadB64)))
    } catch (_: CryptoException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun connection(c: Connection): ConnectionInfo {
        val p = PeerProfile.parse(c.profile)
        return ConnectionInfo(
            id = c.id,
            name = p?.displayName ?: c.name,
            state = ConnectionState.of(c.state),
            favorite = c.favorite,
            archived = c.archived,
            tags = c.tags,
            version = c.version,
            firstName = p?.firstName,
            lastName = p?.lastName,
            sharedItems = p?.items ?: emptyList(),
            hasPhoto = p?.hasPhoto ?: false,
            photo = p?.photo,
            keyFingerprint = IkFingerprint.formatB64(c.ik),
            createdAt = instant(c.createdAt),
            lastActiveAt = instant(c.lastActiveAt),
            service = isService(c.peerKind),
            asks = c.asks?.let { AskState(it.muted, it.paused, instant(it.pausedAt), it.cooldowns.coerceAtLeast(0)) },
        )
    }

    fun message(m: Message): MessageInfo = MessageInfo(
        connectionId = m.connectionId,
        messageId = m.messageId,
        outgoing = m.direction == "out",
        text = m.text,
        sentAt = instant(m.sentAt) ?: Instant.EPOCH,
        delivered = m.delivered,
        read = m.read,
        attachment = m.attachment?.let(::attachment),
    )

    /** The device view of an attachment (§10.5.1); the thumbnail is not kept here. */
    fun attachment(a: com.vettid.core.vault.AttachmentView): AttachmentInfo = AttachmentInfo(
        attId = a.attId,
        state = AttachmentState.of(a.state),
        reason = a.reason,
        mime = a.mime,
        size = a.size,
        width = a.width,
        height = a.height,
        name = AccountNames.cleanedCapped(a.name, com.vettid.core.media.PhotoLimits.NAME_MAX_CHARS),
    )

    /**
     * The `key` member of a `connection.authenticate.list` state, `.result` or `.key` body (VAULT-MESSAGING 0.88.1 §10.4):
     * returned as sent when it is canonical standard base64 of exactly 32 bytes (an Ed25519 public key, §5.3), else
     * null. Only the encoding and the length are checked: the app does no cryptography with it (the vault verified the
     * signatures and follows the rotation chains), it only records which key the vault has pinned.
     */
    fun credentialKey(o: JsonObject): String? {
        val k = o.s("key") ?: return null
        return try {
            Base64s.decodeStd(k, CredentialKeyFingerprint.KEY_SIZE)
            k
        } catch (_: CryptoException) {
            null
        }
    }

    /** One `connection.authenticate.list` state; a malformed `key` is dropped and the rest kept. */
    fun authState(o: JsonObject): AuthenticationState? {
        val conn = o.s("connection_id") ?: return null
        return AuthenticationState(
            conn, instant(o.s("verified_at")), o.s("last_result"), instant(o.s("last_at")), key = credentialKey(o),
        )
    }

    /**
     * A `connection.authenticate.result` event over the state [previous].
     *
     * Only a successful verdict (`authenticated: true`) speaks about the key. Its `key_changed` decides the warning: true
     * when the key was not the pinned one and the pin did not lead to it (§10.4); false or absent otherwise, including a
     * response whose `rotations` the vault followed. Its `key` (when sent and well formed) is the key the vault now has
     * pinned; an absent or malformed one leaves the previous key.
     *
     * A failed verdict (`denied`, `bad_signature`) carries no evidence about the key: it keeps the previous `keyChanged`
     * and `key` as they were. Otherwise the party a key-change warning is about could erase it by declining the member's
     * next request, and a `key` sent with a failure (which the spec does not define) could pass for a pinned one.
     */
    fun authResult(o: JsonObject, now: Instant, previous: AuthenticationState?): AuthenticationState? {
        val conn = o.s("connection_id") ?: return null
        val ok = o.b("authenticated") ?: false
        val base = previous ?: AuthenticationState(conn)
        return base.copy(
            verifiedAt = if (ok) now else base.verifiedAt,
            lastResult = if (ok) "authenticated" else o.s("reason") ?: "denied",
            lastAt = now,
            keyChanged = if (ok) o.b("key_changed") ?: false else base.keyChanged,
            waitingUntil = null,
            key = if (ok) credentialKey(o) ?: base.key else base.key,
        )
    }

    private val SAS = Regex("[0-9]{6}")
}
