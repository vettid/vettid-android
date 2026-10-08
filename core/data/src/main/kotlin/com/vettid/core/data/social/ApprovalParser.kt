package com.vettid.core.data.social

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.IkFingerprint
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
        "share.pending", "approval.pending", "device.session.pending",
    )

    private fun JsonObject.s(k: String): String? = VaultJson.str(this, k)

    private fun JsonObject.l(k: String): Long? = VaultJson.long(this, k)

    private fun JsonObject.b(k: String): Boolean? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.o(k: String): JsonObject? = this[k] as? JsonObject

    private fun JsonObject.a(k: String): List<JsonObject> = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

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
            if (id == null || conn == null) null else Approval.Authentication(id, conn, body.s("context"), at, instant(body.s("exp")))
        }
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
                    items = body.a("items").mapNotNull { i ->
                        i.s("item_id")?.let { ShareItem(it, i.s("name") ?: "", i.s("category") ?: "other", i.s("sensitivity") ?: "data") }
                    },
                    reason = body.s("reason"),
                    receivedAt = at,
                )
            }
        }
        "approval.pending" -> body.s("approval_id")?.let {
            Approval.DeviceRequest(type, it, body.s("name"), body.s("role"), body.s("type"), at, instant(body.s("exp")))
        }
        "device.session.pending" -> body.s("request_id")?.let {
            Approval.DeviceRequest(type, it, body.s("name"), body.s("role"), null, at, instant(body.s("exp")))
        }
        else -> null
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
        )
    }

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
            alias = c.alias?.takeIf { it.isNotEmpty() },
            note = c.note?.takeIf { it.isNotEmpty() },
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
    )

    /** `connection.authenticate.list` states and `.result` events. */
    fun authState(o: JsonObject): AuthenticationState? {
        val conn = o.s("connection_id") ?: return null
        return AuthenticationState(conn, instant(o.s("verified_at")), o.s("last_result"), instant(o.s("last_at")))
    }

    fun authResult(o: JsonObject, now: Instant, previous: AuthenticationState?): AuthenticationState? {
        val conn = o.s("connection_id") ?: return null
        val ok = o.b("authenticated") ?: false
        val base = previous ?: AuthenticationState(conn)
        return base.copy(
            verifiedAt = if (ok) now else base.verifiedAt,
            lastResult = if (ok) "authenticated" else o.s("reason") ?: "denied",
            lastAt = now,
            keyChanged = o.b("key_changed") ?: false,
            waitingUntil = null,
        )
    }

    private val SAS = Regex("[0-9]{6}")
}
