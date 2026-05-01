package com.vettid.app.features.sharing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Phase 1 of the Sharing surface (see SHARING-AND-CONTRACTS-PLAN.md).
 *
 * Four collapsible sections, three of which are placeholders for now:
 *   - Shared with me            — implemented (Phase 1)
 *   - Shared with this connection — Phase 2
 *   - Shared sandbox             — Phase 5
 *   - Connection contract        — Phase 3/4 (peer placeholder for now)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSharingScreen(
    viewModel: ConnectionSharingViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val s = state
                    Text(if (s is SharingState.Loaded) "Sharing — ${s.peerName}" else "Sharing")
                },
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
                SharingState.Loading -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                }
                is SharingState.Error -> {
                    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                        Text(
                            text = s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                is SharingState.Loaded -> LoadedSharing(
                    state = s,
                    onRequest = { viewModel.onEvent(SharingEvent.RequestItem(it)) },
                    onUpdatePolicy = { rows ->
                        viewModel.onEvent(SharingEvent.UpdatePolicy(rows))
                    },
                )
            }
        }
    }
}

@Composable
private fun LoadedSharing(
    state: SharingState.Loaded,
    onRequest: (String) -> Unit,
    onUpdatePolicy: (Map<String, SharePolicyRow>) -> Unit,
) {
    var editingRow by remember { mutableStateOf<SharePolicyRow?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SharedWithMeSection(items = state.sharedWithMe, onRequest = onRequest) }
        item {
            SharedWithConnectionSection(
                rows = state.sharedWithConnection,
                onEdit = { editingRow = it },
            )
        }
        item { SandboxSection() }
        item { ContractSection(connectionType = state.connectionType) }
    }

    editingRow?.let { row ->
        PolicyItemEditorSheet(
            row = row,
            onDismiss = { editingRow = null },
            onSave = { updated ->
                onUpdatePolicy(mapOf(updated.key to updated))
                editingRow = null
            },
        )
    }
}

@Composable
private fun SharedWithMeSection(
    items: List<SharedItem>,
    onRequest: (String) -> Unit,
) {
    SectionCard(
        title = "Shared with me",
        subtitle = "What this connection has in their catalog. Tap Request to ask for any item.",
        count = items.size,
    ) {
        if (items.isEmpty()) {
            EmptyHint("This connection hasn't published any catalog items yet.")
        } else {
            items.forEachIndexed { idx, item ->
                if (idx > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
                SharedItemRow(item = item, onRequest = { onRequest(item.key) })
            }
        }
    }
}

@Composable
private fun SharedItemRow(item: SharedItem, onRequest: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (item.kind) {
                SharedItem.Kind.DATA -> Icons.Default.PersonOutline
                SharedItem.Kind.SECRET -> Icons.Default.Lock
                SharedItem.Kind.WALLET -> Icons.Default.AccountBalanceWallet
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (item.category.isNotEmpty()) {
                Text(item.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        when (item.status) {
            RequestStatus.AVAILABLE -> {
                FilledTonalButton(onClick = onRequest, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("Request", style = MaterialTheme.typography.labelMedium)
                }
            }
            RequestStatus.PENDING -> StatusPill("Pending", MaterialTheme.colorScheme.tertiaryContainer)
            RequestStatus.APPROVED -> StatusPill("Approved", MaterialTheme.colorScheme.primaryContainer)
            RequestStatus.DENIED -> StatusPill("Denied", MaterialTheme.colorScheme.errorContainer)
            RequestStatus.EXPIRED -> StatusPill("Expired", MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun StatusPill(label: String, container: androidx.compose.ui.graphics.Color) {
    Surface(color = container, shape = RoundedCornerShape(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SharedWithConnectionSection(
    rows: List<SharePolicyRow>,
    onEdit: (SharePolicyRow) -> Unit,
) {
    val allowed = rows.count { it.allowed }
    SectionCard(
        title = "Shared with this connection",
        subtitle = "Per-item rules for what this connection can request. Tap a row to edit.",
        count = if (rows.isNotEmpty()) allowed else null,
    ) {
        if (rows.isEmpty()) {
            EmptyHint("Default policy in effect: only your published-profile fields (name, email, photo, public key) are shared. Add explicit items via the Handlers control on the connection detail; the unified editor will populate this section as you make decisions.")
        } else {
            rows.forEachIndexed { idx, row ->
                if (idx > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEdit(row) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(row.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            text = buildString {
                                append(row.category)
                                append(" · ")
                                append(if (row.allowed) "Allowed" else "Denied")
                                if (row.allowed) {
                                    append(" · ")
                                    append(row.tier)
                                    append(" · ")
                                    append(row.retention)
                                    if (row.rateLimitPerHour > 0) {
                                        append(" · ${row.rateLimitPerHour}/hr")
                                    }
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        if (row.allowed) Icons.Default.Check else Icons.Default.Block,
                        contentDescription = null,
                        tint = if (row.allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PolicyItemEditorSheet(
    row: SharePolicyRow,
    onDismiss: () -> Unit,
    onSave: (SharePolicyRow) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var allowed by remember(row.key) { mutableStateOf(row.allowed) }
    var tier by remember(row.key) { mutableStateOf(row.tier) }
    var retention by remember(row.key) { mutableStateOf(row.retention) }
    var rateLimit by remember(row.key) { mutableStateOf(row.rateLimitPerHour) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(row.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(row.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Allow / deny
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Allow this connection to request", modifier = Modifier.weight(1f))
                Switch(checked = allowed, onCheckedChange = { allowed = it })
            }

            if (allowed) {
                // Tier
                Text("Tier", style = MaterialTheme.typography.labelMedium)
                SegmentedRow(
                    options = listOf("on_demand" to "On demand", "consent" to "Consent"),
                    selected = tier,
                    onSelect = { tier = it },
                )
                // Retention
                Text("Retention", style = MaterialTheme.typography.labelMedium)
                SegmentedRow(
                    options = listOf("session" to "Session", "time_limited" to "Time-limited", "until_revoked" to "Until revoked"),
                    selected = retention,
                    onSelect = { retention = it },
                )
                // Rate limit
                Text(
                    text = "Rate limit · ${if (rateLimit == 0) "unlimited" else "$rateLimit/hr"}",
                    style = MaterialTheme.typography.labelMedium,
                )
                SegmentedRow(
                    options = listOf("0" to "Unlimited", "5" to "5/hr", "30" to "30/hr", "100" to "100/hr"),
                    selected = rateLimit.toString(),
                    onSelect = { rateLimit = it.toIntOrNull() ?: 0 },
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onSave(row.copy(
                            allowed = allowed,
                            tier = tier,
                            retention = retention,
                            rateLimitPerHour = rateLimit,
                        ))
                    },
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun SegmentedRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SandboxSection() {
    SectionCard(
        title = "Shared sandbox",
        subtitle = "A co-owned namespace for collaboration. Coming in Phase 5.",
    ) {
        EmptyHint("Not yet available.")
    }
}

@Composable
private fun ContractSection(connectionType: String) {
    val isService = connectionType == "service"
    SectionCard(
        title = "Connection contract",
        subtitle = if (isService) "Terms this service published when you connected." else "Peer connections don't use formal contracts — sharing is governed by the per-item policy above.",
    ) {
        if (isService) {
            EmptyHint("Service contract presentation lands in Phase 3/4.")
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    count: Int? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (count != null && count > 0) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
