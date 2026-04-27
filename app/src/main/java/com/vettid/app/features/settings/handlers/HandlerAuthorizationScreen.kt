package com.vettid.app.features.settings.handlers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.nats.VaultHandler
import kotlinx.coroutines.flow.collectLatest

/**
 * User-facing screen for the handler classification + authorization
 * model. Each row is one capability the enclave declares; the user
 * controls whether it runs (Enabled) and whether it appears in their
 * public profile (Share publicly). Required system capabilities are
 * shown but locked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandlerAuthorizationScreen(
    viewModel: HandlerAuthorizationViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is HandlerAuthorizationEffect.ShowMessage -> snackbar.showSnackbar(effect.message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Capabilities") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.isLoading && state.handlers.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.handlers.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.error ?: "No capabilities reported",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                HandlerList(
                    handlers = state.handlers,
                    pendingHandlerId = state.pendingHandlerId,
                    onSetEnabled = viewModel::setEnabled,
                    onSetShare = viewModel::setShareGlobally,
                    contentPadding = padding,
                )
            }
        }
    }
}

@Composable
private fun HandlerList(
    handlers: List<VaultHandler>,
    pendingHandlerId: String?,
    onSetEnabled: (String, Boolean) -> Unit,
    onSetShare: (String, Boolean) -> Unit,
    contentPadding: PaddingValues,
) {
    val grouped = handlers.groupBy { it.category.ifEmpty { "default" } }
    val order = listOf("system", "default", "optional")
    // Default-collapsed sections; `default` expanded so the most-relevant
    // user toggles are visible without an extra tap.
    val expanded = remember {
        mutableStateMapOf<String, Boolean>().apply {
            put("system", false)
            put("default", true)
            put("optional", false)
        }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        item {
            Text(
                text = "Choose which vault capabilities run and which appear in your public profile. " +
                    "System capabilities power the vault and can't be disabled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        order.forEach { category ->
            val rows = grouped[category].orEmpty()
            if (rows.isEmpty()) return@forEach
            val isExpanded = expanded[category] ?: false
            item(key = "header_$category") {
                CategoryHeader(
                    category = category,
                    count = rows.size,
                    expanded = isExpanded,
                    onToggle = { expanded[category] = !isExpanded },
                )
            }
            if (isExpanded) {
                items(rows, key = { "row_${it.id}" }) { handler ->
                    HandlerRow(
                        handler = handler,
                        pending = pendingHandlerId == handler.id,
                        onSetEnabled = onSetEnabled,
                        onSetShare = onSetShare,
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp, end = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(
    category: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val label = when (category) {
        "system" -> "SYSTEM"
        "default" -> "DEFAULT"
        "optional" -> "OPTIONAL"
        else -> category.uppercase()
    }
    val sublabel = when (category) {
        "system" -> "Required for vault function"
        "default" -> "Enabled by default"
        "optional" -> "Opt-in"
        else -> ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$label  ($count)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            if (sublabel.isNotEmpty()) {
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HandlerRow(
    handler: VaultHandler,
    pending: Boolean,
    onSetEnabled: (String, Boolean) -> Unit,
    onSetShare: (String, Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = handler.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (handler.required) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Required",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (handler.shareable) {
                        Spacer(modifier = Modifier.width(8.dp))
                        ShareableBadge()
                    }
                }
                if (handler.description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = handler.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        // Toggle row: Enabled
        ToggleRow(
            label = "Enabled",
            secondary = if (handler.required) "Required for vault function" else null,
            checked = handler.enabled,
            enabled = !handler.required && !pending,
            onChange = { onSetEnabled(handler.id, it) },
        )
        if (handler.shareable) {
            Spacer(modifier = Modifier.height(6.dp))
            ToggleRow(
                label = "Share publicly",
                secondary = "Show this capability in your public profile",
                checked = handler.shareGlobally,
                enabled = handler.enabled && !pending,
                onChange = { onSetShare(handler.id, it) },
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    secondary: String?,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (secondary != null) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onChange else null,
            enabled = enabled,
            colors = SwitchDefaults.colors(),
        )
    }
}

@Composable
private fun ShareableBadge() {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = "Shareable",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
