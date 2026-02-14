package com.vettid.app.features.devices

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceManagementScreen(
    onNavigateToPairing: () -> Unit,
    onNavigateToApproval: (DeviceApprovalRequest) -> Unit = {},
    viewModel: DeviceManagementViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val isRevoking by viewModel.isRevoking.collectAsState()
    val isExtending by viewModel.isExtending.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    var deviceToRevoke by remember { mutableStateOf<ConnectedDevice?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadDevices()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Desktop Devices") },
                actions = {
                    IconButton(onClick = onNavigateToPairing) {
                        Icon(Icons.Default.Add, contentDescription = "Pair Desktop")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Error banner
            errorMessage?.let { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            msg,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            when (val s = state) {
                is DeviceManagementState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Loading devices...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                is DeviceManagementState.Empty -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Computer,
                                contentDescription = null,
                                modifier = Modifier.size(80.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "No Desktop Devices",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Pair a desktop to access your vault from your computer. Your phone stays in control.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(onClick = onNavigateToPairing) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Pair Desktop")
                            }
                        }
                    }
                }

                is DeviceManagementState.Loaded -> {
                    val activeDevices = s.devices.filter { it.isSessionActive }
                    val inactiveDevices = s.devices.filter { !it.isSessionActive }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (activeDevices.isNotEmpty()) {
                            item {
                                Text(
                                    "Active Sessions",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            items(activeDevices, key = { it.connectionId }) { device ->
                                DeviceCard(
                                    device = device,
                                    isExtending = isExtending,
                                    onExtend = { viewModel.extendSession(device.connectionId) },
                                    onRevoke = { deviceToRevoke = device }
                                )
                            }
                        }

                        if (inactiveDevices.isNotEmpty()) {
                            item {
                                Text(
                                    "Inactive",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                                )
                            }
                            items(inactiveDevices, key = { it.connectionId }) { device ->
                                DeviceCard(
                                    device = device,
                                    isExtending = false,
                                    onExtend = null,
                                    onRevoke = { deviceToRevoke = device }
                                )
                            }
                        }
                    }
                }

                is DeviceManagementState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Error", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(s.message, color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { viewModel.loadDevices() }) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        }
    }

    // Revoke confirmation dialog
    deviceToRevoke?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToRevoke = null },
            title = { Text("Revoke Device") },
            text = {
                Text("Disconnect ${device.displayName} and revoke its session? The desktop will need to be paired again.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.revokeDevice(device.connectionId)
                        deviceToRevoke = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Revoke")
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToRevoke = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DeviceCard(
    device: ConnectedDevice,
    isExtending: Boolean,
    onExtend: (() -> Unit)?,
    onRevoke: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Device name and status
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Computer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.displayName, fontWeight = FontWeight.Medium)
                    Text(
                        device.platformLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SessionStatusChip(device.sessionStatus ?: device.status)
            }

            // Session timer for active devices
            if (device.isSessionActive) {
                Spacer(modifier = Modifier.height(8.dp))
                device.sessionTimeRemainingSeconds?.let { remaining ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (remaining < 3600) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Session: ${formatTimeRemaining(remaining)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (remaining < 3600) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Actions
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (device.isSessionActive && onExtend != null) {
                    OutlinedButton(
                        onClick = onExtend,
                        enabled = !isExtending,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        if (isExtending) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text("Extend", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (device.status != "revoked") {
                    OutlinedButton(
                        onClick = onRevoke,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Revoke", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun SessionStatusChip(status: String) {
    val (text, color) = when (status) {
        "active" -> "Active" to MaterialTheme.colorScheme.primary
        "expired" -> "Expired" to MaterialTheme.colorScheme.error
        "revoked" -> "Revoked" to MaterialTheme.colorScheme.error
        "suspended" -> "Suspended" to MaterialTheme.colorScheme.tertiary
        else -> status.replaceFirstChar { it.uppercase() } to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

private fun formatTimeRemaining(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
