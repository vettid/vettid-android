package com.vettid.app.walletui

import androidx.navigation.NavGraphBuilder
import com.vettid.core.ui.components.ShellChrome
import com.vettid.feature.wallet.WalletHost
import com.vettid.feature.wallet.walletDestination

/** The wallet's screens in the builds that have the wallet (`app/src/noWallet` has none). */
fun NavGraphBuilder.walletDestinations(chrome: ShellChrome, navigate: (Any) -> Unit, onBack: () -> Unit) =
    walletDestination(chrome, WalletHost(navigate = navigate, onBack = onBack))
