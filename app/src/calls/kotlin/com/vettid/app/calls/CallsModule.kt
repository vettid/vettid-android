package com.vettid.app.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.vettid.app.BuildConfig
import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Suite
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
import com.vettid.core.calls.vaultOpenings
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.calls.CallAccess
import com.vettid.core.data.prefs.NotificationPreviews
import com.vettid.core.data.prefs.PreferencesRepository
import com.vettid.core.data.social.ConnectionInfo
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.delay
import com.vettid.core.data.vault.OwnerCheckView
import java.time.Duration
import java.time.Instant
import javax.inject.Singleton

/**
 * The name a call shows for a connection: the names on its VettID account, else its display name, each cleaned first
 * (VAULT-MESSAGING §10.8), so a name that shows nothing (only U+2800, say) falls through to the next; null when neither
 * shows anything.
 */
internal fun callName(c: ConnectionInfo): String? = AccountNames.cleaned(c.accountName) ?: AccountNames.cleaned(c.name)

/**
 * The phone leaves its vault ([CallVault.vaultLeft]) when its phase becomes one with no vault set up on it: signed out
 * (after a sign-out, a wipe, the end of a deletion, or as a phone a transfer or a recovery replaced) or setting one up
 * (a new enrolment, a recovery, a transfer to this phone, each of which starts there). Once per leaving; a lock and an
 * unlock are not one. An unlock of the same vault that passes through setup (`Setup` with `FINISHING`,
 * `NEEDS_CREDENTIAL` or `RECOVERING`) counts as one too: clearing then forgets more than needed, which is harmless.
 *
 * [phase] is a conflated state flow, so a value can in principle be skipped. A signed-out or setup value is not skipped
 * in practice: reaching another vault from here needs the member's input in between.
 */
internal fun vaultLeavings(phase: Flow<AppPhase>): Flow<Unit> =
    phase.map { it == AppPhase.SignedOut || it is AppPhase.Setup }.distinctUntilChanged().filter { it }.map { }

/**
 * Whether the calls count the vault as open: unlocked, and no update of its release required first (Rule 3, the release
 * gate: while its release is checked or the vault is behind, no call starts or rings, and a live one ends).
 */
internal fun callsOpen(phase: AppPhase, releaseRequired: Boolean): Boolean = phase == AppPhase.Unlocked && !releaseRequired

/**
 * [CallVault.unlocked] from the vault's [phase], its [opens] and the release gate's [required]. All read live, the phase
 * first: an opening counts itself and starts its release check before the phase is published, so a phase read as open
 * always comes with its own count and its own check (the flows' values, collected apart, could pair an open phase with
 * the count before it and make an unlock look like a lock and an unlock, or with the gate before its check).
 */
internal fun callsUnlocked(phase: StateFlow<AppPhase>, opens: StateFlow<Int>, required: StateFlow<Boolean>): Flow<Boolean> =
    vaultOpenings(
        combine(phase, opens, required) { _, _, _ ->
            val open = callsOpen(phase.value, required.value)
            open to opens.value
        }.distinctUntilChanged(),
    )

/**
 * Whether the daily owner check is due or held ([OwnerCheckView.gated]) as it changes: at each new [view], and again when
 * its deadline passes with no new value (the vault says nothing then). Owner decision a, 2026-11-17: no ring meanwhile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun ownerCheckGatings(view: Flow<OwnerCheckView?>, now: () -> Instant): Flow<Boolean> = view.transformLatest { v ->
    while (true) {
        val t = now()
        val gated = v?.gated(t) == true
        emit(gated)
        val deadline = v?.deadline?.takeIf { !gated } ?: break
        delay(Duration.between(t, deadline).toMillis().coerceAtLeast(1))
    }
}.distinctUntilChanged()

/**
 * Calls in every build, release included (owner decision 2026-11-14): the one [CallsController] of the process on the
 * app's vault. Calls show while the vault is open on this phone and no release update is required ([callsOpen]).
 */
@Module
@InstallIn(SingletonComponent::class)
object CallsModule {
    /**
     * Whether this build may switch on the call faults, the DTLS trial and relay only (the controller and the engines
     * refuse them otherwise): the call-test builds alone. ReleaseCallGateTest holds it false in the release.
     */
    internal val TEST_BUILD: Boolean = BuildConfig.CALLS_TEST

    /** The logcat tag of a call-test build's tap log ([CallsController]'s testLog). */
    private const val TEST_LOG_TAG = "VettIdCallTest"

    /** What the calls use of the vault manager: its phase, its typed client, and its connections as the app shows them. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private class ManagerVault(private val m: VaultManager, private val prefs: PreferencesRepository, scope: CoroutineScope) : CallVault {
        override val unlocked: Flow<Boolean> = callsUnlocked(m.phase, m.opens, m.releaseUpdate.required)

        override val unlockedNow: Boolean get() = callsOpen(m.phase.value, m.releaseUpdate.required.value)

        override val vaultLeft: Flow<Unit> = vaultLeavings(m.phase)

        /** The root UI shows the release update over every phase while its progress is non-null (VettIdApp). */
        override val updateShowing: Boolean get() = m.releaseUpdate.progress.value != null

        /** The daily owner check due or held now (the rule presence and location follow): no ring (owner decision a, 2026-11-17). */
        override val ownerCheckGated: Boolean get() = m.ownerCheck.ownerCheck.value?.gated(java.time.Instant.now()) == true

        override val ownerCheckGatings: Flow<Boolean> = ownerCheckGatings(m.ownerCheck.ownerCheck, Instant::now)

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
            VaultCallGateway(api = { m.vaultApi() }, events = sources.map { it.events }, sendEnd = m::sendCallEnd, testBuild = TEST_BUILD)

        override val droppedForTime: Flow<String> = sources.flatMapLatest { it.droppedForTime }

        /** Only an active connection has an identity a call can be checked against. */
        private fun connection(id: String) = m.social.connections.value.firstOrNull { it.id == id && it.state == ConnectionState.ACTIVE }

        override suspend fun fingerprint(connectionId: String): String? = connection(connectionId)?.keyFingerprint

        /** The key the fingerprint is of, from the same record: a form 2 call names it. */
        override suspend fun identityKey(connectionId: String): ByteArray? =
            connection(connectionId)?.ik?.let { runCatching { Base64s.decodeStd(it, Suite.ED25519_PUBLIC_SIZE) }.getOrNull() }

        override suspend fun deviceId(): String? = runCatching { m.vaultApi().device.deviceId }.getOrNull()

        override fun name(connectionId: String): String? = connection(connectionId)?.let(::callName)

        override fun firstName(connectionId: String): String? = connection(connectionId)?.let { AccountNames.cleaned(it.firstName) }

        override fun notificationName(connectionId: String): String? = if (showNames.value) name(connectionId) else null

        /** This app's signer as its own entry in the vault's device list says (the "Installed from" line). */
        override suspend fun signerClass(): String? = runCatching { m.overview().signer?.signerClass?.wire }.getOrNull()
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
        engines = WebRtcMediaEngine.factory(context, testBuild = TEST_BUILD),
        scope = scope,
        audio = AndroidCallAudio(context),
        tones = AndroidCallTones(context),
        microphoneGranted = { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
        startService = { CallService.start(context) },
        // Known-good algorithms only (owner decision 2026-10-12): the post-quantum DTLS trial stays off, and no
        // fault is ever on by default. Only a build made for the phone tests may change that (the controller
        // refuses it in any other): a debuggable build, or a staging build made with -PvettidCallsTest=true.
        config = MediaConfig(),
        testBuild = TEST_BUILD,
        // Call-test builds only: the taps and the route and mute changes (states only), for a phone run's logcat.
        testLog = { line -> if (TEST_BUILD) Log.i(TEST_LOG_TAG, line) },
    ).also { controller ->
        CallService.host = controller
        // The member's lock waits (two seconds at most) for a live call's hang-up to reach the vault.
        manager.onBeforeLock { controller.endCallBeforeLock() }
    }

    @Provides
    fun callAccess(controller: CallsController): CallAccess = controller
}
