package com.vettid.app.features.secrets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.vettid.app.core.storage.CriticalSecretCategory
import com.vettid.app.core.storage.CriticalSecretMetadata
import com.vettid.app.core.storage.CriticalSecretViewState
import com.vettid.app.core.storage.MinorSecret
import com.vettid.app.core.storage.SecretCategory
import kotlinx.coroutines.flow.collectLatest

/**
 * Two-tier secrets screen with tabbed interface.
 *
 * Tab 1: Minor Secrets (Passwords & Keys) - stored in enclave datastore
 * Tab 2: Critical Secrets - stored in Protean Credential, extra security
 *
 * Security: All secrets require password authentication (NO biometrics).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwoTierSecretsScreen(
    onBack: () -> Unit,
    onNavigateToAddSecret: (isCritical: Boolean) -> Unit,
    viewModel: TwoTierSecretsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Dialogs
    var showMinorPasswordDialog by remember { mutableStateOf(false) }
    var showMinorRevealedDialog by remember { mutableStateOf(false) }
    var showCriticalPasswordDialog by remember { mutableStateOf(false) }
    var showCriticalAcknowledgment by remember { mutableStateOf(false) }
    var showCriticalRevealedDialog by remember { mutableStateOf(false) }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is TwoTierSecretsEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
                is TwoTierSecretsEffect.ShowError -> snackbarHostState.showSnackbar(effect.message)
                is TwoTierSecretsEffect.NavigateToAddSecret -> onNavigateToAddSecret(effect.isCritical)
                is TwoTierSecretsEffect.NavigateBack -> { /* Handled by navigation */ }
            }
        }
    }

    // Update dialog states based on view model state
    LaunchedEffect(state.minorSecretValueState) {
        showMinorPasswordDialog = state.minorSecretValueState is MinorSecretValueState.PasswordRequired ||
                                  state.minorSecretValueState is MinorSecretValueState.Verifying ||
                                  state.minorSecretValueState is MinorSecretValueState.Error
        showMinorRevealedDialog = state.minorSecretValueState is MinorSecretValueState.Revealed
    }

    LaunchedEffect(state.criticalSecretViewState) {
        showCriticalPasswordDialog = state.criticalSecretViewState is CriticalSecretViewState.PasswordPrompt ||
                                     (state.criticalSecretViewState is CriticalSecretViewState.Retrieving &&
                                      state.criticalSecretViewState !is CriticalSecretViewState.AcknowledgementRequired)
        showCriticalAcknowledgment = state.criticalSecretViewState is CriticalSecretViewState.AcknowledgementRequired
        showCriticalRevealedDialog = state.criticalSecretViewState is CriticalSecretViewState.Revealed
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
                    IconButton(
                        onClick = {
                            viewModel.onEvent(TwoTierSecretsEvent.NavigateToAdd(
                                isCritical = state.selectedTab == SecretsTab.CRITICAL
                            ))
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Secret")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.onEvent(TwoTierSecretsEvent.NavigateToAdd(
                        isCritical = state.selectedTab == SecretsTab.CRITICAL
                    ))
                }
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Password required to view secrets. Biometrics not used for security.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            // Tabs
            TabRow(
                selectedTabIndex = state.selectedTab.ordinal,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = state.selectedTab == SecretsTab.MINOR,
                    onClick = { viewModel.onEvent(TwoTierSecretsEvent.SelectTab(SecretsTab.MINOR)) },
                    text = { Text("Passwords & Keys") },
                    icon = { Icon(Icons.Default.Key, contentDescription = null) }
                )
                Tab(
                    selected = state.selectedTab == SecretsTab.CRITICAL,
                    onClick = { viewModel.onEvent(TwoTierSecretsEvent.SelectTab(SecretsTab.CRITICAL)) },
                    text = { Text("Critical Secrets") },
                    icon = { Icon(Icons.Default.Shield, contentDescription = null) }
                )
            }

            // Search bar
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { viewModel.onEvent(TwoTierSecretsEvent.SearchQueryChanged(it)) },
                placeholder = { Text("Search secrets...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onEvent(TwoTierSecretsEvent.SearchQueryChanged("")) }) {
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

            // Content based on selected tab
            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                when (state.selectedTab) {
                    SecretsTab.MINOR -> MinorSecretsContent(
                        secrets = state.filteredMinorSecrets,
                        onSecretClick = { viewModel.onEvent(TwoTierSecretsEvent.MinorSecretClicked(it.id)) },
                        onAddClick = { viewModel.onEvent(TwoTierSecretsEvent.NavigateToAdd(false)) }
                    )
                    SecretsTab.CRITICAL -> CriticalSecretsContent(
                        secrets = state.filteredCriticalSecrets,
                        onSecretClick = { viewModel.onEvent(TwoTierSecretsEvent.CriticalSecretClicked(it.id)) },
                        onAddClick = { viewModel.onEvent(TwoTierSecretsEvent.NavigateToAdd(true)) }
                    )
                }
            }
        }

        // Minor secret password dialog
        if (showMinorPasswordDialog) {
            PasswordDialog(
                title = "Enter Password",
                message = "Password is required to view this secret.",
                isVerifying = state.minorSecretValueState is MinorSecretValueState.Verifying,
                errorMessage = (state.minorSecretValueState as? MinorSecretValueState.Error)?.message,
                onDismiss = { viewModel.onEvent(TwoTierSecretsEvent.HideSecret) },
                onSubmit = { password ->
                    state.selectedMinorSecretId?.let { id ->
                        viewModel.onEvent(TwoTierSecretsEvent.RevealMinorSecret(id, password))
                    }
                }
            )
        }

        // Minor secret revealed dialog
        if (showMinorRevealedDialog) {
            val revealedState = state.minorSecretValueState as? MinorSecretValueState.Revealed
            val selectedSecret = state.minorSecrets.find { it.id == state.selectedMinorSecretId }

            revealedState?.let { revealed ->
                RevealedSecretDialog(
                    secretName = selectedSecret?.name ?: "Secret",
                    secretValue = revealed.value,
                    autoHideSeconds = revealed.autoHideSeconds,
                    onDismiss = { viewModel.onEvent(TwoTierSecretsEvent.HideSecret) },
                    onCopy = { viewModel.onEvent(TwoTierSecretsEvent.CopySecret(revealed.value)) }
                )
            }
        }

        // Critical secret password dialog
        if (showCriticalPasswordDialog) {
            PasswordDialog(
                title = "Critical Secret Access",
                message = "This is a critical secret stored in your Protean Credential. Password required.",
                isVerifying = state.criticalSecretViewState is CriticalSecretViewState.Retrieving,
                errorMessage = (state.criticalSecretViewState as? CriticalSecretViewState.Error)?.message,
                onDismiss = { viewModel.onEvent(TwoTierSecretsEvent.HideSecret) },
                onSubmit = { password ->
                    viewModel.onEvent(TwoTierSecretsEvent.SubmitPasswordForCritical(password))
                }
            )
        }

        // Critical secret acknowledgment dialog
        if (showCriticalAcknowledgment) {
            val ackState = state.criticalSecretViewState as? CriticalSecretViewState.AcknowledgementRequired
            ackState?.let {
                CriticalSecretAcknowledgmentDialog(
                    secretName = it.secretName,
                    onDismiss = { viewModel.onEvent(TwoTierSecretsEvent.HideSecret) },
                    onAcknowledge = { viewModel.onEvent(TwoTierSecretsEvent.AcknowledgeCriticalAccess) }
                )
            }
        }

        // Critical secret revealed dialog
        if (showCriticalRevealedDialog) {
            val revealedState = state.criticalSecretViewState as? CriticalSecretViewState.Revealed
            val selectedSecret = state.criticalSecrets.find { it.id == state.selectedCriticalSecretId }

            revealedState?.let { revealed ->
                RevealedSecretDialog(
                    secretName = selectedSecret?.name ?: "Critical Secret",
                    secretValue = revealed.value,
                    autoHideSeconds = revealed.expiresInSeconds,
                    isCritical = true,
                    onDismiss = { viewModel.onEvent(TwoTierSecretsEvent.HideSecret) },
                    onCopy = { viewModel.onEvent(TwoTierSecretsEvent.CopySecret(revealed.value)) }
                )
            }
        }
    }
}

@Composable
private fun MinorSecretsContent(
    secrets: List<MinorSecret>,
    onSecretClick: (MinorSecret) -> Unit,
    onAddClick: () -> Unit
) {
    if (secrets.isEmpty()) {
        EmptySecretsContent(
            title = "No Passwords or Keys",
            message = "Store your passwords, API keys, and other credentials securely.",
            onAddClick = onAddClick
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(secrets, key = { it.id }) { secret ->
                MinorSecretListItem(
                    secret = secret,
                    onClick = { onSecretClick(secret) }
                )
            }
        }
    }
}

@Composable
private fun CriticalSecretsContent(
    secrets: List<CriticalSecretMetadata>,
    onSecretClick: (CriticalSecretMetadata) -> Unit,
    onAddClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Warning banner for critical secrets
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Critical secrets are stored in your Protean Credential",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "Values auto-hide after 30 seconds for security",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        if (secrets.isEmpty()) {
            EmptySecretsContent(
                title = "No Critical Secrets",
                message = "Store seed phrases, private keys, and other highly sensitive data.",
                onAddClick = onAddClick
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(secrets, key = { it.id }) { secret ->
                    CriticalSecretListItem(
                        secret = secret,
                        onClick = { onSecretClick(secret) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MinorSecretListItem(
    secret: MinorSecret,
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
                        imageVector = getMinorCategoryIcon(secret.category),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CriticalSecretListItem(
    secret: CriticalSecretMetadata,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        headlineContent = {
            Text(
                text = secret.name,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = secret.category.displayName,
                    color = MaterialTheme.colorScheme.error
                )
                secret.description?.let {
                    Text(
                        text = it,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        leadingContent = {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = getCriticalCategoryIcon(secret.category),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = "Critical",
                tint = MaterialTheme.colorScheme.error
            )
        }
    )
}

@Composable
private fun EmptySecretsContent(
    title: String,
    message: String,
    onAddClick: () -> Unit
) {
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
                text = title,
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onAddClick) {
                Icon(Icons.Default.Add, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Secret")
            }
        }
    }
}

@Composable
private fun PasswordDialog(
    title: String,
    message: String,
    isVerifying: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSubmit(password) }),
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
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
            Button(onClick = { onSubmit(password) }, enabled = password.isNotEmpty() && !isVerifying) {
                if (isVerifying) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Unlock")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CriticalSecretAcknowledgmentDialog(
    secretName: String,
    onDismiss: () -> Unit,
    onAcknowledge: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
        },
        title = { Text("Security Warning") },
        text = {
            Column {
                Text(
                    text = "You are about to view: $secretName",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Please understand:")
                Spacer(modifier = Modifier.height(8.dp))
                BulletPoint("This secret will be displayed on screen")
                BulletPoint("The value will auto-hide in 30 seconds")
                BulletPoint("Never share this with anyone")
                BulletPoint("Ensure no one is watching your screen")
            }
        },
        confirmButton = {
            Button(
                onClick = onAcknowledge,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("I Understand, Show Secret")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun BulletPoint(text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("• ", color = MaterialTheme.colorScheme.error)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RevealedSecretDialog(
    secretName: String,
    secretValue: String,
    autoHideSeconds: Int,
    isCritical: Boolean = false,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.LockOpen,
                contentDescription = null,
                tint = if (isCritical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(secretName) },
        text = {
            Column {
                // Auto-hide warning
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Timer,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Auto-hides in ${autoHideSeconds}s",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
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
                            fontFamily = FontFamily.Monospace
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
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

private fun getMinorCategoryIcon(category: SecretCategory): ImageVector {
    return when (category) {
        SecretCategory.IDENTITY -> Icons.Default.Fingerprint
        SecretCategory.CRYPTOCURRENCY -> Icons.Default.CurrencyBitcoin
        SecretCategory.BANK_ACCOUNT -> Icons.Default.AccountBalance
        SecretCategory.CREDIT_CARD -> Icons.Default.CreditCard
        SecretCategory.INSURANCE -> Icons.Default.HealthAndSafety
        SecretCategory.DRIVERS_LICENSE -> Icons.Default.Badge
        SecretCategory.PASSPORT -> Icons.Default.Flight
        SecretCategory.SSN -> Icons.Default.Security
        SecretCategory.API_KEY -> Icons.Default.Key
        SecretCategory.PASSWORD -> Icons.Default.Password
        SecretCategory.WIFI -> Icons.Default.Wifi
        SecretCategory.CERTIFICATE -> Icons.Default.VerifiedUser
        SecretCategory.NOTE -> Icons.Default.Notes
        SecretCategory.OTHER -> Icons.Default.Lock
    }
}

private fun getCriticalCategoryIcon(category: CriticalSecretCategory): ImageVector {
    return when (category) {
        CriticalSecretCategory.SEED_PHRASE -> Icons.Default.Wallet
        CriticalSecretCategory.PRIVATE_KEY -> Icons.Default.Key
        CriticalSecretCategory.SIGNING_KEY -> Icons.Default.Edit
        CriticalSecretCategory.MASTER_PASSWORD -> Icons.Default.Password
        CriticalSecretCategory.RECOVERY_KEY -> Icons.Default.Restore
        CriticalSecretCategory.OTHER -> Icons.Default.Shield
    }
}
