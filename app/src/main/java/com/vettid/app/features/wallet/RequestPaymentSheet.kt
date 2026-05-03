package com.vettid.app.features.wallet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Bottom sheet for creating a payment request to send to a connection.
 *
 * The user specifies an amount, optional memo, and selects which connection
 * to send the request to. The request is delivered via the vault's messaging system.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestPaymentSheet(
    connections: List<ConnectionOption> = emptyList(),
    onDismiss: () -> Unit,
    onSendRequest: (amountSats: Long, memo: String?, connectionId: String) -> Unit,
    /**
     * When invoked from inside a conversation, the connection is
     * already known — pass it through so the sheet skips the
     * connection picker and the user can request straight away.
     * Empty string keeps the picker visible (legacy callers).
     */
    preselectedConnectionId: String = "",
    preselectedConnectionLabel: String = "",
) {
    var amountBtc by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var selectedConnectionId by remember { mutableStateOf(preselectedConnectionId) }
    var isSending by remember { mutableStateOf(false) }
    var connectionExpanded by remember { mutableStateOf(false) }

    val amountSats = btcToSats(amountBtc)
    val canSend = amountSats > 0 && selectedConnectionId.isNotBlank() && !isSending

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Title
            Text(
                text = "Request Payment",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "Send a payment request to one of your connections.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Amount
            OutlinedTextField(
                value = amountBtc,
                onValueChange = { value ->
                    if (value.isEmpty() || value.matches(Regex("^\\d*\\.?\\d{0,8}$"))) {
                        amountBtc = value
                    }
                },
                label = { Text("Amount (BTC)") },
                placeholder = { Text("0.0") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                leadingIcon = {
                    Icon(
                        Icons.Default.CurrencyBitcoin,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
                supportingText = {
                    if (amountSats > 0) {
                        Text("$amountSats sats")
                    }
                }
            )

            // Memo (optional)
            OutlinedTextField(
                value = memo,
                onValueChange = { memo = it },
                label = { Text("Memo (optional)") },
                placeholder = { Text("What is this for?") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
                leadingIcon = {
                    Icon(
                        Icons.Default.Notes,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )

            // Connection selector — hidden when invoked from a
            // conversation (the connection is already implicit).
            if (preselectedConnectionId.isEmpty()) {
            ExposedDropdownMenuBox(
                expanded = connectionExpanded,
                onExpandedChange = { connectionExpanded = it }
            ) {
                val selected = connections.find { it.id == selectedConnectionId }
                OutlinedTextField(
                    value = selected?.displayName ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Send To") },
                    placeholder = { Text("Select connection") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(connectionExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    leadingIcon = {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )

                ExposedDropdownMenu(
                    expanded = connectionExpanded,
                    onDismissRequest = { connectionExpanded = false }
                ) {
                    if (connections.isEmpty()) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "No connections available",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = { connectionExpanded = false }
                        )
                    } else {
                        connections.forEach { connection ->
                            DropdownMenuItem(
                                text = { Text(connection.displayName) },
                                onClick = {
                                    selectedConnectionId = connection.id
                                    connectionExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            } else if (preselectedConnectionLabel.isNotEmpty()) {
                Text(
                    text = "Sending to ${preselectedConnectionLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Send request button
            Button(
                onClick = {
                    isSending = true
                    onSendRequest(
                        amountSats,
                        memo.ifBlank { null },
                        selectedConnectionId
                    )
                },
                enabled = canSend,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sending...")
                } else {
                    Icon(
                        Icons.Default.RequestPage,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send Request")
                }
            }
        }
    }
}

/**
 * Simple model for connections shown in the payment request selector.
 */
data class ConnectionOption(
    val id: String,
    val displayName: String
)

/**
 * Convert a BTC string to satoshis.
 */
private fun btcToSats(btcString: String): Long {
    if (btcString.isBlank()) return 0
    return try {
        (btcString.toDouble() * 100_000_000).toLong()
    } catch (e: NumberFormatException) {
        0
    }
}
