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
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Grant management, scoped to one side of a connection's sharing
 * relationship via the route's `direction` arg:
 *
 *   "inbound"  — "Data they've shared": items they granted you, split
 *                into Current (active) / Expired (everything else).
 *   "outbound" — "Data sharing": items you granted them, split into
 *                Allowed (active) / Expired, plus Pending (their
 *                requests of you awaiting your decision).
 *
 * Scoping by direction is deliberate — the old single screen showed
 * all three direction tabs from both ConnectionDetail entry points, so
 * "Granted" appeared under both Them and You.
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
    val myRequests by viewModel.myRequests.collectAsState()
    val revealedValue by viewModel.revealedValue.collectAsState()
    val busy by viewModel.busy.collectAsState()

    val isInbound = viewModel.isInbound

    // Split this direction's grants into active vs ended (expired or
    // revoked). The per-row status badge still shows the precise state.
    val directionGrants = if (isInbound) inbound else outbound
    val activeGrants = directionGrants.filter { it.status == "active" }
    val endedGrants = directionGrants.filter { it.status != "active" }

    var tab by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.refresh() }

    // Surface errors emitted by the ViewModel (fetch denials, send
    // failures, etc.) as a snackbar. Before this, the events flow had
    // no UI consumer on this screen — a denied data-grant fetch
    // emitted "Fetch denied: …" into the void and the user saw nothing
    // happen when they tapped Reveal. (Surfaced 2026-05-16.)
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { ev ->
            if (ev is GrantsEvent.Error) {
                snackbarHostState.showSnackbar(ev.message)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (isInbound) "Data they've shared" else "Data sharing") },
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
                if (isInbound) {
                    // Them side — "Data they've shared". Requested =
                    // items you've asked them for, still awaiting (or
                    // already given) their decision.
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Current (${activeGrants.size})") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Expired (${endedGrants.size})") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Requested (${myRequests.size})") })
                } else {
                    // You side.
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Allowed (${activeGrants.size})") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Expired (${endedGrants.size})") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Pending (${pending.size})") })
                }
            }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            when {
                isInbound && tab == 0 -> InboundList(
                    grants = activeGrants,
                    emptyMessage = "Nothing currently held in trust from this connection.",
                    onTap = { viewModel.reveal(it.grantId) },
                )
                isInbound && tab == 1 -> InboundList(
                    grants = endedGrants,
                    emptyMessage = "No expired or revoked items from this connection.",
                    onTap = { viewModel.reveal(it.grantId) },
                )
                isInbound && tab == 2 -> MyRequestsList(myRequests)
                !isInbound && tab == 0 -> OutboundList(
                    grants = activeGrants,
                    emptyMessage = "You haven't granted any items to this connection.",
                    onRevoke = { viewModel.revoke(it.grantId) },
                )
                !isInbound && tab == 1 -> OutboundList(
                    grants = endedGrants,
                    emptyMessage = "No expired or revoked grants for this connection.",
                    onRevoke = { viewModel.revoke(it.grantId) },
                )
                else -> PendingList(
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
private fun InboundList(
    grants: List<GrantSummary>,
    emptyMessage: String,
    onTap: (GrantSummary) -> Unit,
) {
    if (grants.isEmpty()) {
        EmptyState(emptyMessage)
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
private fun OutboundList(
    grants: List<GrantSummary>,
    emptyMessage: String,
    onRevoke: (GrantSummary) -> Unit,
) {
    if (grants.isEmpty()) {
        EmptyState(emptyMessage)
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
private fun MyRequestsList(requests: List<OutgoingRequestSummary>) {
    if (requests.isEmpty()) {
        EmptyState("You haven't requested anything from this connection yet.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        items(requests) { r ->
            ListItem(
                headlineContent = { Text(r.itemLabel.ifEmpty { r.itemRef }) },
                supportingContent = {
                    val line = buildString {
                        append(r.mode.ifEmpty { "one-shot" })
                        if (r.status == "denied" && r.denialReason.isNotBlank()) {
                            append(" · ").append(r.denialReason)
                        } else if (r.createdAt > 0) {
                            append(" · requested ").append(formatTime(r.createdAt))
                        }
                    }
                    Text(line)
                },
                trailingContent = {
                    AssistChip(
                        onClick = {},
                        label = { Text(r.status.ifEmpty { "pending" }) },
                        enabled = false,
                    )
                },
            )
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
