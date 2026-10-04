package com.vettid.feature.connections

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
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
 */
@Composable
fun QrScanner(onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onText)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember { QRCodeReader() }
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
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { a -> a.setAnalyzer(executor) { img -> decode(reader, img)?.let { t -> previewView.post { callback(t) } } } }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

private val HINTS = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    DecodeHintType.TRY_HARDER to true,
    DecodeHintType.CHARACTER_SET to "UTF-8",
)

/** Decodes the luminance plane of a frame; null when it holds no QR code. */
private fun decode(reader: QRCodeReader, image: ImageProxy): String? = image.use { img ->
    val plane = img.planes.firstOrNull() ?: return null
    val buf = plane.buffer
    val data = ByteArray(buf.remaining()).also { buf.get(it) }
    val source = PlanarYUVLuminanceSource(data, plane.rowStride, img.height, 0, 0, img.width, img.height, false)
    try {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), HINTS).text
    } catch (_: NotFoundException) {
        null
    } catch (_: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}
