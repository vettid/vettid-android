package com.vettid.core.data.social

import com.vettid.core.data.account.AccountNames
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
 * A connection (§10.4). [firstName] and [lastName] are the names on the peer's VettID account, from the shared
 * profile's core (VAULT-MESSAGING 0.18.0 §10.8; on the inviter's side, before the first `profile.update`, from the
 * request): null before they arrived, never verified. [name] (the display name), [photo] and [sharedItems] are
 * the peer's self-asserted extras; [alias], [note], [favorite] and [archived] are the owner's own metadata, never
 * sent to the peer.
 */
data class ConnectionInfo(
    val id: String,
    /** The peer's display name (optional); "" without one. Never the title on its own (§10.8). */
    val name: String,
    val state: ConnectionState,
    val alias: String? = null,
    val note: String? = null,
    val favorite: Boolean = false,
    val archived: Boolean = false,
    val tags: List<String> = emptyList(),
    val version: Long = 0,
    val firstName: String? = null,
    val lastName: String? = null,
    /** The `@profile` items the peer shares, as the peer presents them. */
    val sharedItems: List<SharedProfileItem> = emptyList(),
    /** Whether the peer shares a photo. */
    val hasPhoto: Boolean = false,
    /** The peer's shared photo (§10.8, base64 JPEG or PNG, self-asserted); decoded defensively by the UI. */
    val photo: String? = null,
    /** The fingerprint of the peer vault's pinned identity key (§10.8: 8 groups of 4 hex digits). */
    val keyFingerprint: String? = null,
    val createdAt: Instant? = null,
    val lastActiveAt: Instant? = null,
) {
    /** "First Last" from the names on the peer's account; null before they arrived (§10.8). */
    val accountName: String? get() = AccountNames.full(firstName, lastName)

    /**
     * The title (§10.8): the owner's alias, else "First Last"; "" before the names arrived, for which the UI shows
     * "Name not shared yet". Never the display name alone.
     */
    val displayName: String get() = alias?.takeIf { it.isNotBlank() } ?: accountName ?: ""

    /** The peer's display name when it adds something to the title (non-empty and different), for secondary text. */
    val secondaryName: String? get() = name.takeIf { it.isNotBlank() && it != displayName && it != accountName }
}

/** One `@profile` item of a peer's shared profile (§10.8): self-asserted. */
data class SharedProfileItem(val itemId: String, val name: String, val fields: List<Pair<String, String>>)

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

/**
 * A new invitation: [link] the bare payload, [url] the invitation URL to share
 * (`<r>/connect#<link>` on the invitation's relay, §6.4), [qr] (compact JSON) to
 * show as a QR code.
 */
data class InviteInfo(
    val inviteId: String,
    val link: String,
    val qr: String,
    val exp: Instant,
    val remote: Boolean,
) {
    val url: String get() = InviteLinks.url(link) ?: link
}

/** An outstanding invitation (`connection.invite.list`). */
data class OutstandingInvite(val inviteId: String, val exp: Instant?, val remote: Boolean)

/**
 * `connection.invite.accept` (§6.4): the outgoing request [connectionId] (its
 * safety code follows once the handshake has run, 0.10.3) and the inviter's
 * [name] from the invitation; or, with [exists], the connection (or request)
 * this vault already has with the inviter (0.10.2).
 */
data class AcceptedConnection(
    val connectionId: String,
    val name: String? = null,
    val exists: Boolean = false,
    val exp: Instant? = null,
)

/** Where a connection request stands (§10.4, 0.10.3). */
enum class RequestState {
    /** Outgoing only: the handshake has not run yet, no safety code. */
    WAITING,

    /** The safety code is known; this member has not decided. */
    PENDING,

    /** This member approved; the other member's approval is awaited. */
    APPROVED,
    ;

    companion object {
        fun of(wire: String?): RequestState = when (wire) {
            "waiting" -> WAITING
            "approved" -> APPROVED
            else -> PENDING
        }
    }
}

/** How a connection request ended without a connection (§6.4). */
enum class RequestEnd {
    /** This member declined (on this or another of their devices). */
    DECLINED,
    EXPIRED,

    /** The handshake was aborted, or an older vault let the request run out. */
    FAILED,

    /** The other member declined (`connection.declined`, 0.10.5). */
    PEER_DECLINED,
}

/**
 * The other member declined a connection request of this member's (0.10.5,
 * §6.4): shown once, until the member dismisses it. [outgoing]: this member
 * accepted an invitation ("<name> declined your connection request"); else this
 * member invited ("<name> declined the connection"). [name] is the one the
 * request showed (self-asserted), kept from the request since it has left the list.
 */
data class PeerDecline(val requestId: String, val name: String?, val outgoing: Boolean, val at: Instant)

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

/**
 * The member's answer to a grant request (§10.12): [items] are the request's entry indexes granted; [answers] name
 * the member's item for category entries (index → item id); [uses] (1–100) and [expiresInSeconds]
 * (60–31,536,000) replace the request's when set.
 */
data class GrantDecision(
    val items: List<Int>,
    val answers: Map<Int, String> = emptyMap(),
    val uses: Int? = null,
    val expiresInSeconds: Long? = null,
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
        /**
         * The requester's "First Last" from its `hs.init` profile (VAULT-MESSAGING 0.18.0 §6.2, §10.8): the names on
         * its VettID account, not verified; null when the profile carries none.
         */
        val name: String?,
        val introducedBy: String?,
        override val receivedAt: Instant,
        override val exp: Instant?,
        override val connectionName: String? = null,
        /** [RequestState.PENDING], or [RequestState.APPROVED] (by this member, or by in-person auto-approval). */
        val state: RequestState = RequestState.PENDING,
        /** The other member approved too (their vault's `connection.approved` arrived). */
        val peerApproved: Boolean = false,
        /** The requester's self-asserted display name (`profile.name`), if any and different from [name]. */
        val displayName: String? = null,
    ) : Approval {
        override val key: String get() = "connection:$pendingId"
    }

    /**
     * This vault's outgoing request (§6.4, 0.10.2/0.10.3): an invitation this
     * member accepted. Once the handshake has run its [sas] is known and the
     * member approves or declines after comparing it with the inviter's screen.
     */
    data class OutgoingRequest(
        val connectionId: String,
        val sas: String?,
        val remote: Boolean,
        /** The inviter's name from the invitation (`hint.name`). */
        val name: String?,
        val state: RequestState,
        val peerApproved: Boolean,
        val introducedBy: String?,
        override val receivedAt: Instant,
        override val exp: Instant?,
        override val connectionName: String? = null,
    ) : Approval {
        override val key: String get() = "outgoing:$connectionId"
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

        /**
         * The payload is here and SHA-256(payload) equals [payloadSha256] (§10.13): only then is
         * it shown and the approval offered. A list entry has no payload until `critical-secret-use.get`.
         */
        val payloadVerified: Boolean
            get() = payload.isNotEmpty() && ApprovalParser.payloadSha256(payload)?.let {
                com.vettid.core.crypto.Bytes.constantTimeEquals(it.toByteArray(), payloadSha256.toByteArray())
            } == true
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
        /** The rule's tags (from `share.rule.list`; the event does not carry them). */
        val tags: List<String> = emptyList(),
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

/**
 * Whether the member has something to decide: a connection request whose safety code is
 * known and that this member has not approved yet; every other kind until it is decided.
 * Requests waiting for the handshake or for the other member are shown but not counted.
 */
val Approval.needsDecision: Boolean
    get() = when (this) {
        is Approval.ConnectionRequest -> state == RequestState.PENDING
        is Approval.OutgoingRequest -> state == RequestState.PENDING
        else -> true
    }

/** How long an unanswered incoming connection request is kept (§6.4: 7 days). */
val CONNECTION_REQUEST_TTL: Duration = Duration.ofDays(7)
