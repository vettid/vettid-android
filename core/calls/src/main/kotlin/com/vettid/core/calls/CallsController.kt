package com.vettid.core.calls

import com.vettid.core.data.calls.CallAccess
import com.vettid.core.data.calls.CallAvailability
import com.vettid.core.data.calls.CallHistoryEntry
import com.vettid.core.data.calls.CallOutcome
import com.vettid.core.vault.CallReason
import com.vettid.core.vault.CallRecord
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** What the calls need of the app's vault; the app's wiring gives it from `VaultManager`. */
interface CallVault {
    /** True while the vault is open on this phone. */
    val unlocked: Flow<Boolean>

    val gateway: CallGateway

    /** The fingerprint of [connectionId]'s pinned identity key, as the connection's details show it; null if unknown. */
    suspend fun fingerprint(connectionId: String): String?

    /** A name to show for [connectionId]; null if unknown. */
    fun displayName(connectionId: String): String?

    /** The types of the vault's messages this phone dropped for their time (a wrong clock shows here). */
    val droppedForTime: Flow<String>
}

/** What is known beyond one call, for the diagnostics view and the phone tests. Counts only. */
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
)

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
    /** Whether this is a debuggable build: the only kind that may ask for [MediaConfig.dtlsPqcTrial]. */
    debuggable: Boolean = false,
    private val now: () -> Instant = Instant::now,
    timings: CallTimings = CallTimings(),
) : CallAccess, CallServiceHost {
    init {
        // Owner decision 2026-10-12: known-good algorithms only; the experimental trial is for measurement in
        // debug builds.
        check(!config.dtlsPqcTrial || debuggable) { "the post-quantum DTLS trial is for debug builds only" }
    }

    private val signalling = VaultCallSignalling(vault.gateway, vault::fingerprint, engines.suites)
    private val machine = CallMachine(signalling, engines, scope, now, timings = timings, config = config)

    /** The call, for the call screens. */
    override val state: StateFlow<CallState> = machine.state
    val callDiagnostics: StateFlow<CallDiagnostics> = machine.diagnostics
    val audioState: StateFlow<AudioState> = audio.state

    private val availabilityFlow = MutableStateFlow(CallAvailability.UNKNOWN)
    override val availability: StateFlow<CallAvailability> = availabilityFlow.asStateFlow()

    private val diagnosticsFlow = MutableStateFlow(CallsDiagnostics())
    val diagnostics: StateFlow<CallsDiagnostics> = diagnosticsFlow.asStateFlow()

    private val microphoneFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** A call or an answer was held back for the microphone permission: ask for it, then call [call] or [answer] again. */
    val microphoneNeeded: SharedFlow<Unit> = microphoneFlow.asSharedFlow()

    /** Whether the vault's release has calls; null until it has been asked since the last unlock. */
    private var supported: Boolean? = null
    private var longCall: Job? = null

    init {
        scope.launch { vault.unlocked.collectLatest { open -> if (open) opened() else locked() } }
        scope.launch { machine.state.collect { around(it) } }
        scope.launch { signalling.refused.collect { n -> diagnosticsFlow.update { it.copy(refused = n) } } }
        scope.launch {
            vault.droppedForTime.collect { type ->
                if (type.startsWith("call.")) diagnosticsFlow.update { it.copy(droppedForTime = it.droppedForTime + 1) }
            }
        }
    }

    // --- the member ---

    override fun call(connectionId: String) {
        if (availabilityFlow.value != CallAvailability.READY) return
        if (microphoneGranted()) machine.call(connectionId) else microphoneFlow.tryEmit(Unit)
    }

    fun answer() {
        if (microphoneGranted()) machine.answer() else microphoneFlow.tryEmit(Unit)
    }

    fun decline() = machine.decline()

    override fun hangUp() = machine.hangUp()

    fun setMuted(muted: Boolean) = machine.setMuted(muted)

    fun setRoute(route: AudioRoute) = audio.setRoute(route)

    fun dismiss() = machine.dismiss()

    override fun displayName(connectionId: String): String? = vault.displayName(connectionId)

    // --- the vault ---

    @Suppress("ReturnCount")
    private suspend fun opened() {
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
            supported = true
            publish()
            endStale(records)
            return
        }
    }

    /**
     * Calls the vault still holds live while this app has none: left by a run of the app that ended during a call.
     * Ended there, or the vault answers every call `busy` for up to 12 hours (CALLS-PLAN §6.1 V3). A ringing call
     * is left alone while it could still be ringing.
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

    private fun locked() {
        supported = null
        machine.vaultLocking()
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

    override suspend fun history(connectionId: String): List<CallHistoryEntry> {
        val records = if (supported == true) runCatching { vault.gateway.list(HISTORY_LIMIT) }.getOrNull() else null
        val t = now()
        return records.orEmpty().filter { it.connectionId == connectionId }.mapNotNull { entry(it, t) }
    }

    companion object {
        const val HISTORY_LIMIT = 200
        val MAX_CALL: Duration = Duration.ofHours(12)

        /** A `ringing` record older than this is not ringing any more (an offer lives 45 s, 90 at most). */
        val RING_STALE: Duration = Duration.ofMinutes(2)
        private val PROBE_RETRY: Duration = Duration.ofSeconds(2)
        private const val PROBE_ATTEMPTS = 3
        private const val UNSUPPORTED = "unsupported_type"
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
