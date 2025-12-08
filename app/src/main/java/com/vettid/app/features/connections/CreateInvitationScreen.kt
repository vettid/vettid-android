package com.vettid.app.features.connections

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
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

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CreateInvitationEffect.ShareInvitation -> {
                    shareInvitation(context, effect.deepLink, effect.displayName)
                }
                is CreateInvitationEffect.CopyToClipboard -> {
                    copyToClipboard(context, effect.text)
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
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
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

    Spacer(modifier = Modifier.height(32.dp))

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Invitation expires in:",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            Column(Modifier.selectableGroup()) {
                expirationOptions.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .selectable(
                                selected = expirationMinutes == option.minutes,
                                onClick = { onExpirationSelected(option.minutes) },
                                role = Role.RadioButton
                            )
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = expirationMinutes == option.minutes,
                            onClick = null
                        )
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }
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
        text = "Scan this QR code",
        style = MaterialTheme.typography.headlineSmall
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Ask the other person to scan this QR code to connect with you.",
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
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Copy Link")
        }

        Button(
            onClick = onShare,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Share")
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

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("VettID Invitation", text)
    clipboard.setPrimaryClip(clip)
}
