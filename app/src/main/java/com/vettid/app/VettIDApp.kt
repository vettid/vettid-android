package com.vettid.app

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.vettid.app.features.handlers.HandlerDetailScreen
import com.vettid.app.features.handlers.HandlerDiscoveryScreen
import com.vettid.app.features.handlers.HandlerExecutionScreen
import com.vettid.app.features.messaging.ConversationScreen
import com.vettid.app.features.profile.ProfileScreen
import com.vettid.app.ui.components.QrCodeScanner

private const val TAG = "VettIDApp"

sealed class Screen(val route: String) {
    object Welcome : Screen("welcome")
    object Enrollment : Screen("enrollment")
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
                onScanQR = { navController.navigate(Screen.Enrollment.route) },
                onEnterCode = { /* Navigate to manual entry */ }
            )
        }
        composable(Screen.Enrollment.route) {
            EnrollmentScreen(
                onComplete = { appViewModel.refreshCredentialStatus() },
                onBack = { navController.popBackStack() }
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
    }
}

@Composable
fun WelcomeScreen(
    onScanQR: () -> Unit,
    onEnterCode: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Welcome to VettID",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Secure credential management\nfor your personal vault",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrollmentScreen(
    onComplete: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var scannedCode by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Enrollment QR") },
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
        ) {
            when {
                isProcessing -> {
                    // Processing scanned code
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Processing enrollment...")
                    }
                }
                scannedCode != null -> {
                    // Show scanned result (for now just display it)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "QR Code Scanned!",
                            style = MaterialTheme.typography.headlineSmall
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Invitation code detected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Show the scanned code (truncated for display)
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = "Scanned Data:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = scannedCode?.take(100) ?: "",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if ((scannedCode?.length ?: 0) > 100) {
                                    Text(
                                        text = "...",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = {
                                // TODO: Start enrollment API flow
                                isProcessing = true
                                // For now, just show a toast
                                Toast.makeText(
                                    context,
                                    "Enrollment flow coming soon!",
                                    Toast.LENGTH_SHORT
                                ).show()
                                isProcessing = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Continue Enrollment")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = { scannedCode = null },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Scan Again")
                        }
                    }
                }
                else -> {
                    // Show QR scanner
                    QrCodeScanner(
                        onQrCodeScanned = { code ->
                            Log.d(TAG, "Scanned QR code: $code")
                            scannedCode = code
                        },
                        onError = { error ->
                            Log.e(TAG, "Scanner error: $error")
                            errorMessage = error
                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Error snackbar
            errorMessage?.let { error ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = { errorMessage = null }) {
                            Text("Dismiss")
                        }
                    }
                ) {
                    Text(error)
                }
            }
        }
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
    onNavigateToProfile: () -> Unit = {}
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
                0 -> Text("Vault Status - Coming Soon")
                1 -> ConnectionsTab(onNavigateToConnections = onNavigateToConnections)
                2 -> HandlersTab(onNavigateToHandlers = onNavigateToHandlers)
                3 -> ProfileTab(onNavigateToProfile = onNavigateToProfile)
            }
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
