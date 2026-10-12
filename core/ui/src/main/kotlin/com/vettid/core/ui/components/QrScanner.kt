package com.vettid.core.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vettid.core.ui.R
import com.vettid.core.ui.theme.Spacing
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** Whether the app may use the camera, and a way to ask. */
class CameraPermission(val granted: Boolean, val request: () -> Unit)

@Composable
fun rememberCameraPermission(): CameraPermission {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    return CameraPermission(granted) { launcher.launch(Manifest.permission.CAMERA) }
}

/**
 * The camera preview with a QR decoder (ported in spirit from the v1 app's
 * scanner, with ZXing in place of ML Kit so that no Google Play services are
 * needed). Every decoded text goes to [onText]; the caller decides whether it
 * is an invitation. Frames are analysed in memory only and never stored.
 *
 * A phone without a camera this can use never crashes the screen (A6 audit G1: a tablet or Chromebook with a front
 * camera only, a camera that fails to start): the back camera is used, else the front one, else a note in place of
 * the preview says to type or paste the code, which every scan screen offers next to the scanner. [onUnavailable]
 * tells the caller.
 */
@Composable
fun QrScanner(onText: (String) -> Unit, modifier: Modifier = Modifier, onUnavailable: () -> Unit = {}) {
    val context = LocalContext.current
    var unavailable by remember { mutableStateOf(false) }
    val gone by rememberUpdatedState {
        unavailable = true
        onUnavailable()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onText)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val decoder = remember { QrFrameDecoder() }
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // A TextureView, so that Compose's clip and layout bounds apply to the preview.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    LaunchedEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = try {
                providerFuture.get()
            } catch (_: ExecutionException) {
                gone()
                return@addListener
            }
            val selector = scanCamera(
                back = runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false),
                front = runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false),
            )
            if (selector == null) {
                gone()
                return@addListener
            }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { a -> a.setAnalyzer(executor) { img -> decode(decoder, img)?.let { t -> previewView.post { callback(t) } } } }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            } catch (_: IllegalArgumentException) {
                gone() // no camera for the selector after all, or a use case it cannot serve
            } catch (_: IllegalStateException) {
                gone()
            }
        }, ContextCompat.getMainExecutor(context))
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }
    if (unavailable) {
        Box(modifier.padding(Spacing.l), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.core_ui_scanner_no_camera),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                // Appears by itself where the preview would be: spoken.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("scanner_no_camera"),
            )
        }
    } else {
        AndroidView(factory = { previewView }, modifier = modifier)
    }
}

/** The camera a QR code is scanned with: the back one, else the front one (a tablet, a Chromebook), else none. */
internal fun scanCamera(back: Boolean, front: Boolean): CameraSelector? = when {
    back -> CameraSelector.DEFAULT_BACK_CAMERA
    front -> CameraSelector.DEFAULT_FRONT_CAMERA
    else -> null
}

/** Decodes the luminance plane of a frame; null when it holds no QR code. */
private fun decode(decoder: QrFrameDecoder, image: ImageProxy): String? = image.use { img ->
    val plane = img.planes.firstOrNull() ?: return null
    val buf = plane.buffer
    val data = ByteArray(buf.remaining()).also { buf.get(it) }
    decoder.decode(data, plane.rowStride, img.width, img.height)
}
