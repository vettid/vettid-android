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
    private val random = SecureRandom()

    /** The authority of the app's provider: `<application id>.photos`. */
    fun authority(packageName: String) = "$packageName$AUTHORITY_SUFFIX"

    /** Offers [bytes] (a copy) for one share; returns the content URI to hand to the chosen app. */
    fun offer(packageName: String, bytes: ByteArray, type: ImageType): Uri {
        val t = ByteArray(TOKEN_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        synchronized(order) {
            // At most a few offers at once: the oldest goes (zeroized) first.
            while (order.size >= MAX_OFFERS) offers.remove(order.removeFirst())?.bytes?.fill(0)
            order.addLast(t)
        }
        offers[t] = Offer(bytes.copyOf(), type)
        return Uri.Builder().scheme("content").authority(authority(packageName)).appendPath(t).appendPath("photo.${type.extension}").build()
    }

    fun get(uri: Uri): Offer? = uri.pathSegments.firstOrNull()?.let { offers[it] }

    internal fun token(uri: Uri): String? = uri.pathSegments.firstOrNull()

    fun live(token: String, offer: Offer): Boolean = offers[token] === offer

    /** The write ends of the pipes being streamed: a clear closes them with an error, so no reader gets zeros. */
    private val pipes = java.util.Collections.newSetFromMap(ConcurrentHashMap<ParcelFileDescriptor, Boolean>())

    internal fun opened(pfd: ParcelFileDescriptor) {
        pipes += pfd
    }

    internal fun closed(pfd: ParcelFileDescriptor) {
        pipes -= pfd
    }

    /** Withdraws every offer (zeroized). */
    fun clear() {
        pipes.toList().forEach { runCatching { it.closeWithError("withdrawn") } }
        pipes.clear()
        synchronized(order) { order.clear() }
        offers.values.forEach { it.bytes.fill(0) }
        offers.clear()
    }

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
        SharedPhotos.opened(write)
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
