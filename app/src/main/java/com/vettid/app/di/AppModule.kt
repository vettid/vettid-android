package com.vettid.app.di

import android.content.Context
import com.vettid.app.core.attestation.HardwareAttestationManager
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.network.ApiClient
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.features.auth.BiometricAuthManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
    fun provideHardwareAttestationManager(@ApplicationContext context: Context): HardwareAttestationManager {
        return HardwareAttestationManager(context)
    }

    @Provides
    @Singleton
    fun provideBiometricAuthManager(@ApplicationContext context: Context): BiometricAuthManager {
        return BiometricAuthManager(context)
    }
}
