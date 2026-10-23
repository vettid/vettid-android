package com.vettid.core.data.social

import androidx.annotation.VisibleForTesting
import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.ClaimBlob
import com.vettid.core.data.vault.ClearSteps
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.vaultGuard
import com.vettid.core.media.ImageType
import com.vettid.core.media.MemoryCache
import com.vettid.core.media.PhotoLimits
import com.vettid.core.media.PreparedPhoto
import com.vettid.core.relay.RelayException
import com.vettid.core.vault.FetchedAttachment
import com.vettid.core.vault.Message
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.SentAttachment
import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.attachmentContent
import com.vettid.core.vault.attachmentThumb
import com.vettid.core.vault.attachmentsSupported
import com.vettid.core.vault.deleteBlob
import com.vettid.core.vault.fetchBlob
import com.vettid.core.vault.messageAttachmentDiscard
import com.vettid.core.vault.messageSendPhoto
import com.vettid.core.vault.uploadBlob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * A photo on its way out (ANDROID-PLAN 0.1.39 "Sending"): prepared in memory, uploaded at most once per tap of Send,
 * and sent with one inner id that a Retry repeats. Kept in memory only until the vault's answer arrives or the member
 * cancels, and wiped at a lock.
 */
class PendingPhoto internal constructor(
    val connectionId: String,
    val caption: String,
    internal val photo: PreparedPhoto,
    /** The inner id of this photo's `message.send`: a Retry re-sends the same request (§8.2), never a new upload. */
    val requestId: String,
    /** [AttachmentsRepository.cleared] when it was begun: a clear since then ends it before anything more is sent. */
    internal val clearMark: Long = 0,
) {
    /** The upload in the vault's mailbox, once the relay took it. */
    @Volatile
    internal var blobId: String? = null
    internal var sealed: ClaimBlob.Sealed? = null

    @Volatile
    var sent: Boolean = false
        internal set

    /** One send of this photo at a time (a Retry tapped twice must not upload twice). */
    internal val lock = kotlinx.coroutines.sync.Mutex()

    /** Whether the upload is in the vault's mailbox (a Retry then only re-sends the request). */
    val uploaded: Boolean get() = blobId != null

    val thumbnail: ByteArray get() = photo.thumb

    val width: Int get() = photo.width
    val height: Int get() = photo.height

    internal fun wipe() {
        sealed?.destroy()
        sealed = null
        photo.wipe()
    }
}

/** TEST hook: a [PendingPhoto] made outside [AttachmentManager] (fakes in `:core:testing`). */
object AttachmentAccess {
    fun pending(connectionId: String, caption: String, photo: PreparedPhoto, requestId: String) =
        PendingPhoto(connectionId, caption, photo, requestId)
}

/** A received photo, fetched and checked: the encoded bytes (decoded only for showing) and their sniffed type. */
class FetchedPhoto(val bytes: ByteArray, val type: ImageType)

/**
 * Photo attachments (VAULT-MESSAGING 0.32.0 §10.5.1, ANDROID-PLAN 0.1.39) on this phone. Everything lives in memory:
 * thumbnails, photos and prepared uploads, wiped by [clear] at a lock, on entering the hold or the due state, at a
 * wipe and at an unlink. Nothing from a photo goes to a log.
 */
@Suppress("TooManyFunctions") // one per step of §10.5.1 the app takes
interface AttachmentsRepository {
    /** Whether the vault has attachments (`message.attachment.get{}` after each unlock); no attach button otherwise. */
    val available: StateFlow<Boolean>

    /** The availability probe: once after each unlock. */
    suspend fun probe()

    /**
     * Prepares a picked picture's bytes for sending, off the main thread (ANDROID-PLAN "Preparing it, in memory"):
     * bounds first, orientation, long edge 2,560, JPEG without metadata, the thumbnail. Throws
     * [com.vettid.core.media.PhotoException].
     */
    suspend fun prepare(source: ByteArray): PreparedPhoto

    /** Prepares a camera shot (upright, in memory); the caller recycles [shot]. */
    suspend fun prepare(shot: android.graphics.Bitmap): PreparedPhoto

    /** Starts sending [photo] to [connectionId] with [caption] (it goes with the first photo only). */
    fun begin(connectionId: String, caption: String, photo: PreparedPhoto): PendingPhoto

    /**
     * Uploads [p] (unless it already is) and sends `message.send` with it under [PendingPhoto.requestId]. A lost
     * or late answer leaves [p] uploaded: calling this again is the member's Retry, the same request without a new
     * upload. The relay refusing the upload with `quota_exceeded` is [FailureKind.LIMIT] with code
     * `quota_exceeded`, and nothing is retried until the member taps Send again.
     */
    suspend fun send(p: PendingPhoto): MessageInfo

    /** Cancel before `message.send` answered: `message.attachment.discard{blob_id}` for an upload, then wiped. */
    suspend fun cancel(p: PendingPhoto)

    /** [cancel] without waiting (the screen that held [p] is gone). */
    fun abandon(p: PendingPhoto)

    /** The thumbnail of a message's photo: from `message.new`, or fetched (`part: "thumb"`); memory only. */
    suspend fun thumbnail(connectionId: String, messageId: String): ByteArray

    /** The photo itself: fetched from this phone's mailbox, checked, decrypted and deleted there; memory only. */
    suspend fun photo(connectionId: String, messageId: String): FetchedPhoto

    /** The thumbnail in memory, if any (no request); a copy. */
    fun cachedThumbnail(connectionId: String, messageId: String): ByteArray?

    /**
     * Counts every [clear] (a lock, the hold or due state, a wipe, an unlink): screens drop the photos, previews and
     * drafts they hold when it changes. Thumbnails and photos handed out are copies, which [clear] cannot zero.
     */
    val cleared: StateFlow<Long>

    /** Drops every thumbnail, photo and pending upload (zeroized). */
    fun clear()
}

/** The vault calls behind [AttachmentManager] (a fake in tests). */
interface AttachmentOps {
    suspend fun supported(): Boolean?
    fun newRequestId(): String
    suspend fun upload(blob: ByteArray): String
    suspend fun send(connectionId: String, text: String?, attachment: JsonObject, requestId: String): SentAttachment
    suspend fun discard(blobId: String)
    suspend fun thumb(connectionId: String, messageId: String): ByteArray
    suspend fun content(connectionId: String, messageId: String): FetchedAttachment
    suspend fun fetchBlob(blobId: String): ByteArray
    suspend fun deleteBlob(blobId: String)
}

/** [AttachmentOps] over the vault client. */
class VaultAttachmentOps(private val api: suspend () -> VaultApi) : AttachmentOps {
    override suspend fun supported() = api().attachmentsSupported()
    override fun newRequestId(): String = com.vettid.core.crypto.envelope.Ulid.new(Instant.now())
    override suspend fun upload(blob: ByteArray) = api().uploadBlob(blob)
    override suspend fun send(connectionId: String, text: String?, attachment: JsonObject, requestId: String) =
        api().messageSendPhoto(connectionId, text, attachment, requestId)
    override suspend fun discard(blobId: String) = api().messageAttachmentDiscard(blobId)
    override suspend fun thumb(connectionId: String, messageId: String) = api().attachmentThumb(connectionId, messageId)
    override suspend fun content(connectionId: String, messageId: String) = api().attachmentContent(connectionId, messageId)
    override suspend fun fetchBlob(blobId: String) = api().fetchBlob(blobId)
    override suspend fun deleteBlob(blobId: String) = api().deleteBlob(blobId)
}

@Suppress("TooManyFunctions")
class AttachmentManager(
    private val ops: AttachmentOps,
    /** A sent photo's message joins its conversation ([SocialManager.addSent]). */
    private val onSent: (MessageInfo) -> Unit,
    /** Where an abandoned upload's discard runs. */
    private val scope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
) : AttachmentsRepository {
    private val availableFlow = MutableStateFlow(false)
    private val clearedFlow = MutableStateFlow(0L)
    override val cleared: StateFlow<Long> = clearedFlow.asStateFlow()

    /** Caches are keyed by conversation and message: a message id is unique only within its connection. */
    private fun cacheKey(connectionId: String, messageId: String) = "$connectionId\u0000$messageId"
    override val available: StateFlow<Boolean> = availableFlow.asStateFlow()

    private val thumbs = MemoryCache<String, ByteArray>(PhotoLimits.THUMB_CACHE_BYTES, { it.size.toLong() }) { it.fill(0) }
    private val photos = MemoryCache<String, FetchedPhoto>(PhotoLimits.PHOTO_CACHE_BYTES, { it.bytes.size.toLong() }) { it.bytes.fill(0) }
    private val pending = java.util.Collections.synchronizedSet(mutableSetOf<PendingPhoto>())

    /**
     * Held by [clear], by a fetch's check-and-cache ([keepUnlessCleared]), by a send's last check ([sendLocked]) and by
     * every wipe of a pending photo ([finish]). Taken before [pending]'s monitor and the caches' monitors, never after.
     */
    private val clearLock = Any()

    /** TEST hook: called with [HOOK_BUILD] while a send's request is built, and [HOOK_EVENT] before an inline thumbnail is kept. */
    @VisibleForTesting
    @Volatile
    internal var testHook: ((String) -> Unit)? = null

    /** `attachment_fetches`: at most 2 `content` fetches of this device at once (§10.5.1). */
    private val fetches = Semaphore(MAX_FETCHES)

    override suspend fun probe() {
        availableFlow.value = try {
            vaultGuard { ops.supported() } == true
        } catch (_: VaultFailure) {
            false
        }
    }

    override suspend fun prepare(source: ByteArray): PreparedPhoto =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.vettid.core.media.Photos.prepare(source) }

    override suspend fun prepare(shot: android.graphics.Bitmap): PreparedPhoto =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.vettid.core.media.Photos.prepare(shot) }

    override fun begin(connectionId: String, caption: String, photo: PreparedPhoto): PendingPhoto =
        PendingPhoto(connectionId, caption, photo, ops.newRequestId(), clearedFlow.value).also { pending += it }

    /**
     * (ANDROID-PLAN "Memory only, wiped at lock") A clear (a lock, the hold or due state, a wipe, an unlink) came since [p]
     * was begun, or its photo was wiped: it ends here, unavailable. Checked before sealing, before the upload and before
     * `message.send`, so that zeroed bytes are never sealed or uploaded and nothing is sent after a clear.
     */
    private fun checkNotCleared(p: PendingPhoto) {
        if (p.clearMark == clearedFlow.value && !p.photo.wiped) return
        val blob = p.blobId
        finish(p)
        // Best effort: an upload a clear overtook is discarded (it expires after the blob TTL otherwise).
        if (blob != null && !p.sent) scope.launch { runCatching { vaultGuard { ops.discard(blob) } } }
        throw VaultFailure(FailureKind.OTHER, CODE_UNAVAILABLE)
    }

    override suspend fun send(p: PendingPhoto): MessageInfo = p.lock.withLock { sendLocked(p) }

    @Suppress("ThrowsCount")
    private suspend fun sendLocked(p: PendingPhoto): MessageInfo {
        if (p.sent) throw VaultFailure(FailureKind.CONFLICT, "already_sent")
        if (p.blobId == null) upload(p)
        // The last check and the request built from copies (its strings) in one step under [clearLock], which [clear]
        // wipes the pending photos under: a clear either stops the send here or comes after the request is complete, so
        // `message.send` never carries a zeroed key or thumbnail.
        val (att, size) = synchronized(clearLock) {
            checkNotCleared(p)
            testHook?.invoke(HOOK_BUILD)
            val blobId = p.blobId ?: throw VaultFailure(FailureKind.OTHER, "no_upload")
            val sealed = p.sealed ?: throw VaultFailure(FailureKind.OTHER, "no_upload")
            buildJsonObject {
                put(
                    "blob",
                    buildJsonObject {
                        put("id", blobId)
                        put("key", Base64s.encodeStd(sealed.key))
                        put("sha256", Base64s.encodeStd(sealed.sha256))
                        put("size", sealed.size)
                        put("mime", ImageType.JPEG.mime)
                    },
                )
                put("kind", "photo")
                put("width", p.photo.width)
                put("height", p.photo.height)
                put("thumb", Base64s.encodeStd(p.photo.thumb))
            } to sealed.size
        }
        val answer = try {
            vaultGuard { ops.send(p.connectionId, p.caption.ifEmpty { null }, att, p.requestId) }
        } catch (e: VaultFailure) {
            // A lost or late answer, `busy` or a relay error: the same request again on Retry (the upload stays). Any
            // other refusal is final (the vault answers the same id from its cache, and has deleted the upload).
            if (!retryable(e)) finish(p)
            throw e
        }
        val kept = synchronized(clearLock) {
            (p.clearMark == clearedFlow.value).also {
                // A Cancel that waited out the build wiped the photo: no thumbnail is kept (a copy would be all zeros),
                // so the conversation fetches it from the vault like a received photo's.
                if (it && !p.photo.wiped) thumbs.put(cacheKey(p.connectionId, answer.messageId), p.photo.thumb.copyOf())
            }
        }
        if (!kept) {
            // Sent, but answered after a clear: nothing of it is kept in memory (the conversation reads it at the next unlock).
            p.sent = true
            finish(p)
            throw VaultFailure(FailureKind.OTHER, CODE_UNAVAILABLE)
        }
        val m = MessageInfo(
            connectionId = p.connectionId,
            messageId = answer.messageId,
            outgoing = true,
            text = p.caption,
            sentAt = ApprovalParser.instant(answer.sentAt) ?: Instant.now(),
            delivered = false,
            read = false,
            attachment = AttachmentInfo(
                attId = answer.attId,
                state = AttachmentState.PREPARING,
                size = size.toLong(),
                width = p.photo.width,
                height = p.photo.height,
            ),
        )
        p.sent = true
        finish(p)
        onSent(m)
        return m
    }

    @Suppress("ThrowsCount")
    private suspend fun upload(p: PendingPhoto) {
        checkNotCleared(p)
        p.sealed?.destroy()
        val sealed = ClaimBlob.seal(p.photo.jpeg)
        p.sealed = sealed
        p.blobId = try {
            // A clear while it was sealed: the sealed copy goes with the photo (finish), nothing is uploaded.
            checkNotCleared(p)
            vaultGuard {
                try {
                    ops.upload(sealed.blob)
                } catch (e: RelayException) {
                    if (e.code == RelayException.QUOTA_EXCEEDED) throw VaultFailure(FailureKind.LIMIT, CODE_QUOTA, cause = e)
                    throw e
                }
            }
        } catch (e: VaultFailure) {
            sealed.destroy()
            p.sealed = null
            throw e
        }
        sealed.blob.fill(0) // uploaded: only the key and the hash are needed now
    }

    override suspend fun cancel(p: PendingPhoto) {
        val blob = p.blobId
        finish(p)
        if (blob != null && !p.sent) {
            try {
                vaultGuard { ops.discard(blob) }
            } catch (_: VaultFailure) {
                // best effort: the upload expires after the blob TTL otherwise
            }
        }
    }

    override fun abandon(p: PendingPhoto) {
        scope.launch { cancel(p) }
    }

    /**
     * Ends [p] and wipes it under [clearLock], like [clear]: a Cancel or an abandon landing while a send builds its
     * request waits for the request to be complete, so `message.send` never carries a zeroed key or thumbnail.
     */
    private fun finish(p: PendingPhoto) {
        synchronized(clearLock) {
            pending -= p
            p.wipe()
        }
    }

    override fun cachedThumbnail(connectionId: String, messageId: String): ByteArray? =
        thumbs.get(cacheKey(connectionId, messageId))?.copyOf()

    override suspend fun thumbnail(connectionId: String, messageId: String): ByteArray {
        thumbs.get(cacheKey(connectionId, messageId))?.let { return it.copyOf() }
        val mark = clearedFlow.value
        val t = vaultGuard { ops.thumb(connectionId, messageId) }
        if (!acceptableThumb(t)) throw VaultFailure(FailureKind.OTHER, CODE_TYPE)
        keepUnlessCleared(mark, t) { thumbs.put(cacheKey(connectionId, messageId), t) }
        return t.copyOf()
    }

    override suspend fun photo(connectionId: String, messageId: String): FetchedPhoto {
        photos.get(cacheKey(connectionId, messageId))?.let { return FetchedPhoto(it.bytes.copyOf(), it.type) }
        val mark = clearedFlow.value
        val got = fetches.withPermit { fetch(connectionId, messageId) }
        keepUnlessCleared(mark, got.bytes) { photos.put(cacheKey(connectionId, messageId), got) }
        return FetchedPhoto(got.bytes.copyOf(), got.type)
    }

    /**
     * (ANDROID-PLAN "Memory only, wiped at lock") What a fetch brought is kept ([keep]) only when no clear came since it
     * started ([mark]); otherwise it is zeroed and the fetch fails as unavailable. Under the same lock as [clear], so
     * that nothing is cached after a clear.
     */
    private inline fun keepUnlessCleared(mark: Long, arrived: ByteArray, keep: () -> Unit) {
        if (keptUnlessCleared(mark, arrived, keep)) return
        throw VaultFailure(FailureKind.OTHER, CODE_UNAVAILABLE)
    }

    /** [keepUnlessCleared] without the failure: false (and [arrived] zeroed) when a clear came since [mark]. */
    private inline fun keptUnlessCleared(mark: Long, arrived: ByteArray, keep: () -> Unit): Boolean {
        synchronized(clearLock) {
            if (clearedFlow.value == mark) {
                keep()
                return true
            }
        }
        arrived.fill(0)
        return false
    }

    @Suppress("ThrowsCount")
    private suspend fun fetch(connectionId: String, messageId: String): FetchedPhoto {
        val ref = vaultGuard { ops.content(connectionId, messageId) }.blob
        val raw = try {
            vaultGuard { ops.fetchBlob(ref.id) }
        } finally {
            // Every fetched blob is deleted, good or bad (§5.5): the mailbox's blob storage is shared.
            try {
                vaultGuard { ops.deleteBlob(ref.id) }
            } catch (_: VaultFailure) {
                // it expires after the blob TTL
            }
        }
        val content = try {
            if (raw.size > PhotoLimits.MAX_BLOB_BYTES) throw VaultFailure(FailureKind.OTHER, CODE_BAD)
            openBlob(raw, ref)
        } catch (_: ClaimBlob.Refused) {
            throw VaultFailure(FailureKind.OTHER, CODE_BAD)
        } catch (_: IllegalArgumentException) {
            throw VaultFailure(FailureKind.OTHER, CODE_BAD)
        } finally {
            raw.fill(0)
        }
        // The type by sniffing, never the peer's `mime`.
        val type = ImageType.sniff(content) ?: run {
            content.fill(0)
            throw VaultFailure(FailureKind.OTHER, CODE_TYPE)
        }
        return FetchedPhoto(content, type)
    }

    /** The §5.5 checks with the reference's key and hash, each exactly 32 bytes; a malformed one is a bad blob. */
    @Suppress("ThrowsCount")
    private fun openBlob(raw: ByteArray, ref: com.vettid.core.vault.BlobRef): ByteArray {
        val key = try {
            Base64s.decodeStd(ref.key, KEY_BYTES)
        } catch (_: com.vettid.core.crypto.CryptoException) {
            throw VaultFailure(FailureKind.OTHER, CODE_BAD)
        }
        try {
            val sha = try {
                Base64s.decodeStd(ref.sha256, KEY_BYTES)
            } catch (_: com.vettid.core.crypto.CryptoException) {
                throw VaultFailure(FailureKind.OTHER, CODE_BAD)
            }
            return ClaimBlob.open(raw, key, sha, ref.size)
        } catch (_: com.vettid.core.crypto.CryptoException) {
            throw VaultFailure(FailureKind.OTHER, CODE_BAD)
        } finally {
            key.fill(0)
        }
    }

    /** A thumbnail that arrived inline in `message.new` (§10.5.1), kept in memory. */
    fun onEvent(type: String, body: JsonObject) {
        if (type != "message.new") return
        val mark = clearedFlow.value
        val m = runCatching { VaultJson.decode(Message.serializer(), body) }.getOrNull()
        val bytes = m?.attachment?.thumb?.let { t -> runCatching { Base64s.decodeStd(t) }.getOrNull() }
        if (m != null && bytes != null && acceptableThumb(bytes)) {
            testHook?.invoke(HOOK_EVENT)
            // A clear while it was read: nothing of it is kept.
            keptUnlessCleared(mark, bytes) { thumbs.put(cacheKey(m.connectionId, m.messageId), bytes) }
        }
    }

    /** 1 to `thumb_max_bytes`, and JPEG, PNG or WebP by its bytes. */
    private fun acceptableThumb(t: ByteArray) = t.isNotEmpty() && t.size <= PhotoLimits.THUMB_MAX_BYTES && ImageType.sniff(t) != null

    override fun clear() {
        // Every step runs even if one throws (fail secure); the first failure is rethrown after the last.
        val steps = ClearSteps()
        synchronized(clearLock) {
            steps.step { clearedFlow.value++ }
            steps.step { thumbs.clear() }
            steps.step { photos.clear() }
            // Under the same lock as a send's last check: a send in that step completes its request first.
            synchronized(pending) {
                pending.forEach { p -> steps.step { p.wipe() } }
                steps.step { pending.clear() }
            }
        }
        steps.step { com.vettid.core.media.SharedPhotos.clear() } // the share provider refuses every read from now on
        steps.finish()
    }

    /** Clears and forgets that the vault has attachments (signed out, or a new vault). */
    fun reset() {
        try {
            clear()
        } finally {
            availableFlow.value = false
        }
    }

    companion object {
        /** The relay refused the upload: the vault's mailbox is full. */
        const val CODE_QUOTA = "quota_exceeded"

        /** A fetched blob failed its checks (hash, length, padding, decryption). */
        const val CODE_BAD = "bad"

        /** Not JPEG, PNG or WebP by its bytes. */
        const val CODE_TYPE = "type"

        /** A clear (a lock, the hold or due state, a wipe, an unlink) came during the fetch or the send. */
        const val CODE_UNAVAILABLE = "unavailable"

        internal const val HOOK_BUILD = "build"
        internal const val HOOK_EVENT = "event"
        private const val MAX_FETCHES = 2
        private const val KEY_BYTES = 32

        /** Whether a failed `message.send` is retried as the same request: no answer, `busy`, or a relay error. */
        fun retryable(e: VaultFailure): Boolean =
            e.kind == FailureKind.NO_RESPONSE || e.kind == FailureKind.NETWORK || e.code == "busy" || e.code == "relay_error"
    }
}
