package com.vettid.app.features.connections

import android.Manifest
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.features.calling.CallType
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
    onSendBtc: (String) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val showRevokeDialog by viewModel.showRevokeDialog.collectAsState()
    val peerPhoto by viewModel.peerPhoto.collectAsState()
    val peerEmail by viewModel.peerEmail.collectAsState()
    val peerFields by viewModel.peerFields.collectAsState()
    val peerPublicKey by viewModel.peerPublicKey.collectAsState()
    val peerUserGuid by viewModel.peerUserGuid.collectAsState()
    val peerIdentityKey by viewModel.peerIdentityKey.collectAsState()
    val peerWallets by viewModel.peerWallets.collectAsState()
    val peerPublishedProfile by viewModel.peerPublishedProfile.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingCallType by remember { mutableStateOf<CallType?>(null) }

    val callPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            pendingCallType?.let { callType ->
                when (callType) {
                    CallType.VOICE -> viewModel.onVoiceCallClick()
                    CallType.VIDEO -> viewModel.onVideoCallClick()
                }
            }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Microphone permission is required for calls")
            }
        }
        pendingCallType = null
    }

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
                    peerUserGuid = peerUserGuid,
                    peerIdentityKey = peerIdentityKey,
                    peerWallets = peerWallets,
                    peerPublishedProfile = peerPublishedProfile,
                    isRevoking = currentState.isRevoking,
                    isRotating = currentState.isRotating,
                    isLocationSharingEnabled = currentState.isLocationSharingEnabled,
                    isTogglingLocationSharing = currentState.isTogglingLocationSharing,
                    onMessageClick = { viewModel.onMessageClick() },
                    onSendBtcClick = { onSendBtc(currentState.connection.connectionId) },
                    onVoiceCallClick = {
                        pendingCallType = CallType.VOICE
                        callPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    },
                    onVideoCallClick = {
                        pendingCallType = CallType.VIDEO
                        callPermissionLauncher.launch(arrayOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.CAMERA
                        ))
                    },
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
    peerUserGuid: String? = null,
    peerIdentityKey: String? = null,
    peerWallets: List<com.vettid.app.core.nats.PeerWalletInfo> = emptyList(),
    peerPublishedProfile: com.vettid.app.features.personaldata.PublishedProfileData =
        com.vettid.app.features.personaldata.PublishedProfileData(emptyList(), false),
    isRevoking: Boolean,
    isRotating: Boolean = false,
    isLocationSharingEnabled: Boolean = false,
    isTogglingLocationSharing: Boolean = false,
    onMessageClick: () -> Unit,
    onSendBtcClick: () -> Unit = {},
    onVoiceCallClick: () -> Unit,
    onVideoCallClick: () -> Unit,
    onRotateKeysClick: () -> Unit = {},
    onRevokeClick: () -> Unit,
    onLocationSharingToggle: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val connectedDateFormatter = java.text.SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", java.util.Locale.getDefault())
    val connectedDate = connectedDateFormatter.format(java.util.Date(
        if (connection.createdAt < 10000000000L) connection.createdAt * 1000 else connection.createdAt
    ))

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // === 1. PUBLIC PROFILE ===
        // Rendered through the same BusinessCardView used for the
        // user's own public-profile preview so both sides show the
        // same hero avatar / clickable contact card / categorized
        // fields / public keys layout. PublishedProfileData is built
        // in ConnectionDetailViewModel from the cached peer profile.
        com.vettid.app.features.personaldata.PeerProfileView(
            profile = peerPublishedProfile,
            modifier = Modifier.fillMaxWidth(),
        )

        // Action buttons + detail sections below the profile use the
        // screen's side padding. PeerProfileView handles its own
        // horizontal padding internally, so we only pad the rest.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

        // === ACTION BUTTONS ===
        if (connection.status == ConnectionStatus.ACTIVE) {
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalIconButton(
                        onClick = onMessageClick,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "Message"
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Message", style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalIconButton(
                        onClick = onSendBtcClick,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountBalance,
                            contentDescription = "Send BTC"
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Send BTC", style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalIconButton(
                        onClick = onVoiceCallClick,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = "Call"
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Call", style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalIconButton(
                        onClick = onVideoCallClick,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = "Video"
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Video", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // === 2. SHARED WITH CONNECTION ===
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "SHARED WITH CONNECTION",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                ListItem(
                    headlineContent = { Text("Shared Data") },
                    supportingContent = { Text("No shared data") },
                    leadingContent = { Icon(Icons.Default.FolderShared, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Shared Secrets") },
                    supportingContent = { Text("No shared secrets") },
                    leadingContent = { Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Handlers") },
                    supportingContent = { Text("No active handlers") },
                    leadingContent = { Icon(Icons.Default.Extension, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Location Sharing") },
                    supportingContent = { Text(if (isLocationSharingEnabled) "Enabled" else "Disabled") },
                    leadingContent = { Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = {
                        Switch(
                            checked = isLocationSharingEnabled,
                            onCheckedChange = onLocationSharingToggle,
                            enabled = connection.status == ConnectionStatus.ACTIVE && !isTogglingLocationSharing
                        )
                    }
                )
            }
        }

        // === 3. SECURITY ===
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "SECURITY",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("End-to-End Encrypted", style = MaterialTheme.typography.bodyMedium)
                }

                if (!peerIdentityKey.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Identity Key", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = peerIdentityKey!!.chunked(8).joinToString(" "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!peerPublicKey.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("E2E Session Key", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = peerPublicKey!!.chunked(8).joinToString(" "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!peerUserGuid.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("User ID", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = peerUserGuid!!,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(12.dp))

                if (connection.status == ConnectionStatus.ACTIVE) {
                    OutlinedButton(
                        onClick = onRotateKeysClick,
                        enabled = !isRotating,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isRotating) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Rotate Keys")
                    }
                }
            }
        }

        // === 4. MANAGE CONNECTION ===
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "MANAGE CONNECTION",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Connected date
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Event, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connected $connectedDate", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Revoke button
                if (connection.status == ConnectionStatus.ACTIVE) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = onRevokeClick,
                        enabled = !isRevoking,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        if (isRevoking) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.PersonRemove, null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Revoke Connection")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        } // end padded inner Column
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
