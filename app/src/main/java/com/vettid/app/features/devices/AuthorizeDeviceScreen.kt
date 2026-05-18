package com.vettid.app.features.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.ui.components.QrCodeScanner
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged

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
private fun DurationPicker(
    seconds: Long,
    maxSeconds: Long,
    onChange: (Long) -> Unit
) {
    // Filter presets to those the vault still allows, and pick the
    // option closest to (but not exceeding) the currently-selected
    // duration so the wheel starts on the right row when the user
    // first sees the form.
    val options = remember(maxSeconds) {
        DURATION_PRESETS.filter { it.second <= maxSeconds }
            .ifEmpty { listOf(DURATION_PRESETS.first()) }
    }
    val initialIndex = remember(seconds, options) {
        options.indexOfLast { it.second <= seconds }.coerceAtLeast(0)
    }

    // Pure-Compose scroll wheel. Pad the list with three blank rows at
    // top and bottom so the selected item (the row at the middle of the
    // viewport) can scroll to any preset including the first and last.
    // Snap-fling locks the scroll to whole-row stops; the centered row
    // is whichever is `firstVisibleItemIndex + paddingRows`.
    val rowHeight = 44.dp
    val paddingRows = 2
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    // Track the centered row as the user scrolls. firstVisibleItem +
    // paddingRows gives the row currently at the highlighted center
    // band; emit a duration change on every distinct landing.
    LaunchedEffect(listState, options) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { idx ->
                val opt = options.getOrNull(idx) ?: return@collect
                onChange(opt.second.coerceAtMost(maxSeconds))
            }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight * (paddingRows * 2 + 1))
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        // Center highlight band — visual cue for which row counts as
        // "selected." Behind the LazyColumn so the row text sits on top.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
        )
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            modifier = Modifier.fillMaxSize()
        ) {
            // Top padding rows so the first real option can land on the
            // center band.
            items(paddingRows) {
                Box(Modifier.fillMaxWidth().height(rowHeight))
            }
            items(options) { (label, _) ->
                val idx = options.indexOfFirst { it.first == label }
                val centerIdx = listState.firstVisibleItemIndex
                val distance = abs(idx - centerIdx)
                val rowAlpha = when (distance) {
                    0 -> 1.0f
                    1 -> 0.55f
                    else -> 0.30f
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .alpha(rowAlpha),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (distance == 0) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
            items(paddingRows) {
                Box(Modifier.fillMaxWidth().height(rowHeight))
            }
        }
    }
}
