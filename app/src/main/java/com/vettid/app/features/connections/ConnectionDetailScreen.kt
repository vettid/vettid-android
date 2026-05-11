package com.vettid.app.features.connections

import android.Manifest
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.platform.LocalContext
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
    // Carries (connectionId, peerBtcAddress?) so the Send BTC screen
    // pre-fills the recipient field with the peer's published wallet
    // address — saves the user from copy-pasting it.
    onSendBtc: (String, String?) -> Unit = { _, _ -> },
    onShowHistory: () -> Unit = {},
    onNavigateToPeerCatalog: (connectionId: String) -> Unit = {},
    onNavigateToMySharing: (connectionId: String) -> Unit = {},
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
                    scope.launch { snackbarHostState.showSnackbar(effect.message) }
                }
                is ConnectionDetailEffect.ShowError -> {
                    scope.launch { snackbarHostState.showSnackbar(effect.message) }
                }
                is ConnectionDetailEffect.PeerRequestedLocation -> {
                    // V6: peer pinged us for our location. Surface a
                    // confirmable snackbar — "Send" fulfills via
                    // location.send-once without enabling continuous
                    // sharing; dismiss is a silent decline.
                    val peerName = (state as? ConnectionDetailState.Loaded)?.connection?.peerDisplayName ?: "Peer"
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = "$peerName is asking for your location",
                            actionLabel = "Send",
                            withDismissAction = true,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.fulfillPeerLocationRequest()
                        }
                    }
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
    val isActiveConnection = (state as? ConnectionDetailState.Loaded)?.connection?.status == ConnectionStatus.ACTIVE
    val connectionId = (state as? ConnectionDetailState.Loaded)?.connection?.connectionId

    // Manage Connection rows replaced the top-bar overflow menu.
    // Rotate / History / Remove are inline buttons in that section
    // so they stay discoverable without a kebab.
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
                val peerLocation by viewModel.peerLocation.collectAsState()
                val isRequestingPeerLocation by viewModel.isRequestingPeerLocation.collectAsState()
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
                    isPeerOnline = isPeerOnline,
                    onMessageClick = { viewModel.onMessageClick() },
                    onSendBtcClick = {
                        onSendBtc(
                            currentState.connection.connectionId,
                            peerWallets.firstOrNull()?.address?.takeIf { it.isNotBlank() },
                        )
                    },
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
                    onShowHistory = onShowHistory,
                    onNavigateToPeerCatalog = onNavigateToPeerCatalog,
                    onNavigateToMySharing = onNavigateToMySharing,
                    peerLocation = peerLocation,
                    isRequestingPeerLocation = isRequestingPeerLocation,
                    onRequestPeerLocation = { viewModel.requestPeerLocation() },
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
    isPeerOnline: Boolean = false,
    onMessageClick: () -> Unit,
    onSendBtcClick: () -> Unit = {},
    onVoiceCallClick: () -> Unit,
    onVideoCallClick: () -> Unit,
    onRotateKeysClick: () -> Unit = {},
    onRevokeClick: () -> Unit,
    onShowHistory: () -> Unit = {},
    onNavigateToPeerCatalog: (String) -> Unit = {},
    onNavigateToMySharing: (String) -> Unit = {},
    peerLocation: com.vettid.app.core.nats.CachedPeerLocation? = null,
    isRequestingPeerLocation: Boolean = false,
    onRequestPeerLocation: () -> Unit = {},
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

        // === 2. SHARING ===
        // Two scoped entries: peer's catalog (request flow) and the
        // user's outbound sharing settings (presence + location +
        // policy editor). Each one navigates to its own focused
        // screen so the actions on each side stay unambiguous.
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "SHARING",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        val peerShortName = connection.peerDisplayName.takeIf { it.isNotBlank() }?.substringBefore(' ') ?: "this connection"
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                ListItem(
                    modifier = Modifier.clickable(enabled = connection.status == ConnectionStatus.ACTIVE) {
                        onNavigateToPeerCatalog(connection.connectionId)
                    },
                    headlineContent = { Text("Their catalog") },
                    supportingContent = { Text("Items $peerShortName has published — request access here") },
                    leadingContent = { Icon(Icons.Default.Inbox, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                HorizontalDivider()
                ListItem(
                    modifier = Modifier.clickable(enabled = connection.status == ConnectionStatus.ACTIVE) {
                        onNavigateToMySharing(connection.connectionId)
                    },
                    headlineContent = { Text("My sharing") },
                    supportingContent = { Text("Online presence, location, and what $peerShortName can request from you") },
                    leadingContent = { Icon(Icons.Default.Outbox, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                HorizontalDivider()
                PeerLocationRow(
                    peerName = peerShortName,
                    peerLocation = peerLocation,
                    isRequesting = isRequestingPeerLocation,
                    enabled = connection.status == ConnectionStatus.ACTIVE,
                    onRequestPeerLocation = onRequestPeerLocation,
                )
            }
        }

        // === 3. MANAGE CONNECTION ===
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "MANAGE CONNECTION",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        var showRotateInfo by remember { mutableStateOf(false) }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                // Connected date stays at the top — it's a passive
                // info row and the natural mental anchor for the
                // section.
                ListItem(
                    headlineContent = { Text("Connected") },
                    supportingContent = { Text(connectedDate) },
                    leadingContent = { Icon(Icons.Default.Event, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                HorizontalDivider()
                ListItem(
                    modifier = Modifier.clickable(enabled = connection.status == ConnectionStatus.ACTIVE) { onShowHistory() },
                    headlineContent = { Text("History") },
                    supportingContent = { Text("Calls, messages, and other interactions") },
                    leadingContent = { Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                HorizontalDivider()
                ListItem(
                    modifier = Modifier.clickable(enabled = !isRotating && connection.status == ConnectionStatus.ACTIVE) {
                        showRotateInfo = true
                    },
                    headlineContent = { Text("Rotate keys") },
                    supportingContent = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lock, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Text("End-to-end encrypted", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                "Generate a fresh shared encryption key with this peer",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingContent = {
                        if (isRotating) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                HorizontalDivider()
                ListItem(
                    modifier = Modifier.clickable(enabled = !isRevoking && connection.status == ConnectionStatus.ACTIVE) {
                        onRevokeClick()
                    },
                    headlineContent = { Text("Remove connection", color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text("Revoke this connection and zero out shared keys", color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)) },
                    leadingContent = {
                        if (isRevoking) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.error)
                        else Icon(Icons.Default.PersonRemove, null, tint = MaterialTheme.colorScheme.error)
                    },
                )
            }
        }

        if (showRotateInfo) {
            RotateKeysDialog(
                peerIdentityKey = peerIdentityKey,
                peerPublicKey = peerPublicKey,
                peerUserGuid = peerUserGuid,
                isRotating = isRotating,
                onConfirm = {
                    showRotateInfo = false
                    onRotateKeysClick()
                },
                onDismiss = { showRotateInfo = false },
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
        } // end padded inner Column
    }
}

@Composable
private fun RotateKeysDialog(
    peerIdentityKey: String?,
    peerPublicKey: String?,
    peerUserGuid: String?,
    isRotating: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rotate keys") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("End-to-end encrypted", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "Rotation generates a new shared encryption key with this peer. Past messages remain readable; future messages use the new key.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!peerIdentityKey.isNullOrBlank()) {
                    KeyFingerprint("Identity key", peerIdentityKey!!)
                }
                if (!peerPublicKey.isNullOrBlank()) {
                    KeyFingerprint("E2E session key", peerPublicKey!!)
                }
                if (!peerUserGuid.isNullOrBlank()) {
                    KeyFingerprint("User ID", peerUserGuid!!, mono = false)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isRotating) {
                Text(if (isRotating) "Rotating…" else "Rotate now")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun KeyFingerprint(label: String, value: String, mono: Boolean = true) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (mono) value.chunked(8).joinToString(" ") else value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else androidx.compose.ui.text.font.FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


/**
 * SHARING-card row for the peer's location. Three states:
 *
 *  - shared, fresh        → "<peer> shared their location" + freshness
 *                           label + Map icon, taps open the map view (A5).
 *  - not shared           → "<peer> isn't sharing" + a "Request" affordance
 *                           that fires location.request (V6 / A6).
 *  - request in flight    → spinner, disabled, "Requesting…" supporting text.
 */
@Composable
private fun PeerLocationRow(
    peerName: String,
    peerLocation: com.vettid.app.core.nats.CachedPeerLocation?,
    isRequesting: Boolean,
    enabled: Boolean,
    onRequestPeerLocation: () -> Unit,
) {
    if (peerLocation != null) {
        val freshness = remember(peerLocation.updatedAt) {
            formatLocationFreshness(peerLocation.updatedAt)
        }
        val context = LocalContext.current
        ListItem(
            modifier = Modifier.clickable(enabled = enabled) {
                // A5: hand off to the user's default maps app via a
                // geo: intent. Avoids adding a maps SDK dependency
                // and respects the user's choice of map app
                // (Google Maps, OsmAnd, Organic Maps, etc.).
                openLocationInMaps(context, peerLocation.latitude, peerLocation.longitude, peerName)
            },
            headlineContent = { Text("$peerName's location") },
            supportingContent = {
                val coords = "%.5f, %.5f".format(peerLocation.latitude, peerLocation.longitude)
                Text("Shared $freshness · $coords")
            },
            leadingContent = {
                Icon(
                    Icons.Default.LocationOn,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
    } else {
        ListItem(
            modifier = Modifier.clickable(enabled = enabled && !isRequesting) {
                onRequestPeerLocation()
            },
            headlineContent = { Text("Request $peerName's location") },
            supportingContent = {
                Text(
                    if (isRequesting) "Requesting…"
                    else "$peerName isn't sharing right now. Send a one-time request.",
                )
            },
            leadingContent = {
                if (isRequesting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.LocationOff,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingContent = {
                if (!isRequesting) {
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}

/**
 * Launch the user's default maps app with a marker at the shared
 * coordinates. Uses the standard `geo:` schema with a `q=` query that
 * carries a human label so the map pin reads "Alice's location"
 * instead of just the raw coordinates. Falls back silently if no
 * matching activity is registered.
 */
private fun openLocationInMaps(
    context: android.content.Context,
    latitude: Double,
    longitude: Double,
    label: String,
) {
    val uri = android.net.Uri.parse(
        "geo:%1\$f,%2\$f?q=%1\$f,%2\$f(%3\$s's location)".format(
            latitude,
            longitude,
            android.net.Uri.encode(label),
        )
    )
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // No maps app installed — silent no-op; the row will still
        // show the coordinates inline so the data isn't trapped.
    }
}

/**
 * Format an RFC3339 timestamp as a human "shared X ago" label.
 * Falls back to the raw string if parsing fails so we never lose the
 * source-of-truth in the UI.
 */
private fun formatLocationFreshness(updatedAt: String): String {
    if (updatedAt.isBlank()) return "just now"
    return try {
        val parsed = java.time.Instant.parse(updatedAt)
        val elapsedSec = java.time.Duration.between(parsed, java.time.Instant.now()).seconds
        when {
            elapsedSec < 60 -> "just now"
            elapsedSec < 3600 -> "${elapsedSec / 60} min ago"
            elapsedSec < 86400 -> "${elapsedSec / 3600} hr ago"
            else -> "${elapsedSec / 86400} day(s) ago"
        }
    } catch (_: Exception) {
        updatedAt
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

