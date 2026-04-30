package com.vettid.app.features.connections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.nats.ConnectionRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Read-only list of connections that have left the active feed —
 * declined invites, revoked connections, expired invitations. Each
 * row carries the peer's name (when known), what happened, and when.
 *
 * No tap-into-detail action: these connections are terminal in the
 * vault. If the user wants to reconnect, they create a fresh invite
 * from the connections feed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedConnectionsScreen(
    viewModel: ArchivedConnectionsViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                ArchivedConnectionsState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                ArchivedConnectionsState.Empty -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No archived connections",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Declined, revoked, and expired connections will appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is ArchivedConnectionsState.Error -> {
                    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                is ArchivedConnectionsState.Loaded -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(s.items, key = { it.connectionId }) { conn ->
                            ArchivedConnectionRow(conn)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchivedConnectionRow(conn: ConnectionRecord) {
    val peerName = listOfNotNull(
        conn.peerProfile?.firstName, conn.peerProfile?.lastName
    ).joinToString(" ").trim().ifEmpty {
        conn.label.ifEmpty { "Unknown peer" }
    }
    val (statusLabel, statusColor) = when (conn.status) {
        "rejected", "declined_by_us" -> "You declined" to MaterialTheme.colorScheme.errorContainer
        "declined_by_peer" -> "Peer declined" to MaterialTheme.colorScheme.errorContainer
        "revoked" -> "Revoked" to MaterialTheme.colorScheme.errorContainer
        "expired" -> "Expired" to MaterialTheme.colorScheme.tertiaryContainer
        else -> conn.status to MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = peerName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Surface(color = statusColor, shape = RoundedCornerShape(4.dp)) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            conn.peerProfile?.email?.let { email ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = formatTimestamp(conn.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTimestamp(iso: String): String = try {
    val instant = Instant.parse(iso)
    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a").withZone(ZoneId.systemDefault())
    formatter.format(instant)
} catch (_: Exception) {
    iso
}
