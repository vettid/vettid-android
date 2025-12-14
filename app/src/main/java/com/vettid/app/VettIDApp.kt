package com.vettid.app

import android.graphics.BitmapFactory
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.vettid.app.features.connections.*
import com.vettid.app.features.auth.BiometricAuthManager
import com.vettid.app.features.enrollment.EnrollmentScreen
import com.vettid.app.features.feed.FeedContent
import com.vettid.app.features.secrets.SecretsScreenFull
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
import com.vettid.app.ui.backup.CredentialBackupScreen
import com.vettid.app.ui.components.QrCodeScanner
import com.vettid.app.ui.navigation.*
import com.vettid.app.ui.recovery.CredentialRecoveryScreen

private const val TAG = "VettIDApp"

sealed class Screen(val route: String) {
    object Welcome : Screen("welcome")
    object Enrollment : Screen("enrollment?startWithManualEntry={startWithManualEntry}") {
        fun createRoute(startWithManualEntry: Boolean = false) = "enrollment?startWithManualEntry=$startWithManualEntry"
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
    object ScanInvitation : Screen("connections/scan-invitation")
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
    object Documents : Screen("documents")
    object VaultCredentials : Screen("vault-credentials")
    // Backup
    object Backups : Screen("backups")
    object BackupSettings : Screen("backups/settings")
    object BackupDetail : Screen("backups/{backupId}") {
        fun createRoute(backupId: String) = "backups/$backupId"
    }
    object CredentialBackup : Screen("backup/credential")
    object CredentialRecovery : Screen("recovery/credential")
}

@Composable
fun VettIDApp(
    navController: NavHostController = rememberNavController(),
    appViewModel: AppViewModel = hiltViewModel()
) {
    val appState by appViewModel.appState.collectAsState()

    LaunchedEffect(appState) {
        when {
            !appState.hasCredential -> navController.navigate(Screen.Welcome.route) {
                popUpTo(0) { inclusive = true }
            }
            !appState.isAuthenticated -> navController.navigate(Screen.Authentication.route) {
                popUpTo(0) { inclusive = true }
            }
            else -> navController.navigate(Screen.Main.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Welcome.route
    ) {
        composable(Screen.Welcome.route) {
            WelcomeScreen(
                onScanQR = { navController.navigate(Screen.Enrollment.createRoute(startWithManualEntry = false)) }
            )
        }
        composable(
            route = Screen.Enrollment.route,
            arguments = listOf(
                navArgument("startWithManualEntry") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val startWithManualEntry = backStackEntry.arguments?.getBoolean("startWithManualEntry") ?: false
            EnrollmentScreen(
                onEnrollmentComplete = { appViewModel.refreshCredentialStatus() },
                onCancel = { navController.popBackStack() },
                startWithManualEntry = startWithManualEntry
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
                onNavigateToCredentialBackup = {
                    navController.navigate(Screen.CredentialBackup.route)
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
                onNavigateToDocuments = {
                    navController.navigate(Screen.Documents.route)
                },
                onNavigateToVaultCredentials = {
                    navController.navigate(Screen.VaultCredentials.route)
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
        composable(Screen.ScanInvitation.route) {
            ScanInvitationScreen(
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
        composable(Screen.CredentialBackup.route) {
            CredentialBackupScreen(
                onComplete = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.CredentialRecovery.route) {
            CredentialRecoveryScreen(
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
        composable(Screen.Documents.route) {
            DocumentsScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.VaultCredentials.route) {
            VaultCredentialsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
fun WelcomeScreen(
    onScanQR: () -> Unit
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

        Spacer(modifier = Modifier.height(40.dp))
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
    onNavigateToCredentialBackup: () -> Unit = {},
    onNavigateToConnectionDetail: (String) -> Unit = {},
    onNavigateToCreateInvitation: () -> Unit = {},
    onNavigateToScanInvitation: () -> Unit = {},
    onNavigateToConversation: (String) -> Unit = {},
    onNavigateToHandlerDetail: (String) -> Unit = {},
    onNavigateToPersonalData: () -> Unit = {},
    onNavigateToSecrets: () -> Unit = {},
    onNavigateToDocuments: () -> Unit = {},
    onNavigateToVaultCredentials: () -> Unit = {},
    onSignOut: () -> Unit = {},
    appViewModel: AppViewModel = hiltViewModel()
) {
    var navigationState by remember { mutableStateOf(NavigationState()) }
    val snackbarHostState = remember { SnackbarHostState() }

    MainScaffold(
        navigationState = navigationState,
        onNavigationStateChange = { navigationState = it },
        userName = "VettID User",
        userEmail = "",
        vaultStatus = com.vettid.app.ui.navigation.VaultStatus.ACTIVE,
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
                    VaultServicesTab.HANDLERS -> onNavigateToHandlers()
                    VaultServicesTab.LOGS -> { /* Filter logs */ }
                }
                AppSection.APP_SETTINGS -> { /* No header actions for settings */ }
            }
        },
        onSearchClick = {
            // Handle search based on current screen
        },
        onNavigateToPersonalData = onNavigateToPersonalData,
        onNavigateToSecrets = onNavigateToSecrets,
        onNavigateToDocuments = onNavigateToDocuments,
        onNavigateToCredentials = onNavigateToVaultCredentials,
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
        vaultServicesHandlersContent = {
            HandlersContentEmbedded(
                onHandlerSelected = onNavigateToHandlerDetail
            )
        },
        vaultServicesLogsContent = {
            VaultServicesLogsContent()
        },
        // App Settings section content
        appSettingsGeneralContent = {
            AppSettingsGeneralContent()
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
 * Embedded handlers content without Scaffold.
 */
@Composable
private fun HandlersContentEmbedded(
    onHandlerSelected: (String) -> Unit = {}
) {
    PlaceholderSection(
        icon = Icons.Default.Extension,
        title = "Handlers",
        description = "Browse and manage installed handlers that extend your vault capabilities."
    )
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

