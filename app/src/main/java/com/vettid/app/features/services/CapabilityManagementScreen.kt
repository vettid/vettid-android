package com.vettid.app.features.services

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.features.services.models.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Screen for managing individual capabilities within active contracts.
 *
 * Shows:
 * - All granted capabilities
 * - Toggle for optional capabilities
 * - Required capabilities (locked)
 * - Last used timestamps
 *
 * Issue #41 [AND-032] - Capability Management UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilityManagementScreen(
    contractId: String,
    serviceName: String,
    viewModel: CapabilityManagementViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val capabilities by viewModel.capabilities.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val pendingChanges by viewModel.pendingChanges.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()

    // Load capabilities on first composition
    LaunchedEffect(contractId) {
        viewModel.loadCapabilities(contractId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Capabilities")
                        Text(
                            text = serviceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            if (pendingChanges.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "${pendingChanges.size} change(s) pending",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = viewModel::discardChanges,
                                modifier = Modifier.weight(1f),
                                enabled = !isSaving
                            ) {
                                Text("Discard")
                            }
                            Button(
                                onClick = viewModel::saveChanges,
                                modifier = Modifier.weight(1f),
                                enabled = !isSaving
                            ) {
                                if (isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Text("Save Changes")
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        when {
            isLoading -> {
                LoadingContent(modifier = Modifier.padding(padding))
            }
            capabilities.isEmpty() -> {
                EmptyContent(modifier = Modifier.padding(padding))
            }
            else -> {
                CapabilitiesList(
                    capabilities = capabilities,
                    pendingChanges = pendingChanges,
                    onToggle = viewModel::toggleCapability,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
private fun CapabilitiesList(
    capabilities: List<GrantedCapability>,
    pendingChanges: Map<String, Boolean>,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // Group by category
    val grouped = capabilities.groupBy { it.category }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        grouped.forEach { (category, caps) ->
            item {
                CategoryHeader(category = category)
            }

            items(caps, key = { it.id }) { capability ->
                val pendingState = pendingChanges[capability.id]
                val currentEnabled = pendingState ?: capability.enabled

                CapabilityToggleRow(
                    capability = capability,
                    isEnabled = currentEnabled,
                    hasPendingChange = pendingState != null,
                    onToggle = { enabled -> onToggle(capability.id, enabled) }
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Spacer for bottom bar
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun CategoryHeader(category: CapabilityCategory) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when (category) {
                CapabilityCategory.IDENTITY -> Icons.Outlined.Badge
                CapabilityCategory.DATA -> Icons.Outlined.Storage
                CapabilityCategory.COMMUNICATION -> Icons.Outlined.Message
                CapabilityCategory.FINANCIAL -> Icons.Outlined.Payments
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = category.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CapabilityToggleRow(
    capability: GrantedCapability,
    isEnabled: Boolean,
    hasPendingChange: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (hasPendingChange) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = capability.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    if (capability.required) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "Required",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (hasPendingChange) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "Modified",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Text(
                    text = capability.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                capability.lastUsed?.let { lastUsed ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Last used: ${formatRelativeTime(lastUsed)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (capability.usageCount > 0) {
                            Text(
                                text = " • ${capability.usageCount} times",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            if (capability.required) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Required capability",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle
                )
            }
        }
    }
}

private fun formatRelativeTime(instant: Instant): String {
    val now = java.time.Instant.now()
    val diff = now.toEpochMilli() - instant.toEpochMilli()
    val minutes = diff / (60 * 1000)
    val hours = diff / (60 * 60 * 1000)
    val days = diff / (24 * 60 * 60 * 1000)

    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> {
            val formatter = DateTimeFormatter.ofPattern("MMM d")
                .withZone(ZoneId.systemDefault())
            formatter.format(instant)
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No capabilities",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This contract doesn't have any configurable capabilities",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
