package com.vettid.core.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** A photo prepared for sending, in memory only: the re-encoded JPEG, its thumbnail and its size in pixels. */
class PreparedPhoto(val jpeg: ByteArray, val thumb: ByteArray, val width: Int, val height: Int) {
    /** Whether [wipe] ran: the bytes are zeros, and nothing is sealed or sent from them. */
    @Volatile
    var wiped: Boolean = false
        private set

    /** Zeroizes both encodings (after the answer to `message.send`, at Cancel and at a lock). */
    fun wipe() {
        wiped = true
        jpeg.fill(0)
        thumb.fill(0)
    }
}

/** Why a photo could not be prepared or shown. */
enum class PhotoFailure {
    /** Larger than the bounds (sending: 16,384 a side or 100 MP; showing: 8,192 or 40 MP, 1,024 for a thumbnail). */
    TOO_LARGE,

    /** Not JPEG, PNG or WebP by its bytes (showing), or a format the phone cannot decode (sending). */
    UNSUPPORTED,

    /** Truncated, corrupt, or out of memory. */
    UNREADABLE,

    /** Could not be brought under `attachment_max_bytes` or the thumbnail under 32 KiB. */
    CANNOT_FIT,
}

class PhotoException(val failure: PhotoFailure) : Exception("photo: $failure")

/**
 * Reading, preparing and decoding photos in memory (ANDROID-PLAN 0.1.39). Every path reads the bounds first and
 * refuses before pixels are allocated; nothing writes a file. Decoding uses [ImageDecoder], which applies the EXIF
 * orientation to the pixels and, for an animated WebP or PNG, returns the first frame only.
 */
object Photos {
    /** Reads a picked picture into memory, at most [PhotoLimits.SOURCE_MAX_BYTES]; no copy anywhere on disk. */
    fun read(resolver: ContentResolver, uri: Uri, maxBytes: Long = PhotoLimits.SOURCE_MAX_BYTES): ByteArray {
        val input = try {
            resolver.openInputStream(uri)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: throw PhotoException(PhotoFailure.UNREADABLE)
        // The size the provider declares, when it does, refuses a picture too large before any byte is read.
        val declared = declaredSize(resolver, uri)
        if (declared != null && declared > maxBytes) {
            input.close()
            throw PhotoException(PhotoFailure.TOO_LARGE)
        }
        return input.use { s ->
            val out = ByteArrayOutputStream(declared?.toInt()?.coerceIn(BUFFER, maxBytes.toInt()) ?: BUFFER)
            val buf = ByteArray(BUFFER)
            var total = 0L
            while (true) {
                val n = try {
                    s.read(buf)
                } catch (_: IOException) {
                    throw PhotoException(PhotoFailure.UNREADABLE)
                }
                if (n < 0) break
                total += n
                if (total > maxBytes) throw PhotoException(PhotoFailure.TOO_LARGE)
                out.write(buf, 0, n)
            }
            buf.fill(0)
            out.toByteArray()
        }
    }

    private fun declaredSize(resolver: ContentResolver, uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    /**
     * Prepares [source] (a picked file's bytes, or a camera shot encoded in memory) for sending: bounds from the
     * header (16,384 a side, 100 MP), decoded with the orientation applied and downscaled to a long edge of at most
     * 2,560, re-encoded as JPEG from pixels only (quality 85, lowered in steps to fit `attachment_max_bytes`), and a
     * thumbnail of long edge 320 under 32 KiB. No EXIF, XMP, GPS or camera data survives: the encoder writes pixels.
     */
    fun prepare(source: ByteArray): PreparedPhoto {
        val declared = ImageHeader.dimensions(source)
        if (declared != null && !declared.within(PhotoLimits.SOURCE_MAX_SIDE, PhotoLimits.SOURCE_MAX_PIXELS)) {
            throw PhotoException(PhotoFailure.TOO_LARGE)
        }
        val bitmap = decode(
            source,
            PhotoLimits.SOURCE_MAX_SIDE,
            PhotoLimits.SOURCE_MAX_PIXELS,
            PhotoLimits.SEND_LONG_EDGE,
            PhotoFailure.UNSUPPORTED,
        )
        return prepare(bitmap).also { bitmap.recycle() }
    }

    /** Prepares a picture already in memory (a camera shot, upright); the caller recycles [bitmap]. */
    fun prepare(bitmap: Bitmap): PreparedPhoto {
        // An Ultra HDR photo's gain map (API 34+) would be carried by the scaling and written back by the encoder
        // with its XMP and an MPF segment: the pixels are drawn into a plain bitmap first, which has none.
        val plain = withoutGainmap(bitmap)
        try {
            return preparePlain(plain)
        } finally {
            if (plain !== bitmap) plain.recycle()
        }
    }

    private fun preparePlain(bitmap: Bitmap): PreparedPhoto {
        val photo = scaled(bitmap, PhotoLimits.SEND_LONG_EDGE)
        try {
            val jpeg = encodeUnder(photo, PhotoLimits.ATTACHMENT_MAX_BYTES.toLong(), PhotoLimits.SEND_QUALITY, PhotoLimits.MIN_QUALITY)
            val thumbBitmap = scaled(photo, PhotoLimits.THUMB_LONG_EDGE)
            val thumb = try {
                encodeUnder(thumbBitmap, PhotoLimits.THUMB_MAX_BYTES.toLong(), PhotoLimits.THUMB_QUALITY, PhotoLimits.THUMB_MIN_QUALITY)
            } finally {
                if (thumbBitmap !== photo) thumbBitmap.recycle()
            }
            if (JpegSegments.hasMetadata(jpeg) || JpegSegments.hasMetadata(thumb)) throw PhotoException(PhotoFailure.UNSUPPORTED)
            return PreparedPhoto(jpeg, thumb, photo.width, photo.height)
        } finally {
            if (photo !== bitmap) photo.recycle()
        }
    }

    /**
     * Decodes a received photo for showing: the type sniffed from its bytes (JPEG, PNG, WebP; the peer's `mime` is
     * never read), the header read first and anything over [maxSide] or [maxPixels] refused before decoding, then
     * decoded to a long edge of at most [targetLongEdge] (the screen), the first frame only.
     */
    fun decodeReceived(
        bytes: ByteArray,
        targetLongEdge: Int,
        maxSide: Int = PhotoLimits.VIEW_MAX_SIDE,
        maxPixels: Long = PhotoLimits.VIEW_MAX_PIXELS,
    ): Bitmap {
        val type = ImageType.sniff(bytes) ?: throw PhotoException(PhotoFailure.UNSUPPORTED)
        // A JPEG with a second image (an Ultra HDR gain map, MPF) is refused before decoding: the platform would
        // decode the gain map too, whose size the primary's header does not bound.
        if (type == ImageType.JPEG && (JpegSegments.hasMpf(bytes) || JpegSegments.hasSecondImage(bytes))) {
            throw PhotoException(PhotoFailure.UNSUPPORTED)
        }
        val declared = ImageHeader.dimensions(bytes) ?: throw PhotoException(PhotoFailure.UNREADABLE)
        if (!declared.within(maxSide, maxPixels)) throw PhotoException(PhotoFailure.TOO_LARGE)
        return decode(bytes, maxSide, maxPixels, targetLongEdge, PhotoFailure.UNREADABLE)
    }

    /** [decodeReceived] with a thumbnail's bounds: 1,024 pixels a side at most. */
    fun decodeThumbnail(bytes: ByteArray, targetLongEdge: Int = PhotoLimits.THUMB_LONG_EDGE): Bitmap =
        decodeReceived(bytes, targetLongEdge, PhotoLimits.THUMB_VIEW_MAX_SIDE, PhotoLimits.THUMB_VIEW_MAX_PIXELS)

    /** Encodes [bitmap] as JPEG at [quality] (pixels only). */
    fun jpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw PhotoException(PhotoFailure.UNREADABLE)
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray, maxSide: Int, maxPixels: Long, targetLongEdge: Int, unsupported: PhotoFailure): Bitmap {
        val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        return try {
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                if (!Dimensions(w, h).within(maxSide, maxPixels)) throw PhotoException(PhotoFailure.TOO_LARGE)
                val scale = minOf(1.0, targetLongEdge.toDouble() / max(w, h))
                decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.isMutableRequired = false
            }
        } catch (e: PhotoException) {
            throw e
        } catch (_: ImageDecoder.DecodeException) {
            throw PhotoException(unsupported)
        } catch (_: IOException) {
            throw PhotoException(PhotoFailure.UNREADABLE)
        } catch (_: OutOfMemoryError) {
            throw PhotoException(PhotoFailure.UNREADABLE)
        } catch (_: IllegalArgumentException) {
            throw PhotoException(PhotoFailure.UNREADABLE)
        }
    }

    /** [bitmap] as a plain ARGB_8888 bitmap when it carries a gain map (API 34+); [bitmap] itself otherwise. */
    private fun withoutGainmap(bitmap: Bitmap): Bitmap {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !bitmap.hasGainmap()) return bitmap
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(bitmap, 0f, 0f, null)
        return out
    }

    /** [bitmap] scaled so that its long edge is at most [longEdge] (the same bitmap when it already is). */
    private fun scaled(bitmap: Bitmap, longEdge: Int): Bitmap {
        val edge = max(bitmap.width, bitmap.height)
        if (edge <= longEdge) return bitmap
        val s = longEdge.toDouble() / edge
        return Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * s).roundToInt()), max(1, (bitmap.height * s).roundToInt()), true)
    }

    /** JPEG at [start], lowered by 10 to [floor]; then scaled down by a fifth and tried again, until under [limit]. */
    private fun encodeUnder(bitmap: Bitmap, limit: Long, start: Int, floor: Int): ByteArray {
        var current = bitmap
        try {
            repeat(SHRINKS) {
                var q = start
                while (q >= floor) {
                    val out = jpeg(current, q)
                    if (out.size <= limit) return out
                    q -= PhotoLimits.QUALITY_STEP
                }
                val next = scaled(current, (max(current.width, current.height) * SHRINK).roundToInt().coerceAtLeast(1))
                if (current !== bitmap) current.recycle()
                current = next
            }
        } finally {
            if (current !== bitmap) current.recycle()
        }
        throw PhotoException(PhotoFailure.CANNOT_FIT)
    }

    private const val BUFFER = 64 * 1024
    private const val SHRINKS = 6
    private const val SHRINK = 0.8
}
