// Expected failures stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Preparing and decoding photos with the platform's decoder and encoder (Robolectric's native graphics). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotosTest {
    /** Left half red, right half blue. */
    private fun halves(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.BLUE)
        c.drawRect(0f, 0f, w / 2f, h.toFloat(), Paint().apply { color = Color.RED })
        return b
    }

    private fun isRed(c: Int) = Color.red(c) > 200 && Color.blue(c) < 60

    private fun isBlue(c: Int) = Color.blue(c) > 200 && Color.red(c) < 60

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean =
        (0..hay.size - needle.size).any { i -> needle.indices.all { hay[i + it] == needle[it] } }

    @Test
    fun aJpegWithGpsCameraDataAndOrientationComesOutCleanAndUpright() {
        val source = TestImages.withApp1(Photos.jpeg(halves(400, 200), 95), TestImages.exifWithGpsAndOrientation(6))
        assertTrue(JpegSegments.hasMetadata(source))
        val p = Photos.prepare(source)
        // No APP1 (EXIF, XMP, GPS) and no APP13; nothing of the camera's name either.
        assertFalse(JpegSegments.hasMetadata(p.jpeg))
        assertFalse(JpegSegments.APP1 in JpegSegments.markers(p.jpeg))
        assertFalse(JpegSegments.APP13 in JpegSegments.markers(p.jpeg))
        assertFalse(contains(p.jpeg, "TestCam".toByteArray()))
        assertFalse(contains(p.jpeg, "Exif".toByteArray()))
        assertFalse(contains(p.jpeg, "http://ns.adobe.com/xap".toByteArray()))
        // Orientation 6 (rotate 90° clockwise): the left half (red) is now at the top.
        assertEquals(200, p.width)
        assertEquals(400, p.height)
        val back = Photos.decodeReceived(p.jpeg, 400)
        assertTrue("top red", isRed(back.getPixel(100, 20)))
        assertTrue("bottom blue", isBlue(back.getPixel(100, 380)))
        assertEquals(ImageType.JPEG, ImageType.sniff(p.thumb))
        assertFalse(JpegSegments.hasMetadata(p.thumb))
    }

    @Test
    fun aLargePhotoIsDownscaledToTheLongEdgeAndFits() {
        val p = Photos.prepare(Photos.jpeg(halves(3_000, 1_500), 90))
        assertEquals(PhotoLimits.SEND_LONG_EDGE, maxOf(p.width, p.height))
        assertEquals(1_280, p.height)
        assertTrue(p.jpeg.size <= PhotoLimits.ATTACHMENT_MAX_BYTES)
        assertTrue("thumbnail under 32 KiB", p.thumb.size <= PhotoLimits.THUMB_MAX_BYTES)
        val thumb = Photos.decodeThumbnail(p.thumb)
        assertEquals(PhotoLimits.THUMB_LONG_EDGE, maxOf(thumb.width, thumb.height))
    }

    @Test
    fun aPngIsReencodedAsJpeg() {
        val png = java.io.ByteArrayOutputStream().also { halves(300, 300).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val p = Photos.prepare(png)
        assertEquals(ImageType.JPEG, ImageType.sniff(p.jpeg))
        assertEquals(300, p.width)
    }

    @Test
    fun theBombIsRefusedBeforeAnyDecoding() {
        val bomb = TestImages.pngHeaderOnly(60_000, 60_000)
        assertEquals(PhotoFailure.TOO_LARGE, assertThrows(PhotoException::class.java) { Photos.decodeReceived(bomb, 1_000) }.failure)
        assertEquals(PhotoFailure.TOO_LARGE, assertThrows(PhotoException::class.java) { Photos.prepare(bomb) }.failure)
    }

    @Test
    fun aThumbnailDeclaringFourThousandPixelsIsRefused() {
        val big = TestImages.pngHeaderOnly(4_000, 100)
        assertEquals(PhotoFailure.TOO_LARGE, assertThrows(PhotoException::class.java) { Photos.decodeThumbnail(big) }.failure)
    }

    @Test
    fun onlySniffedTypesAreDecoded() {
        val gif = "GIF89a".toByteArray() + ByteArray(100)
        assertEquals(PhotoFailure.UNSUPPORTED, assertThrows(PhotoException::class.java) { Photos.decodeReceived(gif, 100) }.failure)
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"/>".toByteArray()
        assertEquals(PhotoFailure.UNSUPPORTED, assertThrows(PhotoException::class.java) { Photos.decodeReceived(svg, 100) }.failure)
    }

    @Test
    fun anApngIsDecodedToItsFirstFrameOnly() {
        val apng = TestImages.apngRedThenBlue(16, 16)
        assertEquals(ImageType.PNG, ImageType.sniff(apng))
        val bmp = Photos.decodeReceived(apng, 16)
        assertTrue(isRed(bmp.getPixel(8, 8)))
        val thumb = Photos.decodeThumbnail(apng, 16)
        assertTrue(isRed(thumb.getPixel(8, 8)))
    }

    @Test
    fun anAnimatedWebpIsDecodedToItsFirstFrameOnly() {
        fun still(color: Int): ByteArray {
            val b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            return java.io.ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it) }.toByteArray()
        }
        val webp = TestImages.animatedWebp(16, 16, still(Color.RED), still(Color.BLUE))
        assertEquals(ImageType.WEBP, ImageType.sniff(webp))
        assertEquals(Dimensions(16, 16), ImageHeader.dimensions(webp))
        val bmp = Photos.decodeReceived(webp, 16)
        assertTrue(isRed(bmp.getPixel(8, 8)))
    }

    @Test
    fun aCameraShotIsPreparedFromMemory() {
        val p = Photos.prepare(halves(1_000, 750))
        assertEquals(1_000, p.width)
        assertFalse(JpegSegments.hasMetadata(p.jpeg))
        p.wipe()
        assertTrue(p.jpeg.all { it == 0.toByte() } && p.thumb.all { it == 0.toByte() })
    }
    /** [jpeg] with an APP2 segment holding an MPF index inserted after SOI (as an Ultra HDR photo has). */
    private fun withMpf(jpeg: ByteArray): ByteArray {
        val payload = "MPF".toByteArray() + byteArrayOf(0) + ByteArray(16)
        val len = payload.size + 2
        return jpeg.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0xE2.toByte(), (len ushr 8).toByte(), len.toByte()) + payload +
            jpeg.copyOfRange(2, jpeg.size)
    }

    @Test
    fun aReceivedJpegWithAGainMapOrASecondImageIsRefusedBeforeDecoding() {
        val jpeg = Photos.jpeg(halves(64, 64), 90)
        assertTrue(JpegSegments.hasMpf(withMpf(jpeg)))
        assertEquals(PhotoFailure.UNSUPPORTED, assertThrows(PhotoException::class.java) { Photos.decodeReceived(withMpf(jpeg), 64) }.failure)
        val two = jpeg + Photos.jpeg(halves(8_000, 8), 50)
        assertTrue(JpegSegments.hasSecondImage(two))
        assertEquals(PhotoFailure.UNSUPPORTED, assertThrows(PhotoException::class.java) { Photos.decodeReceived(two, 64) }.failure)
        assertEquals(PhotoFailure.UNSUPPORTED, assertThrows(PhotoException::class.java) { Photos.decodeThumbnail(two) }.failure)
        assertFalse(JpegSegments.hasSecondImage(jpeg))
        assertFalse(JpegSegments.hasMpf(jpeg))
    }

    @Test
    fun aPhotoWithAGainMapIsSentWithoutIt() {
        val b = halves(400, 300)
        val gm = try {
            android.graphics.Gainmap(Bitmap.createBitmap(100, 75, Bitmap.Config.ALPHA_8)).also { b.gainmap = it }
        } catch (_: Throwable) {
            null
        }
        org.junit.Assume.assumeTrue("no gain map support in this runtime", gm != null && b.hasGainmap())
        val p = Photos.prepare(b)
        assertFalse(JpegSegments.hasMetadata(p.jpeg))
        assertFalse(JpegSegments.hasMpf(p.jpeg))
        assertFalse(JpegSegments.hasSecondImage(p.jpeg))
        assertTrue(b.hasGainmap()) // the caller's bitmap is untouched
    }
}
