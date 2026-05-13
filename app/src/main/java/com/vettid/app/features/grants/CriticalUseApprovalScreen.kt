package com.vettid.app.features.grants

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.security.SecurePassword

/**
 * Full-screen prompt that the owner sees when a peer requests a
 * critical-secret operation (sign / decrypt / derive / auth). The
 * intentional friction is the password gate — every approve requires
 * fresh password entry, no TTL session. The screen makes the trust
 * implications explicit before any cryptography runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CriticalUseApprovalScreen(
    viewModel: CriticalUseApprovalViewModel = hiltViewModel(),
    onDone: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }
    val submit: () -> Unit = submit@{
        if (password.isBlank()) return@submit
        if (state is CriticalUseApprovalViewModel.State.Authenticating) return@submit
        viewModel.approve(SecurePassword.fromString(password))
        password = ""
    }

    LaunchedEffect(state) {
        if (state is CriticalUseApprovalViewModel.State.Approved ||
            state is CriticalUseApprovalViewModel.State.Denied) {
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Use of your critical secret") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(16.dp))

            Text(
                "A connection has requested an operation using one of your critical secrets.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))

            DetailRow("Secret", viewModel.itemLabel.ifEmpty { "(unnamed)" })
            DetailRow("Operation", viewModel.operation.uppercase())
            if (viewModel.context.isNotBlank()) {
                DetailRow("Context", viewModel.context)
            }
            Spacer(Modifier.height(16.dp))

            // Collapsible privacy details — same pattern as verify
            // approval so the screen stays light by default.
            Surface(
                onClick = { detailsExpanded = !detailsExpanded },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "What happens when I approve?",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (detailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (detailsExpanded) "Hide details" else "Show details",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (detailsExpanded) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Your password (encrypted) is sent to your vault enclave, which decrypts the secret, runs the operation, and wipes the key from memory. The secret material never exists outside the enclave — only the operation's result is shared with the peer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Your password") },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = state !is CriticalUseApprovalViewModel.State.Authenticating,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { submit() },
                ),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Required every time. No session caching for critical-secret operations.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val errorMessage = (state as? CriticalUseApprovalViewModel.State.Error)?.message
            if (errorMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(24.dp))

            if (state is CriticalUseApprovalViewModel.State.Authenticating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { viewModel.deny() },
                    enabled = state !is CriticalUseApprovalViewModel.State.Authenticating,
                    modifier = Modifier.weight(1f),
                ) { Text("Deny") }
                Button(
                    onClick = submit,
                    enabled = password.isNotBlank() && state !is CriticalUseApprovalViewModel.State.Authenticating,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Approve once") }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
