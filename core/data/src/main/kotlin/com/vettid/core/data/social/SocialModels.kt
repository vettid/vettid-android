package com.vettid.core.data.social

import java.time.Duration
import java.time.Instant

/** A connection's state as the app shows it (VAULT-MESSAGING §10.4, ANDROID-PLAN §4). */
enum class ConnectionState {
    ACTIVE,
    PENDING,
    STALE,
    BLOCKED,
    OTHER,
    ;

    companion object {
        fun of(wire: String): ConnectionState = when (wire) {
            "active" -> ACTIVE
            "pending" -> PENDING
            "stale" -> STALE
            "blocked" -> BLOCKED
            else -> OTHER
        }
    }
}

/**
 * A connection (§10.4). [name] and [profile] are the peer's self-asserted
 * values; [alias], [note], [favorite] and [archived] are the owner's own
 * metadata, never sent to the peer.
 */
data class ConnectionInfo(
    val id: String,
    val name: String,
    val state: ConnectionState,
    val alias: String? = null,
    val note: String? = null,
    val favorite: Boolean = false,
    val archived: Boolean = false,
    val tags: List<String> = emptyList(),
    val version: Long = 0,
    /** The shared profile's text members (photo left out), as the peer presents them. */
    val profile: List<Pair<String, String>> = emptyList(),
    /** The first hex digits of the peer vault's identity key, grouped, for display. */
    val keyFingerprint: String? = null,
    val createdAt: Instant? = null,
    val lastActiveAt: Instant? = null,
) {
    /** The owner's alias, else the peer's name, else a fallback the UI supplies. */
    val displayName: String get() = alias?.takeIf { it.isNotBlank() } ?: name
}

/** The invite lifetimes of §6.4: 10 minutes in person (default), longer for remote invites. */
@Suppress("MagicNumber") // the spec's values
enum class InviteTtl(val seconds: Int) {
    TEN_MINUTES(600),
    ONE_HOUR(3_600),
    ONE_DAY(86_400),
    SEVEN_DAYS(604_800),
    ;

    /** Remote invites stay pending until the inviter approves; auto-approval never applies (§6.4). */
    val remote: Boolean get() = this != TEN_MINUTES
}

/** A new invitation: [link] to share or paste, [qr] (compact JSON, §6.4) to show as a QR code. */
data class InviteInfo(
    val inviteId: String,
    val link: String,
    val qr: String,
    val exp: Instant,
    val remote: Boolean,
)

/** An outstanding invitation (`connection.invite.list`). */
data class OutstandingInvite(val inviteId: String, val exp: Instant?, val remote: Boolean)

/** `connection.invite.accept`: the new (pending) connection and, if the vault sent it, the safety code. */
data class AcceptedConnection(val connectionId: String, val sas: String?)

/** The safety code shown when a connection was made (recorded by this app). */
data class SafetyCodeRecord(val sas: String, val at: Instant)

/** What this vault knows about a connection's member authentication (§10.4). */
data class AuthenticationState(
    val connectionId: String,
    val verifiedAt: Instant? = null,
    /** `authenticated`, `denied`, `bad_signature` or `requested`. */
    val lastResult: String? = null,
    val lastAt: Instant? = null,
    val keyChanged: Boolean = false,
    /** A request this app sent and is waiting for (until it expires). */
    val waitingUntil: Instant? = null,
)

/** One message (§10.5). */
data class MessageInfo(
    val connectionId: String,
    val messageId: String,
    val outgoing: Boolean,
    val text: String,
    val sentAt: Instant,
    val delivered: Boolean,
    val read: Boolean,
)

/** One row of the Messages screen: a connection with its latest message. */
data class ConversationSummary(
    val connection: ConnectionInfo,
    val last: MessageInfo?,
    val unread: Int,
)

/** An item of a grant request (§10.12): an item the asker names, or a category the member answers. */
data class GrantEntry(val kind: String, val ref: String, val label: String?, val available: Boolean)

/** An item waiting for a share decision (§10.12 `share.pending`). */
data class ShareItem(val itemId: String, val name: String, val category: String, val sensitivity: String)

/**
 * Something that needs the member's decision (ANDROID-PLAN §4 Approvals).
 * [key] is stable and unique across kinds; [connectionName] is resolved from
 * the connection list when the request names a connection.
 */
sealed interface Approval {
    val key: String
    val receivedAt: Instant
    val exp: Instant?
    val connectionName: String?

    /** `connection.request.pending` (§6.4): approve after comparing the safety code. */
    data class ConnectionRequest(
        val pendingId: String,
        val inviteId: String?,
        val sas: String,
        val remote: Boolean,
        /** The requester's self-asserted name (`profile.name`). */
        val name: String?,
        val introducedBy: String?,
        override val receivedAt: Instant,
        override val exp: Instant?,
        override val connectionName: String? = null,
    ) : Approval {
        override val key: String get() = "connection:$pendingId"
    }

    /** `connection.authenticate.pending` (§10.4): sign a connection's challenge with the credential key. */
    data class Authentication(
        val requestId: String,
        val connectionId: String,
        val context: String?,
        override val receivedAt: Instant,
        override val exp: Instant?,
        override val connectionName: String? = null,
    ) : Approval {
        override val key: String get() = "auth:$requestId"
    }

    /** `grant.pending` (§10.12): a connection asks to read items. */
    data class GrantRequest(
        val requestId: String,
        val connectionId: String,
        val entries: List<GrantEntry>,
        val uses: Int?,
        val expiresIn: Long?,
        val reason: String?,
        override val receivedAt: Instant,
        override val exp: Instant?,
        override val connectionName: String? = null,
    ) : Approval {
        override val key: String get() = "grant:$requestId"

        /** The entries an approval grants now (named items that exist); categories are answered in Items. */
        val grantable: List<Int> get() = entries.indices.filter { entries[it].kind == "item" && entries[it].available }
    }

    /** `critical-secret-use.pending` (§10.13): one use of a critical item, with the password. */
    data class CriticalUse(
        val requestId: String,
        val connectionId: String,
        val itemName: String,
        val fieldLabel: String,
        val operation: String,
        val payload: String,
        val payloadSha256: String,
        val context: String?,
        override val receivedAt: Instant,
        override val exp: Instant?,
        override val connectionName: String? = null,
    ) : Approval {
        override val key: String get() = "critical:$requestId"
    }

    /** `share.pending` (§10.12): items a share rule asks about. */
    data class ShareDecision(
        val ruleId: String,
        val subjectConnectionId: String?,
        val subjectAgentId: String?,
        val items: List<ShareItem>,
        val reason: String?,
        override val receivedAt: Instant,
        override val exp: Instant? = null,
        override val connectionName: String? = null,
    ) : Approval {
        override val key: String get() = "share:$ruleId"
    }

    /**
     * Requests of desktops and agents (`approval.pending`, `device.session.pending`,
     * §6.8): shown as typed rows; v1 pairs no desktops or agents, so they can only be declined.
     */
    data class DeviceRequest(
        val type: String,
        val id: String,
        val deviceName: String?,
        val role: String?,
        val requestType: String?,
        override val receivedAt: Instant,
        override val exp: Instant?,
        override val connectionName: String? = null,
    ) : Approval {
        override val key: String get() = "device:$type:$id"
    }
}

/** How long an unanswered connection request is kept (§6.4: 7 days). */
val CONNECTION_REQUEST_TTL: Duration = Duration.ofDays(7)
