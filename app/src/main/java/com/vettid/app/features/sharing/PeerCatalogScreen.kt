package com.vettid.app.features.sharing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.features.grants.RequestAccessSheet

/**
 * "Their catalog" — the peer's published items the local user can
 * request. Tap → sends capability.request through the vault.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerCatalogScreen(
    viewModel: PeerCatalogViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val s = state
                    Text(if (s is PeerCatalogState.Loaded) "${s.peerName}'s catalog" else "Their catalog")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                PeerCatalogState.Loading -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                }
                is PeerCatalogState.Error -> {
                    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                        Text(
                            text = s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                is PeerCatalogState.Loaded -> {
                    var requestTarget by remember { mutableStateOf<SharedItem?>(null) }
                    Loaded(
                        state = s,
                        onRequest = { key ->
                            requestTarget = s.items.firstOrNull { it.key == key }
                        },
                    )
                    requestTarget?.let { item ->
                        RequestAccessSheet(
                            itemLabel = item.displayName,
                            onDismiss = { requestTarget = null },
                            onSubmit = { mode, expiresAt, maxUses, reason ->
                                viewModel.onEvent(
                                    PeerCatalogEvent.RequestGrant(
                                        key = item.key,
                                        mode = mode,
                                        expiresAt = expiresAt,
                                        maxUses = maxUses,
                                        reason = reason,
                                    )
                                )
                                requestTarget = null
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Loaded(
    state: PeerCatalogState.Loaded,
    onRequest: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                title = "What ${state.peerName} shares",
                subtitle = "Catalog items this connection has published. Tap Request to ask for any item.",
                count = state.items.size,
            ) {
                if (state.items.isEmpty()) {
                    EmptyHint("This connection hasn't published any catalog items yet.")
                } else {
                    state.items.forEachIndexed { idx, item ->
                        if (idx > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        }
                        SharedItemRow(item = item, onRequest = { onRequest(item.key) })
                    }
                }
            }
        }
    }
}
