package com.vettid.feature.wallet

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.data.wallet.BackupRoute
import com.vettid.core.data.wallet.SendRoute
import com.vettid.core.data.wallet.SpendingLimitsRoute
import com.vettid.core.data.wallet.WalletOpenRoute
import com.vettid.core.ui.components.SecureClipboard

/** The wallet detail and what hangs off it: Receive, Chain server, Details. */
internal fun NavGraphBuilder.walletDetailDestinations(host: WalletHost) {
    composable<WalletOpenRoute> { entry -> DetailDestination(host) { host.leave(entry.id) } }
    composable<ReceiveRoute> { entry -> ReceiveDestination(host) { host.leave(entry.id) } }
    composable<SendRoute> { entry -> SendDestination(host) { host.leave(entry.id) } }
    composable<ChainServerRoute> { entry -> ChainServerDestination { host.leave(entry.id) } }
    composable<WalletDetailsRoute> { entry -> DetailsDestination(host) { host.leave(entry.id) } }
    composable<SpendingLimitsRoute> { entry -> LimitsDestination { host.leave(entry.id) } }
}

@Composable
private fun LimitsDestination(leave: () -> Unit) {
    val vm: SpendingLimitsViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.closed) { if (state.closed) leave() }
    SpendingLimitsScreen(
        state,
        SpendingLimitsActions(
            onBack = leave,
            onLower = vm::openLower,
            onRaise = vm::openRaise,
            onPerTx = vm::setPerTx,
            onPerPeriod = vm::setPerPeriod,
            onRemoveTx = vm::setRemoveTx,
            onRemovePeriod = vm::setRemovePeriod,
            onToggleUnit = vm::toggleUnit,
            onNext = vm::next,
            onConfirmSet = vm::confirmSet,
            onConfirmRaise = vm::confirmRaise,
            onPin = vm::setPin,
            onPassword = vm::setPassword,
            onDismissError = vm::dismissError,
            onStepBack = vm::back,
        ),
    )
}

@Composable
private fun DetailDestination(host: WalletHost, leave: () -> Unit) {
    val vm: WalletDetailViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    // Deleted, or the vault closed: nothing of the wallet is left to show.
    LaunchedEffect(state.closed) { if (state.closed) leave() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.resumed() }
    val id = vm.walletId
    WalletDetailScreen(
        state,
        WalletDetailActions(
            onBack = leave,
            onRefresh = vm::refresh,
            onReceive = { host.navigate(ReceiveRoute(id)) },
            onSend = { host.navigate(SendRoute(id)) },
            onBackUp = { host.navigate(BackupRoute(id)) },
            onRetryCaps = vm::retryCaps,
            onAcknowledge = vm::acknowledgeNote,
            onChooseNetwork = vm::chooseTestNetwork,
            onChainServer = { state.network?.let { host.navigate(ChainServerRoute(it.vaultName)) } },
            onAddressType = vm::setAddressType,
            onDetails = { host.navigate(WalletDetailsRoute(id)) },
            onLimits = { host.navigate(SpendingLimitsRoute(id)) },
            onAskReset = vm::askReset,
            onCancelReset = vm::cancelReset,
            onReset = vm::resetSettings,
            onAskDelete = vm::askDelete,
            onCancelDelete = vm::cancelDelete,
            onContinueDelete = vm::continueDelete,
            onPassword = vm::setPassword,
            onDelete = vm::delete,
            onDismissTypeError = vm::dismissTypeError,
            onChangeNetwork = vm::changeNetwork,
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
    val shareText = state.shareText
    val clipLabel = stringResource(R.string.wallet_clip_label)
    val shareTitle = stringResource(R.string.wallet_share_title)
    ReceiveScreen(
        state,
        ReceiveActions(
            onBack = leave,
            // A receive address is not a secret (the owner's decision 2a): hidden from previews, but left for the member to paste.
            onCopy = { SecureClipboard.copyHidden(context, clipLabel, address) },
            onShare = { shareText?.let { share(context, it, shareTitle) } },
            onAmount = vm::setAmount,
            onNewAddress = vm::newAddress,
            onBackUp = { host.navigate(BackupRoute(vm.walletId)) },
            onRetry = vm::load,
            onChooseNetwork = vm::chooseTestNetwork,
            onLimitsContinue = vm::answerLimits,
            limits = LimitsActions(vm::setLimitChoice, vm::setPerTx, vm::setPerPeriod, vm::toggleUnit),
            onAskReset = vm::askReset,
            onCancelReset = vm::cancelReset,
            onReset = vm::resetSettings,
        ),
    )
}

@Composable
private fun SendDestination(host: WalletHost, leave: () -> Unit) {
    val vm: SendViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.closed) { if (state.closed) leave() }
    // Back from the review is back to the form, not out of the screen (nothing was signed).
    BackHandler(enabled = state.step == SendStep.REVIEW && !state.swallowsBack && !state.scanning) { vm.edit() }
    BackHandler(enabled = state.scanning) { vm.stopScan() }
    // While a request is out (approving, trying again, asking the server) back does nothing: leaving would drop the result of a
    // signing that goes on (`approveSend` is not cancelled), and the member would never see how it ended. Declared last, it wins.
    BackHandler(enabled = state.swallowsBack) {}
    SendScreen(
        state,
        SendActions(
            onBack = { if (!state.swallowsBack) leave() },
            onLoad = vm::load,
            onBackUp = { host.navigate(BackupRoute(vm.walletId)) },
            onChooseNetwork = vm::chooseTestNetwork,
            onAddress = vm::setAddress,
            onScan = vm::startScan,
            onStopScan = vm::stopScan,
            onScanned = vm::scanned,
            onScanUnavailable = vm::scanUnavailable,
            onAmount = vm::setAmount,
            onUnit = vm::setUnit,
            onSendAll = vm::setSendAll,
            onFee = vm::setFeeChoice,
            onCustomFee = vm::setCustomFee,
            onUnconfirmed = vm::setIncludeUnconfirmed,
            onRetryFees = vm::retryFees,
            onReview = vm::review,
            onConfirmBigFee = vm::confirmBigFee,
            onCancelBigFee = vm::cancelBigFee,
            onEdit = vm::edit,
            onPassword = vm::setPassword,
            onApprove = vm::approve,
            onRetry = vm::retry,
            onChainServer = { state.network?.let { host.navigate(ChainServerRoute(it.vaultName)) } },
            onLimits = { host.navigate(SpendingLimitsRoute(vm.walletId)) },
            onAskDiscard = vm::askDiscard,
            onCancelDiscard = vm::cancelDiscard,
            onDiscard = vm::discard,
            onStartOver = vm::startOver,
            onDone = leave,
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
    val detailsLabel = stringResource(R.string.wallet_menu_details)
    LaunchedEffect(state.closed) { if (state.closed) leave() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.resumed() }
    WalletDetailsScreen(
        state,
        WalletDetailsActions(
            onBack = leave,
            onCopy = { copySensitive(context, detailsLabel, it) },
            onChainServer = { state.networkName?.let { host.navigate(ChainServerRoute(it)) } },
        ),
    )
}

/** Copy: on the clipboard marked sensitive ([com.vettid.core.ui.components.textClip]), cleared again after a minute. */
private fun copySensitive(context: Context, label: String, text: String) =
    SecureClipboard.copy(context, label, text, sensitive = true)

/** Share: [text] ([ReceiveUiState.shareText]) as plain text, through the system's chooser. */
private fun share(context: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    val chooser = Intent.createChooser(send, title)
    context.startActivity(chooser)
}
