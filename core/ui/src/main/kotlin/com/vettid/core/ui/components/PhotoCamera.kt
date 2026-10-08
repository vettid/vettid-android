package com.vettid.core.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import android.util.Size
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.min

/** Which camera the profile photo is taken with. */
enum class CameraLens { FRONT, BACK }

/** Whether the app may use the camera. */
enum class CameraAccess {
    /** Allowed. */
    GRANTED,

    /** Not asked yet (or the answer is not known): the app may ask. */
    ASK,

    /** Refused; the app may ask again. */
    DENIED,

    /** Refused for good ("Don't ask again"): only the system settings can allow it now. */
    BLOCKED,
}

/** The camera permission with the refusal told apart, a way to ask, and a way to the app's system settings. */
class CameraAccessState(val access: CameraAccess, val request: () -> Unit, val openSettings: () -> Unit)

/**
 * The camera permission for the profile photo. A refusal is [CameraAccess.DENIED] while the system would still show
 * its dialog, [CameraAccess.BLOCKED] once it would not; the permission is read again whenever the screen resumes (the
 * member may have allowed it in the system settings meanwhile).
 */
@Composable
fun rememberCameraAccess(): CameraAccessState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var access by rememberSaveable { mutableStateOf(if (granted()) CameraAccess.GRANTED else CameraAccess.ASK) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val activity = context.findActivity()
        access = when {
            ok -> CameraAccess.GRANTED
            activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA) ->
                CameraAccess.DENIED
            else -> CameraAccess.BLOCKED
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (granted()) {
                    access = CameraAccess.GRANTED
                } else if (access == CameraAccess.GRANTED) {
                    // Revoked in the system settings while the app was away.
                    access = CameraAccess.ASK
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return CameraAccessState(
        access,
        request = { launcher.launch(Manifest.permission.CAMERA) },
        openSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Whether the phone has any camera at all. */
fun hasAnyCamera(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/**
 * Takes a still from the camera [PhotoCamera] bound. The picture is captured into memory
 * ([ImageCapture.OnImageCapturedCallback]): no file is written, nothing reaches the gallery or shared storage.
 */
class PhotoCameraHandle internal constructor() {
    internal var capture: ImageCapture? = null
    internal var lens: CameraLens = CameraLens.FRONT
    private val executor = Executors.newSingleThreadExecutor()

    /** Takes one picture; [onShot] gets it upright (front shots mirrored, as previewed), or null, on the main thread. */
    fun take(context: Context, onShot: (Bitmap?) -> Unit) {
        val c = capture ?: return onShot(null)
        val mirror = lens == CameraLens.FRONT
        val main = ContextCompat.getMainExecutor(context)
        c.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val shot = image.use { upright(it, mirror) }
                    main.execute { onShot(shot) }
                }

                override fun onError(exception: ImageCaptureException) {
                    main.execute { onShot(null) }
                }
            },
        )
    }

    internal fun close() {
        capture = null
        executor.shutdown()
    }

    private companion object {

        fun upright(image: ImageProxy, mirror: Boolean): Bitmap? = try {
            val raw = image.toBitmap()
            uprightShot(raw, image.imageInfo.rotationDegrees, mirror).also { if (it !== raw) raw.recycle() }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
    }
}

/**
 * Turns a sensor-frame shot upright and, for the front camera, mirrors it as previewed. The rotation comes
 * first: a mirror applied in the sensor's frame becomes a top-bottom flip once a 90/270° rotation follows
 * (front shots came out upside down on a Pixel 9 Pro, 2026-10-08). Scales the shorter side down to
 * [keepSide] at most.
 */
internal fun uprightShot(raw: Bitmap, rotationDegrees: Int, mirror: Boolean, keepSide: Int = SHOT_SIDE): Bitmap {
    val scale = min(1f, keepSide.toFloat() / min(raw.width, raw.height))
    val m = Matrix().apply {
        postRotate(rotationDegrees.toFloat())
        postScale(if (mirror) -scale else scale, scale)
    }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
}

@Composable
fun rememberPhotoCameraHandle(): PhotoCameraHandle {
    val handle = remember { PhotoCameraHandle() }
    DisposableEffect(handle) { onDispose { handle.close() } }
    return handle
}

/**
 * The live camera for the profile photo (CameraX, as [QrScanner]): a preview and an [ImageCapture] on [lens].
 * [onLenses] reports which cameras the phone has; [onUnavailable] is called when no camera can be opened.
 */
@Composable
fun PhotoCamera(
    lens: CameraLens,
    handle: PhotoCameraHandle,
    onLenses: (front: Boolean, back: Boolean) -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lenses by rememberUpdatedState(onLenses)
    val unavailable by rememberUpdatedState(onUnavailable)
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    LaunchedEffect(lifecycleOwner, lens) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = try {
                providerFuture.get()
            } catch (_: ExecutionException) {
                unavailable()
                return@addListener
            }
            val front = runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
            val back = runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
            lenses(front, back)
            val selector = if (lens == CameraLens.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            if (!(if (lens == CameraLens.FRONT) front else back)) {
                if (!front && !back) unavailable()
                return@addListener
            }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(CAPTURE_WIDTH, CAPTURE_HEIGHT),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ),
                        )
                        .build(),
                )
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                handle.capture = capture
                handle.lens = lens
            } catch (_: IllegalArgumentException) {
                unavailable()
            } catch (_: IllegalStateException) {
                unavailable()
            }
        }, ContextCompat.getMainExecutor(context))
    }
    DisposableEffect(Unit) {
        onDispose {
            handle.capture = null
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * The shorter side a shot is kept at: three times [ProfilePhotos.TARGET_SIDE], so the review can zoom in up to 3x
 * before the crop would be scaled up (owner, 2026-10-08: "my head in my photo looks pretty small").
 */
internal const val SHOT_SIDE = 1_536

private const val CAPTURE_WIDTH = 2_048
private const val CAPTURE_HEIGHT = 1_536
