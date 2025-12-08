package com.vettid.app.core.network

import com.google.gson.annotations.SerializedName

/**
 * Data models for connections and messaging.
 *
 * These models support:
 * - Connection invitation generation and acceptance
 * - Connection list and detail views
 * - Profile viewing and editing
 * - Encrypted messaging
 */

// MARK: - Connection Models

/**
 * Represents a connection between two users.
 */
data class Connection(
    @SerializedName("connection_id") val connectionId: String,
    @SerializedName("peer_guid") val peerGuid: String,
    @SerializedName("peer_display_name") val peerDisplayName: String,
    @SerializedName("peer_avatar_url") val peerAvatarUrl: String?,
    val status: ConnectionStatus,
    @SerializedName("created_at") val createdAt: Long,
    @SerializedName("last_message_at") val lastMessageAt: Long?,
    @SerializedName("unread_count") val unreadCount: Int
)

/**
 * Connection status enumeration.
 */
enum class ConnectionStatus {
    @SerializedName("pending")
    PENDING,
    @SerializedName("active")
    ACTIVE,
    @SerializedName("revoked")
    REVOKED
}

/**
 * Represents a connection invitation.
 */
data class ConnectionInvitation(
    @SerializedName("invitation_id") val invitationId: String,
    @SerializedName("invitation_code") val invitationCode: String,
    @SerializedName("qr_code_data") val qrCodeData: String,
    @SerializedName("deep_link_url") val deepLinkUrl: String,
    @SerializedName("expires_at") val expiresAt: Long,
    @SerializedName("creator_display_name") val creatorDisplayName: String
)

/**
 * Connection with last message for list display.
 */
data class ConnectionWithLastMessage(
    val connection: Connection,
    val lastMessage: Message?
)

// MARK: - Message Models

/**
 * Represents a message in a conversation.
 */
data class Message(
    @SerializedName("message_id") val messageId: String,
    @SerializedName("connection_id") val connectionId: String,
    @SerializedName("sender_id") val senderId: String,
    val content: String,
    @SerializedName("content_type") val contentType: MessageContentType,
    @SerializedName("sent_at") val sentAt: Long,
    @SerializedName("received_at") val receivedAt: Long?,
    @SerializedName("read_at") val readAt: Long?,
    val status: MessageStatus
)

/**
 * Message content type enumeration.
 */
enum class MessageContentType {
    @SerializedName("text")
    TEXT,
    @SerializedName("image")
    IMAGE,
    @SerializedName("file")
    FILE
}

/**
 * Message delivery/read status enumeration.
 */
enum class MessageStatus {
    @SerializedName("sending")
    SENDING,
    @SerializedName("sent")
    SENT,
    @SerializedName("delivered")
    DELIVERED,
    @SerializedName("read")
    READ,
    @SerializedName("failed")
    FAILED
}

/**
 * Encrypted message for transport.
 */
data class EncryptedMessage(
    val ciphertext: ByteArray,
    val nonce: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EncryptedMessage

        if (!ciphertext.contentEquals(other.ciphertext)) return false
        if (!nonce.contentEquals(other.nonce)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        return result
    }
}

// MARK: - Profile Models

/**
 * User profile information.
 */
data class Profile(
    val guid: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("avatar_url") val avatarUrl: String?,
    val bio: String?,
    val location: String?,
    @SerializedName("last_updated") val lastUpdated: Long
)

/**
 * Profile update request.
 */
data class ProfileUpdateRequest(
    @SerializedName("display_name") val displayName: String,
    @SerializedName("avatar_url") val avatarUrl: String?,
    val bio: String?,
    val location: String?
)

// MARK: - API Request/Response Types

/**
 * Request to create a connection invitation.
 */
data class CreateInvitationRequest(
    @SerializedName("expires_in_minutes") val expiresInMinutes: Int = 60
)

/**
 * Request to accept a connection invitation.
 */
data class AcceptInvitationRequest(
    @SerializedName("invitation_code") val invitationCode: String,
    @SerializedName("public_key") val publicKey: String  // Base64-encoded X25519 public key
)

/**
 * Response when accepting an invitation.
 */
data class AcceptInvitationResponse(
    val connection: Connection,
    @SerializedName("peer_public_key") val peerPublicKey: String  // Base64-encoded X25519 public key
)

/**
 * Request to revoke a connection.
 */
data class RevokeConnectionRequest(
    @SerializedName("connection_id") val connectionId: String
)

/**
 * Response with list of connections.
 */
data class ConnectionListResponse(
    val connections: List<Connection>,
    val total: Int,
    val page: Int,
    @SerializedName("has_more") val hasMore: Boolean
)

/**
 * Request to send a message.
 */
data class SendMessageRequest(
    @SerializedName("connection_id") val connectionId: String,
    @SerializedName("encrypted_content") val encryptedContent: String,  // Base64-encoded ciphertext
    val nonce: String,  // Base64-encoded nonce
    @SerializedName("content_type") val contentType: MessageContentType = MessageContentType.TEXT
)

/**
 * Response after sending a message.
 */
data class SendMessageResponse(
    val message: Message
)

/**
 * Response with message history.
 */
data class MessageHistoryResponse(
    val messages: List<Message>,
    @SerializedName("has_more") val hasMore: Boolean,
    @SerializedName("next_cursor") val nextCursor: String?
)

/**
 * Response with unread message counts by connection.
 */
data class UnreadCountResponse(
    @SerializedName("unread_counts") val unreadCounts: Map<String, Int>,
    val total: Int
)

/**
 * Request to mark a message as read.
 */
data class MarkAsReadRequest(
    @SerializedName("message_id") val messageId: String
)

// MARK: - Real-time Event Types

/**
 * Connection event for real-time updates via NATS.
 */
sealed class ConnectionEvent {
    data class InvitationAccepted(
        val connection: Connection,
        @SerializedName("peer_public_key") val peerPublicKey: String
    ) : ConnectionEvent()

    data class ConnectionRevoked(
        @SerializedName("connection_id") val connectionId: String
    ) : ConnectionEvent()

    data class ProfileUpdated(
        @SerializedName("connection_id") val connectionId: String,
        val profile: Profile
    ) : ConnectionEvent()

    data class NewMessage(
        val message: Message,
        @SerializedName("connection_id") val connectionId: String
    ) : ConnectionEvent()

    data class MessageRead(
        @SerializedName("message_id") val messageId: String,
        @SerializedName("connection_id") val connectionId: String,
        @SerializedName("read_at") val readAt: Long
    ) : ConnectionEvent()
}
