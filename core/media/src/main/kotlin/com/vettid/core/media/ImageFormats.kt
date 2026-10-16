package com.vettid.core.media

/**
 * The photo types a vault and an app accept (VAULT-MESSAGING 0.32.0 §10.5.1 `photo_types`), decided **by sniffing**
 * the first bytes and nothing else: never by the `mime` or the name a peer sent.
 */
enum class ImageType(val mime: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    ;

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        /** JPEG `FF D8 FF`; PNG its 8-byte signature; WebP `RIFF`, any 4 bytes, `WEBP`. Anything else is null. */
        fun sniff(b: ByteArray): ImageType? = when {
            b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> JPEG
            b.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { b[it] == PNG_SIGNATURE[it] } -> PNG
            b.size >= WEBP_MIN && b.ascii(0, "RIFF") && b.ascii(8, "WEBP") -> WEBP
            else -> null
        }

        private const val WEBP_MIN = 12
    }
}

/** A picture's width and height in pixels, as its header declares them. */
data class Dimensions(val width: Int, val height: Int) {
    val longEdge: Int get() = maxOf(width, height)
    val pixels: Long get() = width.toLong() * height.toLong()

    /** Whether this is within [maxSide] on each side and [maxPixels] in all. */
    fun within(maxSide: Int, maxPixels: Long): Boolean = width in 1..maxSide && height in 1..maxSide && pixels <= maxPixels
}

/**
 * Reads the dimensions a JPEG, PNG or WebP declares **from its header only**, before any decoder allocates pixels:
 * a decompression bomb (a small file declaring 60,000 × 60,000 pixels) is refused from these few bytes. Null when the
 * header cannot be read (truncated, or not one of the three types).
 */
object ImageHeader {
    fun dimensions(b: ByteArray): Dimensions? = when (ImageType.sniff(b)) {
        ImageType.PNG -> png(b)
        ImageType.JPEG -> jpeg(b)
        ImageType.WEBP -> webp(b)
        null -> null
    }

    // PNG: the signature, then the IHDR chunk (length 13): width and height as 32-bit big-endian.
    private fun png(b: ByteArray): Dimensions? {
        if (b.size < PNG_IHDR_END || !b.ascii(PNG_IHDR_TYPE, "IHDR")) return null
        val w = b.u32be(PNG_IHDR_TYPE + 4)
        val h = b.u32be(PNG_IHDR_TYPE + 8)
        val valid = 1L..Int.MAX_VALUE.toLong()
        return if (w in valid && h in valid) Dimensions(w.toInt(), h.toInt()) else null
    }

    // JPEG: the segments after SOI up to the first start-of-frame (SOF0–SOF15 but DHT C4, JPG C8 and DAC CC).
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // one branch per marker kind
    private fun jpeg(b: ByteArray): Dimensions? {
        var i = 2
        while (i + 3 < b.size) {
            if (b[i] != 0xFF.toByte()) return null
            var m = b[i + 1].toInt() and 0xFF
            while (m == 0xFF && i + 2 < b.size) { // fill bytes
                i++
                m = b[i + 1].toInt() and 0xFF
            }
            i += 2
            if (m == 0xD8 || m == 0x01 || m in 0xD0..0xD7) continue // no length
            if (m == 0xD9 || m == 0xDA || i + 1 >= b.size) return null // end, or scan data before any frame
            val len = b.u16be(i)
            if (len < 2) return null
            val sof = m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC
            if (sof) {
                if (i + JPEG_SOF_MIN > b.size) return null
                val h = b.u16be(i + 3)
                val w = b.u16be(i + 5)
                return if (w > 0 && h > 0) Dimensions(w, h) else null
            }
            i += len
        }
        return null
    }

    // WebP: the first chunk after "RIFF....WEBP": VP8 (lossy), VP8L (lossless) or VP8X (extended, the canvas).
    @Suppress("ReturnCount")
    private fun webp(b: ByteArray): Dimensions? {
        if (b.size < WEBP_CHUNK + 8) return null
        return when {
            b.ascii(WEBP_CHUNK, "VP8 ") -> {
                // Frame tag (3), start code 9D 01 2A, then 14-bit width and height, little-endian.
                val p = WEBP_CHUNK + 8 + 3
                if (b.size < p + 7) return null
                if (b[p] != 0x9D.toByte() || b[p + 1] != 0x01.toByte() || b[p + 2] != 0x2A.toByte()) return null
                Dimensions(b.u16le(p + 3) and VP8_MASK, b.u16le(p + 5) and VP8_MASK).takeIf { it.width > 0 && it.height > 0 }
            }
            b.ascii(WEBP_CHUNK, "VP8L") -> {
                // Signature 0x2F, then 14 bits width − 1 and 14 bits height − 1, little-endian bit order.
                val p = WEBP_CHUNK + 8
                if (b.size < p + 5 || b[p] != 0x2F.toByte()) return null
                val bits = (b[p + 1].toLong() and 0xFF) or ((b[p + 2].toLong() and 0xFF) shl 8) or
                    ((b[p + 3].toLong() and 0xFF) shl 16) or ((b[p + 4].toLong() and 0xFF) shl 24)
                Dimensions(((bits and 0x3FFF) + 1).toInt(), (((bits shr 14) and 0x3FFF) + 1).toInt())
            }
            b.ascii(WEBP_CHUNK, "VP8X") -> {
                // Flags (4), then canvas width − 1 and height − 1, 24-bit little-endian each.
                val p = WEBP_CHUNK + 8 + 4
                if (b.size < p + 6) return null
                Dimensions(b.u24le(p) + 1, b.u24le(p + 3) + 1)
            }
            else -> null
        }
    }

    private const val PNG_IHDR_TYPE = 12
    private const val PNG_IHDR_END = 24
    private const val JPEG_SOF_MIN = 7
    private const val WEBP_CHUNK = 12
    private const val VP8_MASK = 0x3FFF
}

/**
 * The marker segments of a JPEG up to its scan data (for the check that an encoded photo carries no metadata:
 * no APP1 (EXIF, XMP, so no GPS or camera data) and no APP13 (IPTC); the encoder is given sRGB pixels, so an ICC
 * profile, if the platform writes one, is sRGB's).
 */
object JpegSegments {
    const val APP1 = 0xE1
    const val APP2 = 0xE2
    const val APP13 = 0xED

    /** The markers in order (without SOI), up to and including SOS; empty for anything but a JPEG. */
    @Suppress("LoopWithTooManyJumpStatements")
    fun markers(b: ByteArray): List<Int> {
        if (ImageType.sniff(b) != ImageType.JPEG) return emptyList()
        val out = mutableListOf<Int>()
        var i = 2
        while (i + 3 < b.size && b[i] == 0xFF.toByte()) {
            val m = b[i + 1].toInt() and 0xFF
            i += 2
            if (m == 0x01 || m in 0xD0..0xD7) continue
            out += m
            if (m == 0xDA || m == 0xD9) break
            i += b.u16be(i)
        }
        return out
    }

    /** Whether [b] carries a metadata segment: APP1 (EXIF, XMP, so GPS and camera data) or APP13 (IPTC). */
    fun hasMetadata(b: ByteArray): Boolean = markers(b).any { it == APP1 || it == APP13 }
}

internal fun ByteArray.ascii(at: Int, s: String): Boolean =
    at + s.length <= size && s.indices.all { this[at + it] == s[it].code.toByte() }

internal fun ByteArray.u16be(at: Int): Int = ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

internal fun ByteArray.u16le(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.u24le(at: Int): Int = u16le(at) or ((this[at + 2].toInt() and 0xFF) shl SHIFT_16)

internal fun ByteArray.u32be(at: Int): Long = (u16be(at).toLong() shl SHIFT_16) or u16be(at + 2).toLong()

private const val SHIFT_16 = 16
