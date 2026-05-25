package com.vettid.app.features.agents

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Per-agent detail screen. Shows the agent's capability contract
 * (read-only — capabilities are set at pair/extend time) and the bulk
 * visibility section where the owner toggles which minor secrets are
 * catalog-visible. Toggling here also affects peer-profile visibility:
 * "visible" maps to Discoverability=cataloged globally.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDetailScreen(
    connectionId: String,
    onNavigateBack: () -> Unit,
    viewModel: AgentDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(connectionId) { viewModel.bind(connectionId) }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { e ->
            when (e) {
                is AgentDetailEffect.ShowError -> snackbar.showSnackbar(e.message)
                is AgentDetailEffect.ShowMessage -> snackbar.showSnackbar(e.message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        when (val s = state) {
            AgentDetailState.Loading -> Box(
                Modifier.fillMaxSize().padding(pad),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is AgentDetailState.Error -> Box(
                Modifier.fillMaxSize().padding(pad).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) { Text(s.message, style = MaterialTheme.typography.bodyLarge) }

            is AgentDetailState.Loaded -> {
                LoadedBody(
                    s = s,
                    pad = pad,
                    onToggle = { id, on -> viewModel.onEvent(AgentDetailEvent.ToggleVisibility(id, on)) },
                    onShowAll = { viewModel.onEvent(AgentDetailEvent.ShowAll) },
                    onHideAll = { viewModel.onEvent(AgentDetailEvent.HideAll) },
                    onOpenMint = { viewModel.onEvent(AgentDetailEvent.OpenMint) },
                )
                s.mint?.let { mint ->
                    MintDialog(
                        state = mint,
                        onClose = { viewModel.onEvent(AgentDetailEvent.CloseMint) },
                        onPubkeyChange = { viewModel.onEvent(AgentDetailEvent.MintSetAgentPubkey(it)) },
                        onToggleScope = { token, granted ->
                            viewModel.onEvent(AgentDetailEvent.MintToggleScope(token, granted))
                        },
                        onCustomScopeChange = { viewModel.onEvent(AgentDetailEvent.MintSetCustomScope(it)) },
                        onDurationChange = { viewModel.onEvent(AgentDetailEvent.MintSetDuration(it)) },
                        onConfirm = { viewModel.onEvent(AgentDetailEvent.MintConfirm) },
                        onDismissResult = { viewModel.onEvent(AgentDetailEvent.MintDismissResult) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadedBody(
    s: AgentDetailState.Loaded,
    pad: PaddingValues,
    onToggle: (String, Boolean) -> Unit,
    onShowAll: () -> Unit,
    onHideAll: () -> Unit,
    onOpenMint: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
    ) {
        item { Spacer(Modifier.height(12.dp)) }
        item { AgentHeader(s.agent) }
        item { Spacer(Modifier.height(16.dp)) }
        item { CapabilitiesSection(s.agent.scope) }
        item { Spacer(Modifier.height(16.dp)) }
        item { LeashSection(onMint = onOpenMint) }
        item { Spacer(Modifier.height(16.dp)) }
        item { VisibilityHeader(visibleCount = s.items.count { it.visible }, total = s.items.size, onShowAll = onShowAll, onHideAll = onHideAll) }
        item { Spacer(Modifier.height(4.dp)) }
        items(s.items, key = { it.secretId }) { item ->
            VisibilityRow(item = item, onToggle = { on -> onToggle(item.secretId, on) })
        }
        if (s.items.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) {
                    Text(
                        "No secrets in your vault yet. Add a secret first; it will appear here for sharing.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun LeashSection(onMint: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("LEASH delegation", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Mint a short-lived JWT scoped to specific resources. Use it to let " +
                    "this agent prove its delegated authority against external validators " +
                    "(e.g. vettid.dev/leash).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onMint) {
                Text("Mint LEASH")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MintDialog(
    state: MintDialogState,
    onClose: () -> Unit,
    onPubkeyChange: (String) -> Unit,
    onToggleScope: (String, Boolean) -> Unit,
    onCustomScopeChange: (String) -> Unit,
    onDurationChange: (Long) -> Unit,
    onConfirm: () -> Unit,
    onDismissResult: () -> Unit,
) {
    // Result-mode dialog: shows the minted JWT.
    state.result?.let { result ->
        MintResultDialog(result = result, onDismiss = onDismissResult)
        return
    }

    AlertDialog(
        onDismissRequest = { if (!state.submitting) onClose() },
        title = { Text("Mint LEASH") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Agent pubkey", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Run `vettid-agent leash pubkey` on the agent and paste the output here. The LEASH binds to this exact key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = state.agentPubkeyB64,
                    onValueChange = onPubkeyChange,
                    label = { Text("base64url Ed25519 pubkey") },
                    singleLine = true,
                    enabled = !state.submitting,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))
                Text("Scopes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                state.scopes.forEach { toggle ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(toggle.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                toggle.token,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = toggle.granted,
                            enabled = !state.submitting,
                            onCheckedChange = { onToggleScope(toggle.token, it) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.customScope,
                    onValueChange = onCustomScopeChange,
                    label = { Text("Custom scope (optional)") },
                    placeholder = { Text("resource.field:action") },
                    singleLine = true,
                    enabled = !state.submitting,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))
                Text("Duration", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                DurationChips(
                    seconds = state.durationSeconds,
                    onChange = onDurationChange,
                    enabled = !state.submitting,
                )

                if (state.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !state.submitting) {
                if (state.submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.submitting) "Minting…" else "Mint")
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = !state.submitting) { Text("Cancel") }
        },
    )
}

@Composable
private fun DurationChips(seconds: Long, onChange: (Long) -> Unit, enabled: Boolean) {
    val options = listOf(
        15L * 60L to "15m",
        30L * 60L to "30m",
        60L * 60L to "1h",
        24L * 60L * 60L to "24h",
    )
    Row {
        options.forEach { (s, label) ->
            FilterChip(
                selected = seconds == s,
                onClick = { onChange(s) },
                label = { Text(label) },
                enabled = enabled,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}

@Composable
private fun MintResultDialog(result: MintResult, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val qrBitmap = remember(result.jwt) { generateQrCode(result.jwt, 600) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("LEASH minted") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Scan or copy the JWT below into your agent.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "LEASH QR code",
                        modifier = Modifier.size(220.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        result.jwt,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { copyToClipboard(context, "LEASH", result.jwt) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy JWT")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "jti: ${result.jti}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "expires: ${java.time.Instant.ofEpochSecond(result.expiresAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Done") }
        },
    )
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun generateQrCode(content: String, size: Int): Bitmap? = try {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) {
        bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
    bmp
} catch (_: Exception) {
    null
}

@Composable
private fun AgentHeader(agent: AgentConnection) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(agent.agentName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (agent.agentType.isNotBlank()) {
                    Text(agent.agentType, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!agent.hostname.isNullOrBlank()) {
                    Text(agent.hostname, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CapabilitiesSection(scope: List<String>) {
    Text("Capabilities", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    if (scope.isEmpty()) {
        Text("No capabilities granted.", style = MaterialTheme.typography.bodySmall)
        return
    }
    scope.forEach { token ->
        val meta = scopeMeta(token)
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(meta.label, style = MaterialTheme.typography.bodyMedium)
                Text(meta.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun VisibilityHeader(
    visibleCount: Int,
    total: Int,
    onShowAll: () -> Unit,
    onHideAll: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Visible to this agent", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "$visibleCount of $total · Changes also apply to peer connections",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onHideAll, enabled = visibleCount > 0) { Text("Hide all") }
        TextButton(onClick = onShowAll, enabled = visibleCount < total) { Text("Show all") }
    }
}

@Composable
private fun VisibilityRow(item: VisibilityItem, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium)
            if (item.category.isNotBlank()) {
                Text(item.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = item.visible, onCheckedChange = onToggle)
    }
}
