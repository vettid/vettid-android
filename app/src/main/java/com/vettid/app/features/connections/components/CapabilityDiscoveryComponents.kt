package com.vettid.app.features.connections.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * A capability offered by a connection's vault.
 */
data class VaultCapability(
    val id: String,
    val name: String,
    val description: String,
    val category: CapabilityCategory,
    val isEnabled: Boolean = true,
    val requiresPermission: Boolean = false,
    val version: String? = null
)

enum class CapabilityCategory {
    MESSAGING,
    FILE_SHARING,
    CREDENTIALS,
    PAYMENTS,
    IDENTITY,
    CUSTOM
}

/**
 * Card showing capabilities of a connection.
 */
@Composable
fun ConnectionCapabilitiesCard(
    capabilities: List<VaultCapability>,
    onCapabilityClick: (VaultCapability) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Available Capabilities",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (capabilities.isEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No capabilities discovered yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(modifier = Modifier.height(12.dp))

                // Group by category
                val grouped = capabilities.groupBy { it.category }
                grouped.forEach { (category, caps) ->
                    CapabilityCategorySection(
                        category = category,
                        capabilities = caps,
                        onCapabilityClick = onCapabilityClick
                    )
                }
            }
        }
    }
}

@Composable
private fun CapabilityCategorySection(
    category: CapabilityCategory,
    capabilities: List<VaultCapability>,
    onCapabilityClick: (VaultCapability) -> Unit
) {
    Column {
        Text(
            text = getCategoryName(category),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        capabilities.forEach { capability ->
            CapabilityItem(
                capability = capability,
                onClick = { onCapabilityClick(capability) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CapabilityItem(
    capability: VaultCapability,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(8.dp),
                color = getCategoryColor(capability.category).copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = getCategoryIcon(capability.category),
                        contentDescription = null,
                        tint = getCategoryColor(capability.category),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = capability.name,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (!capability.isEnabled) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = "Disabled",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (capability.requiresPermission) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Requires permission",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = capability.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Your capabilities configuration card.
 */
@Composable
fun MyCapabilitiesCard(
    capabilities: List<VaultCapability>,
    onToggleCapability: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Your Capabilities",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Control what you offer to connections",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            capabilities.forEach { capability ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = getCategoryIcon(capability.category),
                            contentDescription = null,
                            tint = getCategoryColor(capability.category),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = capability.name,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = capability.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Switch(
                        checked = capability.isEnabled,
                        onCheckedChange = { onToggleCapability(capability.id, it) }
                    )
                }

                HorizontalDivider()
            }
        }
    }
}

/**
 * Capability detail dialog.
 */
@Composable
fun CapabilityDetailDialog(
    capability: VaultCapability,
    onDismiss: () -> Unit,
    onRequestAccess: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = getCategoryIcon(capability.category),
                contentDescription = null,
                tint = getCategoryColor(capability.category)
            )
        },
        title = { Text(capability.name) },
        text = {
            Column {
                Text(capability.description)

                Spacer(modifier = Modifier.height(16.dp))

                capability.version?.let {
                    Text(
                        text = "Version: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (capability.requiresPermission) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "This capability requires permission from the connection",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (capability.requiresPermission && onRequestAccess != null) {
                Button(onClick = onRequestAccess) {
                    Text("Request Access")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
        dismissButton = if (capability.requiresPermission && onRequestAccess != null) {
            {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        } else null
    )
}

// Helper functions
private fun getCategoryName(category: CapabilityCategory): String {
    return when (category) {
        CapabilityCategory.MESSAGING -> "Messaging"
        CapabilityCategory.FILE_SHARING -> "File Sharing"
        CapabilityCategory.CREDENTIALS -> "Credentials"
        CapabilityCategory.PAYMENTS -> "Payments"
        CapabilityCategory.IDENTITY -> "Identity"
        CapabilityCategory.CUSTOM -> "Custom"
    }
}

private fun getCategoryIcon(category: CapabilityCategory): ImageVector {
    return when (category) {
        CapabilityCategory.MESSAGING -> Icons.AutoMirrored.Filled.Chat
        CapabilityCategory.FILE_SHARING -> Icons.Default.Share
        CapabilityCategory.CREDENTIALS -> Icons.Default.Badge
        CapabilityCategory.PAYMENTS -> Icons.Default.Payment
        CapabilityCategory.IDENTITY -> Icons.Default.Person
        CapabilityCategory.CUSTOM -> Icons.Default.Extension
    }
}

private fun getCategoryColor(category: CapabilityCategory): Color {
    return when (category) {
        CapabilityCategory.MESSAGING -> Color(0xFF2196F3)
        CapabilityCategory.FILE_SHARING -> Color(0xFF00BFA5)
        CapabilityCategory.CREDENTIALS -> Color(0xFF9C27B0)
        CapabilityCategory.PAYMENTS -> Color(0xFF4CAF50)
        CapabilityCategory.IDENTITY -> Color(0xFFFF9800)
        CapabilityCategory.CUSTOM -> Color(0xFF607D8B)
    }
}
