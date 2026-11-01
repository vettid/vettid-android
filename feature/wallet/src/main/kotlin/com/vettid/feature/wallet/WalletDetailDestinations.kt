package com.vettid.feature.wallet

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.data.wallet.BackupRoute
import com.vettid.core.data.wallet.WalletOpenRoute
import com.vettid.core.ui.components.SecureClipboard

/** The wallet detail and what hangs off it: Receive, Chain server, Details. */
internal fun NavGraphBuilder.walletDetailDestinations(host: WalletHost) {
    composable<WalletOpenRoute> { entry -> DetailDestination(host) { host.leave(entry.id) } }
    composable<ReceiveRoute> { entry -> ReceiveDestination(host) { host.leave(entry.id) } }
    composable<ChainServerRoute> { entry -> ChainServerDestination { host.leave(entry.id) } }
    composable<WalletDetailsRoute> { entry -> DetailsDestination(host) { host.leave(entry.id) } }
}

@Composable
private fun DetailDestination(host: WalletHost, leave: () -> Unit) {
    val vm: WalletDetailViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    // Deleted, or the vault closed: nothing of the wallet is left to show.
    LaunchedEffect(state.closed) { if (state.closed) leave() }
    val id = vm.walletId
    WalletDetailScreen(
        state,
        WalletDetailActions(
            onBack = leave,
            onRefresh = vm::refresh,
            onReceive = { host.navigate(ReceiveRoute(id)) },
            onBackUp = { host.navigate(BackupRoute(id)) },
            onAcknowledge = vm::acknowledgeNote,
            onChooseNetwork = vm::chooseTestNetwork,
            onChainServer = { state.network?.let { host.navigate(ChainServerRoute(it.vaultName)) } },
            onAddressType = vm::setAddressType,
            onDetails = { host.navigate(WalletDetailsRoute(id)) },
            onAskDelete = vm::askDelete,
            onCancelDelete = vm::cancelDelete,
            onContinueDelete = vm::continueDelete,
            onPassword = vm::setPassword,
            onDelete = vm::delete,
            onDismissTypeError = vm::dismissTypeError,
        ),
    )
}

@Composable
private fun ReceiveDestination(host: WalletHost, leave: () -> Unit) {
    val vm: ReceiveViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.closed) { if (state.closed) leave() }
    val address = state.address?.address.orEmpty()
    ReceiveScreen(
        state,
        ReceiveActions(
            onBack = leave,
            onCopy = { copySensitive(context, context.getString(R.string.wallet_clip_label), address) },
            onShare = { share(context, address) },
            onAmount = vm::setAmount,
            onNewAddress = vm::newAddress,
            onBackUp = { host.navigate(BackupRoute(vm.walletId)) },
            onRetry = vm::load,
        ),
    )
}

@Composable
private fun ChainServerDestination(leave: () -> Unit) {
    val vm: ChainServerViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    ChainServerScreen(state, ChainServerActions(onBack = leave, onText = vm::setText, onSave = vm::save, onReset = vm::reset))
}

@Composable
private fun DetailsDestination(host: WalletHost, leave: () -> Unit) {
    val vm: WalletDetailsViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.closed) { if (state.closed) leave() }
    WalletDetailsScreen(
        state,
        WalletDetailsActions(
            onBack = leave,
            onCopy = { copySensitive(context, context.getString(R.string.wallet_menu_details), it) },
            onChainServer = { state.networkName?.let { host.navigate(ChainServerRoute(it)) } },
        ),
    )
}

/** Copy: on the clipboard marked sensitive ([com.vettid.core.ui.components.textClip]), cleared again after a minute. */
private fun copySensitive(context: Context, label: String, text: String) =
    SecureClipboard.copy(context, label, text, sensitive = true)

/** Share: the address as plain text, through the system's chooser. */
private fun share(context: Context, address: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, address)
    val chooser = Intent.createChooser(send, context.getString(R.string.wallet_share_title))
    context.startActivity(chooser)
}
