package com.vettid.app

import android.graphics.BitmapFactory
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vettid.app.features.applock.AppLockScreen
import com.vettid.app.features.applock.PinSetupScreen
import com.vettid.app.features.calling.ActiveCallScreen
import com.vettid.app.features.calling.CallManager
import com.vettid.app.features.calling.CallUIEvent
import com.vettid.app.features.calling.IncomingCallScreen
import com.vettid.app.features.calling.OutgoingCallScreen
import com.vettid.app.features.connections.*
import com.vettid.app.features.auth.BiometricAuthManager
import com.vettid.app.features.enrollment.EnrollmentScreen
import com.vettid.app.features.feed.FeedContent
import com.vettid.app.features.archive.ArchiveScreenFull
import com.vettid.app.features.secrets.SecretsScreenFull
import com.vettid.app.features.setup.FirstTimeSetupScreen
import com.vettid.app.features.vault.DeployVaultScreen
import com.vettid.app.features.vault.VaultPreferencesScreenFull
import kotlinx.coroutines.launch
import com.vettid.app.features.handlers.HandlerDetailScreen
import com.vettid.app.features.handlers.HandlerDiscoveryScreen
import com.vettid.app.features.handlers.HandlerExecutionScreen
import com.vettid.app.features.messaging.ConversationScreen
import com.vettid.app.features.profile.ProfileScreen
import com.vettid.app.features.vault.VaultStatusScreen
import com.vettid.app.ui.backup.BackupDetailScreen
import com.vettid.app.ui.backup.BackupListScreen
import com.vettid.app.ui.backup.BackupSettingsScreen
import com.vettid.app.ui.components.NatsConnectionDetails
import com.vettid.app.ui.components.NatsConnectionDetailsDialog
import com.vettid.app.ui.components.QrCodeScanner
import com.vettid.app.ui.navigation.*
import com.vettid.app.ui.recovery.ProteanRecoveryScreen
import com.vettid.app.features.debug.CredentialDebugScreen
import com.vettid.app.features.voting.MyVotesScreen
import com.vettid.app.features.voting.ProposalDetailScreen
import com.vettid.app.features.voting.ProposalsScreen
import com.vettid.app.features.transfer.TransferRequestScreen
import com.vettid.app.features.transfer.TransferApprovalScreen
import com.vettid.app.features.postenrollment.PostEnrollmentScreen

private const val TAG = "VettIDApp"

sealed class Screen(val route: String) {
    object Welcome : Screen("welcome")
    object Enrollment : Screen("enrollment?startWithManualEntry={startWithManualEntry}&initialCode={initialCode}") {
        fun createRoute(startWithManualEntry: Boolean = false, initialCode: String? = null): String {
            val encodedCode = initialCode?.let { java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
            return "enrollment?startWithManualEntry=$startWithManualEntry&initialCode=$encodedCode"
        }
    }
    object Authentication : Screen("authentication")
    object Main : Screen("main")
    object HandlerDiscovery : Screen("handlers")
    object HandlerDetail : Screen("handlers/{handlerId}") {
        fun createRoute(handlerId: String) = "handlers/$handlerId"
    }
    object HandlerExecution : Screen("handlers/{handlerId}/execute") {
        fun createRoute(handlerId: String) = "handlers/$handlerId/execute"
    }
    // Connections
    object Connections : Screen("connections")
    object CreateInvitation : Screen("connections/create-invitation")
    object ScanInvitation : Screen("connections/scan-invitation?data={data}") {
        fun createRoute(data: String? = null) = if (data != null) {
            "connections/scan-invitation?data=${java.net.URLEncoder.encode(data, "UTF-8")}"
        } else {
            "connections/scan-invitation"
        }
    }
    object ConnectionDetail : Screen("connections/{connectionId}") {
        fun createRoute(connectionId: String) = "connections/$connectionId"
    }
    object Conversation : Screen("connections/{connectionId}/messages") {
        fun createRoute(connectionId: String) = "connections/$connectionId/messages"
    }
    // Profile
    object Profile : Screen("profile")
    // Vault More items
    object PersonalData : Screen("personal-data")
    object Secrets : Screen("secrets")
    object Archive : Screen("archive")
    object VaultPreferences : Screen("vault-preferences")
    // Backup
    object Backups : Screen("backups")
    object BackupSettings : Screen("backups/settings")
    object BackupDetail : Screen("backups/{backupId}") {
        fun createRoute(backupId: String) = "backups/$backupId"
    }
    object ProteanRecovery : Screen("recovery/protean")
    // App Lock & Setup
    object AppLock : Screen("app-lock")
    object PinSetup : Screen("pin-setup")
    object FirstTimeSetup : Screen("first-time-setup")
    object DeployVault : Screen("deploy-vault")
    // Calling
    object IncomingCall : Screen("call/incoming")
    object OutgoingCall : Screen("call/outgoing")
    object ActiveCall : Screen("call/active")
    // Debug
    object CredentialDebug : Screen("debug/credentials")
    // Voting (Issue #50)
    object Proposals : Screen("voting/proposals")
    object ProposalDetail : Screen("voting/proposals/{proposalId}") {
        fun createRoute(proposalId: String) = "voting/proposals/$proposalId"
    }
    object MyVotes : Screen("voting/my-votes")
    // Transfer (Issue #31: Device-to-device credential transfer)
    object TransferRequest : Screen("transfer/request")
    object TransferApproval : Screen("transfer/approve/{transferId}") {
        fun createRoute(transferId: String) = "transfer/approve/$transferId"
    }
    // Post-Enrollment verification (Phase 1 of post-enrollment flow)
    object PostEnrollment : Screen("post-enrollment")
}

@Composable
fun VettIDApp(
    navController: NavHostController = rememberNavController(),
    appViewModel: AppViewModel = hiltViewModel(),
    callManager: CallManager? = null,
    deepLinkData: DeepLinkData = DeepLinkData(DeepLinkType.NONE),
    onDeepLinkConsumed: () -> Unit = {}
) {
    val appState by appViewModel.appState.collectAsState()

    // Handle call UI events
    LaunchedEffect(callManager) {
        callManager?.showCallUI?.collect { event ->
            when (event) {
                is CallUIEvent.ShowIncoming -> {
                    navController.navigate(Screen.IncomingCall.route)
                }
                is CallUIEvent.ShowOutgoing -> {
                    navController.navigate(Screen.OutgoingCall.route)
                }
                is CallUIEvent.ShowActive -> {
                    // Pop any existing call screens and navigate to active
                    navController.navigate(Screen.ActiveCall.route) {
                        popUpTo(Screen.IncomingCall.route) { inclusive = true }
                        popUpTo(Screen.OutgoingCall.route) { inclusive = true }
                    }
                }
                is CallUIEvent.DismissCall -> {
                    // Pop back from call screens
                    navController.popBackStack(Screen.IncomingCall.route, inclusive = true)
                    navController.popBackStack(Screen.OutgoingCall.route, inclusive = true)
                    navController.popBackStack(Screen.ActiveCall.route, inclusive = true)
                }
            }
        }
    }

    // Store pending deep links to process after app state is ready
    var pendingConnectData by remember { mutableStateOf<String?>(null) }
    var pendingEnrollData by remember { mutableStateOf<String?>(null) }
    var pendingTransferApprovalId by remember { mutableStateOf<String?>(null) }

    // Handle deep links - store data for processing in appState effect
    LaunchedEffect(deepLinkData) {
        when (deepLinkData.type) {
            DeepLinkType.ENROLL -> {
                val code = deepLinkData.code
                if (code != null) {
                    pendingEnrollData = code
                }
                onDeepLinkConsumed()
            }
            DeepLinkType.CONNECT -> {
                val data = deepLinkData.code
                if (data != null) {
                    pendingConnectData = data
                }
                onDeepLinkConsumed()
            }
            DeepLinkType.TRANSFER_APPROVE -> {
                val transferId = deepLinkData.code
                if (transferId != null) {
                    pendingTransferApprovalId = transferId
                }
                onDeepLinkConsumed()
            }
            DeepLinkType.NONE -> { /* No deep link */ }
        }
    }

    // Handle navigation based on app state and pending deep links
    LaunchedEffect(appState, pendingEnrollData, pendingConnectData, pendingTransferApprovalId) {
        // Handle pending enrollment - takes priority
        if (pendingEnrollData != null) {
            val code = pendingEnrollData
            pendingEnrollData = null
            navController.navigate(Screen.Enrollment.createRoute(startWithManualEntry = true, initialCode = code)) {
                popUpTo(0) { inclusive = true }
            }
            return@LaunchedEffect
        }

        when {
            !appState.hasCredential -> navController.navigate(Screen.Welcome.route) {
                popUpTo(0) { inclusive = true }
            }
            !appState.isAuthenticated -> navController.navigate(Screen.Authentication.route) {
                popUpTo(0) { inclusive = true }
            }
            else -> {
                // Check for pending transfer approval (Issue #31: Device-to-device transfer)
                if (pendingTransferApprovalId != null) {
                    val transferId = pendingTransferApprovalId
                    pendingTransferApprovalId = null
                    navController.navigate(Screen.Main.route) {
                        popUpTo(0) { inclusive = true }
                    }
                    // Then navigate to TransferApproval
                    if (transferId != null) {
                        navController.navigate(Screen.TransferApproval.createRoute(transferId))
                    }
                }
                // Check for pending connect data before going to Main
                else if (pendingConnectData != null) {
                    val data = pendingConnectData
                    pendingConnectData = null
                    navController.navigate(Screen.Main.route) {
                        popUpTo(0) { inclusive = true }
                    }
                    // Then navigate to ScanInvitation
                    if (data != null) {
                        navController.navigate(Screen.ScanInvitation.createRoute(data))
                    }
                } else {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Welcome.route
    ) {
        composable(Screen.Welcome.route) {
            WelcomeScreen(
                onScanQR = { navController.navigate(Screen.Enrollment.createRoute(startWithManualEntry = false)) },
                onRecoverAccount = { navController.navigate(Screen.ProteanRecovery.route) }
            )
        }
        composable(
            route = Screen.Enrollment.route,
            arguments = listOf(
                navArgument("startWithManualEntry") {
                    type = NavType.BoolType
                    defaultValue = false
                },
                navArgument("initialCode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val startWithManualEntry = backStackEntry.arguments?.getBoolean("startWithManualEntry") ?: false
            val initialCode = backStackEntry.arguments?.getString("initialCode")?.let {
                if (it.isNotEmpty()) java.net.URLDecoder.decode(it, "UTF-8") else null
            }
            EnrollmentScreen(
                onEnrollmentComplete = {
                    // Refresh credential status and mark as authenticated
                    // User just completed enrollment (proved identity with PIN), so skip biometric prompt
                    appViewModel.refreshCredentialStatus()
                    appViewModel.setAuthenticated(true)
                    // Navigate to post-enrollment verification
                    navController.navigate(Screen.PostEnrollment.route) {
                        popUpTo(Screen.Enrollment.route) { inclusive = true }
                    }
                },
                onCancel = { navController.popBackStack() },
                startWithManualEntry = startWithManualEntry,
                initialCode = initialCode
            )
        }
        composable(Screen.Authentication.route) {
            AuthenticationScreen(
                onAuthenticated = { appViewModel.setAuthenticated(true) }
            )
        }
        composable(Screen.Main.route) {
            MainScreen(
                onNavigateToHandlers = {
                    navController.navigate(Screen.HandlerDiscovery.route)
                },
                onNavigateToConnections = {
                    navController.navigate(Screen.Connections.route)
                },
                onNavigateToProfile = {
                    navController.navigate(Screen.Profile.route)
                },
                onNavigateToBackups = {
                    navController.navigate(Screen.Backups.route)
                },
                onNavigateToProteanRecovery = {
                    navController.navigate(Screen.ProteanRecovery.route)
                },
                onNavigateToConnectionDetail = { connectionId ->
                    navController.navigate(Screen.ConnectionDetail.createRoute(connectionId))
                },
                onNavigateToCreateInvitation = {
                    navController.navigate(Screen.CreateInvitation.route)
                },
                onNavigateToScanInvitation = {
                    navController.navigate(Screen.ScanInvitation.route)
                },
                onNavigateToConversation = { connectionId ->
                    navController.navigate(Screen.Conversation.createRoute(connectionId))
                },
                onNavigateToHandlerDetail = { handlerId ->
                    navController.navigate(Screen.HandlerDetail.createRoute(handlerId))
                },
                onNavigateToPersonalData = {
                    navController.navigate(Screen.PersonalData.route)
                },
                onNavigateToSecrets = {
                    navController.navigate(Screen.Secrets.route)
                },
                onNavigateToArchive = {
                    navController.navigate(Screen.Archive.route)
                },
                onNavigateToPreferences = {
                    navController.navigate(Screen.VaultPreferences.route)
                },
                onNavigateToDeployVault = {
                    navController.navigate(Screen.DeployVault.route)
                },
                onNavigateToPinSetup = {
                    navController.navigate(Screen.PinSetup.route)
                },
                onNavigateToCredentialDebug = {
                    navController.navigate(Screen.CredentialDebug.route)
                },
                onNavigateToProposals = {
                    navController.navigate(Screen.Proposals.route)
                },
                onNavigateToMyVotes = {
                    navController.navigate(Screen.MyVotes.route)
                },
                onSignOut = {
                    appViewModel.signOut()
                }
            )
        }
        composable(Screen.HandlerDiscovery.route) {
            HandlerDiscoveryScreen(
                onHandlerSelected = { handlerId ->
                    navController.navigate(Screen.HandlerDetail.createRoute(handlerId))
                },
                onNavigateBack = { navController.popBackStack() },
                onRequireAuth = {
                    // TODO: Handle auth requirement
                }
            )
        }
        composable(
            route = Screen.HandlerDetail.route,
            arguments = listOf(navArgument("handlerId") { type = NavType.StringType })
        ) {
            HandlerDetailScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToExecution = { handlerId ->
                    navController.navigate(Screen.HandlerExecution.createRoute(handlerId))
                },
                onRequireAuth = {
                    // TODO: Handle auth requirement
                }
            )
        }
        composable(
            route = Screen.HandlerExecution.route,
            arguments = listOf(navArgument("handlerId") { type = NavType.StringType })
        ) {
            HandlerExecutionScreen(
                onNavigateBack = { navController.popBackStack() },
                onRequireAuth = {
                    // TODO: Handle auth requirement
                }
            )
        }
        // Connections routes
        composable(Screen.Connections.route) {
            ConnectionsScreen(
                onConnectionClick = { connectionId ->
                    navController.navigate(Screen.ConnectionDetail.createRoute(connectionId))
                },
                onCreateInvitation = {
                    navController.navigate(Screen.CreateInvitation.route)
                },
                onScanInvitation = {
                    navController.navigate(Screen.ScanInvitation.route)
                }
            )
        }
        composable(Screen.CreateInvitation.route) {
            CreateInvitationScreen(
                onInvitationCreated = { /* Handle invitation created */ },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.ScanInvitation.route,
            arguments = listOf(navArgument("data") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            })
        ) { backStackEntry ->
            val data = backStackEntry.arguments?.getString("data")?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            }
            ScanInvitationScreen(
                initialData = data,
                onConnectionEstablished = { connectionId ->
                    navController.navigate(Screen.ConnectionDetail.createRoute(connectionId)) {
                        popUpTo(Screen.Connections.route)
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.ConnectionDetail.route,
            arguments = listOf(navArgument("connectionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val connectionId = backStackEntry.arguments?.getString("connectionId") ?: return@composable
            ConnectionDetailScreen(
                onMessageClick = {
                    navController.navigate(Screen.Conversation.createRoute(connectionId))
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.Conversation.route,
            arguments = listOf(navArgument("connectionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val connectionId = backStackEntry.arguments?.getString("connectionId") ?: return@composable
            ConversationScreen(
                onBack = { navController.popBackStack() },
                onConnectionDetail = {
                    navController.navigate(Screen.ConnectionDetail.createRoute(connectionId))
                }
            )
        }
        // Profile route
        composable(Screen.Profile.route) {
            ProfileScreen(
                onBack = { navController.popBackStack() }
            )
        }
        // Backup routes
        composable(Screen.Backups.route) {
            BackupListScreen(
                onBackupClick = { backupId ->
                    navController.navigate(Screen.BackupDetail.createRoute(backupId))
                },
                onSettingsClick = {
                    navController.navigate(Screen.BackupSettings.route)
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.BackupSettings.route) {
            BackupSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.BackupDetail.route,
            arguments = listOf(navArgument("backupId") { type = NavType.StringType })
        ) {
            BackupDetailScreen(
                onRestoreComplete = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Backups.route) { inclusive = true }
                    }
                },
                onDeleted = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.ProteanRecovery.route) {
            ProteanRecoveryScreen(
                onRecoveryComplete = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        // More menu screens
        composable(Screen.PersonalData.route) {
            PersonalDataScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Secrets.route) {
            SecretsScreenFull(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Archive.route) {
            ArchiveScreenFull(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.VaultPreferences.route) {
            VaultPreferencesScreenFull(
                onBack = { navController.popBackStack() }
            )
        }
        // App Lock & Setup routes
        composable(Screen.AppLock.route) {
            AppLockScreen(
                onUnlock = { navController.popBackStack() },
                onBiometricAuth = { /* Trigger biometric prompt */ }
            )
        }
        composable(Screen.PinSetup.route) {
            PinSetupScreen(
                onPinCreated = { pin ->
                    // In production, save PIN securely
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.FirstTimeSetup.route) {
            FirstTimeSetupScreen(
                onSetupComplete = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.FirstTimeSetup.route) { inclusive = true }
                    }
                },
                onEnableBiometrics = { /* Trigger biometric enrollment */ },
                onEnableNotifications = { /* Request notification permission */ },
                onSetupPin = {
                    navController.navigate(Screen.PinSetup.route)
                }
            )
        }
        composable(Screen.DeployVault.route) {
            DeployVaultScreen(
                onDeploymentComplete = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.DeployVault.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        // Call screens
        composable(Screen.IncomingCall.route) {
            IncomingCallScreen(
                onDismiss = { navController.popBackStack() }
            )
        }
        composable(Screen.OutgoingCall.route) {
            OutgoingCallScreen(
                onDismiss = { navController.popBackStack() }
            )
        }
        composable(Screen.ActiveCall.route) {
            ActiveCallScreen(
                onDismiss = { navController.popBackStack() }
            )
        }
        // Debug screens
        composable(Screen.CredentialDebug.route) {
            CredentialDebugScreen(
                onBack = { navController.popBackStack() }
            )
        }
        // Voting screens (Issue #50)
        composable(Screen.Proposals.route) {
            ProposalsScreen(
                onNavigateToProposal = { proposalId ->
                    navController.navigate(Screen.ProposalDetail.createRoute(proposalId))
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.ProposalDetail.route,
            arguments = listOf(navArgument("proposalId") { type = NavType.StringType })
        ) { backStackEntry ->
            val proposalId = backStackEntry.arguments?.getString("proposalId") ?: return@composable
            ProposalDetailScreen(
                proposalId = proposalId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToMyVotes = {
                    navController.navigate(Screen.MyVotes.route)
                }
            )
        }
        composable(Screen.MyVotes.route) {
            MyVotesScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToProposal = { proposalId ->
                    navController.navigate(Screen.ProposalDetail.createRoute(proposalId))
                }
            )
        }
        // Transfer screens (Issue #31: Device-to-device credential transfer)
        composable(Screen.TransferRequest.route) {
            TransferRequestScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToMain = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
        composable(
            route = Screen.TransferApproval.route,
            arguments = listOf(navArgument("transferId") { type = NavType.StringType })
        ) { backStackEntry ->
            val transferId = backStackEntry.arguments?.getString("transferId") ?: return@composable
            TransferApprovalScreen(
                transferId = transferId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        // Post-Enrollment verification screen
        composable(Screen.PostEnrollment.route) {
            PostEnrollmentScreen(
                onVerificationComplete = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.PostEnrollment.route) { inclusive = true }
                    }
                },
                onNavigateToPersonalData = {
                    // TODO: Navigate to personal data collection (Phase 2)
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.PostEnrollment.route) { inclusive = true }
                    }
                },
                onNavigateToMain = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.PostEnrollment.route) { inclusive = true }
                    }
                }
            )
        }
    }
}

@Composable
fun WelcomeScreen(
    onScanQR: () -> Unit,
    onRecoverAccount: () -> Unit = {}
) {
    val context = LocalContext.current
    val iconBitmap = remember {
        context.assets.open("vettid-icon-1024.png").use { inputStream ->
            BitmapFactory.decodeStream(inputStream)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Image(
            bitmap = iconBitmap.asImageBitmap(),
            contentDescription = "VettID",
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(16.dp))
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Welcome to VettID",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Control your data.\nSecure your keys.\nOwn your future.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onScanQR,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Scan QR Code")
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(
            onClick = onRecoverAccount,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Recover Account")
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}


/**
 * App unlock authentication screen with biometric support.
 * Uses BiometricAuthManager for fingerprint/face authentication with device credential fallback.
 */
@Composable
fun AuthenticationScreen(
    onAuthenticated: () -> Unit,
    biometricAuthManager: BiometricAuthManager = hiltViewModel<UnlockViewModel>().biometricAuthManager
) {
    val context = LocalContext.current
    val activity = context as? androidx.fragment.app.FragmentActivity

    var authState by remember { mutableStateOf<UnlockAuthState>(UnlockAuthState.Idle) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Attempt biometric auth on screen load
    LaunchedEffect(Unit) {
        if (activity != null && biometricAuthManager.isBiometricAvailable()) {
            authState = UnlockAuthState.Authenticating
            val result = biometricAuthManager.authenticateWithFallback(
                activity = activity,
                title = "Unlock VettID",
                subtitle = "Use biometric or device credential"
            )
            when (result) {
                is com.vettid.app.features.auth.BiometricAuthResult.Success -> {
                    authState = UnlockAuthState.Success
                    onAuthenticated()
                }
                is com.vettid.app.features.auth.BiometricAuthResult.Error -> {
                    authState = UnlockAuthState.Failed
                    errorMessage = result.message
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon based on state
        Icon(
            imageVector = when (authState) {
                is UnlockAuthState.Success -> Icons.Default.CheckCircle
                is UnlockAuthState.Failed -> Icons.Default.Error
                else -> Icons.Default.Fingerprint
            },
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = when (authState) {
                is UnlockAuthState.Success -> MaterialTheme.colorScheme.primary
                is UnlockAuthState.Failed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = when (authState) {
                is UnlockAuthState.Authenticating -> "Authenticating..."
                is UnlockAuthState.Success -> "Authenticated"
                is UnlockAuthState.Failed -> "Authentication Failed"
                else -> "Unlock VettID"
            },
            style = MaterialTheme.typography.headlineMedium
        )

        // Error message
        errorMessage?.let { msg ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = msg,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Loading indicator when authenticating
        if (authState is UnlockAuthState.Authenticating) {
            CircularProgressIndicator()
        } else {
            // Retry button
            Button(
                onClick = {
                    errorMessage = null
                    if (activity != null) {
                        authState = UnlockAuthState.Authenticating
                        kotlinx.coroutines.MainScope().launch {
                            val result = biometricAuthManager.authenticateWithFallback(
                                activity = activity,
                                title = "Unlock VettID",
                                subtitle = "Use biometric or device credential"
                            )
                            when (result) {
                                is com.vettid.app.features.auth.BiometricAuthResult.Success -> {
                                    authState = UnlockAuthState.Success
                                    onAuthenticated()
                                }
                                is com.vettid.app.features.auth.BiometricAuthResult.Error -> {
                                    authState = UnlockAuthState.Failed
                                    errorMessage = result.message
                                }
                            }
                        }
                    } else {
                        // Fallback for non-FragmentActivity contexts (shouldn't happen)
                        onAuthenticated()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(Icons.Default.Fingerprint, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (authState is UnlockAuthState.Failed) "Try Again" else "Unlock with Biometrics")
            }
        }

        // Biometric availability warning
        if (!biometricAuthManager.isBiometricAvailable()) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Biometric authentication not available. Please set up fingerprint or face unlock in device settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

/**
 * State for the unlock screen.
 */
sealed class UnlockAuthState {
    object Idle : UnlockAuthState()
    object Authenticating : UnlockAuthState()
    object Success : UnlockAuthState()
    object Failed : UnlockAuthState()
}

/**
 * Simple ViewModel to provide BiometricAuthManager via Hilt.
 */
@dagger.hilt.android.lifecycle.HiltViewModel
class UnlockViewModel @javax.inject.Inject constructor(
    val biometricAuthManager: BiometricAuthManager
) : androidx.lifecycle.ViewModel()

/**
 * Main screen with drawer + contextual bottom navigation pattern.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToHandlers: () -> Unit = {},
    onNavigateToConnections: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToBackups: () -> Unit = {},
    onNavigateToProteanRecovery: () -> Unit = {},
    onNavigateToConnectionDetail: (String) -> Unit = {},
    onNavigateToCreateInvitation: () -> Unit = {},
    onNavigateToScanInvitation: () -> Unit = {},
    onNavigateToConversation: (String) -> Unit = {},
    onNavigateToHandlerDetail: (String) -> Unit = {},
    onNavigateToPersonalData: () -> Unit = {},
    onNavigateToSecrets: () -> Unit = {},
    onNavigateToArchive: () -> Unit = {},
    onNavigateToPreferences: () -> Unit = {},
    onNavigateToDeployVault: () -> Unit = {},
    onNavigateToPinSetup: () -> Unit = {},
    onNavigateToCredentialDebug: () -> Unit = {},
    onNavigateToProposals: () -> Unit = {},
    onNavigateToMyVotes: () -> Unit = {},
    onSignOut: () -> Unit = {},
    appViewModel: AppViewModel = hiltViewModel()
) {
    var navigationState by remember { mutableStateOf(NavigationState()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val appState by appViewModel.appState.collectAsState()

    // Connection details dialog state
    var showConnectionDetailsDialog by remember { mutableStateOf(false) }

    // Show connection details dialog
    if (showConnectionDetailsDialog) {
        NatsConnectionDetailsDialog(
            connectionState = appState.natsConnectionState,
            connectionDetails = NatsConnectionDetails(
                endpoint = appState.natsEndpoint,
                ownerSpaceId = appState.natsOwnerSpaceId,
                messageSpaceId = appState.natsMessageSpaceId,
                credentialsExpiry = appState.natsCredentialsExpiry
            ),
            errorMessage = appState.natsError,
            onRetry = { appViewModel.retryNatsConnection() },
            onRefreshCredentials = { appViewModel.refreshNatsCredentials() },
            onDismiss = { showConnectionDetailsDialog = false }
        )
    }

    MainScaffold(
        navigationState = navigationState,
        onNavigationStateChange = { navigationState = it },
        userName = "VettID User",
        userEmail = "",
        vaultStatus = com.vettid.app.ui.navigation.VaultStatus.ACTIVE,
        // NATS connection state
        natsConnectionState = appState.natsConnectionState,
        natsErrorMessage = appState.natsError,
        onNatsRetry = { appViewModel.retryNatsConnection() },
        onNatsStatusClick = { showConnectionDetailsDialog = true },
        onSignOutVaultOnly = { /* Lock vault only */ },
        onSignOutVaultServices = onSignOut,
        onHeaderAction = {
            // Handle header action based on current screen
            when (navigationState.currentSection) {
                AppSection.VAULT -> when (navigationState.vaultTab) {
                    VaultTab.CONNECTIONS -> onNavigateToCreateInvitation()
                    VaultTab.FEED -> { /* No action for feed */ }
                    VaultTab.MORE -> { }
                }
                AppSection.VAULT_SERVICES -> when (navigationState.vaultServicesTab) {
                    VaultServicesTab.STATUS -> { /* Refresh vault status */ }
                    VaultServicesTab.BACKUPS -> { /* Backup actions */ }
                    VaultServicesTab.MANAGE -> { /* Manage vault actions */ }
                }
                AppSection.APP_SETTINGS -> { /* No header actions for settings */ }
            }
        },
        onSearchClick = {
            // Handle search based on current screen
        },
        onNavigateToPersonalData = onNavigateToPersonalData,
        onNavigateToSecrets = onNavigateToSecrets,
        onNavigateToArchive = onNavigateToArchive,
        onNavigateToVoting = onNavigateToProposals,
        onNavigateToPreferences = onNavigateToPreferences,
        // Badge counts - in production these would come from ViewModel/state
        pendingConnectionsCount = 2, // Demo: 2 pending connection requests
        unreadFeedCount = 5, // Demo: 5 unread feed items
        // Vault section content
        vaultConnectionsContent = {
            ConnectionsContentEmbedded(
                onConnectionClick = onNavigateToConnectionDetail,
                onCreateInvitation = onNavigateToCreateInvitation,
                onScanInvitation = onNavigateToScanInvitation
            )
        },
        vaultFeedContent = {
            FeedContent(
                onNavigateToConversation = onNavigateToConversation,
                onNavigateToHandler = onNavigateToHandlerDetail,
                onNavigateToBackup = { onNavigateToBackups() }
            )
        },
        // Vault Services section content
        vaultServicesStatusContent = {
            VaultStatusContentEmbedded()
        },
        vaultServicesBackupsContent = {
            VaultServicesBackupsContent()
        },
        vaultServicesManageContent = {
            VaultServicesManageContent()
        },
        // App Settings section content
        appSettingsGeneralContent = {
            AppSettingsGeneralContent(
                onNavigateToCredentialDebug = onNavigateToCredentialDebug
            )
        },
        appSettingsSecurityContent = {
            AppSettingsSecurityContent()
        },
        appSettingsBackupContent = {
            AppSettingsBackupContent()
        },
        snackbarHostState = snackbarHostState
    )
}

/**
 * Embedded vault status content without Scaffold.
 */
@Composable
private fun VaultStatusContentEmbedded() {
    // For now, a placeholder that links to the full vault status screen
    PlaceholderSection(
        icon = Icons.Default.Dashboard,
        title = "Vault Status",
        description = "View and manage your vault instance status, health metrics, and quick actions."
    )
}

/**
 * Vault Services Backups content.
 * Per mobile-ui-plan.md Section 3.4.4
 */
@Composable
private fun VaultServicesBackupsContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Credential Backup Section
        BackupSection(
            title = "CREDENTIAL BACKUP",
            icon = Icons.Default.Key,
            description = "Back up your vault credential to restore access if you lose your device.",
            actionLabel = "Backup Credential",
            onAction = { /* TODO: Navigate to credential backup */ }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Vault Backup Section
        BackupSection(
            title = "VAULT DATA",
            icon = Icons.Default.Storage,
            description = "Export encrypted vault data for safekeeping. Last backup: Never",
            actionLabel = "Create Backup",
            onAction = { /* TODO: Create vault backup */ }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Restore Section
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Restore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Restore from Backup",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Import from a backup file",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun BackupSection(
    title: String,
    icon: ImageVector,
    description: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/**
 * Vault Services Manage content.
 * Per mobile-ui-plan.md Section 3.4.3
 */
@Composable
private fun VaultServicesManageContent() {
    var showStopDialog by remember { mutableStateOf(false) }
    var showTerminateDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Status indicator
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Vault Running",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Instance: i-abc123def456",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "ACTIONS",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Stop/Start button
        ManageActionCard(
            icon = Icons.Default.Pause,
            title = "Stop Vault",
            description = "Stop the vault instance to reduce costs. Data is preserved.",
            buttonText = "Stop",
            onClick = { showStopDialog = true }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Restart button
        ManageActionCard(
            icon = Icons.Default.Refresh,
            title = "Restart Vault",
            description = "Restart the vault instance. Brief downtime expected.",
            buttonText = "Restart",
            onClick = { /* TODO: Restart vault */ }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Danger zone
        Text(
            text = "DANGER ZONE",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Terminate Vault",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Permanently delete vault instance and all data",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { showTerminateDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Terminate")
                }
            }
        }
    }

    // Stop confirmation dialog
    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            icon = { Icon(Icons.Default.Pause, null) },
            title = { Text("Stop Vault?") },
            text = { Text("Your vault will be stopped. You won't be able to sync data until you start it again.") },
            confirmButton = {
                Button(onClick = { showStopDialog = false }) {
                    Text("Stop")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Terminate confirmation dialog
    if (showTerminateDialog) {
        AlertDialog(
            onDismissRequest = { showTerminateDialog = false },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Terminate Vault?") },
            text = { Text("This action cannot be undone. All vault data will be permanently deleted. Make sure you have a backup.") },
            confirmButton = {
                Button(
                    onClick = { showTerminateDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Terminate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTerminateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ManageActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    buttonText: String,
    onClick: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onClick) {
                    Text(buttonText)
                }
            }
        }
    }
}

@Composable
private fun PlaceholderSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// Placeholder screens for "More" menu items

@Composable
fun PersonalDataScreen(onBack: () -> Unit) {
    PlaceholderScreenWithBack(
        title = "Personal Data",
        icon = Icons.Default.Person,
        description = "Manage your personal data including public info, private details, and keys.",
        onBack = onBack
    )
}

@Composable
fun SecretsScreen(onBack: () -> Unit) {
    PlaceholderScreenWithBack(
        title = "Secrets",
        icon = Icons.Default.Lock,
        description = "Store and manage sensitive secrets with password-only access.",
        onBack = onBack
    )
}

@Composable
fun DocumentsScreen(onBack: () -> Unit) {
    PlaceholderScreenWithBack(
        title = "Documents",
        icon = Icons.Default.Description,
        description = "Store and manage encrypted documents in your vault.",
        onBack = onBack
    )
}

@Composable
fun VaultCredentialsScreen(onBack: () -> Unit) {
    PlaceholderScreenWithBack(
        title = "Credentials",
        icon = Icons.Default.Key,
        description = "Manage your stored credentials and authentication data.",
        onBack = onBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceholderScreenWithBack(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Coming soon",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

