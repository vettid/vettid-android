package com.vettid.feature.settings

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
        assertSame(second, m.use())
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
        val b64 = encodeShot(big)
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
