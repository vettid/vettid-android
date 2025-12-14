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
