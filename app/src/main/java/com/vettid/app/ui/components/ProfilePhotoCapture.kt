package com.vettid.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

private const val TAG = "ProfilePhotoCapture"

// Photo constraints
private const val MAX_DIMENSION = 512
private const val MAX_FILE_SIZE_BYTES = 150 * 1024 // 150KB
private const val INITIAL_QUALITY = 80
private const val MIN_QUALITY = 50

/**
 * Profile photo capture composable using CameraX.
 *
 * Features:
 * - Front camera by default (selfie mode)
 * - Capture button with preview
 * - Retake / Use Photo buttons after capture
 * - Automatic compression to 512x512 JPEG under 150KB
 *
 * @param onPhotoCapture Callback with compressed JPEG bytes when user confirms the photo
 * @param onCancel Callback when user cancels
 * @param modifier Modifier for the container
 */
@Composable
fun ProfilePhotoCapture(
    onPhotoCapture: (ByteArray) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var capturedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            errorMessage = "Camera permission is required"
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top bar
            TopBar(
                onClose = onCancel,
                title = if (capturedBitmap != null) "Preview" else "Take Photo"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    !hasCameraPermission -> {
                        PermissionRequestView(
                            onRequestPermission = {
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        )
                    }
                    capturedBitmap != null -> {
                        PhotoPreview(
                            bitmap = capturedBitmap!!,
                            isProcessing = isProcessing,
                            onRetake = {
                                capturedBitmap = null
                                capturedBytes = null
                            },
                            onUse = {
                                capturedBytes?.let { bytes ->
                                    onPhotoCapture(bytes)
                                }
                            }
                        )
                    }
                    else -> {
                        CameraView(
                            onPhotoCaptured = { bitmap, bytes ->
                                capturedBitmap = bitmap
                                capturedBytes = bytes
                            },
                            onError = { error ->
                                errorMessage = error
                            },
                            onProcessingChange = { processing ->
                                isProcessing = processing
                            }
                        )
                    }
                }
            }

            // Error message
            errorMessage?.let { error ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    onClose: () -> Unit,
    title: String
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
            }
        },
        title = { Text(title) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun CameraView(
    onPhotoCaptured: (Bitmap, ByteArray) -> Unit,
    onError: (String) -> Unit,
    onProcessingChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { previewView ->
                cameraProviderFuture.addListener({
                    try {
                        val cameraProvider = cameraProviderFuture.get()

                        val preview = Preview.Builder()
                            .build()
                            .also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()
                        imageCapture = capture

                        // Use front camera for selfie
                        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            capture
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera initialization failed", e)
                        onError("Failed to initialize camera: ${e.message}")
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        )

        // Circular frame overlay
        CameraOverlay()

        // Capture button at bottom
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            FloatingActionButton(
                onClick = {
                    imageCapture?.let { capture ->
                        onProcessingChange(true)
                        capture.takePicture(
                            executor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    try {
                                        val result = processAndCompressPhoto(image)
                                        image.close()

                                        if (result != null) {
                                            onPhotoCaptured(result.first, result.second)
                                        } else {
                                            onError("Failed to process photo")
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error processing photo", e)
                                        onError("Failed to process photo: ${e.message}")
                                    } finally {
                                        onProcessingChange(false)
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    Log.e(TAG, "Photo capture failed", exception)
                                    onError("Capture failed: ${exception.message}")
                                    onProcessingChange(false)
                                }
                            }
                        )
                    }
                },
                modifier = Modifier.size(72.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                shape = CircleShape
            ) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = "Take Photo",
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
private fun CameraOverlay() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Semi-transparent background with circular cutout hint
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
        )

        // Circular guide
        Box(
            modifier = Modifier
                .size(280.dp)
                .clip(CircleShape)
                .background(Color.Transparent)
        ) {
            // Border ring
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                color = Color.Transparent,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {}
        }

        // Instructions
        Text(
            text = "Position your face in the circle",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
        )
    }
}

@Composable
private fun PhotoPreview(
    bitmap: Bitmap,
    isProcessing: Boolean,
    onRetake: () -> Unit,
    onUse: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Preview image
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Captured photo preview",
                modifier = Modifier
                    .size(280.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )

            if (isProcessing) {
                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }

        // Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = onRetake,
                modifier = Modifier.weight(1f),
                enabled = !isProcessing
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retake")
            }
            Button(
                onClick = onUse,
                modifier = Modifier.weight(1f),
                enabled = !isProcessing
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save to Profile")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun PermissionRequestView(
    onRequestPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Camera Permission Required",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "To take a profile photo, please grant camera access.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onRequestPermission) {
            Text("Grant Permission")
        }
    }
}

/**
 * Process and compress a captured photo to meet constraints:
 * - Crop to square (center)
 * - Scale to 512x512
 * - Compress as JPEG starting at 80% quality
 * - Reduce quality iteratively if > 150KB
 *
 * @return Pair of (display bitmap, compressed JPEG bytes) or null on error
 */
private fun processAndCompressPhoto(imageProxy: ImageProxy): Pair<Bitmap, ByteArray>? {
    return try {
        // Convert ImageProxy to Bitmap
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val originalBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return null

        // Apply rotation based on image rotation
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val matrix = Matrix()

        // For front camera selfie: rotate by camera degrees, then mirror horizontally
        // The rotation corrects the camera sensor orientation
        // The horizontal mirror makes it look like a selfie (not reversed)
        matrix.postRotate(rotationDegrees.toFloat())
        matrix.postScale(-1f, 1f)  // Mirror horizontally only for selfie effect

        val rotatedBitmap = Bitmap.createBitmap(
            originalBitmap,
            0, 0,
            originalBitmap.width,
            originalBitmap.height,
            matrix,
            true
        )

        // Crop to square (center crop)
        val size = minOf(rotatedBitmap.width, rotatedBitmap.height)
        val xOffset = (rotatedBitmap.width - size) / 2
        val yOffset = (rotatedBitmap.height - size) / 2

        val croppedBitmap = Bitmap.createBitmap(
            rotatedBitmap,
            xOffset, yOffset,
            size, size
        )

        // Scale to target dimension
        val scaledBitmap = Bitmap.createScaledBitmap(
            croppedBitmap,
            MAX_DIMENSION,
            MAX_DIMENSION,
            true
        )

        // Compress with iteratively decreasing quality until under size limit
        var quality = INITIAL_QUALITY
        var compressedBytes: ByteArray

        do {
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            compressedBytes = outputStream.toByteArray()

            Log.d(TAG, "Compressed at quality $quality: ${compressedBytes.size} bytes")

            if (compressedBytes.size <= MAX_FILE_SIZE_BYTES) {
                break
            }

            quality -= 5
        } while (quality >= MIN_QUALITY)

        if (compressedBytes.size > MAX_FILE_SIZE_BYTES) {
            Log.w(TAG, "Could not compress to under ${MAX_FILE_SIZE_BYTES} bytes, got ${compressedBytes.size}")
        }

        Log.i(TAG, "Photo processed: ${scaledBitmap.width}x${scaledBitmap.height}, ${compressedBytes.size} bytes, quality=$quality")

        // Cleanup
        if (originalBitmap !== rotatedBitmap) originalBitmap.recycle()
        if (rotatedBitmap !== croppedBitmap) rotatedBitmap.recycle()
        if (croppedBitmap !== scaledBitmap) croppedBitmap.recycle()

        Pair(scaledBitmap, compressedBytes)
    } catch (e: Exception) {
        Log.e(TAG, "Error processing photo", e)
        null
    }
}
