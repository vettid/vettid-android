package com.vettid.feature.wallet

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.data.wallet.BackupRoute
import com.vettid.core.data.wallet.ImportWalletRoute
import com.vettid.core.data.wallet.NewWalletRoute
import com.vettid.core.data.wallet.WalletOpenRoute
import com.vettid.core.data.wallet.WalletRoute
import com.vettid.core.ui.components.ShellChrome

/**
 * What the wallet's screens ask of the app's navigation: open a route, and leave the screen of one back stack entry (by its id: a
 * second Back while the screen animates out does nothing, instead of popping the screen below it).
 */
data class WalletHost(val navigate: (Any) -> Unit, val leave: (entryId: String) -> Unit)

/** The wallet's screens (ANDROID-PLAN §4 "Wallet"): the list ([WalletRoute], the drawer entry), new wallet, back up. */
fun NavGraphBuilder.walletDestination(chrome: ShellChrome, host: WalletHost) {
    composable<WalletRoute> {
        val vm: WalletsViewModel = hiltViewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { vm.refresh() }
        WalletsScreen(
            state,
            chrome,
            WalletsActions(
                onNew = { host.navigate(NewWalletRoute) },
                onImport = { host.navigate(ImportWalletRoute) },
                onOpen = { row -> host.navigate(WalletOpenRoute(row.id)) },
                onRetry = vm::refresh,
            ),
        )
    }
    walletDetailDestinations(host)
    composable<NewWalletRoute> { entry -> BackupDestination(host, entry.id) }
    composable<BackupRoute> { entry -> BackupDestination(host, entry.id) }
    composable<ImportWalletRoute> { entry -> ImportDestination(host, entry.id) }
}

@Composable
private fun ImportDestination(host: WalletHost, entryId: String) {
    val leave = { host.leave(entryId) }
    val vm: ImportViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    // The vault closed during the flow: its words are gone, and the flow leaves.
    LaunchedEffect(state.closed) { if (state.closed) leave() }
    // Imported with its limit set (or none chosen): on to the wallet, where its history is looked for.
    LaunchedEffect(state.importedId, state.capsFailed) {
        val id = state.importedId
        if (id != null && !state.capsFailed) {
            leave()
            host.navigate(WalletOpenRoute(id))
        }
    }
    val inFlow = state.step == ImportStep.LIMITS || state.step == ImportStep.PASSWORD
    BackHandler(enabled = inFlow) { vm.previous() }
    ImportScreen(
        state,
        ImportActions(
            onBack = if (inFlow) vm::previous else leave,
            onName = vm::setName,
            onNetwork = vm::setNetwork,
            onWords = vm::setWords,
            onAdvanced = vm::toggleAdvanced,
            onPassphrase = vm::setPassphrase,
            onContinue = if (state.step == ImportStep.LIMITS) vm::toPassword else vm::toLimits,
            onPassword = vm::setPassword,
            onImport = vm::import,
            onChoice = vm::setLimitChoice,
            onPerTx = vm::setPerTx,
            onPerPeriod = vm::setPerPeriod,
            onToggleUnit = vm::toggleUnit,
            onRetryCaps = vm::retryCaps,
            onOpen = {
                leave()
                state.importedId?.let { host.navigate(WalletOpenRoute(it)) }
            },
        ),
    )
}

@Composable
private fun BackupDestination(host: WalletHost, entryId: String) {
    val leave = { host.leave(entryId) }
    val vm: BackupViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    // The vault closed during the flow: its words are gone, and the flow leaves.
    LaunchedEffect(state.closed) { if (state.closed) leave() }
    // Inside making a wallet (the limits, the password) Back is one step back, not out of the flow.
    val inFlow = state.step == BackupStep.LIMITS || state.step == BackupStep.CREATE
    BackHandler(enabled = inFlow) { vm.previous() }
    BackupScreen(
        state,
        BackupActions(
            onBack = if (inFlow) vm::previous else leave,
            onName = vm::setName,
            onNetwork = vm::setNetwork,
            onPassword = vm::setPassword,
            onPin = vm::setPin,
            onNext = if (state.step == BackupStep.LIMITS) vm::toPassword else vm::toLimits,
            onPrevious = vm::previous,
            onChoice = vm::setLimitChoice,
            onPerTx = vm::setPerTx,
            onPerPeriod = vm::setPerPeriod,
            onToggleUnit = vm::toggleUnit,
            onRetryCaps = vm::retryCaps,
            onCreate = vm::create,
            onReveal = vm::reveal,
            onContinue = vm::startCheck,
            onTyped = vm::setTyped,
            onAnswer = vm::answer,
            onPick = vm::pick,
            onShowAgain = vm::showAgain,
            onSaveAgain = vm::saveAgain,
        ),
    )
}
