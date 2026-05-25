package com.vettid.app.features.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Approval screen for an agent-initiated LEASH mint request.
 *
 * Mirrors AuthorizeAgentScreen's shape: identity card, scope toggles,
 * duration picker, Approve / Deny. Distinct from AuthorizeAgent
 * because the vocabulary (LEASH grammar) and the downstream action
 * (mint a JWT, deliver to the agent) differ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeashApprovalScreen(
    requestId: String,
    onNavigateBack: () -> Unit,
    viewModel: LeashApprovalViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(requestId) { viewModel.bindRequest(requestId) }

    LaunchedEffect(state) {
        if (state is LeashApprovalState.Done) onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LEASH approval") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (val s = state) {
                LeashApprovalState.Waiting -> WaitingContent()
                is LeashApprovalState.Ready -> ReadyForm(
                    state = s,
                    onToggleScope = viewModel::toggleScope,
                    onDurationChange = viewModel::setDuration,
                    onApprove = viewModel::approve,
                    onDeny = viewModel::deny,
                )
                LeashApprovalState.Submitting -> SubmittingContent()
                LeashApprovalState.Done -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { Text("Done") }
                is LeashApprovalState.Error -> ErrorContent(s.message, onDismiss = onNavigateBack)
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
            Text("Waiting for request details…")
        }
    }
}

@Composable
private fun SubmittingContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Submitting…")
        }
    }
}

@Composable
private fun ErrorContent(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
private fun ReadyForm(
    state: LeashApprovalState.Ready,
    onToggleScope: (String, Boolean) -> Unit,
    onDurationChange: (Long) -> Unit,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        // Identity card
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(state.notification.agentName, fontWeight = FontWeight.SemiBold)
                    if (state.notification.agentType.isNotBlank()) {
                        Text(
                            state.notification.agentType,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "is requesting a LEASH bound to its key:",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            state.notification.agentPubkey,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (state.notification.reason.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Reason", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(state.notification.reason, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Scopes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        if (state.scopes.isEmpty()) {
            Text(
                "Agent requested no scopes — denying.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            state.scopes.forEach { toggle ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(toggle.token, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = toggle.granted, onCheckedChange = { onToggleScope(toggle.token, it) })
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Duration", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        DurationChipsLeash(seconds = state.durationSeconds, onChange = onDurationChange)

        Spacer(Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
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
                enabled = state.scopes.any { it.granted },
            ) { Text("Approve") }
        }
    }
}

@Composable
private fun DurationChipsLeash(seconds: Long, onChange: (Long) -> Unit) {
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
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}
