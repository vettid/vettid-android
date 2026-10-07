package com.vettid.core.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * The profile photo (VAULT-MESSAGING §10.8): encoded to a square JPEG of at most 65,536 bytes, and read back
 * defensively. Native graphics, so that JPEG compression is real.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilePhotosTest {
    /** A large picture of noise, the worst case for JPEG: the encoder must lower quality and size to fit. */
    private fun noise(w: Int, h: Int): Bitmap {
        val r = Random(7)
        val px = IntArray(w * h) { (0xFF shl 24) or r.nextInt(0x1000000) }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    @Test
    fun aLargePictureFitsTheLimitAsASquareJpeg() {
        val out = ProfilePhotos.encode(noise(3000, 2000))
        assertTrue("${out.size} bytes", out.size <= ProfilePhotos.MAX_BYTES)
        assertTrue(ProfilePhotos.isJpeg(out))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(out, 0, out.size, bounds)
        assertEquals(bounds.outWidth, bounds.outHeight)
        assertTrue(bounds.outWidth <= 512)
    }

    @Test
    fun aSmallLimitIsMetBySmallerSides() {
        val out = ProfilePhotos.encode(noise(1200, 1600), maxBytes = 8_000)
        assertTrue("${out.size} bytes", out.size <= 8_000)
    }

    @Test
    fun theCropIsCentred() {
        val b = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888)
        b.eraseColor(android.graphics.Color.RED)
        // The centre third is blue: the square crop keeps only it.
        for (x in 100 until 200) for (y in 0 until 100) b.setPixel(x, y, android.graphics.Color.BLUE)
        val sq = ProfilePhotos.centreSquare(b)
        assertEquals(100, sq.width)
        assertEquals(100, sq.height)
        assertEquals(android.graphics.Color.BLUE, sq.getPixel(0, 0))
        assertEquals(android.graphics.Color.BLUE, sq.getPixel(99, 99))
    }

    @Test
    fun theOutputCarriesNoMetadata() {
        // Re-encoded from pixels: no APP1 (EXIF) segment follows the JPEG start.
        val out = ProfilePhotos.encode(noise(800, 800))
        val hasExif = (0 until out.size - 6).any { i ->
            out[i] == 0xFF.toByte() && out[i + 1] == 0xE1.toByte() && String(out, i + 4, 4, Charsets.US_ASCII) == "Exif"
        }
        assertFalse(hasExif)
    }

    @Test
    fun anEncodedPhotoDecodes() {
        val b64 = ProfilePhotos.encodeBase64(noise(600, 400))
        val back = ProfilePhotos.decode(b64)
        assertNotNull(back)
        assertEquals(back!!.width, back.height)
    }

    @Test
    fun aPngDecodes() {
        val png = ByteArrayOutputStream().use { s ->
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, s)
            s.toByteArray()
        }
        assertTrue(ProfilePhotos.isPng(png))
        assertNotNull(ProfilePhotos.decode(Base64.encodeToString(png, Base64.NO_WRAP)))
    }

    @Test
    fun badPhotosFallBackToTheInitial() {
        assertNull(ProfilePhotos.decode(null))
        assertNull(ProfilePhotos.decode(""))
        assertNull(ProfilePhotos.decode("not base64 !!!"))
        // Not a JPEG or PNG.
        assertNull(ProfilePhotos.decode(Base64.encodeToString(ByteArray(100) { 1 }, Base64.NO_WRAP)))
        // A JPEG header followed by garbage.
        val fake = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(200) { 7 }
        assertNull(ProfilePhotos.decode(Base64.encodeToString(fake, Base64.NO_WRAP)))
        // Over the size limit: refused unread.
        val big = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(ProfilePhotos.MAX_BYTES)
        assertNull(ProfilePhotos.decode(Base64.encodeToString(big, Base64.NO_WRAP)))
    }
}
