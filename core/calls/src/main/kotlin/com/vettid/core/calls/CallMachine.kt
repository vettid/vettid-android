package com.vettid.core.calls

import com.vettid.core.vault.CallCandidate
import com.vettid.core.vault.CallLimits
import com.vettid.core.vault.CallReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/**
 * One call at a time, from the tap to the hang-up (CALLS-PLAN 0.1.1 §2, phase C1: voice, app open, the vault of
 * staging release 10 as it is). It speaks to the vault through [CallSignalling] and to the media stack through
 * [MediaEngine]; it holds no key (keys go from one to the other and are wiped there) and logs nothing.
 *
 * Every input (the member's taps, the vault's events, the engine's events, timers) goes through one queue and is
 * handled in order by one coroutine, which alone changes [state]. What takes time (the engine, the vault) runs in
 * the call's own jobs and reports back through the queue; a report for a call that is over is dropped.
 *
 * What the vault does not do at S10 and this does for it:
 * - the **caller** sends `call.end{timeout}` when the offer expires; the **callee** only stops ringing (if it sent
 *   the timeout itself, its vault would end the call first and never record the missed call);
 * - an address that cannot be gathered, a path that is not found and frames that do not decrypt each end the call
 *   with their own reason instead of leaving it waiting.
 */
@Suppress("TooManyFunctions", "LargeClass")
class CallMachine(
    private val signalling: CallSignalling,
    private val engines: MediaEngineFactory,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
    /** A clock that only moves forward, in milliseconds (for the measurements). */
    private val elapsedMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val timings: CallTimings = CallTimings(),
    private val config: MediaConfig = MediaConfig(),
) {
    private val stateFlow = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = stateFlow.asStateFlow()

    private val diagnosticsFlow = MutableStateFlow(CallDiagnostics())

    /** Measurements of the current (or last) call. */
    val diagnostics: StateFlow<CallDiagnostics> = diagnosticsFlow.asStateFlow()

    private val inbox = Channel<Msg>(Channel.UNLIMITED)
    private var session: Session? = null
    private var nextSeq = 0

    /** Outgoing calls the member hung up before the vault had answered `call.start`: ended as soon as it does. */
    private val abandoned = mutableSetOf<Int>()

    private sealed interface Msg {
        data class Call(val connectionId: String) : Msg
        data object Answer : Msg
        data object Decline : Msg
        data class HangUp(val reason: CallEnd) : Msg
        data class Mute(val muted: Boolean) : Msg
        data object Dismiss : Msg
        class Signal(val event: SignalEvent) : Msg
        class Media(val seq: Int, val event: MediaEvent) : Msg
        class Placed(val seq: Int, val call: PlacedCall, val atMs: Long) : Msg
        class Answered(val seq: Int) : Msg
        class RemoteApplied(val seq: Int) : Msg
        class Gathering(val seq: Int) : Msg
        class Failed(val seq: Int, val reason: CallEnd) : Msg
        class Timer(val seq: Int, val kind: TimerKind) : Msg
    }

    private enum class TimerKind { RING_OUT, RING_IN, GATHER, CONNECT, ICE_FLUSH }

    /** One call's working state. Touched only by the loop, except [lane], which is a queue. */
    private class Session(
        val seq: Int,
        val connectionId: String,
        val direction: CallDirection,
        val job: Job,
        val tappedAtMs: Long,
    ) {
        var engine: MediaEngine? = null
        var callId: String? = null
        var phase: CallPhase = CallPhase.STARTING
        var offer: SignalEvent.Offer? = null
        var muted = false
        var activeSince: Instant? = null
        var reconnecting = false
        var keysSet = false
        var remoteApplied = false
        var canSendIce = false
        var gathered = 0
        var iceMessages = 0
        var flushScheduled = false
        var answeredAtMs: Long? = null

        /** The answer's keys until the engine has them (it wipes them); wiped here if the call ends first. */
        var keys: MediaKeys? = null

        /**
         * What the vault passed on for this call before its answer to `call.start` was handled here (the answer and
         * the events come through two queues: a `call.end{busy}` or even a `call.answer` can be first). Held while
         * the call has no id, and handled in order once it has.
         */
        val early = mutableListOf<SignalEvent>()
        val pendingRemote = mutableListOf<CallCandidate>()
        val pendingLocal = ArrayDeque<CallCandidate>()
        val timers = mutableMapOf<TimerKind, Job>()

        /** The engine's work, in order. */
        val lane = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    }

    init {
        scope.launch { for (m in inbox) handle(m) }
        scope.launch { signalling.events.collect { inbox.send(Msg.Signal(it)) } }
    }

    // --- the member ---

    /** Calls [connectionId]. Ignored while a call is live. */
    fun call(connectionId: String) = post(Msg.Call(connectionId))

    /** Answers the ringing call. */
    fun answer() = post(Msg.Answer)

    /** Declines the ringing call. */
    fun decline() = post(Msg.Decline)

    /** Ends the call in any phase (a ringing incoming call is declined). */
    fun hangUp() = post(Msg.HangUp(CallEnd.HUNG_UP))

    /** The vault is being locked: a live call ends. */
    fun vaultLocking() = post(Msg.HangUp(CallEnd.LOCKED))

    fun setMuted(muted: Boolean) = post(Msg.Mute(muted))

    /** Clears an ended call. */
    fun dismiss() = post(Msg.Dismiss)

    private fun post(m: Msg) {
        inbox.trySend(m)
    }

    // --- the loop ---

    @Suppress("CyclomaticComplexMethod")
    private fun handle(m: Msg) {
        val s = session
        when (m) {
            is Msg.Call -> if (s == null) startOutgoing(m.connectionId)
            Msg.Answer -> if (s != null && s.phase == CallPhase.INCOMING) answerIncoming(s)
            Msg.Decline -> if (s != null && s.phase == CallPhase.INCOMING) finish(s, CallEnd.DECLINED_HERE, CallReason.DECLINE)
            is Msg.HangUp -> if (s != null) hangUp(s, m.reason)
            is Msg.Mute -> if (s != null) {
                s.muted = m.muted
                s.engine?.setMuted(m.muted)
                publish(s)
            }
            Msg.Dismiss -> if (s == null) stateFlow.value = CallState.Idle
            is Msg.Signal -> if (!heldEarly(s, m.event)) onSignal(s, m.event)
            is Msg.Placed -> onPlaced(s, m)
            is Msg.Media -> if (s != null && s.seq == m.seq) onMedia(s, m.event)
            is Msg.Answered -> if (s != null && s.seq == m.seq) onAnswered(s)
            is Msg.RemoteApplied -> if (s != null && s.seq == m.seq) s.remoteApplied = true
            is Msg.Gathering -> if (s != null && s.seq == m.seq && s.gathered == 0) timer(s, TimerKind.GATHER, timings.gather)
            is Msg.Failed -> if (s != null && s.seq == m.seq) finish(s, m.reason, CallReason.FAILED)
            is Msg.Timer -> if (s != null && s.seq == m.seq) onTimer(s, m.kind)
        }
    }

    /** Keeps [e] for a call that has no id yet (see [Session.early]); true if it was kept. */
    private fun heldEarly(s: Session?, e: SignalEvent): Boolean {
        val waiting = s != null && s.callId == null && s.early.size < MAX_EARLY
        if (!waiting || e is SignalEvent.Offer) return false
        s?.early?.add(e)
        return true
    }

    private fun newSession(connectionId: String, direction: CallDirection): Session {
        val s = Session(nextSeq++, connectionId, direction, SupervisorJob(scope.coroutineContext.job), elapsedMs())
        session = s
        diagnosticsFlow.value = CallDiagnostics()
        val lane = CoroutineScope(scope.coroutineContext + s.job)
        lane.launch {
            for (work in s.lane) {
                try {
                    work()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: CallSecurityException) {
                    post(Msg.Failed(s.seq, CallEnd.NOT_ENCRYPTED))
                } catch (_: MediaLoggingException) {
                    post(Msg.Failed(s.seq, CallEnd.NOT_ENCRYPTED))
                } catch (_: MediaNotEncryptedException) {
                    post(Msg.Failed(s.seq, CallEnd.NOT_ENCRYPTED))
                } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                    // The engine or the relay failed; what failed is in the diagnostics of the engine, never in a log.
                    post(Msg.Failed(s.seq, CallEnd.FAILED))
                }
            }
        }
        return s
    }

    private fun Session.launch(block: suspend () -> Unit): Job = CoroutineScope(scope.coroutineContext + job).launch { block() }

    /** Creates the call's engine and listens to it. False (and the call ended) if the media stack cannot be used. */
    private fun openEngine(s: Session): Boolean {
        val (engine, failure) = try {
            engines.create(config).let { it to if (it.suites.isEmpty()) CallEnd.NOT_ENCRYPTED else null }
        } catch (_: MediaLoggingException) {
            null to CallEnd.NOT_ENCRYPTED
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            null to CallEnd.FAILED
        }
        s.engine = engine
        if (engine == null || failure != null) {
            finish(s, failure ?: CallEnd.FAILED, null)
            return false
        }
        s.launch { engine.events.collect { inbox.send(Msg.Media(s.seq, it)) } }
        return true
    }

    private fun startOutgoing(connectionId: String) {
        val s = newSession(connectionId, CallDirection.OUTGOING)
        publish(s)
        if (!openEngine(s)) return
        val engine = s.engine ?: return
        s.lane.trySend {
            val sdp = engine.createOffer()
            // Not cancelled by a hang-up: once `call.start` may have left, its answer is needed to end the call.
            withContext(NonCancellable) {
                try {
                    val placed = signalling.start(connectionId, sdp, engine.suites)
                    post(Msg.Placed(s.seq, placed, elapsedMs()))
                } catch (e: CallRefusedException) {
                    post(Msg.Failed(s.seq, refusal(e.code)))
                }
            }
        }
    }

    private fun refusal(code: String): CallEnd = when (code) {
        "busy" -> CallEnd.OWN_VAULT_BUSY
        "not_found", "connection_unavailable" -> CallEnd.UNAVAILABLE
        "owner_check_required" -> CallEnd.OWNER_CHECK_REQUIRED
        "unsupported_type" -> CallEnd.NOT_SUPPORTED
        else -> CallEnd.FAILED
    }

    private fun onPlaced(s: Session?, m: Msg.Placed) {
        if (s == null || s.seq != m.seq) {
            // The member hung up while the vault was answering: the call exists there, so it is ended there.
            if (abandoned.remove(m.seq)) sendEnd(m.call.callId, CallReason.HANGUP)
            return
        }
        val early = s.early.toList()
        s.early.clear()
        s.callId = m.call.callId
        s.phase = CallPhase.CALLING
        s.canSendIce = true
        diagnosticsFlow.update { it.copy(tapToPlacedMs = m.atMs - s.tappedAtMs) }
        val engine = s.engine ?: return
        s.lane.trySend { engine.startGathering(m.call.servers) }
        timer(s, TimerKind.GATHER, timings.gather)
        val ring = Duration.between(now(), m.call.exp).coerceIn(timings.minRing, timings.maxRing)
        timer(s, TimerKind.RING_OUT, ring)
        publish(s)
        // What arrived before this, in its order; each is checked against the call's id as usual.
        for (e in early) if (session === s) onSignal(s, e) else (e as? SignalEvent.Answer)?.keys?.wipe()
    }

    @Suppress("ReturnCount")
    private fun answerIncoming(s: Session) {
        val offer = s.offer ?: return
        cancelTimer(s, TimerKind.RING_IN)
        s.phase = CallPhase.CONNECTING
        s.answeredAtMs = elapsedMs()
        publish(s)
        if (!openEngine(s)) {
            sendEnd(offer.callId, CallReason.FAILED)
            return
        }
        val engine = s.engine ?: return
        // The callee gathers no address before the member answers (CALLS-PLAN §2 step 6).
        s.lane.trySend {
            val sdp = engine.acceptOffer(offer.sdp, offer.servers)
            post(Msg.Gathering(s.seq))
            val keys = signalling.answer(offer.callId, sdp, engine.suites)
            try {
                engine.setKeys(keys.suite, keys.send, keys.receive)
            } finally {
                keys.wipe()
            }
            post(Msg.Answered(s.seq))
        }
        applyRemote(s)
    }

    private fun onAnswered(s: Session) {
        s.keysSet = true
        s.canSendIce = true
        timer(s, TimerKind.CONNECT, timings.connect)
        scheduleFlush(s, timings.iceBatch)
        startDiagnostics(s)
    }

    /** The other device's SDP is applied in the lane before this runs there: its candidates may follow. */
    private fun applyRemote(s: Session) {
        val engine = s.engine ?: return
        val waiting = s.pendingRemote.toList()
        s.pendingRemote.clear()
        s.lane.trySend {
            if (waiting.isNotEmpty()) engine.addRemoteCandidates(waiting)
            post(Msg.RemoteApplied(s.seq))
        }
    }

    private fun hangUp(s: Session, reason: CallEnd) {
        when {
            s.phase == CallPhase.INCOMING -> finish(s, if (reason == CallEnd.HUNG_UP) CallEnd.DECLINED_HERE else reason, CallReason.DECLINE)
            s.callId == null -> finish(s, reason, null)
            else -> finish(s, reason, CallReason.HANGUP)
        }
    }

    // --- the vault ---

    @Suppress("CyclomaticComplexMethod")
    private fun onSignal(s: Session?, e: SignalEvent) {
        when (e) {
            is SignalEvent.Offer -> if (s == null) ring(e)
            is SignalEvent.Ringing -> if (s != null && s.callId == e.callId && s.phase == CallPhase.CALLING) {
                s.phase = CallPhase.RINGING
                diagnosticsFlow.update { it.copy(tapToRingingMs = elapsedMs() - s.tappedAtMs) }
                publish(s)
            }
            is SignalEvent.Answer -> {
                val ringing = s != null && s.callId == e.callId && s.direction == CallDirection.OUTGOING &&
                    (s.phase == CallPhase.CALLING || s.phase == CallPhase.RINGING)
                if (s != null && ringing) takeAnswer(s, e) else e.keys.wipe()
            }
            is SignalEvent.AnswerRejected -> if (s != null && s.callId == e.callId && s.direction == CallDirection.OUTGOING) {
                finish(s, CallEnd.NOT_ENCRYPTED, CallReason.FAILED)
            }
            is SignalEvent.Ice -> if (s != null && s.callId == e.callId) {
                val engine = s.engine
                if (s.remoteApplied && engine != null) {
                    s.lane.trySend { engine.addRemoteCandidates(e.candidates) }
                } else if (s.pendingRemote.size + e.candidates.size <= MAX_PENDING_CANDIDATES) {
                    s.pendingRemote += e.candidates
                }
            }
            is SignalEvent.End -> if (s != null && s.callId == e.callId) finish(s, ended(s, e.reason), null)
        }
    }

    private fun ring(offer: SignalEvent.Offer) {
        val s = newSession(offer.connectionId, CallDirection.INCOMING)
        s.callId = offer.callId
        s.offer = offer
        s.phase = CallPhase.INCOMING
        publish(s)
        s.launch { runCatching { signalling.ringing(offer.callId) } }
        // The caller's `call.end{timeout}` normally ends the ringing; this is for a caller that has gone.
        val ringFor = Duration.between(now(), offer.exp).plus(timings.incomingGrace)
            .coerceIn(timings.incomingGrace, timings.maxIncomingRing)
        timer(s, TimerKind.RING_IN, ringFor)
    }

    private fun takeAnswer(s: Session, e: SignalEvent.Answer) {
        val engine = s.engine
        if (engine == null) {
            e.keys.wipe()
            return
        }
        cancelTimer(s, TimerKind.RING_OUT)
        s.phase = CallPhase.CONNECTING
        s.answeredAtMs = elapsedMs()
        s.keysSet = true
        s.keys = e.keys
        // The keys first, then the answer: no frame is ever sent or played without them.
        s.lane.trySend {
            engine.setKeys(e.keys.suite, e.keys.send, e.keys.receive)
            engine.acceptAnswer(e.sdp)
        }
        applyRemote(s)
        timer(s, TimerKind.CONNECT, timings.connect)
        startDiagnostics(s)
        publish(s)
    }

    /** What the other side's (or this member's other device's) `call.end` means here. */
    private fun ended(s: Session, reason: String): CallEnd = when (s.phase) {
        CallPhase.INCOMING -> RINGING_HERE[reason] ?: CallEnd.MISSED
        CallPhase.STARTING, CallPhase.CALLING, CallPhase.RINGING -> RINGING_THERE[reason] ?: CallEnd.FAILED
        CallPhase.CONNECTING, CallPhase.ACTIVE -> ANSWERED[reason] ?: CallEnd.PEER_HUNG_UP
    }

    // --- the media engine ---

    private fun onMedia(s: Session, e: MediaEvent) {
        when (e) {
            is MediaEvent.LocalCandidate -> {
                s.gathered++
                cancelTimer(s, TimerKind.GATHER)
                s.pendingLocal.addLast(e.candidate)
                scheduleFlush(s, timings.iceBatch)
            }
            is MediaEvent.GatheringDone -> if (e.found == 0 && s.gathered == 0) finish(s, CallEnd.NO_ADDRESS, CallReason.FAILED)
            is MediaEvent.Connection -> onConnection(s, e.state)
            is MediaEvent.Cryptor -> if (cryptorFailed(s, e)) finish(s, CallEnd.NOT_ENCRYPTED, CallReason.FAILED)
            is MediaEvent.Failed -> finish(s, CallEnd.FAILED, CallReason.FAILED)
        }
    }

    private fun onConnection(s: Session, state: MediaConnection) {
        when (state) {
            MediaConnection.CONNECTED -> if (s.phase == CallPhase.CONNECTING || s.phase == CallPhase.ACTIVE) connected(s)
            MediaConnection.DISCONNECTED -> if (s.phase == CallPhase.ACTIVE) {
                s.reconnecting = true
                publish(s)
            }
            MediaConnection.FAILED ->
                finish(s, if (s.activeSince != null) CallEnd.CONNECTION_LOST else CallEnd.COULD_NOT_CONNECT, CallReason.FAILED)
            MediaConnection.NEW, MediaConnection.CONNECTING, MediaConnection.CLOSED -> Unit
        }
    }

    private fun connected(s: Session) {
        cancelTimer(s, TimerKind.CONNECT)
        cancelTimer(s, TimerKind.GATHER)
        if (s.activeSince == null) {
            s.activeSince = now()
            s.answeredAtMs?.let { at -> diagnosticsFlow.update { it.copy(answerToMediaMs = elapsedMs() - at) } }
        }
        s.phase = CallPhase.ACTIVE
        s.reconnecting = false
        publish(s)
    }

    /**
     * Frames that do not decrypt are never played, and the call does not go on without them. A missing key is a
     * failure only once the keys have been set: before the answer the caller has none, and sends nothing.
     */
    private fun cryptorFailed(s: Session, e: MediaEvent.Cryptor): Boolean = when (e.state) {
        CryptorState.DECRYPTION_FAILED, CryptorState.ENCRYPTION_FAILED, CryptorState.INTERNAL_ERROR, CryptorState.UNKNOWN -> true
        CryptorState.MISSING_KEY -> s.keysSet && s.phase == CallPhase.ACTIVE
        CryptorState.NEW, CryptorState.OK -> false
    }

    // --- timers ---

    private fun timer(s: Session, kind: TimerKind, after: Duration) {
        s.timers.remove(kind)?.cancel()
        s.timers[kind] = s.launch {
            delay(after.toMillis())
            post(Msg.Timer(s.seq, kind))
        }
    }

    private fun cancelTimer(s: Session, kind: TimerKind) {
        s.timers.remove(kind)?.cancel()
    }

    private fun onTimer(s: Session, kind: TimerKind) {
        when (kind) {
            TimerKind.RING_OUT -> if (s.phase == CallPhase.CALLING || s.phase == CallPhase.RINGING) {
                finish(s, CallEnd.NO_ANSWER, CallReason.TIMEOUT)
            }
            // Nothing is sent: the missed call is recorded by this member's vault when the caller's timeout arrives.
            TimerKind.RING_IN -> if (s.phase == CallPhase.INCOMING) finish(s, CallEnd.MISSED, null)
            TimerKind.GATHER -> if (s.gathered == 0) finish(s, CallEnd.NO_ADDRESS, CallReason.FAILED)
            TimerKind.CONNECT -> if (s.phase == CallPhase.CONNECTING) finish(s, CallEnd.COULD_NOT_CONNECT, CallReason.FAILED)
            TimerKind.ICE_FLUSH -> flush(s)
        }
    }

    // --- this device's candidates ---

    private fun scheduleFlush(s: Session, after: Duration) {
        if (s.flushScheduled || !s.canSendIce || s.pendingLocal.isEmpty()) return
        s.flushScheduled = true
        timer(s, TimerKind.ICE_FLUSH, after)
    }

    /** Sends up to 16 candidates in one `call.ice`, then waits [CallTimings.iceInterval] before the next. */
    @Suppress("ReturnCount")
    private fun flush(s: Session) {
        s.flushScheduled = false
        val callId = s.callId ?: return
        if (!s.canSendIce || s.pendingLocal.isEmpty()) return
        if (s.iceMessages >= timings.maxIceMessages) {
            s.pendingLocal.clear()
            return
        }
        val batch = List(minOf(CallLimits.MAX_CANDIDATES, s.pendingLocal.size)) { s.pendingLocal.removeFirst() }
        s.iceMessages++
        // A candidate that could not be sent is lost, like any ephemeral message; the call's own timeouts cover it.
        s.launch { runCatching { signalling.ice(callId, batch) } }
        scheduleFlush(s, timings.iceInterval)
    }

    // --- state ---

    private fun startDiagnostics(s: Session) {
        val engine = s.engine ?: return
        s.launch {
            while (true) {
                delay(timings.diagnosticsEvery.toMillis())
                val media = runCatching { engine.diagnostics() }.getOrNull() ?: continue
                diagnosticsFlow.update { it.copy(media = media) }
            }
        }
    }

    private fun publish(s: Session) {
        stateFlow.value = CallState.Live(s.connectionId, s.direction, s.phase, s.callId, s.muted, s.activeSince, s.reconnecting)
    }

    /** Ends the call here; [tell] is the `call.end` reason for the vault, or null when nothing is to be sent. */
    private fun finish(s: Session, reason: CallEnd, tell: String?) {
        if (session !== s) return
        session = null
        s.lane.close()
        s.job.cancel()
        s.engine?.close()
        s.keys?.wipe()
        s.early.forEach { (it as? SignalEvent.Answer)?.keys?.wipe() }
        s.early.clear()
        s.pendingRemote.clear()
        s.pendingLocal.clear()
        val callId = s.callId
        if (tell != null && callId != null) sendEnd(callId, tell)
        if (s.direction == CallDirection.OUTGOING && callId == null) {
            // `call.start` may still be answered: the call it placed is ended when it is (onPlaced).
            abandoned.add(s.seq)
            if (abandoned.size > MAX_ABANDONED) abandoned.remove(abandoned.min())
        }
        val duration = s.activeSince?.let { Duration.between(it, now()) }
        stateFlow.value = CallState.Ended(s.connectionId, s.direction, reason, callId, duration)
    }

    /** `call.end` outlives the call: it is durable in the device's outbox once it has been handed over. */
    private fun sendEnd(callId: String, reason: String) {
        scope.launch { runCatching { signalling.end(callId, reason) } }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_PENDING_CANDIDATES = 256
        const val MAX_ABANDONED = 8
        const val MAX_EARLY = 64

        /** `call.end` reasons while an incoming call rings here; anything else is a missed call. */
        val RINGING_HERE = mapOf(CallReason.ANSWERED_ELSEWHERE to CallEnd.ANSWERED_ELSEWHERE, CallReason.DECLINE to CallEnd.DECLINED_HERE)

        /** While this device's call rings there; anything else is a failure. */
        val RINGING_THERE = mapOf(
            CallReason.DECLINE to CallEnd.DECLINED,
            CallReason.BUSY to CallEnd.BUSY,
            CallReason.TIMEOUT to CallEnd.NO_ANSWER,
            CallReason.UNAVAILABLE to CallEnd.UNAVAILABLE,
            CallReason.HANGUP to CallEnd.PEER_HUNG_UP,
        )

        /** After the answer; anything else is the other member hanging up. */
        val ANSWERED = mapOf(
            CallReason.UNAVAILABLE to CallEnd.UNAVAILABLE,
            CallReason.FAILED to CallEnd.FAILED,
            CallReason.ANSWERED_ELSEWHERE to CallEnd.ANSWERED_ELSEWHERE,
        )
    }
}
