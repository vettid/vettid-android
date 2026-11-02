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
 * A wallet's Transactions (W2-3): the list. Transactions are memory only, so like [TransactionRoute] it is never saved with the back
 * stack (the drawer and a notification pop it, `popWalletSecrets`).
 */
@Serializable
data class TransactionsRoute(val walletId: String)

/**
 * One transaction of a wallet (W2-3; a `wallet.signed` notification and History's entry open it). `wallet.signed` carries the
 * `txid` only (its `ref`), so [walletId] is null there and the screen finds the wallet whose `wallet.history` has it. A txid is public
 * (it is on the chain), but the plan keeps transactions in memory only: never saved with the back stack, popped by
 * `popWalletSecrets`; the screen leaves when the vault closes. Registered by `:feature:wallet` only
 * where it is linked.
 */
@Serializable
data class TransactionRoute(val txid: String, val walletId: String? = null)

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

/**
 * Send from a wallet (W2-2). It holds the password the member types to approve, and a payment built and inspected: never saved with
 * the back stack (state saving would keep its view model alive behind another screen); the drawer and a notification pop it
 * (`navigateTopLevel`), as [NewWalletRoute].
 */
@Serializable
data class SendRoute(val walletId: String)
