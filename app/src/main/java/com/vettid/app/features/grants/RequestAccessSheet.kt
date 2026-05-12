package com.vettid.app.features.grants

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Sheet for requesting access to a peer's cataloged item. Collects
 * mode, expiry, max-uses, optional reason — caller supplies the
 * item kind/ref/label from the catalog row tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                "Request access",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                itemLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            Text("Mode", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !renewable, onClick = { renewable = false }, label = { Text("One-shot") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = renewable, onClick = { renewable = true }, label = { Text("Renewable") })
            }
            Spacer(Modifier.height(12.dp))

            Text("Expires", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                ExpiryChoice.values().forEach { choice ->
                    FilterChip(
                        selected = expiryChoice == choice,
                        onClick = { expiryChoice = choice },
                        label = { Text(choice.label) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            if (renewable) {
                Text("Max uses", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    MaxUsesChoice.values().forEach { choice ->
                        FilterChip(
                            selected = maxUsesChoice == choice,
                            onClick = { maxUsesChoice = choice },
                            label = { Text(choice.label) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("Reason (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3,
            )
            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
