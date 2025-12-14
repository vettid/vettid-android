package com.vettid.app.features.secrets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

/**
 * Secrets screen with password-only authentication.
 * IMPORTANT: Secrets should NEVER use biometric authentication.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretsScreenFull(
    viewModel: SecretsViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val listState by viewModel.listState.collectAsState()
    val valueState by viewModel.valueState.collectAsState()
    val selectedSecretId by viewModel.selectedSecretId.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showPasswordDialog by remember { mutableStateOf(false) }
    var showSecretDialog by remember { mutableStateOf(false) }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SecretsEffect.ShowPasswordPrompt -> {
                    showPasswordDialog = true
                }
                is SecretsEffect.ShowSuccess -> {
                    showSecretDialog = true
                    showPasswordDialog = false
                    snackbarHostState.showSnackbar(effect.message)
                }
                is SecretsEffect.ShowError -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is SecretsEffect.SecretCopied -> {
                    snackbarHostState.showSnackbar("Copied to clipboard (auto-clears in 30s)")
                }
                else -> { }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Secrets") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.onEvent(SecretsEvent.AddSecret) }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Secret")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.onEvent(SecretsEvent.AddSecret) }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Secret")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Security notice
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Secrets require password entry. Biometrics are not used for additional security.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            // Search bar
            if (listState is SecretsListState.Loaded) {
                val loaded = listState as SecretsListState.Loaded
                OutlinedTextField(
                    value = loaded.searchQuery,
                    onValueChange = { viewModel.onEvent(SecretsEvent.SearchQueryChanged(it)) },
                    placeholder = { Text("Search secrets...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (loaded.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onEvent(SecretsEvent.SearchQueryChanged("")) }) {
                                Icon(Icons.Default.Clear, "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Content
            when (val state = listState) {
                is SecretsListState.Loading -> LoadingContent()
                is SecretsListState.Empty -> EmptyContent(
                    onAddSecret = { viewModel.onEvent(SecretsEvent.AddSecret) }
                )
                is SecretsListState.Loaded -> {
                    val filteredSecrets = if (state.searchQuery.isBlank()) {
                        state.secrets
                    } else {
                        state.secrets.filter {
                            it.name.contains(state.searchQuery, ignoreCase = true) ||
                            it.notes?.contains(state.searchQuery, ignoreCase = true) == true
                        }
                    }
                    SecretsList(
                        secrets = filteredSecrets,
                        onSecretClick = { viewModel.onEvent(SecretsEvent.SecretClicked(it.id)) }
                    )
                }
                is SecretsListState.Error -> ErrorContent(
                    message = state.message,
                    onRetry = { viewModel.onEvent(SecretsEvent.Refresh) }
                )
            }
        }

        // Password dialog
        if (showPasswordDialog) {
            PasswordDialog(
                onDismiss = {
                    showPasswordDialog = false
                    viewModel.onEvent(SecretsEvent.HideSecret)
                },
                onSubmit = { password ->
                    selectedSecretId?.let { id ->
                        viewModel.onEvent(SecretsEvent.RevealSecret(id, password))
                    }
                },
                isVerifying = valueState is SecretValueState.Verifying,
                errorMessage = (valueState as? SecretValueState.Error)?.message
            )
        }

        // Revealed secret dialog
        if (showSecretDialog && valueState is SecretValueState.Revealed) {
            val revealedState = valueState as SecretValueState.Revealed
            val selectedSecret = (listState as? SecretsListState.Loaded)?.secrets
                ?.find { it.id == selectedSecretId }

            RevealedSecretDialog(
                secretName = selectedSecret?.name ?: "Secret",
                secretValue = revealedState.value,
                autoHideSeconds = revealedState.autoHideSeconds,
                onDismiss = {
                    showSecretDialog = false
                    viewModel.onEvent(SecretsEvent.HideSecret)
                },
                onCopy = {
                    selectedSecretId?.let { viewModel.onEvent(SecretsEvent.CopySecret(it)) }
                }
            )
        }
    }
}

@Composable
private fun SecretsList(
    secrets: List<Secret>,
    onSecretClick: (Secret) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(secrets, key = { it.id }) { secret ->
            SecretListItem(
                secret = secret,
                onClick = { onSecretClick(secret) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecretListItem(
    secret: Secret,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        headlineContent = {
            Text(
                text = secret.name,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = secret.category.displayName + (secret.notes?.let { " • $it" } ?: ""),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = getCategoryIcon(secret.category),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Locked",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@Composable
private fun PasswordDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    isVerifying: Boolean,
    errorMessage: String?
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text("Enter Password")
        },
        text = {
            Column {
                Text(
                    text = "Password is required to view this secret. Biometrics cannot be used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (showPassword) "Hide" else "Show"
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { onSubmit(password) }
                    ),
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let {
                        { Text(it, color = MaterialTheme.colorScheme.error) }
                    },
                    enabled = !isVerifying,
                    modifier = Modifier.fillMaxWidth()
                )

                if (isVerifying) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(password) },
                enabled = password.isNotEmpty() && !isVerifying
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Unlock")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun RevealedSecretDialog(
    secretName: String,
    secretValue: String,
    autoHideSeconds: Int,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.LockOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(secretName)
        },
        text = {
            Column {
                // Auto-hide warning
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Auto-hides in ${autoHideSeconds}s",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Secret value
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    SelectionContainer {
                        Text(
                            text = secretValue,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyContent(onAddSecret: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "No Secrets Yet",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Store API keys, passwords, and other sensitive data securely.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onAddSecret) {
                Icon(Icons.Default.Add, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Secret")
            }
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Error",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

private fun getCategoryIcon(category: SecretCategory): ImageVector {
    return when (category) {
        SecretCategory.PASSWORD -> Icons.Default.Password
        SecretCategory.API_KEY -> Icons.Default.Key
        SecretCategory.CERTIFICATE -> Icons.Default.VerifiedUser
        SecretCategory.CRYPTO_KEY -> Icons.Default.EnhancedEncryption
        SecretCategory.NOTE -> Icons.Default.Notes
        SecretCategory.OTHER -> Icons.Default.Lock
    }
}
