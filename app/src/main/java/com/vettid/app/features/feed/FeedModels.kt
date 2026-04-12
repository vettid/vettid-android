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
 * ConnectionItem groups all activity for a single connection.
 * EventItem wraps standalone events (guides, security, etc.).
 */
sealed class FeedDisplayItem {
    abstract val sortTimestamp: Long
    abstract val isUnread: Boolean

    data class ConnectionItem(
        val connectionId: String,
        val peerName: String,
        val peerPhotoBase64: String?,
        val lastActivityPreview: String,
        val lastActivityType: String,
        val unreadCount: Int,
        override val sortTimestamp: Long,
        override val isUnread: Boolean
    ) : FeedDisplayItem()

    data class EventItem(
        val event: ApiFeedEvent,
        override val sortTimestamp: Long,
        override val isUnread: Boolean
    ) : FeedDisplayItem()

    data class AgentConnectionItem(
        val connectionId: String,
        val agentName: String,
        val agentType: String,
        val lastActivityPreview: String,
        val lastActivityType: String,
        val unreadCount: Int,
        override val sortTimestamp: Long,
        override val isUnread: Boolean
    ) : FeedDisplayItem()
}

/**
 * Effects emitted by the feed view model.
 */
sealed class FeedEffect {
    data class NavigateToConversation(val connectionId: String) : FeedEffect()
    data class NavigateToConnectionRequest(val requestId: String) : FeedEffect()
    data class NavigateToAuthRequest(val requestId: String) : FeedEffect()
    data class NavigateToHandler(val handlerId: String) : FeedEffect()
    data class NavigateToBackup(val backupId: String) : FeedEffect()
    data class NavigateToCall(val callId: String) : FeedEffect()
    data class NavigateToTransfer(val transferId: String) : FeedEffect()
    data class NavigateToGuide(val guideId: String, val eventId: String, val userName: String) : FeedEffect()
    data class NavigateToAgentApproval(val requestId: String) : FeedEffect()
    data class NavigateToAgentConversation(val connectionId: String) : FeedEffect()
    data class NavigateToConnectionReview(val connectionId: String, val eventId: String) : FeedEffect()
    data class ShowEventDetail(val event: com.vettid.app.core.nats.FeedEvent) : FeedEffect()
    data class ShowError(val message: String) : FeedEffect()
    data class ShowActionSuccess(val message: String) : FeedEffect()
}

/**
 * Event types supported by the feed.
 * Maps to backend event_type values.
 */
object EventTypes {
    const val CALL_INCOMING = "call.incoming"
    const val CALL_MISSED = "call.missed"
    const val CALL_COMPLETED = "call.completed"
    const val CONNECTION_REQUEST = "connection.request"
    const val CONNECTION_ACCEPTED = "connection.accepted"
    const val CONNECTION_REVOKED = "connection.revoked"
    const val MESSAGE_RECEIVED = "message.received"
    const val MESSAGE_SENT = "message.sent"
    const val SECURITY_ALERT = "security.alert"
    const val SECURITY_MIGRATION = "security.migration"
    const val TRANSFER_REQUEST = "transfer.request"
    const val BACKUP_COMPLETE = "backup.complete"
    const val VAULT_STATUS = "vault.status"
    const val HANDLER_COMPLETE = "handler.complete"
    const val GUIDE = "guide"
    const val AGENT_SECRET_REQUEST = "agent.secret.request"
    const val AGENT_ACTION_REQUEST = "agent.action.request"
    const val AGENT_CONNECTED = "agent.connection.approved"
    const val AGENT_MESSAGE_RECEIVED = "agent.message.received"
    const val AGENT_MESSAGE_SENT = "agent.message.sent"
    const val AGENT_APPROVAL_REQUESTED = "agent.approval.requested"
}

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
