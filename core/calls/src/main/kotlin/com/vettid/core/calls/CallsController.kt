package com.vettid.core.calls

import com.vettid.core.data.calls.CallAccess
import com.vettid.core.data.calls.CallAvailability
import com.vettid.core.data.calls.CallHistory
import com.vettid.core.data.calls.CallHistoryEntry
import com.vettid.core.data.calls.CallOutcome
import com.vettid.core.data.calls.CallPermissions
import com.vettid.core.data.calls.ConnectionCalls
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.vaultGuard
import com.vettid.core.vault.CallReason
import com.vettid.core.vault.CallLimits
import com.vettid.core.vault.CallList
import com.vettid.core.vault.CallRecord
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

/** What the calls need of the app's vault; the app's wiring gives it from `VaultManager`. */
interface CallVault {
    /** True while the vault is open on this phone. */
    val unlocked: Flow<Boolean>

    /** True while the release-update screen covers the app: no screen could answer a call, so none rings. */
    val updateShowing: Boolean get() = false

    val gateway: CallGateway

    /** The fingerprint of [connectionId]'s pinned identity key, as the connection's details show it; null if unknown. */
    suspend fun fingerprint(connectionId: String): String?

    /** (C2) This device's id in the vault (its own `device.list` entry): the stale sweep's own calls. */
    suspend fun deviceId(): String? = null

    /**
     * (C2) [connectionId]'s pinned identity key itself (32 bytes), the key [fingerprint] is of; null if unknown. A form 2
     * call names it (VAULT-MESSAGING 0.88.16).
     */
    suspend fun identityKey(connectionId: String): ByteArray? = null

    /** The name the app shows for [connectionId] on its screens; null if it has none. */
    fun name(connectionId: String): String?

    /** (C2) The connection's first name, for the lines that name it so ("Calls with <First> are off."); null if none. */
    fun firstName(connectionId: String): String? = null

    /**
     * The name for [connectionId] in a notification: [name], or null when the member's notifications show no names
     * (the choice the message notifications follow).
     */
    fun notificationName(connectionId: String): String?

    /** The types of the vault's messages this phone dropped for their time (a wrong clock shows here). */
    val droppedForTime: Flow<String>

    /** Reads the app's connections again (an offer can be handled before the list has been read after an unlock). */
    suspend fun refreshConnections()

    /**
     * Something changed that may make the vault answer where it refused before (the daily check was done, a vault
     * operation succeeded): if it is not yet known whether the vault has calls, it is asked again.
     */
    val recheck: Flow<Unit>

    /**
     * The class of the key this app is signed with, as the vault reports it (`vettid`, `google_play`,
     * `development`); null if the vault cannot be asked or does not say.
     */
    suspend fun signerClass(): String?
}

/** What was held back for the microphone permission: a call to [call], or (null) the answer to the ringing call. */
data class MicrophoneRequest(val call: String?)

/**
 * What is known beyond one call, for the diagnostics view and the phone tests: counts, and the call ids of skipped
 * `call.list` entries ([listSkippedIds]), cleared at every lock. Prints its counts only.
 */
data class CallsDiagnostics(
    /** Offers and answers that failed their checks since the process started. */
    val refused: Int = 0,
    /**
     * Call messages from the vault that this phone dropped as expired or from the future. Not zero: this phone's
     * clock is probably wrong (an offer lives 45 s), and that is why it does not ring.
     */
    val droppedForTime: Int = 0,
    /** Calls left live in the vault by an earlier run of the app, ended at unlock. */
    val staleEnded: Int = 0,
    /** Of [refused], the ones where something failed (the vault client, the relay) rather than a check. */
    val errors: Int = 0,
    /** How often the listening to the vault's call events had to be started again (expected: 0). */
    val listenRestarts: Int = 0,
    /**
     * (GC #7718) The `call.list` entries the last read skipped (they did not read), and the call ids of those whose id read
     * (at most [CallsController.SKIPPED_IDS_KEPT]): kept in memory only, never logged, nothing else of an entry.
     */
    val listSkipped: Int = 0,
    val listSkippedIds: List<String> = emptyList(),
) {
    override fun toString(): String =
        "CallsDiagnostics(refused=$refused, droppedForTime=$droppedForTime, staleEnded=$staleEnded, errors=$errors, " +
            "listenRestarts=$listenRestarts, listSkipped=$listSkipped, listSkippedIds=${listSkippedIds.size})"
}

/**
 * Calls in the app: the one [CallMachine] of the process and what goes on around a call (CALLS-PLAN 0.1.1 C1).
 *
 * - **Whether there are calls.** No message lists a vault release's features, so after every unlock the vault is
 *   asked for its call list: an answer means calls ([CallAvailability.READY]), `unsupported_type` means none.
 * - **Stale calls.** The vault of S10 has no timer and no liveness check: a call whose app died stays live there
 *   (and the vault busy) for up to 12 hours. With that same list, every call the vault still holds live while this
 *   app has none is ended.
 * - **The microphone.** No call is placed or answered without the permission; [microphoneNeeded] asks the screen
 *   to ask for it.
 * - **Around a call.** The audio route, the ring-back tone and the ringtone, the foreground service, the end of a
 *   call when the vault locks and after 12 hours.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class CallsController(
    private val vault: CallVault,
    engines: MediaEngineFactory,
    private val scope: CoroutineScope,
    private val audio: CallAudio,
    private val tones: CallTones,
    private val microphoneGranted: () -> Boolean,
    private val startService: () -> Unit,
    config: MediaConfig = MediaConfig(),
    /**
     * Whether this build was made for the phone tests: the only kind that may ask for [MediaConfig.dtlsPqcTrial]
     * or any of [MediaFaults] (owner decision 2026-10-12: known-good algorithms only; and no build a member uses
     * can break a rule on purpose). Necessary, not sufficient: see [testsAllowed].
     */
    private val testBuild: Boolean = false,
    private val now: () -> Instant = Instant::now,
    timings: CallTimings = CallTimings(),
) : CallAccess, CallServiceHost {
    init {
        // What the app starts with is never a test configuration: those are switched on by hand, after the checks.
        check(!config.dtlsPqcTrial && !config.faults.any) { "no test configuration by default" }
    }

    @Volatile
    private var mediaConfigValue = config

    private val testsAllowedFlow = MutableStateFlow(false)

    /**
     * Whether this build may break a rule on purpose right now: it was built for the phone tests ([testBuild])
     * **and** the vault, asked since the last unlock, reports that this app is signed with a development key (the
     * automation key of the tests; never the key of a build members get). False until the vault has said so and
     * whenever it is not known.
     */
    val testsAllowed: StateFlow<Boolean> = testsAllowedFlow.asStateFlow()

    /** How the next call's media engine is set up: a test configuration only while [testsAllowed]. */
    val mediaConfig: MediaConfig get() = if (testsAllowedFlow.value) mediaConfigValue else MediaConfig()

    /** Changes how the next call's engine is set up. Throws unless [testsAllowed] (or [c] is the plain configuration). */
    fun setMediaConfig(c: MediaConfig) {
        val plain = !c.dtlsPqcTrial && !c.faults.any
        check(plain || testsAllowedFlow.value) { "the post-quantum DTLS trial and the test faults are for the phone tests only" }
        mediaConfigValue = c
    }

    private val signalling = VaultCallSignalling(
        vault.gateway,
        vault::fingerprint,
        engines.suites,
        vault::refreshConnections,
        // An offer waits a moment for an unlock that is completing; none comes: it does not ring.
        mayRing = { !vault.updateShowing && (open || withTimeoutOrNull(UNLOCK_WAIT.toMillis()) { vault.unlocked.first { it } } != null) },
        peerKey = vault::identityKey,
        vaultIsC2 = { vaultC2 },
    )
    private val machine = CallMachine(signalling, engines, scope, now, timings = timings, config = { mediaConfig })

    /** The call, for the call screens. */
    override val state: StateFlow<CallState> = machine.state
    val callDiagnostics: StateFlow<CallDiagnostics> = machine.diagnostics
    val audioState: StateFlow<AudioState> = audio.state

    private val availabilityFlow = MutableStateFlow(CallAvailability.UNKNOWN)
    override val availability: StateFlow<CallAvailability> = availabilityFlow.asStateFlow()

    private val permissionsFlow = MutableStateFlow(CallPermissions())
    override val permissions: StateFlow<CallPermissions> = permissionsFlow.asStateFlow()

    /** Reads of `call.permission.list` asked for, one at a time; asks that come while one runs make one more. */
    private val permissionReads = Channel<Unit>(Channel.CONFLATED)

    /**
     * Bumped at every lock: a read or a change begun before it writes nothing after it. The bump with its clear, and each
     * check with its write, are made under [permissionLock], so no write lands between a lock's bump and its clear.
     */
    private var permissionEpoch = 0L
    private val permissionLock = Any()

    /**
     * Bumped by each change the member made here ([setAllowed]), under [permissionLock]: a read begun before it holds
     * the list as it was and is dropped (the read the change asks for follows it), so the switch never flips back.
     */
    private var ownChanges = 0L

    private fun permissionEpochNow(): Long = synchronized(permissionLock) { permissionEpoch }

    private val diagnosticsFlow = MutableStateFlow(CallsDiagnostics())
    val diagnostics: StateFlow<CallsDiagnostics> = diagnosticsFlow.asStateFlow()

    private val microphoneFlow = MutableSharedFlow<MicrophoneRequest>(extraBufferCapacity = 1)

    /** A call or an answer was held back for the microphone permission: ask for it, then call [call] or [answer] again. */
    val microphoneNeeded: SharedFlow<MicrophoneRequest> = microphoneFlow.asSharedFlow()

    /** Whether the vault's release has calls; null until it has been asked since the last unlock. */
    @Volatile
    private var supported: Boolean? = null

    /** (C2) Whether the vault is C2 (`call.list` `v: 2`, read again after each unlock; nothing is cached across one). */
    @Volatile
    private var vaultC2 = false

    /** (C2) The keep-alives of the active call, one loop per call id. */
    private var keepalive: Pair<String, Job>? = null

    @Volatile
    private var open = false

    /** One probe at a time: the one an unlock starts and one a [CallVault.recheck] starts never run side by side. */
    private val probing = Mutex()
    private var longCall: Job? = null

    init {
        scope.launch {
            vault.unlocked.collectLatest { isOpen ->
                open = isOpen
                if (isOpen) opened() else locked()
            }
        }
        scope.launch { machine.state.collect { around(it) } }
        scope.launch { signalling.refused.collect { n -> diagnosticsFlow.update { it.copy(refused = n) } } }
        scope.launch { signalling.errors.collect { n -> diagnosticsFlow.update { it.copy(errors = n) } } }
        scope.launch { machine.listenRestarts.collect { n -> diagnosticsFlow.update { it.copy(listenRestarts = n) } } }
        scope.launch { vault.recheck.collect { if (open && supported == null) opened() } }
        scope.launch { vault.gateway.permissionChanges.collect { if (open && vaultC2) permissionReads.trySend(Unit) } }
        scope.launch {
            while (true) {
                permissionReads.receive()
                readPermissions()
            }
        }
        scope.launch {
            vault.droppedForTime.collect { type ->
                if (type.startsWith("call.")) diagnosticsFlow.update { it.copy(droppedForTime = it.droppedForTime + 1) }
            }
        }
    }

    /** Tests: as if the vault had not been asked yet. */
    internal fun forgetProbeForTest() {
        supported = null
    }

    // --- the member ---

    override fun call(connectionId: String) {
        if (availabilityFlow.value != CallAvailability.READY) return
        if (microphoneGranted()) machine.call(connectionId) else microphoneFlow.tryEmit(MicrophoneRequest(connectionId))
    }

    fun answer() {
        if (microphoneGranted()) machine.answer() else microphoneFlow.tryEmit(MicrophoneRequest(null))
    }

    fun decline() = machine.decline()

    override fun hangUp() = machine.hangUp()

    fun setMuted(muted: Boolean) = machine.setMuted(muted)

    fun setRoute(route: AudioRoute) = audio.setRoute(route)

    fun dismiss() = machine.dismiss()

    override fun displayName(connectionId: String): String? = vault.notificationName(connectionId)

    /** The connection's name for the call screens. */
    fun screenName(connectionId: String): String? = vault.name(connectionId)

    /** The connection's first name for the call screens' lines that use it. */
    fun screenFirstName(connectionId: String): String? = vault.firstName(connectionId)

    // --- the vault ---

    private suspend fun opened() {
        probing.withLock {
            // Asked while another probe ran: it may have answered already.
            if (open && supported == null) probe()
        }
    }

    /**
     * Ends a live call and waits until its `call.end` is with the relay, before the vault is locked: a locked vault
     * collects nothing, so a hang-up sent after the lock would wait in its mailbox while the other member listens
     * to silence. The caller bounds the wait; the lock happens whatever becomes of this.
     */
    suspend fun endCallBeforeLock() {
        val live = machine.state.value is CallState.Live
        // Nothing to end and no hang-up on its way: no wait. A hang-up still being sent is waited for also when the
        // call itself is already over on this phone (the member hung up and locked at once).
        if (!live && machine.pendingEnds.value == 0) return
        if (live) machine.vaultLocking()
        combine(machine.state, machine.pendingEnds) { s, pending -> s !is CallState.Live && pending == 0 }.first { it }
    }

    @Suppress("ReturnCount")
    private suspend fun probe() {
        supported = null
        publish()
        repeat(PROBE_ATTEMPTS) { attempt ->
            val records = try {
                vault.gateway.list(HISTORY_LIMIT)
            } catch (e: VaultOpException) {
                // `unsupported_type`: a release without calls. Any other refusal (the daily check is due, …): not known.
                if (e.code == UNSUPPORTED) supported = false
                publish()
                return
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                delay(PROBE_RETRY.toMillis() * (attempt + 1))
                return@repeat
            }
            vaultC2 = records.formTwo
            supported = true
            publish()
            // (C2) The permission is a C2 vault's: an older one has none, and no switch is shown for it.
            if (records.formTwo) permissionReads.trySend(Unit) else permissionsFlow.value = CallPermissions()
            noteSkipped(records)
            if (records.formTwo) endStaleC2(records) else endStale(records.calls)
            if (testBuild) testsAllowedFlow.value = runCatching { vault.signerClass() }.getOrNull() == SIGNER_DEVELOPMENT
            return
        }
    }

    /** A read's skipped entries, in the diagnostics (this phone's own record of them). */
    private fun noteSkipped(list: CallList) {
        val ids = list.skipped.mapNotNull { it.callId }.take(SKIPPED_IDS_KEPT)
        diagnosticsFlow.update { it.copy(listSkipped = list.skipped.size, listSkippedIds = ids) }
    }

    /**
     * (C2, VAULT-MESSAGING §10.10 "Liveness") The call's device sends `call.keepalive` to its own vault as soon as the
     * call is active on it ([CallState.Live.answerThrough]: the callee once its `call.answer` is with its vault, the
     * caller once it took the answer; one sent before is answered `call.end{unavailable}`), then every 30 s until it
     * ends. Only to a C2 vault (an S10 vault answers an unknown type): one the probe found C2, or a call in form 2,
     * which proves it for that call even before the probe has answered.
     */
    private fun keepAlive(live: CallState.Live?) {
        val id = live?.callId?.takeIf { (vaultC2 || live.formTwo) && live.answerThrough }
        if (id == keepalive?.first) return
        keepalive?.second?.cancel()
        keepalive = id?.let {
            it to scope.launch {
                while (true) {
                    try {
                        signalling.keepalive(it)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") _: Exception) {
                        // One lost keep-alive is not an end: the next goes in 30 s, within the vault's window.
                    }
                    delay(CallLimits.KEEPALIVE_EVERY.toMillis())
                }
            }
        }
    }

    /**
     * (C2) The stale sweep against a C2 vault: by `device_id`, `exp` and the vault's `now`, never this phone's clock,
     * and only this device's own calls (other devices' are left alone). A live active call of this device ends
     * `failed`; its own outgoing call still ringing past `exp` ends `timeout`.
     */
    private suspend fun endStaleC2(list: CallList) {
        val me = vault.deviceId()
        val vaultNow = list.now
        if (machine.state.value is CallState.Live || me == null || vaultNow == null) return
        for (r in list.calls) {
            val reason = staleReasonC2(r, me, vaultNow) ?: continue
            runCatching { vault.gateway.end(r.callId, reason) }
                .onSuccess { diagnosticsFlow.update { it.copy(staleEnded = it.staleEnded + 1) } }
        }
    }

    /** The end of [r] by the C2 rule, or null when it is not this device's or not stale by the vault's [vaultNow]. */
    private fun staleReasonC2(r: CallRecord, me: String, vaultNow: Instant): String? {
        val pastExp = r.exp?.let(::time)?.let { !vaultNow.isBefore(it) } ?: false
        return when {
            r.deviceId != me -> null
            r.state == STATE_ACTIVE -> CallReason.FAILED
            r.state == STATE_RINGING && r.direction == DIRECTION_OUT && pastExp -> CallReason.TIMEOUT
            else -> null
        }
    }

    /**
     * Calls the vault still holds live while this app has none: left by a run of the app that ended during a call.
     * Ended there, or the vault answers every call `busy` for up to 12 hours (CALLS-PLAN §6.1 V3). A ringing call
     * is left alone while it could still be ringing.
     *
     * FOR C3 (a second device, the app closed): `call.list` at S10 gives neither the call's device nor its `exp`,
     * so "stale" is judged by this phone's clock against `started_at`, and a live record is taken to be this
     * device's. Both are right only while one app is the member's only call device; with the vault's V2 and V3
     * (CALLS-PLAN §6.1) this sweep goes away, or it must use a device id and a vault time from the list.
     */
    private suspend fun endStale(records: List<CallRecord>) {
        if (machine.state.value is CallState.Live) return
        val t = now()
        val stale = records.filter { r ->
            when (r.state) {
                STATE_ACTIVE -> true
                STATE_RINGING -> time(r.startedAt)?.let { Duration.between(it, t) > RING_STALE } ?: true
                else -> false
            }
        }
        for (r in stale) {
            val reason = if (r.state == STATE_RINGING && r.direction == DIRECTION_OUT) CallReason.TIMEOUT else CallReason.FAILED
            runCatching { vault.gateway.end(r.callId, reason) }
                .onSuccess { diagnosticsFlow.update { it.copy(staleEnded = it.staleEnded + 1) } }
        }
    }

    /**
     * (C2) Reads `call.permission.list` into [permissions]: what was read before stays while a read fails (it is tried
     * again a few times); a read begun before a lock writes nothing after it.
     */
    @Suppress("ReturnCount") // each way a read ends: locked meanwhile, refused, read
    private suspend fun readPermissions() {
        val epoch = permissionEpochNow()
        val changes = synchronized(permissionLock) { ownChanges }
        repeat(PROBE_ATTEMPTS) { attempt ->
            if (!open || !vaultC2 || epoch != permissionEpochNow()) return
            val list = try {
                vault.gateway.permissions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: VaultOpException) {
                // `unsupported_type` from a vault the probe called C2 cannot happen; any refusal leaves things as they are.
                if (e.code == UNSUPPORTED) {
                    synchronized(permissionLock) { if (epoch == permissionEpoch) permissionsFlow.value = CallPermissions() }
                }
                return
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                delay(PROBE_RETRY.toMillis() * (attempt + 1))
                return@repeat
            }
            val read = CallPermissions(
                supported = true,
                known = true,
                differing = list.associate { it.connectionId to ConnectionCalls(it.allowed, it.limited) }
                    .filterValues { it != ConnectionCalls() },
            )
            synchronized(permissionLock) {
                if (epoch == permissionEpoch && changes == ownChanges && open) permissionsFlow.value = read
            }
            return
        }
    }

    override suspend fun setAllowed(connectionId: String, allowed: Boolean) {
        val epoch = permissionEpochNow()
        vaultGuard {
            if (!open || !vaultC2) throw VaultFailure(FailureKind.OTHER, CODE_NO_PERMISSION)
            vault.gateway.setPermission(connectionId, allowed)
        }
        synchronized(permissionLock) {
            ownChanges++
            if (epoch == permissionEpoch) {
                permissionsFlow.update { p ->
                    val now = p.of(connectionId).copy(allowed = allowed)
                    p.copy(differing = if (now == ConnectionCalls()) p.differing - connectionId else p.differing + (connectionId to now))
                }
            }
        }
        // The vault's own word follows (also its sync.event): turning calls on may not end a limit.
        permissionReads.trySend(Unit)
    }

    private fun locked() {
        synchronized(permissionLock) {
            permissionEpoch++
            permissionsFlow.value = CallPermissions()
        }
        supported = null
        vaultC2 = false
        testsAllowedFlow.value = false
        machine.vaultLocking()
        // Nothing of a call is kept for the next unlock: an ended call's line (this one's "…your vault was locked"
        // included, which nobody can see behind the unlock screen) would show then, long after.
        machine.dismiss()
        // (Review of 0470e984, L1) Nothing of the vault's call history is kept while it is locked: not the skipped
        // entries' call ids either (a sign-out to another vault in this process would keep the previous one's).
        diagnosticsFlow.update { it.copy(listSkipped = 0, listSkippedIds = emptyList()) }
        publish()
    }

    private fun publish() {
        availabilityFlow.value = when {
            supported == null -> CallAvailability.UNKNOWN
            supported == false -> CallAvailability.NOT_IN_VAULT
            machine.state.value is CallState.Live -> CallAvailability.IN_CALL
            else -> CallAvailability.READY
        }
    }

    // --- around a call ---

    private fun around(s: CallState) {
        publish()
        val live = s as? CallState.Live
        tones.ringtone(live?.phase == CallPhase.INCOMING)
        tones.ringBack(live?.phase == CallPhase.RINGING)
        if (live != null && live.phase != CallPhase.INCOMING) {
            audio.start()
            startService()
        } else {
            audio.stop()
        }
        keepAlive(live)
        if (live?.phase == CallPhase.ACTIVE) {
            if (longCall == null) {
                // The vault stops counting a call as live 12 hours after its answer; the frame cipher's nonce must
                // not wrap either (CALL-MEDIA.md §6).
                longCall = scope.launch {
                    delay(MAX_CALL.toMillis())
                    machine.hangUp()
                }
            }
        } else {
            longCall?.cancel()
            longCall = null
        }
    }

    override suspend fun history(connectionId: String): CallHistory {
        val list = if (supported == true) {
            try {
                vault.gateway.list(HISTORY_LIMIT)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                null
            }
        } else {
            null
        }
        list?.let(::noteSkipped)
        return historyOf(list, connectionId, now())
    }

    companion object {
        const val HISTORY_LIMIT = 200

        /** (C2) The calls permission was asked for while the vault is locked or has none. */
        const val CODE_NO_PERMISSION = "calls_permission_unavailable"

        /** How many skipped call ids [CallsDiagnostics.listSkippedIds] keeps. */
        const val SKIPPED_IDS_KEPT = 20

        /**
         * (GC #7718) The conversation's calls of [list]: the entries that read, and whether some could not be shown (one
         * skipped for this connection, or whose connection did not read, or one of its own without a readable start).
         */
        internal fun historyOf(list: CallList?, connectionId: String, now: Instant): CallHistory {
            val mine = list?.calls.orEmpty().filter { it.connectionId == connectionId }
            val entries = mine.mapNotNull { entry(it, now) }
            val skipped = list?.skipped.orEmpty().any { it.connectionId == null || it.connectionId == connectionId }
            return CallHistory(entries, someNotShown = skipped || entries.size < mine.size)
        }
        val MAX_CALL: Duration = Duration.ofHours(12)

        /**
         * A `ringing` record older than this is not ringing any more. Two minutes: an offer lives 45 s (90 s is the
         * most a vault accepts), and the record's time is the vault's while "now" is this phone's, so the rest is
         * margin for their clocks and for a hang-up on its way. Erring long only shows a finished call as going on
         * for a moment; erring short would end a call that is still ringing.
         */
        val RING_STALE: Duration = Duration.ofMinutes(2)
        private val PROBE_RETRY: Duration = Duration.ofSeconds(2)
        private const val PROBE_ATTEMPTS = 3
        private const val UNSUPPORTED = "unsupported_type"
        private const val SIGNER_DEVELOPMENT = "development"

        /** How long an offer waits for an unlock that is just completing before it is dropped. */
        val UNLOCK_WAIT: Duration = Duration.ofSeconds(10)
        private const val STATE_RINGING = "ringing"
        private const val STATE_ACTIVE = "active"
        private const val DIRECTION_OUT = "out"

        private fun time(s: String?): Instant? = s?.let { runCatching { Instant.parse(it) }.getOrNull() }

        /** One record of `call.list` as the conversation shows it; null for a record without a readable start. */
        internal fun entry(r: CallRecord, now: Instant): CallHistoryEntry? {
            val started = time(r.startedAt) ?: return null
            val outgoing = r.direction == DIRECTION_OUT
            val answered = time(r.answeredAt)
            val ended = time(r.endedAt)
            val duration = if (answered != null && ended != null) Duration.between(answered, ended) else null
            val outcome = when {
                duration != null -> CallOutcome.TALKED
                answered != null -> CallOutcome.ONGOING
                r.state == STATE_RINGING -> unanswered(outgoing, Duration.between(started, now))
                outgoing -> OUT[r.reason] ?: CallOutcome.FAILED
                else -> IN[r.reason] ?: CallOutcome.MISSED
            }
            return CallHistoryEntry(r.callId, r.connectionId, outgoing, outcome, started, duration)
        }

        /** A record still `ringing` (the vault of S10 never times one out by itself): ringing now, or long over. */
        private fun unanswered(outgoing: Boolean, age: Duration): CallOutcome = when {
            age <= RING_STALE -> CallOutcome.ONGOING
            outgoing -> CallOutcome.NOT_ANSWERED
            else -> CallOutcome.MISSED
        }

        private val OUT = mapOf(
            CallReason.DECLINE to CallOutcome.DECLINED,
            CallReason.BUSY to CallOutcome.BUSY,
            CallReason.TIMEOUT to CallOutcome.NOT_ANSWERED,
            CallReason.HANGUP to CallOutcome.NOT_ANSWERED,
        )
        private val IN = mapOf(
            CallReason.DECLINE to CallOutcome.DECLINED_HERE,
            CallReason.ANSWERED_ELSEWHERE to CallOutcome.ANSWERED_ELSEWHERE,
            CallReason.FAILED to CallOutcome.FAILED,
        )
    }
}
