package com.vettid.app.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.vettid.app.BuildConfig
import com.vettid.app.di.AppScope
import com.vettid.core.calls.AndroidCallAudio
import com.vettid.core.calls.AndroidCallTones
import com.vettid.core.calls.CallGateway
import com.vettid.core.calls.CallService
import com.vettid.core.calls.CallVault
import com.vettid.core.calls.CallsController
import com.vettid.core.calls.MediaConfig
import com.vettid.core.calls.VaultCallGateway
import com.vettid.core.calls.WebRtcMediaEngine
import com.vettid.core.data.calls.CallAccess
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.VaultManager
import com.vettid.core.vault.VaultApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

/**
 * Calls in the builds that have them (debug, devStack, staging; CALLS-PLAN 0.1.1 phase C1): the one
 * [CallsController] of the process on the app's vault. Production builds have `src/noCalls` instead, and neither
 * `:core:calls` nor anything here.
 */
@Module
@InstallIn(SingletonComponent::class)
object CallsModule {
    /** What the calls use of the vault manager: its phase, its typed client, and its connections as the app shows them. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private class ManagerVault(private val m: VaultManager) : CallVault {
        override val unlocked: Flow<Boolean> = m.phase.map { it == AppPhase.Unlocked }.distinctUntilChanged()

        /** The vault client in use, read again at every unlock (a phone set up again has a new one). */
        private val clients: Flow<VaultApi> = unlocked.filter { it }.map { m.vaultApi() }.distinctUntilChanged { a, b -> a === b }

        override val gateway: CallGateway = VaultCallGateway(api = { m.vaultApi() }, clients = clients)

        override val droppedForTime: Flow<String> = clients.flatMapLatest { it.device.droppedForTime }

        /** Only an active connection has an identity a call can be checked against. */
        private fun connection(id: String) = m.social.connections.value.firstOrNull { it.id == id && it.state == ConnectionState.ACTIVE }

        override suspend fun fingerprint(connectionId: String): String? = connection(connectionId)?.keyFingerprint

        override fun displayName(connectionId: String): String? = connection(connectionId)?.let { it.accountName ?: it.name.ifEmpty { null } }
    }

    @Provides
    @Singleton
    fun callsController(
        @ApplicationContext context: Context,
        manager: VaultManager,
        @AppScope scope: CoroutineScope,
    ): CallsController = CallsController(
        vault = ManagerVault(manager),
        engines = WebRtcMediaEngine.factory(context),
        scope = scope,
        audio = AndroidCallAudio(context),
        tones = AndroidCallTones(context),
        microphoneGranted = { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
        startService = { CallService.start(context) },
        // Known-good algorithms only (owner decision 2026-10-12): the post-quantum DTLS trial stays off. The
        // debug screen of PR 4 may ask for it in a debuggable build; the controller refuses it in any other.
        config = MediaConfig(dtlsPqcTrial = false),
        debuggable = BuildConfig.DEBUG,
    ).also { CallService.host = it }

    @Provides
    fun callAccess(controller: CallsController): CallAccess = controller
}
