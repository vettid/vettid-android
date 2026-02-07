package com.vettid.app.features.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.network.HandlerSummary
import com.vettid.app.core.network.InstalledHandler
import com.vettid.app.features.settings.AppTheme
import kotlinx.coroutines.flow.collectLatest

/**
 * Vault Preferences screen content.
 * Per mobile-ui-plan.md Section 3.5.4
 */
@Composable
fun VaultPreferencesContent(
    viewModel: VaultPreferencesViewModel = hiltViewModel(),
    onManageHandlers: () -> Unit = {},
    onChangePassword: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is VaultPreferencesEffect.ShowSuccess -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is VaultPreferencesEffect.ShowError -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is VaultPreferencesEffect.NavigateToHandlers -> {
                    onManageHandlers()
                }
                is VaultPreferencesEffect.NavigateToChangePassword -> {
                    onChangePassword()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Vault Server Section
            VaultServerSection(
                status = state.vaultServerStatus,
                instanceId = state.vaultInstanceId,
                instanceIp = state.vaultInstanceIp,
                natsEndpoint = state.natsEndpoint,
                actionInProgress = state.vaultActionInProgress,
                errorMessage = state.vaultErrorMessage,
                pcrVersion = state.pcrVersion,
                pcr0Hash = state.pcr0Hash,
                onStartClick = { viewModel.startVault() },
                onStopClick = { viewModel.stopVault() },
                onRefreshClick = { viewModel.refreshVaultStatus() }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Credential Settings
            PreferencesSection(title = "CREDENTIAL SETTINGS") {
                // Session TTL
                TTLDropdownItem(
                    currentTtl = state.sessionTtlMinutes,
                    onTtlChange = { viewModel.updateSessionTtl(it) }
                )

                Divider()

                // Change Password
                PreferencesItem(
                    icon = Icons.Default.Password,
                    title = "Change Password",
                    subtitle = "Update your vault credential password",
                    onClick = { viewModel.onChangePasswordClick() }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Event Handlers
            EventHandlersSection(
                installedCount = state.installedHandlerCount,
                availableCount = state.availableHandlerCount,
                installedHandlers = state.installedHandlers,
                availableHandlers = state.availableHandlers,
                isLoading = state.handlersLoading,
                error = state.handlersError,
                onManageClick = { viewModel.onManageHandlersClick() },
                onRefresh = { viewModel.loadHandlers() }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Archive Settings
            PreferencesSection(title = "ARCHIVE SETTINGS") {
                ArchiveDropdownItem(
                    label = "Archive after",
                    currentValue = state.archiveAfterDays,
                    options = listOf(7, 14, 30, 60, 90),
                    onValueChange = { viewModel.updateArchiveAfterDays(it) }
                )

                Divider()

                ArchiveDropdownItem(
                    label = "Delete after",
                    currentValue = state.deleteAfterDays,
                    options = listOf(30, 60, 90, 180, 365),
                    onValueChange = { viewModel.updateDeleteAfterDays(it) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Appearance section
            AppearanceSection(
                currentTheme = state.theme,
                onThemeChange = { viewModel.updateTheme(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // About section
            AboutSection()

            Spacer(modifier = Modifier.height(24.dp))

            // Help & Support section
            HelpSupportSection()

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Snackbar host
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun PreferencesSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun PreferencesItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@Composable
private fun AppearanceSection(
    currentTheme: AppTheme,
    onThemeChange: (AppTheme) -> Unit
) {
    PreferencesSection(title = "APPEARANCE") {
        AppTheme.values().forEach { theme ->
            ListItem(
                modifier = Modifier.clickable { onThemeChange(theme) },
                headlineContent = { Text(theme.displayName) },
                leadingContent = {
                    RadioButton(
                        selected = currentTheme == theme,
                        onClick = { onThemeChange(theme) }
                    )
                },
                trailingContent = {
                    when (theme) {
                        AppTheme.AUTO -> Icon(
                            Icons.Default.BrightnessAuto,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AppTheme.LIGHT -> Icon(
                            Icons.Default.LightMode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AppTheme.DARK -> Icon(
                            Icons.Default.DarkMode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
            if (theme != AppTheme.values().last()) {
                Divider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TTLDropdownItem(
    currentTtl: Int,
    onTtlChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(5, 15, 30, 60)

    ListItem(
        headlineContent = { Text("Session TTL") },
        supportingContent = { Text("Time before re-authentication") },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.Timer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.menuAnchor()
                ) {
                    Text("$currentTtl min")
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null
                    )
                }
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text("$option minutes") },
                            onClick = {
                                onTtlChange(option)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchiveDropdownItem(
    label: String,
    currentValue: Int,
    options: List<Int>,
    onValueChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(label) },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.Inventory2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.menuAnchor()
                ) {
                    Text("$currentValue days")
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null
                    )
                }
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text("$option days") },
                            onClick = {
                                onValueChange(option)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    )
}

/**
 * Full-screen Vault Preferences with Scaffold and back navigation.
 * Used when navigating from More menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultPreferencesScreenFull(
    viewModel: VaultPreferencesViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preferences") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            VaultPreferencesContent(viewModel = viewModel)
        }
    }
}

// MARK: - Vault Server Section

@Composable
private fun VaultServerSection(
    status: VaultServerStatus,
    instanceId: String?,
    instanceIp: String?,
    natsEndpoint: String?,
    actionInProgress: Boolean,
    errorMessage: String?,
    pcrVersion: String?,
    pcr0Hash: String?,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    var showLogsDialog by remember { mutableStateOf(false) }

    PreferencesSection(title = "VAULT") {
        // Status Row - clickable to show logs
        ListItem(
            modifier = Modifier.clickable { showLogsDialog = true },
            headlineContent = { Text("Status") },
            supportingContent = {
                Text(
                    text = when (status) {
                        VaultServerStatus.UNKNOWN -> "Unknown"
                        VaultServerStatus.LOADING -> "Checking..."
                        VaultServerStatus.ENCLAVE_READY -> "Enclave Ready"
                        VaultServerStatus.RUNNING -> "Running"
                        VaultServerStatus.STOPPED -> "Stopped"
                        VaultServerStatus.STARTING -> "Starting..."
                        VaultServerStatus.STOPPING -> "Stopping..."
                        VaultServerStatus.PENDING -> "Pending"
                        VaultServerStatus.ERROR -> errorMessage ?: "Error"
                    }
                )
            },
            leadingContent = {
                Icon(
                    imageVector = when (status) {
                        VaultServerStatus.ENCLAVE_READY -> Icons.Default.CheckCircle
                        VaultServerStatus.RUNNING -> Icons.Default.CheckCircle
                        VaultServerStatus.STOPPED -> Icons.Default.Cancel
                        VaultServerStatus.ERROR -> Icons.Default.Error
                        else -> Icons.Default.Cloud
                    },
                    contentDescription = null,
                    tint = when (status) {
                        VaultServerStatus.ENCLAVE_READY -> Color(0xFF4CAF50) // Green
                        VaultServerStatus.RUNNING -> Color(0xFF4CAF50) // Green
                        VaultServerStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                        VaultServerStatus.ERROR -> MaterialTheme.colorScheme.error
                        VaultServerStatus.STARTING, VaultServerStatus.STOPPING -> Color(0xFFFFA000) // Amber
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            },
            trailingContent = {
                if (status == VaultServerStatus.LOADING || actionInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(onClick = onRefreshClick) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh status"
                        )
                    }
                }
            }
        )

        // Instance Info (only when running)
        if (status == VaultServerStatus.RUNNING && instanceId != null) {
            Divider()
            ListItem(
                headlineContent = { Text("Instance") },
                supportingContent = { Text(instanceId) },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }

        if (status == VaultServerStatus.RUNNING && natsEndpoint != null) {
            Divider()
            ListItem(
                headlineContent = { Text("NATS Endpoint") },
                supportingContent = { Text(natsEndpoint) },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.Hub,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }

        // PCR Attestation Info
        if (pcrVersion != null || pcr0Hash != null) {
            Divider()
            ListItem(
                headlineContent = { Text("Enclave Attestation") },
                supportingContent = {
                    Column {
                        if (pcrVersion != null) {
                            Text("PCR Version: $pcrVersion")
                        }
                        if (pcr0Hash != null) {
                            // Show full PCR0 hash with word wrap
                            Text(
                                text = "PCR0:",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = pcr0Hash,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                softWrap = true
                            )
                        }
                    }
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50)
                    )
                }
            )
        }

        // Only show start/stop buttons for legacy EC2 mode (not Nitro Enclave)
        if (status != VaultServerStatus.ENCLAVE_READY) {
            Divider()

            // Action Buttons (legacy EC2 mode only)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Start Button
                OutlinedButton(
                    onClick = onStartClick,
                    enabled = !actionInProgress && (status == VaultServerStatus.STOPPED || status == VaultServerStatus.ERROR || status == VaultServerStatus.UNKNOWN),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start")
                }

                // Stop Button
                OutlinedButton(
                    onClick = onStopClick,
                    enabled = !actionInProgress && status == VaultServerStatus.RUNNING,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop")
                }
            }
        }

        // Error message card (if there's an error)
        if (status == VaultServerStatus.ERROR && errorMessage != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    // Vault logs dialog
    if (showLogsDialog) {
        VaultLogsDialog(onDismiss = { showLogsDialog = false })
    }
}

@Composable
private fun VaultLogsDialog(onDismiss: () -> Unit) {
    var logs by remember { mutableStateOf("Loading vault logs...") }

    // Load vault-related logs
    LaunchedEffect(Unit) {
        logs = try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", "150", "-s",
                    "NitroEnrollmentClient:*",
                    "NitroAttestation:*",
                    "NitroWebSocket:*",
                    "VaultManager:*",
                    "CredentialStore:*",
                    "CryptoManager:*",
                    "PinUnlockViewModel:*",
                    "EnrollmentWizardVM:*"
                )
            )
            val output = process.inputStream.bufferedReader().readText()
            if (output.isBlank()) {
                "No recent vault logs found.\n\nTry performing a vault operation to generate logs."
            } else {
                output
            }
        } catch (e: Exception) {
            "Failed to load vault logs: ${e.message}"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Vault Manager Logs")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = logs,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

// MARK: - About Section

@Composable
private fun AboutSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showLogsDialog by remember { mutableStateOf(false) }

    // Get app version info
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }
    val versionCode = remember {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toString()
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    PreferencesSection(title = "ABOUT") {
        ListItem(
            headlineContent = { Text("Version") },
            supportingContent = { Text("$versionName ($versionCode)") },
            leadingContent = {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )

        Divider()

        ListItem(
            modifier = Modifier.clickable { showLogsDialog = true },
            headlineContent = { Text("App Logs") },
            supportingContent = { Text("View recent app activity") },
            leadingContent = {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }

    // Logs dialog
    if (showLogsDialog) {
        AppLogsDialog(onDismiss = { showLogsDialog = false })
    }
}

@Composable
private fun AppLogsDialog(onDismiss: () -> Unit) {
    var logs by remember { mutableStateOf("Loading logs...") }

    // Load logs
    LaunchedEffect(Unit) {
        logs = try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "100", "--pid=${android.os.Process.myPid()}"))
            process.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            "Failed to load logs: ${e.message}"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App Logs") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = logs,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

// MARK: - Help & Support Section

@Composable
private fun HelpSupportSection() {
    val context = androidx.compose.ui.platform.LocalContext.current

    PreferencesSection(title = "HELP & SUPPORT") {
        ListItem(
            modifier = Modifier.clickable {
                // Open support URL or email
                try {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://vettid.com/support")
                    )
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Ignore
                }
            },
            headlineContent = { Text("Get Help") },
            supportingContent = { Text("FAQs, guides, and contact support") },
            leadingContent = {
                Icon(
                    imageVector = Icons.Default.Help,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )

        Divider()

        ListItem(
            modifier = Modifier.clickable {
                // Open feedback email
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                        data = android.net.Uri.parse("mailto:support@vettid.com")
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "VettID App Feedback")
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Ignore
                }
            },
            headlineContent = { Text("Send Feedback") },
            supportingContent = { Text("Report issues or suggest improvements") },
            leadingContent = {
                Icon(
                    imageVector = Icons.Default.Feedback,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}

// MARK: - Event Handlers Section

@Composable
private fun EventHandlersSection(
    installedCount: Int,
    availableCount: Int,
    installedHandlers: List<InstalledHandler>,
    availableHandlers: List<HandlerSummary>,
    isLoading: Boolean,
    error: String?,
    onManageClick: () -> Unit,
    onRefresh: () -> Unit
) {
    var showHandlersDialog by remember { mutableStateOf(false) }

    PreferencesSection(title = "EVENT HANDLERS") {
        ListItem(
            modifier = Modifier.clickable { showHandlersDialog = true },
            headlineContent = { Text("Manage Handlers") },
            supportingContent = {
                if (isLoading) {
                    Text("Loading...")
                } else if (error != null) {
                    Text("Tap to retry", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("$installedCount installed, $availableCount available")
                }
            },
            leadingContent = {
                Icon(
                    imageVector = Icons.Default.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingContent = {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    // Handlers full-screen dialog
    if (showHandlersDialog) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showHandlersDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            EventHandlersFullScreen(
                installedHandlers = installedHandlers,
                availableHandlers = availableHandlers,
                isLoading = isLoading,
                error = error,
                onRefresh = onRefresh,
                onBack = { showHandlersDialog = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventHandlersFullScreen(
    installedHandlers: List<InstalledHandler>,
    availableHandlers: List<HandlerSummary>,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Event Handlers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                error != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(error, color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(onClick = onRefresh) {
                                Text("Retry")
                            }
                        }
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        // Summary card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Extension,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "${installedHandlers.size} Active Handlers",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = "Vault event handlers process NATS messages in the enclave",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Installed handlers
                        Text(
                            text = "ACTIVE HANDLERS",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        installedHandlers.forEach { handler ->
                            HandlerDetailCard(handler = handler)
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (availableHandlers.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "AVAILABLE",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            availableHandlers.forEach { handler ->
                                AvailableHandlerCard(handler = handler)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HandlerDetailCard(handler: InstalledHandler) {
    val description = getHandlerDescription(handler.id)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = getCategoryIcon(handler.category),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = handler.name,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "v${handler.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = Color(0xFF4CAF50).copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Active",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Handler details
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    DetailRow("NATS Topic", handler.id)
                    Spacer(modifier = Modifier.height(4.dp))
                    DetailRow("Category", handler.category.replaceFirstChar { it.uppercase() })
                    if (handler.enabled) {
                        Spacer(modifier = Modifier.height(4.dp))
                        DetailRow("Status", "Enabled")
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = if (label == "NATS Topic") androidx.compose.ui.text.font.FontFamily.Monospace else null,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AvailableHandlerCard(handler: HandlerSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = getCategoryIcon(handler.category),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = handler.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Text(
                    text = handler.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                Text(
                    text = "v${handler.version} • ${handler.publisher}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun getHandlerDescription(handlerId: String): String {
    return when {
        handlerId.startsWith("profile.") -> "Manages your public profile data. Handles profile retrieval, updates, and publishing to make your information visible to connections."
        handlerId.startsWith("personal-data.") -> "Stores and retrieves personal data fields in the encrypted vault. Supports categorized fields with privacy controls."
        handlerId.startsWith("secrets.add") || handlerId.startsWith("secrets.retrieve") || handlerId.startsWith("secrets.delete") -> "Manages encrypted secrets in the vault datastore. Handles storage, retrieval, and deletion of sensitive credentials."
        handlerId.startsWith("secrets.identity") -> "Manages cryptographic identity keys. Returns the Ed25519 public key used for signature verification and identity attestation."
        handlerId.startsWith("connection.") -> "Handles connection requests between users. Manages the connection lifecycle including invitations, acceptance, and key exchange."
        handlerId.startsWith("message.") -> "Processes encrypted messages between connected users. Handles message routing, delivery, and storage within the vault."
        handlerId.startsWith("credential.") -> "Manages the Protean Credential lifecycle. Handles credential creation, updates, key rotation, and attestation verification."
        else -> "Vault event handler for processing NATS messages in the Nitro Enclave."
    }
}

private fun getCategoryIcon(category: String): androidx.compose.ui.graphics.vector.ImageVector {
    return when (category.lowercase()) {
        "authentication", "auth" -> Icons.Default.Fingerprint
        "sync", "data" -> Icons.Default.Sync
        "notification", "notifications" -> Icons.Default.Notifications
        "share", "profile" -> Icons.Default.Share
        "file", "transfer" -> Icons.Default.AttachFile
        "voice", "call" -> Icons.Default.Call
        "video" -> Icons.Default.Videocam
        "messaging", "chat" -> Icons.Default.Chat
        "security" -> Icons.Default.Security
        "storage" -> Icons.Default.Storage
        else -> Icons.Default.Extension
    }
}
