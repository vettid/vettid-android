package com.vettid.app.features.wallet

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Bottom sheet for creating a new Bitcoin wallet.
 *
 * The user picks a label + network and taps Create. Tapping Create
 * opens a follow-on password dialog — the seed will be written into
 * the credential and every wallet sign requires the password gate,
 * so we collect it once at confirmation. The password never lives
 * inline with the form fields.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateWalletSheet(
    onDismiss: () -> Unit,
    onCreateWallet: (label: String, network: String, password: com.vettid.app.core.security.SecurePassword) -> Unit
) {
    var label by remember { mutableStateOf("") }
    var selectedNetwork by remember { mutableStateOf("mainnet") }
    var pendingPasswordPrompt by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Title
            Text(
                text = "Create Wallet",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Your seed will be sealed inside your credential. We'll ask for your password when you tap Create — every wallet sign asks for it again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Label field
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Wallet Label") },
                placeholder = { Text("e.g., Savings, Daily") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(
                        Icons.Default.Label,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Network selector
            Text(
                text = "Network",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                NetworkOption(
                    label = "Mainnet",
                    description = "Real Bitcoin",
                    selected = selectedNetwork == "mainnet",
                    onClick = { selectedNetwork = "mainnet" },
                    modifier = Modifier.weight(1f)
                )

                NetworkOption(
                    label = "Testnet",
                    description = "Test coins",
                    selected = selectedNetwork == "testnet",
                    onClick = { selectedNetwork = "testnet" },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Create button — opens the password confirmation dialog.
            Button(
                onClick = { pendingPasswordPrompt = true },
                enabled = label.isNotBlank() && !isCreating,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Creating...")
                } else {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Create Wallet")
                }
            }
        }
    }

    if (pendingPasswordPrompt) {
        WalletPasswordDialog(
            onConfirm = { securePw ->
                pendingPasswordPrompt = false
                isCreating = true
                onCreateWallet(label.trim(), selectedNetwork, securePw)
            },
            onCancel = { pendingPasswordPrompt = false },
        )
    }
}

@Composable
private fun WalletPasswordDialog(
    onConfirm: (com.vettid.app.core.security.SecurePassword) -> Unit,
    onCancel: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { password = "" } }
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = { Text("Confirm with password") },
        text = {
            Column {
                Text(
                    text = "Your wallet's seed will be sealed inside your credential. Enter your password to authorize the write.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val pw = com.vettid.app.core.security.SecurePassword.fromString(password)
                    password = ""
                    onConfirm(pw)
                },
                enabled = password.isNotEmpty(),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
