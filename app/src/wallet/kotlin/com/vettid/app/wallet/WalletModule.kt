package com.vettid.app.wallet

import android.content.Context
import com.vettid.app.BuildConfig
import com.vettid.app.di.AppScope
import com.vettid.core.chain.ChainDefaults
import com.vettid.core.chain.ChainServers
import com.vettid.core.chain.ChainSettingsFile
import com.vettid.core.data.KeystoreFileStore
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.VaultManager
import com.vettid.core.data.wallet.WalletAccess
import com.vettid.core.data.wallet.WalletLabel
import com.vettid.core.data.wipe.WipeHook
import com.vettid.core.wallet.ServersChainSource
import com.vettid.core.wallet.VaultWalletOps
import com.vettid.core.wallet.WalletAvailability
import com.vettid.core.wallet.WalletFlags
import com.vettid.core.wallet.WalletFlagsFile
import com.vettid.core.wallet.WalletWipe
import com.vettid.core.wallet.WalletsManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

/**
 * The wallet in the builds that link it (debug, devStack, staging; ANDROID-PLAN §4 "Wallet"). Production builds have `src/noWallet`
 * instead, and none of this (`:app:check<Variant>Wallet`, `walletMarkers`).
 */
@Module
@InstallIn(SingletonComponent::class)
object WalletModule {
    /** The chain server per network: an encrypted file in no-backup storage, which a wipe erases with the rest. */
    const val CHAIN_SERVERS_FILE = "chain-servers.bin"
    internal const val CHAIN_SERVERS_PURPOSE = "chain-servers"

    /** The backed-up flags and the legacy testnet labels: the same. */
    const val WALLET_FLAGS_FILE = "wallet-flags.bin"
    internal const val WALLET_FLAGS_PURPOSE = "wallet-flags"

    @Provides
    @Singleton
    fun walletWipe(): WalletWipe = WalletWipe()

    /** The chain server setting (ANDROID-PLAN §4 "The chain source"): on this phone only, never sent to the vault. */
    @Provides
    @Singleton
    fun chainServers(@ApplicationContext context: Context, http: OkHttpClient, wipe: WalletWipe): ChainServers = ChainServers(
        settingsFile(KeystoreFileStore(chainServersFile(context), CHAIN_SERVERS_PURPOSE)),
        chainDefaults(),
        http,
        generation = { wipe.generation },
    )

    /** The defaults; a regtest server only in devStack (the branch is compiled away elsewhere: no loopback host in any other APK). */
    internal fun chainDefaults(): ChainDefaults =
        if (BuildConfig.WALLET_REGTEST) ChainDefaults.forBuild(BuildConfig.WALLET_REGTEST_ESPLORA) else ChainDefaults()

    @Provides
    @Singleton
    fun walletFlags(@ApplicationContext context: Context, wipe: WalletWipe): WalletFlags =
        WalletFlags(flagsFile(KeystoreFileStore(walletFlagsFile(context), WALLET_FLAGS_PURPOSE))) { wipe.generation }

    /** The wallets, in memory while the vault is open and not held; each server's block 0 checked again after every lock. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides
    @Singleton
    fun walletsManager(
        manager: VaultManager,
        servers: ChainServers,
        flags: WalletFlags,
        wipe: WalletWipe,
        @AppScope scope: CoroutineScope,
    ): WalletsManager = WalletsManager(
        scope = scope,
        ops = { VaultWalletOps { manager.vaultApi() } },
        chain = ServersChainSource(servers),
        flags = flags,
        offered = servers.defaults::offered,
    ).also { m ->
        wipe.register(m::clear)
        // The member's own lock, before it reaches the vault; every other way the vault closes is the `open` flow below.
        manager.onBeforeLock {
            m.clear()
            servers.forgetChecks()
        }
        val open = combine(manager.phase, manager.ownerCheck.ownerCheck, ::openOf)
        val events = manager.vaultEvents.filterNotNull().flatMapLatest { it.events }.map { it.type to it.body }
        m.follow(open, events, onClosed = { servers.forgetChecks() })
    }

    @Provides
    @Singleton
    fun walletAccess(m: WalletsManager, @AppScope scope: CoroutineScope): WalletAccess = object : WalletAccess {
        override val shown: StateFlow<Boolean> = combine(m.isOpen, m.availability, ::shownOf).stateIn(scope, SharingStarted.Eagerly, false)

        override val wallets: StateFlow<List<WalletLabel>> =
            m.wallets.map { list -> list.map { WalletLabel(it.id, it.name) } }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    /**
     * The drawer's Wallet entry: while the vault is open, unless it answered that it has no wallets. A read that failed at the unlock
     * leaves the entry there, and the list's Try again reads it again (nothing fails silently).
     */
    internal fun shownOf(open: Boolean, availability: WalletAvailability): Boolean = open && availability != WalletAvailability.UNSUPPORTED

    /** Whether the wallets may be held: the vault unlocked and not held or due (§3.6.3: nothing of them is kept or shown then). */
    internal fun openOf(phase: AppPhase, check: OwnerCheckView?): Boolean =
        phase == AppPhase.Unlocked && (check == null || check.state == OwnerCheckState.OK)

    /**
     * At a live wipe, before anything is erased: the generation moves and the wallets are forgotten; then the writes already in
     * progress finish (a write that started before the generation moved is the only one that could still land), and every
     * server's block 0 is checked again.
     */
    @Provides
    @IntoSet
    fun walletWipeHook(wipe: WalletWipe, flags: WalletFlags, servers: ChainServers): WipeHook = WipeHook {
        wipeHook(wipe, flags, servers)
    }

    internal suspend fun wipeHook(wipe: WalletWipe, flags: WalletFlags, servers: ChainServers) {
        try {
            wipe.onWipe()
        } finally {
            flags.fence()
            servers.fence()
            servers.forgetChecks()
        }
    }

    internal fun chainServersFile(context: Context): File = File(context.noBackupFilesDir, CHAIN_SERVERS_FILE)

    internal fun walletFlagsFile(context: Context): File = File(context.noBackupFilesDir, WALLET_FLAGS_FILE)

    internal fun settingsFile(store: KeystoreFileStore): ChainSettingsFile = object : ChainSettingsFile {
        override fun load(): ByteArray? = store.load()

        override fun save(bytes: ByteArray) = store.save(bytes)
    }

    internal fun flagsFile(store: KeystoreFileStore): WalletFlagsFile = object : WalletFlagsFile {
        override fun load(): ByteArray? = store.load()

        override fun save(bytes: ByteArray) = store.save(bytes)
    }
}
