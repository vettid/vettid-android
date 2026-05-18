package com.vettid.app.features.devices

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePairingScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAuthorize: (connectionId: String) -> Unit,
    viewModel: DevicePairingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state) {
        val s = state
        if (s is DevicePairingState.DevicePending) {
            onNavigateToAuthorize(s.info.connectionId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair Desktop") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.cancel()
                        onNavigateBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (val s = state) {
                is DevicePairingState.Idle -> {
                    Text(
                        "Pair a Desktop",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Generate a pairing code that you'll enter on your desktop to connect it to your vault.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = { viewModel.startPairing() },
                        modifier = Modifier.fillMaxWidth(0.7f)
                    ) {
                        Text("Generate Code")
                    }
                }

                is DevicePairingState.Creating -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Creating pairing invitation...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                is DevicePairingState.ShowingCode -> {
                    Text(
                        "Enter this code on your desktop",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    // Large code display — formatted as three 4-char
                    // blocks (ABCD-EFGH-JKLM) so the 12-char vault
                    // codes fit the card without wrapping or
                    // letter-spacing overlap, and read like one chunk
                    // per glance.
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Text(
                            formatDeviceCode(s.code),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Countdown
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (s.remainingSeconds < 60)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Expires in ${formatCountdown(s.remainingSeconds)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (s.remainingSeconds < 60)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Manual escape hatch: scan the QR the desktop renders
                    // once it's resolved the invite. Normally the vault's
                    // pending-authorization notification auto-navigates the
                    // user to the scanner — this button is the fallback when
                    // that notification is missed/delayed, so the user is
                    // never stranded staring at a code they've already typed
                    // on the desktop.
                    Button(
                        onClick = {
                            val connId = viewModel.pendingConnectionId()
                            if (connId != null) onNavigateToAuthorize(connId)
                        },
                        modifier = Modifier.fillMaxWidth(0.7f)
                    ) {
                        Text("Scan QR Code from Desktop")
                    }
                    Text(
                        "Once you've typed the code into your desktop, scan the QR it shows.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(onClick = { viewModel.cancel() }) {
                        Text("Cancel")
                    }
                }

                is DevicePairingState.DevicePending -> {
                    // UI transition is handled by the LaunchedEffect above.
                    CircularProgressIndicator()
                }

                is DevicePairingState.Timeout -> {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Code Expired", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "The pairing code has expired. Generate a new one.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { viewModel.startPairing() }) {
                        Text("Generate New Code")
                    }
                }

                is DevicePairingState.Error -> {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Error", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { viewModel.startPairing() }) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

private fun formatCountdown(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "${m}:${s.toString().padStart(2, '0')}"
}

// Display 12-char vault codes as three 4-char blocks (ABCD-EFGH-JKLM)
// so the typography fits cleanly and is easier to read aloud / dictate.
// Falls back to the raw code when the length isn't 12 — keeps unknown
// shapes intact rather than guessing where to insert dashes.
private fun formatDeviceCode(code: String): String {
    val clean = code.uppercase()
    return if (clean.length == 12) {
        "${clean.substring(0, 4)}-${clean.substring(4, 8)}-${clean.substring(8, 12)}"
    } else {
        clean
    }
}
