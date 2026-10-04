package com.vettid.feature.messages

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.social.ConnectionsRepository
import com.vettid.core.data.social.ConversationSummary
import com.vettid.core.data.social.MessageInfo
import com.vettid.core.data.social.MessagesRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Immutable UI state of the Messages screen. */
data class MessagesUiState(
    val loading: Boolean = true,
    val conversations: List<ConversationSummary> = emptyList(),
    val unreadOnly: Boolean = false,
    /** True when the member has no active connection at all (the empty state then suggests an invite). */
    val noConnections: Boolean = true,
    val error: FailureKind? = null,
)

/** Messages (ANDROID-PLAN §4, Proton's inbox): conversations by connection, the Unread chip. */
@HiltViewModel
class MessagesViewModel @Inject constructor(private val repo: MessagesRepository) : ViewModel() {
    private val local = MutableStateFlow(MessagesUiState())

    val uiState: StateFlow<MessagesUiState> = combine(local, repo.conversations) { s, all ->
        s.copy(
            conversations = if (s.unreadOnly) all.filter { it.unread > 0 } else all,
            noConnections = all.none { it.connection.state == ConnectionState.ACTIVE },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MessagesUiState())

    init {
        refresh()
    }

    fun refresh() {
        local.update { it.copy(error = null) }
        viewModelScope.launch {
            try {
                repo.refreshConversations()
                local.update { it.copy(loading = false) }
            } catch (e: VaultFailure) {
                local.update { it.copy(loading = false, error = e.kind) }
            }
        }
    }

    fun setUnreadOnly(on: Boolean) = local.update { it.copy(unreadOnly = on) }

    fun dismissError() = local.update { it.copy(error = null) }
}

/** Immutable UI state of a conversation. */
data class ConversationUiState(
    val connectionId: String,
    val connection: ConnectionInfo? = null,
    val messages: List<MessageInfo> = emptyList(),
    val loading: Boolean = true,
    val draft: String = "",
    val sending: Boolean = false,
    val tooLong: Boolean = false,
    /** The message the member long-pressed: delete is confirmed first. */
    val deleting: MessageInfo? = null,
    val error: FailureKind? = null,
) {
    val canSend: Boolean get() = connection?.state == ConnectionState.ACTIVE && draft.isNotBlank() && !sending && !tooLong
    val active: Boolean get() = connection?.state == ConnectionState.ACTIVE
}

/**
 * A conversation (ANDROID-PLAN §4): the messages with one connection, oldest
 * first; incoming messages are marked read while it is open (the vault sends
 * read receipts, §10.5); send, and delete a message on this vault only.
 */
@HiltViewModel
class ConversationViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val messages: MessagesRepository,
    private val connections: ConnectionsRepository,
) : ViewModel() {
    private val connectionId: String = checkNotNull(savedState[ConversationRoute.ARG]) { "no connection" }
    private val local = MutableStateFlow(ConversationUiState(connectionId))

    val uiState: StateFlow<ConversationUiState> =
        combine(local, messages.messages(connectionId), connections.connections) { s, ms, cs ->
            s.copy(messages = ms, connection = cs.firstOrNull { it.id == connectionId } ?: s.connection)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ConversationUiState(connectionId))

    init {
        viewModelScope.launch {
            try {
                if (connections.connections.value.none { it.id == connectionId }) {
                    val c = connections.connection(connectionId)
                    local.update { it.copy(connection = c) }
                }
                messages.load(connectionId)
                local.update { it.copy(loading = false) }
            } catch (e: VaultFailure) {
                local.update { it.copy(loading = false, error = e.kind) }
            }
        }
        viewModelScope.launch {
            // Read while open: every new incoming message is marked read (best effort).
            messages.messages(connectionId).collect { ms ->
                if (ms.any { !it.outgoing && !it.read }) {
                    try {
                        messages.markRead(connectionId)
                    } catch (_: VaultFailure) {
                        // the next arrival or the next visit tries again
                    }
                }
            }
        }
    }

    fun setDraft(v: String) = local.update {
        it.copy(draft = v, tooLong = v.toByteArray(Charsets.UTF_8).size > MessagesRepository.MAX_TEXT_BYTES)
    }

    fun send() {
        // The draft from the local state (the combined state may not have caught up yet).
        val s = local.value.copy(connection = uiState.value.connection ?: local.value.connection)
        if (!s.canSend) return
        val text = s.draft.trim()
        local.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try {
                messages.send(connectionId, text)
                local.update { it.copy(sending = false, draft = "") }
            } catch (e: VaultFailure) {
                // The draft stays so that the member can retry.
                local.update { it.copy(sending = false, error = e.kind) }
            }
        }
    }

    fun askDelete(m: MessageInfo?) = local.update { it.copy(deleting = m) }

    fun confirmDelete() {
        val m = local.value.deleting ?: return
        local.update { it.copy(deleting = null) }
        viewModelScope.launch {
            try {
                messages.delete(connectionId, m.messageId)
            } catch (e: VaultFailure) {
                local.update { it.copy(error = e.kind) }
            }
        }
    }

    fun dismissError() = local.update { it.copy(error = null) }
}

/** Immutable UI state of the new-message picker. */
data class NewMessageUiState(
    val loading: Boolean = true,
    val connections: List<ConnectionInfo> = emptyList(),
    val error: FailureKind? = null,
)

/** The compose button: pick an active connection to write to. */
@HiltViewModel
class NewMessageViewModel @Inject constructor(private val repo: ConnectionsRepository) : ViewModel() {
    private val local = MutableStateFlow(NewMessageUiState())

    val uiState: StateFlow<NewMessageUiState> = combine(local, repo.connections) { s, cs ->
        s.copy(
            connections = cs.filter { it.state == ConnectionState.ACTIVE }
                .sortedWith(compareByDescending<ConnectionInfo> { it.favorite }.thenBy { it.displayName.lowercase() }),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NewMessageUiState())

    init {
        viewModelScope.launch {
            try {
                repo.refresh()
                local.update { it.copy(loading = false) }
            } catch (e: VaultFailure) {
                local.update { it.copy(loading = false, error = e.kind) }
            }
        }
    }
}
