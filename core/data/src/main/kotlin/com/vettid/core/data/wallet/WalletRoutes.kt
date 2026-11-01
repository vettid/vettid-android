package com.vettid.core.data.wallet

import kotlinx.serialization.Serializable

/**
 * The wallet's routes that the main app names (the drawer entry, a wallet opened from elsewhere). They are here, beside
 * [WalletAccess], because the screens are not in every build: `:feature:wallet` registers them where the wallet is linked
 * (`walletDestinations()`, `app/src/wallet`); a build without it never navigates to them (the entry is hidden).
 */
@Serializable
data object WalletRoute

/** One wallet, opened from elsewhere (the Credential screen's row). */
@Serializable
data class WalletOpenRoute(val walletId: String)

/** One wallet's Spending limits (W1d-2; a notification about a limit opens it). Registered by `:feature:wallet` only where it is linked. */
@Serializable
data class SpendingLimitsRoute(val walletId: String)

/**
 * Make a wallet and back it up. This and [BackupRoute] hold a recovery phrase: never saved with the back stack (state saving would
 * keep their view model, and with it the words, alive behind another screen); the drawer and a notification pop them
 * (`navigateTopLevel`), as the pairing.
 */
@Serializable
data object NewWalletRoute

/**
 * Import a wallet from its recovery phrase (W1d). It holds the words the member types: never saved with the back stack, as
 * [NewWalletRoute] (the drawer and a notification pop it).
 */
@Serializable
data object ImportWalletRoute

/** Back up a wallet that is not backed up yet (from its row). */
@Serializable
data class BackupRoute(val walletId: String)
