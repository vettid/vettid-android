package com.vettid.app.wallet

import android.content.Context
import com.vettid.app.BuildConfig
import com.vettid.core.chain.ChainDefaults
import com.vettid.core.chain.ChainServers
import com.vettid.core.chain.ChainSettingsFile
import com.vettid.core.data.KeystoreFileStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

/**
 * The wallet in the builds that link it (debug, devStack, staging; ANDROID-PLAN §4 "Wallet"). Production builds have none of this
 * (`:app:check<Variant>Wallet`).
 */
@Module
@InstallIn(SingletonComponent::class)
object WalletModule {
    /** The chain server per network: an encrypted file in no-backup storage, which a wipe erases with the rest. */
    const val CHAIN_SERVERS_FILE = "chain-servers.bin"
    internal const val CHAIN_SERVERS_PURPOSE = "chain-servers"

    /** The chain server setting (ANDROID-PLAN §4 "The chain source"): on this phone only, never sent to the vault. */
    @Provides
    @Singleton
    fun chainServers(@ApplicationContext context: Context, http: OkHttpClient): ChainServers = ChainServers(
        settingsFile(KeystoreFileStore(chainServersFile(context), CHAIN_SERVERS_PURPOSE)),
        ChainDefaults.forBuild(BuildConfig.WALLET_REGTEST_ESPLORA),
        http,
    )

    internal fun chainServersFile(context: Context): File = File(context.noBackupFilesDir, CHAIN_SERVERS_FILE)

    internal fun settingsFile(store: KeystoreFileStore): ChainSettingsFile = object : ChainSettingsFile {
        override fun load(): ByteArray? = store.load()

        override fun save(bytes: ByteArray) = store.save(bytes)
    }
}
