package com.vettid.app.walletui

import androidx.navigation.NavGraphBuilder
import com.vettid.core.ui.components.ShellChrome

/** A build without the wallet: no wallet screen (its drawer entry is never shown, `NoWalletAccess`). */
@Suppress("UnusedParameter", "UNUSED_PARAMETER")
fun NavGraphBuilder.walletDestinations(chrome: ShellChrome, navigate: (Any) -> Unit, onBack: () -> Unit) = Unit
