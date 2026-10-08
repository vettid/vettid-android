package com.vettid.feature.settings

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import kotlin.math.roundToInt

/** What the capture screen can ask for. */
data class PhotoCaptureActions(
    val onClose: () -> Unit = {},
    val onAllow: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onSwitchLens: () -> Unit = {},
    val onShutter: () -> Unit = {},
    val onRetake: () -> Unit = {},
    val onUse: () -> Unit = {},
    /** The shot under review zoomed or moved (see [PhotoCaptureMachine.frame]). */
    val onFrame: ((Framing, Int, Int) -> Framing) -> Unit = {},
    /** "Reset": the default framing. */
    val onResetFraming: () -> Unit = {},
)

/**
 * The profile photo is taken here, in the app (owner feedback 2026-10-08): never chosen from the gallery, never taken
 * by another camera app (which could hand back any picture). The live camera in a square frame (the photo is cropped
 * to a square), the front camera first with a switch to the rear one; the shot is reviewed in a round frame (as the
 * avatar is shown) where it is zoomed and moved (owner, 2026-10-08), with "Reset", "Retake" and "Use photo".
 * Stateless; [camera] draws the live preview (a fake one in the screen catalog).
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
        if (review) {
            CropReview(state, checkNotNull(state.shot), actions)
        } else {
            CaptureBody(state, camera)
        }
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

/** The live camera, or why there is no camera. */
@Composable
private fun CaptureBody(state: PhotoCaptureUiState, camera: @Composable (Modifier) -> Unit) {
    when {
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

/** The square, gold-framed area the live camera is shown in (the photo is a square). */
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

/**
 * The shot under review in the round frame: pinch to zoom, drag to move, double-tap to zoom in (or back out); the
 * zoom buttons and slider do the same without a pinch, and TalkBack offers "Show more to the left" (and the other
 * directions) as actions on the frame. Outside the circle the shot is dimmed: only what is inside is kept.
 */
@Composable
private fun ColumnScope.CropReview(state: PhotoCaptureUiState, shot: Bitmap, actions: PhotoCaptureActions) {
    val image = remember(shot) { shot.asImageBitmap() }
    val onFrame by rememberUpdatedState(actions.onFrame)
    val framing = PhotoCrop.clamp(state.framing, shot.width, shot.height)
    val dim = MaterialTheme.colorScheme.scrim.copy(alpha = DIM_ALPHA)
    val ring = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.settings_photo_crop_frame)
    val zoomState = stringResource(R.string.settings_photo_crop_zoom_state, "%.1f".format(framing.zoom))
    val moves = nudgeActions(framing, shot, onFrame)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RectangleShape)
            .pointerInput(shot) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val side = size.width.toFloat()
                    onFrame { f, w, h -> PhotoCrop.gesture(f, w, h, side, centroid.x, centroid.y, pan.x, pan.y, zoom) }
                }
            }
            .pointerInput(shot) {
                detectTapGestures(onDoubleTap = { p ->
                    val side = size.width.toFloat()
                    onFrame { f, w, h -> PhotoCrop.doubleTap(f, w, h, side, p.x, p.y) }
                })
            }
            .semantics {
                contentDescription = description
                stateDescription = zoomState
                customActions = moves
            }
            .testTag("photo_capture_review"),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val scale = PhotoCrop.displayScale(framing, shot.width, shot.height, size.width)
            val left = size.width / 2f - framing.centreX * shot.width * scale
            val top = size.height / 2f - framing.centreY * shot.height * scale
            drawImage(
                image,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize((shot.width * scale).roundToInt(), (shot.height * scale).roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
            val circle = Path().apply { addOval(Rect(Offset.Zero, size)) }
            clipPath(circle, ClipOp.Difference) { drawRect(dim) }
            val stroke = RING.toPx()
            drawCircle(ring, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
        }
    }
    Spacer(Modifier.height(Spacing.s))
    ZoomControls(state.maxZoom, framing, onFrame)
    TextButton(
        onClick = actions.onResetFraming,
        enabled = !framing.isDefault,
        modifier = Modifier.align(Alignment.CenterHorizontally).testTag("photo_crop_reset"),
    ) {
        Text(stringResource(R.string.settings_photo_crop_reset))
    }
}

/** Zoom out, the zoom slider, zoom in: the pinch without a pinch. Disabled when the shot cannot be zoomed. */
@Composable
private fun ZoomControls(maxZoom: Float, framing: Framing, onFrame: ((Framing, Int, Int) -> Framing) -> Unit) {
    val zoomable = maxZoom > 1f + ZOOM_EPSILON
    val zoomLabel = stringResource(R.string.settings_photo_crop_zoom)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onFrame { f, w, h -> PhotoCrop.step(f, w, h, zoomIn = false) } },
            enabled = zoomable && framing.zoom > 1f + ZOOM_EPSILON,
            modifier = Modifier.testTag("photo_crop_zoom_out"),
        ) {
            Icon(Icons.Outlined.ZoomOut, contentDescription = stringResource(R.string.settings_photo_crop_zoom_out))
        }
        Slider(
            value = framing.zoom,
            onValueChange = { z -> onFrame { f, w, h -> PhotoCrop.zoomTo(f, w, h, z) } },
            valueRange = 1f..maxOf(maxZoom, 1f + ZOOM_EPSILON),
            enabled = zoomable,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = zoomLabel }
                .testTag("photo_crop_zoom"),
        )
        IconButton(
            onClick = { onFrame { f, w, h -> PhotoCrop.step(f, w, h, zoomIn = true) } },
            enabled = zoomable && framing.zoom < maxZoom - ZOOM_EPSILON,
            modifier = Modifier.testTag("photo_crop_zoom_in"),
        ) {
            Icon(Icons.Outlined.ZoomIn, contentDescription = stringResource(R.string.settings_photo_crop_zoom_in))
        }
    }
}

/** TalkBack's way to move the shot: one action per direction it can still move in. */
@Composable
private fun nudgeActions(
    framing: Framing,
    shot: Bitmap,
    onFrame: ((Framing, Int, Int) -> Framing) -> Unit,
): List<CustomAccessibilityAction> {
    val directions = listOf(
        Triple(R.string.settings_photo_crop_show_left, -1, 0),
        Triple(R.string.settings_photo_crop_show_right, 1, 0),
        Triple(R.string.settings_photo_crop_show_up, 0, -1),
        Triple(R.string.settings_photo_crop_show_down, 0, 1),
    )
    return directions.mapNotNull { (label, dx, dy) ->
        val text = stringResource(label)
        if (!PhotoCrop.canNudge(framing, shot.width, shot.height, dx, dy)) {
            null
        } else {
            CustomAccessibilityAction(text) {
                onFrame { f, w, h -> PhotoCrop.nudge(f, w, h, dx, dy) }
                true
            }
        }
    }
}

private const val DIM_ALPHA = 0.6f
private const val ZOOM_EPSILON = 0.01f
private val RING = 2.dp

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
 * accepted shot and the square under the round frame to [onUse] (which owns the shot from then on). The framing is
 * kept in the [machine] (normalised), so a rotation keeps it. Back from the review retakes; back from the camera closes.
 * After process death the screen comes back to the live camera: the shot was never stored.
 */
@Composable
fun PhotoCaptureScreen(machine: PhotoCaptureMachine, onClose: () -> Unit, onUse: (PhotoSelection) -> Unit) {
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
            onFrame = machine::frame,
            onResetFraming = machine::resetFraming,
        ),
        camera = { m -> PhotoCamera(state.lens, handle, machine::lenses, machine::unavailable, m) },
    )
}
