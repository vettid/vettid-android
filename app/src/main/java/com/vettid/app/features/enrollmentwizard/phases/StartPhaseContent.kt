package com.vettid.app.features.enrollmentwizard.phases

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vettid.app.ui.components.QrCodeScanner

/**
 * Mode for start phase display
 */
enum class StartPhaseMode {
    SCANNING,
    MANUAL_ENTRY
}

/**
 * Start phase content - QR scanning or manual entry.
 */
@Composable
fun StartPhaseContent(
    mode: StartPhaseMode,
    inviteCode: String = "",
    error: String? = null,
    onCodeScanned: (String) -> Unit = {},
    onInviteCodeChanged: (String) -> Unit = {},
    onSubmitCode: () -> Unit = {},
    onSwitchToManual: () -> Unit = {},
    onSwitchToScanning: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    when (mode) {
        StartPhaseMode.SCANNING -> QRScannerContent(
            error = error,
            onCodeScanned = onCodeScanned,
            onManualEntry = onSwitchToManual,
            onCancel = onCancel
        )
        StartPhaseMode.MANUAL_ENTRY -> ManualEntryContent(
            inviteCode = inviteCode,
            error = error,
            onInviteCodeChange = onInviteCodeChanged,
            onSubmit = onSubmitCode,
            onScanQR = onSwitchToScanning,
            onCancel = onCancel
        )
    }
}

@Composable
private fun QRScannerContent(
    error: String?,
    onCodeScanned: (String) -> Unit,
    onManualEntry: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
            }
            Text(
                text = "Scan Invitation",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onManualEntry) {
                Icon(Icons.Default.Edit, contentDescription = "Enter code manually")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Instructions
        Text(
            text = "Scan the QR code from your VettID web portal to begin enrollment.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // QR Scanner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            QrCodeScanner(
                onQrCodeScanned = { code ->
                    if (code.isNotEmpty()) {
                        onCodeScanned(code)
                    }
                },
                onError = { /* Error handled via error parameter */ },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Error message
        if (error != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        // Manual entry option
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(
            onClick = onManualEntry,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.Edit,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Enter code manually")
        }
    }
}

@Composable
private fun ManualEntryContent(
    inviteCode: String,
    error: String?,
    onInviteCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onScanQR: () -> Unit,
    onCancel: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
            }
            Text(
                text = "Enter Invitation Code",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onScanQR) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR code")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Icon
        Icon(
            imageVector = Icons.Default.VpnKey,
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .align(Alignment.CenterHorizontally),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Instructions
        Text(
            text = "Enter the invitation code from your VettID web portal or paste the enrollment link.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Invite code input
        OutlinedTextField(
            value = inviteCode,
            onValueChange = onInviteCodeChange,
            label = { Text("Invitation Code") },
            placeholder = { Text("Enter code or paste link") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    onSubmit()
                }
            ),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = error != null
        )

        // Error message
        if (error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Submit button
        Button(
            onClick = onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = inviteCode.isNotBlank()
        ) {
            Text("Continue")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Scan QR option
        TextButton(
            onClick = onScanQR,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Scan QR code instead")
        }
    }
}
