package com.vettid.app.features.feed

import java.time.Instant

/**
 * Feed event types that appear in the user's activity feed.
 */
sealed class FeedEvent {
    abstract val id: String
    abstract val timestamp: Instant
    abstract val isRead: Boolean

    /**
     * A message received from a connection.
     */
    data class Message(
        override val id: String,
        override val timestamp: Instant,
        override val isRead: Boolean,
        val senderId: String,
        val senderName: String,
        val preview: String
    ) : FeedEvent()

    /**
     * An incoming connection request.
     */
    data class ConnectionRequest(
        override val id: String,
        override val timestamp: Instant,
        override val isRead: Boolean,
        val fromId: String,
        val fromName: String,
        val fromEmail: String?
    ) : FeedEvent()

    /**
     * An authentication request from a service.
     */
    data class AuthRequest(
        override val id: String,
        override val timestamp: Instant,
        override val isRead: Boolean,
        val serviceId: String,
        val serviceName: String,
        val scope: String,
        val expiresAt: Instant?
    ) : FeedEvent()

    /**
     * A handler execution completed.
     */
    data class HandlerComplete(
        override val id: String,
        override val timestamp: Instant,
        override val isRead: Boolean,
        val handlerId: String,
        val handlerName: String,
        val success: Boolean,
        val resultSummary: String?
    ) : FeedEvent()

    /**
     * A vault status change.
     */
    data class VaultStatusChange(
        override val id: String,
        override val timestamp: Instant,
        override val isRead: Boolean,
        val previousStatus: String,
        val newStatus: String
    ) : FeedEvent()

    /**
     * A backup completed.
     */
    data class BackupComplete(
        override val id: String,
        override val timestamp: Instant,
        override val isRead: Boolean,
        val backupId: String,
        val success: Boolean,
        val sizeBytes: Long?
    ) : FeedEvent()
}

/**
 * State for the feed screen.
 */
sealed class FeedState {
    object Loading : FeedState()
    data class Loaded(
        val events: List<FeedEvent>,
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
    data class ShowError(val message: String) : FeedEffect()
}
