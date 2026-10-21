package com.vettid.core.testing

import android.graphics.Bitmap
import com.vettid.core.data.social.AttachmentAccess
import com.vettid.core.data.social.AttachmentsRepository
import com.vettid.core.data.social.FetchedPhoto
import com.vettid.core.data.social.MessageInfo
import com.vettid.core.data.social.PendingPhoto
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.media.ImageType
import com.vettid.core.media.PhotoException
import com.vettid.core.media.PhotoFailure
import com.vettid.core.media.PreparedPhoto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/**
 * TEST ONLY. Photo attachments for ViewModel tests: records the calls, prepares by wrapping the source bytes, and
 * fails a call once when [fail] names it.
 */
@Suppress("TooManyFunctions")
class FakeAttachments : AttachmentsRepository {
    val calls = mutableListOf<String>()
    val fail = mutableMapOf<String, Exception>()
    val availableFlow = MutableStateFlow(true)
    override val available: StateFlow<Boolean> = availableFlow
    var seq = 0

    /** Every photo [prepare] made, to check that a cancel wiped them. */
    val prepared = mutableListOf<PreparedPhoto>()

    private fun call(name: String) {
        calls += name
        fail.remove(name)?.let { throw it }
    }

    override suspend fun probe() = call("probe")

    override suspend fun prepare(source: ByteArray): PreparedPhoto {
        call("prepare")
        if (source.isEmpty()) throw PhotoException(PhotoFailure.UNREADABLE)
        return PreparedPhoto(source.copyOf(), byteArrayOf(1, 2, 3), 2_560, 1_920).also { prepared += it }
    }

    override suspend fun prepare(shot: Bitmap): PreparedPhoto {
        call("prepareShot")
        return PreparedPhoto(ByteArray(10), byteArrayOf(1), shot.width, shot.height)
    }

    override fun begin(connectionId: String, caption: String, photo: PreparedPhoto): PendingPhoto {
        calls += "begin:$caption"
        return AttachmentAccess.pending(connectionId, caption, photo, "REQ${++seq}")
    }

    /** When set, [send] waits for it (a request on its way). */
    var sendGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun send(p: PendingPhoto): MessageInfo {
        calls += "send:${p.requestId}"
        sendGate?.await()
        fail.remove("send")?.let { throw it }
        return MessageInfo(p.connectionId, "M${p.requestId}", true, p.caption, Instant.EPOCH, delivered = false, read = false)
    }

    override suspend fun cancel(p: PendingPhoto) {
        calls += "cancel:${p.requestId}"
    }

    override fun abandon(p: PendingPhoto) {
        calls += "abandon:${p.requestId}"
    }

    /** When set, [thumbnail] and [photo] wait for it (a fetch on its way). */
    var fetchGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Every thumbnail and photo handed out, to check that one fetched across a clear was zeroed. */
    val handedOut = mutableListOf<ByteArray>()

    override suspend fun thumbnail(connectionId: String, messageId: String): ByteArray {
        call("thumbnail")
        fetchGate?.await()
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()).also { handedOut += it }
    }

    override suspend fun photo(connectionId: String, messageId: String): FetchedPhoto {
        call("photo")
        fetchGate?.await()
        return FetchedPhoto(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()).also { handedOut += it }, ImageType.JPEG)
    }

    override fun cachedThumbnail(connectionId: String, messageId: String): ByteArray? = null

    val clearedFlow = MutableStateFlow(0L)
    override val cleared: StateFlow<Long> = clearedFlow

    override fun clear() {
        calls += "clear"
        clearedFlow.value++
    }

    companion object {
        fun failure(kind: com.vettid.core.data.vault.FailureKind, code: String? = null) = VaultFailure(kind, code)
    }
}
