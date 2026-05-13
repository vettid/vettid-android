package com.vettid.app.features.grants

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.security.SecurePassword

/**
 * Full-screen prompt the owner sees when a peer challenges their
 * identity. Approving signs the challenge nonce with the Ed25519
 * identity key inside the vault. The password gate is mandatory and
 * not cached — every approve re-prompts. The signing key is wiped
 * from vault memory immediately after the response is published.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityVerifyApprovalScreen(
    viewModel: IdentityVerifyApprovalViewModel = hiltViewModel(),
    onDone: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    var password by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        if (state is IdentityVerifyApprovalViewModel.State.Approved ||
            state is IdentityVerifyApprovalViewModel.State.Denied) {
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verify your identity to a connection") },
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
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Icon(
                Icons.Default.VerifiedUser,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(16.dp))

            Text(
                "A connection is asking you to prove this vault holds your identity key.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))

            if (viewModel.context.isNotBlank()) {
                IdentityVerifyDetailRow("Context", viewModel.context)
                Spacer(Modifier.height(12.dp))
            }

            Text(
                "Your identity key never leaves this device. Only the signed challenge is shared with the peer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Enter your password to approve") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = state !is IdentityVerifyApprovalViewModel.State.Authenticating,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Required every time. No session caching for identity-key use.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val errorMessage = (state as? IdentityVerifyApprovalViewModel.State.Error)?.message
            if (errorMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.weight(1f))

            if (state is IdentityVerifyApprovalViewModel.State.Authenticating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { viewModel.deny() },
                    enabled = state !is IdentityVerifyApprovalViewModel.State.Authenticating,
                    modifier = Modifier.weight(1f),
                ) { Text("Deny") }
                Button(
                    onClick = {
                        if (password.isNotBlank()) {
                            viewModel.approve(SecurePassword.fromString(password))
                            password = ""
                        }
                    },
                    enabled = password.isNotBlank() && state !is IdentityVerifyApprovalViewModel.State.Authenticating,
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
private fun IdentityVerifyDetailRow(label: String, value: String) {
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
