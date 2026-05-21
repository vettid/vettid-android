package com.vettid.app.features.sharing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.features.grants.CriticalUseRequestSheet
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
                    var useTarget by remember { mutableStateOf<SharedItem?>(null) }
                    var groupRequestTarget by remember { mutableStateOf<AliasGroup<SharedItem>?>(null) }
                    val criticalResult by viewModel.lastCriticalResult.collectAsState()
                    criticalResult?.let { res ->
                        AlertDialog(
                            onDismissRequest = { viewModel.dismissCriticalResult() },
                            title = { Text(if (res.status == "ok") "Operation result" else "Operation failed") },
                            text = {
                                Text(
                                    if (res.status == "ok") res.result
                                    else "Status: ${res.status}\n${res.error}"
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { viewModel.dismissCriticalResult() }) { Text("Close") }
                            },
                        )
                    }
                    Loaded(
                        state = s,
                        onRequest = { key ->
                            val item = s.items.firstOrNull { it.key == key } ?: return@Loaded
                            if (item.useOnly) {
                                // Cataloged-for-use rows branch to the
                                // use-on-behalf flow — never offer
                                // "give me a copy" for these.
                                useTarget = item
                            } else {
                                requestTarget = item
                            }
                        },
                        onRequestGroup = { group -> groupRequestTarget = group },
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
                    useTarget?.let { item ->
                        CriticalUseRequestSheet(
                            secretLabel = item.displayName,
                            onDismiss = { useTarget = null },
                            onSubmit = { op, payload, ctx ->
                                viewModel.onEvent(
                                    PeerCatalogEvent.RequestCriticalUse(
                                        key = item.key,
                                        operation = op,
                                        payloadBase64 = payload,
                                        context = ctx,
                                    )
                                )
                                useTarget = null
                            },
                        )
                    }
                    groupRequestTarget?.let { group ->
                        // "Request all" — one RequestAccessSheet for the
                        // whole alias group; on submit, fan out one
                        // grant.request per requestable member. The vault
                        // still tracks each individually; the grantor's
                        // approval screen regroups them by alias.
                        val members = group.items.filter {
                            it.status == RequestStatus.AVAILABLE && !it.useOnly
                        }
                        RequestAccessSheet(
                            itemLabel = "${group.label ?: group.key} (${members.size} item${if (members.size == 1) "" else "s"})",
                            onDismiss = { groupRequestTarget = null },
                            onSubmit = { mode, expiresAt, maxUses, reason ->
                                members.forEach { item ->
                                    viewModel.onEvent(
                                        PeerCatalogEvent.RequestGrant(
                                            key = item.key,
                                            mode = mode,
                                            expiresAt = expiresAt,
                                            maxUses = maxUses,
                                            reason = reason,
                                        )
                                    )
                                }
                                groupRequestTarget = null
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
    onRequestGroup: (AliasGroup<SharedItem>) -> Unit,
) {
    // Alias-card model: items the peer filed under one alias collapse
    // into a single card; ungrouped items are their own card, first.
    val groups = remember(state.items) {
        buildAliasGroups(state.items, aliasOf = { it.alias }, idOf = { it.key })
    }
    val singles = groups.filter { it.label == null }
    val aliasGroups = groups.filter { it.label != null }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            SharingSectionHeader(
                title = "What ${state.peerName} shares",
                subtitle = "Catalog items this connection has published. Tap Request to ask for any item.",
                count = state.items.size,
            )
        }
        if (state.items.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    EmptyHint("This connection hasn't published any catalog items yet.")
                }
            }
        }
        items(singles, key = { "single_${it.key}" }) { group ->
            val item = group.items.first()
            AliasCard {
                SharedItemRow(item = item, onRequest = { onRequest(item.key) })
            }
        }
        items(aliasGroups, key = { "group_${it.key}" }) { group ->
            val requestable = group.items.count {
                it.status == RequestStatus.AVAILABLE && !it.useOnly
            }
            AliasCard {
                AliasCardHeader(
                    label = group.label ?: group.key,
                    trailing = if (requestable > 0) {
                        {
                            FilledTonalButton(
                                onClick = { onRequestGroup(group) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text("Request all", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    } else null,
                )
                group.items.forEach { item ->
                    SharedItemRow(item = item, onRequest = { onRequest(item.key) })
                }
            }
        }
    }
}
