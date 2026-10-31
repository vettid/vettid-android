package com.vettid.app.nowallet

import com.vettid.core.data.wallet.NoWalletAccess
import com.vettid.core.data.wallet.WalletAccess
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * A build without the wallet (every production build until the wallet is promoted): no Wallet entry, and nothing of wallets is
 * asked of the vault. The builds with the wallet have `src/wallet` (`com.vettid.app.wallet`, which a release APK must not contain).
 */
@Module
@InstallIn(SingletonComponent::class)
object NoWalletModule {
    @Provides
    fun walletAccess(): WalletAccess = NoWalletAccess
}
