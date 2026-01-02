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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.network.Connection
import com.vettid.app.core.network.ConnectionWithLastMessage
import com.vettid.app.core.network.Message
import com.vettid.app.features.connections.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Embedded connections content without Scaffold.
 * Used within MainScaffold for the Vault > Connections tab.
 */
@Composable
fun ConnectionsContentEmbedded(
    viewModel: ConnectionsViewModel = hiltViewModel(),
    onConnectionClick: (String) -> Unit = {},
    onCreateInvitation: () -> Unit = {},
    onScanInvitation: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    var showFabMenu by remember { mutableStateOf(false) }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ConnectionsEffect.NavigateToConnection -> onConnectionClick(effect.connectionId)
                is ConnectionsEffect.NavigateToCreateInvitation -> onCreateInvitation()
                is ConnectionsEffect.NavigateToScanInvitation -> onScanInvitation()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Search bar
            if (state is ConnectionsState.Loaded) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.onSearchQueryChanged(it) },
                    placeholder = { Text("Search connections...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear search"
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

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

        // FAB
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End
        ) {
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
        icon = Icons.Default.Article,
        title = "Activity Logs",
        description = "View vault activity logs and audit trail."
    )
}

@Composable
fun AppSettingsGeneralContent() {
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
    }
}

@Composable
fun AppSettingsSecurityContent() {
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
