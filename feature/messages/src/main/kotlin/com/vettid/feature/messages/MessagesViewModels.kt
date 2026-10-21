package com.vettid.feature.messages

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.graphics.Bitmap
import com.vettid.core.data.social.AttachmentManager
import com.vettid.core.data.social.AttachmentsRepository
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.PendingPhoto
import com.vettid.core.media.PhotoException
import com.vettid.core.media.PreparedPhoto
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.social.ConnectionsRepository
import com.vettid.core.data.social.ConversationSummary
import com.vettid.core.data.social.MessageInfo
import com.vettid.core.data.social.MessagesRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckRepository
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.checkCleared
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
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
    /** (ANDROID-PLAN 0.1.57 (2)) The code `error_vault_code` names with [error], or null. */
    val errorCode: String? = null,
    /** The top bar's search (owner request 2026-10-08): connection names and the latest message, on this phone. */
    val query: String = "",
    /** Whether there is any conversation at all (the search is offered then). */
    val searchable: Boolean = false,
)

/** Whether [c] matches [query]: the connection's title or display name, or its latest message's text, ignoring case. */
internal fun ConversationSummary.matches(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return listOfNotNull(connection.displayName, connection.name, last?.text).any { it.contains(q, ignoreCase = true) }
}

/** Messages (ANDROID-PLAN §4, Proton's inbox): conversations by connection, the Unread chip. */
@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val repo: MessagesRepository,
    private val ownerCheck: OwnerCheckRepository,
) : ViewModel() {
    private val local = MutableStateFlow(MessagesUiState())

    val uiState: StateFlow<MessagesUiState> = combine(local, repo.conversations) { s, all ->
        s.copy(
            conversations = (if (s.unreadOnly) all.filter { it.unread > 0 } else all).filter { it.matches(s.query) },
            noConnections = all.none { it.connection.state == ConnectionState.ACTIVE },
            searchable = all.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MessagesUiState())

    init {
        refresh()
        // The daily check passed (or the hold ended): a list refused because it was due reads again by itself.
        viewModelScope.launch {
            ownerCheck.checkCleared().collect {
                if (local.value.error == FailureKind.OWNER_CHECK_REQUIRED) refresh()
            }
        }
    }

    fun refresh() {
        local.update { it.copy(error = null) }
        viewModelScope.launch {
            try {
                repo.refreshConversations()
                local.update { it.copy(loading = false) }
            } catch (e: VaultFailure) {
                local.update { it.copy(loading = false, error = e.kind, errorCode = e.shownCode) }
            }
        }
    }

    fun setUnreadOnly(on: Boolean) = local.update { it.copy(unreadOnly = on) }

    fun setQuery(q: String) = local.update { it.copy(query = q) }

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
    /** (ANDROID-PLAN 0.1.57 (2)) The code `error_vault_code` names with [error], or null. */
    val errorCode: String? = null,
    /** Older messages remain in the vault (VAULT-MESSAGING 0.32.0 §10.5.2): scrolling to the top loads them. */
    val olderAvailable: Boolean = false,
    /** An older page is being read. */
    val loadingOlder: Boolean = false,
    /** The vault has photo attachments (VAULT-MESSAGING 0.32.0 §10.5.1): the composer offers the photo button. */
    val photosAvailable: Boolean = false,
    /** The app's own camera screen is open (ANDROID-PLAN 0.1.39 "Take a photo"). */
    val capturing: Boolean = false,
    /** Photos picked or taken, prepared in memory, waiting for Send or Cancel. */
    val photoDraft: PhotoDraft? = null,
    /** Changes at every clear of the attachment memory: the bubbles drop their decoded thumbnails. */
    val photoGeneration: Long = 0,
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
@Suppress("TooManyFunctions") // the conversation's and its photos' actions
class ConversationViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val messages: MessagesRepository,
    private val connections: ConnectionsRepository,
    private val attachments: AttachmentsRepository,
) : ViewModel() {
    private val connectionId: String = checkNotNull(savedState[ConversationRoute.ARG]) { "no connection" }
    private val local = MutableStateFlow(ConversationUiState(connectionId))

    val uiState: StateFlow<ConversationUiState> =
        combine(
            local,
            messages.messages(connectionId),
            connections.connections,
            messages.olderAvailable(connectionId),
            attachments.available,
        ) { s, ms, cs, older, photos ->
            s.copy(
                messages = ms,
                connection = cs.firstOrNull { it.id == connectionId } ?: s.connection,
                olderAvailable = older,
                photosAvailable = photos,
            )
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
                local.update { it.copy(loading = false, error = e.kind, errorCode = e.shownCode) }
            }
        }
        viewModelScope.launch {
            // A lock, the hold or due state, a wipe or an unlink (ANDROID-PLAN 0.1.39 "Memory only"): the draft, the
            // pending upload and the thumbnails shown go, and the bubbles read their thumbnails again.
            attachments.cleared.drop(1).collect { gen ->
                dropPhotos(discard = false)
                local.update { it.copy(photoGeneration = gen) }
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
                local.update { it.copy(sending = false, error = e.kind, errorCode = e.shownCode) }
            }
        }
    }

    /**
     * The member scrolled to the top: the next older page (§10.5.2), one at a time. `response_too_large` (§8.1) and
     * every other failure are shown; the list keeps what it has, and the next scroll to the top tries again.
     */
    fun loadOlder() {
        if (local.value.loadingOlder || !uiState.value.olderAvailable) return
        local.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            try {
                messages.loadOlder(connectionId)
                local.update { it.copy(loadingOlder = false) }
            } catch (e: VaultFailure) {
                local.update { it.copy(loadingOlder = false, error = e.kind, errorCode = e.shownCode) }
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
                local.update { it.copy(error = e.kind, errorCode = e.shownCode) }
            }
        }
    }

    fun dismissError() = local.update { it.copy(error = null) }

    // --- photos (ANDROID-PLAN 0.1.39) ---

    /** The upload being sent, kept for Retry (the same request) or Cancel (a discard). */
    private var pendingPhoto: PendingPhoto? = null

    fun startCapture() = local.update { it.copy(capturing = true) }

    fun stopCapture() = local.update { it.copy(capturing = false) }

    /** The pass that reads and prepares picked pictures, the send loop, and whether the member cancelled. */
    private var photoJob: Job? = null

    @Volatile
    private var photosCancelled = false

    /**
     * Pictures the Photo Picker handed over, read into memory and prepared one at a time (bounds, orientation, JPEG,
     * thumbnail): only one source and one prepared photo are being worked on at once. [sources] read one each; a
     * pick that cannot be read or prepared is counted and said.
     */
    fun onPicked(sources: List<suspend () -> ByteArray?>) {
        if (sources.isEmpty()) return
        photosCancelled = false
        local.update { it.copy(photoDraft = PhotoDraft(preparing = true)) }
        photoJob = viewModelScope.launch {
            val ready = mutableListOf<PreparedPhoto>()
            var problem: PhotoProblem? = null
            var failed = 0
            try {
                for (read in sources.take(PhotoDraft.MAX_PHOTOS)) {
                    if (photosCancelled) break
                    val src = try {
                        read()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    val failure = prepareOne(src, ready)
                    if (failure != null) {
                        failed++
                        problem = failure
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled (Cancel, a clear, the screen gone): nothing prepared is kept.
                ready.forEach { it.wipe() }
                throw e
            }
            if (photosCancelled) {
                ready.forEach { it.wipe() }
                return@launch
            }
            val shownFailures = if (ready.isEmpty()) 0 else failed
            local.update { it.copy(photoDraft = PhotoDraft(photos = ready, problem = problem, failedPicks = shownFailures)) }
        }
    }

    /** Prepares one picked picture into [ready] and zeroes its source; the problem when it cannot be used. */
    private suspend fun prepareOne(src: ByteArray?, ready: MutableList<PreparedPhoto>): PhotoProblem? {
        if (src == null || src.isEmpty()) return PhotoProblem.UNREADABLE
        return try {
            ready += attachments.prepare(src)
            null
        } catch (e: PhotoException) {
            PhotoProblem.of(e.failure)
        } finally {
            src.fill(0)
        }
    }

    /** A shot from the app's camera, upright, in memory (no file); recycled here once prepared. */
    fun onShot(shot: Bitmap) {
        local.update { it.copy(capturing = false, photoDraft = PhotoDraft(preparing = true)) }
        viewModelScope.launch {
            val draft = try {
                PhotoDraft(photos = listOf(attachments.prepare(shot)))
            } catch (e: PhotoException) {
                PhotoDraft(problem = PhotoProblem.of(e.failure))
            } finally {
                shot.recycle()
            }
            local.update { it.copy(photoDraft = draft) }
        }
    }

    fun setPhotoCaption(v: String) = local.update { s ->
        s.copy(photoDraft = s.photoDraft?.copy(caption = v, problem = null))
    }

    /**
     * Sends the draft's photos one after another, each as its own message, the caption with the first; the next
     * upload starts after the previous message's answer. A lost answer stops with Retry offered (the same request,
     * no new upload); a full mailbox or vault says so; any other refusal ends that photo. Cancel is offered only
     * between requests ([PhotoDraft.inFlight]); a cancel stops the loop before the next photo is begun.
     */
    @Suppress("CyclomaticComplexMethod")
    fun sendPhotos() {
        val draft = local.value.photoDraft ?: return
        if (draft.sending || draft.preparing || draft.photos.isEmpty()) return
        photosCancelled = false
        local.update { it.copy(photoDraft = draft.copy(sending = true, problem = null)) }
        photoJob = viewModelScope.launch {
            var d = draft
            while (d.photos.isNotEmpty()) {
                if (photosCancelled) return@launch
                val p = pendingPhoto ?: attachments.begin(connectionId, if (d.sent == 0) d.caption.trim() else "", d.photos.first())
                pendingPhoto = p
                local.update { s -> s.copy(photoDraft = s.photoDraft?.copy(inFlight = true)) }
                try {
                    attachments.send(p)
                    pendingPhoto = null
                    d = d.copy(photos = d.photos.drop(1), sent = d.sent + 1)
                    if (photosCancelled) return@launch
                    local.update { it.copy(photoDraft = d.copy(sending = true, inFlight = false)) }
                } catch (e: VaultFailure) {
                    if (photosCancelled) return@launch
                    val retry = AttachmentManager.retryable(e)
                    val problem = when {
                        e.code == AttachmentManager.CODE_QUOTA -> PhotoProblem.MAILBOX_FULL
                        e.kind == FailureKind.LIMIT -> PhotoProblem.VAULT_FULL
                        retry -> PhotoProblem.NO_ANSWER
                        else -> PhotoProblem.NOT_SENT
                    }
                    if (!retry && e.code != AttachmentManager.CODE_QUOTA) {
                        // Final for this photo: it is dropped, the others wait for Send.
                        pendingPhoto = null
                        d = d.copy(photos = d.photos.drop(1))
                    }
                    local.update { it.copy(photoDraft = d.copy(sending = false, inFlight = false, problem = problem, retry = retry)) }
                    return@launch
                }
            }
            local.update { it.copy(photoDraft = null) }
        }
    }

    /**
     * Cancel: no further photo is begun, an upload not yet sent is discarded at the vault, and the prepared photos
     * are wiped. Not while a request is on its way (the vault may already act on it): the screen disables it then.
     */
    fun cancelPhotos() {
        val draft = local.value.photoDraft
        if (draft?.inFlight == true) return
        dropPhotos(discard = true)
    }

    /** Everything of the photos in this conversation goes: the draft, the pending upload, the camera. */
    private fun dropPhotos(discard: Boolean) {
        photosCancelled = true
        photoJob?.cancel()
        photoJob = null
        val draft = local.value.photoDraft
        val p = pendingPhoto
        pendingPhoto = null
        local.update { it.copy(photoDraft = null, capturing = false) }
        if (p != null && discard) attachments.abandon(p)
        draft?.photos?.forEach { it.wipe() }
    }

    fun dismissPhotoProblem() = local.update { s ->
        s.copy(photoDraft = s.photoDraft?.takeIf { it.photos.isNotEmpty() }?.copy(problem = null))
    }

    /**
     * A message's thumbnail, from memory or fetched (`part: "thumb"`); null when it cannot be had. One that arrives after
     * a clear (ANDROID-PLAN "Memory only, wiped at lock") is zeroed and not shown.
     */
    suspend fun thumbnail(messageId: String): ByteArray? {
        val generation = attachments.cleared.value
        val t = attachments.cachedThumbnail(connectionId, messageId) ?: try {
            attachments.thumbnail(connectionId, messageId)
        } catch (_: VaultFailure) {
            null
        }
        if (t != null && attachments.cleared.value != generation) {
            t.fill(0)
            return null
        }
        return t
    }

    override fun onCleared() {
        // Leaving the conversation is a Cancel: an upload not sent is discarded at the vault.
        pendingPhoto?.let(attachments::abandon)
        local.value.photoDraft?.photos?.forEach { it.wipe() }
    }
}

/** Immutable UI state of the new-message picker. */
data class NewMessageUiState(
    val loading: Boolean = true,
    val connections: List<ConnectionInfo> = emptyList(),
    val error: FailureKind? = null,
    /** (ANDROID-PLAN 0.1.57 (2)) The code `error_vault_code` names with [error], or null. */
    val errorCode: String? = null,
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
                local.update { it.copy(loading = false, error = e.kind, errorCode = e.shownCode) }
            }
        }
    }
}
