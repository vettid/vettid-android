package com.vettid.app.features.feed

import com.vettid.app.core.nats.FeedEvent as ApiFeedEvent

/**
 * UI state for the feed screen.
 */
sealed class FeedState {
    object Loading : FeedState()
    data class Loaded(
        val events: List<ApiFeedEvent>,
        val hasMore: Boolean = false,
        val unreadCount: Int = 0
    ) : FeedState()
    data class Error(val message: String) : FeedState()
    object Empty : FeedState()
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
    const val SECURITY_ALERT = "security.alert"
    const val SECURITY_MIGRATION = "security.migration"
    const val TRANSFER_REQUEST = "transfer.request"
    const val BACKUP_COMPLETE = "backup.complete"
    const val VAULT_STATUS = "vault.status"
    const val HANDLER_COMPLETE = "handler.complete"
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
