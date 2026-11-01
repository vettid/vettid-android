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
