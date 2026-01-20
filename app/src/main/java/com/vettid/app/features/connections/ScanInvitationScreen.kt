package com.vettid.app.features.connections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.features.connections.components.ConnectionPreviewCard
import com.vettid.app.features.connections.components.PeerProfilePreview
import com.vettid.app.features.connections.components.CapabilityInfo
import com.vettid.app.features.connections.components.SharedDataType
import com.vettid.app.ui.components.QrCodeScanner

/**
 * Screen for scanning and accepting connection invitations.
 *
 * @param initialData Base64-encoded invitation JSON from deep link (optional)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanInvitationScreen(
    viewModel: ScanInvitationViewModel = hiltViewModel(),
    initialData: String? = null,
    onConnectionEstablished: (String) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val manualCode by viewModel.manualCode.collectAsState()

    // Process initial data from deep link
    LaunchedEffect(initialData) {
        if (initialData != null) {
            // Decode base64 and process as QR data
            try {
                val decoded = android.util.Base64.decode(
                    initialData.replace('-', '+').replace('_', '/'),
                    android.util.Base64.DEFAULT
                ).toString(Charsets.UTF_8)
                android.util.Log.d("ScanInvitation", "Processing deep link data: ${decoded.take(100)}...")
                viewModel.onQrCodeScanned(decoded)
            } catch (e: Exception) {
                android.util.Log.e("ScanInvitation", "Failed to decode deep link data", e)
            }
        }
    }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ScanInvitationEffect.ShowError -> {
                    // Could show a snackbar here
                }
                is ScanInvitationEffect.NavigateToConnection -> {
                    onConnectionEstablished(effect.connectionId)
                }
            }
        }
    }

    // Handle success navigation
    LaunchedEffect(state) {
        if (state is ScanInvitationState.Success) {
            onConnectionEstablished((state as ScanInvitationState.Success).connection.connectionId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Invitation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    when (state) {
                        is ScanInvitationState.Scanning -> {
                            IconButton(onClick = { viewModel.switchToManualEntry() }) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Enter code manually"
                                )
                            }
                        }
                        is ScanInvitationState.ManualEntry -> {
                            IconButton(onClick = { viewModel.switchToScanner() }) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = "Scan QR code"
                                )
                            }
                        }
                        else -> {}
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val currentState = state) {
                is ScanInvitationState.Scanning -> {
                    ScanningContent(
                        onQrCodeScanned = { viewModel.onQrCodeScanned(it) },
                        onManualEntry = { viewModel.switchToManualEntry() }
                    )
                }

                is ScanInvitationState.ManualEntry -> {
                    ManualEntryContent(
                        code = manualCode,
                        onCodeChanged = { viewModel.onManualCodeChanged(it) },
                        onSubmit = { viewModel.onManualCodeEntered() }
                    )
                }

                is ScanInvitationState.Processing -> {
                    ProcessingContent()
                }

                is ScanInvitationState.Preview -> {
                    EnhancedPreviewContent(
                        state = currentState,
                        onAccept = { viewModel.acceptInvitation() },
                        onDecline = { viewModel.declineInvitation() }
                    )
                }

                is ScanInvitationState.Success -> {
                    SuccessContent(
                        connectionName = currentState.connection.peerDisplayName,
                        onContinue = { onConnectionEstablished(currentState.connection.connectionId) }
                    )
                }

                is ScanInvitationState.Error -> {
                    ErrorContent(
                        message = currentState.message,
                        onRetry = { viewModel.retry() }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanningContent(
    onQrCodeScanned: (String) -> Unit,
    onManualEntry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Scan the QR code shown by the person inviting you.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(32.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(16.dp)
            ) {
                QrCodeScanner(
                    onQrCodeScanned = onQrCodeScanned,
                    onError = { /* QrCodeScanner handles permissions internally */ },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Text(
            text = "Position the QR code within the frame",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun ManualEntryContent(
    code: String,
    onCodeChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Enter Invitation Code",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Ask the person inviting you for their invitation code.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = code,
            onValueChange = onCodeChanged,
            label = { Text("Invitation Code") },
            placeholder = { Text("e.g., ABC123-XYZ789") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { onSubmit() }
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onSubmit,
            enabled = code.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Submit")
        }
    }
}

@Composable
private fun ProcessingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Establishing secure connection...",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun EnhancedPreviewContent(
    state: ScanInvitationState.Preview,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        ConnectionPreviewCard(
            profile = PeerProfilePreview(
                displayName = state.creatorName,
                email = state.creatorEmail,
                avatarUrl = state.creatorAvatarUrl,
                publicKeyFingerprint = state.publicKeyFingerprint,
                isEmailVerified = state.isEmailVerified,
                trustLevel = state.trustLevel,
                capabilities = state.capabilities.map { capability ->
                    CapabilityInfo(
                        name = capability,
                        description = getCapabilityDescription(capability),
                        icon = getCapabilityIcon(capability)
                    )
                },
                sharedDataTypes = state.sharedDataCategories.map { category ->
                    SharedDataType(category = category)
                }
            ),
            onAccept = onAccept,
            onDecline = onDecline,
            isProcessing = false
        )
    }
}

private fun getCapabilityDescription(capability: String): String {
    return when (capability.lowercase()) {
        "messaging" -> "Send and receive secure messages"
        "file_sharing" -> "Share documents and files"
        "credential_sharing" -> "Share verifiable credentials"
        "payment" -> "Send and receive payments"
        else -> "Additional capability"
    }
}

private fun getCapabilityIcon(capability: String): String {
    return when (capability.lowercase()) {
        "messaging" -> "messaging"
        "file_sharing" -> "sharing"
        "credential_sharing" -> "credentials"
        "payment" -> "payments"
        else -> "default"
    }
}

@Composable
private fun PreviewContent(
    creatorName: String,
    creatorAvatarUrl: String?,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Card {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Connection Request",
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Avatar placeholder
                Surface(
                    modifier = Modifier.size(80.dp),
                    shape = RoundedCornerShape(40.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = creatorName.take(2).uppercase(),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = creatorName,
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = "wants to connect with you",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDecline,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Decline")
                    }

                    Button(
                        onClick = onAccept,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Accept")
                    }
                }
            }
        }
    }
}

@Composable
private fun SuccessContent(
    connectionName: String,
    onContinue: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Connected!",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "You are now connected with $connectionName",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(onClick = onContinue) {
                Text("Continue")
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Connection Failed",
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
                Text("Try Again")
            }
        }
    }
}
