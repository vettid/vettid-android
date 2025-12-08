package com.vettid.app.features.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.collectLatest
import java.time.Duration

/**
 * Screen for displaying vault health status.
 *
 * Shows:
 * - Overall health status
 * - Component health (Local NATS, Central NATS, Vault Manager)
 * - System stats (uptime, handlers, memory)
 * - Provisioning progress
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultHealthScreen(
    viewModel: VaultHealthViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
    onRequireAuth: () -> Unit = {}
) {
    val state by viewModel.healthState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is VaultHealthEffect.RequireReauth -> onRequireAuth()
                is VaultHealthEffect.ShowMessage -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is VaultHealthEffect.ShowError -> {
                    snackbarHostState.showSnackbar(
                        message = effect.message,
                        actionLabel = "Dismiss"
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vault Health") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            when (val currentState = state) {
                is VaultHealthState.Loading -> LoadingContent()
                is VaultHealthState.NotProvisioned -> NotProvisionedContent(
                    onProvision = { viewModel.provisionVault() }
                )
                is VaultHealthState.Provisioning -> ProvisioningContent(
                    progress = currentState.progress,
                    message = currentState.message
                )
                is VaultHealthState.Loaded -> VaultHealthDetails(
                    state = currentState,
                    onRefresh = { viewModel.refresh() }
                )
                is VaultHealthState.Error -> ErrorContent(
                    message = currentState.message,
                    onRetry = { viewModel.startHealthMonitoring() }
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Loading vault health...")
        }
    }
}

@Composable
private fun NotProvisionedContent(
    onProvision: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Cloud,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Vault Not Provisioned",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Your vault instance hasn't been created yet. Provision a vault to start using VettID.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onProvision,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Provision Vault")
            }
        }
    }
}

@Composable
private fun ProvisioningContent(
    progress: Float,
    message: String
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                progress = progress,
                modifier = Modifier.size(80.dp),
                strokeWidth = 6.dp
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Provisioning Vault",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun VaultHealthDetails(
    state: VaultHealthState.Loaded,
    onRefresh: () -> Unit
) {
    // Status header
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = getStatusBackgroundColor(state.status)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusIndicator(status = state.status)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.status.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = getStatusTextColor(state.status)
                )
                state.uptime?.let { uptime ->
                    Text(
                        text = "Uptime: ${formatDuration(uptime)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = getStatusTextColor(state.status).copy(alpha = 0.8f)
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Component status
    Text(
        text = "Components",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Local NATS
    ComponentStatusCard(
        name = "Local NATS",
        icon = Icons.Default.Storage,
        isHealthy = state.localNats?.status ?: false,
        detail = state.localNats?.let { "${it.connections} connections" }
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Central NATS
    ComponentStatusCard(
        name = "Central NATS",
        icon = Icons.Default.Cloud,
        isHealthy = state.centralNats?.connected ?: false,
        detail = state.centralNats?.let { "${it.latencyMs}ms latency" }
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Vault Manager
    ComponentStatusCard(
        name = "Vault Manager",
        icon = Icons.Default.Settings,
        isHealthy = state.vaultManager?.running ?: false,
        detail = state.vaultManager?.let { "${it.handlersLoaded} handlers loaded" }
    )

    Spacer(modifier = Modifier.height(16.dp))

    // System stats
    state.vaultManager?.let { manager ->
        Text(
            text = "System Resources",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                ResourceBar(
                    label = "Memory",
                    value = manager.memoryMb,
                    unit = "MB"
                )
                Spacer(modifier = Modifier.height(12.dp))
                ResourceBar(
                    label = "CPU",
                    value = manager.cpuPercent.toInt(),
                    unit = "%",
                    maxValue = 100
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // Last event
    state.lastEventAt?.let { lastEvent ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Last Event",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatRelativeTime(lastEvent),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun ComponentStatusCard(
    name: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isHealthy: Boolean,
    detail: String?
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isHealthy) Color(0xFF4CAF50) else Color(0xFFF44336)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                detail?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        color = if (isHealthy) Color(0xFF4CAF50) else Color(0xFFF44336),
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
private fun ResourceBar(
    label: String,
    value: Int,
    unit: String,
    maxValue: Int = 1024
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "$value $unit",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = (value.toFloat() / maxValue).coerceIn(0f, 1f),
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Health Check Failed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry")
            }
        }
    }
}

@Composable
private fun StatusIndicator(status: HealthStatus) {
    val color = when (status) {
        HealthStatus.Healthy -> Color(0xFF4CAF50)
        HealthStatus.Degraded -> Color(0xFFFF9800)
        HealthStatus.Unhealthy -> Color(0xFFF44336)
    }
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(color, CircleShape)
    )
}

@Composable
private fun getStatusBackgroundColor(status: HealthStatus): Color {
    return when (status) {
        HealthStatus.Healthy -> Color(0xFFE8F5E9)
        HealthStatus.Degraded -> Color(0xFFFFF3E0)
        HealthStatus.Unhealthy -> Color(0xFFFFEBEE)
    }
}

@Composable
private fun getStatusTextColor(status: HealthStatus): Color {
    return when (status) {
        HealthStatus.Healthy -> Color(0xFF2E7D32)
        HealthStatus.Degraded -> Color(0xFFE65100)
        HealthStatus.Unhealthy -> Color(0xFFC62828)
    }
}

private fun formatDuration(duration: Duration): String {
    val hours = duration.toHours()
    val minutes = duration.toMinutes() % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}

private fun formatRelativeTime(instant: java.time.Instant): String {
    val now = java.time.Instant.now()
    val seconds = java.time.Duration.between(instant, now).seconds

    return when {
        seconds < 60 -> "Just now"
        seconds < 3600 -> "${seconds / 60} minutes ago"
        seconds < 86400 -> "${seconds / 3600} hours ago"
        else -> "${seconds / 86400} days ago"
    }
}
