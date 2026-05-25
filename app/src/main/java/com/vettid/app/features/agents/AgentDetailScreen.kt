package com.vettid.app.features.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

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

            is AgentDetailState.Loaded -> LoadedBody(
                s = s,
                pad = pad,
                onToggle = { id, on -> viewModel.onEvent(AgentDetailEvent.ToggleVisibility(id, on)) },
                onShowAll = { viewModel.onEvent(AgentDetailEvent.ShowAll) },
                onHideAll = { viewModel.onEvent(AgentDetailEvent.HideAll) },
            )
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
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
    ) {
        item { Spacer(Modifier.height(12.dp)) }
        item { AgentHeader(s.agent) }
        item { Spacer(Modifier.height(16.dp)) }
        item { CapabilitiesSection(s.agent.scope) }
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
