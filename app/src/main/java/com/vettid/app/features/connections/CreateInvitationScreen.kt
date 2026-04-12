package com.vettid.app.features.connections

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Screen for creating connection invitations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateInvitationScreen(
    viewModel: CreateInvitationViewModel = hiltViewModel(),
    onInvitationCreated: (String) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val expirationMinutes by viewModel.expirationMinutes.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CreateInvitationEffect.ShareInvitation -> {
                    shareInvitation(context, effect.deepLink, effect.displayName)
                }
                is CreateInvitationEffect.LinkCopied -> {
                    snackbarHostState.showSnackbar("Link copied (auto-clears in 30s)")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Invitation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val currentState = state) {
                is CreateInvitationState.Idle -> {
                    IdleContent(
                        expirationMinutes = expirationMinutes,
                        expirationOptions = viewModel.expirationOptions,
                        onExpirationSelected = { viewModel.setExpirationMinutes(it) },
                        onCreate = { viewModel.createInvitation() }
                    )
                }

                is CreateInvitationState.Creating -> {
                    CreatingContent()
                }

                is CreateInvitationState.Created -> {
                    CreatedContent(
                        qrCodeData = currentState.invitation.qrCodeData,
                        expiresInSeconds = currentState.expiresInSeconds,
                        onShare = { viewModel.shareInvitation() },
                        onCopyLink = { viewModel.copyLink() }
                    )
                }

                is CreateInvitationState.PeerAccepted -> {
                    PeerAcceptedContent(
                        peerAlias = currentState.peerAlias,
                        peerPhoto = currentState.peerPhoto,
                        peerEmail = currentState.peerEmail,
                        peerFields = currentState.peerFields,
                        onAccept = {
                            viewModel.respondToConnection(true)
                            onBack()
                        },
                        onDecline = {
                            viewModel.respondToConnection(false)
                            onBack()
                        }
                    )
                }

                is CreateInvitationState.Expired -> {
                    ExpiredContent(
                        onCreateNew = { viewModel.retry() },
                        onBack = onBack
                    )
                }

                is CreateInvitationState.Error -> {
                    ErrorContent(
                        message = currentState.message,
                        onRetry = { viewModel.retry() }
                    )
                }
            }
        }
    }
}

@Composable
private fun IdleContent(
    expirationMinutes: Int,
    expirationOptions: List<ExpirationOption>,
    onExpirationSelected: (Int) -> Unit,
    onCreate: () -> Unit
) {
    Spacer(modifier = Modifier.height(32.dp))

    Text(
        text = "Create a connection invitation to share with someone you trust.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(24.dp))

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Timer,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Valid for 15 minutes",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "The invitation must be used within 15 minutes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }

    Spacer(modifier = Modifier.height(32.dp))

    Button(
        onClick = onCreate,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Create Invitation")
    }
}

@Composable
private fun CreatingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Creating invitation...",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun CreatedContent(
    qrCodeData: String,
    expiresInSeconds: Int,
    onShare: () -> Unit,
    onCopyLink: () -> Unit
) {
    val qrBitmap = remember(qrCodeData) {
        generateQrCode(qrCodeData, 300)
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Share this invitation",
        style = MaterialTheme.typography.headlineSmall
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Scan this QR code or share the link to connect.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(24.dp))

    // QR Code
    qrBitmap?.let { bitmap ->
        Card(
            modifier = Modifier
                .size(280.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Expiration timer
    ExpirationTimer(expiresInSeconds = expiresInSeconds)

    Spacer(modifier = Modifier.height(24.dp))

    // Action buttons
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onCopyLink,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Copy", maxLines = 1)
        }

        Button(
            onClick = onShare,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Share", maxLines = 1)
        }
    }
}

@Composable
private fun ExpirationTimer(expiresInSeconds: Int) {
    val minutes = expiresInSeconds / 60
    val seconds = expiresInSeconds % 60
    val timeText = String.format("%02d:%02d", minutes, seconds)

    val isLow = expiresInSeconds < 60

    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Expires in ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = timeText,
            style = MaterialTheme.typography.titleMedium,
            color = if (isLow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PeerAcceptedContent(
    peerAlias: String,
    peerPhoto: String?,
    peerEmail: String?,
    peerFields: Map<String, Map<String, String>>?,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val photoBitmap = remember(peerPhoto) {
        peerPhoto?.let { base64 ->
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) { null }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Connection Request",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Photo or initials
        if (photoBitmap != null) {
            Image(
                bitmap = photoBitmap.asImageBitmap(),
                contentDescription = "Profile photo",
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Surface(
                modifier = Modifier.size(96.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = peerAlias.take(2).uppercase(),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = peerAlias,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        peerEmail?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "wants to connect with you",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Profile fields
        if (!peerFields.isNullOrEmpty()) {
            val customFields = peerFields.entries
                .filter { !it.key.startsWith("_system_") }
                .filter { (it.value["value"] ?: "").isNotBlank() }

            if (customFields.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "PUBLIC PROFILE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        customFields.forEachIndexed { index, (_, fieldData) ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                )
                            }
                            Text(
                                text = (fieldData["display_name"] ?: "").trim(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = fieldData["value"] ?: "",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Accept / Decline buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onDecline,
                modifier = Modifier.weight(1f)
            ) {
                Text("Decline")
            }
            Button(
                onClick = onAccept,
                modifier = Modifier.weight(1f)
            ) {
                Text("Accept")
            }
        }
    }
}

@Composable
private fun ExpiredContent(
    onCreateNew: () -> Unit,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Invitation Expired",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This invitation has expired. Create a new one to connect.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onCreateNew) {
                Text("Create New Invitation")
            }
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onBack) {
                Text("Go Back")
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Error",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) {
                Text("Try Again")
            }
        }
    }
}

// MARK: - Utility Functions

private fun generateQrCode(content: String, size: Int): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(
                    x, y,
                    if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                )
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

private fun shareInvitation(context: Context, deepLink: String, displayName: String) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Connect with $displayName on VettID")
        putExtra(Intent.EXTRA_TEXT, "Join me on VettID: $deepLink")
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share invitation"))
}
