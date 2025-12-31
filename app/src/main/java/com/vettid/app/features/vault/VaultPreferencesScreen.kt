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
            PreferencesSection(title = "EVENT HANDLERS") {
                PreferencesItem(
                    icon = Icons.Default.Extension,
                    title = "Manage Handlers",
                    subtitle = "${state.installedHandlerCount} installed, ${state.availableHandlerCount} available",
                    onClick = { viewModel.onManageHandlersClick() }
                )
            }

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

            // Info card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "These settings are stored locally in your vault and sync across your devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
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
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    PreferencesSection(title = "VAULT SERVER") {
        // Status Row
        ListItem(
            headlineContent = { Text("Status") },
            supportingContent = {
                Text(
                    text = when (status) {
                        VaultServerStatus.UNKNOWN -> "Unknown"
                        VaultServerStatus.LOADING -> "Checking..."
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
                        VaultServerStatus.RUNNING -> Icons.Default.CheckCircle
                        VaultServerStatus.STOPPED -> Icons.Default.Cancel
                        VaultServerStatus.ERROR -> Icons.Default.Error
                        else -> Icons.Default.Cloud
                    },
                    contentDescription = null,
                    tint = when (status) {
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

        Divider()

        // Action Buttons
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
}
