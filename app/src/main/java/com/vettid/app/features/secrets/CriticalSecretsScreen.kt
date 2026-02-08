package com.vettid.app.features.secrets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Full-screen critical secrets viewer.
 *
 * Flow:
 *   PasswordPrompt -> Authenticating -> MetadataList (with search + FAB)
 *     -> tap secret -> SecondPasswordPrompt -> Retrieving -> Revealed (30s countdown)
 *     -> timer expires -> back to MetadataList
 *
 * No local caching - all state is in ViewModel memory, cleared on navigate away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CriticalSecretsScreen(
    viewModel: CriticalSecretsViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onNavigateToAddSecret: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    var showSearch by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (showSearch && state is CriticalSecretsState.MetadataList) {
                val metadataState = state as CriticalSecretsState.MetadataList
                SearchBar(
                    query = metadataState.searchQuery,
                    onQueryChange = { viewModel.onEvent(CriticalSecretsScreenEvent.SearchQueryChanged(it)) },
                    onClose = {
                        showSearch = false
                        viewModel.onEvent(CriticalSecretsScreenEvent.SearchQueryChanged(""))
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("Critical Secrets") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (state is CriticalSecretsState.MetadataList) {
                            IconButton(onClick = { showSearch = true }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (state is CriticalSecretsState.MetadataList) {
                FloatingActionButton(
                    onClick = onNavigateToAddSecret,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add critical secret")
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val currentState = state) {
                is CriticalSecretsState.PasswordPrompt -> {
                    PasswordPromptContent(
                        title = "Critical Secrets",
                        subtitle = "Enter your credential password to view secrets stored in your credential.",
                        onSubmit = { viewModel.onEvent(CriticalSecretsScreenEvent.SubmitPassword(it)) },
                        onCancel = onBack
                    )
                }

                is CriticalSecretsState.Authenticating -> {
                    LoadingContent(message = "Authenticating...")
                }

                is CriticalSecretsState.MetadataList -> {
                    MetadataListContent(
                        secrets = currentState.secrets,
                        cryptoKeys = currentState.cryptoKeys,
                        credentialInfo = currentState.credentialInfo,
                        searchQuery = currentState.searchQuery,
                        onSecretTap = { id, name ->
                            viewModel.onEvent(CriticalSecretsScreenEvent.RevealSecret(id, name))
                        }
                    )
                }

                is CriticalSecretsState.SecondPasswordPrompt -> {
                    PasswordPromptContent(
                        title = "Reveal: ${currentState.secretName}",
                        subtitle = "Re-enter your password to view the secret value.",
                        onSubmit = { viewModel.onEvent(CriticalSecretsScreenEvent.SubmitRevealPassword(it)) },
                        onCancel = { viewModel.onEvent(CriticalSecretsScreenEvent.BackToList) }
                    )
                }

                is CriticalSecretsState.Retrieving -> {
                    LoadingContent(message = "Retrieving ${currentState.secretName}...")
                }

                is CriticalSecretsState.Revealed -> {
                    RevealedContent(
                        secretName = currentState.secretName,
                        value = currentState.value,
                        remainingSeconds = currentState.remainingSeconds,
                        onBack = { viewModel.onEvent(CriticalSecretsScreenEvent.BackToList) }
                    )
                }

                is CriticalSecretsState.Error -> {
                    ErrorContent(
                        message = currentState.message,
                        onRetry = { viewModel.onEvent(CriticalSecretsScreenEvent.BackToList) },
                        onClose = onBack
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    TopAppBar(
        title = {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search secrets...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close search")
            }
        }
    )
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
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
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
        Spacer(modifier = Modifier.height(32.dp))

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
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (password.isNotEmpty()) onSubmit(password) }
            ),
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
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MetadataListContent(
    secrets: List<CriticalSecretItem>,
    cryptoKeys: List<CryptoKeyItem>,
    credentialInfo: CredentialInfoItem?,
    searchQuery: String,
    onSecretTap: (String, String) -> Unit
) {
    // Filter items based on search query
    val filteredSecrets = if (searchQuery.isBlank()) secrets else {
        secrets.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.category.contains(searchQuery, ignoreCase = true) ||
            it.description?.contains(searchQuery, ignoreCase = true) == true
        }
    }
    val filteredKeys = if (searchQuery.isBlank()) cryptoKeys else {
        cryptoKeys.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
            it.type.contains(searchQuery, ignoreCase = true)
        }
    }
    val showCredentialInfo = searchQuery.isBlank()

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Credential info (not searchable)
        if (showCredentialInfo) {
            credentialInfo?.let { info ->
                item {
                    SectionHeader("Credential")
                }
                item {
                    CredentialInfoCard(info)
                }
            }
        }

        // Crypto keys
        if (filteredKeys.isNotEmpty()) {
            item {
                SectionHeader("Crypto Keys")
            }
            items(filteredKeys, key = { it.id }) { key ->
                CryptoKeyCard(key)
            }
        }

        // Secrets
        if (filteredSecrets.isNotEmpty()) {
            item {
                SectionHeader("Secrets")
            }
            items(filteredSecrets, key = { it.id }) { secret ->
                SecretMetadataCard(
                    secret = secret,
                    onClick = { onSecretTap(secret.id, secret.name) }
                )
            }
        }

        // Empty state
        if (filteredSecrets.isEmpty() && filteredKeys.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "No results for \"$searchQuery\""
                               else "No critical secrets stored in your credential.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Bottom spacing for FAB
        item {
            Spacer(modifier = Modifier.height(72.dp))
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
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Countdown indicator
        val progress = remainingSeconds / 30f
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(80.dp),
            color = if (remainingSeconds <= 10) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeWidth = 5.dp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "${remainingSeconds}s",
            style = MaterialTheme.typography.titleMedium,
            color = if (remainingSeconds <= 10) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = secretName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(24.dp))

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

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "This value will auto-hide when the timer expires.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        TextButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
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
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Error",
                style = MaterialTheme.typography.titleLarge,
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
