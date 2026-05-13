package com.vettid.app.features.grants

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Bottom sheet for requesting access to a peer's cataloged item.
 * Collects mode, expiry, max-uses, optional reason — caller supplies
 * the item kind/ref/label from the catalog row tapped.
 *
 * Layout notes:
 *   - Outer Column is `verticalScroll`ed so tall content stays
 *     reachable on small screens and when the keyboard opens.
 *   - Chip groups use FlowRow so each chip wraps to the next line
 *     instead of running off the edge.
 *   - imePadding pushes the action row above the soft keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RequestAccessSheet(
    itemLabel: String,
    onDismiss: () -> Unit,
    onSubmit: (mode: String, expiresAt: Long, maxUses: Int, reason: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var renewable by remember { mutableStateOf(false) }
    var expiryChoice by remember { mutableStateOf(ExpiryChoice.ONE_HOUR) }
    var maxUsesChoice by remember { mutableStateOf(MaxUsesChoice.ONE) }
    var reason by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                "Request access",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                itemLabel.ifBlank { "Item" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            SectionLabel("Access mode")
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = !renewable,
                    onClick = { renewable = false },
                    label = { Text("One-shot") },
                )
                FilterChip(
                    selected = renewable,
                    onClick = { renewable = true },
                    label = { Text("Renewable") },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (renewable) "Peer can re-fetch the value up to the max-uses limit."
                else "Peer can fetch the value exactly once.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            SectionLabel("Expires")
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ExpiryChoice.values().forEach { choice ->
                    FilterChip(
                        selected = expiryChoice == choice,
                        onClick = { expiryChoice = choice },
                        label = { Text(choice.label) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            if (renewable) {
                SectionLabel("Max uses")
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MaxUsesChoice.values().forEach { choice ->
                        FilterChip(
                            selected = maxUsesChoice == choice,
                            onClick = { maxUsesChoice = choice },
                            label = { Text(choice.label) },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("Reason (optional)") },
                placeholder = { Text("e.g. proving age to a service") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3,
            )
            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val mode = if (renewable) GrantModes.RENEWABLE else GrantModes.ONE_SHOT
                    val expiresAt = expiryChoice.toEpochSeconds()
                    val maxUses = if (renewable) maxUsesChoice.value else 1
                    onSubmit(mode, expiresAt, maxUses, reason.trim())
                }) { Text("Send request") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

private enum class ExpiryChoice(val label: String, val seconds: Long) {
    FIFTEEN_MIN("15 min", 15 * 60),
    ONE_HOUR("1 hour", 60 * 60),
    ONE_DAY("24 hours", 24 * 60 * 60),
    ONE_WEEK("7 days", 7 * 24 * 60 * 60),
    UNTIL_REVOKED("Until revoked", 0);

    fun toEpochSeconds(): Long = if (seconds == 0L) 0L else System.currentTimeMillis() / 1000 + seconds
}

private enum class MaxUsesChoice(val label: String, val value: Int) {
    ONE("1", 1),
    FIVE("5", 5),
    TWENTY("20", 20),
    UNLIMITED("Unlimited", 0);
}
