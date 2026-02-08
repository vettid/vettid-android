package com.vettid.app.features.vault

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.collectLatest

/**
 * Main vault status screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultStatusScreen(
    onNavigateToEnrollment: () -> Unit,
    onRequireAuth: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: VaultStatusViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is VaultStatusEffect.NavigateToEnrollment -> onNavigateToEnrollment()
                is VaultStatusEffect.RequireAuth -> onRequireAuth(effect.action)
                is VaultStatusEffect.NavigateToSettings -> onNavigateToSettings()
                is VaultStatusEffect.ShowSuccess -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is VaultStatusEffect.ShowError -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                else -> { }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vault") },
                actions = {
                    IconButton(onClick = { viewModel.onEvent(VaultStatusEvent.Refresh) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { viewModel.onEvent(VaultStatusEvent.ViewSettings) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        AnimatedContent(
            targetState = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            },
            label = "vault_state_transition"
        ) { targetState ->
            when (targetState) {
                is VaultStatusState.Loading -> LoadingContent()
                is VaultStatusState.NotEnrolled -> NotEnrolledContent(
                    onSetup = { viewModel.onEvent(VaultStatusEvent.StartEnrollment) }
                )
                is VaultStatusState.Enrolled -> EnrolledContent(
                    state = targetState,
                    onProvision = { viewModel.onEvent(VaultStatusEvent.ProvisionVault) }
                )
                is VaultStatusState.Provisioning -> ProvisioningContent(state = targetState)
                is VaultStatusState.Running -> RunningContent(
                    state = targetState,
                    syncStatus = syncStatus,
                    onSync = { viewModel.onEvent(VaultStatusEvent.SyncVault) },
                    onBackup = { viewModel.onEvent(VaultStatusEvent.TriggerBackup) },
                    onStop = { viewModel.onEvent(VaultStatusEvent.StopVault) }
                )
                is VaultStatusState.Stopped -> StoppedContent(
                    state = targetState,
                    onStart = { viewModel.onEvent(VaultStatusEvent.StartVault) }
                )
                is VaultStatusState.Terminated -> TerminatedContent(state = targetState)
                is VaultStatusState.Error -> ErrorContent(
                    state = targetState,
                    onRetry = { viewModel.onEvent(VaultStatusEvent.Retry) }
                )
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun NotEnrolledContent(
    onSetup: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "No Vault Set Up",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Set up your secure vault to store credentials and access your private data.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onSetup,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Set Up Vault")
        }
    }
}

@Composable
private fun EnrolledContent(
    state: VaultStatusState.Enrolled,
    onProvision: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        StatusBadge(
            icon = Icons.Default.CheckCircle,
            color = Color(0xFF4CAF50),
            label = "Enrolled"
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Vault Enrolled",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Your vault credentials are set up. Provision your vault instance to start using it.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoRow(label = "Vault ID", value = state.vaultId.take(8) + "...")
                if (state.enrolledAt.isNotEmpty()) {
                    InfoRow(label = "Enrolled", value = state.enrolledAt)
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onProvision,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(Icons.Default.Cloud, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Provision Vault")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ProvisioningContent(
    state: VaultStatusState.Provisioning
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.size(80.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Provisioning Vault",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Your vault instance is being created. This may take a few minutes.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        state.estimatedReadyTime?.let { eta ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Estimated ready: $eta",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RunningContent(
    state: VaultStatusState.Running,
    syncStatus: SyncStatus,
    onSync: () -> Unit,
    onBackup: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        // Status header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusBadge(
                icon = when (state.health.status) {
                    HealthLevel.HEALTHY -> Icons.Default.CheckCircle
                    HealthLevel.DEGRADED -> Icons.Default.Warning
                    HealthLevel.UNHEALTHY -> Icons.Default.Error
                    HealthLevel.UNKNOWN -> Icons.AutoMirrored.Filled.Help
                },
                color = when (state.health.status) {
                    HealthLevel.HEALTHY -> Color(0xFF4CAF50)
                    HealthLevel.DEGRADED -> Color(0xFFFF9800)
                    HealthLevel.UNHEALTHY -> Color(0xFFF44336)
                    HealthLevel.UNKNOWN -> Color(0xFF9E9E9E)
                },
                label = state.health.status.name
            )

            Spacer(modifier = Modifier.weight(1f))

            // Sync indicator
            if (syncStatus is SyncStatus.Syncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Vault Running",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Health metrics
        if (state.health.status != HealthLevel.UNKNOWN) {
            HealthMetricsCard(health = state.health)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Vault info
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Vault Details",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(12.dp))
                InfoRow(label = "Vault ID", value = state.vaultId.take(8) + "...")
                state.instanceId?.let { InfoRow(label = "Instance", value = it) }
                state.region?.let { InfoRow(label = "Region", value = it) }
                state.lastBackup?.let { InfoRow(label = "Last Backup", value = it) }
                state.lastSync?.let { InfoRow(label = "Last Sync", value = it) }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Quick actions
        Text(
            text = "Quick Actions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionButton(
                icon = Icons.Default.Sync,
                label = "Sync",
                onClick = onSync,
                modifier = Modifier.weight(1f)
            )
            ActionButton(
                icon = Icons.Default.Backup,
                label = "Backup",
                onClick = onBackup,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Stop button
        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(Icons.Default.Stop, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Stop Vault")
        }
    }
}

@Composable
private fun HealthMetricsCard(health: VaultHealth) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (health.status) {
                HealthLevel.HEALTHY -> Color(0xFF4CAF50).copy(alpha = 0.1f)
                HealthLevel.DEGRADED -> Color(0xFFFF9800).copy(alpha = 0.1f)
                HealthLevel.UNHEALTHY -> Color(0xFFF44336).copy(alpha = 0.1f)
                HealthLevel.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Health Metrics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(12.dp))

            health.cpuUsagePercent?.let {
                MetricRow(label = "CPU", value = it, unit = "%")
            }
            health.memoryUsagePercent?.let {
                MetricRow(label = "Memory", value = it, unit = "%")
            }
            health.diskUsagePercent?.let {
                MetricRow(label = "Disk", value = it, unit = "%")
            }
            health.natsConnected?.let {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("NATS", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = if (it) "Connected" else "Disconnected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (it) Color(0xFF4CAF50) else Color(0xFFF44336)
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: Float, unit: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { value / 100f },
                modifier = Modifier
                    .width(100.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = when {
                    value > 90 -> Color(0xFFF44336)
                    value > 70 -> Color(0xFFFF9800)
                    else -> Color(0xFF4CAF50)
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${value.toInt()}$unit",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun StoppedContent(
    state: VaultStatusState.Stopped,
    onStart: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        StatusBadge(
            icon = Icons.Default.PauseCircle,
            color = Color(0xFF9E9E9E),
            label = "Stopped"
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Vault Stopped",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Your vault instance is stopped. Start it to access your data.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoRow(label = "Vault ID", value = state.vaultId.take(8) + "...")
                state.instanceId?.let { InfoRow(label = "Instance", value = it) }
                state.lastBackup?.let { InfoRow(label = "Last Backup", value = it) }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Start Vault")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun TerminatedContent(
    state: VaultStatusState.Terminated
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Cancel,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Vault Terminated",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "This vault has been permanently terminated.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorContent(
    state: VaultStatusState.Error,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Error",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.retryable) {
            Spacer(modifier = Modifier.height(32.dp))

            Button(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry")
            }
        }
    }
}

// MARK: - Helper Components

@Composable
private fun StatusBadge(
    icon: ImageVector,
    color: Color,
    label: String
) {
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = color
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label)
    }
}
