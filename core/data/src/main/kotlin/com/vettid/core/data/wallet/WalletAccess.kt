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
    /**
     * Whether the drawer's Wallet entry is shown: while the vault is open, unless it answered `wallet.list` with `not_supported`. A
     * read that failed is no reason to hide it: the list shows the failure and Try again.
     */
    val shown: StateFlow<Boolean>

    /**
     * The open vault's wallets, by id and name (empty while closed): the Credential screen lists each, as the critical item it is
     * ("Bitcoin wallet: <name>", ANDROID-PLAN §4 "The wallet's item elsewhere").
     */
    val wallets: StateFlow<List<WalletLabel>>
}

/** A wallet as other screens name it: its id and the name the member gave it. Prints its type name only. */
data class WalletLabel(val id: String, val name: String) {
    override fun toString(): String = "WalletLabel"
}

/** A build without the wallet: no Wallet entry, nothing of wallets asked of the vault. */
object NoWalletAccess : WalletAccess {
    override val shown: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    override val wallets: StateFlow<List<WalletLabel>> = MutableStateFlow(emptyList<WalletLabel>()).asStateFlow()
}
