package com.vettid.app.di

import android.content.Context
import android.os.Build
import com.vettid.app.env.currentEnvironment
import com.vettid.app.net.ConnectivityGate
import com.vettid.core.data.account.SetupLinkInbox
import com.vettid.core.data.social.InviteLinkInbox
import com.vettid.core.data.env.AppEnvironment
import com.vettid.core.data.lock.AppLock
import com.vettid.core.data.lock.FileWrappedKeyFile
import com.vettid.core.data.lock.KeystoreAppLockKeys
import com.vettid.core.data.prefs.DataStorePreferencesRepository
import com.vettid.core.data.prefs.PreferencesRepository
import com.vettid.core.data.social.ApprovalsRepository
import com.vettid.core.data.social.ConnectionsRepository
import com.vettid.core.data.social.MessagesRepository
import com.vettid.core.data.vault.AccountRepository
import com.vettid.core.data.vault.CanaryManifestInbox
import com.vettid.core.data.vault.CanaryManifestRepository
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.MoveRepository
import com.vettid.core.data.vault.RelaySafetyNet
import com.vettid.core.data.vault.OwnerCheckRepository
import com.vettid.core.data.vault.HistoryRepository
import com.vettid.core.data.vault.ProfileRepository
import com.vettid.core.data.vault.VaultManager
import com.vettid.core.data.vault.VaultRepository
import com.vettid.core.data.wipe.AndroidWipeTargets
import com.vettid.core.data.wipe.LocalWipe
import com.vettid.core.relay.NetworkGate
import com.vettid.core.relay.TransportRetry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** The process-wide scope (mailbox collector, event observer). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
@Suppress("TooManyFunctions") // one provider per binding
object AppModule {
    private const val READ_TIMEOUT_S = 70L

    @Provides
    @Singleton
    fun environment(): AppEnvironment = currentEnvironment

    @Provides
    @Singleton
    @AppScope
    fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + safetyNet)

    /** A relay or transport error that escapes a coroutine of the process scope is logged, never fatal. */
    private val safetyNet = RelaySafetyNet(onRelayError = { e -> android.util.Log.w("VettID", "relay error not handled: ${e.message}") })

    /** One pool for every client derived from [http]; emptied when the network changes or the app comes back (VettIdApplication). */
    @Provides
    @Singleton
    fun connectionPool(): ConnectionPool = ConnectionPool()

    @Provides
    @Singleton
    fun networkGate(@ApplicationContext context: Context, pool: ConnectionPool): NetworkGate =
        ConnectivityGate(context, onNewNetwork = { pool.evictAll() })

    /** Transport failures while the phone wakes from Doze are retried before anything reaches the screen ([TransportRetry]). */
    @Provides
    @Singleton
    fun http(pool: ConnectionPool, gate: NetworkGate): OkHttpClient = OkHttpClient.Builder()
        .connectionPool(pool)
        .addInterceptor(TransportRetry(gate))
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun vaultManager(
        @ApplicationContext context: Context,
        env: AppEnvironment,
        http: OkHttpClient,
        @AppScope scope: CoroutineScope,
        wipe: LocalWipe,
    ): VaultManager = VaultManager(context, env, http, scope, deviceName = Build.MODEL ?: "Android", wiper = wipe)

    /** The wipe of a replaced phone (owner decision, 2026-10-05): what it erases, and the in-memory state it resets. */
    @Provides
    @Singleton
    fun localWipe(
        @ApplicationContext context: Context,
        prefs: PreferencesRepository,
        appLock: AppLock,
        setupLinks: SetupLinkInbox,
        inviteLinks: InviteLinkInbox,
        canaryManifests: CanaryManifestInbox,
    ): LocalWipe = LocalWipe(
        AndroidWipeTargets(context),
        hooks = listOf(
            { appLock.reset() },
            { prefs.clear() },
            { setupLinks.consume() },
            { inviteLinks.consume() },
            { canaryManifests.consume() },
        ),
    )

    @Provides
    fun accountRepository(m: VaultManager): AccountRepository = m

    @Provides
    fun vaultRepository(m: VaultManager): VaultRepository = m

    @Provides
    fun credentialRepository(m: VaultManager): CredentialRepository = m

    @Provides
    fun moveRepository(m: VaultManager): MoveRepository = m

    @Provides
    fun ownerCheckRepository(m: VaultManager): OwnerCheckRepository = m.ownerCheck

    @Provides
    fun profileRepository(m: VaultManager): ProfileRepository = m.profile

    @Provides
    fun historyRepository(m: VaultManager): HistoryRepository = m.history

    @Provides
    fun canaryManifestRepository(m: VaultManager): CanaryManifestRepository = m.canary

    @Provides
    fun connectionsRepository(m: VaultManager): ConnectionsRepository = m.social

    @Provides
    fun messagesRepository(m: VaultManager): MessagesRepository = m.social

    @Provides
    fun approvalsRepository(m: VaultManager): ApprovalsRepository = m.social

    @Provides
    @Singleton
    fun preferences(@ApplicationContext context: Context): PreferencesRepository = DataStorePreferencesRepository(context)

    @Provides
    @Singleton
    fun appLock(@ApplicationContext context: Context, prefs: PreferencesRepository): AppLock =
        AppLock(prefs, KeystoreAppLockKeys(), FileWrappedKeyFile(File(context.noBackupFilesDir, "app-lock.bin")))

    @Provides
    @Singleton
    fun setupLinkInbox(): SetupLinkInbox = SetupLinkInbox()

    @Provides
    @Singleton
    fun inviteLinkInbox(): InviteLinkInbox = InviteLinkInbox()

    @Provides
    @Singleton
    fun canaryManifestInbox(): CanaryManifestInbox = CanaryManifestInbox()
}
