package com.vettid.app

import android.Manifest
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.vettid.app.features.applock.AppLockScreen
import com.vettid.app.features.applock.PinSetupScreen
import com.vettid.app.features.calling.ActiveCallScreen
import com.vettid.app.features.calling.CallHistoryScreen
import com.vettid.app.features.calling.CallManager
import com.vettid.app.features.calling.CallUIEvent
import com.vettid.app.features.calling.IncomingCallScreen
import com.vettid.app.features.calling.OutgoingCallScreen
import com.vettid.app.features.connections.*
import com.vettid.app.features.auth.BiometricAuthManager
import com.vettid.app.features.enrollment.EnrollmentScreen
import com.vettid.app.features.feed.FeedContent
import com.vettid.app.features.archive.ArchiveScreenFull
import com.vettid.app.features.archive.ArchiveContent
import com.vettid.app.features.secrets.SecretsScreenFull
import com.vettid.app.features.secrets.SecretsContent
import com.vettid.app.features.wallet.WalletListContentEmbedded
import com.vettid.app.features.wallet.WalletDetailScreen
import com.vettid.app.features.wallet.SendBtcScreen
import com.vettid.app.features.wallet.ReceiveBtcScreen
import com.vettid.app.features.secrets.AddSecretScreen
import com.vettid.app.features.setup.FirstTimeSetupScreen
import com.vettid.app.features.vault.DeployVaultScreen
import com.vettid.app.features.vault.VaultPreferencesScreenFull
import com.vettid.app.features.vault.VaultPreferencesContent
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
import com.vettid.app.ui.navigation.BadgeCountsViewModel
import com.vettid.app.ui.navigation.MainActivityScaffold
import com.vettid.app.ui.navigation.VaultScaffold
import com.vettid.app.ui.recovery.ProteanRecoveryScreen
import com.vettid.app.features.debug.CredentialDebugScreen
import com.vettid.app.features.voting.MyVotesScreen
import com.vettid.app.features.voting.ProposalDetailScreen
import com.vettid.app.features.voting.ProposalsScreen
import com.vettid.app.features.voting.ProposalsContent
import com.vettid.app.features.transfer.TransferRequestScreen
import com.vettid.app.features.transfer.TransferApprovalScreen
import com.vettid.app.features.postenrollment.PostEnrollmentScreen
import com.vettid.app.features.postenrollment.PersonalDataCollectionScreen
import com.vettid.app.features.migration.EmergencyRecoveryScreen
import com.vettid.app.features.migration.SecurityAuditLogScreen
import com.vettid.app.features.migration.VaultUpdateCard
import com.vettid.app.features.migration.VaultUpdateSuccessCard
import com.vettid.app.features.migration.VaultUpdatingInlineCard
import com.vettid.app.features.migration.VaultUpdateViewModel
import com.vettid.app.features.migration.VaultUpdateState
import com.vettid.app.features.enrollmentwizard.EnrollmentWizardScreen
import com.vettid.app.features.feed.GuideDetailScreen
import com.vettid.app.features.settings.AppDetailsScreen
import com.vettid.app.features.feed.NavigationTarget
import com.vettid.app.features.location.LocationHistoryScreen
import com.vettid.app.features.location.LocationSettingsScreen
import com.vettid.app.features.location.SharedLocationsScreen
import com.vettid.app.features.secrets.CriticalSecretsScreen
import com.vettid.app.features.unlock.PinUnlockScreen
import com.vettid.app.features.personaldata.PersonalDataContent
import com.vettid.app.features.services.AuditLogContent

private const val TAG = "VettIDApp"

/** Safe back navigation — falls back to Main if back stack is empty */
private fun NavHostController.safePopBackStack() {
    if (!popBackStack()) {
        navigate(Screen.Main.route) { popUpTo(0) { inclusive = true } }
    }
}

sealed class Screen(val route: String) {
    object Welcome : Screen("welcome")
    object Enrollment : Screen("enrollment?startWithManualEntry={startWithManualEntry}&initialCode={initialCode}") {
        fun createRoute(startWithManualEntry: Boolean = false, initialCode: String? = null): String {
            val encodedCode = initialCode?.let { java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
            return "enrollment?startWithManualEntry=$startWithManualEntry&initialCode=$encodedCode"
        }
    }
    object EnrollmentWizard : Screen("enrollment_wizard?startWithManualEntry={startWithManualEntry}&initialCode={initialCode}") {
        fun createRoute(startWithManualEntry: Boolean = false, initialCode: String? = null): String {
            val encodedCode = initialCode?.let { java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
            return "enrollment_wizard?startWithManualEntry=$startWithManualEntry&initialCode=$encodedCode"
        }
    }
    object Authentication : Screen("authentication")
    object Main : Screen("main")
    object VaultHome : Screen("vault-home")
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
    // Desktop device pairing
    object DevicePairing : Screen("devices/pair")
    object DeviceAuthorize : Screen("devices/authorize/{connectionId}") {
        fun createRoute(connectionId: String) = "devices/authorize/$connectionId"
    }
    object DevicesList : Screen("devices/list")
    object ScanInvitation : Screen("connections/scan-invitation?data={data}") {
        fun createRoute(data: String? = null) = if (data != null) {
            "connections/scan-invitation?data=${java.net.URLEncoder.encode(data, "UTF-8")}"
        } else {
            "connections/scan-invitation"
        }
    }
    object ConnectionReview : Screen("connections/review/{connectionId}?eventId={eventId}") {
        fun createRoute(connectionId: String, eventId: String? = null) =
            "connections/review/$connectionId" + if (eventId != null) "?eventId=$eventId" else ""
    }
    object ConnectionDetail : Screen("connections/{connectionId}") {
        fun createRoute(connectionId: String) = "connections/$connectionId"
    }
    object Conversation : Screen("connections/{connectionId}/messages") {
        fun createRoute(connectionId: String) = "connections/$connectionId/messages"
    }
    object ConnectionHistory : Screen("connections/{connectionId}/history") {
        fun createRoute(connectionId: String) = "connections/$connectionId/history"
    }
    // Profile
    object Profile : Screen("profile")
    // Vault More items
    object PersonalData : Screen("personal-data")
    object Secrets : Screen("secrets")
    object AddSecret : Screen("secrets/add?isCritical={isCritical}") {
        fun createRoute(isCritical: Boolean = false) = "secrets/add?isCritical=$isCritical"
    }
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
    object CallHistory : Screen("call/history")
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
    // Personal data collection (Phase 2 of post-enrollment flow)
    object PersonalDataCollection : Screen("personal-data-collection")
    // Migration screens (Issue #18: Enclave migration support)
    object SecurityAuditLog : Screen("security-audit-log")
    object EmergencyRecovery : Screen("emergency-recovery")
    // Critical Secrets full screen
    object CriticalSecrets : Screen("critical-secrets")
    // App details screen
    object AppDetails : Screen("app-details")
    object LocationHistory : Screen("location-history")
    object SharedLocations : Screen("shared-locations")
    object LocationSettings : Screen("location-settings")
    object VaultStatus : Screen("vault-status")
    // Agent connections
    object AgentManagement : Screen("agents")
    object AgentApproval : Screen("agents/approval/{requestId}") {
        fun createRoute(requestId: String) = "agents/approval/$requestId"
    }
    object CreateAgentInvitation : Screen("agents/create-invitation")
    // Wallet screens
    object WalletDetail : Screen("wallet/{walletId}") {
        fun createRoute(walletId: String) = "wallet/$walletId"
    }
    object SendBtc : Screen("wallet/send?walletId={walletId}&connectionId={connectionId}") {
        fun createRoute(walletId: String, connectionId: String = "") = "wallet/send?walletId=$walletId&connectionId=$connectionId"
    }
    object ReceiveBtc : Screen("wallet/receive/{walletId}") {
        fun createRoute(walletId: String) = "wallet/receive/$walletId"
    }
    // Guide detail screen
    object Guide : Screen("guide/{guideId}?eventId={eventId}&userName={userName}") {
        fun createRoute(guideId: String, eventId: String = "", userName: String = ""): String {
            val encodedName = java.net.URLEncoder.encode(userName, "UTF-8")
            return "guide/$guideId?eventId=$eventId&userName=$encodedName"
        }
    }
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
    var pendingVoteProposalId by remember { mutableStateOf<String?>(null) }
    // Notification tap: navigate to specific screen after PIN unlock
    var pendingNotificationEventType by remember { mutableStateOf<String?>(null) }
    var pendingNotificationSourceId by remember { mutableStateOf<String?>(null) }

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
            DeepLinkType.VOTE -> {
                // proposalId may be null (just navigate to proposals list)
                pendingVoteProposalId = deepLinkData.code ?: ""
                onDeepLinkConsumed()
            }
            DeepLinkType.NOTIFICATION -> {
                pendingNotificationEventType = deepLinkData.eventType
                pendingNotificationSourceId = deepLinkData.sourceId
                onDeepLinkConsumed()
            }
            DeepLinkType.NONE -> { /* No deep link */ }
        }
    }

    // Handle navigation based on app state and pending deep links
    LaunchedEffect(appState, pendingEnrollData, pendingConnectData, pendingTransferApprovalId, pendingVoteProposalId, pendingNotificationSourceId) {
        // Handle pending enrollment - takes priority
        if (pendingEnrollData != null) {
            val code = pendingEnrollData
            pendingEnrollData = null
            navController.navigate(Screen.EnrollmentWizard.createRoute(startWithManualEntry = true, initialCode = code)) {
                popUpTo(0) { inclusive = true }
            }
            return@LaunchedEffect
        }

        // Get current route to avoid interrupting post-enrollment verification
        val currentRoute = navController.currentBackStackEntry?.destination?.route

        when {
            !appState.hasCredential -> navController.navigate(Screen.Welcome.route) {
                popUpTo(0) { inclusive = true }
            }
            !appState.isAuthenticated -> {
                // Don't navigate to Authentication if already there or on special screens
                // Re-navigating to Authentication would destroy the ViewModel and cancel PIN verification
                if (currentRoute != Screen.Authentication.route &&
                    currentRoute != Screen.PostEnrollment.route &&
                    currentRoute != Screen.PersonalDataCollection.route) {
                    navController.navigate(Screen.Authentication.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
            else -> {
                // Don't navigate away if user is on post-enrollment screens
                // They need to complete their setup flow first
                if (currentRoute == Screen.PostEnrollment.route ||
                    currentRoute == Screen.PersonalDataCollection.route) {
                    return@LaunchedEffect
                }

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
                // Check for pending vote deep link (Issue #50: Vault-based voting)
                else if (pendingVoteProposalId != null) {
                    val proposalId = pendingVoteProposalId
                    pendingVoteProposalId = null
                    navController.navigate(Screen.Main.route) {
                        popUpTo(0) { inclusive = true }
                    }
                    if (!proposalId.isNullOrEmpty()) {
                        navController.navigate(Screen.ProposalDetail.createRoute(proposalId))
                    } else {
                        navController.navigate(Screen.Proposals.route)
                    }
                }
                // Check for pending notification navigation (tapped a notification)
                else if (pendingNotificationSourceId != null) {
                    val eventType = pendingNotificationEventType
                    val sourceId = pendingNotificationSourceId!!
                    pendingNotificationEventType = null
                    pendingNotificationSourceId = null
                    navController.navigate(Screen.Main.route) {
                        popUpTo(0) { inclusive = true }
                    }
                    // Navigate based on event type
                    when {
                        eventType?.startsWith("message.") == true ->
                            navController.navigate(Screen.Conversation.createRoute(sourceId))
                        eventType?.startsWith("agent.message") == true ->
                            navController.navigate(Screen.Conversation.createRoute(sourceId))
                        eventType?.startsWith("connection.") == true ->
                            navController.navigate(Screen.ConnectionDetail.createRoute(sourceId))
                        eventType?.startsWith("call.") == true ->
                            navController.navigate(Screen.Conversation.createRoute(sourceId))
                        else ->
                            navController.navigate(Screen.Conversation.createRoute(sourceId))
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
                    // Only navigate to Main from auth screens (Welcome, Authentication)
                    // Do NOT navigate if user is already past auth — any appState change
                    // (photo, UTKs, NATS reconnect) would wipe their current screen
                    if (currentRoute == Screen.Authentication.route ||
                        currentRoute == Screen.Welcome.route) {
                        navController.navigate(Screen.Main.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            }
        }
    }

    // Pick the right start destination based on synchronously-initialized
    // appState (hasCredential is read from CredentialStore in the ViewModel
    // ctor). Avoids a one-frame Welcome-screen flash on launches where the
    // user is already enrolled and only needs to PIN-unlock.
    val initialDestination = remember {
        when {
            !appState.hasCredential -> Screen.Welcome.route
            !appState.isAuthenticated -> Screen.Authentication.route
            else -> Screen.Main.route
        }
    }

    NavHost(
        navController = navController,
        startDestination = initialDestination
    ) {
        composable(Screen.Welcome.route) {
            WelcomeScreen(
                onScanQR = { navController.navigate(Screen.EnrollmentWizard.createRoute(startWithManualEntry = false)) },
                onEnterCode = { navController.navigate(Screen.EnrollmentWizard.createRoute(startWithManualEntry = true)) },
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
                    // Refresh credential status but DON'T set authenticated yet
                    // User needs to verify password on PostEnrollmentScreen first
                    // This unlocks the vault before NATS auto-connect attempts vault operations
                    appViewModel.refreshCredentialStatus()
                    // Navigate to post-enrollment verification
                    navController.navigate(Screen.PostEnrollment.route) {
                        popUpTo(Screen.Enrollment.route) { inclusive = true }
                    }
                },
                onCancel = {
                    navController.navigate(Screen.Welcome.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                startWithManualEntry = startWithManualEntry,
                initialCode = initialCode
            )
        }
        // New unified enrollment wizard
        composable(
            route = Screen.EnrollmentWizard.route,
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
            ),
            deepLinks = listOf(
                navDeepLink { uriPattern = "vettid://enroll/{initialCode}" }
            )
        ) { backStackEntry ->
            val startWithManualEntry = backStackEntry.arguments?.getBoolean("startWithManualEntry") ?: false
            val initialCode = backStackEntry.arguments?.getString("initialCode")?.let {
                if (it.isNotEmpty()) java.net.URLDecoder.decode(it, "UTF-8") else null
            }
            EnrollmentWizardScreen(
                onWizardComplete = {
                    // Wizard handles full flow including verification and personal data
                    // Set authenticated and go to main
                    appViewModel.refreshCredentialStatus()
                    appViewModel.refreshUserProfile()
                    appViewModel.setAuthenticated(true)
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.EnrollmentWizard.route) { inclusive = true }
                    }
                },
                onCancel = {
                    navController.navigate(Screen.Welcome.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                startWithManualEntry = startWithManualEntry,
                initialCode = initialCode
            )
        }
        composable(Screen.Authentication.route) {
            // Use PIN unlock with online/offline mode toggle
            PinUnlockScreen(
                onUnlocked = { offlineMode ->
                    appViewModel.setOfflineMode(offlineMode)
                    appViewModel.refreshUserProfile()
                    appViewModel.setAuthenticated(true)
                    // Start background service for reliable notifications
                    com.vettid.app.core.notifications.VaultProtectionService.start(navController.context)
                    // Schedule WorkManager fallback for when service is killed
                    com.vettid.app.core.notifications.FeedPollWorker.schedule(navController.context)
                }
            )
        }
        composable(Screen.Main.route) { backStackEntry ->
            // Handle navigation from guide screens via saved state
            val drawerItemResult = backStackEntry.savedStateHandle.get<String>("drawerItem")
            val openSettingsResult = backStackEntry.savedStateHandle.get<Boolean>("openSettings")

            MainScreen(
                initialDrawerItem = drawerItemResult,
                initialOpenSettings = openSettingsResult == true,
                onConsumeNavResult = {
                    backStackEntry.savedStateHandle.remove<String>("drawerItem")
                    backStackEntry.savedStateHandle.remove<Boolean>("openSettings")
                },
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
                onNavigateToConnectionHistory = { connectionId ->
                    navController.navigate(Screen.ConnectionHistory.createRoute(connectionId))
                },
                onNavigateToConnectionReview = { connectionId, eventId ->
                    navController.navigate(Screen.ConnectionReview.createRoute(connectionId, eventId))
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
                onNavigateToVaultHome = {
                    navController.navigate(Screen.VaultHome.route) { launchSingleTop = true }
                },
                onNavigateToProposals = {
                    navController.navigate(Screen.Proposals.route)
                },
                onNavigateToProposalDetail = { proposalId ->
                    navController.navigate(Screen.ProposalDetail.createRoute(proposalId))
                },
                onNavigateToMyVotes = {
                    navController.navigate(Screen.MyVotes.route)
                },
                onNavigateToSecurityAuditLog = {
                    navController.navigate(Screen.SecurityAuditLog.route)
                },
                onNavigateToGuide = { guideId, eventId, userName ->
                    navController.navigate(Screen.Guide.createRoute(guideId, eventId, userName))
                },
                onNavigateToCriticalSecrets = {
                    navController.navigate(Screen.CriticalSecrets.route)
                },
                onNavigateToAppDetails = {
                    navController.navigate(Screen.AppDetails.route)
                },
                onNavigateToLocationSettings = {
                    navController.navigate(Screen.LocationSettings.route)
                },
                onNavigateToAgents = {
                    navController.navigate(Screen.AgentManagement.route)
                },
                onNavigateToAgentApproval = { requestId ->
                    navController.navigate(Screen.AgentApproval.createRoute(requestId))
                },
                onNavigateToCreateAgentInvitation = {
                    navController.navigate(Screen.CreateAgentInvitation.route)
                },
                onNavigateToConnectDesktop = {
                    navController.navigate(Screen.DevicePairing.route)
                },
                onNavigateToVaultStatus = {
                    navController.navigate(Screen.VaultStatus.route)
                },
                appViewModel = appViewModel
            )
        }
        composable(Screen.HandlerDiscovery.route) {
            HandlerDiscoveryScreen(
                onHandlerSelected = { handlerId ->
                    navController.navigate(Screen.HandlerDetail.createRoute(handlerId))
                },
                onNavigateBack = { navController.safePopBackStack() },
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
                onNavigateBack = { navController.safePopBackStack() },
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
                onNavigateBack = { navController.safePopBackStack() },
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
                onBack = { navController.safePopBackStack() }
            )
        }
        composable(Screen.DevicePairing.route) {
            com.vettid.app.features.devices.DevicePairingScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onNavigateToAuthorize = { connectionId ->
                    navController.navigate(Screen.DeviceAuthorize.createRoute(connectionId)) {
                        popUpTo(Screen.DevicePairing.route) { inclusive = true }
                    }
                }
            )
        }
        composable(
            route = Screen.DeviceAuthorize.route,
            arguments = listOf(navArgument("connectionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val connectionId = backStackEntry.arguments?.getString("connectionId") ?: ""
            com.vettid.app.features.devices.AuthorizeDeviceScreen(
                connectionId = connectionId,
                onNavigateBack = { navController.safePopBackStack() }
            )
        }
        composable(Screen.DevicesList.route) {
            com.vettid.app.features.devices.DeviceManagementScreen(
                onNavigateToPairing = {
                    navController.navigate(Screen.DevicePairing.route)
                }
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
                onConnectionEstablished = { _ ->
                    // Navigate back to connections list after successful scan
                    navController.popBackStack(Screen.Connections.route, false)
                },
                onBack = { navController.safePopBackStack() }
            )
        }
        composable(
            route = Screen.ConnectionReview.route,
            arguments = listOf(
                navArgument("connectionId") { type = NavType.StringType },
                navArgument("eventId") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStackEntry ->
            val connectionId = backStackEntry.arguments?.getString("connectionId") ?: return@composable
            val eventId = backStackEntry.arguments?.getString("eventId")
            com.vettid.app.features.connections.ConnectionReviewScreen(
                onAccepted = {
                    navController.safePopBackStack()
                    navController.navigate(Screen.Conversation.createRoute(connectionId))
                },
                onDeclined = { navController.safePopBackStack() },
                onBack = { navController.safePopBackStack() }
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
                onSendBtc = { connId ->
                    navController.navigate(Screen.SendBtc.createRoute(walletId = "", connectionId = connId))
                },
                onBack = { navController.safePopBackStack() }
            )
        }
        composable(
            route = Screen.Conversation.route,
            arguments = listOf(navArgument("connectionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val connectionId = backStackEntry.arguments?.getString("connectionId") ?: return@composable
            ConversationScreen(
                onBack = { navController.safePopBackStack() },
                onConnectionDetail = {
                    navController.navigate(Screen.ConnectionDetail.createRoute(connectionId))
                },
                onPaymentRequest = { connId ->
                    navController.navigate(Screen.SendBtc.createRoute(walletId = "", connectionId = connId))
                }
            )
        }
        composable(
            route = Screen.ConnectionHistory.route,
            arguments = listOf(navArgument("connectionId") { type = NavType.StringType })
        ) {
            com.vettid.app.features.feed.ConnectionHistoryScreen(
                onBack = { navController.safePopBackStack() },
                onOpenConversation = { connId ->
                    navController.navigate(Screen.Conversation.createRoute(connId))
                },
                onOpenGuide = { guideId, userName ->
                    // Empty eventId — the audit row replaces the feed
                    // event; Screen.Guide accepts empty for this arg.
                    navController.navigate(Screen.Guide.createRoute(guideId, "", userName))
                }
            )
        }
        // Profile route
        composable(Screen.Profile.route) {
            ProfileScreen(
                onBack = { navController.safePopBackStack() }
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
                onBack = { navController.safePopBackStack() }
            )
        }
        composable(Screen.BackupSettings.route) {
            BackupSettingsScreen(
                onBack = { navController.safePopBackStack() }
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
                onDeleted = { navController.safePopBackStack() },
                onBack = { navController.safePopBackStack() }
            )
        }
        composable(Screen.ProteanRecovery.route) {
            ProteanRecoveryScreen(
                onRecoveryComplete = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack = { navController.safePopBackStack() }
            )
        }
        // More menu screens
        composable(Screen.PersonalData.route) {
            PersonalDataScreenFull(
                onBack = { navController.safePopBackStack() }
            )
        }
        composable(Screen.Secrets.route) {
            SecretsScreenFull(
                onBack = { navController.safePopBackStack() },
                onNavigateToAddSecret = {
                    navController.navigate(Screen.AddSecret.createRoute(isCritical = false))
                }
            )
        }
        composable(
            route = Screen.AddSecret.route,
            arguments = listOf(navArgument("isCritical") {
                type = NavType.BoolType
                defaultValue = false
            })
        ) { backStackEntry ->
            val isCritical = backStackEntry.arguments?.getBoolean("isCritical") ?: false
            AddSecretScreen(
                isCritical = isCritical,
                onBack = { navController.safePopBackStack() },
                onSecretAdded = {
                    if (isCritical) {
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set("secretAdded", true)
                    }
                    navController.safePopBackStack()
                }
            )
        }
        composable(Screen.Archive.route) {
            ArchiveScreenFull(
                onBack = { navController.safePopBackStack() }
            )
        }
        composable(Screen.VaultPreferences.route) {
            VaultPreferencesScreenFull(
                onBack = { navController.safePopBackStack() },
                onNavigateToAppDetails = { navController.navigate(Screen.AppDetails.route) },
                onNavigateToLocationHistory = { navController.navigate(Screen.LocationHistory.route) },
                onNavigateToSharedLocations = { navController.navigate(Screen.SharedLocations.route) },
                onNavigateToLocationSettings = { navController.navigate(Screen.LocationSettings.route) }
            )
        }
        composable(Screen.LocationHistory.route) {
            LocationHistoryScreen(
                onBack = { navController.safePopBackStack() }
            )
        }
        composable(Screen.SharedLocations.route) {
            SharedLocationsScreen(
                onBack = { navController.safePopBackStack() }
            )
        }
        composable(Screen.LocationSettings.route) {
            LocationSettingsScreen(
                onBack = { navController.safePopBackStack() },
                onNavigateToLocationHistory = { navController.navigate(Screen.LocationHistory.route) },
                onNavigateToSharedLocations = { navController.navigate(Screen.SharedLocations.route) }
            )
        }
        composable(Screen.AppDetails.route) {
            AppDetailsScreen(
                onBack = { navController.safePopBackStack() }
            )
        }
        composable(Screen.VaultStatus.route) {
            VaultStatusScreen(
                onBack = { navController.safePopBackStack() },
                onNavigateToEnrollment = {
                    navController.navigate(Screen.EnrollmentWizard.createRoute())
                },
                onRequireAuth = { /* Auth handled by app state */ },
                onNavigateToSettings = {
                    navController.safePopBackStack()
                }
            )
        }
        // App Lock & Setup routes
        composable(Screen.AppLock.route) {
            AppLockScreen(
                onUnlock = { navController.safePopBackStack() },
                onBiometricAuth = { /* Trigger biometric prompt */ }
            )
        }
        composable(Screen.PinSetup.route) {
            PinSetupScreen(
                onPinCreated = { pin ->
                    // In production, save PIN securely
                    navController.safePopBackStack()
                },
                onBack = { navController.safePopBackStack() }
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
                onBack = { navController.safePopBackStack() }
            )
        }
        // Call screens
        composable(Screen.IncomingCall.route) {
            IncomingCallScreen(
                onDismiss = { navController.safePopBackStack() }
            )
        }
        composable(Screen.OutgoingCall.route) {
            OutgoingCallScreen(
                onDismiss = { navController.safePopBackStack() }
            )
        }
        composable(Screen.ActiveCall.route) {
            ActiveCallScreen(
                onDismiss = { navController.safePopBackStack() }
            )
        }
        composable(Screen.CallHistory.route) {
            CallHistoryScreen(
                onBack = { navController.safePopBackStack() },
                onCallClick = { peerGuid ->
                    // Navigate to connection detail if we have a connection for this peer
                    navController.navigate(Screen.ConnectionDetail.createRoute(peerGuid))
                }
            )
        }
        // Debug screens
        composable(Screen.CredentialDebug.route) {
            CredentialDebugScreen(
                onBack = { navController.safePopBackStack() }
            )
        }
        // Vault Home screen (avatar tap)
        composable(Screen.VaultHome.route) { backStackEntry ->
            // Read initial vault segment from savedStateHandle (set by guide navigation)
            val initialSegmentName = backStackEntry.savedStateHandle.get<String>("initialVaultSegment")
            val initialSegment = initialSegmentName?.let {
                try { VaultSegment.valueOf(it) } catch (_: Exception) { null }
            }
            var vaultSegment by rememberSaveable { mutableStateOf(initialSegment ?: VaultSegment.DATA) }
            // Consume the initial segment so it doesn't re-apply on recomposition
            LaunchedEffect(initialSegmentName) {
                if (initialSegmentName != null) {
                    vaultSegment = initialSegment ?: VaultSegment.DATA
                    backStackEntry.savedStateHandle.remove<String>("initialVaultSegment")
                }
            }
            var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
            // Explicit back handler — close settings or pop
            androidx.activity.compose.BackHandler {
                if (isSettingsOpen) {
                    isSettingsOpen = false
                } else {
                    navController.safePopBackStack()
                }
            }
            VaultScaffold(
                vaultSegment = vaultSegment,
                onVaultSegmentChange = { vaultSegment = it },
                profilePhotoBase64 = appViewModel.appState.collectAsState().value.profilePhoto,
                natsConnectionState = appViewModel.appState.collectAsState().value.natsConnectionState,
                onBack = { if (isSettingsOpen) isSettingsOpen = false else navController.safePopBackStack() },
                onSettingsToggle = { isSettingsOpen = !isSettingsOpen },
                isSettingsOpen = isSettingsOpen,
                personalDataContent = { query -> PersonalDataContent(searchQuery = query) },
                secretsContent = { query ->
                    SecretsContentEmbedded(
                        searchQuery = query,
                        onSecretClick = { _ -> /* Secret detail view */ },
                        onNavigateToCriticalSecrets = { navController.navigate(Screen.CriticalSecrets.route) }
                    )
                },
                walletsContent = { query ->
                    WalletListContentEmbedded(
                        searchQuery = query,
                        onWalletClick = { walletId ->
                            navController.navigate(Screen.WalletDetail.createRoute(walletId))
                        },
                        onCreateWallet = { /* show CreateWalletSheet */ }
                    )
                },
                settingsContent = {
                    SettingsContent(
                        onNavigateToAppDetails = { navController.navigate(Screen.AppDetails.route) },
                        onNavigateToLocationSettings = { navController.navigate(Screen.LocationSettings.route) },
                        onNavigateToAgents = { navController.navigate(Screen.AgentManagement.route) },
                        onNavigateToVaultStatus = { navController.navigate(Screen.VaultStatus.route) },
                        onNavigateToSecurityAuditLog = { navController.navigate(Screen.SecurityAuditLog.route) }
                    )
                },
                onFabClick = {
                    when (vaultSegment) {
                        VaultSegment.DATA -> { /* Navigate to add data field */ }
                        VaultSegment.SECRETS -> navController.navigate(Screen.AddSecret.createRoute(isCritical = false))
                        VaultSegment.WALLETS -> { /* Navigate to create wallet */ }
                    }
                }
            )
        }
        // Wallet screens
        composable(
            route = Screen.WalletDetail.route,
            arguments = listOf(navArgument("walletId") { type = NavType.StringType })
        ) {
            WalletDetailScreen(
                onBack = { navController.safePopBackStack() },
                onSend = { wId -> navController.navigate(Screen.SendBtc.createRoute(wId)) },
                onReceive = { wId -> navController.navigate(Screen.ReceiveBtc.createRoute(wId)) }
            )
        }
        composable(
            route = Screen.SendBtc.route,
            arguments = listOf(
                navArgument("walletId") { type = NavType.StringType; defaultValue = "" },
                navArgument("connectionId") { type = NavType.StringType; defaultValue = "" }
            )
        ) {
            SendBtcScreen(
                onBack = { navController.safePopBackStack() }
            )
        }
        composable(
            route = Screen.ReceiveBtc.route,
            arguments = listOf(navArgument("walletId") { type = NavType.StringType })
        ) {
            ReceiveBtcScreen(
                onBack = { navController.safePopBackStack() }
            )
        }
        // Voting screens (Issue #50)
        composable(Screen.Proposals.route) {
            ProposalsScreen(
                onNavigateToProposal = { proposalId ->
                    navController.navigate(Screen.ProposalDetail.createRoute(proposalId))
                },
                onNavigateBack = { navController.safePopBackStack() }
            )
        }
        composable(
            route = Screen.ProposalDetail.route,
            arguments = listOf(navArgument("proposalId") { type = NavType.StringType })
        ) { backStackEntry ->
            val proposalId = backStackEntry.arguments?.getString("proposalId") ?: return@composable
            ProposalDetailScreen(
                proposalId = proposalId,
                onNavigateBack = { navController.safePopBackStack() },
                onNavigateToMyVotes = {
                    navController.navigate(Screen.MyVotes.route)
                }
            )
        }
        composable(Screen.MyVotes.route) {
            MyVotesScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onNavigateToProposal = { proposalId ->
                    navController.navigate(Screen.ProposalDetail.createRoute(proposalId))
                }
            )
        }
        // Transfer screens (Issue #31: Device-to-device credential transfer)
        composable(Screen.TransferRequest.route) {
            TransferRequestScreen(
                onNavigateBack = { navController.safePopBackStack() },
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
                onNavigateBack = { navController.safePopBackStack() }
            )
        }
        // Agent management
        composable(Screen.AgentManagement.route) {
            com.vettid.app.features.agents.AgentManagementScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onNavigateToCreateInvitation = {
                    navController.navigate(Screen.CreateAgentInvitation.route)
                }
            )
        }
        composable(
            route = Screen.AgentApproval.route,
            arguments = listOf(navArgument("requestId") { type = NavType.StringType })
        ) { backStackEntry ->
            val requestId = backStackEntry.arguments?.getString("requestId") ?: return@composable
            com.vettid.app.features.agents.AgentApprovalScreen(
                requestId = requestId,
                onNavigateBack = { navController.safePopBackStack() }
            )
        }
        composable(Screen.CreateAgentInvitation.route) {
            com.vettid.app.features.agents.CreateAgentInvitationScreen(
                onNavigateBack = { navController.safePopBackStack() }
            )
        }
        // Post-Enrollment verification screen
        composable(Screen.PostEnrollment.route) {
            PostEnrollmentScreen(
                onVerificationComplete = {
                    // Password verified - vault is now unlocked, safe to connect to NATS
                    appViewModel.setAuthenticated(true)
                    navController.navigate(Screen.PersonalDataCollection.route) {
                        popUpTo(Screen.PostEnrollment.route) { inclusive = true }
                    }
                },
                onNavigateToPersonalData = {
                    // Password verified - vault is now unlocked, safe to connect to NATS
                    appViewModel.setAuthenticated(true)
                    navController.navigate(Screen.PersonalDataCollection.route) {
                        popUpTo(Screen.PostEnrollment.route) { inclusive = true }
                    }
                },
                onNavigateToMain = {
                    // User skipped verification - vault is locked but allow app access
                    // NATS operations will fail until user authenticates
                    appViewModel.setAuthenticated(true)
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.PostEnrollment.route) { inclusive = true }
                    }
                }
            )
        }
        // Personal data collection screen (Phase 2)
        composable(Screen.PersonalDataCollection.route) {
            val context = LocalContext.current
            val notificationViewModel: NotificationViewModel = hiltViewModel()
            val appPreferencesStore = remember { com.vettid.app.core.storage.AppPreferencesStore(context) }

            // Request notification permission once after enrollment
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    notificationViewModel.feedNotificationService.showWelcomeNotification()
                }
            }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (!appPreferencesStore.hasRequestedNotificationPermission()) {
                        appPreferencesStore.setNotificationPermissionRequested(true)
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            PersonalDataCollectionScreen(
                onNavigateToMain = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.PersonalDataCollection.route) { inclusive = true }
                    }
                }
            )
        }
        // Security Audit Log screen (Issue #18: Enclave migration)
        composable(Screen.SecurityAuditLog.route) {
            SecurityAuditLogScreen(
                onBack = { navController.safePopBackStack() }
            )
        }
        // Emergency Recovery screen (Issue #18: Enclave migration - disaster scenario)
        composable(Screen.EmergencyRecovery.route) {
            EmergencyRecoveryScreen(
                onRecoveryComplete = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
        // Critical Secrets full screen
        composable(Screen.CriticalSecrets.route) {
            val criticalSecretsViewModel: com.vettid.app.features.secrets.CriticalSecretsViewModel = hiltViewModel()
            // Invalidate cache when returning from add secret screen
            val secretAdded = navController.currentBackStackEntry
                ?.savedStateHandle
                ?.getStateFlow("secretAdded", false)
                ?.collectAsState()
            LaunchedEffect(secretAdded?.value) {
                if (secretAdded?.value == true) {
                    criticalSecretsViewModel.invalidateCache()
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.set("secretAdded", false)
                }
            }
            CriticalSecretsScreen(
                viewModel = criticalSecretsViewModel,
                onBack = { navController.safePopBackStack() },
                onNavigateToAddSecret = {
                    navController.navigate(Screen.AddSecret.createRoute(isCritical = true))
                }
            )
        }
        // Guide detail screen
        composable(
            route = Screen.Guide.route,
            arguments = listOf(
                navArgument("guideId") { type = NavType.StringType },
                navArgument("eventId") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("userName") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val guideId = backStackEntry.arguments?.getString("guideId") ?: return@composable
            val eventId = backStackEntry.arguments?.getString("eventId") ?: ""
            val userName = backStackEntry.arguments?.getString("userName")?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            } ?: ""
            // Get FeedViewModel from Main route's scope so markAsRead updates the same cache
            val mainEntry = remember(navController) {
                try { navController.getBackStackEntry(Screen.Main.route) } catch (_: Exception) { null }
            }
            val feedViewModel: com.vettid.app.features.feed.FeedViewModel = if (mainEntry != null) {
                hiltViewModel(mainEntry)
            } else {
                hiltViewModel()
            }
            // Auto-mark guide as read when opened
            LaunchedEffect(eventId) {
                if (eventId.isNotEmpty()) {
                    feedViewModel.markAsRead(eventId)
                }
            }
            GuideDetailScreen(
                guideId = guideId,
                userName = userName,
                onBack = { navController.safePopBackStack() },
                onNavigate = { target ->
                    when (target) {
                        is NavigationTarget.DrawerNav -> {
                            val vaultItems = mapOf(
                                "PERSONAL_DATA" to VaultSegment.DATA.name,
                                "SECRETS" to VaultSegment.SECRETS.name,
                                "WALLETS" to VaultSegment.WALLETS.name
                            )
                            val vaultSegment = vaultItems[target.item.name]
                            if (vaultSegment != null) {
                                // Navigate to vault with the correct tab
                                navController.safePopBackStack()
                                navController.navigate(Screen.VaultHome.route) {
                                    launchSingleTop = true
                                }
                                navController.currentBackStackEntry
                                    ?.savedStateHandle
                                    ?.set("initialVaultSegment", vaultSegment)
                            } else {
                                // Activity drawer items (FEED, ARCHIVE, VOTING)
                                navController.previousBackStackEntry
                                    ?.savedStateHandle
                                    ?.set("drawerItem", target.item.name)
                                navController.safePopBackStack()
                            }
                        }
                        is NavigationTarget.ScreenNav -> {
                            // For settings, toggle settings overlay via saved state
                            if (target.route == "settings") {
                                navController.previousBackStackEntry
                                    ?.savedStateHandle
                                    ?.set("openSettings", true)
                                navController.safePopBackStack()
                            } else {
                                navController.safePopBackStack()
                                navController.navigate(target.route)
                            }
                        }
                    }
                },
                onMarkAsRead = {
                    if (eventId.isNotEmpty()) {
                        feedViewModel.markAsRead(eventId)
                    }
                },
                onArchive = {
                    if (eventId.isNotEmpty()) {
                        Log.d("GuideDetail", "Archiving guide eventId=$eventId guideId=$guideId")
                        feedViewModel.archiveEvent(eventId)
                    } else {
                        Log.w("GuideDetail", "Cannot archive guide: empty eventId for guideId=$guideId")
                    }
                }
            )
        }
    }
}

@Composable
fun WelcomeScreen(
    onScanQR: () -> Unit,
    onEnterCode: () -> Unit = {},
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

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onEnterCode,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Edit, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Enter Enrollment Code")
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
        if (activity != null && biometricAuthManager.isAuthenticationAvailable()) {
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

        // Authentication availability warning (biometric or device credential)
        if (!biometricAuthManager.isAuthenticationAvailable()) {
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
 * Simple ViewModel to provide FeedNotificationService for notification permission flow.
 */
@dagger.hilt.android.lifecycle.HiltViewModel
class NotificationViewModel @javax.inject.Inject constructor(
    val feedNotificationService: com.vettid.app.features.feed.FeedNotificationService
) : androidx.lifecycle.ViewModel()

/**
 * Main screen with drawer + contextual bottom navigation pattern.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    initialDrawerItem: String? = null,
    initialOpenSettings: Boolean = false,
    onConsumeNavResult: () -> Unit = {},
    onNavigateToHandlers: () -> Unit = {},
    onNavigateToConnections: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToBackups: () -> Unit = {},
    onNavigateToProteanRecovery: () -> Unit = {},
    onNavigateToConnectionDetail: (String) -> Unit = {},
    onNavigateToConnectionHistory: (String) -> Unit = {},
    onNavigateToConnectionReview: (connectionId: String, eventId: String) -> Unit = { _, _ -> },
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
    onNavigateToProposalDetail: (String) -> Unit = {},
    onNavigateToMyVotes: () -> Unit = {},
    onNavigateToSecretDetail: (String) -> Unit = {},
    onNavigateToSecurityAuditLog: () -> Unit = {},
    onNavigateToGuide: (guideId: String, eventId: String, userName: String) -> Unit = { _, _, _ -> },
    onNavigateToCriticalSecrets: () -> Unit = {},
    onNavigateToAppDetails: () -> Unit = {},
    onNavigateToLocationSettings: () -> Unit = {},
    onNavigateToAgents: () -> Unit = {},
    onNavigateToAgentApproval: (requestId: String) -> Unit = {},
    onNavigateToCreateAgentInvitation: () -> Unit = {},
    onNavigateToConnectDesktop: () -> Unit = {},
    onNavigateToVaultStatus: () -> Unit = {},
    onNavigateToVaultHome: () -> Unit = {},
    appViewModel: AppViewModel = hiltViewModel(),
    badgeCountsViewModel: BadgeCountsViewModel = hiltViewModel(),
    vaultUpdateViewModel: VaultUpdateViewModel = hiltViewModel()
) {
    // Check for vault security updates after vault unlock
    val vaultUpdateState by vaultUpdateViewModel.state.collectAsState()
    LaunchedEffect(Unit) {
        vaultUpdateViewModel.checkForUpdate()
    }

    // Handle "Review Details" — open browser
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    var activitySegment by rememberSaveable { mutableStateOf(ActivitySegment.FEED) }
    var isSettingsOpen by rememberSaveable { mutableStateOf(false) }

    // Handle navigation results from guide screens
    LaunchedEffect(initialDrawerItem, initialOpenSettings) {
        if (initialDrawerItem != null) {
            when (initialDrawerItem) {
                "FEED" -> { activitySegment = ActivitySegment.FEED; isSettingsOpen = false }
                "ARCHIVE" -> { activitySegment = ActivitySegment.ARCHIVE; isSettingsOpen = false }
                "VOTING" -> { activitySegment = ActivitySegment.VOTING; isSettingsOpen = false }
            }
            onConsumeNavResult()
        } else if (initialOpenSettings) {
            isSettingsOpen = true
            onConsumeNavResult()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val appState by appViewModel.appState.collectAsState()

    // Badge counts from real data
    val unreadFeedCount by badgeCountsViewModel.unreadFeedCount.collectAsState()
    val pendingConnectionsCount by badgeCountsViewModel.pendingConnectionsCount.collectAsState()
    val unvotedProposalsCount by badgeCountsViewModel.unvotedProposalsCount.collectAsState()

    // Badge counts (used by vault screen if needed)

    // Location tracking state
    val context = LocalContext.current
    val appPreferencesStore = remember { com.vettid.app.core.storage.AppPreferencesStore(context) }
    val isLocationTrackingEnabled = appPreferencesStore.isLocationTrackingEnabled()

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

    // Vault security update card (shown above main content)
    Column(modifier = Modifier.fillMaxSize()) {
        when (val updateState = vaultUpdateState) {
            is VaultUpdateState.UpdateAvailable -> {
                VaultUpdateCard(
                    config = updateState.config,
                    isMandatory = updateState.isMandatory,
                    isUpdating = false,
                    onUpdateNow = { vaultUpdateViewModel.startUpdate() },
                    onRemindLater = { vaultUpdateViewModel.remindLater() },
                    onReviewDetails = {
                        val url = updateState.config.detailsUrl
                        if (!url.isNullOrEmpty()) {
                            uriHandler.openUri(url)
                        }
                    }
                )
            }
            is VaultUpdateState.Updating -> {
                if (updateState.autoApplied) {
                    // Pre-PIN consent path — skip the action card so it
                    // doesn't flash between Checking and Updated.
                    VaultUpdatingInlineCard()
                } else {
                    VaultUpdateCard(
                        config = updateState.config,
                        isMandatory = true,
                        isUpdating = true,
                        onUpdateNow = {},
                        onRemindLater = {},
                        onReviewDetails = {}
                    )
                }
            }
            is VaultUpdateState.Updated -> {
                VaultUpdateSuccessCard(
                    onDismiss = { vaultUpdateViewModel.dismissSuccess() }
                )
            }
            is VaultUpdateState.Error -> {
                VaultUpdateCard(
                    config = updateState.config,
                    isMandatory = true,
                    isUpdating = false,
                    onUpdateNow = { vaultUpdateViewModel.retry() },
                    onRemindLater = {},
                    onReviewDetails = {}
                )
            }
            else -> { /* No update card for Checking/NoUpdate */ }
        }

    MainActivityScaffold(
        activitySegment = activitySegment,
        onActivitySegmentChange = { activitySegment = it },
        isSettingsOpen = isSettingsOpen,
        onSettingsToggle = { isSettingsOpen = !isSettingsOpen },
        profilePhotoBase64 = appState.profilePhoto,
        natsConnectionState = appState.natsConnectionState,
        onAvatarClick = onNavigateToVaultHome,
        // Activity content
        feedContent = { query ->
            FeedContent(
                searchQuery = query,
                onNavigateToConversation = onNavigateToConversation,
                onNavigateToConnectionDetail = onNavigateToConnectionDetail,
                onNavigateToConnectionHistory = onNavigateToConnectionHistory,
                onNavigateToHandler = onNavigateToHandlerDetail,
                onNavigateToBackup = { onNavigateToBackups() },
                onNavigateToGuide = onNavigateToGuide,
                onNavigateToAgentApproval = onNavigateToAgentApproval,
                onNavigateToConnectionReview = onNavigateToConnectionReview,
                onNavigateToCreateInvitation = onNavigateToCreateInvitation,
                onNavigateToScanInvitation = onNavigateToScanInvitation,
                onNavigateToCreateAgentInvitation = onNavigateToCreateAgentInvitation,
                onNavigateToConnectDesktop = onNavigateToConnectDesktop,
                onResurfaceVaultUpdate = { vaultUpdateViewModel.resurface() }
            )
        },
        votingContent = { _ ->
            ProposalsContent(
                onNavigateToProposal = { proposalId -> onNavigateToProposalDetail(proposalId) }
            )
        },
        archiveContent = { query ->
            ArchiveContentEmbedded(searchQuery = query)
        },
        settingsContent = {
            SettingsContent(
                onNavigateToAppDetails = onNavigateToAppDetails,
                onNavigateToLocationSettings = onNavigateToLocationSettings,
                onNavigateToAgents = onNavigateToAgents,
                onNavigateToVaultStatus = onNavigateToVaultStatus,
                onNavigateToSecurityAuditLog = onNavigateToSecurityAuditLog
            )
        },
        snackbarHostState = snackbarHostState
    )
    } // End Column wrapping update card + MainScaffold
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalDataScreenFull(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Personal Data") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            PersonalDataContent()
        }
    }
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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

// Embedded content composables for simplified navigation

@Composable
private fun SecretsContentEmbedded(
    searchQuery: String = "",
    onSecretClick: (String) -> Unit = {},
    onNavigateToCriticalSecrets: () -> Unit = {}
) {
    SecretsContent(searchQuery = searchQuery, onNavigateToCriticalSecrets = onNavigateToCriticalSecrets)
}

@Composable
private fun ArchiveContentEmbedded(searchQuery: String = "") {
    ArchiveContent(searchQuery = searchQuery)
}

// Wallet screen composables are defined in features/wallet/ package:
// WalletListContentEmbedded, WalletDetailScreen, SendBtcScreen, ReceiveBtcScreen

@Composable
private fun SettingsContent(
    onNavigateToAppDetails: () -> Unit = {},
    onNavigateToLocationSettings: () -> Unit = {},
    onNavigateToAgents: () -> Unit = {},
    onNavigateToVaultStatus: () -> Unit = {},
    onNavigateToSecurityAuditLog: () -> Unit = {}
) {
    VaultPreferencesContent(
        onNavigateToAppDetails = onNavigateToAppDetails,
        onNavigateToLocationSettings = onNavigateToLocationSettings,
        onNavigateToAgents = onNavigateToAgents,
        onNavigateToVaultStatus = onNavigateToVaultStatus,
        onNavigateToSecurityAuditLog = onNavigateToSecurityAuditLog
    )
}

