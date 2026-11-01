package com.vettid.feature.wallet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.data.wallet.WalletRoute
import com.vettid.core.ui.components.ShellChrome
import kotlinx.serialization.Serializable

/** Make a wallet and back it up. */
@Serializable
data object NewWalletRoute

/** Back up a wallet that is not backed up yet (from its row). */
@Serializable
data class BackupRoute(val walletId: String)

/** What the wallet's screens ask of the app's navigation. */
data class WalletHost(val navigate: (Any) -> Unit, val onBack: () -> Unit)

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
                onOpen = { row -> if (!row.backedUp) host.navigate(BackupRoute(row.id)) },
                onRetry = vm::refresh,
            ),
        )
    }
    composable<NewWalletRoute> { BackupDestination(host) }
    composable<BackupRoute> { BackupDestination(host) }
}

@Composable
private fun BackupDestination(host: WalletHost) {
    val vm: BackupViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    // The vault closed during the flow: its words are gone, and the flow leaves.
    LaunchedEffect(state.closed) { if (state.closed) host.onBack() }
    BackupScreen(
        state,
        BackupActions(
            onBack = host.onBack,
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
