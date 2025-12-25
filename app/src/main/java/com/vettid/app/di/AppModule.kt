package com.vettid.app.di

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vettid.app.core.attestation.HardwareAttestationManager
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.crypto.RecoveryPhraseManager
import com.vettid.app.core.nats.NatsApiClient
import com.vettid.app.core.network.BackupApiClient
import com.vettid.app.core.network.CredentialBackupApiClient
import com.vettid.app.core.security.ApiSecurity
import com.vettid.app.core.security.RuntimeProtection
import com.vettid.app.core.security.SecureClipboard
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import com.vettid.app.core.nats.CallSignalingClient
import com.vettid.app.core.nats.NatsClient
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.features.calling.CallManager
import com.vettid.app.core.network.ApiClient
import com.vettid.app.core.network.VaultServiceClient
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.features.auth.BiometricAuthManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideCryptoManager(): CryptoManager {
        return CryptoManager()
    }

    @Provides
    @Singleton
    fun provideCredentialStore(@ApplicationContext context: Context): CredentialStore {
        return CredentialStore(context)
    }

    @Provides
    @Singleton
    fun provideApiClient(): ApiClient {
        return ApiClient()
    }

    @Provides
    @Singleton
    fun provideVaultServiceClient(): VaultServiceClient {
        return VaultServiceClient()
    }

    @Provides
    @Singleton
    fun provideHardwareAttestationManager(@ApplicationContext context: Context): HardwareAttestationManager {
        return HardwareAttestationManager(context)
    }

    @Provides
    @Singleton
    fun provideBiometricAuthManager(@ApplicationContext context: Context): BiometricAuthManager {
        return BiometricAuthManager(context)
    }

    // NATS Dependencies

    @Provides
    @Singleton
    @Named("nats")
    fun provideNatsSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            "nats_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Provides
    @Singleton
    fun provideNatsClient(): NatsClient {
        return NatsClient()
    }

    @Provides
    @Singleton
    fun provideNatsApiClient(): NatsApiClient {
        return NatsApiClient()
    }

    @Provides
    @Singleton
    fun provideNatsConnectionManager(
        natsClient: NatsClient,
        natsApiClient: NatsApiClient,
        @Named("nats") sharedPreferences: SharedPreferences
    ): NatsConnectionManager {
        return NatsConnectionManager(natsClient, natsApiClient, sharedPreferences)
    }

    @Provides
    @Singleton
    fun provideOwnerSpaceClient(
        connectionManager: NatsConnectionManager,
        credentialStore: CredentialStore
    ): OwnerSpaceClient {
        return OwnerSpaceClient(connectionManager, credentialStore)
    }

    // Calling Dependencies

    @Provides
    @Singleton
    fun provideCallSignalingClient(
        ownerSpaceClient: OwnerSpaceClient
    ): CallSignalingClient {
        return CallSignalingClient(ownerSpaceClient)
    }

    @Provides
    @Singleton
    fun provideCallManager(
        @ApplicationContext context: Context,
        signalingClient: CallSignalingClient
    ): CallManager {
        return CallManager(context, signalingClient)
    }

    // Backup Dependencies

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideBackupApiClient(
        httpClient: OkHttpClient,
        credentialStore: CredentialStore
    ): BackupApiClient {
        return BackupApiClient(httpClient, credentialStore)
    }

    @Provides
    @Singleton
    fun provideCredentialBackupApiClient(
        httpClient: OkHttpClient,
        credentialStore: CredentialStore
    ): CredentialBackupApiClient {
        return CredentialBackupApiClient(httpClient, credentialStore)
    }

    @Provides
    @Singleton
    fun provideRecoveryPhraseManager(cryptoManager: CryptoManager): RecoveryPhraseManager {
        return RecoveryPhraseManager(cryptoManager)
    }

    // Security Dependencies

    @Provides
    @Singleton
    fun provideRuntimeProtection(@ApplicationContext context: Context): RuntimeProtection {
        return RuntimeProtection(context)
    }

    @Provides
    @Singleton
    fun provideSecureClipboard(@ApplicationContext context: Context): SecureClipboard {
        return SecureClipboard(context)
    }

    @Provides
    @Singleton
    fun provideApiSecurity(): ApiSecurity {
        return ApiSecurity()
    }
}
