package com.vettid.feature.messages

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vettid.core.data.social.MessageInfo
import com.vettid.core.data.vault.messageRes
import com.vettid.core.media.ImageType
import com.vettid.core.media.PhotoLimits
import com.vettid.core.media.Photos
import com.vettid.core.media.SharedPhotos
import com.vettid.core.ui.components.CameraAccess
import com.vettid.core.ui.components.CameraLens
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.PhotoCamera
import com.vettid.core.ui.components.SecureWindow
import com.vettid.core.ui.components.rememberCameraAccess
import com.vettid.core.ui.components.rememberPhotoCameraHandle
import com.vettid.core.ui.format.failureText
import com.vettid.core.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** What the photo parts of a conversation can ask for. */
data class PhotoActions(
    val onTakePhoto: () -> Unit = {},
    /** One reader per picked picture, read (into memory) and prepared one at a time. */
    val onPicked: (List<suspend () -> ByteArray?>) -> Unit = {},
    val onShot: (Bitmap) -> Unit = {},
    val onCloseCamera: () -> Unit = {},
    val onCaption: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onDismissProblem: () -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val thumbnail: suspend (String) -> ByteArray? = { null },
    /** Changes at every clear of the attachment memory (the thumbnails are decoded again, or not at all). */
    val generation: Long = 0,
)

/**
 * The composer's photo button (ANDROID-PLAN 0.1.39 "Choosing a photo"): "Take a photo" (the app's own camera) or
 * "Choose photos" (the Photo Picker, at most 10, images only, no permission: only what the member picks reaches
 * the app; where the system has no picker, the contract falls back to the document picker, which needs none either).
 * The picked files are read into memory only.
 */
@Composable
internal fun PhotoButton(actions: PhotoActions, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PhotoDraft.MAX_PHOTOS)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        actions.onPicked(
            uris.map { uri ->
                suspend { withContext(Dispatchers.IO) { runCatching { Photos.read(resolver, uri) }.getOrNull() } }
            },
        )
    }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag("photo")) {
            Icon(Icons.Outlined.Image, contentDescription = stringResource(R.string.messages_photo_add))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.messages_photo_take)) },
                leadingIcon = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null) },
                onClick = {
                    open = false
                    actions.onTakePhoto()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.messages_photo_choose)) },
                leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                onClick = {
                    open = false
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
        }
    }
}

/**
 * The app's own camera for a message photo: the rear camera first, with a switch; the shot is taken into memory
 * (`OnImageCapturedCallback`, never a file), then "Retake" or "Use photo".
 */
@Composable
internal fun PhotoCaptureOverlay(actions: PhotoActions) {
    // The recent-apps snapshot is written to disk: never with a shot on the screen.
    SecureWindow()
    val access = rememberCameraAccess()
    val handle = rememberPhotoCameraHandle()
    val context = LocalContext.current
    var lens by remember { mutableStateOf(CameraLens.BACK) }
    var both by remember { mutableStateOf(false) }
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize().testTag("photo_camera"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    shot?.recycle()
                    actions.onCloseCamera()
                }) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.messages_photo_close_camera)) }
                Spacer(Modifier.weight(1f))
                if (both && shot == null) {
                    IconButton(onClick = { lens = if (lens == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK }) {
                        Icon(Icons.Outlined.Cameraswitch, contentDescription = stringResource(R.string.messages_photo_switch_camera))
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val current = shot
                when {
                    current != null -> Image(
                        current.asImageBitmap(),
                        contentDescription = stringResource(R.string.messages_photo_review),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    access.access != CameraAccess.GRANTED -> CameraPermission(access.access, access.request, access.openSettings)
                    unavailable -> Text(stringResource(R.string.messages_photo_no_camera), textAlign = TextAlign.Center)
                    else -> PhotoCamera(lens, handle, { f, b -> both = f && b }, { unavailable = true }, Modifier.fillMaxSize())
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(Spacing.l),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m, Alignment.CenterHorizontally),
            ) {
                val current = shot
                if (current != null) {
                    OutlinedButton(onClick = {
                        current.recycle()
                        shot = null
                    }) { Text(stringResource(R.string.messages_photo_retake)) }
                    Button(onClick = {
                        shot = null
                        actions.onShot(current)
                    }) { Text(stringResource(R.string.messages_photo_use)) }
                } else if (access.access == CameraAccess.GRANTED && !unavailable) {
                    Button(onClick = { handle.take(context) { b -> shot = b } }, modifier = Modifier.testTag("photo_shutter")) {
                        Text(stringResource(R.string.messages_photo_shutter))
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPermission(access: CameraAccess, request: () -> Unit, openSettings: () -> Unit) {
    Column(Modifier.padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.messages_photo_camera_permission), textAlign = TextAlign.Center)
        if (access == CameraAccess.BLOCKED) {
            TextButton(onClick = openSettings) { Text(stringResource(R.string.messages_photo_camera_settings)) }
        } else {
            TextButton(onClick = request) { Text(stringResource(R.string.messages_photo_camera_allow)) }
        }
    }
}

/**
 * The prepared photos before they are sent (ANDROID-PLAN 0.1.39 step 5): the prepared photo, not the original, with
 * "Location and camera details are removed before sending", the caption, Send and Cancel; Retry after a lost answer.
 */
@Composable
internal fun PhotoDraftOverlay(draft: PhotoDraft, name: String, actions: PhotoActions) {
    // The recent-apps snapshot is written to disk: never with a photo on the screen.
    SecureWindow()
    Surface(Modifier.fillMaxSize().testTag("photo_draft"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onCancel, enabled = !draft.inFlight, modifier = Modifier.testTag("photo_cancel_x")) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.messages_photo_cancel))
                }
                Text(
                    stringResource(R.string.messages_photo_to, name),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (draft.preparing) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(draft.photos) { i, p ->
                            val preview by produceState<ImageBitmap?>(null, p) {
                                value = null // produceState keeps the old value across a change of keys
                                value = withContext(Dispatchers.Default) {
                                    runCatching { Photos.decodeReceived(p.jpeg, PREVIEW_EDGE).asImageBitmap() }.getOrNull()
                                }
                            }
                            preview?.let {
                                Image(
                                    it,
                                    contentDescription = stringResource(R.string.messages_photo_preview_cd, i + 1, draft.photos.size),
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillParentMaxWidth().fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.messages_photo_stripped),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
            )
            draft.problem?.let { pr ->
                NoticeCard(
                    kind = NoticeKind.WARNING,
                    title = stringResource(R.string.messages_error_title),
                    body = stringResource(pr.text),
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s).testTag("photo_problem"),
                    actions = if (draft.photos.isEmpty()) {
                        { TextButton(onClick = actions.onCancel) { Text(stringResource(R.string.messages_ok)) } }
                    } else {
                        null
                    },
                )
            }
            if (draft.failedPicks > 0) {
                Text(
                    pluralStringResource(R.plurals.messages_photo_failed_picks, draft.failedPicks, draft.failedPicks),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = Spacing.gutter).testTag("photo_failed_picks"),
                )
            }
            if (draft.sent > 0 && draft.photos.isNotEmpty()) {
                Text(
                    stringResource(R.string.messages_photo_progress, draft.sent, draft.sent + draft.photos.size),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = Spacing.gutter),
                )
            }
            if (draft.sent == 0) {
                OutlinedTextField(
                    value = draft.caption,
                    onValueChange = actions.onCaption,
                    placeholder = { Text(stringResource(R.string.messages_photo_caption)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).testTag("photo_caption"),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(Spacing.gutter),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m, Alignment.End),
            ) {
                TextButton(onClick = actions.onCancel, enabled = !draft.inFlight, modifier = Modifier.testTag("photo_cancel")) {
                    Text(stringResource(R.string.messages_photo_cancel))
                }
                Button(onClick = actions.onSend, enabled = draft.canSend, modifier = Modifier.testTag("photo_send")) {
                    if (draft.sending) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(if (draft.retry) R.string.messages_photo_retry else R.string.messages_send))
                    }
                }
            }
        }
    }
}

/**
 * What a screen reader says for a photo message: "Photo you sent" or "Photo from {name}", then the caption and the
 * state, each only when there is one ("Photo you sent. Delivered", never "Photo you sent. . Delivered").
 */
internal fun photoLabel(head: String, caption: String?, status: String?): String =
    listOf(head, caption, status).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.joinToString(". ")

/**
 * A photo message (ANDROID-PLAN 0.1.39 "The bubble"): the thumbnail (from memory, or fetched as it is shown),
 * decoded only after the type and the header's bounds (1,024 a side) are checked; a neutral placeholder sized from
 * the claimed `width` and `height`, clamped, until then; the caption under it; the state.
 */
@Composable
internal fun PhotoBubble(m: MessageInfo, name: String, actions: PhotoActions, onLongPress: (MessageInfo?) -> Unit) {
    val a = m.attachment ?: return
    val thumb by produceState<ImageBitmap?>(null, m.messageId, a.state, actions.generation) {
        // produceState keeps the old value across a change of keys: after a clear the old thumbnail goes first.
        value = null
        val bytes = actions.thumbnail(m.messageId) ?: return@produceState
        value = withContext(Dispatchers.Default) { runCatching { Photos.decodeThumbnail(bytes).asImageBitmap() }.getOrNull() }
    }
    val status = photoStatus(a, m.outgoing)
    val statusText = status?.let { if (it.withName) stringResource(it.text, name) else stringResource(it.text) }
    val head = if (m.outgoing) stringResource(R.string.messages_photo_cd_out) else stringResource(R.string.messages_photo_cd_in, name)
    val label = photoLabel(head, m.text, statusText)
    val ratio = if (a.width in 1..PhotoLimits.VIEW_MAX_SIDE && a.height in 1..PhotoLimits.VIEW_MAX_SIDE) {
        (a.width.toFloat() / a.height).coerceIn(MIN_RATIO, MAX_RATIO)
    } else {
        1f
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.xxs),
        horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start,
    ) {
        Box(
            Modifier
                .widthIn(max = BUBBLE_PHOTO_WIDTH)
                .width(BUBBLE_PHOTO_WIDTH)
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(Spacing.m))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(enabled = a.viewable) { actions.onOpen(m.messageId) }
                // The tag before the semantics are cleared (a tag after it would be cleared with the children).
                .testTag("photo_${m.messageId}")
                .clearAndSetSemantics {
                    contentDescription = label
                    this[ThumbnailShown] = thumb != null
                },
            contentAlignment = Alignment.Center,
        ) {
            thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                ?: Icon(Icons.Outlined.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (m.text.isNotEmpty()) {
            Text(m.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Spacing.xxs))
        }
        statusText?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = if (status.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xxs).clickable { onLongPress(m) },
            )
        }
    }
}

/**
 * The photo viewer (ANDROID-PLAN 0.1.39 "Viewing"): `FLAG_SECURE` while open (the recent-apps snapshot is written
 * to disk, and a photo must not end up there), zoom, Save to phone and Share, each after the warning that the photo
 * then leaves VettID. The photo is decoded to the screen's size from memory, after its type and bounds are checked.
 */
@Composable
fun PhotoViewerScreen(state: PhotoViewerUiState, onBack: () -> Unit, onRetry: () -> Unit) {
    SecureWindow()
    val context = LocalContext.current
    val window = LocalWindowInfo.current.containerSize
    val edge = maxOf(window.width, window.height, PREVIEW_EDGE / 2)
    var confirm by remember { mutableStateOf<LeaveAction?>(null) }
    var notice by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val photo = state.photo
    val decoded by produceState<Result<ImageBitmap>?>(null, photo) {
        value = null // produceState keeps the old value across a change of keys
        if (photo == null) return@produceState
        value = withContext(Dispatchers.Default) { runCatching { Photos.decodeReceived(photo.bytes, edge).asImageBitmap() } }
    }
    DetailScaffold(
        onBackClick = onBack,
        modifier = Modifier.testTag("photo_viewer"),
        actions = {
            if (photo != null) {
                IconButton(onClick = { confirm = LeaveAction.SAVE }) {
                    Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.messages_photo_save))
                }
                IconButton(onClick = { confirm = LeaveAction.SHARE }) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.messages_photo_share))
                }
            }
        },
    ) {
        ViewerBody(state, decoded, onRetry)
        notice?.let {
            Text(
                stringResource(it),
                modifier = Modifier.align(Alignment.BottomCenter).padding(Spacing.l),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    val action = confirm
    if (action != null && photo != null) {
        LeaveDialog(action, onDismiss = { confirm = null }) {
            confirm = null
            // Bytes wiped by a clear (a lock, the hold) are not a photo any more: nothing leaves VettID then.
            if (!PhotoExport.intact(photo.bytes, photo.type)) {
                notice = R.string.messages_photo_save_failed
            } else if (action == LeaveAction.SAVE) {
                // A copy: a clear while the write runs must not turn the saved file into zeros.
                val copy = photo.bytes.copyOf()
                scope.launch {
                    val saved = try {
                        withContext(Dispatchers.IO) { PhotoExport.save(context, copy, photo.type) }
                    } finally {
                        copy.fill(0)
                    }
                    notice = if (saved) R.string.messages_photo_saved else R.string.messages_photo_save_failed
                }
            } else {
                PhotoExport.share(context, photo.bytes, photo.type)
                notice = null
            }
        }
    }
}

/** "This puts the photo outside VettID…" before Save to phone or Share. */
@Composable
private fun LeaveDialog(action: LeaveAction, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        title = stringResource(if (action == LeaveAction.SAVE) R.string.messages_photo_save else R.string.messages_photo_share),
        text = stringResource(R.string.messages_photo_leaves),
        confirmLabel = stringResource(R.string.messages_photo_continue),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

private enum class LeaveAction { SAVE, SHARE }

/** The viewer's content: the failure, the zoomable photo, "not supported" when it does not decode, or loading. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ViewerBody(
    state: PhotoViewerUiState,
    decoded: Result<ImageBitmap>?,
    onRetry: () -> Unit,
) {
    val failure = state.error
    val image = decoded?.getOrNull()
    when {
        failure != null -> Column(Modifier.fillMaxSize().padding(Spacing.xl), verticalArrangement = Arrangement.Center) {
            NoticeCard(
                kind = NoticeKind.WARNING,
                title = stringResource(R.string.messages_error_title),
                body = state.problemText?.let { stringResource(it) } ?: failureText(failure.messageRes()),
                actions = { TextButton(onClick = onRetry) { Text(stringResource(R.string.messages_retry)) } },
            )
        }
        image != null -> ZoomableImage(image, state.name)
        decoded?.isFailure == true -> Text(
            stringResource(R.string.messages_photo_in_type),
            modifier = Modifier.align(Alignment.Center).padding(Spacing.xl),
            textAlign = TextAlign.Center,
        )
        else -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ZoomableImage(image: ImageBitmap, name: String?) {
    var scale by remember { mutableFloatStateOf(1f) }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxSize()) {
        Image(
            image,
            contentDescription = stringResource(R.string.messages_photo_viewer_cd),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                        dx = if (scale == 1f) 0f else dx + pan.x
                        dy = if (scale == 1f) 0f else dy + pan.y
                    }
                }
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = dx, translationY = dy),
        )
        name?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(Spacing.m).height(Spacing.xl)) }
    }
}

/**
 * Save to phone and Share (ANDROID-PLAN 0.1.39): Save writes through MediaStore to Pictures/VettID as
 * `VettID_<yyyyMMdd_HHmmss>` with the sniffed type's extension (no permission); Share hands the bytes through the
 * app's own provider, streamed from memory (no file), readable only while the vault is open.
 */
internal object PhotoExport {
    /** Whether [bytes] still are a photo of [type] (a clear zeroes them). */
    fun intact(bytes: ByteArray, type: ImageType): Boolean = ImageType.sniff(bytes) == type

    fun save(context: Context, bytes: ByteArray, type: ImageType): Boolean {
        val resolver = context.contentResolver
        var uri: android.net.Uri? = null
        val ok = runCatching { uri = saveTo(resolver, bytes, type) }.isSuccess && uri != null
        // A row left pending would show as an empty file in the gallery: removed when the write failed.
        if (!ok) uri?.let { u -> runCatching { resolver.delete(u, null, null) } }
        return ok
    }

    private fun saveTo(resolver: android.content.ContentResolver, bytes: ByteArray, type: ImageType): android.net.Uri? {
        val name = "VettID_" + DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(LocalDateTime.now())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.${type.extension}")
            put(MediaStore.Images.Media.MIME_TYPE, type.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VettID")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw java.io.IOException("no stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: java.io.IOException) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    fun share(context: Context, bytes: ByteArray, type: ImageType) {
        val uri = SharedPhotos.offer(context.packageName, bytes, type)
        val send = Intent(Intent.ACTION_SEND).apply {
            setType(type.mime)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Whether a photo bubble shows its decoded thumbnail (for tests; never read out). */
internal val ThumbnailShown = androidx.compose.ui.semantics.SemanticsPropertyKey<Boolean>("ThumbnailShown")

private val BUBBLE_PHOTO_WIDTH = 240.dp
private const val MIN_RATIO = 0.5f
private const val MAX_RATIO = 2f
private const val MAX_ZOOM = 5f
private const val PREVIEW_EDGE = 1_440
