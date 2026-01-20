package com.vettid.app.features.connections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.network.Connection
import com.vettid.app.core.network.ConnectionWithLastMessage
import com.vettid.app.core.network.Message
import java.text.SimpleDateFormat
import java.util.*

/**
 * Screen displaying the list of connections.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
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
                is ConnectionsEffect.NavigateToConnection -> {
                    onConnectionClick(effect.connectionId)
                }
                is ConnectionsEffect.NavigateToCreateInvitation -> {
                    onCreateInvitation()
                }
                is ConnectionsEffect.NavigateToScanInvitation -> {
                    onScanInvitation()
                }
                is ConnectionsEffect.ShowFilterSheet -> {
                    // TODO: Show filter bottom sheet
                }
                is ConnectionsEffect.ShowSnackbar -> {
                    // TODO: Show snackbar with effect.message
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connections") },
                actions = {
                    val currentState = state
                    if (currentState is ConnectionsState.Loaded && currentState.totalUnread > 0) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.error
                        ) {
                            Text("${currentState.totalUnread}")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End
            ) {
                // Mini FABs
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

                // Main FAB
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            if (state is ConnectionsState.Loaded) {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = { viewModel.onSearchQueryChanged(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            when (val currentState = state) {
                is ConnectionsState.Loading -> {
                    LoadingContent()
                }

                is ConnectionsState.Empty -> {
                    EmptyContent(
                        onCreateInvitation = { viewModel.onCreateInvitation() },
                        onScanInvitation = { viewModel.onScanInvitation() }
                    )
                }

                is ConnectionsState.Loaded -> {
                    if (currentState.connections.isEmpty() && currentState.isSearchResult) {
                        NoSearchResultsContent(query = searchQuery)
                    } else {
                        ConnectionsList(
                            connections = currentState.connections,
                            isRefreshing = isRefreshing,
                            onRefresh = { viewModel.refresh() },
                            onConnectionClick = { viewModel.onConnectionClick(it) }
                        )
                    }
                }

                is ConnectionsState.Error -> {
                    ErrorContent(
                        message = currentState.message,
                        onRetry = { viewModel.loadConnections() }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search connections...") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear search"
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
    )
}

@Composable
private fun ConnectionsList(
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
                ConnectionListItem(
                    connection = connectionWithMessage.connection,
                    lastMessage = connectionWithMessage.lastMessage,
                    onClick = { onConnectionClick(connectionWithMessage.connection.connectionId) }
                )
            }
        }

        // Simple refresh indicator at top
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
fun ConnectionListItem(
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
            // Avatar
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
                // Time
                val timeText = formatTime(connection.lastMessageAt ?: connection.createdAt)
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Unread badge
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
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyContent(
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
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
private fun ErrorContent(
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
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

// MARK: - Utility Functions

private fun formatTime(timestamp: Long): String {
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
