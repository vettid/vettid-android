package com.vettid.app.features.personaldata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vettid.app.core.nats.PeerHandlerInfo
import com.vettid.app.core.nats.PeerPublicSecretMetadata

/**
 * Three-icon row (Data / Secrets / Handlers) that renders above a
 * public profile — on the user's own preview AND on every peer
 * profile surface so both sides see the same "what's shared" summary.
 *
 * Tapping a badge opens a dialog with the items. The dialog shows
 * metadata only (names, types, categories) — never values — because
 * the profile is the boundary between "owner knows everything" and
 * "connections see what was opted in."
 *
 * Data = count of public personal-data items (peer-side: published
 * fields; self-side: items marked `isInPublicProfile`). Secrets =
 * metadata the user published. Handlers = the vault capability
 * catalog the peer's enclave supports. All three populate to 0
 * gracefully when the input is empty, so a peer with an older vault
 * that doesn't publish handlers/secrets shows "0" instead of
 * breaking the layout.
 */
@Composable
fun PublishedProfileBadges(
    dataItems: List<PersonalDataItem>,
    publicSecrets: List<PeerPublicSecretMetadata>,
    handlers: List<PeerHandlerInfo>,
    modifier: Modifier = Modifier,
) {
    var showDataDialog by remember { mutableStateOf(false) }
    var showSecretsDialog by remember { mutableStateOf(false) }
    var showHandlersDialog by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BadgeIcon(
            icon = Icons.Default.Person,
            label = "Data",
            count = dataItems.size,
            contentDescription = "Public personal data",
            onClick = { showDataDialog = true },
        )
        Spacer(modifier = Modifier.width(24.dp))
        BadgeIcon(
            icon = Icons.Default.Key,
            label = "Secrets",
            count = publicSecrets.size,
            contentDescription = "Public secret metadata",
            onClick = { showSecretsDialog = true },
        )
        Spacer(modifier = Modifier.width(24.dp))
        BadgeIcon(
            icon = Icons.Default.Extension,
            label = "Handlers",
            count = handlers.size,
            contentDescription = "Vault capabilities",
            onClick = { showHandlersDialog = true },
        )
    }

    if (showDataDialog) {
        PublicItemsDialog(
            title = "Public Data",
            emptyMessage = "No personal data is shared on this profile yet.",
            itemCount = dataItems.size,
            rows = dataItems.map { item ->
                Triple(item.name, item.type.name, item.category?.displayName ?: "Other")
            },
            onDismiss = { showDataDialog = false },
        )
    }
    if (showSecretsDialog) {
        PublicItemsDialog(
            title = "Public Secrets",
            emptyMessage = "No secret metadata is shared on this profile yet.",
            itemCount = publicSecrets.size,
            rows = publicSecrets.map { s ->
                Triple(s.name, s.type.ifBlank { "SECRET" }, s.category.ifBlank { "Other" })
            },
            onDismiss = { showSecretsDialog = false },
        )
    }
    if (showHandlersDialog) {
        PublicItemsDialog(
            title = "Vault Capabilities",
            emptyMessage = "This vault didn't publish its capability catalog.",
            itemCount = handlers.size,
            rows = handlers.map { h ->
                Triple(
                    h.name,
                    if (h.operations.isEmpty()) "0 operations" else "${h.operations.size} operations",
                    h.description.ifBlank { "" },
                )
            },
            onDismiss = { showHandlersDialog = false },
        )
    }
}

@Composable
private fun BadgeIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription)
        }
        Text(
            text = "$label ($count)",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun PublicItemsDialog(
    title: String,
    emptyMessage: String,
    itemCount: Int,
    rows: List<Triple<String, String, String>>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (rows.isEmpty()) {
                    Text(
                        text = emptyMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "$itemCount item${if (itemCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    rows.forEachIndexed { index, (name, type, category) ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 6.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                            )
                        }
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val meta = listOf(type, category).filter { it.isNotBlank() }.joinToString(" · ")
                        if (meta.isNotBlank()) {
                            Text(
                                text = meta,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
