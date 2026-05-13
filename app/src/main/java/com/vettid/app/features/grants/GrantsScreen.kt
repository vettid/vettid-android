package com.vettid.app.features.grants

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Grant management — three tabs: Held in trust (inbound), Granted
 * (outbound), Pending (incoming requests awaiting decision). Scoped
 * to a single connection via SavedStateHandle["connectionId"].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrantsScreen(
    viewModel: GrantsViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val inbound by viewModel.inbound.collectAsState()
    val outbound by viewModel.outbound.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val revealedValue by viewModel.revealedValue.collectAsState()
    val busy by viewModel.busy.collectAsState()

    var tab by remember { mutableStateOf(viewModel.initialTab) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Data sharing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Held in trust (${inbound.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Granted (${outbound.size})") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Pending (${pending.size})") })
            }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            when (tab) {
                0 -> InboundList(inbound) { viewModel.reveal(it.grantId) }
                1 -> OutboundList(outbound) { viewModel.revoke(it.grantId) }
                2 -> PendingList(
                    pending = pending,
                    onApprove = { viewModel.approve(it.requestId, it.requestedExpiresAt, it.requestedMaxUses, it.requestedMode) },
                    onDeny = { viewModel.deny(it.requestId, "") },
                )
            }
        }

        revealedValue?.let { value ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissReveal() },
                title = { Text("Value") },
                text = { Text(value) },
                confirmButton = { TextButton(onClick = { viewModel.dismissReveal() }) { Text("Close") } },
            )
        }
    }
}

@Composable
private fun InboundList(grants: List<GrantSummary>, onTap: (GrantSummary) -> Unit) {
    if (grants.isEmpty()) {
        EmptyState("Nothing held in trust from this connection.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        items(grants) { g ->
            GrantRow(
                title = g.itemLabel.ifEmpty { g.itemRef },
                supportingText = supportingLine(g),
                statusBadge = g.status,
                onClick = { onTap(g) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun OutboundList(grants: List<GrantSummary>, onRevoke: (GrantSummary) -> Unit) {
    if (grants.isEmpty()) {
        EmptyState("You haven't granted any items to this connection.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        items(grants) { g ->
            ListItem(
                headlineContent = { Text(g.itemLabel.ifEmpty { g.itemRef }) },
                supportingContent = { Text(supportingLine(g)) },
                trailingContent = {
                    if (g.status == "active") {
                        TextButton(onClick = { onRevoke(g) }) { Text("Revoke") }
                    } else {
                        Text(g.status, style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun PendingList(
    pending: List<PendingRequestSummary>,
    onApprove: (PendingRequestSummary) -> Unit,
    onDeny: (PendingRequestSummary) -> Unit,
) {
    if (pending.isEmpty()) {
        EmptyState("No pending requests.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        items(pending) { p ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    "Wants access to ${p.itemLabel.ifEmpty { p.itemRef }}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${p.requestedMode} · ${formatExpiry(p.requestedExpiresAt)} · ${p.requestedMaxUses} use(s)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (p.reason.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Reason: ${p.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(onClick = { onApprove(p) }) { Text("Approve") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { onDeny(p) }) { Text("Deny") }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun GrantRow(
    title: String,
    supportingText: String,
    statusBadge: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = statusBadge == "active", onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(supportingText) },
        trailingContent = {
            AssistChip(
                onClick = {},
                label = { Text(statusBadge) },
                enabled = false,
            )
        },
    )
}

@Composable
private fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun supportingLine(g: GrantSummary): String {
    val pieces = mutableListOf<String>()
    pieces += g.mode
    pieces += formatExpiry(g.expiresAt)
    if (g.maxUses > 0) pieces += "${g.usesSoFar}/${g.maxUses} uses"
    if (g.lastFetched > 0) pieces += "last fetched ${formatTime(g.lastFetched)}"
    return pieces.joinToString(" · ")
}

private fun formatExpiry(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "Until revoked"
    val ms = epochSeconds * 1000L
    return "expires " + SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(ms))
}

private fun formatTime(epochSeconds: Long): String {
    val ms = epochSeconds * 1000L
    return SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(ms))
}
