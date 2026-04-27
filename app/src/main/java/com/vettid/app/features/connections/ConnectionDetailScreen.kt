package com.vettid.app.features.connections

import android.Manifest
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
    onShowHistory: () -> Unit = {},
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
    val peerHandlers by viewModel.peerHandlers.collectAsState()
    val peerPublicSecrets by viewModel.peerPublicSecrets.collectAsState()

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

    val titleText = when (val s = state) {
        is ConnectionDetailState.Loaded ->
            s.connection.peerDisplayName.takeIf { it.isNotBlank() } ?: "Connection"
        else -> "Connection"
    }
    var menuExpanded by remember { mutableStateOf(false) }
    val isActiveConnection = (state as? ConnectionDetailState.Loaded)?.connection?.status == ConnectionStatus.ACTIVE
    val isRotating = (state as? ConnectionDetailState.Loaded)?.isRotating == true
    val isRevoking = (state as? ConnectionDetailState.Loaded)?.isRevoking == true
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleText) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (isActiveConnection) {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("History") },
                                leadingIcon = { Icon(Icons.Default.History, null) },
                                onClick = {
                                    menuExpanded = false
                                    onShowHistory()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Rotate keys") },
                                enabled = !isRotating,
                                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.rotateKeys()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Revoke connection",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                enabled = !isRevoking,
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.PersonRemove,
                                        null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.onRevokeClick()
                                }
                            )
                        }
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
                val shareableHandlers by viewModel.shareableHandlers.collectAsState()
                val connectionGrants by viewModel.connectionGrants.collectAsState()
                val grantPendingId by viewModel.grantPendingId.collectAsState()
                val presenceOverride by viewModel.presenceOverride.collectAsState()
                val presenceOverrideInFlight by viewModel.presenceOverrideInFlight.collectAsState()
                val peerDataCatalog by viewModel.peerDataCatalog.collectAsState()
                val peerSecretCatalog by viewModel.peerSecretCatalog.collectAsState()
                val isPeerOnline by viewModel.isPeerOnline.collectAsState()
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
                    peerHandlers = peerHandlers,
                    peerPublicSecrets = peerPublicSecrets,
                    peerDataCatalog = peerDataCatalog,
                    peerSecretCatalog = peerSecretCatalog,
                    isRevoking = currentState.isRevoking,
                    isRotating = currentState.isRotating,
                    isLocationSharingEnabled = currentState.isLocationSharingEnabled,
                    isTogglingLocationSharing = currentState.isTogglingLocationSharing,
                    shareableHandlers = shareableHandlers,
                    connectionGrants = connectionGrants,
                    grantPendingId = grantPendingId,
                    presenceOverride = presenceOverride,
                    presenceOverrideInFlight = presenceOverrideInFlight,
                    isPeerOnline = isPeerOnline,
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
                    onShareHandlerToggle = { id, granted -> viewModel.setShareHandlerForConnection(id, granted) },
                    onPresenceOverrideChange = { viewModel.setPresenceOverride(it) },
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
    peerHandlers: List<com.vettid.app.core.nats.PeerHandlerInfo> = emptyList(),
    peerPublicSecrets: List<com.vettid.app.core.nats.PeerPublicSecretMetadata> = emptyList(),
    peerDataCatalog: List<com.vettid.app.core.nats.PeerDataCatalogEntry>? = null,
    peerSecretCatalog: List<com.vettid.app.core.nats.PeerPublicSecretMetadata>? = null,
    isRevoking: Boolean,
    isRotating: Boolean = false,
    isLocationSharingEnabled: Boolean = false,
    isTogglingLocationSharing: Boolean = false,
    shareableHandlers: List<com.vettid.app.core.nats.VaultHandler> = emptyList(),
    connectionGrants: Map<String, Boolean> = emptyMap(),
    grantPendingId: String? = null,
    presenceOverride: Boolean? = null,
    presenceOverrideInFlight: Boolean = false,
    isPeerOnline: Boolean = false,
    onMessageClick: () -> Unit,
    onSendBtcClick: () -> Unit = {},
    onVoiceCallClick: () -> Unit,
    onVideoCallClick: () -> Unit,
    onRotateKeysClick: () -> Unit = {},
    onRevokeClick: () -> Unit,
    onLocationSharingToggle: (Boolean) -> Unit = {},
    onShareHandlerToggle: (String, Boolean) -> Unit = { _, _ -> },
    onPresenceOverrideChange: (Boolean?) -> Unit = {},
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
            peerHandlers = peerHandlers,
            peerPublicSecrets = peerPublicSecrets,
            peerDataCatalog = peerDataCatalog,
            peerSecretCatalog = peerSecretCatalog,
            isPeerOnline = isPeerOnline,
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

        // Quick-actions for an active connection live on the connection
        // list row (Message / Voice / Video). The detail screen focuses
        // on inspection and per-connection settings — actions are added
        // to the top-bar overflow menu as needed.

        // === 2. SHARED WITH CONNECTION ===
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "SHARED WITH CONNECTION",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        var showHandlersDialog by remember { mutableStateOf(false) }
        val grantedCount = shareableHandlers.count { connectionGrants[it.id] == true }

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
                    modifier = Modifier.clickable(
                        enabled = shareableHandlers.isNotEmpty() && connection.status == ConnectionStatus.ACTIVE
                    ) { showHandlersDialog = true },
                    headlineContent = { Text("Handlers") },
                    supportingContent = {
                        Text(
                            when {
                                shareableHandlers.isEmpty() -> "No shareable capabilities"
                                grantedCount == 0 -> "No capabilities shared with this peer"
                                grantedCount == shareableHandlers.size -> "All ${shareableHandlers.size} capabilities shared"
                                else -> "$grantedCount of ${shareableHandlers.size} capabilities shared"
                            }
                        )
                    },
                    leadingContent = { Icon(Icons.Default.Extension, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = if (shareableHandlers.isNotEmpty()) {
                        { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else null
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
                HorizontalDivider()
                var showPresencePicker by remember { mutableStateOf(false) }
                ListItem(
                    modifier = Modifier.clickable(
                        enabled = connection.status == ConnectionStatus.ACTIVE && !presenceOverrideInFlight
                    ) { showPresencePicker = true },
                    headlineContent = { Text("Online Presence") },
                    supportingContent = {
                        Text(
                            when (presenceOverride) {
                                null -> "Follow account default"
                                true -> "Always shared with this connection"
                                false -> "Never shared with this connection"
                            }
                        )
                    },
                    leadingContent = { Icon(Icons.Default.Circle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = {
                        if (presenceOverrideInFlight) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                )
                if (showPresencePicker) {
                    PresenceOverrideDialog(
                        current = presenceOverride,
                        onPick = {
                            showPresencePicker = false
                            onPresenceOverrideChange(it)
                        },
                        onDismiss = { showPresencePicker = false },
                    )
                }
            }
        }

        if (showHandlersDialog) {
            SharedHandlersDialog(
                shareableHandlers = shareableHandlers,
                connectionGrants = connectionGrants,
                grantPendingId = grantPendingId,
                onToggle = onShareHandlerToggle,
                onDismiss = { showHandlersDialog = false },
            )
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
                // Rotate Keys + Revoke moved to top-bar overflow menu.
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Event, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connected $connectedDate", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/**
 * Per-connection handler share toggles. Shown when the user taps the
 * Handlers row in the SHARED WITH CONNECTION section. One switch per
 * shareable+enabled+globally-shared handler — narrows what this peer
 * may invoke.
 */
/**
 * Tri-state picker for the per-connection presence override.
 * Default = follow user-wide setting; Always = explicit on; Never =
 * explicit off. Stored on the vault's ConnectionRecord and consulted
 * by the presence heartbeat loop.
 */
@Composable
private fun PresenceOverrideDialog(
    current: Boolean?,
    onPick: (Boolean?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Online Presence for this Connection") },
        text = {
            Column {
                PresenceOverrideRow(
                    label = "Follow account default",
                    description = "Use the global Share online presence setting",
                    selected = current == null,
                    onClick = { onPick(null) },
                )
                PresenceOverrideRow(
                    label = "Always share",
                    description = "This peer sees you online regardless of the default",
                    selected = current == true,
                    onClick = { onPick(true) },
                )
                PresenceOverrideRow(
                    label = "Never share",
                    description = "This peer never sees you online",
                    selected = current == false,
                    onClick = { onPick(false) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun PresenceOverrideRow(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SharedHandlersDialog(
    shareableHandlers: List<com.vettid.app.core.nats.VaultHandler>,
    connectionGrants: Map<String, Boolean>,
    grantPendingId: String?,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shared Capabilities") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Choose which capabilities this peer can use. Only capabilities you've shared globally appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                shareableHandlers.forEachIndexed { idx, handler ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = handler.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (handler.description.isNotEmpty()) {
                                Text(
                                    text = handler.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (grantPendingId == handler.id) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Switch(
                                checked = connectionGrants[handler.id] == true,
                                onCheckedChange = { onToggle(handler.id, it) },
                            )
                        }
                    }
                    if (idx < shareableHandlers.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
