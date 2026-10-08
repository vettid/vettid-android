package com.vettid.feature.settings

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vettid.core.ui.components.CameraAccess
import com.vettid.core.ui.components.CameraLens
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.PhotoCamera
import com.vettid.core.ui.components.hasAnyCamera
import com.vettid.core.ui.components.rememberCameraAccess
import com.vettid.core.ui.components.rememberPhotoCameraHandle
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape

/** What the capture screen can ask for. */
data class PhotoCaptureActions(
    val onClose: () -> Unit = {},
    val onAllow: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onSwitchLens: () -> Unit = {},
    val onShutter: () -> Unit = {},
    val onRetake: () -> Unit = {},
    val onUse: () -> Unit = {},
)

/**
 * The profile photo is taken here, in the app (owner feedback 2026-10-08): never chosen from the gallery, never taken
 * by another camera app (which could hand back any picture). The live camera in a square frame (the photo is cropped
 * to a square), the front camera first with a switch to the rear one; the shot is reviewed with "Retake" and
 * "Use photo". Stateless; [camera] draws the live preview (a fake one in the screen catalog).
 */
@Composable
fun PhotoCaptureContent(state: PhotoCaptureUiState, actions: PhotoCaptureActions, camera: @Composable (Modifier) -> Unit) {
    val review = state.step == CaptureStep.REVIEW && state.shot != null
    val (primary, onPrimary) = primaryAction(state, actions, review)
    val (secondary, onSecondary) = secondaryAction(state, actions, review)
    FormScaffold(
        title = stringResource(if (review) R.string.settings_photo_capture_review_title else R.string.settings_photo_capture_title),
        body = stringResource(if (review) R.string.settings_photo_capture_review_body else R.string.settings_photo_capture_body),
        primaryLabel = primary,
        onPrimary = onPrimary,
        primaryEnabled = review || !state.live || state.canShoot,
        busy = state.step == CaptureStep.TAKING,
        secondaryLabel = secondary,
        onSecondary = onSecondary,
        onBack = if (review) actions.onRetake else actions.onClose,
        modifier = Modifier.testTag("photo_capture"),
    ) {
        CaptureBody(state, review, camera)
        if (state.failed && !review) {
            Spacer(Modifier.height(Spacing.m))
            Text(
                stringResource(R.string.settings_photo_capture_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("photo_capture_failed"),
            )
        }
        Spacer(Modifier.height(Spacing.m))
        Text(
            stringResource(R.string.settings_photo_capture_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The primary button: use the shot, open the settings, allow the camera, or the shutter (none without a camera). */
@Composable
private fun primaryAction(state: PhotoCaptureUiState, actions: PhotoCaptureActions, review: Boolean): Pair<String?, () -> Unit> =
    when {
        review -> stringResource(R.string.settings_photo_capture_use) to actions.onUse
        state.noCamera -> null to actions.onClose
        state.access == CameraAccess.BLOCKED -> stringResource(R.string.settings_photo_capture_open_settings) to actions.onOpenSettings
        state.access != CameraAccess.GRANTED -> stringResource(R.string.settings_photo_capture_allow) to actions.onAllow
        else -> stringResource(R.string.settings_photo_capture_shutter) to actions.onShutter
    }

/** The secondary button: retake the shot, or switch to the other camera when the phone has both. */
@Composable
private fun secondaryAction(state: PhotoCaptureUiState, actions: PhotoCaptureActions, review: Boolean): Pair<String?, () -> Unit> =
    when {
        review -> stringResource(R.string.settings_photo_capture_retake) to actions.onRetake
        state.canSwitch -> stringResource(
            if (state.lens == CameraLens.FRONT) R.string.settings_photo_capture_to_back else R.string.settings_photo_capture_to_front,
        ) to actions.onSwitchLens
        else -> null to {}
    }

/** The shot under review, the live camera, or why there is no camera. */
@Composable
private fun CaptureBody(state: PhotoCaptureUiState, review: Boolean, camera: @Composable (Modifier) -> Unit) {
    when {
        review -> Frame(Modifier.testTag("photo_capture_review")) {
            Image(
                checkNotNull(state.shot).asImageBitmap(),
                contentDescription = stringResource(R.string.settings_photo_capture_review_title),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        state.noCamera -> NoticeCard(
            NoticeKind.WARNING,
            stringResource(R.string.settings_photo_capture_no_camera_title),
            stringResource(R.string.settings_photo_capture_no_camera_body),
            modifier = Modifier.testTag("photo_capture_no_camera"),
        )
        state.access == CameraAccess.BLOCKED -> NoticeCard(
            NoticeKind.INFO,
            stringResource(R.string.settings_photo_capture_permission_title),
            stringResource(R.string.settings_photo_capture_permission_blocked),
            modifier = Modifier.testTag("photo_capture_blocked"),
        )
        state.access != CameraAccess.GRANTED -> NoticeCard(
            NoticeKind.INFO,
            stringResource(R.string.settings_photo_capture_permission_title),
            stringResource(R.string.settings_photo_capture_permission_body),
            modifier = Modifier.testTag("photo_capture_permission"),
        )
        else -> Frame(Modifier.testTag("photo_capture_live")) {
            camera(Modifier.fillMaxSize())
            if (state.step == CaptureStep.TAKING) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

/** The square, gold-framed area the camera and the shot are shown in (the photo is a centred square). */
@Composable
private fun Frame(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .border(2.dp, MaterialTheme.colorScheme.primary, VettIdShape.card)
            .padding(2.dp)
            .clip(VettIdShape.card),
        content = content,
    )
}

/** A stand-in for the live camera (screen catalog, tests): a face on the surface variant colour. */
@Composable
fun FakeCameraPreview(modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Outlined.Face,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(FAKE_FACE),
        )
    }
}

private val FAKE_FACE = 160.dp

/**
 * The capture screen with the real camera: asks for the camera permission on first show, explains a refusal (and
 * offers the system settings once the system would no longer ask), reports a phone without a camera, and hands the
 * accepted shot to [onUse] (which owns it from then on). Back from the review retakes; back from the camera closes.
 * After process death the screen comes back to the live camera: the shot was never stored.
 */
@Composable
fun PhotoCaptureScreen(machine: PhotoCaptureMachine, onClose: () -> Unit, onUse: (Bitmap) -> Unit) {
    val state by machine.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val access = rememberCameraAccess()
    val handle = rememberPhotoCameraHandle()
    LaunchedEffect(access.access) { machine.access(access.access) }
    LaunchedEffect(Unit) {
        if (!hasAnyCamera(context)) {
            machine.unavailable()
        } else if (access.access == CameraAccess.ASK) {
            access.request()
        }
    }
    BackHandler { if (state.step == CaptureStep.REVIEW) machine.retake() else onClose() }
    PhotoCaptureContent(
        state,
        PhotoCaptureActions(
            onClose = onClose,
            onAllow = access.request,
            onOpenSettings = access.openSettings,
            onSwitchLens = machine::switchLens,
            onShutter = { if (machine.shutter()) handle.take(context, machine::shot) },
            onRetake = machine::retake,
            onUse = { machine.use()?.let(onUse) },
        ),
        camera = { m -> PhotoCamera(state.lens, handle, machine::lenses, machine::unavailable, m) },
    )
}
