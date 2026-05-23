package com.vettid.app.features.agents

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.nats.AgentPendingAuthNotification

/**
 * Stage-2 authorization screen for vettid-agent pairing.
 *
 * Flow: vettid-agent posts agent.request-session → vault stores
 * AgentPendingAuth + publishes agent.pending-authorization → VettIDApp
 * auto-navigates here → owner reviews identity card + scope picker +
 * duration → Approve fires agent.authorize-session.
 *
 * Mirrors AuthorizeDeviceScreen layout but with a scope picker as the
 * load-bearing input (the agent's requested_scope is a hint; the owner
 * locks in the final set).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorizeAgentScreen(
    connectionId: String,
    onNavigateBack: () -> Unit,
    viewModel: AuthorizeAgentViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(connectionId) {
        viewModel.bindConnection(connectionId)
    }

    LaunchedEffect(state) {
        if (state is AuthorizeAgentState.Done) onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Authorize Agent") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is AuthorizeAgentState.Waiting -> WaitingContent()
                is AuthorizeAgentState.Ready -> ReadyForm(
                    state = s,
                    onToggleScope = viewModel::toggleScope,
                    onApprovalModeChange = viewModel::setApprovalMode,
                    onDurationChange = viewModel::setDuration,
                    onApprove = viewModel::approve,
                    onDeny = viewModel::deny,
                )
                is AuthorizeAgentState.Submitting -> ProgressContent("Authorizing…")
                is AuthorizeAgentState.Done -> DoneContent()
                is AuthorizeAgentState.Error -> ErrorContent(
                    message = s.message,
                    onDismiss = onNavigateBack,
                )
            }
        }
    }
}

@Composable
private fun WaitingContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                "Waiting for agent to check in…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReadyForm(
    state: AuthorizeAgentState.Ready,
    onToggleScope: (String, Boolean) -> Unit,
    onApprovalModeChange: (String) -> Unit,
    onDurationChange: (Long) -> Unit,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val notif = state.notification
    val meta = notif.agentMetadata
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.SmartToy,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "Authorize this agent",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    meta?.agentType?.takeIf { it.isNotBlank() } ?: "Unknown agent",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Scroll-area for the variable-height content (identity card +
        // scope picker). Action row stays pinned at the bottom.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // Identity card — verify against what the agent operator
            // told you. Full fingerprints so the user can confirm
            // bit-for-bit out-of-band.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    InfoRow("Hostname", meta?.hostname?.ifEmpty { "—" } ?: "—")
                    if (meta?.osName?.isNotEmpty() == true) {
                        InfoRow("OS", "${meta.osName} ${meta.osVersion}".trim())
                    }
                    if (meta?.platform?.isNotEmpty() == true) InfoRow("Platform", meta.platform)
                    if (meta?.appVersion?.isNotEmpty() == true) InfoRow("App version", meta.appVersion)
                    if (meta?.binaryFingerprint?.isNotEmpty() == true) {
                        InfoRow("Binary fingerprint", meta.binaryFingerprint, mono = true)
                    }
                    if (meta?.machineFingerprint?.isNotEmpty() == true) {
                        InfoRow("Machine fingerprint", meta.machineFingerprint, mono = true)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Scope picker — the centerpiece. Defaults to the agent's
            // requested_scope, all toggled on; owner narrows from there.
            Text("Permissions", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))

            if (state.scopes.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                ) {
                    Text(
                        "Agent requested no specific permissions. You can still " +
                            "approve to allow connection — the agent will need to " +
                            "request a new session for any data access.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                state.scopes.forEach { toggle ->
                    val meta = scopeMeta(toggle.token)
                    ScopeRow(
                        meta = meta,
                        granted = toggle.granted,
                        onChange = { onToggleScope(toggle.token, it) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Approval mode radio — locked to always_ask unless the
            // agent requested auto_within_contract.
            Text("Approval mode", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            val autoAllowed = notif.requestedApprovalMode == "auto_within_contract"
            ApprovalModeRow(
                selected = state.approvalMode == "always_ask",
                label = "Ask me every time",
                description = "Phone prompts for each operation. Slower but safer.",
                onSelect = { onApprovalModeChange("always_ask") },
            )
            ApprovalModeRow(
                selected = state.approvalMode == "auto_within_contract",
                label = "Auto-approve within these permissions",
                description = if (autoAllowed) {
                    "Operations covered by the toggles above run without prompting."
                } else {
                    "Available only if the agent requests it."
                },
                enabled = autoAllowed,
                onSelect = { onApprovalModeChange("auto_within_contract") },
            )

            Spacer(Modifier.height(16.dp))

            Text("Session duration", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            DurationChips(
                seconds = state.durationSeconds,
                maxSeconds = notif.maxDurationSeconds,
                onChange = onDurationChange,
            )

            Spacer(Modifier.height(16.dp))
        }

        Row(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onDeny,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
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
private fun ScopeRow(
    meta: ScopeMeta,
    granted: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (meta.sensitive) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (meta.sensitive) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        meta.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Text(
                    meta.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = granted, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun ApprovalModeRow(
    selected: Boolean,
    label: String,
    description: String,
    enabled: Boolean = true,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = if (enabled) onSelect else null,
            enabled = enabled,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
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

// Durations the picker offers. Mirrors AuthorizeDeviceScreen's preset
// list but caps lower by default — agents shouldn't hold long-lived
// sessions; the vault enforces a 24h cap regardless of what we offer.
private val DURATION_PRESETS: List<Pair<String, Long>> = listOf(
    "1 hour" to 60L * 60L,
    "4 hours" to 4L * 60L * 60L,
    "8 hours" to 8L * 60L * 60L,
    "12 hours" to 12L * 60L * 60L,
    "1 day" to 24L * 60L * 60L,
)

@Composable
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

@Composable
private fun ProgressContent(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(message)
        }
    }
}

@Composable
private fun DoneContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text("Authorized", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ErrorContent(message: String, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDismiss) { Text("Close") }
        }
    }
}

@Suppress("unused")
private fun AgentPendingAuthNotification.hasMetadata(): Boolean = agentMetadata != null
