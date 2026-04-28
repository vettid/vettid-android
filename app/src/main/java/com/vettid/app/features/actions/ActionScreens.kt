package com.vettid.app.features.actions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vettid.app.core.actions.*

/**
 * Compose surfaces for the shared-action layer:
 *
 *   ActionInvokeSheet     — peer-side: fill params, validate inline, submit
 *   ActionApprovalSheet   — owner-side: review pending invocation, Approve / Deny
 *   ActionsAvailableMenu  — connection card ⋮ submenu listing peer's enabled actions
 *
 * Settings UI (toggle / allowlist edit) lives in
 * features/settings/handlers/ — extended in a follow-up; the auth model
 * is identical and the existing screen rebuilds around the new
 * MyActionEntry list.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionInvokeSheet(
    action: PublishedAction,
    initialParams: JsonObject = JsonObject(),
    onDismiss: () -> Unit,
    onSubmit: (params: JsonObject) -> Unit,
) {
    var params by remember { mutableStateOf(initialParams) }
    var schemaError by remember { mutableStateOf<String?>(null) }
    var rawText by remember { mutableStateOf(initialParams.toString()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Send, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(action.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(action.description, style = MaterialTheme.typography.bodyMedium)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    "auth: ${action.authMode.wire}  ·  scope: ${action.scope.wire}  ·  v${action.version}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(8.dp)
                )
            }

            // Phase-1 generic param editor: raw JSON. Per-action richer
            // forms (number pads, choice pickers) land in the per-handler
            // renderer follow-up. Keeps the wire shape correct for now.
            OutlinedTextField(
                value = rawText,
                onValueChange = {
                    rawText = it
                    schemaError = null
                    runCatching {
                        val parsed = JsonParser.parseString(it).asJsonObject
                        params = parsed
                    }.onFailure { schemaError = "invalid JSON: ${it.message}" }
                },
                label = { Text("Params (JSON)") },
                supportingText = schemaError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                isError = schemaError != null,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    enabled = schemaError == null,
                    onClick = {
                        // Pre-flight schema check matches what the vault
                        // does. Vault is the trust boundary, app is UX.
                        val err = ActionSchemaValidator.validate(action.paramSchema, params)
                        if (err != null) {
                            schemaError = err
                            return@Button
                        }
                        onSubmit(params)
                    }
                ) { Text("Send request") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionApprovalSheet(
    pending: PendingActionApproval,
    onDismiss: () -> Unit,
    onApprove: () -> Unit,
    onDeny: (note: String) -> Unit,
) {
    var note by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.NotificationImportant, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Text("Action request", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "${pending.invokerGuid.take(12)}… is requesting ${pending.actionId}",
                style = MaterialTheme.typography.bodyMedium
            )
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    pending.params.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp)
                )
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (sent to invoker if denied)") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onDeny(note) }) {
                    Icon(Icons.Default.Block, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Deny", color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = onApprove) {
                    Icon(Icons.Default.Check, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Approve")
                }
            }
        }
    }
}

@Composable
fun ActionsAvailableMenu(
    actions: List<PublishedAction>,
    onPick: (PublishedAction) -> Unit,
) {
    if (actions.isEmpty()) {
        DropdownMenuItem(
            text = { Text("No actions enabled by this peer", style = MaterialTheme.typography.bodySmall) },
            enabled = false,
            onClick = {}
        )
        return
    }
    actions.forEach { a ->
        DropdownMenuItem(
            text = { Text(a.label) },
            leadingIcon = { Icon(iconForAction(a.icon), null) },
            onClick = { onPick(a) }
        )
    }
}

private fun iconForAction(name: String) = when (name) {
    "key" -> Icons.Default.Key
    "qr_code" -> Icons.Default.QrCode
    "request_quote" -> Icons.Default.RequestQuote
    "how_to_vote" -> Icons.Default.HowToVote
    "share" -> Icons.Default.Share
    "history" -> Icons.Default.History
    "person_search" -> Icons.Default.PersonSearch
    else -> Icons.Default.PlayArrow
}
