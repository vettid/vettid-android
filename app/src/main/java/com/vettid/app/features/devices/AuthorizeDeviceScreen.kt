package com.vettid.app.features.devices

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.ui.components.QrCodeScanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorizeDeviceScreen(
    connectionId: String,
    onNavigateBack: () -> Unit,
    viewModel: AuthorizeDeviceViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(connectionId) {
        viewModel.setPendingFromNotification(connectionId)
    }

    LaunchedEffect(state) {
        if (state is AuthorizeDeviceState.Done) {
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Authorize Desktop") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val s = state) {
                is AuthorizeDeviceState.Scanning -> {
                    Column(Modifier.fillMaxSize()) {
                        Text(
                            "Scan the QR shown on your desktop",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        QrCodeScanner(
                            onQrCodeScanned = { viewModel.onQrScanned(it) },
                            onError = { /* ignore transient camera errors */ },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is AuthorizeDeviceState.Ready -> {
                    AuthorizeForm(
                        state = s,
                        onNameChange = viewModel::updateDeviceName,
                        onDurationChange = viewModel::updateDuration,
                        onApprove = viewModel::approve,
                        onCancel = onNavigateBack
                    )
                }

                is AuthorizeDeviceState.Submitting -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("Authorizing…")
                        }
                    }
                }

                is AuthorizeDeviceState.Done -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("Authorized", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }

                is AuthorizeDeviceState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(s.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(16.dp))
                            Row {
                                OutlinedButton(onClick = onNavigateBack) { Text("Cancel") }
                                Spacer(Modifier.width(12.dp))
                                Button(onClick = { viewModel.reset() }) { Text("Scan Again") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthorizeForm(
    state: AuthorizeDeviceState.Ready,
    onNameChange: (String) -> Unit,
    onDurationChange: (Long) -> Unit,
    onApprove: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Computer,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Authorize this desktop",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(20.dp))

        // Fingerprint card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                InfoRow("Hostname", state.info.hostname.ifEmpty { "—" })
                InfoRow("Platform", state.info.platform.ifEmpty { "—" })
                if (state.info.osName.isNotEmpty()) InfoRow("OS", "${state.info.osName} ${state.info.osVersion}")
                if (state.info.appVersion.isNotEmpty()) InfoRow("Version", state.info.appVersion)
                if (state.info.clientIp.isNotEmpty()) InfoRow("Client IP", state.info.clientIp)
                if (state.info.binaryFpPrefix.isNotEmpty()) {
                    InfoRow(
                        "Fingerprint",
                        state.info.binaryFpPrefix,
                        mono = true
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Device name
        OutlinedTextField(
            value = state.deviceName,
            onValueChange = onNameChange,
            label = { Text("Device name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))

        // Duration picker
        Text(
            "Session duration",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        DurationPicker(
            seconds = state.durationSeconds,
            maxSeconds = state.info.maxDurationSeconds,
            onChange = onDurationChange
        )

        Spacer(Modifier.height(32.dp))
        Row(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) { Text("Deny") }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onApprove,
                modifier = Modifier.weight(1f)
            ) { Text("Approve") }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null
        )
    }
}

@Composable
private fun DurationPicker(
    seconds: Long,
    maxSeconds: Long,
    onChange: (Long) -> Unit
) {
    var hours by remember(seconds) { mutableStateOf((seconds / 3600).toString()) }
    var minutes by remember(seconds) { mutableStateOf(((seconds % 3600) / 60).toString()) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = hours,
            onValueChange = { input ->
                val filtered = input.filter { it.isDigit() }.take(2)
                hours = filtered
                val h = filtered.toLongOrNull() ?: 0L
                val m = minutes.toLongOrNull() ?: 0L
                onChange((h * 3600 + m * 60).coerceIn(60L, maxSeconds))
            },
            label = { Text("Hours") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            value = minutes,
            onValueChange = { input ->
                val filtered = input.filter { it.isDigit() }.take(2)
                minutes = filtered
                val h = hours.toLongOrNull() ?: 0L
                val m = filtered.toLongOrNull() ?: 0L
                onChange((h * 3600 + m * 60).coerceIn(60L, maxSeconds))
            },
            label = { Text("Minutes") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "Max 24 hours. Current: ${formatDuration(seconds)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}
