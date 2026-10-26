package com.vettid.core.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Photos the member chose to share with another app (ANDROID-PLAN 0.1.39 "Saving and sharing leave VettID"), offered
 * through [PhotoShareProvider] from memory only: no file is written. Each offer has a random token; [clear] (at a
 * lock, the hold, a wipe or an unlink) withdraws every offer, after which the provider refuses every read.
 */
object SharedPhotos {
    class Offer(val bytes: ByteArray, val type: ImageType)

    private val offers = ConcurrentHashMap<String, Offer>()
    private val order = ArrayDeque<String>()

    /** Guards [order], every addition to [offers] and [pipes], and [clear]. */
    private val lock = Any()
    private val random = SecureRandom()

    /** The authority of the app's provider: `<application id>.photos`. */
    fun authority(packageName: String) = "$packageName$AUTHORITY_SUFFIX"

    /** Offers [bytes] (a copy) for one share; returns the content URI to hand to the chosen app. */
    fun offer(packageName: String, bytes: ByteArray, type: ImageType): Uri {
        val t = ByteArray(TOKEN_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val o = Offer(bytes.copyOf(), type)
        // Under the same lock as [clear]: an offer made at the same instant as a clear is either withdrawn by it or
        // made after it, never left live behind it.
        synchronized(lock) {
            // At most a few offers at once: the oldest goes (zeroized) first.
            while (order.size >= MAX_OFFERS) offers.remove(order.removeFirst())?.bytes?.fill(0)
            order.addLast(t)
            offers[t] = o
            testHook?.invoke(HOOK_OFFERED)
        }
        return Uri.Builder().scheme("content").authority(authority(packageName)).appendPath(t).appendPath("photo.${type.extension}").build()
    }

    fun get(uri: Uri): Offer? = uri.pathSegments.firstOrNull()?.let { offers[it] }

    internal fun token(uri: Uri): String? = uri.pathSegments.firstOrNull()

    fun live(token: String, offer: Offer): Boolean = offers[token] === offer

    /** The write ends of the pipes being streamed: a clear closes them with an error, so no reader gets zeros. */
    private val pipes = java.util.Collections.newSetFromMap(ConcurrentHashMap<ParcelFileDescriptor, Boolean>())

    /**
     * A pipe that streams [offer]: kept only while the offer is still offered (checked under [clear]'s lock), so no pipe
     * opened at the same instant as a clear outlives it. False: the offer was withdrawn, and the caller refuses the read.
     */
    internal fun opened(token: String, offer: Offer, pfd: ParcelFileDescriptor): Boolean = synchronized(lock) {
        if (offers[token] !== offer) return false
        pipes += pfd
        true
    }

    internal fun closed(pfd: ParcelFileDescriptor) {
        pipes -= pfd
    }

    /**
     * Withdraws every offer (zeroized). Fails secure (the owner's rule for a lock, a clear, an erase): every pipe is closed
     * with an error even if closing one fails, and the offers are withdrawn and zeroed whatever happens before.
     */
    fun clear() {
        synchronized(lock) {
            try {
                // The set's own iterator, which a stream ending meanwhile ([closed]) never breaks.
                val it = pipes.iterator()
                while (it.hasNext()) {
                    testHook?.invoke(HOOK_NEXT_PIPE)
                    val p = it.next()
                    it.remove()
                    runCatching { p.closeWithError("withdrawn") }
                }
            } finally {
                order.clear()
                val it = offers.values.iterator()
                while (it.hasNext()) {
                    val o = it.next()
                    it.remove() // withdrawn first: [live] is false before its bytes are zeroed
                    o.bytes.fill(0)
                }
            }
        }
    }

    /** Tests only: called inside [offer]'s lock ([HOOK_OFFERED]) and before each pipe [clear] closes ([HOOK_NEXT_PIPE]). */
    @Volatile
    internal var testHook: ((String) -> Unit)? = null
    internal const val HOOK_OFFERED = "offered"
    internal const val HOOK_NEXT_PIPE = "next_pipe"

    const val AUTHORITY_SUFFIX = ".photos"
    private const val TOKEN_BYTES = 16
    private const val MAX_OFFERS = 4
}

/**
 * Hands a shared photo to the app the member chose, streamed from memory through a pipe (no file is written), and
 * only while [SharedPhotos] holds it: once the vault locks, every read is refused. Not exported; readers get a URI
 * permission grant for one URI.
 */
class PhotoShareProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = SharedPhotos.get(uri)?.type?.mime

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read only")
        val offer = SharedPhotos.get(uri) ?: throw FileNotFoundException("not available")
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        val token = SharedPhotos.token(uri) ?: throw FileNotFoundException("not available")
        if (!SharedPhotos.opened(token, offer, write)) {
            // Withdrawn since it was read above (a clear at the same instant): nothing is streamed.
            runCatching { write.closeWithError("withdrawn") }
            runCatching { read.close() }
            throw FileNotFoundException("not available")
        }
        // Streamed from the offer itself (no copy), in chunks, each written only while the offer is still offered:
        // a clear (a lock, the viewer closed) ends the stream with an error, never with zeros.
        Thread {
            try {
                val out = java.io.FileOutputStream(write.fileDescriptor)
                var at = 0
                while (at < offer.bytes.size) {
                    if (!SharedPhotos.live(token, offer)) throw IOException("withdrawn")
                    val n = minOf(CHUNK, offer.bytes.size - at)
                    out.write(offer.bytes, at, n)
                    at += n
                }
                out.flush()
                write.close()
            } catch (_: IOException) {
                runCatching { write.closeWithError("withdrawn") }
            } finally {
                SharedPhotos.closed(write)
            }
        }.apply { name = "photo-share" }.start()
        return read
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val offer = SharedPhotos.get(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> "photo.${offer.type.extension}"
                OpenableColumns.SIZE -> offer.bytes.size.toLong()
                else -> null
            }
        }
        return MatrixCursor(cols, 1).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
