package com.vettid.app.debug

import androidx.compose.runtime.Composable
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.remember
import com.vettid.core.ui.components.CameraAccess
import com.vettid.core.ui.components.CameraLens
import com.vettid.feature.settings.CaptureStep
import com.vettid.feature.settings.FakeCameraPreview
import com.vettid.feature.settings.Framing
import com.vettid.feature.settings.PhotoCaptureActions
import com.vettid.feature.settings.PhotoCaptureContent
import com.vettid.feature.settings.PhotoCaptureUiState

/** The in-app camera for the profile photo (owner feedback 2026-10-08), with a fake preview in place of the camera. */
internal object PhotoCatalog {
    @Composable
    private fun Capture(state: PhotoCaptureUiState) = PhotoCaptureContent(state, PhotoCaptureActions()) { FakeCameraPreview(it) }

    private val granted = PhotoCaptureUiState(access = CameraAccess.GRANTED)

    val screens: Map<String, @Composable () -> Unit> = linkedMapOf(
        "settings.photo_capture" to { Capture(granted) },
        "settings.photo_capture_rear" to { Capture(granted.copy(lens = CameraLens.BACK)) },
        "settings.photo_capture_one_lens" to { Capture(granted.copy(back = false)) },
        "settings.photo_capture_failed" to { Capture(granted.copy(failed = true)) },
        "settings.photo_capture_review" to {
            val shot = remember { sampleShot() }
            Capture(granted.copy(step = CaptureStep.REVIEW, shot = shot))
        },
        // Zoomed in on the head and moved up a little (owner, 2026-10-08: "my head in my photo looks pretty small").
        "settings.photo_capture_review_zoomed" to {
            val shot = remember { sampleShot() }
            Capture(granted.copy(step = CaptureStep.REVIEW, shot = shot, framing = Framing(zoom = 2.5f, centreY = 0.42f)))
        },
        "settings.photo_capture_permission" to { Capture(PhotoCaptureUiState(access = CameraAccess.DENIED)) },
        "settings.photo_capture_blocked" to { Capture(PhotoCaptureUiState(access = CameraAccess.BLOCKED)) },
        "settings.photo_capture_no_camera" to { Capture(granted.copy(noCamera = true)) },
    )
}

/** A stand-in upright shot (portrait, as the camera takes it) with a small head and shoulders in the middle. */
@Suppress("MagicNumber")
private fun sampleShot(): Bitmap {
    val w = 1536
    val h = 2048
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    Canvas(b).apply {
        drawColor(0xFF5B7083.toInt())
        p.color = 0xFF2F3E4C.toInt()
        drawCircle(w / 2f, h * 0.78f, w * 0.32f, p) // shoulders
        p.color = 0xFFE0B48C.toInt()
        drawCircle(w / 2f, h * 0.42f, w * 0.13f, p) // head
        p.color = 0xFF3B2A1E.toInt()
        drawCircle(w / 2f - w * 0.045f, h * 0.41f, w * 0.012f, p)
        drawCircle(w / 2f + w * 0.045f, h * 0.41f, w * 0.012f, p)
    }
    return b
}
