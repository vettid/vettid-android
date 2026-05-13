package com.vettid.app.features.connections

import android.Manifest
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
    onNavigateToTheirGrants: (connectionId: String) -> Unit = {},
    onNavigateToYourGrants: (connectionId: String) -> Unit = {},
    onVerifyIdentity: () -> Unit = {},
    isVerifyingIdentity: Boolean = false,
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

    // Re-hydrate the persistent verify-state whenever this screen
    // returns to the foreground. The VM's grantEvents collector only
    // catches results that arrive while the VM is alive — if the
    // user got a verify verdict via OS notification while backgrounded
    // (or while on a different screen), the in-memory state is stale.
    // The vault is the source of truth, so re-asking on resume keeps
    // the row honest without forcing a fresh challenge.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshVerifyState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

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

    // Manage actions moved to an overflow menu in the top bar so the
    // detail body stays focused on Them/You content. The menu opens
    // dialogs for the few rarely-used actions (rotate keys, remove
    // connection, history) — primary in-screen flows now live on the
    // two tabs instead.
    val isRotating = (state as? ConnectionDetailState.Loaded)?.isRotating == true
    val isRevoking = (state as? ConnectionDetailState.Loaded)?.isRevoking == true
    var overflowOpen by remember { mutableStateOf(false) }
    var showRotateInfo by remember { mutableStateOf(false) }
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
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("History") },
                                onClick = {
                                    overflowOpen = false
                                    onShowHistory()
                                },
                                leadingIcon = { Icon(Icons.Default.History, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Rotate keys") },
                                onClick = {
                                    overflowOpen = false
                                    showRotateInfo = true
                                },
                                enabled = !isRotating,
                                leadingIcon = {
                                    if (isRotating) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Default.Refresh, null)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Remove connection", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    overflowOpen = false
                                    viewModel.onRevokeClick()
                                },
                                enabled = !isRevoking,
                                leadingIcon = {
                                    if (isRevoking) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.error)
                                    else Icon(Icons.Default.PersonRemove, null, tint = MaterialTheme.colorScheme.error)
                                },
                            )
                        }
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
                val verifyResult by viewModel.verifyResult.collectAsState()
                val isVerifying by viewModel.verifying.collectAsState()
                val verifyState by viewModel.verifyState.collectAsState()
                val initialFocus by viewModel.initialFocus.collectAsState()
                // Surface connection.authenticate verdict as a Snackbar.
                LaunchedEffect(verifyResult) {
                    val r = verifyResult ?: return@LaunchedEffect
                    snackbarHostState.showSnackbar(message = r.message)
                    viewModel.dismissVerifyResult()
                }
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
                    onNavigateToTheirGrants = onNavigateToTheirGrants,
                    onNavigateToYourGrants = onNavigateToYourGrants,
                    onVerifyIdentity = { viewModel.verifyIdentity() },
                    isVerifyingIdentity = isVerifying,
                    verifyState = verifyState,
                    peerLocation = peerLocation,
                    isRequestingPeerLocation = isRequestingPeerLocation,
                    onRequestPeerLocation = { viewModel.requestPeerLocation() },
                    showRotateInfo = showRotateInfo,
                    onDismissRotateInfo = { showRotateInfo = false },
                    initialFocus = initialFocus,
                    onFocusConsumed = { viewModel.consumeFocus() },
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
    onNavigateToTheirGrants: (String) -> Unit = {},
    onNavigateToYourGrants: (String) -> Unit = {},
    onVerifyIdentity: () -> Unit = {},
    isVerifyingIdentity: Boolean = false,
    verifyState: com.vettid.app.features.grants.VerifyStatePayload? = null,
    peerLocation: com.vettid.app.core.nats.CachedPeerLocation? = null,
    isRequestingPeerLocation: Boolean = false,
    onRequestPeerLocation: () -> Unit = {},
    showRotateInfo: Boolean = false,
    onDismissRotateInfo: () -> Unit = {},
    initialFocus: String? = null,
    onFocusConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val connectedDateFormatter = java.text.SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", java.util.Locale.getDefault())
    val connectedDate = connectedDateFormatter.format(java.util.Date(
        if (connection.createdAt < 10000000000L) connection.createdAt * 1000 else connection.createdAt
    ))
    val peerShortName = connection.peerDisplayName.takeIf { it.isNotBlank() }?.substringBefore(' ') ?: "this connection"
    val isActive = connection.status == ConnectionStatus.ACTIVE
    var selectedTab by remember { mutableStateOf(0) }

    // Pulse highlight applied to the verify row when we land here from
    // a verify-result OS notification. Compose-managed so we don't
    // need to plumb a flag through three layers; the effect picks the
    // signal up and the row reads `pulseVerify` to animate.
    var pulseVerify by remember { mutableStateOf(false) }
    // y-position of the verify row in the scrollable column so we can
    // scroll-anchor on focus=verify. Reported by an onGloballyPositioned
    // modifier on the row itself.
    var verifyRowY by remember { mutableStateOf<Int?>(null) }
    val scrollState = rememberScrollState()
    LaunchedEffect(initialFocus, verifyRowY) {
        if (initialFocus == "verify") {
            selectedTab = 0
            // Wait until the Them tab has rendered the verify row and
            // reported its y-position. If we got both signals (focus +
            // y), scroll the column so the verify row is in view, then
            // start the pulse.
            verifyRowY?.let { target ->
                scrollState.animateScrollTo((target - 80).coerceAtLeast(0))
            }
            pulseVerify = true
            onFocusConsumed()
            kotlinx.coroutines.delay(2400)
            pulseVerify = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // === 1. PUBLIC PROFILE (header, always visible) ===
        // Same BusinessCardView used for the user's own public-profile
        // preview — hero avatar + clickable contact card + public keys
        // header so the page identity is unambiguous regardless of
        // which tab is active.
        com.vettid.app.features.personaldata.PeerProfileView(
            profile = peerPublishedProfile,
            modifier = Modifier.fillMaxWidth(),
            peerHandlers = peerHandlers,
            peerPublicSecrets = peerPublicSecrets,
            peerDataCatalog = peerDataCatalog,
            peerSecretCatalog = peerSecretCatalog,
            isPeerOnline = isPeerOnline,
        )

        // === 2. TABS — Them / You ===
        // Split the page by data-ownership boundary: "Them" surfaces
        // peer-sourced content + actions on the peer (verify their
        // identity, view their location). "You" surfaces what you
        // share + grants + your sharing toggles. Rare manage actions
        // (rotate, remove, history) moved to the top-bar overflow.
        Spacer(modifier = Modifier.height(8.dp))
        TabRow(selectedTabIndex = selectedTab, modifier = Modifier.fillMaxWidth()) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Them") },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("You") },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            when (selectedTab) {
                0 -> ThemTabContent(
                    peerShortName = peerShortName,
                    connectionId = connection.connectionId,
                    isActive = isActive,
                    verifyState = verifyState,
                    isVerifyingIdentity = isVerifyingIdentity,
                    onVerifyIdentity = onVerifyIdentity,
                    pulseVerify = pulseVerify,
                    onVerifyRowPositioned = { y -> verifyRowY = y },
                    peerLocation = peerLocation,
                    isRequestingPeerLocation = isRequestingPeerLocation,
                    onRequestPeerLocation = onRequestPeerLocation,
                    onNavigateToPeerCatalog = onNavigateToPeerCatalog,
                    onNavigateToTheirGrants = onNavigateToTheirGrants,
                )
                1 -> YouTabContent(
                    peerShortName = peerShortName,
                    connectionId = connection.connectionId,
                    isActive = isActive,
                    connectedDate = connectedDate,
                    onNavigateToMySharing = onNavigateToMySharing,
                    onNavigateToYourGrants = onNavigateToYourGrants,
                )
            }

            if (showRotateInfo) {
                RotateKeysDialog(
                    peerIdentityKey = peerIdentityKey,
                    peerPublicKey = peerPublicKey,
                    peerUserGuid = peerUserGuid,
                    isRotating = isRotating,
                    onConfirm = {
                        onDismissRotateInfo()
                        onRotateKeysClick()
                    },
                    onDismiss = onDismissRotateInfo,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * "Them" tab — peer-sourced data and actions on the peer.
 * Verify identity row mirrors the location-row pattern: persistent
 * status line with a re-request affordance, no fleeting Snackbar UX.
 */
@Composable
private fun ThemTabContent(
    peerShortName: String,
    connectionId: String,
    isActive: Boolean,
    verifyState: com.vettid.app.features.grants.VerifyStatePayload?,
    isVerifyingIdentity: Boolean,
    onVerifyIdentity: () -> Unit,
    pulseVerify: Boolean,
    onVerifyRowPositioned: (Int) -> Unit,
    peerLocation: com.vettid.app.core.nats.CachedPeerLocation?,
    isRequestingPeerLocation: Boolean,
    onRequestPeerLocation: () -> Unit,
    onNavigateToPeerCatalog: (String) -> Unit,
    onNavigateToTheirGrants: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                modifier = Modifier.clickable(enabled = isActive) {
                    onNavigateToPeerCatalog(connectionId)
                },
                headlineContent = { Text("Their catalog") },
                supportingContent = { Text("Items $peerShortName has published — request access here") },
                leadingContent = { Icon(Icons.Default.Inbox, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
            HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable(enabled = isActive) {
                    onNavigateToTheirGrants(connectionId)
                },
                headlineContent = { Text("Data they've shared") },
                supportingContent = { Text("Items $peerShortName granted access to — held in trust on their behalf") },
                leadingContent = { Icon(Icons.Default.Inbox, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
            HorizontalDivider()
            PeerLocationRow(
                peerName = peerShortName,
                peerLocation = peerLocation,
                isRequesting = isRequestingPeerLocation,
                enabled = isActive,
                onRequestPeerLocation = onRequestPeerLocation,
            )
            HorizontalDivider()
            Box(
                modifier = Modifier.onGloballyPositioned { coords ->
                    onVerifyRowPositioned(coords.positionInRoot().y.toInt())
                },
            ) {
                VerifyIdentityRow(
                    peerName = peerShortName,
                    verifyState = verifyState,
                    isVerifying = isVerifyingIdentity,
                    enabled = isActive,
                    onVerifyIdentity = onVerifyIdentity,
                    pulse = pulseVerify,
                )
            }
        }
    }
}

/**
 * "You" tab — what you share with the peer + management of that
 * sharing relationship. The connected-date row stays here as a
 * passive info anchor.
 */
@Composable
private fun YouTabContent(
    peerShortName: String,
    connectionId: String,
    isActive: Boolean,
    connectedDate: String,
    onNavigateToMySharing: (String) -> Unit,
    onNavigateToYourGrants: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                modifier = Modifier.clickable(enabled = isActive) {
                    onNavigateToMySharing(connectionId)
                },
                headlineContent = { Text("My sharing") },
                supportingContent = { Text("Online presence, location, and what $peerShortName can request from you") },
                leadingContent = { Icon(Icons.Default.Outbox, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
            HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable(enabled = isActive) {
                    onNavigateToYourGrants(connectionId)
                },
                headlineContent = { Text("Data you've shared") },
                supportingContent = { Text("Active grants you've given $peerShortName, plus their pending requests of you") },
                leadingContent = { Icon(Icons.Default.Outbox, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Connected") },
                supportingContent = { Text(connectedDate) },
                leadingContent = { Icon(Icons.Default.Event, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
    }
}

/**
 * "Verify identity" row — mirrors PeerLocationRow's freshness pattern.
 *
 *   - never verified    → "Verify $peer's identity" + tap-to-request,
 *                          shield icon, chevron trailing
 *   - last verified ok  → "Verified $freshness" + a refresh affordance
 *                          to challenge again
 *   - last failed       → "Verification failed $freshness" + refresh
 *                          affordance to retry
 *   - in flight         → spinner, disabled body, "Waiting…" subtitle
 */
@Composable
private fun VerifyIdentityRow(
    peerName: String,
    verifyState: com.vettid.app.features.grants.VerifyStatePayload?,
    isVerifying: Boolean,
    enabled: Boolean,
    onVerifyIdentity: () -> Unit,
    pulse: Boolean = false,
) {
    val hasOutbound = !verifyState?.lastOutboundAt.isNullOrBlank()
    val freshness = remember(verifyState?.lastOutboundAt) {
        verifyState?.lastOutboundAt?.takeIf { it.isNotBlank() }?.let { formatLocationFreshness(it) } ?: ""
    }
    // Pulse background when navigation arrives from a verify-result
    // OS notification — fades in the primaryContainer tint, holds
    // briefly, fades back out. Removes itself silently after the
    // 2.4s window the parent screen controls.
    val pulseAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pulse) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = if (pulse) 350 else 600
        ),
        label = "verify-pulse",
    )
    val highlightedColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = pulseAlpha * 0.45f)
    ListItem(
        modifier = Modifier
            .background(highlightedColor)
            .clickable(enabled = enabled && !isVerifying) { onVerifyIdentity() },
        headlineContent = {
            Text(if (hasOutbound) "$peerName's identity" else "Verify $peerName's identity")
        },
        supportingContent = {
            when {
                isVerifying -> Text("Waiting for $peerName's vault to sign…")
                !hasOutbound -> Text("Send a challenge so $peerName's vault proves it holds the credential")
                verifyState?.lastOutboundOk == true -> Text("Verified $freshness")
                else -> {
                    val reason = verifyState?.lastOutboundReason?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                    Text("Verification failed $freshness$reason")
                }
            }
        },
        leadingContent = {
            if (isVerifying) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Default.VerifiedUser,
                    null,
                    tint = if (verifyState?.lastOutboundOk == true) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            if (isVerifying) {
                // Body spinner already showing — no trailing affordance.
            } else if (hasOutbound) {
                IconButton(onClick = onVerifyIdentity, enabled = enabled) {
                    Icon(Icons.Default.Refresh, contentDescription = "Verify again", tint = MaterialTheme.colorScheme.primary)
                }
            } else {
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
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
                // Tap row body → open the user's default maps app via
                // a geo: intent. The trailing refresh icon (below)
                // sends a fresh location.request without leaving the
                // screen; the row click stays focused on "view".
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
            trailingContent = {
                // Refresh button: ask the peer for a fresh sample.
                // Replaces the chevron — peer-location is a leaf
                // entry (it opens an external app, not another
                // in-app screen) so the chevron didn't carry useful
                // affordance anyway, and the refresh action is what
                // the user needs once they've already seen the
                // cached point.
                if (isRequesting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(
                        onClick = onRequestPeerLocation,
                        enabled = enabled,
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Request fresh location",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
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

