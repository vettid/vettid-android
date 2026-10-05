package com.vettid.app.di

import android.content.Context
import android.os.Build
import com.vettid.app.env.currentEnvironment
import com.vettid.core.data.account.SignInLinkInbox
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
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.VaultManager
import com.vettid.core.data.vault.VaultRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun http(): OkHttpClient = OkHttpClient.Builder().readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS).build()

    @Provides
    @Singleton
    fun vaultManager(
        @ApplicationContext context: Context,
        env: AppEnvironment,
        http: OkHttpClient,
        @AppScope scope: CoroutineScope,
    ): VaultManager = VaultManager(context, env, http, scope, deviceName = Build.MODEL ?: "Android")

    @Provides
    fun accountRepository(m: VaultManager): AccountRepository = m

    @Provides
    fun vaultRepository(m: VaultManager): VaultRepository = m

    @Provides
    fun credentialRepository(m: VaultManager): CredentialRepository = m

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
    fun signInLinkInbox(): SignInLinkInbox = SignInLinkInbox()

    @Provides
    @Singleton
    fun inviteLinkInbox(): InviteLinkInbox = InviteLinkInbox()
}
