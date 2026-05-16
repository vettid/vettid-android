package com.vettid.app.features.migration

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.nats.AuditLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Screen displaying security audit log entries.
 *
 * Shows a chronological list of security events including:
 * - Enclave migrations
 * - Emergency recoveries
 * - Other security-related events
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityAuditLogScreen(
    onBack: () -> Unit,
    viewModel: SecurityAuditLogViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val filters by viewModel.filters.collectAsState()
    val connections by viewModel.connections.collectAsState()
    var showFilters by remember { mutableStateOf(false) }
    var showCustomDatePicker by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Storage Access Framework launcher: lets the user pick where to
    // write the export. JSON includes the chain-hashed entries plus a
    // record of which filter produced the view so it's self-describing.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val json = viewModel.buildExportJson()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            snackbarHostState.showSnackbar(if (ok) "Audit log exported" else "Export failed")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Security Audit Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showFilters = !showFilters }) {
                        Icon(
                            if (filters.hasActive) Icons.Default.FilterAlt else Icons.Default.FilterAltOff,
                            contentDescription = if (showFilters) "Hide filters" else "Show filters",
                            tint = if (filters.hasActive) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = {
                        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                        exportLauncher.launch("vettid-audit-$ts.json")
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Export")
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Filter row visibility is now ONLY user-controlled. The
            // previous `|| filters.hasActive` clause kept the row pinned
            // open whenever any filter was set, eating screen space and
            // squeezing the log to one row at the bottom. The filter
            // button in the top bar tints when filters are active so
            // there's still a clear "filters in effect" signal.
            if (showFilters) {
                FilterChipRow(
                    filters = filters,
                    onToggle = { id -> viewModel.toggleCategory(id) },
                    onClear = { viewModel.clearFilters() },
                    modifier = Modifier.fillMaxWidth(),
                )
                DateFilterRow(
                    selectedPresetId = filters.datePresetId,
                    onPresetSelected = { id ->
                        if (id == "custom") {
                            showCustomDatePicker = true
                        }
                        viewModel.applyDatePreset(id)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (connections.isNotEmpty()) {
                    ConnectionFilterDropdown(
                        connections = connections,
                        selected = filters.selectedConnectionIds,
                        onToggle = { viewModel.toggleConnection(it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (showCustomDatePicker) {
                CustomDateRangeDialog(
                    initialStartMs = filters.startSeconds?.times(1000L),
                    initialEndMs = filters.endSeconds?.times(1000L),
                    onDismiss = { showCustomDatePicker = false },
                    onConfirm = { startMs, endMs ->
                        viewModel.setCustomDateRange(
                            startSeconds = startMs?.div(1000L),
                            endSeconds = endMs?.div(1000L),
                        )
                        showCustomDatePicker = false
                    },
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (val currentState = state) {
                    is SecurityAuditLogState.Loading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }

                    is SecurityAuditLogState.Empty -> {
                        EmptyAuditLogContent(modifier = Modifier.align(Alignment.Center))
                    }

                    is SecurityAuditLogState.Success -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            ChainStatusPill(
                                status = currentState.chainStatus,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            AuditLogList(
                                entries = currentState.entries,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    is SecurityAuditLogState.Error -> {
                        ErrorContent(
                            message = currentState.message,
                            onRetry = { viewModel.refresh() },
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Horizontal scroll of chips, one per category. Selected chips fill;
 * the trailing Clear pill resets all filters. Visibility controlled
 * by the parent so the row hides itself when no filter is active and
 * the user hasn't asked to see it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterChipRow(
    filters: SecurityAuditFilters,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SECURITY_AUDIT_CATEGORIES.forEach { cat ->
                FilterChip(
                    selected = cat.id in filters.selectedCategories,
                    onClick = { onToggle(cat.id) },
                    label = { Text(cat.label) },
                )
            }
            if (filters.hasActive) {
                AssistChip(
                    onClick = onClear,
                    label = { Text("Clear all") },
                    leadingIcon = { Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp)) },
                )
            }
        }
    }
}

/**
 * Date-preset chips row: Today / Last 7 days / Last 30 days / All time / Custom.
 * "Custom" triggers the parent's date-picker dialog via the same callback.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DateFilterRow(
    selectedPresetId: String,
    onPresetSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SECURITY_AUDIT_DATE_PRESETS.forEach { preset ->
                FilterChip(
                    selected = preset.id == selectedPresetId,
                    onClick = { onPresetSelected(preset.id) },
                    label = { Text(preset.label) },
                )
            }
        }
    }
}

/**
 * Multi-select connection dropdown shown only when ≥1 connection is
 * cached. Anchored to a button that summarizes the current selection
 * ("All connections" / "1 connection" / "N connections"). Lighter than
 * inlining a chip per connection — keeps the filter row tidy even for
 * users with many peers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionFilterDropdown(
    connections: List<AuditConnectionOption>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val summary = when {
        selected.isEmpty() -> "All connections"
        selected.size == 1 -> connections.firstOrNull { it.connectionId in selected }?.label ?: "1 connection"
        else -> "${selected.size} connections"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            AssistChip(
                onClick = { expanded = true },
                label = { Text(summary) },
                leadingIcon = { Icon(Icons.Default.People, null, modifier = Modifier.size(18.dp)) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                connections.forEach { conn ->
                    DropdownMenuItem(
                        text = { Text(conn.label) },
                        leadingIcon = {
                            Checkbox(
                                checked = conn.connectionId in selected,
                                onCheckedChange = { onToggle(conn.connectionId) },
                            )
                        },
                        onClick = { onToggle(conn.connectionId) },
                    )
                }
            }
        }
    }
}

/**
 * Two-step custom date-range picker. Uses Material3 DateRangePicker
 * which already handles min/max + per-locale presentation. Returns
 * (startMs, endMs) where end defaults to end-of-selected-day so the
 * user's "through Tuesday" intent includes Tuesday's events.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomDateRangeDialog(
    initialStartMs: Long?,
    initialEndMs: Long?,
    onDismiss: () -> Unit,
    onConfirm: (startMs: Long?, endMs: Long?) -> Unit,
) {
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStartMs,
        initialSelectedEndDateMillis = initialEndMs,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val start = pickerState.selectedStartDateMillis
                // end-of-day for the picked end date so the chosen
                // range is inclusive of all of that day's events.
                val end = pickerState.selectedEndDateMillis?.let { it + (24L * 3600 * 1000) - 1 }
                onConfirm(start, end)
            }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        DateRangePicker(state = pickerState)
    }
}

/**
 * Aggregate chain integrity badge shown at the top of the log. Three
 * states map to three colors: green (verified), amber (unsigned),
 * red (tampered). Empty hides the pill entirely.
 */
@Composable
private fun ChainStatusPill(
    status: com.vettid.app.core.audit.AuditChainVerifier.ChainStatus,
    modifier: Modifier = Modifier,
) {
    val (bg, fg, icon, text) = when (status) {
        is com.vettid.app.core.audit.AuditChainVerifier.ChainStatus.Empty -> return
        is com.vettid.app.core.audit.AuditChainVerifier.ChainStatus.Verified -> {
            val unsignedPart = if (status.unsignedRows > 0) " · ${status.unsignedRows} unsigned" else ""
            Quad(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
                androidx.compose.material.icons.Icons.Default.Verified,
                "Chain verified · ${status.signedRows} signed$unsignedPart",
            )
        }
        is com.vettid.app.core.audit.AuditChainVerifier.ChainStatus.Unsigned -> Quad(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            androidx.compose.material.icons.Icons.Default.Info,
            "Chain unsigned · unlock your vault to verify (${status.rows} entries)",
        )
        is com.vettid.app.core.audit.AuditChainVerifier.ChainStatus.Tampered -> Quad(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            androidx.compose.material.icons.Icons.Default.Warning,
            "Chain integrity broken: ${status.reason}",
        )
    }
    Surface(
        color = bg,
        contentColor = fg,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Tuple of four — local helper for ChainStatusPill destructuring. */
private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/** Per-row icon: verified ✓ / unsigned ? / tampered ⚠. Compact for inline use. */
@Composable
private fun VerificationBadge(state: com.vettid.app.core.audit.AuditChainVerifier.RowState) {
    val (icon, tint, label) = when (state) {
        com.vettid.app.core.audit.AuditChainVerifier.RowState.Verified -> Triple(
            androidx.compose.material.icons.Icons.Default.Verified,
            MaterialTheme.colorScheme.primary,
            "Verified",
        )
        com.vettid.app.core.audit.AuditChainVerifier.RowState.Tampered -> Triple(
            androidx.compose.material.icons.Icons.Default.Warning,
            MaterialTheme.colorScheme.error,
            "Tampered",
        )
        com.vettid.app.core.audit.AuditChainVerifier.RowState.Unsigned -> Triple(
            androidx.compose.material.icons.Icons.Default.Info,
            MaterialTheme.colorScheme.outline,
            "Unsigned",
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(14.dp), tint = tint)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
private fun AuditLogList(
    entries: List<AuditLogEntry>,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(entries) { entry ->
            AuditLogItem(entry = entry)
        }
    }
}

@Composable
private fun AuditLogItem(
    entry: AuditLogEntry,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = getIconForEventType(entry.type),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = getColorForEventType(entry.type)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    if (entry.peerName != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "with ${entry.peerName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (entry.description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = entry.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTimestamp(entry.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                VerificationBadge(state = entry.verification)
            }
        }
    }
}

@Composable
private fun EmptyAuditLogContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Security,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "No Security Events",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Security events like migrations and recoveries will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Failed to Load",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

@Composable
private fun getIconForEventType(type: String) = when (type) {
    "migration_sealed_material" -> Icons.Default.Sync
    "migration_verified" -> Icons.Default.Security
    "migration_old_version_deleted" -> Icons.Default.Delete
    "emergency_recovery" -> Icons.Default.Warning
    else -> Icons.Default.Info
}

@Composable
private fun getColorForEventType(type: String) = when (type) {
    "migration_verified" -> MaterialTheme.colorScheme.primary
    "emergency_recovery" -> MaterialTheme.colorScheme.error
    "migration_old_version_deleted" -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.secondary
}

private fun formatTimestamp(timestamp: Long): String {
    val dateFormat = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
    return dateFormat.format(Date(com.vettid.app.util.toEpochMillis(timestamp)))
}
