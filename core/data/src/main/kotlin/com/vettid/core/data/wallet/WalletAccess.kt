package com.vettid.core.data.wallet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * All that every build knows of the wallet (ANDROID-PLAN §4 "Wallet"), as `CallAccess` is for calls: the builds that have the wallet
 * bind it to `:core:wallet` (`app/src/wallet`), every other build to [NoWalletAccess] (`app/src/noWallet`), so that no code that
 * ships in release reaches the wallet's vault requests (`walletMarkers`, app/build.gradle.kts).
 */
interface WalletAccess {
    /** Whether the open vault has wallets (it answered `wallet.list`): the drawer's Wallet entry is shown only then. */
    val shown: StateFlow<Boolean>
}

/** A build without the wallet: no Wallet entry, nothing of wallets asked of the vault. */
object NoWalletAccess : WalletAccess {
    override val shown: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
}
