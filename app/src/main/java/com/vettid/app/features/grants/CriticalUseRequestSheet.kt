package com.vettid.app.features.grants

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Receiver-side sheet for "ask the peer to use a critical secret on
 * my behalf." Lets the user pick the operation, fill the payload,
 * and add free-text context — all of which travel to the owner's
 * approval prompt. The peer's vault never sends back the secret.
 *
 * Phase 6 MVP: sign + auth fully wired. decrypt and derive are
 * selectable but the vault returns "not yet implemented" so the user
 * sees a clear error when they pick those — better than hiding them
 * since the contract is locked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CriticalUseRequestSheet(
    secretLabel: String,
    onDismiss: () -> Unit,
    onSubmit: (operation: String, payloadBase64: String, context: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var operation by remember { mutableStateOf("sign") }
    var payload by remember { mutableStateOf("") }
    var context by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("Ask to use", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "$secretLabel — operation runs on the owner's device. The secret material never crosses the wire; only the result comes back to you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            Text("Operation", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Row {
                listOf("sign", "auth", "decrypt", "derive").forEach { op ->
                    FilterChip(
                        selected = operation == op,
                        onClick = { operation = op },
                        label = { Text(op) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = payload,
                onValueChange = { payload = it },
                label = { Text("Payload (UTF-8 text)") },
                supportingText = { Text("Base64-encoded for the owner's vault. ${operation.uppercase()} hashes / decrypts / derives over these bytes.") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 6,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = context,
                onValueChange = { context = it },
                label = { Text("Context (visible to owner)") },
                supportingText = { Text("Explain why you're asking. The owner sees this on the approval prompt.") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 4,
            )
            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val encoded = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        onSubmit(operation, encoded, context.trim())
                    },
                    enabled = payload.isNotBlank(),
                ) { Text("Send request") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
