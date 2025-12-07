package com.vettid.app.features.nats

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Full screen for NATS connection setup and status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NatsConnectionScreen(
    viewModel: NatsSetupViewModel = hiltViewModel(),
    authToken: String?,
    onNavigateBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(authToken) {
        authToken?.let { token ->
            if (state is NatsSetupState.Initial || state is NatsSetupState.Disconnected) {
                viewModel.onEvent(NatsSetupEvent.SetupNats(token))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NATS Connection") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val currentState = state) {
                is NatsSetupState.Initial -> InitialContent()
                is NatsSetupState.CheckingAccount -> LoadingContent("Checking account...")
                is NatsSetupState.CreatingAccount -> LoadingContent("Creating NATS account...")
                is NatsSetupState.GeneratingToken -> LoadingContent("Generating token...")
                is NatsSetupState.Connecting -> LoadingContent("Connecting to NATS...")
                is NatsSetupState.Connected -> ConnectedContent(
                    state = currentState,
                    onDisconnect = { viewModel.onEvent(NatsSetupEvent.Disconnect) }
                )
                is NatsSetupState.Disconnected -> DisconnectedContent(
                    state = currentState,
                    onReconnect = {
                        authToken?.let { viewModel.onEvent(NatsSetupEvent.SetupNats(it)) }
                    }
                )
                is NatsSetupState.Error -> ErrorContent(
                    state = currentState,
                    onRetry = { viewModel.onEvent(NatsSetupEvent.Retry) },
                    onDismiss = { viewModel.onEvent(NatsSetupEvent.DismissError) }
                )
            }
        }
    }
}

@Composable
private fun InitialContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Cloud,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "NATS Messaging",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Real-time communication with your vault",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LoadingContent(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun ConnectedContent(
    state: NatsSetupState.Connected,
    onDisconnect: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Connection status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            color = Color(0xFF4CAF50),
                            shape = RoundedCornerShape(6.dp)
                        )
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Connected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Real-time messaging active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Account details
        Text(
            text = "Account Details",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(8.dp))

        DetailRow("Owner Space", state.account.ownerSpaceId)
        DetailRow("Message Space", state.account.messageSpaceId)
        DetailRow("Endpoint", state.account.natsEndpoint)
        DetailRow("Token Expires", state.tokenExpiresAt)

        Spacer(modifier = Modifier.height(24.dp))

        // Permissions
        Text(
            text = "Permissions",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Publish Topics",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.permissions.publish.forEach { topic ->
                    Text(
                        text = "  $topic",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Subscribe Topics",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.permissions.subscribe.forEach { topic ->
                    Text(
                        text = "  $topic",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Disconnect button
        OutlinedButton(
            onClick = onDisconnect,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.CloudOff, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Disconnect")
        }
    }
}

@Composable
private fun DisconnectedContent(
    state: NatsSetupState.Disconnected,
    onReconnect: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Disconnected",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        state.reason?.let { reason ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onReconnect) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Reconnect")
        }
    }
}

@Composable
private fun ErrorContent(
    state: NatsSetupState.Error,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Connection Error",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(onClick = onDismiss) {
                Text("Dismiss")
            }
            if (state.retryable) {
                Button(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Retry")
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
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
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
    }
}

/**
 * Compact status indicator for use in other screens.
 */
@Composable
fun NatsStatusIndicator(
    viewModel: NatsSetupViewModel = hiltViewModel(),
    onClick: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val summary = viewModel.getConnectionSummary()

    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = when (summary.icon) {
            NatsStatusIcon.CONNECTED -> Color(0xFF4CAF50).copy(alpha = 0.1f)
            NatsStatusIcon.CONNECTING -> MaterialTheme.colorScheme.primaryContainer
            NatsStatusIcon.ERROR -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = when (summary.icon) {
                            NatsStatusIcon.CONNECTED -> Color(0xFF4CAF50)
                            NatsStatusIcon.CONNECTING -> MaterialTheme.colorScheme.primary
                            NatsStatusIcon.ERROR -> MaterialTheme.colorScheme.error
                            NatsStatusIcon.DISCONNECTED -> Color.Gray
                            NatsStatusIcon.NOT_SETUP -> Color.Gray
                        },
                        shape = RoundedCornerShape(4.dp)
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = summary.statusText,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

/**
 * Card showing NATS connection status for home screen.
 */
@Composable
fun NatsConnectionCard(
    viewModel: NatsSetupViewModel = hiltViewModel(),
    authToken: String?,
    onSetupClick: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSetupClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (state) {
                    is NatsSetupState.Connected -> Icons.Default.Cloud
                    is NatsSetupState.Error -> Icons.Default.CloudOff
                    else -> Icons.Default.Cloud
                },
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = when (state) {
                    is NatsSetupState.Connected -> Color(0xFF4CAF50)
                    is NatsSetupState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Real-time Messaging",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = when (state) {
                        is NatsSetupState.Connected -> "Connected to vault"
                        is NatsSetupState.Connecting,
                        is NatsSetupState.GeneratingToken,
                        is NatsSetupState.CreatingAccount,
                        is NatsSetupState.CheckingAccount -> "Connecting..."
                        is NatsSetupState.Error -> "Connection error"
                        is NatsSetupState.Disconnected -> "Tap to connect"
                        is NatsSetupState.Initial -> "Not configured"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when (state) {
                is NatsSetupState.Connected -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Connected",
                        tint = Color(0xFF4CAF50)
                    )
                }
                is NatsSetupState.Connecting,
                is NatsSetupState.GeneratingToken,
                is NatsSetupState.CreatingAccount,
                is NatsSetupState.CheckingAccount -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Setup",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
