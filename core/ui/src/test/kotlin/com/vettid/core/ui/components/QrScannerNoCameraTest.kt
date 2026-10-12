package com.vettid.core.ui.components

import androidx.camera.core.CameraSelector
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A6 audit G1: the QR scanner on a device without a back camera (a tablet or Chromebook with a front camera only,
 * Play's pre-launch robot) or without any camera. It used to throw in a main-thread listener, on the first screen
 * after "Start".
 */
@RunWith(RobolectricTestRunner::class)
class QrScannerNoCameraTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theBackCameraIsPreferredThenTheFrontOneThenNone() {
        assertEquals(CameraSelector.DEFAULT_BACK_CAMERA, scanCamera(back = true, front = true))
        assertEquals(CameraSelector.DEFAULT_BACK_CAMERA, scanCamera(back = true, front = false))
        assertEquals(CameraSelector.DEFAULT_FRONT_CAMERA, scanCamera(back = false, front = true))
        assertNull(scanCamera(back = false, front = false))
    }

    /** Robolectric's device has no camera at all: the scanner says so in place of the preview and tells its caller. */
    @Test
    fun withoutAnyCameraTheScannerSaysToTypeTheCodeAndDoesNotCrash() {
        var told = 0
        rule.setContent { QrScanner(onText = {}, onUnavailable = { told++ }) }
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithTag("scanner_no_camera").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("scanner_no_camera")
            .assertTextEquals("This device has no camera that VettID can use. Type or paste the code instead.")
        assertTrue(told >= 1)
    }

    private companion object {
        const val WAIT_MS = 30_000L
    }
}
