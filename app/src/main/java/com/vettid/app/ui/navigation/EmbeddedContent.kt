package com.vettid.app.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import android.widget.Toast
import com.vettid.app.core.network.Connection
import com.vettid.app.core.network.ConnectionWithLastMessage
import com.vettid.app.core.network.Message
import com.vettid.app.features.agents.*
import com.vettid.app.features.connections.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Connection type filter for the embedded connections view.
 */
enum class ConnectionTypeFilter(val label: String) {
    ALL("All"),
    PEOPLE("People"),
    AGENTS("Agents"),
    SERVICES("Services")
}

/**
 * Embedded connections content without Scaffold.
 * Used within MainScaffold for the Vault > Connections tab.
 */
@Composable
fun ConnectionsContentEmbedded(
    viewModel: ConnectionsViewModel = hiltViewModel(),
    agentViewModel: AgentManagementViewModel = hiltViewModel(),
    searchQuery: String = "",
    onConnectionClick: (String) -> Unit = {},
    onCreateInvitation: () -> Unit = {},
    onScanInvitation: () -> Unit = {},
    onCreateAgentInvitation: () -> Unit = {}
) {
    // Route search query from top bar to ViewModel
    LaunchedEffect(searchQuery) {
        viewModel.onSearchQueryChanged(searchQuery)
    }

    val state by viewModel.state.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val agentState by agentViewModel.state.collectAsState()

    var showFabMenu by remember { mutableStateOf(false) }
    var typeFilter by remember { mutableStateOf(ConnectionTypeFilter.ALL) }
    var showRevokeDialog by remember { mutableStateOf<AgentConnection?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Handle people connection effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ConnectionsEffect.NavigateToConnection -> onConnectionClick(effect.connectionId)
                is ConnectionsEffect.NavigateToCreateInvitation -> onCreateInvitation()
                is ConnectionsEffect.NavigateToScanInvitation -> onScanInvitation()
                is ConnectionsEffect.ShowFilterSheet -> { /* TODO: Show filter bottom sheet */ }
                is ConnectionsEffect.ShowSnackbar -> { /* TODO: Show snackbar */ }
            }
        }
    }

    // Handle agent effects
    LaunchedEffect(Unit) {
        agentViewModel.effects.collect { effect ->
            when (effect) {
                is AgentManagementEffect.ShowError -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_LONG).show()
                }
                is AgentManagementEffect.ShowSuccess -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
                is AgentManagementEffect.NavigateToCreateInvitation -> {
                    onCreateAgentInvitation()
                }
            }
        }
    }

    // Revoke agent dialog
    showRevokeDialog?.let { agent ->
        AlertDialog(
            onDismissRequest = { showRevokeDialog = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Revoke Agent?") },
            text = {
                Text("This will permanently disconnect \"${agent.agentName}\" and revoke its access to your secrets. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        agentViewModel.onEvent(AgentManagementEvent.RevokeAgent(agent.connectionId))
                        showRevokeDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Revoke")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Connection type filter dropdown
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var expanded by remember { mutableStateOf(false) }
                @OptIn(ExperimentalMaterial3Api::class)
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = typeFilter.label,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .width(160.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        ConnectionTypeFilter.entries.forEach { filter ->
                            DropdownMenuItem(
                                text = { Text(filter.label) },
                                onClick = {
                                    typeFilter = filter
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            when (typeFilter) {
                ConnectionTypeFilter.AGENTS -> {
                    // Agent connections view
                    AgentConnectionsContent(
                        state = agentState,
                        onCreateInvitation = { agentViewModel.onEvent(AgentManagementEvent.CreateInvitation) },
                        onRetry = { agentViewModel.onEvent(AgentManagementEvent.LoadAgents) },
                        onRevoke = { showRevokeDialog = it }
                    )
                }
                ConnectionTypeFilter.SERVICES -> {
                    // Service connections placeholder (Service Vault is in design phase)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Business,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "No Services Connected",
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Service connections allow third-party services to interact with your vault securely.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Coming soon",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
                else -> {
                    // People connections (ALL shows people — agents are in their own tab)
                    when (val currentState = state) {
                        is ConnectionsState.Loading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        is ConnectionsState.Empty -> {
                            ConnectionsEmptyContent(
                                onCreateInvitation = { viewModel.onCreateInvitation() },
                                onScanInvitation = { viewModel.onScanInvitation() }
                            )
                        }

                        is ConnectionsState.Loaded -> {
                            if (currentState.connections.isEmpty() && currentState.isSearchResult) {
                                NoSearchResultsContent(query = searchQuery)
                            } else {
                                ConnectionsListContent(
                                    connections = currentState.connections,
                                    isRefreshing = isRefreshing,
                                    onRefresh = { viewModel.refresh() },
                                    onConnectionClick = { viewModel.onConnectionClick(it) }
                                )
                            }
                        }

                        is ConnectionsState.Error -> {
                            ConnectionsErrorContent(
                                message = currentState.message,
                                onRetry = { viewModel.loadConnections() }
                            )
                        }
                    }
                }
            }
        }

        // FAB - context-aware based on active filter
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End
        ) {
            if (typeFilter == ConnectionTypeFilter.AGENTS) {
                // Single FAB for creating agent invitation
                FloatingActionButton(
                    onClick = { agentViewModel.onEvent(AgentManagementEvent.CreateInvitation) }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Create Agent Invitation")
                }
            } else {
                // People connections FAB menu
                if (showFabMenu) {
                    SmallFloatingActionButton(
                        onClick = {
                            showFabMenu = false
                            viewModel.onScanInvitation()
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Scan Invitation"
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    SmallFloatingActionButton(
                        onClick = {
                            showFabMenu = false
                            viewModel.onCreateInvitation()
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCode,
                            contentDescription = "Create Invitation"
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                FloatingActionButton(
                    onClick = { showFabMenu = !showFabMenu }
                ) {
                    Icon(
                        imageVector = if (showFabMenu) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = if (showFabMenu) "Close menu" else "Add connection"
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentConnectionsContent(
    state: AgentManagementState,
    onCreateInvitation: () -> Unit,
    onRetry: () -> Unit,
    onRevoke: (AgentConnection) -> Unit
) {
    when (state) {
        is AgentManagementState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        is AgentManagementState.Empty -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "No Agents Connected",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Connect AI agents to give them secure access to your vault secrets.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onCreateInvitation) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create Agent Invitation")
                    }
                }
            }
        }
        is AgentManagementState.Loaded -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.agents) { agent ->
                    EmbeddedAgentItem(agent = agent, onRevoke = { onRevoke(agent) })
                }
            }
        }
        is AgentManagementState.Error -> {
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
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun EmbeddedAgentItem(agent: AgentConnection, onRevoke: () -> Unit) {
    val statusColor = when (agent.status) {
        "active" -> MaterialTheme.colorScheme.primary
        "invited" -> MaterialTheme.colorScheme.tertiary
        "revoked" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agent.agentName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                    if (agent.agentType.isNotEmpty()) {
                        Text(
                            text = agent.agentType.replace("_", " ").replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = statusColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = agent.status.replaceFirstChar { it.uppercase() },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when (agent.approvalMode) {
                        "always_ask" -> "Always ask for approval"
                        "auto_within_contract" -> "Auto-approve within scope"
                        "auto_all" -> "Auto-approve all"
                        else -> agent.approvalMode
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (agent.status == "active") {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onRevoke,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Revoke Access")
                }
            }
        }
    }
}

@Composable
private fun ConnectionsListContent(
    connections: List<ConnectionWithLastMessage>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onConnectionClick: (String) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(
                items = connections,
                key = { it.connection.connectionId }
            ) { connectionWithMessage ->
                EmbeddedConnectionListItem(
                    connection = connectionWithMessage.connection,
                    lastMessage = connectionWithMessage.lastMessage,
                    onClick = { onConnectionClick(connectionWithMessage.connection.connectionId) }
                )
            }
        }

        if (isRefreshing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmbeddedConnectionListItem(
    connection: Connection,
    lastMessage: Message?,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        headlineContent = {
            Text(
                text = connection.peerDisplayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = lastMessage?.content ?: "No messages yet",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = connection.peerDisplayName.take(2).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        },
        trailingContent = {
            Column(
                horizontalAlignment = Alignment.End
            ) {
                val timeText = formatConnectionTime(connection.lastMessageAt ?: connection.createdAt)
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (connection.unreadCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = if (connection.unreadCount > 99) "99+" else "${connection.unreadCount}"
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun ConnectionsEmptyContent(
    onCreateInvitation: () -> Unit,
    onScanInvitation: () -> Unit
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
                imageVector = Icons.Default.People,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "No Connections Yet",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Connect with others by creating or scanning an invitation.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(onClick = onCreateInvitation) {
                Icon(
                    imageVector = Icons.Default.QrCode,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Create Invitation")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(onClick = onScanInvitation) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan Invitation")
            }
        }
    }
}

@Composable
private fun NoSearchResultsContent(query: String) {
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
                imageVector = Icons.Default.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "No results for \"$query\"",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "Try a different search term",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConnectionsErrorContent(
    message: String,
    onRetry: () -> Unit
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
            Text(
                text = "Error",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

private fun formatConnectionTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val timestampMillis = if (timestamp < 10000000000L) timestamp * 1000 else timestamp
    val diff = now - timestampMillis

    return when {
        diff < 60 * 1000 -> "now"
        diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)}m"
        diff < 24 * 60 * 60 * 1000 -> "${diff / (60 * 60 * 1000)}h"
        diff < 7 * 24 * 60 * 60 * 1000 -> "${diff / (24 * 60 * 60 * 1000)}d"
        else -> {
            val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
            sdf.format(Date(timestampMillis))
        }
    }
}

// Placeholder content for screens not yet implemented

@Composable
fun VaultServicesLogsContent() {
    PlaceholderContent(
        icon = Icons.AutoMirrored.Filled.Article,
        title = "Activity Logs",
        description = "View vault activity logs and audit trail."
    )
}

@Composable
fun AppSettingsGeneralContent(
    onNavigateToCredentialDebug: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "General",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        SettingsSection(title = "Appearance") {
            SettingsItem(
                icon = Icons.Default.Palette,
                title = "Theme",
                subtitle = "System default"
            )
            SettingsItem(
                icon = Icons.Default.TextFields,
                title = "Font Size",
                subtitle = "Medium"
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(title = "Notifications") {
            SettingsItem(
                icon = Icons.Default.Notifications,
                title = "Push Notifications",
                subtitle = "Enabled"
            )
            SettingsItem(
                icon = Icons.Default.NotificationsActive,
                title = "Sound",
                subtitle = "Default"
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(title = "About") {
            SettingsItem(
                icon = Icons.Default.Info,
                title = "Version",
                subtitle = "1.0.0"
            )
            SettingsItem(
                icon = Icons.Default.Description,
                title = "Terms of Service",
                subtitle = ""
            )
            SettingsItem(
                icon = Icons.Default.PrivacyTip,
                title = "Privacy Policy",
                subtitle = ""
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(title = "Developer") {
            SettingsItem(
                icon = Icons.Default.BugReport,
                title = "Credential Debug",
                subtitle = "Test credential.create / credential.unseal",
                onClick = onNavigateToCredentialDebug
            )
        }
    }
}

@Composable
fun AppSettingsSecurityContent(
    onNavigateToSecurityAuditLog: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Security",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        SettingsSection(title = "Authentication") {
            SettingsItem(
                icon = Icons.Default.Fingerprint,
                title = "Biometrics",
                subtitle = "Enabled"
            )
            SettingsItem(
                icon = Icons.Default.Timer,
                title = "Auto-lock",
                subtitle = "After 5 minutes"
            )
            SettingsItem(
                icon = Icons.Default.Password,
                title = "Change Password",
                subtitle = ""
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(title = "Privacy") {
            SettingsItem(
                icon = Icons.Default.ScreenLockPortrait,
                title = "Screen Lock",
                subtitle = "Hide content when switching apps"
            )
            SettingsItem(
                icon = Icons.Default.Screenshot,
                title = "Screenshots",
                subtitle = "Disabled"
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(title = "Advanced") {
            SettingsItem(
                icon = Icons.Default.Devices,
                title = "Active Sessions",
                subtitle = "1 device"
            )
            SettingsItem(
                icon = Icons.Default.Security,
                title = "Security Audit Log",
                subtitle = "View security events and migrations",
                onClick = onNavigateToSecurityAuditLog
            )
        }
    }
}

@Composable
fun AppSettingsBackupContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Backup & Recovery",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "Backup Enabled",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Last backup: 2 hours ago",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(title = "Backup Settings") {
            SettingsItem(
                icon = Icons.Default.Sync,
                title = "Auto Backup",
                subtitle = "Daily"
            )
            SettingsItem(
                icon = Icons.Default.Wifi,
                title = "Backup on WiFi Only",
                subtitle = "Enabled"
            )
            SettingsItem(
                icon = Icons.Default.History,
                title = "Backup History",
                subtitle = "View all backups"
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(title = "Recovery") {
            SettingsItem(
                icon = Icons.Default.Restore,
                title = "Recover Credentials",
                subtitle = "24-hour security delay"
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Backup, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Backup Now")
        }
    }
}

@Composable
private fun SettingsSection(
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
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit = {}
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = if (subtitle.isNotEmpty()) {
            { Text(subtitle) }
        } else null,
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
private fun PlaceholderContent(
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

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Coming soon",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}
