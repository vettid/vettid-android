package com.vettid.app.features.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.nats.FeedEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-connection audit trail. Opened from the feed card's More > History,
 * and intended as the user's complete record of interactions with a peer:
 * messages, calls (voice + video, including missed), connection
 * lifecycle, key rotations, etc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionHistoryScreen(
    onBack: () -> Unit,
    viewModel: ConnectionHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Interaction History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        when (val current = state) {
            is ConnectionHistoryState.Loading -> LoadingCentered(padding)
            is ConnectionHistoryState.Empty -> EmptyCentered(padding)
            is ConnectionHistoryState.Error -> ErrorCentered(padding, current.message)
            is ConnectionHistoryState.Loaded -> HistoryList(padding, current.events)
        }
    }
}

@Composable
private fun LoadingCentered(padding: PaddingValues) {
    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyCentered(padding: PaddingValues) {
    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No recorded interactions yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorCentered(padding: PaddingValues, message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(24.dp)
        )
    }
}

@Composable
private fun HistoryList(padding: PaddingValues, events: List<FeedEvent>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(items = events, key = { it.eventId }) { event ->
            HistoryRow(event)
        }
    }
}

@Composable
private fun HistoryRow(event: FeedEvent) {
    val (icon, tint) = iconForEventType(event.eventType)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .then(Modifier),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = CircleShape,
                color = tint.copy(alpha = 0.15f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.title.ifEmpty { event.eventType },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            event.message?.takeIf { it.isNotBlank() }?.let { body ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatEventTimestamp(event.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
}

/**
 * Map event type → (icon, tint). Aligned with FeedScreen's card icon so
 * a phone call shows the same green phone in both places.
 */
@Composable
private fun iconForEventType(eventType: String): Pair<ImageVector, Color> {
    val green = Color(0xFF4CAF50)
    val red = Color(0xFFE53935)
    return when {
        eventType == "call.missed" -> Icons.Default.CallMissed to red
        eventType.startsWith("call.") -> Icons.Default.Call to green
        eventType.startsWith("message.") -> Icons.AutoMirrored.Filled.Message to MaterialTheme.colorScheme.primary
        eventType == "connection.revoked" -> Icons.Default.PersonRemove to red
        eventType.startsWith("connection.") -> Icons.Default.Person to MaterialTheme.colorScheme.secondary
        eventType.startsWith("transfer.") -> Icons.Default.SwapHoriz to MaterialTheme.colorScheme.tertiary
        eventType.startsWith("security.") -> Icons.Default.Security to red
        else -> Icons.Default.Event to MaterialTheme.colorScheme.outline
    }
}

private fun formatEventTimestamp(epochSeconds: Long): String {
    // Vault-side timestamps are seconds; guard against millis just in case.
    val millis = if (epochSeconds < 10_000_000_000L) epochSeconds * 1000 else epochSeconds
    val fmt = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
    return fmt.format(Date(millis))
}
