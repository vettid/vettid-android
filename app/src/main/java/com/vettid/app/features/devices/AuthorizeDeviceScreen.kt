package com.vettid.app.features.devices

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
    val isRename = state.info.existingAlias.isNullOrBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Header — compact title row so the rest fits without scroll.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Computer,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "Authorize this desktop",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    state.info.existingAlias?.takeIf { it.isNotBlank() } ?: state.deviceName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Identity card — verify against the desktop's Settings →
        // Device tab. Full fingerprints (not prefixes) so the user
        // can confirm bit-for-bit.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(12.dp)) {
                InfoRow("Hostname", state.info.hostname.ifEmpty { "—" })
                if (state.info.osName.isNotEmpty()) {
                    InfoRow("OS", "${state.info.osName} ${state.info.osVersion}".trim())
                }
                if (state.info.platform.isNotEmpty()) InfoRow("Platform", state.info.platform)
                if (state.info.appVersion.isNotEmpty()) InfoRow("App version", state.info.appVersion)
                if (state.info.clientIp.isNotEmpty()) InfoRow("Client IP", state.info.clientIp)
                if (state.info.binaryFingerprint.isNotEmpty()) {
                    InfoRow("Binary fingerprint", state.info.binaryFingerprint, mono = true)
                }
                if (state.info.machineFingerprint.isNotEmpty()) {
                    InfoRow("Machine fingerprint", state.info.machineFingerprint, mono = true)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Device name — editable only on first-pair. On re-auth we
        // already have a user-chosen alias on the connection record;
        // changing it every session creates churn for no benefit.
        if (isRename) {
            OutlinedTextField(
                value = state.deviceName,
                onValueChange = onNameChange,
                label = { Text("Device name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
        }

        // Session duration — horizontal chip row instead of the wheel.
        // Compact, scannable, snaps to preset durations without the
        // 160dp vertical real-estate the wheel was eating.
        Text(
            "Session duration",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(6.dp))
        DurationChips(
            seconds = state.durationSeconds,
            maxSeconds = state.info.maxDurationSeconds,
            onChange = onDurationChange,
        )

        // Weight = 1 spacer pushes the action row to the bottom so it's
        // always visible without scrolling.
        Spacer(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            ) { Text("Deny") }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onApprove,
                modifier = Modifier.weight(1f),
            ) { Text("Approve") }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    // 64-char mono fingerprints don't fit a single line on most
    // phones; wrap naturally and use a smaller font for mono rows so
    // the row doesn't grow taller than the rest of the card.
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            value,
            style = if (mono) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
        )
    }
}

// Preset durations the wheel picker scrolls through. These are the
// useful "how long should this desktop stay paired" choices — common
// session lengths plus a few long-lived options for trusted machines.
// Values are clamped at use-time against the vault's maxDurationSeconds
// so a vault policy reduction can hide longer options without changing
// this list.
private val DURATION_PRESETS: List<Pair<String, Long>> = listOf(
    "1 hour" to 60L * 60L,
    "4 hours" to 4L * 60L * 60L,
    "8 hours" to 8L * 60L * 60L,
    "12 hours" to 12L * 60L * 60L,
    "1 day" to 24L * 60L * 60L,
    "3 days" to 3L * 24L * 60L * 60L,
    "7 days" to 7L * 24L * 60L * 60L,
    "14 days" to 14L * 24L * 60L * 60L,
    "30 days" to 30L * 24L * 60L * 60L,
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DurationChips(
    seconds: Long,
    maxSeconds: Long,
    onChange: (Long) -> Unit,
) {
    val options = remember(maxSeconds) {
        DURATION_PRESETS.filter { it.second <= maxSeconds }
            .ifEmpty { listOf(DURATION_PRESETS.first()) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (label, secs) ->
            val selected = seconds == secs
            FilterChip(
                selected = selected,
                onClick = { onChange(secs.coerceAtMost(maxSeconds)) },
                label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
            )
        }
    }
}

