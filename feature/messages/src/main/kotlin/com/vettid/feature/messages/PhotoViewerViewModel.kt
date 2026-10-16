package com.vettid.feature.messages

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.social.AttachmentManager
import com.vettid.core.data.social.AttachmentsRepository
import com.vettid.core.data.social.FetchedPhoto
import com.vettid.core.data.social.MessagesRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

/** The photo viewer of one message (only the ids travel; the photo stays in memory). */
@Serializable
data class PhotoViewerRoute(val connectionId: String, val messageId: String) {
    companion object {
        const val ARG_CONNECTION = "connectionId"
        const val ARG_MESSAGE = "messageId"
    }
}

/** Immutable UI state of the photo viewer. */
data class PhotoViewerUiState(
    val loading: Boolean = true,
    /** The fetched, checked photo (encoded; decoded by the screen to its size). */
    val photo: FetchedPhoto? = null,
    /** The sender's name for it, sanitized; never a file name. */
    val name: String? = null,
    val error: FailureKind? = null,
    @StringRes val problemText: Int? = null,
)

/**
 * Fetches a message's photo (`message.attachment.get`, the blob from this phone's mailbox, its checks), from memory
 * when it was fetched before (ANDROID-PLAN 0.1.39 "Viewing").
 */
@HiltViewModel
class PhotoViewerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val attachments: AttachmentsRepository,
    private val messages: MessagesRepository,
) : ViewModel() {
    private val connectionId: String = checkNotNull(savedState[PhotoViewerRoute.ARG_CONNECTION])
    private val messageId: String = checkNotNull(savedState[PhotoViewerRoute.ARG_MESSAGE])
    private val state = MutableStateFlow(PhotoViewerUiState())
    val uiState: StateFlow<PhotoViewerUiState> = state.asStateFlow()

    init {
        load()
        // A lock, the hold or due state, a wipe or an unlink: the photo this viewer holds goes too.
        viewModelScope.launch {
            attachments.cleared.drop(1).collect {
                state.value.photo?.bytes?.fill(0)
                state.update {
                    PhotoViewerUiState(loading = false, error = FailureKind.OTHER, problemText = R.string.messages_photo_unavailable)
                }
            }
        }
    }

    override fun onCleared() {
        state.value.photo?.bytes?.fill(0)
        // The viewer is gone (not merely recomposed or rotated): its share offers are withdrawn.
        com.vettid.core.media.SharedPhotos.clear()
    }

    fun load() {
        state.update { it.copy(loading = true, error = null, problemText = null) }
        viewModelScope.launch {
            val name = messages.messages(connectionId).first().firstOrNull { it.messageId == messageId }?.attachment?.name
            try {
                val p = attachments.photo(connectionId, messageId)
                state.update { PhotoViewerUiState(loading = false, photo = p, name = name) }
            } catch (e: VaultFailure) {
                val text = when (e.code) {
                    AttachmentManager.CODE_TYPE -> R.string.messages_photo_in_type
                    AttachmentManager.CODE_BAD -> R.string.messages_photo_bad
                    "unavailable" -> R.string.messages_photo_unavailable
                    "busy" -> R.string.messages_photo_busy
                    else -> null
                }
                state.update { PhotoViewerUiState(loading = false, name = name, error = e.kind, problemText = text) }
            }
        }
    }
}
