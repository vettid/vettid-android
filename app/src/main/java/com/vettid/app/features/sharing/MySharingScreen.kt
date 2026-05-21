package com.vettid.app.features.sharing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

/**
 * "My sharing" — outbound view of what THIS user shares with the
 * connection. Owns presence override, location toggle, and the
 * per-item SharePolicy editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MySharingScreen(
    viewModel: MySharingViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // Location toggle gate: when the user flips ON we have to make
    // sure ACCESS_FINE_LOCATION is granted at the OS layer; otherwise
    // the vault enables the share but the device can't actually
    // capture a fix and downstream work blows up with a permissions
    // error. Off can flip without a permission check.
    var pendingLocationEnable by remember { mutableStateOf(false) }
    var showDisableHint by remember { mutableStateOf(false) }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.onEvent(MySharingEvent.SetLocationSharing(true))
        } else {
            coroutineScope.launch {
                snackbarHost.showSnackbar(
                    message = "Location permission required. Enable it in Settings to share your location.",
                )
            }
        }
        pendingLocationEnable = false
    }

    fun handleLocationToggle(enabled: Boolean) {
        if (!enabled) {
            // Turning off — no permission needed. If auto-respond is still on,
            // disabling continuous sharing doesn't stop on-demand replies; surface
            // that so the user can disable both in one tap if they want to.
            val s = state
            if (s is MySharingState.Loaded && s.isAutoFulfillLocationEnabled) {
                showDisableHint = true
                return
            }
            viewModel.onEvent(MySharingEvent.SetLocationSharing(false))
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.onEvent(MySharingEvent.SetLocationSharing(true))
        } else {
            pendingLocationEnable = true
            locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val s = state
                    Text(if (s is MySharingState.Loaded) "Sharing with ${s.peerName}" else "My sharing")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                MySharingState.Loading -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                }
                is MySharingState.Error -> {
                    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                        Text(
                            text = s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                is MySharingState.Loaded -> Loaded(
                    state = s,
                    onPresenceChange = { viewModel.onEvent(MySharingEvent.SetPresenceOverride(it)) },
                    onLocationToggle = ::handleLocationToggle,
                    onAutoFulfillToggle = { viewModel.onEvent(MySharingEvent.SetAutoFulfillLocation(it)) },
                    onUpdatePolicy = { viewModel.onEvent(MySharingEvent.UpdatePolicy(it)) },
                )
            }
        }
    }

    if (showDisableHint) {
        val peerName = (state as? MySharingState.Loaded)?.peerName ?: "this connection"
        AlertDialog(
            onDismissRequest = { showDisableHint = false },
            title = { Text("Also turn off auto-respond?") },
            text = {
                Text(
                    "$peerName can still ask for your location and your vault will reply automatically. " +
                        "Turn that off too, or just stop continuous sharing?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDisableHint = false
                    viewModel.onEvent(MySharingEvent.SetAutoFulfillLocation(false))
                    viewModel.onEvent(MySharingEvent.SetLocationSharing(false))
                }) { Text("Turn off both") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDisableHint = false
                    viewModel.onEvent(MySharingEvent.SetLocationSharing(false))
                }) { Text("Just sharing") }
            },
        )
    }
}

@Composable
private fun Loaded(
    state: MySharingState.Loaded,
    onPresenceChange: (Boolean?) -> Unit,
    onAutoFulfillToggle: (Boolean) -> Unit,
    onLocationToggle: (Boolean) -> Unit,
    onUpdatePolicy: (SharePolicyRow) -> Unit,
) {
    // (label, rows-to-write) — a single row for an ungrouped item, the
    // whole alias group for a grouped card. Editing writes the policy
    // to every row in the list.
    var editingTarget by remember { mutableStateOf<Pair<String, List<SharePolicyRow>>?>(null) }
    var showPresencePicker by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionCard(
                title = "Live signals",
                subtitle = "Whether this peer sees your real-time presence and location.",
            ) {
                ListItem(
                    modifier = Modifier.clickable(enabled = !state.isPresenceUpdating) {
                        showPresencePicker = true
                    },
                    headlineContent = { Text("Online presence") },
                    supportingContent = {
                        Text(
                            when (state.presenceOverride) {
                                null -> "Follow account default"
                                true -> "Always shared with this connection"
                                false -> "Never shared with this connection"
                            }
                        )
                    },
                    trailingContent = {
                        if (state.isPresenceUpdating) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                ListItem(
                    headlineContent = { Text("Location") },
                    supportingContent = {
                        Text(if (state.isLocationSharingEnabled) "Sharing your latest location" else "Not sharing")
                    },
                    trailingContent = {
                        if (state.isTogglingLocation) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Switch(
                                checked = state.isLocationSharingEnabled,
                                onCheckedChange = onLocationToggle,
                            )
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                ListItem(
                    headlineContent = { Text("Auto-respond to location requests") },
                    supportingContent = {
                        Text(
                            if (state.isAutoFulfillLocationEnabled)
                                "${state.peerName} will get your current location without prompting you"
                            else
                                "You'll see a prompt each time ${state.peerName} asks"
                        )
                    },
                    trailingContent = {
                        if (state.isTogglingAutoFulfill) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Switch(
                                checked = state.isAutoFulfillLocationEnabled,
                                onCheckedChange = onAutoFulfillToggle,
                            )
                        }
                    },
                )
            }
        }

        // Capabilities and Data/secrets render as two sections of
        // alias cards so the user reads "what they can do" and "what
        // they can ask for" as two distinct decisions.
        val capabilityRows = state.rows.filter { it.key.startsWith("handler:") }
        val dataRows = state.rows.filter {
            it.key.startsWith("data:") || it.key.startsWith("secret:") || it.key.startsWith("wallet:")
        }

        item {
            SharingSectionHeader(
                title = "Capabilities",
                subtitle = "What this connection can do with your vault. Tap a card to fine-tune or revoke.",
                count = if (capabilityRows.isNotEmpty()) capabilityRows.count { it.allowed } else null,
            )
        }
        if (capabilityRows.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    EmptyHint("No shareable capabilities yet. Enable handlers in Settings → Handlers to expose any.")
                }
            }
        } else {
            aliasPolicyCards(capabilityRows, keyPrefix = "cap") { label, rows -> editingTarget = label to rows }
        }

        item {
            SharingSectionHeader(
                title = "Data & secrets",
                subtitle = "What this connection can request from your catalog. Tap a card to allow and set rules.",
                count = if (dataRows.isNotEmpty()) dataRows.count { it.allowed } else null,
            )
        }
        if (dataRows.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    EmptyHint("Default policy in effect: only your published-profile fields (name, email, photo, public key) are shared. Add explicit allowances as you go.")
                }
            }
        } else {
            aliasPolicyCards(dataRows, keyPrefix = "data") { label, rows -> editingTarget = label to rows }
        }
    }

    editingTarget?.let { (label, rows) ->
        PolicyItemEditorSheet(
            // Representative carries the alias/field label for the sheet
            // title; the edited policy is written to every row.
            row = rows.first().copy(displayName = label),
            onDismiss = { editingTarget = null },
            onSave = { updated ->
                rows.forEach { row ->
                    onUpdatePolicy(
                        row.copy(
                            allowed = updated.allowed,
                            tier = updated.tier,
                            retention = updated.retention,
                            rateLimitPerHour = updated.rateLimitPerHour,
                        )
                    )
                }
                editingTarget = null
            },
        )
    }

    if (showPresencePicker) {
        PresenceOverrideDialog(
            current = state.presenceOverride,
            onPick = {
                showPresencePicker = false
                onPresenceChange(it)
            },
            onDismiss = { showPresencePicker = false },
        )
    }
}

/**
 * Renders a section's share-policy rows as alias cards. Rows the user
 * filed under one alias collapse into a single card with ONE policy
 * control covering every member — the alias is the sharing decision,
 * not each field. Ungrouped rows are each their own card, listed first.
 */
private fun LazyListScope.aliasPolicyCards(
    rows: List<SharePolicyRow>,
    keyPrefix: String,
    onEdit: (label: String, rows: List<SharePolicyRow>) -> Unit,
) {
    val groups = buildAliasGroups(rows, aliasOf = { it.alias }, idOf = { it.key })
    val singles = groups.filter { it.label == null }
    val aliasGroups = groups.filter { it.label != null }
    items(singles, key = { "${keyPrefix}_single_${it.key}" }) { group ->
        val row = group.items.first()
        AliasCard {
            SharePolicyRow(row = row, onClick = { onEdit(row.displayName, listOf(row)) })
        }
    }
    items(aliasGroups, key = { "${keyPrefix}_group_${it.key}" }) { group ->
        val label = group.label ?: group.key
        // One policy control for the whole alias — the representative
        // SharePolicyRow carries it; member field names list below so
        // the user sees exactly what the decision covers.
        val representative = group.items.first().copy(displayName = label)
        AliasCard {
            SharePolicyRow(row = representative, onClick = { onEdit(label, group.items) })
            group.items.forEach { member ->
                Text(
                    text = member.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, end = 8.dp, bottom = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun PresenceOverrideDialog(
    current: Boolean?,
    onPick: (Boolean?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Online presence for this connection") },
        text = {
            Column {
                PresenceOption(
                    label = "Follow account default",
                    description = "Use the global Share online presence setting",
                    selected = current == null,
                    onClick = { onPick(null) },
                )
                PresenceOption(
                    label = "Always share",
                    description = "This peer sees you online regardless of the default",
                    selected = current == true,
                    onClick = { onPick(true) },
                )
                PresenceOption(
                    label = "Never share",
                    description = "This peer never sees you online",
                    selected = current == false,
                    onClick = { onPick(false) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun PresenceOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
