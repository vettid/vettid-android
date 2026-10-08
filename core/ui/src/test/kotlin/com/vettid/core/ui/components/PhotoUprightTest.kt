package com.vettid.core.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * [uprightShot]: the rotation is applied before the front camera's mirror. Mirroring first turned a front shot
 * upside down on a 270° sensor (Pixel 9 Pro, 2026-10-08).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoUprightTest {
    /** A sensor-frame image, 4 wide and 2 high, with a distinct colour in each corner. */
    private fun sensorShot(): Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.BLACK)
        setPixel(0, 0, RED)
        setPixel(W - 1, 0, GREEN)
        setPixel(0, H - 1, BLUE)
        setPixel(W - 1, H - 1, WHITE)
    }

    private data class Corners(val tl: Int, val tr: Int, val bl: Int, val br: Int)

    private fun corners(b: Bitmap) = Corners(
        tl = b.getPixel(0, 0),
        tr = b.getPixel(b.width - 1, 0),
        bl = b.getPixel(0, b.height - 1),
        br = b.getPixel(b.width - 1, b.height - 1),
    )

    @Test
    fun rearCameraIsRotatedClockwise() {
        val r90 = uprightShot(sensorShot(), 90, mirror = false)
        assertEquals(H, r90.width)
        assertEquals(W, r90.height)
        assertEquals(Corners(tl = BLUE, tr = RED, bl = WHITE, br = GREEN), corners(r90))

        val r270 = uprightShot(sensorShot(), 270, mirror = false)
        assertEquals(Corners(tl = GREEN, tr = WHITE, bl = RED, br = BLUE), corners(r270))

        val r0 = uprightShot(sensorShot(), 0, mirror = false)
        assertEquals(Corners(tl = RED, tr = GREEN, bl = BLUE, br = WHITE), corners(r0))
    }

    @Test
    fun frontCameraIsRotatedThenMirroredLeftRight() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val plain = corners(uprightShot(sensorShot(), rotation, mirror = false))
            val mirrored = corners(uprightShot(sensorShot(), rotation, mirror = true))
            // A left-right mirror of the upright picture: the top row stays the top row (never upside down).
            val expected = Corners(tl = plain.tr, tr = plain.tl, bl = plain.br, br = plain.bl)
            assertEquals("rotation $rotation", expected, mirrored)
        }
    }

    @Test
    fun largeShotsAreScaledToTheKeptSide() {
        val big = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)
        val out = uprightShot(big, 270, mirror = true)
        assertEquals(1024, minOf(out.width, out.height))
        assertEquals(1536, maxOf(out.width, out.height))
    }

    private companion object {
        const val W = 4
        const val H = 2
        val RED = Color.RED
        val GREEN = Color.GREEN
        val BLUE = Color.BLUE
        val WHITE = Color.WHITE
    }
}
