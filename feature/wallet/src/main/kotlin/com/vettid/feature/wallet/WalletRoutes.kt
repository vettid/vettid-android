package com.vettid.feature.wallet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.data.wallet.BackupRoute
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
                onOpen = { row -> host.navigate(WalletOpenRoute(row.id)) },
                onRetry = vm::refresh,
            ),
        )
    }
    walletDetailDestinations(host)
    composable<NewWalletRoute> { entry -> BackupDestination(host, entry.id) }
    composable<BackupRoute> { entry -> BackupDestination(host, entry.id) }
}

@Composable
private fun BackupDestination(host: WalletHost, entryId: String) {
    val leave = { host.leave(entryId) }
    val vm: BackupViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    // The vault closed during the flow: its words are gone, and the flow leaves.
    LaunchedEffect(state.closed) { if (state.closed) leave() }
    BackupScreen(
        state,
        BackupActions(
            onBack = leave,
            onName = vm::setName,
            onNetwork = vm::setNetwork,
            onPassword = vm::setPassword,
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
