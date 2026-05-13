package com.vettid.app.features.grants

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.security.SecurePassword

/**
 * Full-screen approval prompt for an incoming identity verification
 * challenge. The peer's vault has asked us to prove possession of our
 * Ed25519 identity key by signing a nonce. Approving runs the signing
 * inside the vault under a fresh password entry — no TTL caching, the
 * key is wiped from vault memory immediately after the response is
 * published.
 *
 * Visual structure mirrors the critical-secret approval prompt so the
 * "this is a sensitive operation gated by your password" feel is
 * consistent across the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityVerifyApprovalScreen(
    viewModel: IdentityVerifyApprovalViewModel = hiltViewModel(),
    onDone: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state is IdentityVerifyApprovalViewModel.State.Approved ||
            state is IdentityVerifyApprovalViewModel.State.Denied) {
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verify identity") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    titleContentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Hero — circular shield in the brand error color to make
            // the privacy weight obvious at a glance.
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.height(16.dp))

            Text(
                "${viewModel.peerName} wants to verify your identity",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Approving sends your password (encrypted) to your vault enclave. The enclave decrypts your credential, signs the challenge with your Ed25519 identity key, then wipes the key from memory. The identity key never exists outside the enclave; only the signature is shared with ${viewModel.peerName}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))

            // Request details card — peer + connection + optional
            // free-form context line carried in the challenge.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    DetailRow("From", viewModel.peerName)
                    if (viewModel.context.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        DetailRow("Context", viewModel.context)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Password card — primary action area. Show/hide toggle on
            // the trailing slot follows the platform pattern users
            // expect from any masked field.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Lock,
                            null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Password approval",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Your password") },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state !is IdentityVerifyApprovalViewModel.State.Authenticating,
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showPassword) "Hide password" else "Show password",
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Required every time. Identity-key use is never cached.",
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
                }
            }

            if (state is IdentityVerifyApprovalViewModel.State.Authenticating) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(24.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
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
                ) { Text("Approve") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

