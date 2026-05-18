package com.vettid.app.features.feed

import com.vettid.app.core.nats.FeedEvent as ApiFeedEvent

/**
 * UI state for the feed screen.
 */
sealed class FeedState {
    object Loading : FeedState()
    data class Loaded(
        val items: List<FeedDisplayItem>,
        val hasMore: Boolean = false,
        val unreadCount: Int = 0,
        val isOffline: Boolean = false
    ) : FeedState()
    data class Error(val message: String) : FeedState()
    object Empty : FeedState()
}

/**
 * Display items for the connection-centric feed.
 *
 * ConnectionCard is the primary UI element for connections at ALL lifecycle stages.
 * Driven by connection.list data, enriched with feed events for activity previews.
 *
 * EventItem wraps standalone events (guides, security, agent approvals, etc.)
 * that are not tied to a specific connection.
 */
sealed class FeedDisplayItem {
    abstract val sortTimestamp: Long
    abstract val isUnread: Boolean

    /**
     * Connection card shown at all lifecycle stages.
     * Status drives the UI variant: pending review, waiting, active, revoked, declined.
     */
    data class ConnectionCard(
        val connectionId: String,
        val peerName: String,
        val peerPhotoBase64: String?,
        val peerEmail: String?,
        val connectionStatus: String,      // "pending", "active", "revoked", "rejected"
        val direction: String,             // "inbound" or "outbound"
        val needsReview: Boolean,          // show accept/decline (pending + needs_attention)
        val hasAccepted: Boolean,          // we accepted, waiting for peer
        val connectionType: String,        // "peer", "agent", "device"
        val e2eReady: Boolean,             // can message (key exchange complete)
        val lastActivityPreview: String,   // latest message text or status description
        val lastActivityType: String,      // "message", "call", "connection", etc.
        val lastActivityDirection: String? = null, // "incoming" | "outgoing" | null
        val lastActivitySubtype: String? = null,   // "voice" | "video" when type=call
        val lastActivityOutcome: String? = null,   // "completed" | "missed" | "rejected" for calls
        val lastActivityAt: Long = 0L,             // epoch-millis for last-activity row timestamp
        val missedCallCount: Int = 0,
        val unreadCount: Int,
        val pendingRows: List<PendingRow> = emptyList(),
        // System connection badge counts. Ignored for non-system cards.
        val systemVaultMessagesBadge: Int = 0,
        val systemVotesBadge: Int = 0,
        val systemGuidesBadge: Int = 0,
        // Presence from the peer. "online" when a fresh heartbeat
        // arrived within the aggregator's timeout; null means "no
        // signal" (peer hasn't opted in, hasn't opened the app
        // recently, or we're out of range). Absence is not "offline"
        // per plans/luminous-unifying-manatee.md §15.
        val presence: String? = null,
        // True when the user created an invitation and the peer
        // hasn't acted on it yet (status invited/pending, outbound
        // direction, no acceptance signal). Card renders as a
        // pending-invitation row with a Cancel action so abandoned
        // invites don't pile up as empty connection cards.
        val hasOutstandingInvitation: Boolean = false,
        // Peer published at least one wallet (BTC) in their profile.
        // Used together with the local user's wallet state to gate
        // BTC actions on the connection card — no point offering
        // Send/Request BTC if either side can't transact.
        val peerHasWallet: Boolean = false,
        // The local user has at least one active BTC wallet — surfaced
        // here (rather than read fresh from the wallet store at render
        // time) so card recomposition stays cheap.
        val localHasWallet: Boolean = false,
        // Peer's primary published BTC address. Pre-filled in Send BTC.
        val peerBtcAddress: String? = null,
        // Device-only fields. Populated when connectionType == "device"
        // by FeedViewModel via `device.list` so the desktop-specific card
        // can render the session state, hostname, and platform without a
        // second round-trip per render. Null/empty for non-device cards.
        val deviceSessionStatus: String? = null,        // "active" | "suspended" | "revoked" | null
        val deviceSessionExpiresAt: Long = 0L,          // unix seconds; 0 = no active session
        val deviceHostname: String? = null,
        val devicePlatform: String? = null,
        override val sortTimestamp: Long,
        override val isUnread: Boolean
    ) : FeedDisplayItem()

    /**
     * Standalone event not tied to a connection (guides, security alerts, etc.).
     */
    data class EventItem(
        val event: ApiFeedEvent,
        override val sortTimestamp: Long,
        override val isUnread: Boolean
    ) : FeedDisplayItem()

    /**
     * Footer card listing the count of archived connections (declined,
     * revoked, expired). Tap → ArchivedConnectionsScreen. Only emitted
     * when at least one archived connection exists; sorts to the
     * bottom by carrying timestamp 0.
     */
    data class ArchivedConnectionsCard(
        val count: Int,
    ) : FeedDisplayItem() {
        override val sortTimestamp: Long = 0L
        override val isUnread: Boolean = false
    }
}

/**
 * A single actionable row at the bottom of a connection card —
 * replaces badges + icons with an explicit, tappable "something is
 * waiting for you" item. See plans/luminous-unifying-manatee.md §12.
 *
 * `timestamp` is epoch millis when available (so the UI can format it
 * as "5m ago"). Rows with no natural timestamp (e.g. the passive
 * last-activity fallback) carry 0 and the card shows the sort time.
 */
sealed class PendingRow {
    abstract val timestamp: Long

    /** N unread messages from this peer. Tap → Conversation. */
    data class UnreadMessages(
        val count: Int,
        override val timestamp: Long,
    ) : PendingRow()

    /** One or more unanswered incoming calls. Tap → call detail. */
    data class MissedCall(
        val count: Int,
        val subtype: String?, // "voice" | "video" | null
        override val timestamp: Long,
    ) : PendingRow()

    /** Pending connection request awaiting user review. Tap → review screen. */
    data class PendingReview(
        override val timestamp: Long,
    ) : PendingRow()

    /** Vault security update waiting on approval (system connection only). */
    data class PendingMigration(
        val version: String,
        override val timestamp: Long,
    ) : PendingRow()

    /** Unread guide (system connection). One row per unread guide. */
    data class GuideUnread(
        val guideId: String,
        val title: String,
        override val timestamp: Long,
    ) : PendingRow()

    /** Active proposal the user hasn't voted on (system connection). One row per proposal. */
    data class ProposalUnvoted(
        val proposalId: String,
        val title: String,
        override val timestamp: Long,
    ) : PendingRow()

    /**
     * Peer started or stopped sharing their location (V3/V5). One row
     * per transition; the previous row is replaced when a new one
     * arrives for the same connection so the feed doesn't accumulate
     * stale lifecycle events. Tap → ConnectionDetail.
     */
    data class PeerLocationShare(
        val started: Boolean,
        override val timestamp: Long,
    ) : PendingRow()

    /**
     * Incoming data-access request from this peer. Tap → opens the
     * connection's Grants screen on the Pending tab. One row per
     * pending request; auto-clears when the user approves / denies.
     */
    data class IncomingGrantRequest(
        val requestId: String,
        val itemLabel: String,
        override val timestamp: Long,
    ) : PendingRow()

    /**
     * Passive last-activity row shown when nothing is pending. Not
     * interactive beyond opening the default card destination.
     */
    data class LastActivity(
        val text: String,
        val direction: String?,    // "incoming" | "outgoing" | null
        val activityType: String,  // "message" | "call" | "connection" …
        val subtype: String?,      // "voice" | "video" | null
        val outcome: String?,      // "missed" | "completed" | "rejected" | null
        override val timestamp: Long,
    ) : PendingRow()
}

/**
 * Effects emitted by the feed view model.
 */
sealed class FeedEffect {
    data class NavigateToConversation(val connectionId: String) : FeedEffect()
    data class NavigateToConnectionReview(val connectionId: String, val eventId: String = "") : FeedEffect()
    data class NavigateToConnectionDetail(val connectionId: String) : FeedEffect()
    data class NavigateToConnectionRequest(val requestId: String) : FeedEffect()
    data class NavigateToAuthRequest(val requestId: String) : FeedEffect()
    data class NavigateToHandler(val handlerId: String) : FeedEffect()
    data class NavigateToBackup(val backupId: String) : FeedEffect()
    data class NavigateToCall(val callId: String) : FeedEffect()
    data class NavigateToTransfer(val transferId: String) : FeedEffect()
    data class NavigateToGuide(val guideId: String, val eventId: String, val userName: String) : FeedEffect()
    data class NavigateToAgentApproval(val requestId: String) : FeedEffect()
    data class StartVoiceCall(val peerGuid: String, val peerName: String) : FeedEffect()
    data class StartVideoCall(val peerGuid: String, val peerName: String) : FeedEffect()
    data class ShowEventDetail(val event: com.vettid.app.core.nats.FeedEvent) : FeedEffect()
    /** Bring the Vault Security Update card back — user tapped the deferred-update entry. */
    object ShowVaultUpdatePrompt : FeedEffect()
    data class ShowError(val message: String) : FeedEffect()
    data class ShowActionSuccess(val message: String) : FeedEffect()
}

/**
 * Event types supported by the feed.
 * Maps to backend event_type values.
 */
object EventTypes {
    // Connection events (used for filtering, not for creating cards)
    const val CONNECTION_REQUEST = "connection.request"
    const val CONNECTION_ACCEPTED = "connection.accepted"
    const val CONNECTION_CREATED = "connection.created"
    const val CONNECTION_REVOKED = "connection.revoked"

    // Activity events (enrich connection cards)
    const val CALL_INCOMING = "call.incoming"
    const val CALL_MISSED = "call.missed"
    const val CALL_COMPLETED = "call.completed"
    const val MESSAGE_RECEIVED = "message.received"
    const val MESSAGE_SENT = "message.sent"
    const val TRANSFER_REQUEST = "transfer.request"

    // Standalone events
    const val SECURITY_ALERT = "security.alert"
    const val SECURITY_MIGRATION = "security.migration"
    const val BACKUP_COMPLETE = "backup.complete"
    const val VAULT_STATUS = "vault.status"
    const val HANDLER_COMPLETE = "handler.complete"
    const val GUIDE = "guide"

    // Agent events
    const val AGENT_SECRET_REQUEST = "agent.secret.request"
    const val AGENT_ACTION_REQUEST = "agent.action.request"
    const val AGENT_CONNECTED = "agent.connection.approved"
    const val AGENT_MESSAGE_RECEIVED = "agent.message.received"
    const val AGENT_MESSAGE_SENT = "agent.message.sent"
    const val AGENT_APPROVAL_REQUESTED = "agent.approval.requested"
}

/**
 * Event types that are connection activity (used to enrich connection cards).
 * These events are matched to connections by connection_id and provide
 * the activity preview and unread count for connection cards.
 */
val CONNECTION_ACTIVITY_EVENT_TYPES = setOf(
    EventTypes.MESSAGE_RECEIVED, EventTypes.MESSAGE_SENT,
    EventTypes.CALL_INCOMING, EventTypes.CALL_COMPLETED, EventTypes.CALL_MISSED,
    EventTypes.TRANSFER_REQUEST,
    EventTypes.AGENT_MESSAGE_RECEIVED, EventTypes.AGENT_MESSAGE_SENT
)

/**
 * Event types related to connection lifecycle (excluded from standalone events).
 * These are handled by connection cards, not event items.
 */
val CONNECTION_LIFECYCLE_EVENT_TYPES = setOf(
    EventTypes.CONNECTION_REQUEST, EventTypes.CONNECTION_ACCEPTED,
    EventTypes.CONNECTION_CREATED, EventTypes.CONNECTION_REVOKED
)

/**
 * Action types that feed items can have.
 */
object ActionTypes {
    const val ACCEPT_DECLINE = "accept_decline"
    const val REPLY = "reply"
    const val VIEW = "view"
    const val ACKNOWLEDGE = "acknowledge"
}

/**
 * Feed status values.
 */
object FeedStatus {
    const val HIDDEN = "hidden"
    const val ACTIVE = "active"
    const val READ = "read"
    const val ARCHIVED = "archived"
    const val DELETED = "deleted"
}
