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
import com.vettid.app.features.enrollment.EnrollmentScreen
import com.vettid.app.features.handlers.HandlerDetailScreen
import com.vettid.app.features.handlers.HandlerDiscoveryScreen
import com.vettid.app.features.handlers.HandlerExecutionScreen
import com.vettid.app.features.messaging.ConversationScreen
import com.vettid.app.features.profile.ProfileScreen
import com.vettid.app.ui.backup.BackupDetailScreen
import com.vettid.app.ui.backup.BackupListScreen
import com.vettid.app.ui.backup.BackupSettingsScreen
import com.vettid.app.ui.backup.CredentialBackupScreen
import com.vettid.app.ui.components.QrCodeScanner
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
                onScanQR = { navController.navigate(Screen.Enrollment.createRoute(startWithManualEntry = false)) },
                onEnterCode = { navController.navigate(Screen.Enrollment.createRoute(startWithManualEntry = true)) }
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
    }
}

@Composable
fun WelcomeScreen(
    onScanQR: () -> Unit,
    onEnterCode: () -> Unit
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
            Text("I have an enrollment link")
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}


@Composable
fun AuthenticationScreen(
    onAuthenticated: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Fingerprint,
            contentDescription = null,
            modifier = Modifier.size(60.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Authenticate",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onAuthenticated) {
            Text("Unlock with Biometrics")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToHandlers: () -> Unit = {},
    onNavigateToConnections: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToBackups: () -> Unit = {},
    onNavigateToCredentialBackup: () -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.AccountBalance, contentDescription = null) },
                    label = { Text("Vault") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.People, contentDescription = null) },
                    label = { Text("Connections") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Extension, contentDescription = null) },
                    label = { Text("Handlers") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Person, contentDescription = null) },
                    label = { Text("Profile") },
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when (selectedTab) {
                0 -> VaultTab(
                    onNavigateToBackups = onNavigateToBackups,
                    onNavigateToCredentialBackup = onNavigateToCredentialBackup
                )
                1 -> ConnectionsTab(onNavigateToConnections = onNavigateToConnections)
                2 -> HandlersTab(onNavigateToHandlers = onNavigateToHandlers)
                3 -> ProfileTab(onNavigateToProfile = onNavigateToProfile)
            }
        }
    }
}

@Composable
private fun VaultTab(
    onNavigateToBackups: () -> Unit,
    onNavigateToCredentialBackup: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.AccountBalance,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Your Vault",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Manage your vault backups\nand credential security",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onNavigateToBackups,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Backup, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Manage Backups")
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onNavigateToCredentialBackup,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Key, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Backup Credentials")
        }
    }
}

@Composable
private fun ConnectionsTab(
    onNavigateToConnections: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.People,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Connections",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Connect with others and send\nend-to-end encrypted messages",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onNavigateToConnections) {
            Icon(Icons.Default.PersonAdd, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("View Connections")
        }
    }
}

@Composable
private fun HandlersTab(
    onNavigateToHandlers: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Extension,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Handler Marketplace",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Browse, install, and run handlers\nto extend your vault capabilities",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onNavigateToHandlers) {
            Icon(Icons.Default.Search, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Browse Handlers")
        }
    }
}

@Composable
private fun ProfileTab(
    onNavigateToProfile: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Your Profile",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Manage your profile and\naccount settings",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onNavigateToProfile) {
            Icon(Icons.Default.Edit, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Edit Profile")
        }
    }
}
