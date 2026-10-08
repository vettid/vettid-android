package com.vettid.core.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Profile photos (VAULT-MESSAGING §10.8): `photo` is the standard base64 of a JPEG or PNG of at most 65,536 bytes.
 *
 * [encode] makes the member's photo: a centre-cropped square, scaled down and re-encoded as JPEG with the quality
 * (then the size) lowered until it fits the limit. Re-encoding from the decoded pixels writes no metadata, so EXIF
 * (location, camera, time) of the chosen picture never leaves the phone.
 *
 * [decode] reads a photo for display (the member's own, or a peer's self-asserted one) defensively: over the size
 * limit, not a JPEG or PNG, undecodable, or with absurd dimensions, it is null and the initial tile is shown.
 */
object ProfilePhotos {
    /** §10.8: at most 65,536 bytes of image. */
    const val MAX_BYTES = 65_536

    /**
     * The side the member's photo is encoded at first (smaller sides are tried if the qualities do not fit). The
     * crop under the review's round frame is never smaller than this, so the photo is never scaled up into blur.
     */
    const val TARGET_SIDE = 512

    @Suppress("MagicNumber")
    private val SIDES = intArrayOf(TARGET_SIDE, 384, 256, 192, 128, 96)
    @Suppress("MagicNumber")
    private val QUALITIES = intArrayOf(85, 75, 65, 55, 45, 35)

    /** Dimensions a peer's photo may declare before it is refused unread (a decompression bomb). */
    private const val MAX_DECODE_SIDE = 4_096

    /** The side a photo is decoded down to for display (tiles are at most 88 dp). */
    private const val DISPLAY_SIDE = 256

    /** The JPEG bytes of [source] for `profile.set{photo}`, at most [maxBytes]. */
    fun encode(source: Bitmap, maxBytes: Int = MAX_BYTES): ByteArray {
        val square = centreSquare(source)
        try {
            for (side in SIDES) {
                fitting(square, side, maxBytes)?.let { return it }
            }
            error("photo does not fit")
        } finally {
            if (square !== source) square.recycle()
        }
    }

    /** [square] at [side] px with the highest quality that fits [maxBytes], or null. */
    private fun fitting(square: Bitmap, side: Int, maxBytes: Int): ByteArray? {
        val scaled = if (square.width > side) Bitmap.createScaledBitmap(square, side, side, true) else square
        try {
            return QUALITIES.asSequence().map { jpeg(scaled, it) }.firstOrNull { it.size <= maxBytes }
        } finally {
            if (scaled !== square) scaled.recycle()
        }
    }

    /** [encode] as the standard base64 `profile.set` takes. */
    fun encodeBase64(source: Bitmap): String = Base64.encodeToString(encode(source), Base64.NO_WRAP)

    /** The centred square of [b] (the whole bitmap when it is square already). */
    fun centreSquare(b: Bitmap): Bitmap {
        val side = min(b.width, b.height)
        if (b.width == b.height) return b
        return Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
    }

    private fun jpeg(b: Bitmap, quality: Int): ByteArray {
        // JPEG has no alpha: draw a transparent picture onto white rather than black.
        val opaque = if (b.hasAlpha()) {
            Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also { o ->
                android.graphics.Canvas(o).apply {
                    drawColor(android.graphics.Color.WHITE)
                    drawBitmap(b, 0f, 0f, null)
                }
            }
        } else {
            b
        }
        return ByteArrayOutputStream().use { s ->
            opaque.compress(Bitmap.CompressFormat.JPEG, quality, s)
            if (opaque !== b) opaque.recycle()
            s.toByteArray()
        }
    }

    /** A photo from the vault for display, or null (absent, too large, not a JPEG or PNG, undecodable). */
    @Suppress("ReturnCount")
    fun decode(base64: String?): Bitmap? {
        val bytes = imageBytes(base64) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (!sane(bounds.outWidth) || !sane(bounds.outHeight)) return null
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= DISPLAY_SIDE) sample *= 2
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun sane(side: Int) = side in 1..MAX_DECODE_SIDE

    /** The decoded bytes of a JPEG or PNG of at most [MAX_BYTES], else null. */
    @Suppress("ReturnCount")
    private fun imageBytes(base64: String?): ByteArray? {
        // Base64 of 65,536 bytes is 87,384 characters: anything longer is over the limit, unread.
        if (base64.isNullOrEmpty() || base64.length > MAX_BASE64_CHARS) return null
        val bytes = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val image = isJpeg(bytes) || isPng(bytes)
        return bytes.takeIf { image && it.size <= MAX_BYTES }
    }

    fun isJpeg(b: ByteArray): Boolean = b.size > JPEG.size && JPEG.indices.all { b[it] == JPEG[it] }

    fun isPng(b: ByteArray): Boolean = b.size > PNG.size && PNG.indices.all { b[it] == PNG[it] }

    private const val MAX_BASE64_CHARS = (MAX_BYTES + 2) / 3 * 4

    @Suppress("MagicNumber") // the format signatures
    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    @Suppress("MagicNumber")
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
}

/** [ProfilePhotos.decode] as an [ImageBitmap], decoded once per distinct photo. */
@Composable
fun rememberProfilePhoto(base64: String?): ImageBitmap? =
    remember(base64) { ProfilePhotos.decode(base64)?.asImageBitmap() }
