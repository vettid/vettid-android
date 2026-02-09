package com.vettid.app.features.vault

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.features.location.LocationPrecision
import com.vettid.app.features.settings.AppTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow

/**
 * Vault Preferences screen content.
 * Per mobile-ui-plan.md Section 3.5.4
 */
@Composable
fun VaultPreferencesContent(
    viewModel: VaultPreferencesViewModel = hiltViewModel(),
    onChangePassword: () -> Unit = {},
    onNavigateToAppDetails: () -> Unit = {},
    onNavigateToLocationHistory: () -> Unit = {},
    onNavigateToSharedLocations: () -> Unit = {},
    onNavigateToLocationSettings: () -> Unit = {},
    onNavigateToAgents: () -> Unit = {},
    onNavigateToVaultStatus: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showChangePinDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Location permission launchers
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Background location is optional - proceed either way
        viewModel.onLocationPermissionResult(granted)
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // On Android 10+, also request background location
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                viewModel.onLocationPermissionResult(true)
            }
        } else {
            viewModel.onLocationPermissionResult(false)
        }
    }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is VaultPreferencesEffect.ShowSuccess -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is VaultPreferencesEffect.ShowError -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is VaultPreferencesEffect.NavigateToChangePassword -> {
                    onChangePassword()
                }
                is VaultPreferencesEffect.NavigateToLocationHistory -> {
                    onNavigateToLocationHistory()
                }
                is VaultPreferencesEffect.NavigateToSharedLocations -> {
                    onNavigateToSharedLocations()
                }
                is VaultPreferencesEffect.NavigateToLocationSettings -> {
                    onNavigateToLocationSettings()
                }
                is VaultPreferencesEffect.RequestLocationPermission -> {
                    val permission = if (effect.precision == LocationPrecision.EXACT) {
                        Manifest.permission.ACCESS_FINE_LOCATION
                    } else {
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    }
                    // Check if already granted
                    if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                viewModel.onLocationPermissionResult(true)
                            } else {
                                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            }
                        } else {
                            viewModel.onLocationPermissionResult(true)
                        }
                    } else {
                        locationPermissionLauncher.launch(permission)
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Vault Server Section
            VaultServerSection(
                status = state.vaultServerStatus,
                instanceId = state.vaultInstanceId,
                instanceIp = state.vaultInstanceIp,
                natsEndpoint = state.natsEndpoint,
                actionInProgress = state.vaultActionInProgress,
                errorMessage = state.vaultErrorMessage,
                pcrVersion = state.pcrVersion,
                pcr0Hash = state.pcr0Hash,
                onRefreshClick = { viewModel.refreshVaultStatus() },
                onChangePinClick = { showChangePinDialog = true },
                onSectionClick = onNavigateToVaultStatus
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Credential Settings
            PreferencesSection(title = "CREDENTIAL SETTINGS") {
                // Change Password
                PreferencesItem(
                    icon = Icons.Default.Password,
                    title = "Change Password",
                    subtitle = "Update your vault credential password",
                    onClick = { showChangePasswordDialog = true }
                )

                HorizontalDivider()

                // Session TTL
                TTLDropdownItem(
                    currentTtl = state.sessionTtlMinutes,
                    onTtlChange = { viewModel.updateSessionTtl(it) }
                )

                HorizontalDivider()

                // Credential Backup
                ListItem(
                    headlineContent = { Text("Credential Backup") },
                    supportingContent = {
                        Text(
                            if (state.backupEnabled) "Credentials backed up on use"
                            else "Backups disabled"
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = state.backupEnabled,
                            onCheckedChange = { viewModel.toggleBackup(it) }
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Archive Settings
            PreferencesSection(title = "ARCHIVE SETTINGS") {
                ArchiveDropdownItem(
                    label = "Archive after",
                    currentValue = state.archiveAfterDays,
                    options = listOf(7, 14, 30, 60, 90),
                    onValueChange = { viewModel.updateArchiveAfterDays(it) }
                )

                HorizontalDivider()

                ArchiveDropdownItem(
                    label = "Delete after",
                    currentValue = state.deleteAfterDays,
                    options = listOf(30, 60, 90, 180, 365),
                    onValueChange = { viewModel.updateDeleteAfterDays(it) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Location Tracking link
            PreferencesSection(title = "LOCATION") {
                PreferencesItem(
                    icon = Icons.Default.LocationOn,
                    title = "Location Tracking",
                    subtitle = if (state.locationTrackingEnabled) "Enabled" else "Disabled",
                    onClick = { viewModel.onLocationSettingsClick() }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Appearance section
            AppearanceSection(
                currentTheme = state.theme,
                onThemeChange = { viewModel.updateTheme(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // About section
            AboutSection(onNavigateToAppDetails = onNavigateToAppDetails)

            Spacer(modifier = Modifier.height(24.dp))

            // Help & Support section
            HelpSupportSection()

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Snackbar host
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // Change PIN dialog
    if (showChangePinDialog) {
        ChangePinDialog(
            onDismiss = { showChangePinDialog = false },
            onChangePIN = { currentPin, newPin, onError ->
                viewModel.changePIN(currentPin, newPin) { result ->
                    result.fold(
                        onSuccess = { showChangePinDialog = false },
                        onFailure = { e -> onError(e.message ?: "PIN change failed") }
                    )
                }
            }
        )
    }

    // Change Password dialog
    if (showChangePasswordDialog) {
        ChangePasswordDialog(
            onDismiss = { showChangePasswordDialog = false },
            onChangePassword = { currentPassword, newPassword, onError ->
                viewModel.changePassword(currentPassword, newPassword) { result ->
                    result.fold(
                        onSuccess = { showChangePasswordDialog = false },
                        onFailure = { e -> onError(e.message ?: "Password change failed") }
                    )
                }
            }
        )
    }
}

@Composable
private fun ChangePinDialog(
    onDismiss: () -> Unit,
    onChangePIN: (currentPin: String, newPin: String, onError: (String) -> Unit) -> Unit
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isChanging by remember { mutableStateOf(false) }

    val pinsMatch = newPin.length >= 4 && confirmPin.isNotEmpty() && newPin == confirmPin

    AlertDialog(
        onDismissRequest = { if (!isChanging) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Pin,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Change PIN")
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Current PIN
                OutlinedTextField(
                    value = currentPin,
                    onValueChange = { value ->
                        currentPin = value.filter { it.isDigit() }.take(8)
                        error = null
                    },
                    label = { Text("Current PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    enabled = !isChanging,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // New PIN
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { value ->
                        newPin = value.filter { it.isDigit() }.take(8)
                        error = null
                    },
                    label = { Text("New PIN (4-8 digits)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    enabled = !isChanging,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Confirm New PIN
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { value ->
                        confirmPin = value.filter { it.isDigit() }.take(8)
                        error = null
                    },
                    label = { Text("Confirm New PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    trailingIcon = {
                        if (confirmPin.isNotEmpty()) {
                            Icon(
                                imageVector = if (pinsMatch) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                contentDescription = if (pinsMatch) "PINs match" else "PINs don't match",
                                tint = if (pinsMatch) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    isError = confirmPin.isNotEmpty() && !pinsMatch,
                    singleLine = true,
                    enabled = !isChanging,
                    modifier = Modifier.fillMaxWidth()
                )

                // Error message
                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Loading indicator
                if (isChanging) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Changing PIN...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Validate
                    when {
                        currentPin.isEmpty() || !currentPin.all { it.isDigit() } -> {
                            error = "Enter your current PIN"
                        }
                        newPin.length !in 4..8 || !newPin.all { it.isDigit() } -> {
                            error = "New PIN must be 4-8 digits"
                        }
                        confirmPin != newPin -> {
                            error = "PINs do not match"
                        }
                        newPin == currentPin -> {
                            error = "New PIN must be different from current PIN"
                        }
                        else -> {
                            isChanging = true
                            error = null
                            onChangePIN(currentPin, newPin) { errorMsg ->
                                error = errorMsg
                                isChanging = false
                            }
                        }
                    }
                },
                enabled = !isChanging
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isChanging
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onChangePassword: (currentPassword: String, newPassword: String, onError: (String) -> Unit) -> Unit
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isChanging by remember { mutableStateOf(false) }
    var currentPasswordVisible by remember { mutableStateOf(false) }
    var newPasswordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }

    val passwordsMatch = newPassword.isNotEmpty() && confirmPassword.isNotEmpty() && newPassword == confirmPassword

    AlertDialog(
        onDismissRequest = { if (!isChanging) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Password,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Change Password")
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Current Password
                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = {
                        currentPassword = it
                        error = null
                    },
                    label = { Text("Current Password") },
                    visualTransformation = if (currentPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { currentPasswordVisible = !currentPasswordVisible }) {
                            Icon(
                                if (currentPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (currentPasswordVisible) "Hide" else "Show"
                            )
                        }
                    },
                    singleLine = true,
                    enabled = !isChanging,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // New Password
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = {
                        newPassword = it
                        error = null
                    },
                    label = { Text("New Password") },
                    visualTransformation = if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { newPasswordVisible = !newPasswordVisible }) {
                            Icon(
                                if (newPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (newPasswordVisible) "Hide" else "Show"
                            )
                        }
                    },
                    singleLine = true,
                    enabled = !isChanging,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Confirm New Password
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        error = null
                    },
                    label = { Text("Confirm New Password") },
                    visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Match indicator
                            if (confirmPassword.isNotEmpty()) {
                                Icon(
                                    imageVector = if (passwordsMatch) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                    contentDescription = if (passwordsMatch) "Passwords match" else "Passwords don't match",
                                    tint = if (passwordsMatch) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                Icon(
                                    if (confirmPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (confirmPasswordVisible) "Hide" else "Show"
                                )
                            }
                        }
                    },
                    isError = confirmPassword.isNotEmpty() && !passwordsMatch,
                    singleLine = true,
                    enabled = !isChanging,
                    modifier = Modifier.fillMaxWidth()
                )

                // Error message
                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Loading indicator
                if (isChanging) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Changing password...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        currentPassword.isEmpty() -> {
                            error = "Enter your current password"
                        }
                        newPassword.length < 8 -> {
                            error = "New password must be at least 8 characters"
                        }
                        !passwordsMatch -> {
                            error = "Passwords do not match"
                        }
                        newPassword == currentPassword -> {
                            error = "New password must be different from current password"
                        }
                        else -> {
                            isChanging = true
                            error = null
                            onChangePassword(currentPassword, newPassword) { errorMsg ->
                                error = errorMsg
                                isChanging = false
                            }
                        }
                    }
                },
                enabled = !isChanging
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isChanging
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun PreferencesSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun PreferencesItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSection(
    currentTheme: AppTheme,
    onThemeChange: (AppTheme) -> Unit
) {
    PreferencesSection(title = "APPEARANCE") {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                AppTheme.values().forEachIndexed { index, theme ->
                    SegmentedButton(
                        selected = currentTheme == theme,
                        onClick = { onThemeChange(theme) },
                        shape = SegmentedButtonDefaults.itemShape(index, AppTheme.values().size),
                        icon = {
                            Icon(
                                imageVector = when (theme) {
                                    AppTheme.AUTO -> Icons.Default.BrightnessAuto
                                    AppTheme.LIGHT -> Icons.Default.LightMode
                                    AppTheme.DARK -> Icons.Default.DarkMode
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    ) {
                        Text(theme.displayName.substringBefore(" ("))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TTLDropdownItem(
    currentTtl: Int,
    onTtlChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(5, 15, 30, 60)

    ListItem(
        headlineContent = { Text("Session TTL") },
        supportingContent = { Text("Time before re-authentication") },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.Timer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                ) {
                    Text("$currentTtl min")
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null
                    )
                }
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text("$option minutes") },
                            onClick = {
                                onTtlChange(option)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchiveDropdownItem(
    label: String,
    currentValue: Int,
    options: List<Int>,
    onValueChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(label) },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.Inventory2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                ) {
                    Text("$currentValue days")
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null
                    )
                }
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text("$option days") },
                            onClick = {
                                onValueChange(option)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    )
}

/**
 * Full-screen Vault Preferences with Scaffold and back navigation.
 * Used when navigating from More menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultPreferencesScreenFull(
    viewModel: VaultPreferencesViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onNavigateToAppDetails: () -> Unit = {},
    onNavigateToLocationHistory: () -> Unit = {},
    onNavigateToSharedLocations: () -> Unit = {},
    onNavigateToLocationSettings: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preferences") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            VaultPreferencesContent(
                viewModel = viewModel,
                onNavigateToAppDetails = onNavigateToAppDetails,
                onNavigateToLocationHistory = onNavigateToLocationHistory,
                onNavigateToSharedLocations = onNavigateToSharedLocations,
                onNavigateToLocationSettings = onNavigateToLocationSettings
            )
        }
    }
}

// MARK: - Vault Server Section

@Composable
private fun VaultServerSection(
    status: VaultServerStatus,
    instanceId: String?,
    instanceIp: String?,
    natsEndpoint: String?,
    actionInProgress: Boolean,
    errorMessage: String?,
    pcrVersion: String?,
    pcr0Hash: String?,
    onRefreshClick: () -> Unit,
    onChangePinClick: () -> Unit = {},
    onSectionClick: () -> Unit = {}
) {
    PreferencesSection(title = "VAULT") {
        // Status Row — tap to navigate to Vault Status screen
        ListItem(
            modifier = Modifier.clickable(onClick = onSectionClick),
            headlineContent = { Text("Status") },
            supportingContent = {
                Text(
                    text = when (status) {
                        VaultServerStatus.UNKNOWN -> "Unknown"
                        VaultServerStatus.LOADING -> "Checking..."
                        VaultServerStatus.ENCLAVE_READY -> "Enclave Ready"
                        VaultServerStatus.RUNNING -> "Running"
                        VaultServerStatus.STOPPED -> "Stopped"
                        VaultServerStatus.STARTING -> "Starting..."
                        VaultServerStatus.STOPPING -> "Stopping..."
                        VaultServerStatus.PENDING -> "Pending"
                        VaultServerStatus.ERROR -> errorMessage ?: "Error"
                    }
                )
            },
            leadingContent = {
                Icon(
                    imageVector = when (status) {
                        VaultServerStatus.ENCLAVE_READY -> Icons.Default.CheckCircle
                        VaultServerStatus.RUNNING -> Icons.Default.CheckCircle
                        VaultServerStatus.STOPPED -> Icons.Default.Cancel
                        VaultServerStatus.ERROR -> Icons.Default.Error
                        else -> Icons.Default.Cloud
                    },
                    contentDescription = null,
                    tint = when (status) {
                        VaultServerStatus.ENCLAVE_READY -> Color(0xFF4CAF50) // Green
                        VaultServerStatus.RUNNING -> Color(0xFF4CAF50) // Green
                        VaultServerStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                        VaultServerStatus.ERROR -> MaterialTheme.colorScheme.error
                        VaultServerStatus.STARTING, VaultServerStatus.STOPPING -> Color(0xFFFFA000) // Amber
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            },
            trailingContent = {
                if (status == VaultServerStatus.LOADING || actionInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(onClick = onRefreshClick) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh status"
                        )
                    }
                }
            }
        )

        // Instance Info (only when running)
        if (status == VaultServerStatus.RUNNING && instanceId != null) {
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Instance") },
                supportingContent = { Text(instanceId) },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }

        if (status == VaultServerStatus.RUNNING && natsEndpoint != null) {
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("NATS Endpoint") },
                supportingContent = { Text(natsEndpoint) },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.Hub,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }

        // PCR Attestation Info
        if (pcrVersion != null || pcr0Hash != null) {
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Enclave Attestation") },
                supportingContent = {
                    Column {
                        if (pcrVersion != null) {
                            Text("PCR Version: $pcrVersion")
                        }
                        if (pcr0Hash != null) {
                            // Show full PCR0 hash with word wrap
                            Text(
                                text = "PCR0:",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = pcr0Hash,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                softWrap = true
                            )
                        }
                    }
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50)
                    )
                }
            )
        }

        // Change PIN
        HorizontalDivider()
        PreferencesItem(
            icon = Icons.Default.Pin,
            title = "Change PIN",
            subtitle = "Update your vault unlock PIN (4-8 digits)",
            onClick = onChangePinClick
        )

        // Error message card (if there's an error)
        if (status == VaultServerStatus.ERROR && errorMessage != null) {
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
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

}

// MARK: - About Section

@Composable
private fun AgentConnectionsSection(onNavigateToAgents: () -> Unit = {}) {
    Text(
        text = "AGENT CONNECTIONS",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        ListItem(
            modifier = Modifier.clickable { onNavigateToAgents() },
            headlineContent = { Text("Agent Connections") },
            supportingContent = { Text("Manage AI agent access to your vault") },
            leadingContent = {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            trailingContent = {
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
        )
    }
}

@Composable
private fun AboutSection(onNavigateToAppDetails: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Get app version info
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }
    val versionCode = remember {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toString()
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    PreferencesSection(title = "ABOUT") {
        ListItem(
            modifier = Modifier.clickable { onNavigateToAppDetails() },
            headlineContent = { Text("Version") },
            supportingContent = { Text("$versionName ($versionCode)") },
            leadingContent = {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}

// MARK: - Help & Support Section

@Composable
private fun HelpSupportSection() {
    val context = androidx.compose.ui.platform.LocalContext.current

    PreferencesSection(title = "HELP & SUPPORT") {
        ListItem(
            modifier = Modifier.clickable {
                // Open support URL or email
                try {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://vettid.com/support")
                    )
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Ignore
                }
            },
            headlineContent = { Text("Get Help") },
            supportingContent = { Text("FAQs, guides, and contact support") },
            leadingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Help,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )

        HorizontalDivider()

        ListItem(
            modifier = Modifier.clickable {
                // Open feedback email
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                        data = android.net.Uri.parse("mailto:support@vettid.com")
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "VettID App Feedback")
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Ignore
                }
            },
            headlineContent = { Text("Send Feedback") },
            supportingContent = { Text("Report issues or suggest improvements") },
            leadingContent = {
                Icon(
                    imageVector = Icons.Default.Feedback,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}

