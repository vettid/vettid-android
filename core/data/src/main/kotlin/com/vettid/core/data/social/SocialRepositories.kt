package com.vettid.core.data.social

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Connections and invitations (VAULT-MESSAGING §6.4, §10.4). Every failure is
 * a [com.vettid.core.data.vault.VaultFailure].
 */
@Suppress("TooManyFunctions")
interface ConnectionsRepository {
    /** The vault's connections, as last read (refreshed on connection events). */
    val connections: StateFlow<List<ConnectionInfo>>

    /** Member authentication per connection id (§10.4). */
    val authentication: StateFlow<Map<String, AuthenticationState>>

    suspend fun refresh()

    suspend fun connection(id: String): ConnectionInfo

    /** The invite lifetimes the relay allows (§6.4: never above its open-token and claim limits). */
    suspend fun inviteTtls(): List<InviteTtl>

    suspend fun createInvite(ttl: InviteTtl): InviteInfo

    suspend fun outstandingInvites(): List<OutstandingInvite>

    suspend fun cancelInvite(inviteId: String)

    /** Accepts a pasted link or a scanned QR code (both forms of §6.4); see [InviteLinks]. */
    suspend fun acceptInvite(text: String): AcceptedConnection

    suspend fun setFavorite(id: String, favorite: Boolean)

    /** Sets the owner's alias and note; "" clears one, null leaves it. */
    suspend fun updateNames(id: String, alias: String?, note: String?)

    suspend fun remove(id: String)

    /** Removes the connection and blocks its identity (§10.4 block list). */
    suspend fun block(id: String)

    /** Asks the connection's member to prove presence (§10.4); returns the request id. */
    suspend fun requestAuthentication(id: String, context: String?): String

    /** The safety code this app showed when the connection was made, if it recorded one. */
    fun safetyCode(id: String): SafetyCodeRecord?
}

/** Messages with connections (§10.5). */
interface MessagesRepository {
    /** One entry per active connection with messages or not, newest conversation first. */
    val conversations: StateFlow<List<ConversationSummary>>

    /** The messages with one connection, oldest first, as loaded and as they arrive. */
    fun messages(connectionId: String): Flow<List<MessageInfo>>

    /** Reloads every conversation (`message.list` per connection). */
    suspend fun refreshConversations()

    /** Reloads one conversation. */
    suspend fun load(connectionId: String)

    /** Sends [text] (1 byte to 16 KiB in UTF-8). */
    suspend fun send(connectionId: String, text: String): MessageInfo

    /** Marks every unread incoming message of the conversation read (the vault sends read receipts). */
    suspend fun markRead(connectionId: String)

    /** Deletes a message on this vault only (the connection keeps its copy). */
    suspend fun delete(connectionId: String, messageId: String)

    companion object {
        /** `message.send` limit (§10.5). */
        const val MAX_TEXT_BYTES = 16 * 1024
    }
}

/** Decisions the member makes (ANDROID-PLAN §4 Approvals). */
@Suppress("TooManyFunctions")
interface ApprovalsRepository {
    /** Everything waiting for a decision, newest first. */
    val approvals: StateFlow<List<Approval>>

    /** Re-reads what the vault lists (grant and critical-item requests) and drops expired requests. */
    suspend fun refreshApprovals()

    suspend fun approveConnection(pendingId: String)

    suspend fun declineConnection(pendingId: String)

    /** Declines the request and blocks the requester's identity. */
    suspend fun blockConnectionRequest(pendingId: String)

    /** Opens the credential's unlock window with [password] and signs the challenge (§10.4). */
    suspend fun approveAuthentication(requestId: String, password: String)

    suspend fun denyAuthentication(requestId: String)

    /** Approves the grantable entries ([Approval.GrantRequest.grantable]) or denies the request. */
    suspend fun decideGrant(requestId: String, approve: Boolean)

    /** One use of a critical item, consented with the credential password (§10.13). */
    suspend fun approveCriticalUse(requestId: String, password: String)

    suspend fun denyCriticalUse(requestId: String)

    /** Includes or declines every listed item of a share rule (§10.12). */
    suspend fun decideShare(ruleId: String, approve: Boolean)

    /** Declines a desktop's or agent's request (§6.8). */
    suspend fun declineDeviceRequest(key: String)
}
