package com.vettid.app.features.connections.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Data for sharing an invitation.
 */
data class ShareableInvitation(
    val qrCodeData: String,
    val deepLink: String,
    val displayName: String,
    val expiresInSeconds: Int
)

/**
 * Enhanced share sheet with multiple sharing options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareInvitationSheet(
    invitation: ShareableInvitation,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showCopiedSnackbar by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Share Invitation",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Choose how to share your connection invitation",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // QR Code Card
            QrCodeCard(
                qrData = invitation.qrCodeData,
                expiresInSeconds = invitation.expiresInSeconds
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Share options
            Text(
                text = "Or share via",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Share options row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ShareOption(
                    icon = Icons.Default.ContentCopy,
                    label = "Copy Link",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = {
                        copyToClipboard(context, invitation.deepLink)
                        showCopiedSnackbar = true
                    }
                )

                ShareOption(
                    icon = Icons.Default.Share,
                    label = "Share",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = {
                        shareViaIntent(context, invitation.deepLink, invitation.displayName)
                    }
                )

                ShareOption(
                    icon = Icons.AutoMirrored.Filled.Message,
                    label = "Message",
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    onClick = {
                        shareViaSms(context, invitation.deepLink, invitation.displayName)
                    }
                )

                ShareOption(
                    icon = Icons.Default.Email,
                    label = "Email",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = {
                        shareViaEmail(context, invitation.deepLink, invitation.displayName)
                    }
                )
            }

            // Nearby Share option (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = {
                        shareViaNearby(context, invitation.deepLink, invitation.displayName)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share with Nearby Device")
                }
            }

            // Show copied message
            if (showCopiedSnackbar) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.inverseSurface
                ) {
                    Text(
                        text = "✓ Link copied to clipboard",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                LaunchedEffect(showCopiedSnackbar) {
                    kotlinx.coroutines.delay(2000)
                    showCopiedSnackbar = false
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Security note
            SecurityNote()
        }
    }
}

@Composable
private fun QrCodeCard(
    qrData: String,
    expiresInSeconds: Int
) {
    val qrBitmap = remember(qrData) {
        generateQrCode(qrData, 250)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            qrBitmap?.let { bitmap ->
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Expiration timer
            val minutes = expiresInSeconds / 60
            val seconds = expiresInSeconds % 60
            val timeText = String.format("%d:%02d", minutes, seconds)
            val isLow = expiresInSeconds < 60

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Timer,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (isLow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Expires in $timeText",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isLow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ShareOption(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            color = containerColor
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = contentColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SecurityNote() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "This link contains a one-time invitation code. Only share it with people you trust.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Inline share buttons for compact layouts.
 */
@Composable
fun InlineShareButtons(
    invitation: ShareableInvitation,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = {
                copyToClipboard(context, invitation.deepLink)
            },
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Copy")
        }

        Button(
            onClick = {
                shareViaIntent(context, invitation.deepLink, invitation.displayName)
            },
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

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("VettID Invitation", text)
    clipboard.setPrimaryClip(clip)

    // Auto-clear clipboard after 30 seconds for security
    Handler(Looper.getMainLooper()).postDelayed({
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            }
        } catch (e: Exception) {
            // Ignore - clipboard may have changed
        }
    }, 30_000)
}

private fun shareViaIntent(context: Context, deepLink: String, displayName: String) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Connect with $displayName on VettID")
        putExtra(
            Intent.EXTRA_TEXT,
            """
            |Join me on VettID!
            |
            |$deepLink
            |
            |VettID provides end-to-end encrypted communication and secure identity verification.
            """.trimMargin()
        )
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share invitation via"))
}

private fun shareViaSms(context: Context, deepLink: String, displayName: String) {
    val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
        data = android.net.Uri.parse("smsto:")
        putExtra("sms_body", "Connect with me on VettID: $deepLink")
    }
    try {
        context.startActivity(smsIntent)
    } catch (e: Exception) {
        // Fall back to generic share
        shareViaIntent(context, deepLink, displayName)
    }
}

private fun shareViaEmail(context: Context, deepLink: String, displayName: String) {
    val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
        data = android.net.Uri.parse("mailto:")
        putExtra(Intent.EXTRA_SUBJECT, "Connect with $displayName on VettID")
        putExtra(
            Intent.EXTRA_TEXT,
            """
            |Hi,
            |
            |I'd like to connect with you on VettID for secure communication.
            |
            |Click this link to accept my invitation:
            |$deepLink
            |
            |VettID provides end-to-end encrypted messaging and secure identity verification.
            |
            |Best regards,
            |$displayName
            """.trimMargin()
        )
    }
    try {
        context.startActivity(emailIntent)
    } catch (e: Exception) {
        // Fall back to generic share
        shareViaIntent(context, deepLink, displayName)
    }
}

private fun shareViaNearby(context: Context, deepLink: String, displayName: String) {
    // Use Android's Nearby Share if available
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, deepLink)
        // Request Nearby Share specifically
        `package` = "com.google.android.gms"
    }
    try {
        context.startActivity(shareIntent)
    } catch (e: Exception) {
        // Fall back to generic share
        shareViaIntent(context, deepLink, displayName)
    }
}
