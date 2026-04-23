package com.vettid.app.features.connections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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

    // Process initial data from deep link. The data field arrives in
    // one of two shapes depending on the link MainActivity produced:
    //
    //   - Bare broker codes from share links (vettid.dev/connect?c=CODE)
    //     are wrapped as plain JSON `{"c":"CODE"}` before being passed
    //     as the route arg. Pass it straight through.
    //   - Legacy inline invitations (vettid.dev/connect?data=<base64>)
    //     arrive base64-encoded. Decode first.
    //
    // Previously we always tried to base64-decode, which silently
    // failed on the JSON form and dropped the user into the scanner.
    LaunchedEffect(initialData) {
        if (initialData != null) {
            val payload = if (initialData.trimStart().startsWith("{")) {
                initialData
            } else {
                try {
                    android.util.Base64.decode(
                        initialData.replace('-', '+').replace('_', '/'),
                        android.util.Base64.DEFAULT
                    ).toString(Charsets.UTF_8)
                } catch (e: Exception) {
                    android.util.Log.e("ScanInvitation", "Failed to decode deep link data", e)
                    null
                }
            }
            if (payload != null) {
                android.util.Log.d("ScanInvitation", "Processing deep link data: ${payload.take(100)}...")
                viewModel.onQrCodeScanned(payload)
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

    // Success navigation handled by ScanInvitationEffect.NavigateToConnection

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Invitation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
                    // Acceptance always emits NavigateToConnection, which
                    // pops us back to the Connections list immediately.
                    // The old PendingApprovalContent screen with its
                    // "Return to connections" button was redundant — the
                    // user already acted, and they're already on their
                    // way back. Render a spinner for the couple of
                    // frames before navigation lands.
                    ProcessingContent()
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
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // Manual-entry escape hatch — the icon in the top bar is
        // easy to miss when the camera fills the screen. A full-
        // width text button below the viewfinder makes it obvious
        // that typing the code is a supported path too.
        TextButton(
            onClick = onManualEntry,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Keyboard,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Type the code instead")
        }

        Spacer(modifier = Modifier.height(8.dp))
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
    // Render the inviter's published profile through the same
    // BusinessCardView the user sees for their own profile preview
    // and on an established connection's detail screen. One layout
    // across every "this is what gets shared" surface.
    val firstName = state.creatorName.substringBefore(' ').takeIf { it.isNotBlank() }
    val lastName = state.creatorName.substringAfter(' ', missingDelimiterValue = "")
        .takeIf { it.isNotBlank() }
    val peer = com.vettid.app.core.nats.PeerProfileData(
        firstName = firstName,
        lastName = lastName,
        email = state.creatorEmail,
        photo = state.creatorPhoto,
        fields = state.profileFields,
        publicKey = state.publicKey,
        wallets = state.wallets.map { w ->
            com.vettid.app.core.nats.PeerWalletInfo(
                walletId = w["id"] ?: "",
                label = w["label"] ?: "Wallet",
                address = w["address"] ?: "",
                network = w["network"] ?: "mainnet",
            )
        },
    )
    val published = com.vettid.app.features.personaldata.peerProfileToPublishedProfileData(
        peer,
        fallbackDisplayName = state.creatorName,
        fallbackEmail = state.creatorEmail,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "${state.creatorName} invited you to connect",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))

        com.vettid.app.features.personaldata.PeerProfileView(
            profile = published,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onDecline,
                modifier = Modifier.weight(1f),
            ) {
                Text("Decline")
            }
            Button(
                onClick = onAccept,
                modifier = Modifier.weight(1f),
            ) {
                Text("Accept")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
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
private fun PendingApprovalContent(
    connectionName: String,
    onBack: () -> Unit
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
            CircularProgressIndicator(
                modifier = Modifier.size(64.dp),
                strokeWidth = 4.dp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Waiting for Approval",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "$connectionName needs to review and accept your connection request.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedButton(onClick = onBack) {
                Text("Back to Connections")
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
