package com.vettid.feature.settings

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import com.vettid.core.data.vault.AccountInfo
import com.vettid.core.data.vault.OwnProfile
import com.vettid.core.ui.components.CameraAccess
import com.vettid.core.ui.components.CameraLens
import com.vettid.core.ui.components.ProfilePhotos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * The profile photo is taken with the in-app camera, never chosen (owner feedback 2026-10-08): the capture's state
 * machine (permission, cameras, shutter, retake, use), the screens as rendered, and the encoding the shot goes through.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoCaptureTest {
    @get:Rule
    val rule = createComposeRule()

    private fun shot(w: Int = 40, h: Int = 30): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    private companion object {
        const val GREY = 0xFF808080.toInt()
    }

    private fun granted() = PhotoCaptureMachine().apply { access(CameraAccess.GRANTED) }

    // --- the state machine ---

    @Test
    fun theFrontCameraIsFirstAndNothingIsTakenWithoutThePermission() {
        val m = PhotoCaptureMachine()
        assertEquals(CameraLens.FRONT, m.uiState.value.lens)
        assertFalse(m.uiState.value.live)
        assertFalse(m.shutter())
        m.access(CameraAccess.DENIED)
        assertFalse(m.shutter())
        m.access(CameraAccess.BLOCKED)
        assertFalse(m.shutter())
        assertFalse(m.uiState.value.canSwitch)
        m.access(CameraAccess.GRANTED)
        assertTrue(m.uiState.value.live)
        assertTrue(m.shutter())
        assertEquals(CaptureStep.TAKING, m.uiState.value.step)
    }

    @Test
    fun theLensSwitchesBothWaysOnlyWhenThePhoneHasBoth() {
        val m = granted()
        m.lenses(front = true, back = true)
        m.switchLens()
        assertEquals(CameraLens.BACK, m.uiState.value.lens)
        m.switchLens()
        assertEquals(CameraLens.FRONT, m.uiState.value.lens)
        m.lenses(front = true, back = false)
        assertFalse(m.uiState.value.canSwitch)
        m.switchLens()
        assertEquals(CameraLens.FRONT, m.uiState.value.lens)
    }

    @Test
    fun aPhoneWithoutAFrontCameraUsesTheRearOneAndOneWithoutAnyHasNoCamera() {
        val m = granted()
        m.lenses(front = false, back = true)
        assertEquals(CameraLens.BACK, m.uiState.value.lens)
        assertFalse(m.uiState.value.noCamera)
        m.lenses(front = false, back = false)
        assertTrue(m.uiState.value.noCamera)
        assertFalse(m.shutter())
        val n = granted()
        n.unavailable()
        assertTrue(n.uiState.value.noCamera)
        assertFalse(n.uiState.value.live)
    }

    @Test
    fun aShotIsReviewedThenRetakenOrUsed() {
        val m = granted()
        assertTrue(m.shutter())
        assertFalse(m.shutter()) // one picture at a time
        val first = shot()
        m.shot(first)
        assertEquals(CaptureStep.REVIEW, m.uiState.value.step)
        assertSame(first, m.uiState.value.shot)
        assertFalse(m.uiState.value.canSwitch)
        m.retake()
        assertEquals(CaptureStep.LIVE, m.uiState.value.step)
        assertNull(m.uiState.value.shot)
        assertTrue(first.isRecycled) // dropped, not kept anywhere
        assertTrue(m.shutter())
        val second = shot()
        m.shot(second)
        assertSame(second, m.use()?.shot)
        assertFalse(second.isRecycled) // handed over to the encoder
        assertNull(m.uiState.value.shot)
        assertNull(m.use())
    }

    @Test
    fun aFailedShotSaysSoAndALateOneIsDropped() {
        val m = granted()
        m.shutter()
        m.shot(null)
        assertEquals(CaptureStep.LIVE, m.uiState.value.step)
        assertTrue(m.uiState.value.failed)
        val late = shot()
        m.shot(late) // no shutter pressed
        assertNull(m.uiState.value.shot)
        assertTrue(late.isRecycled)
    }

    @Test
    fun resetKeepsThePermissionAndDropsTheShot() {
        val m = granted()
        m.lenses(front = true, back = true)
        m.switchLens()
        m.shutter()
        val s = shot()
        m.shot(s)
        m.reset()
        assertEquals(PhotoCaptureUiState(access = CameraAccess.GRANTED), m.uiState.value)
        assertTrue(s.isRecycled)
    }

    // --- the encoding (VAULT-MESSAGING §10.8) ---

    @Test
    fun theShotIsEncodedAsASquareJpegWithinTheLimit() {
        val r = Random(3)
        val px = IntArray(1600 * 1200) { (0xFF shl 24) or r.nextInt(0x1000000) }
        val big = Bitmap.createBitmap(px, 1600, 1200, Bitmap.Config.ARGB_8888)
        val b64 = encodeShot(centredSelection(big))
        assertNotNull(b64)
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        assertTrue("${bytes.size} bytes", bytes.size <= ProfilePhotos.MAX_BYTES)
        assertTrue(ProfilePhotos.isJpeg(bytes))
        // A JPEG re-encoded from pixels: no EXIF (APP1) segment right after SOI.
        assertFalse(bytes[2] == 0xFF.toByte() && bytes[3] == 0xE1.toByte())
        val d = ProfilePhotos.decode(b64)!!
        assertEquals(d.width, d.height)
        assertTrue(big.isRecycled)
    }

    // --- the round frame: zoom, move, Reset (owner, 2026-10-08) ---

    private fun reviewing(w: Int = 2048, h: Int = 1536): Pair<PhotoCaptureMachine, Bitmap> {
        val m = granted()
        m.shutter()
        val b = shot(w, h)
        m.shot(b)
        return m to b
    }

    @Test
    fun theFramingIsClampedAndResetRestoresTheDefault() {
        val (m, _) = reviewing()
        assertEquals(Framing(), m.uiState.value.framing)
        assertEquals(3f, m.uiState.value.maxZoom, 1e-3f)
        m.frame { _, _, _ -> Framing(zoom = 9f, centreX = -1f, centreY = 2f) }
        val f = m.uiState.value.framing
        assertEquals(3f, f.zoom, 1e-3f)
        assertEquals(CropRect(0, 1536 - 512, 512), PhotoCrop.sourceRect(f, 2048, 1536))
        m.resetFraming()
        assertEquals(Framing(), m.uiState.value.framing)
        assertEquals(CaptureStep.REVIEW, m.uiState.value.step) // Reset keeps the shot
    }

    @Test
    fun retakeAndANewShotStartFromTheDefaultFraming() {
        val (m, first) = reviewing()
        m.frame { f, w, h -> PhotoCrop.zoomTo(f, w, h, 2f) }
        m.retake()
        assertTrue(first.isRecycled)
        assertEquals(Framing(), m.uiState.value.framing)
        // No shot under review: the framing cannot change.
        m.frame { _, _, _ -> Framing(zoom = 2f) }
        assertEquals(Framing(), m.uiState.value.framing)
        m.shutter()
        m.shot(shot(2048, 1536))
        assertEquals(Framing(), m.uiState.value.framing)
    }

    @Test
    fun useHandsOverTheSquareUnderTheFrame() {
        val (m, b) = reviewing()
        m.frame { f, w, h -> PhotoCrop.zoomTo(f, w, h, 2f) }
        val sel = m.use()!!
        assertSame(b, sel.shot)
        assertEquals(CropRect(640, 384, 768), sel.crop)
        assertEquals(Framing(), m.uiState.value.framing)
        assertEquals(CaptureStep.LIVE, m.uiState.value.step)
    }

    /** A grey shot with a red top-left quarter: zoomed and moved onto it, "Use photo" keeps red only. */
    @Test
    fun usePhotoEncodesTheSelectedRegion() {
        fun marked(): Bitmap {
            val w = 2048
            val h = 1536
            val px = IntArray(w * h) { i -> if (i % w < w / 2 && i / w < h / 2) android.graphics.Color.RED else GREY }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }
        // Default framing: the centred square, mostly grey.
        val (plain, _) = reviewing()
        plain.retake()
        plain.shutter()
        plain.shot(marked())
        val centred = ProfilePhotos.decode(encodeShot(plain.use()!!))!!
        assertTrue(isGrey(centred.getPixel(centred.width * 3 / 4, centred.height * 3 / 4)))
        // Zoomed 2x (a 768 px square) and dragged to the top-left corner: all red.
        val m = granted()
        m.shutter()
        val b = marked()
        m.shot(b)
        m.frame { f, w, h -> PhotoCrop.gesture(f, w, h, 1000f, 500f, 500f, 0f, 0f, 2f) }
        m.frame { f, w, h -> PhotoCrop.gesture(f, w, h, 1000f, 500f, 500f, 5000f, 5000f, 1f) }
        val sel = m.use()!!
        assertEquals(CropRect(0, 0, 768), sel.crop)
        val b64 = encodeShot(sel)
        assertTrue(b.isRecycled)
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        assertTrue(bytes.size <= ProfilePhotos.MAX_BYTES)
        assertFalse(bytes[2] == 0xFF.toByte() && bytes[3] == 0xE1.toByte()) // no EXIF
        val out = ProfilePhotos.decode(b64)!!
        assertEquals(out.width, out.height)
        assertEquals(red(out), 1f, 0.02f)
    }

    private fun isGrey(c: Int) = android.graphics.Color.red(c) in 100..160 && android.graphics.Color.green(c) in 100..160

    /** The share of [b]'s pixels that are clearly red. */
    private fun red(b: Bitmap): Float {
        var n = 0
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val c = b.getPixel(x, y)
            if (android.graphics.Color.red(c) > 200 && android.graphics.Color.green(c) < 60) n++
        }
        return n.toFloat() / (b.width * b.height)
    }

    // --- the screens ---

    @Test
    fun theLiveCameraOffersTheShutterAndTheRearCamera() {
        var shutter = 0
        var switched = 0
        rule.setContent {
            PhotoCaptureContent(
                PhotoCaptureUiState(access = CameraAccess.GRANTED),
                PhotoCaptureActions(onShutter = { shutter++ }, onSwitchLens = { switched++ }),
            ) { FakeCameraPreview(it) }
        }
        rule.onNodeWithTag("photo_capture_live").assertIsDisplayed()
        rule.onNodeWithText("Take photo").performClick()
        rule.onNodeWithText("Use the rear camera").performClick()
        assertEquals(1, shutter)
        assertEquals(1, switched)
    }

    @Test
    fun theReviewOffersRetakeAndUse() {
        var used = 0
        var retaken = 0
        rule.setContent {
            PhotoCaptureContent(
                PhotoCaptureUiState(access = CameraAccess.GRANTED, step = CaptureStep.REVIEW, shot = shot()),
                PhotoCaptureActions(onUse = { used++ }, onRetake = { retaken++ }),
            ) { FakeCameraPreview(it) }
        }
        rule.onNodeWithTag("photo_capture_review").assertIsDisplayed()
        rule.onNodeWithText("Use photo").performClick()
        rule.onNodeWithText("Retake").performClick()
        assertEquals(1, used)
        assertEquals(1, retaken)
    }

    @Test
    fun theReviewZoomsWithTheButtonsAndResets() {
        val m = granted()
        m.shutter()
        m.shot(shot(2048, 1536))
        rule.setContent {
            val s by m.uiState.collectAsState()
            PhotoCaptureContent(s, PhotoCaptureActions(onFrame = m::frame, onResetFraming = m::resetFraming)) {
                FakeCameraPreview(it)
            }
        }
        rule.onNodeWithTag("photo_crop_reset").assertIsNotEnabled()
        rule.onNodeWithTag("photo_crop_zoom_out").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Zoom in").performScrollTo().performClick()
        assertEquals(PhotoCrop.ZOOM_STEP, m.uiState.value.framing.zoom, 1e-3f)
        rule.onNodeWithTag("photo_capture_review").assertContentDescriptionContains("round frame", substring = true)
        rule.onNodeWithTag("photo_crop_reset").assertIsEnabled().performScrollTo().performClick()
        assertEquals(Framing(), m.uiState.value.framing)
        // TalkBack can move the shot without a drag.
        val actions = rule.onNodeWithTag("photo_capture_review").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        rule.runOnIdle { actions.first { it.label == "Show more to the left" }.action() }
        assertTrue(m.uiState.value.framing.centreX < 0.5f)
    }

    @Test
    fun aPinchOnTheFrameZoomsIn() {
        val m = granted()
        m.shutter()
        m.shot(shot(2048, 1536))
        rule.setContent {
            val s by m.uiState.collectAsState()
            PhotoCaptureContent(s, PhotoCaptureActions(onFrame = m::frame)) { FakeCameraPreview(it) }
        }
        rule.onNodeWithTag("photo_capture_review").performTouchInput {
            pinch(center - Offset(20f, 0f), center - Offset(150f, 0f), center + Offset(20f, 0f), center + Offset(150f, 0f))
        }
        assertTrue("${m.uiState.value.framing}", m.uiState.value.framing.zoom > 1.5f)
    }

    @Test
    fun aRefusalIsExplainedAndABlockedCameraOpensTheSettings() {
        var allow = 0
        var settings = 0
        val state = androidx.compose.runtime.mutableStateOf(PhotoCaptureUiState(access = CameraAccess.DENIED))
        rule.setContent {
            PhotoCaptureContent(state.value, PhotoCaptureActions(onAllow = { allow++ }, onOpenSettings = { settings++ })) {
                FakeCameraPreview(it)
            }
        }
        rule.onNodeWithTag("photo_capture_permission").assertIsDisplayed()
        rule.onNodeWithText("Allow camera").performClick()
        assertEquals(1, allow)
        state.value = PhotoCaptureUiState(access = CameraAccess.BLOCKED)
        rule.onNodeWithTag("photo_capture_blocked").assertIsDisplayed()
        rule.onNodeWithText("Open settings").performClick()
        assertEquals(1, settings)
    }

    @Test
    fun aPhoneWithoutACameraIsTold() {
        rule.setContent {
            PhotoCaptureContent(PhotoCaptureUiState(access = CameraAccess.GRANTED, noCamera = true), PhotoCaptureActions()) {
                FakeCameraPreview(it)
            }
        }
        rule.onNodeWithTag("photo_capture_no_camera").assertIsDisplayed()
        rule.onNodeWithTag("photo_capture_live").assertDoesNotExist()
    }

    @Test
    fun theSharedProfileOffersToTakeOrRetakeAPhotoAndToRemoveIt() {
        val account = AccountInfo("a***@example.org", state = "member", firstName = "Ada", lastName = "Lovelace")
        val profile = OwnProfile(2, "", "Ada", "Lovelace", "9a1f")
        val state = androidx.compose.runtime.mutableStateOf(SharedProfileUiState(account, profile))
        var take = 0
        rule.setContent { SharedProfileContent(state.value, SharedProfileActions(onTakePhoto = { take++ })) }
        rule.onNodeWithTag("photo_take").assertTextContains("Take a photo")
        rule.onNodeWithTag("photo_remove").assertDoesNotExist()
        rule.onNodeWithTag("photo_take").performScrollTo().performClick()
        assertEquals(1, take)
        state.value = SharedProfileUiState(account, profile.copy(photo = "/9j/4AAQ"))
        rule.onNodeWithTag("photo_take").assertTextContains("Retake photo")
        rule.onNodeWithTag("photo_remove").assertExists()
    }

    @Test
    fun theSharedProfileGlyphIsTheIdCard() {
        assertEquals("Outlined.Badge", SharedProfileIcon.name)
    }
}
