package com.vettid.feature.settings

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import com.vettid.core.ui.components.CameraAccess
import com.vettid.core.ui.components.CameraLens
import com.vettid.core.ui.components.ProfilePhotos
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** Where the profile-photo capture is (owner feedback 2026-10-08: the photo is taken in the app, never chosen). */
enum class CaptureStep {
    /** The live camera, ready for the shutter. */
    LIVE,

    /** The shutter was pressed; the picture is on its way. */
    TAKING,

    /** The shot is shown with "Retake" and "Use photo". */
    REVIEW,
}

/** Immutable state of the capture screen. [shot] lives in memory only: it is never written to storage. */
data class PhotoCaptureUiState(
    val access: CameraAccess = CameraAccess.ASK,
    /** The phone has no camera the app can open. */
    val noCamera: Boolean = false,
    val lens: CameraLens = CameraLens.FRONT,
    /** The cameras the phone has (both assumed until the camera reports). */
    val front: Boolean = true,
    val back: Boolean = true,
    val step: CaptureStep = CaptureStep.LIVE,
    val shot: Bitmap? = null,
    /** The last shutter press brought no picture. */
    val failed: Boolean = false,
) {
    /** The camera may be shown. */
    val live: Boolean get() = access == CameraAccess.GRANTED && !noCamera

    val canShoot: Boolean get() = live && step == CaptureStep.LIVE

    val canSwitch: Boolean get() = canShoot && front && back
}

/**
 * The capture's state machine: the permission, the cameras, the shutter, the shot under review. Pure state; the
 * screen drives the camera. A shot that is retaken or dropped is recycled; [use] hands it over.
 */
class PhotoCaptureMachine {
    private val state = MutableStateFlow(PhotoCaptureUiState())
    val uiState: StateFlow<PhotoCaptureUiState> = state.asStateFlow()

    fun access(a: CameraAccess) = state.update { it.copy(access = a) }

    /** The cameras the phone has; a missing lens is swapped for the other, none at all is [noCamera]. */
    fun lenses(front: Boolean, back: Boolean) = state.update {
        when {
            !front && !back -> it.copy(front = false, back = false, noCamera = true)
            it.lens == CameraLens.FRONT && !front -> it.copy(front = false, back = true, lens = CameraLens.BACK)
            it.lens == CameraLens.BACK && !back -> it.copy(front = true, back = false, lens = CameraLens.FRONT)
            else -> it.copy(front = front, back = back)
        }
    }

    /** No camera can be opened (none on the phone, or the camera failed to start). */
    fun unavailable() = state.update { it.copy(noCamera = true) }

    fun switchLens() = state.update {
        if (!it.canSwitch) it else it.copy(lens = if (it.lens == CameraLens.FRONT) CameraLens.BACK else CameraLens.FRONT, failed = false)
    }

    /** The shutter: true when a picture should be taken now. */
    fun shutter(): Boolean {
        if (!state.value.canShoot) return false
        state.update { it.copy(step = CaptureStep.TAKING, failed = false) }
        return true
    }

    /** The picture the camera returned (null: none). Ignored unless a picture was asked for. */
    fun shot(b: Bitmap?) {
        if (state.value.step != CaptureStep.TAKING) {
            b?.recycle()
            return
        }
        state.update {
            if (b == null) it.copy(step = CaptureStep.LIVE, failed = true) else it.copy(step = CaptureStep.REVIEW, shot = b)
        }
    }

    /** Back to the live camera; the shot is dropped. */
    fun retake() {
        val old = state.value.shot
        state.update { it.copy(step = CaptureStep.LIVE, shot = null, failed = false) }
        old?.recycle()
    }

    /** The shot under review, handed over to the caller (who recycles it); null when there is none. */
    fun use(): Bitmap? {
        val s = state.value
        if (s.step != CaptureStep.REVIEW) return null
        state.update { it.copy(step = CaptureStep.LIVE, shot = null) }
        return s.shot
    }

    /** A fresh capture: the front camera, no shot (the permission is kept). */
    fun reset() {
        val old = state.value.shot
        state.update { PhotoCaptureUiState(access = it.access) }
        old?.recycle()
    }
}

/** Holds the [PhotoCaptureMachine] for the shared-profile screen; the shot is dropped with it. */
@HiltViewModel
class PhotoCaptureViewModel @Inject constructor() : ViewModel() {
    val machine = PhotoCaptureMachine()

    override fun onCleared() = machine.reset()
}

/**
 * The shot as `profile.set{photo}` takes it (VAULT-MESSAGING §10.8): [ProfilePhotos.encodeBase64] crops it to a
 * centred square, scales it down and re-encodes it as a JPEG of at most 65,536 bytes; re-encoding the pixels writes
 * no EXIF or location. Recycles [shot]. Null when it cannot be encoded. Blocking: call off the main thread.
 */
internal fun encodeShot(shot: Bitmap): String? = try {
    ProfilePhotos.encodeBase64(shot)
} catch (_: IllegalStateException) {
    null
} catch (_: OutOfMemoryError) {
    null
} finally {
    shot.recycle()
}
