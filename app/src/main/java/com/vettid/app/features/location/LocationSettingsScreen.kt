package com.vettid.app.features.location

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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.features.vault.VaultPreferencesEffect
import com.vettid.app.features.vault.VaultPreferencesViewModel
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSettingsScreen(
    viewModel: VaultPreferencesViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onNavigateToLocationHistory: () -> Unit = {},
    onNavigateToSharedLocations: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Location permission launchers
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onLocationPermissionResult(granted)
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
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
                is VaultPreferencesEffect.NavigateToLocationHistory -> {
                    onNavigateToLocationHistory()
                }
                is VaultPreferencesEffect.NavigateToSharedLocations -> {
                    onNavigateToSharedLocations()
                }
                is VaultPreferencesEffect.RequestLocationPermission -> {
                    val permission = if (effect.precision == LocationPrecision.EXACT) {
                        Manifest.permission.ACCESS_FINE_LOCATION
                    } else {
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    }
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
                else -> { /* handled elsewhere */ }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Location Tracking") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            // Enable toggle
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Enable Location Tracking") },
                    supportingContent = { Text("Capture and store location in your vault") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = state.locationTrackingEnabled,
                            onCheckedChange = { viewModel.toggleLocationTracking(it) }
                        )
                    }
                )
            }

            if (state.locationTrackingEnabled) {
                Spacer(modifier = Modifier.height(24.dp))

                // Settings
                Text(
                    text = "SETTINGS",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    // Precision
                    LocationPrecisionSelector(
                        current = state.locationPrecision,
                        onChange = { viewModel.updateLocationPrecision(it) }
                    )

                    HorizontalDivider()

                    // Update frequency
                    LocationDropdownItem(
                        label = "Update Frequency",
                        icon = Icons.Default.Schedule,
                        currentValue = state.locationFrequency.displayName,
                        options = LocationUpdateFrequency.entries.map { it.displayName },
                        onSelected = { index ->
                            viewModel.updateLocationFrequency(LocationUpdateFrequency.entries[index])
                        }
                    )

                    HorizontalDivider()

                    // Displacement threshold
                    LocationDropdownItem(
                        label = "Movement Threshold",
                        icon = Icons.Default.NearMe,
                        currentValue = state.locationDisplacementThreshold.displayName,
                        options = DisplacementThreshold.entries.map { it.displayName },
                        onSelected = { index ->
                            viewModel.updateDisplacementThreshold(DisplacementThreshold.entries[index])
                        }
                    )

                    HorizontalDivider()

                    // Retention
                    LocationDropdownItem(
                        label = "Data Retention",
                        icon = Icons.Default.DeleteSweep,
                        currentValue = state.locationRetention.displayName,
                        options = LocationRetention.entries.map { it.displayName },
                        onSelected = { index ->
                            viewModel.updateLocationRetention(LocationRetention.entries[index])
                        }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Actions
                Text(
                    text = "DATA",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        modifier = Modifier.clickable { viewModel.onViewLocationHistoryClick() },
                        headlineContent = { Text("View Location History") },
                        supportingContent = { Text("See your stored location data") },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Default.History,
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

                    HorizontalDivider()

                    ListItem(
                        modifier = Modifier.clickable { viewModel.onViewSharedLocationsClick() },
                        headlineContent = { Text("Shared Locations") },
                        supportingContent = { Text("See locations shared by connections") },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Default.People,
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

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationPrecisionSelector(
    current: LocationPrecision,
    onChange: (LocationPrecision) -> Unit
) {
    ListItem(
        headlineContent = { Text("Precision") },
        supportingContent = {
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    LocationPrecision.entries.forEachIndexed { index, precision ->
                        SegmentedButton(
                            selected = current == precision,
                            onClick = { onChange(precision) },
                            shape = SegmentedButtonDefaults.itemShape(index, LocationPrecision.entries.size)
                        ) {
                            Text(precision.displayName, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.GpsFixed,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationDropdownItem(
    label: String,
    icon: ImageVector,
    currentValue: String,
    options: List<String>,
    onSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(label) },
        leadingContent = {
            Icon(
                imageVector = icon,
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
                    Text(currentValue, style = MaterialTheme.typography.labelSmall)
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null
                    )
                }
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEachIndexed { index, option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                onSelected(index)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    )
}
