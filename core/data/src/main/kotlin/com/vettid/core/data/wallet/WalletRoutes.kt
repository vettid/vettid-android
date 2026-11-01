package com.vettid.core.data.wallet

import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

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

/** Make a wallet and back it up. */
@Serializable
data object NewWalletRoute

/** Back up a wallet that is not backed up yet (from its row). */
@Serializable
data class BackupRoute(val walletId: String)

/**
 * The routes whose screens hold a recovery phrase: never saved with the back stack (state saving would keep their view model, and
 * with it the words, alive behind another screen). The drawer and a notification pop them (`navigateTopLevel`), as the pairing.
 */
val WalletSecretRoutes: List<KClass<*>> = listOf(NewWalletRoute::class, BackupRoute::class)
