package com.vettid.app.features.secrets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Bottom sheet for critical secrets (embedded in the Protean Credential).
 *
 * Flow:
 *   PasswordPrompt -> Authenticating -> MetadataList
 *     -> tap secret -> SecondPasswordPrompt -> Retrieving -> Revealed (30s countdown)
 *     -> timer expires -> back to MetadataList
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CriticalSecretsSheet(
    state: CriticalSecretsSheetState,
    onEvent: (CriticalSecretsEvent) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = { onEvent(CriticalSecretsEvent.Close) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            when (state) {
                is CriticalSecretsSheetState.PasswordPrompt -> {
                    PasswordPromptContent(
                        title = "Critical Secrets",
                        subtitle = "Enter your credential password to view secrets stored in your credential.",
                        onSubmit = { onEvent(CriticalSecretsEvent.SubmitPassword(it)) },
                        onCancel = { onEvent(CriticalSecretsEvent.Close) }
                    )
                }

                is CriticalSecretsSheetState.Authenticating -> {
                    LoadingContent(message = "Authenticating...")
                }

                is CriticalSecretsSheetState.MetadataList -> {
                    MetadataListContent(
                        secrets = state.secrets,
                        cryptoKeys = state.cryptoKeys,
                        credentialInfo = state.credentialInfo,
                        onSecretTap = { id, name ->
                            onEvent(CriticalSecretsEvent.RevealSecret(id, name))
                        },
                        onClose = { onEvent(CriticalSecretsEvent.Close) }
                    )
                }

                is CriticalSecretsSheetState.SecondPasswordPrompt -> {
                    PasswordPromptContent(
                        title = "Reveal: ${state.secretName}",
                        subtitle = "Re-enter your password to view the secret value.",
                        onSubmit = { onEvent(CriticalSecretsEvent.SubmitRevealPassword(it)) },
                        onCancel = { onEvent(CriticalSecretsEvent.BackToList) }
                    )
                }

                is CriticalSecretsSheetState.Retrieving -> {
                    LoadingContent(message = "Retrieving ${state.secretName}...")
                }

                is CriticalSecretsSheetState.Revealed -> {
                    RevealedContent(
                        secretName = state.secretName,
                        value = state.value,
                        remainingSeconds = state.remainingSeconds,
                        onBack = { onEvent(CriticalSecretsEvent.BackToList) }
                    )
                }

                is CriticalSecretsSheetState.Error -> {
                    ErrorContent(
                        message = state.message,
                        onRetry = { onEvent(CriticalSecretsEvent.BackToList) },
                        onClose = { onEvent(CriticalSecretsEvent.Close) }
                    )
                }

                is CriticalSecretsSheetState.Hidden -> {
                    // Should not be shown
                }
            }
        }
    }
}

@Composable
private fun PasswordPromptContent(
    title: String,
    subtitle: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = if (passwordVisible) VisualTransformation.None
                                   else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff
                                      else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password"
                    )
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onSubmit(password) },
                enabled = password.isNotEmpty()
            ) {
                Text("Authenticate")
            }
        }
    }
}

@Composable
private fun LoadingContent(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MetadataListContent(
    secrets: List<CriticalSecretItem>,
    cryptoKeys: List<CryptoKeyItem>,
    credentialInfo: CredentialInfoItem?,
    onSecretTap: (String, String) -> Unit,
    onClose: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Critical Secrets",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.heightIn(max = 500.dp)
        ) {
            // Credential info
            credentialInfo?.let { info ->
                item {
                    SectionHeader("Credential")
                }
                item {
                    CredentialInfoCard(info)
                }
            }

            // Crypto keys
            if (cryptoKeys.isNotEmpty()) {
                item {
                    SectionHeader("Crypto Keys")
                }
                items(cryptoKeys, key = { it.id }) { key ->
                    CryptoKeyCard(key)
                }
            }

            // Secrets
            if (secrets.isNotEmpty()) {
                item {
                    SectionHeader("Secrets")
                }
                items(secrets, key = { it.id }) { secret ->
                    SecretMetadataCard(
                        secret = secret,
                        onClick = { onSecretTap(secret.id, secret.name) }
                    )
                }
            }

            // Empty state
            if (secrets.isEmpty() && cryptoKeys.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No critical secrets stored in your credential.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun CredentialInfoCard(info: CredentialInfoItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            InfoRow("Fingerprint", info.identityFingerprint)
            info.vaultId?.let { InfoRow("Vault", it) }
            InfoRow("Version", info.version.toString())
            InfoRow("Created", info.createdAt)
            info.boundAt?.let { InfoRow("Bound", it) }
        }
    }
}

@Composable
private fun CryptoKeyCard(key: CryptoKeyItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.VpnKey,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = key.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            InfoRow("Type", key.type)
            key.derivationPath?.let { InfoRow("Path", it) }
            key.publicKey?.let {
                InfoRow("Public Key", if (it.length > 20) "${it.take(12)}...${it.takeLast(8)}" else it)
            }
        }
    }
}

@Composable
private fun SecretMetadataCard(
    secret: CriticalSecretItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                getCriticalSecretCategoryIcon(secret.category),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = secret.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                secret.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = formatCategory(secret.category),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Reveal",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RevealedContent(
    secretName: String,
    value: String,
    remainingSeconds: Int,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Countdown indicator
        val progress = remainingSeconds / 30f
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(64.dp),
            color = if (remainingSeconds <= 10) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeWidth = 4.dp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${remainingSeconds}s",
            style = MaterialTheme.typography.labelMedium,
            color = if (remainingSeconds <= 10) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = secretName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Value display
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
            tonalElevation = 2.dp
        ) {
            SelectionContainer {
                Text(
                    text = value,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "This value will auto-hide when the timer expires.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        TextButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Back to list")
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Error",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Row {
            TextButton(onClick = onClose) {
                Text("Close")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onRetry) {
                Text("Try again")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun getCriticalSecretCategoryIcon(category: String) = when (category) {
    "SEED_PHRASE" -> Icons.Default.FormatListNumbered
    "PRIVATE_KEY" -> Icons.Default.Key
    "SIGNING_KEY" -> Icons.Default.Draw
    "MASTER_PASSWORD" -> Icons.Default.Password
    else -> Icons.Default.Lock
}

private fun formatCategory(category: String) = when (category) {
    "SEED_PHRASE" -> "Seed Phrase"
    "PRIVATE_KEY" -> "Private Key"
    "SIGNING_KEY" -> "Signing Key"
    "MASTER_PASSWORD" -> "Master Password"
    "OTHER" -> "Other"
    else -> category
}
