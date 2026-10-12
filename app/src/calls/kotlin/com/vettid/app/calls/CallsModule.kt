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
import com.vettid.core.data.prefs.NotificationPreviews
import com.vettid.core.data.prefs.PreferencesRepository
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.VaultManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
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
    private class ManagerVault(private val m: VaultManager, private val prefs: PreferencesRepository, scope: CoroutineScope) : CallVault {
        override val unlocked: Flow<Boolean> = m.phase.map { it == AppPhase.Unlocked }.distinctUntilChanged()

        /**
         * The events of the vault device in use, from the moment its session exists (before the vault is open): the
         * call that was waiting in the mailbox at an unlock is collected right then, and the device's events are not
         * replayed.
         */
        private val sources: Flow<VaultManager.VaultEvents> = m.vaultEvents.filterNotNull()

        /** "Show in notifications": names unless the member chose that notifications show nothing. */
        private val showNames: StateFlow<Boolean> = prefs.preferences.map { it.notificationPreviews != NotificationPreviews.NOTHING }
            .stateIn(scope, SharingStarted.Eagerly, false)

        override suspend fun refreshConnections() {
            runCatching { m.social.refresh() }
        }

        /** The daily check was done, or the connections were read: the vault answers now, if it did not before. */
        override val recheck: Flow<Unit> = merge(m.ownerCheck.ownerCheck.map { }, m.social.connections.map { })

        override val gateway: CallGateway =
            VaultCallGateway(api = { m.vaultApi() }, events = sources.map { it.events }, endApi = { m.vaultApiForCallEnd() })

        override val droppedForTime: Flow<String> = sources.flatMapLatest { it.droppedForTime }

        /** Only an active connection has an identity a call can be checked against. */
        private fun connection(id: String) = m.social.connections.value.firstOrNull { it.id == id && it.state == ConnectionState.ACTIVE }

        override suspend fun fingerprint(connectionId: String): String? = connection(connectionId)?.keyFingerprint

        override fun displayName(connectionId: String): String? =
            if (showNames.value) connection(connectionId)?.let { it.accountName ?: it.name.ifEmpty { null } } else null
    }

    @Provides
    @Singleton
    fun callsController(
        @ApplicationContext context: Context,
        manager: VaultManager,
        prefs: PreferencesRepository,
        @AppScope scope: CoroutineScope,
    ): CallsController = CallsController(
        vault = ManagerVault(manager, prefs, scope),
        engines = WebRtcMediaEngine.factory(context),
        scope = scope,
        audio = AndroidCallAudio(context),
        tones = AndroidCallTones(context),
        microphoneGranted = { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
        startService = { CallService.start(context) },
        // Known-good algorithms only (owner decision 2026-10-12): the post-quantum DTLS trial stays off, and no
        // fault is ever on by default. Only a build made for the phone tests may change that (the controller
        // refuses it in any other): a debuggable build, or a staging build made with -PvettidCallsTest=true.
        config = MediaConfig(),
        testBuild = BuildConfig.CALLS_TEST,
    ).also { controller ->
        CallService.host = controller
        // The member's lock waits (two seconds at most) for a live call's hang-up to reach the vault.
        manager.onBeforeLock { controller.endCallBeforeLock() }
    }

    @Provides
    fun callAccess(controller: CallsController): CallAccess = controller
}
