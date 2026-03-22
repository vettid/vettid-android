package com.vettid.app.features.connections

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.network.Connection
import com.vettid.app.core.network.ConnectionStatus
import com.vettid.app.core.network.Profile
import java.text.SimpleDateFormat
import java.util.*

/**
 * Screen displaying connection details.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionDetailScreen(
    viewModel: ConnectionDetailViewModel = hiltViewModel(),
    onMessageClick: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val showRevokeDialog by viewModel.showRevokeDialog.collectAsState()
    val peerPhoto by viewModel.peerPhoto.collectAsState()
    val peerEmail by viewModel.peerEmail.collectAsState()
    val peerFields by viewModel.peerFields.collectAsState()
    val peerPublicKey by viewModel.peerPublicKey.collectAsState()

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ConnectionDetailEffect.NavigateToMessages -> {
                    onMessageClick()
                }
                is ConnectionDetailEffect.NavigateToProfile -> {
                    // Could navigate to profile view
                }
                is ConnectionDetailEffect.NavigateBack -> {
                    onBack()
                }
                is ConnectionDetailEffect.ShowSuccess -> {
                    // Show snackbar
                }
                is ConnectionDetailEffect.ShowError -> {
                    // Show snackbar
                }
            }
        }
    }

    // Revoke confirmation dialog
    if (showRevokeDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRevokeDialog() },
            title = { Text("Revoke Connection?") },
            text = {
                Text("This will permanently remove this connection. You won't be able to send or receive messages from this person anymore.")
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmRevoke() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Revoke")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissRevokeDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        when (val currentState = state) {
            is ConnectionDetailState.Loading -> {
                LoadingContent(modifier = Modifier.padding(padding))
            }

            is ConnectionDetailState.Loaded -> {
                LoadedContent(
                    connection = currentState.connection,
                    profile = currentState.profile,
                    peerPhotoBase64 = peerPhoto,
                    peerEmail = peerEmail,
                    peerFields = peerFields,
                    peerPublicKey = peerPublicKey,
                    isRevoking = currentState.isRevoking,
                    isRotating = currentState.isRotating,
                    isLocationSharingEnabled = currentState.isLocationSharingEnabled,
                    isTogglingLocationSharing = currentState.isTogglingLocationSharing,
                    onMessageClick = { viewModel.onMessageClick() },
                    onVoiceCallClick = { viewModel.onVoiceCallClick() },
                    onVideoCallClick = { viewModel.onVideoCallClick() },
                    onRotateKeysClick = { viewModel.rotateKeys() },
                    onRevokeClick = { viewModel.onRevokeClick() },
                    onLocationSharingToggle = { viewModel.toggleLocationSharing(it) },
                    modifier = Modifier.padding(padding)
                )
            }

            is ConnectionDetailState.Error -> {
                ErrorContent(
                    message = currentState.message,
                    onRetry = { viewModel.loadConnection() },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun LoadedContent(
    connection: Connection,
    profile: Profile?,
    peerPhotoBase64: String? = null,
    peerEmail: String? = null,
    peerFields: Map<String, Map<String, String>>? = null,
    peerPublicKey: String? = null,
    isRevoking: Boolean,
    isRotating: Boolean = false,
    isLocationSharingEnabled: Boolean = false,
    isTogglingLocationSharing: Boolean = false,
    onMessageClick: () -> Unit,
    onVoiceCallClick: () -> Unit,
    onVideoCallClick: () -> Unit,
    onRotateKeysClick: () -> Unit = {},
    onRevokeClick: () -> Unit,
    onLocationSharingToggle: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val photoBitmap = remember(peerPhotoBase64) {
        peerPhotoBase64?.let { base64 ->
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) { null }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Avatar — photo if available, otherwise initials
        if (photoBitmap != null) {
            Image(
                bitmap = photoBitmap.asImageBitmap(),
                contentDescription = "Profile photo",
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Surface(
                modifier = Modifier.size(100.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = connection.peerDisplayName.take(2).uppercase(),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Name
        Text(
            text = connection.peerDisplayName,
            style = MaterialTheme.typography.headlineMedium
        )

        // Email
        peerEmail?.let { email ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = email,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Status badge
        Spacer(modifier = Modifier.height(8.dp))
        StatusBadge(status = connection.status)

        Spacer(modifier = Modifier.height(24.dp))

        // Action icons row: Message | Call | Video
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Message
            IconButton(
                onClick = onMessageClick,
                enabled = connection.status == ConnectionStatus.ACTIVE
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Chat,
                    contentDescription = "Message",
                    modifier = Modifier.size(28.dp),
                    tint = if (connection.status == ConnectionStatus.ACTIVE)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }

            // Voice call
            IconButton(
                onClick = onVoiceCallClick,
                enabled = connection.status == ConnectionStatus.ACTIVE
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Voice Call",
                    modifier = Modifier.size(28.dp),
                    tint = if (connection.status == ConnectionStatus.ACTIVE)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }

            // Video call
            IconButton(
                onClick = onVideoCallClick,
                enabled = connection.status == ConnectionStatus.ACTIVE
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = "Video Call",
                    modifier = Modifier.size(28.dp),
                    tint = if (connection.status == ConnectionStatus.ACTIVE)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        }

        // Peer's public profile fields
        if (!peerFields.isNullOrEmpty()) {
            val allFields = peerFields!!.entries
                .filter { (it.value["value"] ?: "").isNotBlank() }
                // Don't duplicate name already shown above
                .filter { it.key != "_system_first_name" && it.key != "_system_last_name" }

            if (allFields.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "PUBLIC PROFILE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        allFields.forEachIndexed { index, (_, fieldData) ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                )
                            }
                            val displayName = (fieldData["display_name"] ?: "").trim()
                                .removePrefix(" System ")
                                .replaceFirstChar { it.uppercase() }
                            val value = fieldData["value"] ?: ""
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

        }

        // --- Security ---
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "SECURITY",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                // E2E encryption status
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "End-to-End Encrypted",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Public key fingerprint
                if (!peerPublicKey.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Public Key",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = peerPublicKey!!.chunked(8).joinToString(" "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // --- Shared Data (stub) ---
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "SHARED WITH CONNECTION",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                ListItem(
                    headlineContent = { Text("Shared Data") },
                    supportingContent = { Text("No shared data") },
                    leadingContent = {
                        Icon(Icons.Default.FolderShared, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Shared Secrets") },
                    supportingContent = { Text("No shared secrets") },
                    leadingContent = {
                        Icon(Icons.Default.Key, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Handlers") },
                    supportingContent = { Text("No active handlers") },
                    leadingContent = {
                        Icon(Icons.Default.Extension, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- Manage Connection ---
        Text(
            text = "MANAGE CONNECTION",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        // Connection info card
        ConnectionInfoCard(connection = connection)

        Spacer(modifier = Modifier.height(16.dp))

        // Location sharing card
        LocationSharingCard(
            isEnabled = isLocationSharingEnabled,
            isToggling = isTogglingLocationSharing,
            isActive = connection.status == ConnectionStatus.ACTIVE,
            onToggle = onLocationSharingToggle
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Rotate Keys button
        if (connection.status == ConnectionStatus.ACTIVE) {
            OutlinedButton(
                onClick = onRotateKeysClick,
                enabled = !isRotating
            ) {
                if (isRotating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Rotate Keys")
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Revoke button
        if (connection.status == ConnectionStatus.ACTIVE) {
            OutlinedButton(
                onClick = onRevokeClick,
                enabled = !isRevoking,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                if (isRevoking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PersonRemove,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Revoke Connection")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun LocationSharingCard(
    isEnabled: Boolean,
    isToggling: Boolean,
    isActive: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Share My Location",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "Share your latest location with this connection",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isToggling) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { onToggle(it) },
                    enabled = isActive
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: ConnectionStatus) {
    val (color, label) = when (status) {
        ConnectionStatus.ACTIVE -> MaterialTheme.colorScheme.primary to "Connected"
        ConnectionStatus.PENDING -> MaterialTheme.colorScheme.secondary to "Pending"
        ConnectionStatus.REVOKED -> MaterialTheme.colorScheme.error to "Revoked"
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
private fun ProfileInfoCard(profile: Profile) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Profile",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (profile.bio != null) {
                ProfileInfoRow(
                    icon = Icons.Default.Info,
                    label = "Bio",
                    value = profile.bio
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (profile.location != null) {
                ProfileInfoRow(
                    icon = Icons.Default.LocationOn,
                    label = "Location",
                    value = profile.location
                )
            }
        }
    }
}

@Composable
private fun ProfileInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ConnectionInfoCard(connection: Connection) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Connection Info",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            ConnectionInfoRow(
                icon = Icons.Default.Event,
                label = "Connected since",
                value = formatDate(connection.createdAt)
            )

            connection.lastMessageAt?.let { timestamp ->
                Spacer(modifier = Modifier.height(8.dp))
                ConnectionInfoRow(
                    icon = Icons.Default.Email,
                    label = "Last message",
                    value = formatDate(timestamp)
                )
            }
        }
    }
}

@Composable
private fun ConnectionInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
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
                Text("Retry")
            }
        }
    }
}

// MARK: - Utility Functions

private fun formatDate(timestamp: Long): String {
    val timestampMillis = if (timestamp < 10000000000L) timestamp * 1000 else timestamp
    val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return sdf.format(Date(timestampMillis))
}
