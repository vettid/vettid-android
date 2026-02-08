package com.vettid.app.features.services

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.vettid.app.ui.components.QrCodeScanner

/**
 * Screen for initiating a new service connection via QR code or deep link.
 *
 * Flow:
 * 1. Scan QR code or receive deep link data
 * 2. Parse service offering/contract
 * 3. Show service preview for user review
 * 4. Navigate to contract review on confirm
 *
 * Issue #22 [AND-001] - Service connection initiation screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceInitiationScreen(
    viewModel: ServiceInitiationViewModel = hiltViewModel(),
    initialData: String? = null,
    onNavigateToContractReview: (contractId: String) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val manualCode by viewModel.manualCode.collectAsState()

    // Process initial data from deep link
    LaunchedEffect(initialData) {
        if (initialData != null) {
            try {
                val decoded = android.util.Base64.decode(
                    initialData.replace('-', '+').replace('_', '/'),
                    android.util.Base64.DEFAULT
                ).toString(Charsets.UTF_8)
                android.util.Log.d("ServiceInitiation", "Processing deep link: ${decoded.take(100)}...")
                viewModel.onQrCodeScanned(decoded)
            } catch (e: Exception) {
                android.util.Log.e("ServiceInitiation", "Failed to decode deep link data", e)
            }
        }
    }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ServiceInitiationEffect.NavigateToContractReview -> {
                    onNavigateToContractReview(effect.contractId)
                }
                is ServiceInitiationEffect.ShowError -> {
                    // Handle error display
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state) {
                            is ServiceInitiationState.Scanning,
                            is ServiceInitiationState.ManualEntry -> "Connect to Service"
                            is ServiceInitiationState.Fetching -> "Loading..."
                            is ServiceInitiationState.Preview -> "Review Service"
                            is ServiceInitiationState.Error -> "Connection Error"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    when (state) {
                        is ServiceInitiationState.Scanning -> {
                            IconButton(onClick = { viewModel.switchToManualEntry() }) {
                                Icon(Icons.Default.Edit, contentDescription = "Enter code manually")
                            }
                        }
                        is ServiceInitiationState.ManualEntry -> {
                            IconButton(onClick = { viewModel.switchToScanning() }) {
                                Icon(Icons.Default.CameraAlt, contentDescription = "Scan QR code")
                            }
                        }
                        else -> {}
                    }
                }
            )
        }
    ) { padding ->
        when (val currentState = state) {
            is ServiceInitiationState.Scanning -> {
                ScanningContent(
                    modifier = Modifier.padding(padding),
                    onQrScanned = { viewModel.onQrCodeScanned(it) }
                )
            }
            is ServiceInitiationState.ManualEntry -> {
                ManualEntryContent(
                    modifier = Modifier.padding(padding),
                    code = manualCode,
                    onCodeChanged = { viewModel.updateManualCode(it) },
                    onSubmit = { viewModel.submitManualCode() }
                )
            }
            is ServiceInitiationState.Fetching -> {
                FetchingContent(modifier = Modifier.padding(padding))
            }
            is ServiceInitiationState.Preview -> {
                ServicePreviewContent(
                    modifier = Modifier.padding(padding),
                    preview = currentState.preview,
                    onConfirm = { viewModel.confirmConnection() },
                    onCancel = onBack
                )
            }
            is ServiceInitiationState.Error -> {
                ErrorContent(
                    modifier = Modifier.padding(padding),
                    message = currentState.message,
                    onRetry = { viewModel.reset() }
                )
            }
        }
    }
}

@Composable
private fun ScanningContent(
    modifier: Modifier = Modifier,
    onQrScanned: (String) -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // QR Scanner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            QrCodeScanner(
                modifier = Modifier.fillMaxSize(),
                onQrCodeScanned = onQrScanned,
                onError = { /* Handle scanner error */ }
            )
        }

        // Instructions
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Scan Service QR Code",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Point your camera at a service's QR code to connect",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ManualEntryContent(
    modifier: Modifier = Modifier,
    code: String,
    onCodeChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Icon(
            imageVector = Icons.Outlined.Link,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Enter Service Code",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Enter the code provided by the service",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = code,
            onValueChange = onCodeChanged,
            label = { Text("Service Code") },
            placeholder = { Text("e.g., svc_abc123xyz") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() })
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            enabled = code.isNotBlank()
        ) {
            Text("Connect")
        }
    }
}

@Composable
private fun FetchingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Fetching service details...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ServicePreviewContent(
    modifier: Modifier = Modifier,
    preview: ServicePreview,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Service Header
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Logo
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (preview.logoUrl != null) {
                        AsyncImage(
                            model = preview.logoUrl,
                            contentDescription = preview.serviceName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Business,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Name and verification
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = preview.serviceName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (preview.isVerified) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = "Verified",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Organization
                Text(
                    text = preview.organizationName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Category badge
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = preview.category,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Description
                Text(
                    text = preview.description,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // What this service can do
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "What this service can do",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                preview.capabilities.forEach { capability ->
                    CapabilityRow(
                        icon = getCapabilityIcon(capability),
                        text = capability
                    )
                }
            }
        }

        // Data access
        if (preview.dataAccess.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Data access requested",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    preview.dataAccess.forEach { access ->
                        DataAccessRow(text = access)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }

            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f)
            ) {
                Text("Review Contract")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun CapabilityRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun DataAccessRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.tertiary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun getCapabilityIcon(capability: String): androidx.compose.ui.graphics.vector.ImageVector {
    return when {
        capability.contains("auth", ignoreCase = true) -> Icons.Outlined.Lock
        capability.contains("message", ignoreCase = true) -> Icons.AutoMirrored.Outlined.Message
        capability.contains("payment", ignoreCase = true) -> Icons.Outlined.Payment
        capability.contains("notification", ignoreCase = true) -> Icons.Outlined.Notifications
        capability.contains("data", ignoreCase = true) -> Icons.Outlined.Storage
        capability.contains("read", ignoreCase = true) -> Icons.Outlined.Visibility
        capability.contains("write", ignoreCase = true) -> Icons.Outlined.Edit
        else -> Icons.Outlined.CheckCircle
    }
}

@Composable
private fun ErrorContent(
    modifier: Modifier = Modifier,
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Connection Failed",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Try Again")
            }
        }
    }
}
