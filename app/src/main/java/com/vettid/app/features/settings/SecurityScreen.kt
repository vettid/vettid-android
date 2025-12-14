package com.vettid.app.features.settings

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
 * Security settings screen.
 * Per mobile-ui-plan.md Section 3.3.2
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsContent(
    viewModel: SettingsViewModel = hiltViewModel(),
    onChangePassword: () -> Unit = {},
    onViewRecoveryPhrase: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is SettingsEffect.ShowSuccess -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is SettingsEffect.ShowError -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is SettingsEffect.NavigateToChangePassword -> {
                    onChangePassword()
                }
                is SettingsEffect.NavigateToRecoveryPhrase -> {
                    onViewRecoveryPhrase()
                }
                else -> {}
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
            // App Lock Section
            Text(
                text = "APP LOCK",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Enable App Lock") },
                    supportingContent = { Text("Require authentication to open app") },
                    leadingContent = {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = state.appLockEnabled,
                            onCheckedChange = { viewModel.toggleAppLock(it) }
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Lock Method Section
            if (state.appLockEnabled) {
                Text(
                    text = "LOCK METHOD",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        LockMethod.values().forEach { method ->
                            LockMethodOption(
                                method = method,
                                isSelected = state.lockMethod == method,
                                onClick = { viewModel.updateLockMethod(method) }
                            )
                            if (method != LockMethod.values().last()) {
                                Divider(modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Auto-Lock Section
                Text(
                    text = "AUTO-LOCK",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                var expandedTimeout by remember { mutableStateOf(false) }
                Card(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text("Lock after") },
                        leadingContent = {
                            Icon(
                                Icons.Default.Timer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            ExposedDropdownMenuBox(
                                expanded = expandedTimeout,
                                onExpandedChange = { expandedTimeout = it }
                            ) {
                                OutlinedButton(
                                    onClick = { expandedTimeout = true },
                                    modifier = Modifier.menuAnchor()
                                ) {
                                    Text(state.autoLockTimeout.displayName)
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = null
                                    )
                                }
                                ExposedDropdownMenu(
                                    expanded = expandedTimeout,
                                    onDismissRequest = { expandedTimeout = false }
                                ) {
                                    AutoLockTimeout.values().forEach { timeout ->
                                        DropdownMenuItem(
                                            text = { Text(timeout.displayName) },
                                            onClick = {
                                                viewModel.updateAutoLockTimeout(timeout)
                                                expandedTimeout = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Privacy Section
            Text(
                text = "PRIVACY",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ListItem(
                        headlineContent = { Text("Screen Lock") },
                        supportingContent = { Text("Hide content when switching apps") },
                        leadingContent = {
                            Icon(
                                Icons.Default.ScreenLockPortrait,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = state.screenLockEnabled,
                                onCheckedChange = { viewModel.toggleScreenLock(it) }
                            )
                        }
                    )

                    Divider(modifier = Modifier.padding(horizontal = 16.dp))

                    ListItem(
                        headlineContent = { Text("Screenshots") },
                        supportingContent = { Text(if (state.screenshotsEnabled) "Enabled" else "Disabled") },
                        leadingContent = {
                            Icon(
                                Icons.Default.Screenshot,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = state.screenshotsEnabled,
                                onCheckedChange = { viewModel.toggleScreenshots(it) }
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Advanced Section
            Text(
                text = "ADVANCED",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ListItem(
                        modifier = Modifier.clickable { viewModel.onChangePasswordClick() },
                        headlineContent = { Text("Change Password") },
                        leadingContent = {
                            Icon(
                                Icons.Default.Password,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )

                    Divider(modifier = Modifier.padding(horizontal = 16.dp))

                    ListItem(
                        modifier = Modifier.clickable { viewModel.onViewRecoveryPhraseClick() },
                        headlineContent = { Text("View Recovery Phrase") },
                        supportingContent = { Text("Required for account recovery") },
                        leadingContent = {
                            Icon(
                                Icons.Default.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Recommendation card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Lightbulb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Recommended: Enable app lock with biometrics for additional security.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun LockMethodOption(
    method: LockMethod,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(method.displayName) },
        leadingContent = {
            RadioButton(
                selected = isSelected,
                onClick = onClick
            )
        },
        trailingContent = {
            Icon(
                imageVector = when (method) {
                    LockMethod.PIN -> Icons.Default.Pin
                    LockMethod.BIOMETRIC -> Icons.Default.Fingerprint
                    LockMethod.BOTH -> Icons.Default.Security
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}
