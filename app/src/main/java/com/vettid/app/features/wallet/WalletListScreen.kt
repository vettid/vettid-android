package com.vettid.app.features.wallet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.collectLatest

/**
 * Embedded wallet list content for the Wallets tab.
 *
 * Follows the same pattern as SecretsContent and ConnectionsContentEmbedded:
 * no Scaffold, receives search query from parent, uses internal FAB.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletListContentEmbedded(
    viewModel: WalletViewModel = hiltViewModel(),
    searchQuery: String = "",
    onWalletClick: (String) -> Unit = {},
    onCreateWallet: () -> Unit = {}
) {
    val wallets by viewModel.wallets.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showCreateSheet by remember { mutableStateOf(false) }

    // Reload on resume so the public indicator (and balances) pick
    // up changes made from the wallet detail screen without
    // requiring a pull-to-refresh. WalletDetailViewModel updates its
    // own state on visibility toggle but can't reach into this
    // list's ViewModel, so we refresh when returning here.
    val walletLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(walletLifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.loadWallets()
            }
        }
        walletLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { walletLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is WalletEffect.ShowError -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is WalletEffect.ShowSuccess -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is WalletEffect.WalletCreated -> {
                    showCreateSheet = false
                    snackbarHostState.showSnackbar("Wallet created")
                }
            }
        }
    }

    // Filter wallets by search query
    val filteredWallets = remember(wallets, searchQuery) {
        if (searchQuery.isBlank()) wallets
        else wallets.filter { wallet ->
            wallet.label.contains(searchQuery, ignoreCase = true) ||
                wallet.address.contains(searchQuery, ignoreCase = true)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            wallets.isEmpty() -> {
                EmptyWalletsContent(onCreateClick = { showCreateSheet = true })
            }

            else -> {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.loadWallets() },
                    modifier = Modifier.fillMaxSize()
                ) {
                    WalletList(
                        wallets = filteredWallets,
                        onWalletClick = onWalletClick,
                        onRefreshBalance = { viewModel.refreshBalance(it) }
                    )
                }
            }
        }

        // FAB
        if (!isLoading) {
            FloatingActionButton(
                onClick = { showCreateSheet = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create wallet")
            }
        }

        // Snackbar host
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp)
        )
    }

    // Create wallet bottom sheet
    if (showCreateSheet) {
        CreateWalletSheet(
            onDismiss = { showCreateSheet = false },
            onCreateWallet = { label, network, password ->
                viewModel.createWallet(label, network, password)
            }
        )
    }
}

@Composable
private fun WalletList(
    wallets: List<WalletInfo>,
    onWalletClick: (String) -> Unit,
    onRefreshBalance: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = wallets,
            key = { it.walletId }
        ) { wallet ->
            WalletCard(
                wallet = wallet,
                onClick = { onWalletClick(wallet.walletId) },
                onRefresh = { onRefreshBalance(wallet.walletId) }
            )
        }
    }
}

@Composable
private fun WalletCard(
    wallet: WalletInfo,
    onClick: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header: Label + Network badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = wallet.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.width(8.dp))

                NetworkBadge(network = wallet.network)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Truncated address
            Text(
                text = truncateAddress(wallet.address),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Balance row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatBtcBalance(wallet.cachedBalanceSats),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh balance",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Public indicator
            if (wallet.isPublic) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Public,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Public",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkBadge(network: String) {
    val (color, label) = when (network.lowercase()) {
        "testnet" -> MaterialTheme.colorScheme.tertiary to "Testnet"
        "signet" -> MaterialTheme.colorScheme.secondary to "Signet"
        else -> MaterialTheme.colorScheme.primary to "Mainnet"
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun EmptyWalletsContent(
    onCreateClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.AccountBalanceWallet,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "No wallets yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Create your first Bitcoin wallet to start sending and receiving payments securely through your vault.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onCreateClick) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Create Wallet")
            }
        }
    }
}

// MARK: - Utility Functions

/**
 * Format satoshis as BTC string: "0.00123456 BTC"
 */
fun formatBtcBalance(sats: Long): String {
    if (sats == 0L) return "0 BTC"
    val btc = sats.toDouble() / 100_000_000.0
    val formatted = "%.8f".format(btc).trimEnd('0').let {
        if (it.endsWith('.')) "${it}0" else it
    }
    return "$formatted BTC"
}

/**
 * Truncate a Bitcoin address for display: "bc1q...x4f8"
 */
fun truncateAddress(address: String): String {
    return if (address.length <= 16) address
    else "${address.take(8)}...${address.takeLast(6)}"
}
