package com.vettid.feature.settings

import com.vettid.core.ui.components.ProfilePhotos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crop math of the review's round frame (owner, 2026-10-08): the shot always covers the frame, the zoom stays
 * within 1 (fitted) and the most that keeps the crop at least [ProfilePhotos.TARGET_SIDE] px, and the frame maps to
 * exactly one square of the shot whatever its shape and whatever size the frame has on screen.
 */
class PhotoCropTest {
    private val eps = 1e-3f

    private fun inside(r: CropRect, w: Int, h: Int) {
        assertTrue("$r in $w×$h", r.left >= 0 && r.top >= 0 && r.left + r.side <= w && r.top + r.side <= h)
    }

    @Test
    fun theDefaultFramingIsTheCentredSquareOfEveryShape() {
        assertEquals(CropRect(256, 0, 1536), PhotoCrop.sourceRect(Framing(), 2048, 1536)) // landscape
        assertEquals(CropRect(0, 384, 1536), PhotoCrop.sourceRect(Framing(), 1536, 2304)) // portrait (rotated shot)
        assertEquals(CropRect(0, 0, 900), PhotoCrop.sourceRect(Framing(), 900, 900)) // square
        assertEquals(CropRect(0, 1, 3), PhotoCrop.sourceRect(Framing(), 3, 5)) // odd sides
    }

    @Test
    fun theMostZoomKeepsTheCropAtTheEncodedSide() {
        assertEquals(3f, PhotoCrop.maxZoom(2048, 1536), eps)
        assertEquals(3f, PhotoCrop.maxZoom(1536, 2304), eps)
        assertEquals(1.875f, PhotoCrop.maxZoom(1280, 960), eps)
        // A shot no larger than the encoded side cannot be zoomed at all (it would only be scaled up).
        assertEquals(1f, PhotoCrop.maxZoom(400, 300), eps)
        assertEquals(1f, PhotoCrop.maxZoom(ProfilePhotos.TARGET_SIDE, 2000), eps)
        val r = PhotoCrop.sourceRect(Framing(zoom = 100f), 2048, 1536)
        assertEquals(ProfilePhotos.TARGET_SIDE, r.side)
        inside(r, 2048, 1536)
    }

    @Test
    fun theZoomIsClampedToFittedAndToTheMost() {
        assertEquals(1f, PhotoCrop.clamp(Framing(zoom = 0.2f), 2048, 1536).zoom, eps)
        assertEquals(3f, PhotoCrop.clamp(Framing(zoom = 9f), 2048, 1536).zoom, eps)
        assertEquals(1f, PhotoCrop.clamp(Framing(zoom = Float.NaN), 2048, 1536).zoom, eps)
        assertEquals(2f, PhotoCrop.clamp(Framing(zoom = 2f), 2048, 1536).zoom, eps)
    }

    @Test
    fun theCentreIsClampedSoTheShotCoversTheFrame() {
        // Fitted on a landscape shot: free left-right within the spare width, fixed top-bottom.
        val f = PhotoCrop.clamp(Framing(1f, 0f, 0f), 2048, 1536)
        assertEquals(768f / 2048, f.centreX, eps)
        assertEquals(0.5f, f.centreY, eps)
        assertEquals(CropRect(0, 0, 1536), PhotoCrop.sourceRect(f, 2048, 1536))
        val g = PhotoCrop.clamp(Framing(1f, 1f, 1f), 2048, 1536)
        assertEquals(CropRect(512, 0, 1536), PhotoCrop.sourceRect(g, 2048, 1536))
        // Zoomed in 2x: the 768 px square can reach every corner, never past it.
        val z = PhotoCrop.sourceRect(Framing(2f, 1f, 1f), 2048, 1536)
        assertEquals(CropRect(2048 - 768, 1536 - 768, 768), z)
        val o = PhotoCrop.sourceRect(Framing(2f, -5f, Float.NaN), 2048, 1536)
        assertEquals(0, o.left)
        assertEquals((1536 - 768) / 2, o.top)
    }

    @Test
    fun theFrameMapsToTheSameSquareWhateverItsSizeOnScreen() {
        // A rotation changes the frame's size on screen; the framing is normalised, so the crop stays put.
        var f = Framing()
        f = PhotoCrop.gesture(f, 2048, 1536, frame = 1000f, 500f, 500f, 0f, 0f, zoomChange = 2f)
        f = PhotoCrop.gesture(f, 2048, 1536, frame = 1000f, 500f, 500f, -100f, 50f, zoomChange = 1f)
        val before = PhotoCrop.sourceRect(f, 2048, 1536)
        val scaleSmall = PhotoCrop.displayScale(f, 2048, 1536, 600f)
        val scaleLarge = PhotoCrop.displayScale(f, 2048, 1536, 1400f)
        assertEquals(1400f / 600f, scaleLarge / scaleSmall, eps)
        assertEquals(before, PhotoCrop.sourceRect(f, 2048, 1536))
        // On a frame of 1000 px at 2x, 1 screen px is 768 / 1000 shot px: a drag of -100, 50 moves the crop +76.8, -38.4.
        assertEquals(CropRect(640 + 77, 384 - 38, 768), before)
    }

    @Test
    fun aDragMovesTheShotWithTheFinger() {
        val start = Framing(2f)
        // Dragging right shows more of the left: the crop's centre moves left.
        val right = PhotoCrop.gesture(start, 2048, 1536, 1000f, 600f, 500f, 100f, 0f, 1f)
        assertTrue(right.centreX < start.centreX)
        val down = PhotoCrop.gesture(start, 2048, 1536, 1000f, 500f, 600f, 0f, 100f, 1f)
        assertTrue(down.centreY < start.centreY)
        // A drag past the edge stops at it.
        val far = PhotoCrop.gesture(start, 2048, 1536, 1000f, 500f, 500f, 1e6f, 1e6f, 1f)
        assertEquals(CropRect(0, 0, 768), PhotoCrop.sourceRect(far, 2048, 1536))
        // Fitted on a landscape shot, an up-down drag cannot move it (nothing to show).
        val fitted = PhotoCrop.gesture(Framing(), 2048, 1536, 1000f, 500f, 500f, 0f, 300f, 1f)
        assertEquals(0.5f, fitted.centreY, eps)
    }

    @Test
    fun aPinchZoomsAboutItsCentroid() {
        // A square shot, the pinch over its top-left quarter's centre: that point stays under the fingers.
        val w = 2000
        val frame = 1000f
        val f = PhotoCrop.gesture(Framing(), w, w, frame, 250f, 250f, 0f, 0f, 2f)
        assertEquals(2f, f.zoom, eps)
        val r = PhotoCrop.sourceRect(f, w, w)
        assertEquals(CropRect(250, 250, 1000), r)
        // Pinching out past fitted stops at fitted, and re-centres within the shot.
        val out = PhotoCrop.gesture(f, w, w, frame, 250f, 250f, 0f, 0f, 0.1f)
        assertEquals(1f, out.zoom, eps)
        assertEquals(CropRect(0, 0, w), PhotoCrop.sourceRect(out, w, w))
        // Pinching in past the most stops at the most.
        val most = PhotoCrop.gesture(Framing(), w, w, frame, 500f, 500f, 0f, 0f, 50f)
        assertEquals(PhotoCrop.maxZoom(w, w), most.zoom, eps)
        // No frame yet (not measured): nothing changes.
        assertEquals(Framing(), PhotoCrop.gesture(Framing(), w, w, 0f, 1f, 1f, 5f, 5f, 3f))
    }

    @Test
    fun theZoomButtonsStepAndStopAtTheLimits() {
        var f = Framing()
        f = PhotoCrop.step(f, 2048, 1536, zoomIn = true)
        assertEquals(PhotoCrop.ZOOM_STEP, f.zoom, eps)
        repeat(20) { f = PhotoCrop.step(f, 2048, 1536, zoomIn = true) }
        assertEquals(3f, f.zoom, eps)
        repeat(20) { f = PhotoCrop.step(f, 2048, 1536, zoomIn = false) }
        assertEquals(1f, f.zoom, eps)
        assertEquals(2.5f, PhotoCrop.zoomTo(Framing(), 2048, 1536, 2.5f).zoom, eps)
        // A small shot cannot be zoomed by the buttons either.
        assertEquals(1f, PhotoCrop.step(Framing(), 400, 300, zoomIn = true).zoom, eps)
    }

    @Test
    fun aDoubleTapZoomsInThereAndAnotherGoesBack() {
        val f = PhotoCrop.doubleTap(Framing(), 2000, 2000, 1000f, 250f, 250f)
        assertEquals(PhotoCrop.DOUBLE_TAP_ZOOM, f.zoom, eps)
        assertEquals(CropRect(250, 250, 1000), PhotoCrop.sourceRect(f, 2000, 2000))
        assertEquals(Framing(), PhotoCrop.doubleTap(f, 2000, 2000, 1000f, 10f, 10f))
        // At most the allowed zoom (1.875 for a 1280×960 shot).
        assertEquals(1.875f, PhotoCrop.doubleTap(Framing(), 1280, 960, 1000f, 500f, 500f).zoom, eps)
    }

    @Test
    fun theAccessibilityMovesShiftAQuarterFrameAndOnlyWhereTheyCan() {
        val f = Framing(2f)
        val left = PhotoCrop.nudge(f, 2048, 1536, -1, 0)
        assertEquals(0.5f - 0.25f * 768 / 2048, left.centreX, eps)
        assertTrue(PhotoCrop.canNudge(f, 2048, 1536, 0, -1))
        val corner = Framing(2f, 0f, 0f)
        assertFalse(PhotoCrop.canNudge(corner, 2048, 1536, -1, 0))
        assertFalse(PhotoCrop.canNudge(corner, 2048, 1536, 0, -1))
        assertTrue(PhotoCrop.canNudge(corner, 2048, 1536, 1, 1))
        // Fitted on a landscape shot: left and right only.
        assertFalse(PhotoCrop.canNudge(Framing(), 2048, 1536, 0, 1))
        assertTrue(PhotoCrop.canNudge(Framing(), 2048, 1536, 1, 0))
    }

    @Test
    fun everyFramingMapsInsideTheShot() {
        val shapes = listOf(2048 to 1536, 1536 to 2048, 1536 to 2304, 1000 to 1000, 513 to 4000, 300 to 200)
        val values = listOf(-1f, 0f, 0.1f, 0.5f, 0.9f, 1f, 2f)
        val framings = listOf(0.5f, 1f, 1.7f, 3f, 10f).flatMap { z ->
            values.flatMap { x -> values.map { y -> Framing(z, x, y) } }
        }
        shapes.forEach { (w, h) ->
            framings.forEach { f ->
                val r = PhotoCrop.sourceRect(f, w, h)
                inside(r, w, h)
                assertTrue(r.side >= minOf(ProfilePhotos.TARGET_SIDE, w, h))
            }
        }
    }
}
