package com.vettid.app.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.vettid.core.ui.components.CameraAccess
import com.vettid.core.ui.components.CameraLens
import com.vettid.core.ui.components.ProfilePhotos
import com.vettid.feature.settings.CaptureStep
import com.vettid.feature.settings.FakeCameraPreview
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
            val shot = remember { ProfilePhotos.decode(SAMPLE_PHOTO) }
            Capture(granted.copy(step = CaptureStep.REVIEW, shot = shot))
        },
        "settings.photo_capture_permission" to { Capture(PhotoCaptureUiState(access = CameraAccess.DENIED)) },
        "settings.photo_capture_blocked" to { Capture(PhotoCaptureUiState(access = CameraAccess.BLOCKED)) },
        "settings.photo_capture_no_camera" to { Capture(granted.copy(noCamera = true)) },
    )
}
