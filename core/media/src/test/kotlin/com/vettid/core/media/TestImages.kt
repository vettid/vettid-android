// Hand-built bytes stay on one line each.
@file:Suppress("MaxLineLength", "MagicNumber")

package com.vettid.core.media

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Hand-built image bytes for the tests (headers, EXIF with GPS, an APNG, an animated WebP). */
object TestImages {
    private fun be32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun le24(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte())

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun pngChunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(t); update(data) }.value
        return be32(data.size.toLong()) + t + data + be32(crc)
    }

    private fun ihdr(w: Int, h: Int) = be32(w.toLong()) + be32(h.toLong()) + byteArrayOf(8, 2, 0, 0, 0) // 8-bit RGB

    /** A PNG that is only its signature and IHDR: a header declaring [w] × [h] and no pixels at all. */
    fun pngHeaderOnly(w: Int, h: Int): ByteArray = PNG_SIG + pngChunk("IHDR", ihdr(w, h))

    /** SOI, an APP0 and an SOF0 declaring [w] × [h]: enough for the header reader, not for a decoder. */
    fun minimalJpegHeader(w: Int, h: Int): ByteArray {
        val app0 = byteArrayOf(0xFF.toByte(), 0xE0.toByte()) + be16(16) + "JFIF\u0000".toByteArray() + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0)
        val sof = byteArrayOf(0xFF.toByte(), 0xC0.toByte()) + be16(11) + byteArrayOf(8) + be16(h) + be16(w) + byteArrayOf(1, 1, 0x11, 0)
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0 + sof
    }

    fun webpVp8xHeader(w: Int, h: Int): ByteArray {
        val vp8x = "VP8X".toByteArray() + le32(10) + byteArrayOf(0, 0, 0, 0) + le24(w - 1) + le24(h - 1)
        return "RIFF".toByteArray() + le32(4 + vp8x.size) + "WEBP".toByteArray() + vp8x
    }

    /** [jpeg] with an APP1 segment ([app1], from "Exif\0\0" on) inserted after SOI. */
    fun withApp1(jpeg: ByteArray, app1: ByteArray): ByteArray =
        jpeg.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0xE1.toByte()) + be16(app1.size + 2) + app1 + jpeg.copyOfRange(2, jpeg.size)

    /**
     * An EXIF block (big-endian TIFF) with Make "TestCam", Orientation [orientation] and a GPS IFD with a latitude
     * reference and a latitude: the camera and location data that must never be sent.
     */
    fun exifWithGpsAndOrientation(orientation: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val tiff = ByteArrayOutputStream()
        // Header: MM, 42, IFD0 at 8.
        tiff.write("MM".toByteArray()); tiff.write(be16(42)); tiff.write(be32(8))
        // IFD0: 3 entries (12 bytes each), next IFD 0. Data after: "TestCam\0" and the GPS IFD.
        val ifd0Size = 2 + 3 * 12 + 4
        val makeAt = 8 + ifd0Size
        val make = "TestCam\u0000".toByteArray()
        val gpsAt = makeAt + make.size
        tiff.write(be16(3))
        tiff.write(be16(0x010F) + be16(2) + be32(make.size.toLong()) + be32(makeAt.toLong())) // Make, ASCII
        tiff.write(be16(0x0112) + be16(3) + be32(1) + be16(orientation) + be16(0)) // Orientation, SHORT
        tiff.write(be16(0x8825) + be16(4) + be32(1) + be32(gpsAt.toLong())) // GPSInfo IFD pointer, LONG
        tiff.write(be32(0))
        tiff.write(make)
        // GPS IFD: LatitudeRef "N", Latitude 3 RATIONALs (data after the IFD).
        val gpsIfdSize = 2 + 2 * 12 + 4
        val latAt = gpsAt + gpsIfdSize
        tiff.write(be16(2))
        tiff.write(be16(0x0001) + be16(2) + be32(2) + "N\u0000\u0000\u0000".toByteArray())
        tiff.write(be16(0x0002) + be16(5) + be32(3) + be32(latAt.toLong()))
        tiff.write(be32(0))
        listOf(52L to 1L, 31L to 1L, 1234L to 100L).forEach { (n, d) -> tiff.write(be32(n)); tiff.write(be32(d)) }
        out.write("Exif\u0000\u0000".toByteArray())
        out.write(tiff.toByteArray())
        return out.toByteArray()
    }

    private fun zlibRows(w: Int, h: Int, r: Int, g: Int, b: Int): ByteArray {
        val raw = ByteArrayOutputStream()
        repeat(h) {
            raw.write(0)
            repeat(w) { raw.write(r); raw.write(g); raw.write(b) }
        }
        val d = Deflater()
        d.setInput(raw.toByteArray())
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        return out.toByteArray()
    }

    /** An APNG of two [w] × [h] frames: red first (also the default image), then blue. */
    fun apngRedThenBlue(w: Int, h: Int): ByteArray {
        val actl = be32(2) + be32(0)
        fun fctl(seq: Int) = be32(seq.toLong()) + be32(w.toLong()) + be32(h.toLong()) + be32(0) + be32(0) + be16(1) + be16(1) + byteArrayOf(0, 0)
        return PNG_SIG + pngChunk("IHDR", ihdr(w, h)) + pngChunk("acTL", actl) + pngChunk("fcTL", fctl(0)) +
            pngChunk("IDAT", zlibRows(w, h, 255, 0, 0)) + pngChunk("fcTL", fctl(1)) +
            pngChunk("fdAT", be32(2) + zlibRows(w, h, 0, 0, 255)) + pngChunk("IEND", ByteArray(0))
    }

    /**
     * An animated WebP of two frames whose bitstreams are those of the given still lossless WebPs,
     * the first shown first.
     */
    fun animatedWebp(w: Int, h: Int, firstStill: ByteArray, secondStill: ByteArray): ByteArray {
        fun chunk(fourcc: String, data: ByteArray): ByteArray {
            val pad = if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
            return fourcc.toByteArray() + le32(data.size) + data + pad
        }
        // The VP8L chunk with its header (the encoder may put VP8X and ICCP chunks before it).
        fun frameData(still: ByteArray): ByteArray {
            val at = (12 until still.size - 4).first { i -> "VP8L".indices.all { still[i + it] == "VP8L"[it].code.toByte() } }
            return still.copyOfRange(at, still.size)
        }
        val vp8x = chunk("VP8X", byteArrayOf(0x02, 0, 0, 0) + le24(w - 1) + le24(h - 1)) // animation flag
        val anim = chunk("ANIM", le32(0) + byteArrayOf(0, 0))
        fun anmf(still: ByteArray) = chunk("ANMF", le24(0) + le24(0) + le24(w - 1) + le24(h - 1) + le24(100) + byteArrayOf(0) + frameData(still))
        val body = "WEBP".toByteArray() + vp8x + anim + anmf(firstStill) + anmf(secondStill)
        return "RIFF".toByteArray() + le32(body.size) + body
    }
}
