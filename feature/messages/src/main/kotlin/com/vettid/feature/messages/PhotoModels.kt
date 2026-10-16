package com.vettid.feature.messages

import androidx.annotation.StringRes
import com.vettid.core.data.social.AttachmentInfo
import com.vettid.core.data.social.AttachmentState
import com.vettid.core.media.PhotoFailure
import com.vettid.core.media.PreparedPhoto

/**
 * Photos picked or taken for one conversation (ANDROID-PLAN 0.1.39 "Preparing it, in memory"), prepared and waiting
 * for Send or Cancel. In memory only; never in SavedStateHandle.
 */
data class PhotoDraft(
    val photos: List<PreparedPhoto> = emptyList(),
    val caption: String = "",
    val preparing: Boolean = false,
    val sending: Boolean = false,
    /** Photos of this draft already sent (the caption went with the first). */
    val sent: Int = 0,
    val problem: PhotoProblem? = null,
    /** The last answer was lost: Retry re-sends the same request, without a new upload. */
    val retry: Boolean = false,
    /** A request is on its way to the vault: Cancel waits for its answer. */
    val inFlight: Boolean = false,
    /** Picked pictures that could not be read or prepared (the others are here). */
    val failedPicks: Int = 0,
) {
    val canSend: Boolean get() = photos.isNotEmpty() && !sending && !preparing &&
        caption.toByteArray(Charsets.UTF_8).size <= com.vettid.core.data.social.MessagesRepository.MAX_TEXT_BYTES

    companion object {
        /** The Photo Picker hands at most this many. */
        const val MAX_PHOTOS = 10
    }
}

/** What went wrong with a photo, in the plan's words. */
enum class PhotoProblem(@StringRes val text: Int) {
    TOO_LARGE(R.string.messages_photo_too_large),
    UNSUPPORTED(R.string.messages_photo_unsupported),
    UNREADABLE(R.string.messages_photo_unreadable),

    /** The relay refused the upload: the vault's mailbox is full. */
    MAILBOX_FULL(R.string.messages_photo_mailbox_full),

    /** `limit` `attachment_storage` or `attachments`. */
    VAULT_FULL(R.string.messages_photo_vault_full),

    /** No answer, `busy` or a relay error: Retry. */
    NO_ANSWER(R.string.messages_photo_no_answer),
    NOT_SENT(R.string.messages_photo_not_sent),
    ;

    companion object {
        fun of(f: PhotoFailure): PhotoProblem = when (f) {
            PhotoFailure.TOO_LARGE -> TOO_LARGE
            PhotoFailure.UNSUPPORTED -> UNSUPPORTED
            PhotoFailure.UNREADABLE, PhotoFailure.CANNOT_FIT -> UNREADABLE
        }
    }
}

/** The line under a photo bubble: its state and, for a failure, the reason (ANDROID-PLAN "The bubble"). */
data class PhotoStatus(@StringRes val text: Int, val withName: Boolean = false, val failed: Boolean = false)

/** [AttachmentInfo]'s state and reason as the bubble says them; null for a stored incoming photo (nothing to say). */
@Suppress("CyclomaticComplexMethod") // one branch per state and reason
fun photoStatus(a: AttachmentInfo, outgoing: Boolean): PhotoStatus? {
    val reason = a.reason.orEmpty()
    return when (a.state) {
        AttachmentState.PREPARING, AttachmentState.SENDING -> PhotoStatus(R.string.messages_photo_sending)
        AttachmentState.DELIVERED -> PhotoStatus(R.string.messages_photo_delivered)
        AttachmentState.FETCHING -> PhotoStatus(R.string.messages_photo_receiving)
        AttachmentState.STORED -> null
        AttachmentState.UNKNOWN -> null
        AttachmentState.FAILED -> if (outgoing) {
            when {
                reason == "bad" || reason == "type" -> PhotoStatus(R.string.messages_photo_not_sent_bubble, failed = true)
                reason == "unsupported" -> PhotoStatus(R.string.messages_photo_peer_no_photos, withName = true, failed = true)
                reason == "unconfirmed" -> PhotoStatus(R.string.messages_photo_unconfirmed, failed = true)
                else -> PhotoStatus(R.string.messages_photo_not_delivered, failed = true)
            }
        } else {
            when {
                reason == "full" -> PhotoStatus(R.string.messages_photo_in_full, failed = true)
                reason == "rate" -> PhotoStatus(R.string.messages_photo_in_rate, withName = true, failed = true)
                reason == "type" -> PhotoStatus(R.string.messages_photo_in_type, failed = true)
                reason.startsWith("withdrawn") -> PhotoStatus(R.string.messages_photo_in_withdrawn, withName = true, failed = true)
                else -> PhotoStatus(R.string.messages_photo_in_never, failed = true)
            }
        }
    }
}
