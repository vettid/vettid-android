package com.vettid.app.features.personaldata

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    dataCatalog: List<PublicMetadataItem>,
    secretCatalog: List<PublicMetadataItem>,
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
            count = dataCatalog.size,
            contentDescription = "Personal data catalog",
            onClick = { showDataDialog = true },
        )
        Spacer(modifier = Modifier.width(24.dp))
        BadgeIcon(
            icon = Icons.Default.Key,
            label = "Secrets",
            count = secretCatalog.size,
            contentDescription = "Secret catalog",
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

    // Reuse the same dialogs the user sees on their own public-profile
    // preview so the peer-side view matches the self-side view exactly.
    if (showDataDialog) {
        PublicMetadataDialog(
            title = "Available Personal Data",
            subtitle = "Metadata only — peers can request these via policy. Values are never shared without consent.",
            items = dataCatalog,
            emptyMessage = "No personal data is available yet.",
            onDismiss = { showDataDialog = false },
        )
    }
    if (showSecretsDialog) {
        PublicMetadataDialog(
            title = "Available Secrets",
            subtitle = "Metadata only — peers can request these via policy. Values are never shared without consent.",
            items = secretCatalog,
            emptyMessage = "No secrets are available yet.",
            onDismiss = { showSecretsDialog = false },
        )
    }
    if (showHandlersDialog) {
        PeerHandlersDialog(
            handlers = handlers,
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

/**
 * Handler-catalog dialog for a peer's published profile. Mirrors the
 * styling of the user's own public-profile preview so peer and self
 * sides render identically. Each row expands to show the description
 * and operations chips.
 */
@Composable
private fun PeerHandlersDialog(
    handlers: List<PeerHandlerInfo>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Available Handlers")
                Spacer(modifier = Modifier.width(8.dp))
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        "${handlers.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        },
        text = {
            if (handlers.isEmpty()) {
                Text(
                    "This vault didn't publish its capability catalog.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    handlers.forEach { handler ->
                        // Convert PeerHandlerInfo to VaultHandler so the
                        // shared row composable can render it. Defaults
                        // for enabled/share_globally are fine — peer side
                        // doesn't see toggle state.
                        val vh = com.vettid.app.core.nats.VaultHandler(
                            id = handler.id,
                            name = handler.name,
                            description = handler.description,
                            operations = handler.operations,
                            category = handler.category,
                            required = handler.required,
                            shareable = handler.shareable,
                        )
                        ProfileHandlerRow(handler = vh)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
