package com.vettid.app.features.migration

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vettid.app.core.nats.MigrationConfig

/**
 * Card shown when a vault security update is available.
 *
 * Displays the update summary and provides actions to:
 * - Review details (opens browser)
 * - Update now (triggers vault re-sealing)
 * - Remind me later (defers until next app open, hidden after 72h)
 */
@Composable
fun VaultUpdateCard(
    config: MigrationConfig,
    isMandatory: Boolean,
    isUpdating: Boolean,
    onUpdateNow: () -> Unit,
    onRemindLater: () -> Unit,
    onReviewDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Vault Security Update Available",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        config.summary.ifEmpty { "A security update is available for your vault." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                }
            }

            // All three actions use TextButton so they share the same
            // visual weight — the old filled-Button "Update Now" jumped
            // out next to the two TextButton siblings. Differentiation
            // is now through the "Update Now" label's primary-colored
            // text (it's still the main action; just not shouting).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                if (!config.detailsUrl.isNullOrEmpty()) {
                    TextButton(
                        onClick = onReviewDetails,
                        enabled = !isUpdating
                    ) {
                        Text("Review Details")
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // "Remind Me Later" — hidden when mandatory
                if (!isMandatory) {
                    TextButton(
                        onClick = onRemindLater,
                        enabled = !isUpdating
                    ) {
                        Text("Remind Me Later")
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                TextButton(
                    onClick = onUpdateNow,
                    enabled = !isUpdating,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    if (isUpdating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Updating…")
                    } else {
                        Text("Update Now", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * Success confirmation shown briefly after migration completes.
 */
@Composable
fun VaultUpdateSuccessCard(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                "Vault Updated Successfully",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    }
}
