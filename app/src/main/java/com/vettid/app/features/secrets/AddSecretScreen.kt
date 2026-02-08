package com.vettid.app.features.secrets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.storage.CriticalSecretCategory
import com.vettid.app.core.storage.SecretCategory
import kotlinx.coroutines.flow.collectLatest

/**
 * Screen for adding a new secret (minor or critical).
 *
 * For minor secrets:
 * - Name, value, category, notes
 * - Stored locally and synced to enclave datastore
 *
 * For critical secrets:
 * - Name, value, category, description
 * - Requires password confirmation
 * - Stored in Protean Credential (never on device)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSecretScreen(
    isCritical: Boolean,
    onBack: () -> Unit,
    viewModel: TwoTierSecretsViewModel = hiltViewModel()
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // Form state
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    var selectedMinorCategory by remember { mutableStateOf(SecretCategory.PASSWORD) }
    var selectedCriticalCategory by remember { mutableStateOf(CriticalSecretCategory.SEED_PHRASE) }

    var showValue by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }

    var isSaving by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is TwoTierSecretsEffect.ShowMessage -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is TwoTierSecretsEffect.ShowError -> {
                    isSaving = false
                    snackbarHostState.showSnackbar(effect.message)
                }
                is TwoTierSecretsEffect.NavigateBack -> {
                    onBack()
                }
                else -> { }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (isCritical) "Add Critical Secret" else "Add Secret")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (isCritical) {
                                showConfirmDialog = true
                            } else {
                                isSaving = true
                                viewModel.onEvent(TwoTierSecretsEvent.AddMinorSecret(
                                    name = name,
                                    value = value,
                                    category = selectedMinorCategory,
                                    notes = notes.takeIf { it.isNotBlank() }
                                ))
                            }
                        },
                        enabled = name.isNotBlank() && value.isNotBlank() && !isSaving
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Save")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Warning for critical secrets
            if (isCritical) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Critical Secret",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "This secret will be stored in your Protean Credential. It can only be accessed after password verification and will never be stored on this device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Name field
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text(if (isCritical) "e.g., Bitcoin Wallet Seed" else "e.g., GitHub Token") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Category dropdown
            ExposedDropdownMenuBox(
                expanded = categoryExpanded,
                onExpandedChange = { categoryExpanded = it }
            ) {
                OutlinedTextField(
                    value = if (isCritical) {
                        selectedCriticalCategory.displayName
                    } else {
                        selectedMinorCategory.displayName
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Category") },
                    leadingIcon = {
                        Icon(
                            if (isCritical) Icons.Default.Shield else Icons.Default.Category,
                            null
                        )
                    },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )

                ExposedDropdownMenu(
                    expanded = categoryExpanded,
                    onDismissRequest = { categoryExpanded = false }
                ) {
                    if (isCritical) {
                        CriticalSecretCategory.entries.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.displayName) },
                                onClick = {
                                    selectedCriticalCategory = category
                                    categoryExpanded = false
                                }
                            )
                        }
                    } else {
                        SecretCategory.entries.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.displayName) },
                                onClick = {
                                    selectedMinorCategory = category
                                    categoryExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Value field
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(if (isCritical) "Secret Value" else "Value") },
                placeholder = {
                    Text(
                        when {
                            isCritical && selectedCriticalCategory == CriticalSecretCategory.SEED_PHRASE ->
                                "word1 word2 word3 ... word24"
                            isCritical -> "Enter the critical secret value"
                            else -> "Enter the secret value"
                        }
                    )
                },
                leadingIcon = { Icon(Icons.Default.Lock, null) },
                trailingIcon = {
                    IconButton(onClick = { showValue = !showValue }) {
                        Icon(
                            if (showValue) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showValue) "Hide" else "Show"
                        )
                    }
                },
                visualTransformation = if (showValue) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next
                ),
                minLines = if (isCritical && selectedCriticalCategory == CriticalSecretCategory.SEED_PHRASE) 3 else 1,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Notes/Description field
            OutlinedTextField(
                value = if (isCritical) description else notes,
                onValueChange = { if (isCritical) description = it else notes = it },
                label = { Text(if (isCritical) "Description (optional)" else "Notes (optional)") },
                placeholder = {
                    Text(
                        if (isCritical) "What is this secret for?"
                        else "Additional notes about this secret"
                    )
                },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Notes, null) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Info card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (isCritical) {
                            "Critical secrets are encrypted and stored in your Protean Credential. They require password verification to access and auto-hide after 30 seconds."
                        } else {
                            "Secrets are encrypted and synced to your vault. They require password verification to view."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Save button (larger for mobile)
            Button(
                onClick = {
                    if (isCritical) {
                        showConfirmDialog = true
                    } else {
                        isSaving = true
                        viewModel.onEvent(TwoTierSecretsEvent.AddMinorSecret(
                            name = name,
                            value = value,
                            category = selectedMinorCategory,
                            notes = notes.takeIf { it.isNotBlank() }
                        ))
                    }
                },
                enabled = name.isNotBlank() && value.isNotBlank() && !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        if (isCritical) Icons.Default.Shield else Icons.Default.Add,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isCritical) "Save Critical Secret" else "Save Secret")
                }
            }
        }

        // Confirmation dialog for critical secrets
        if (showConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmDialog = false },
                icon = {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                },
                title = { Text("Confirm Critical Secret") },
                text = {
                    Column {
                        Text(
                            text = "You are about to save a critical secret:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = selectedCriticalCategory.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Enter your password to confirm:",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
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
                                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showConfirmDialog = false
                            isSaving = true
                            viewModel.onEvent(TwoTierSecretsEvent.AddCriticalSecret(
                                name = name,
                                value = value,
                                category = selectedCriticalCategory,
                                description = description.takeIf { it.isNotBlank() },
                                password = password
                            ))
                            password = ""  // Clear password immediately
                        },
                        enabled = password.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Save Critical Secret")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showConfirmDialog = false
                        password = ""
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
