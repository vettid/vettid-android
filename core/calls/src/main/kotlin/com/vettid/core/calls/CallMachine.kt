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
internal class CallMachine(
    private val signalling: CallSignalling,
    private val engines: MediaEngineFactory,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
    /** A clock that only moves forward, in milliseconds (for the measurements). */
    private val elapsedMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val timings: CallTimings = CallTimings(),
    /** Asked when a call's engine is made. */
    private val config: () -> MediaConfig = { MediaConfig() },
    /**
     * Whether the vault is open on this phone at this moment, read live each time the machine is about to start something
     * of a call (the controller gives the vault's phase as it is now). A lock makes it false before anything else is done
     * for the lock, so a call request, an offer or a glare's winner already waiting in the inbox when the lock begins is
     * refused when it is handled; an unlock makes it true again, with nothing to clear.
     */
    private val vaultOpen: () -> Boolean = { true },
) {
    private val stateFlow = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = stateFlow.asStateFlow()

    private val diagnosticsFlow = MutableStateFlow(CallDiagnostics())

    /** Measurements of the current (or last) call. */
    val diagnostics: StateFlow<CallDiagnostics> = diagnosticsFlow.asStateFlow()

    private val pathsFlow = MutableStateFlow(CallPaths())

    /** Each call that connected, counted once when it ends, by the last path the stack reported for it. */
    val paths: StateFlow<CallPaths> = pathsFlow.asStateFlow()

    private val restartsFlow = MutableStateFlow(0)

    /** How often the listening to the vault's call events had to be started again (expected: 0). */
    val listenRestarts: StateFlow<Int> = restartsFlow.asStateFlow()

    private val pendingEndsFlow = MutableStateFlow(0)

    /** `call.end` messages handed to the vault client and not yet with the relay. */
    val pendingEnds: StateFlow<Int> = pendingEndsFlow.asStateFlow()

    private val startsOutFlow = MutableStateFlow(0)

    /**
     * `call.start` requests sent and not yet handled: raised in the loop's step that lets the start go (the call still
     * current), lowered by a message posted after the answer's own, so a hang-up the answer needs (the call was ended
     * meanwhile) is already counted in [pendingEnds] when this goes down. A lock waits for both.
     */
    val startsOut: StateFlow<Int> = startsOutFlow.asStateFlow()

    private val inbox = Channel<Msg>(Channel.UNLIMITED)
    private var session: Session? = null

    /** The current session's seq ([NO_SEQ] without one), readable off the loop: a start asks whether its call is still wanted. */
    private val liveSeq = java.util.concurrent.atomic.AtomicInteger(NO_SEQ)
    private var nextSeq = 0

    /**
     * Glare winners that ended while this device's lost call waited for them: an offer for one of them that still
     * arrives (late, or delivered again) is not rung or answered. A few ids, memory only.
     */
    private val endedWinners = ArrayDeque<String>()

    /**
     * Other calls known to have ended here: an end for a call that is neither the session's nor its held offer's (a call
     * turned away as busy, or one whose offer has not come yet: VAULT-MESSAGING §8.4 gives no order), the ends held while a
     * start was out (when the call ends before its id, and those a replay leaves), and incoming calls that rang here and
     * ended. An offer for one of them that still arrives is not rung or answered. Kept apart from [endedWinners] so that many
     * of them (a mailbox replayed after a reconnect) cannot push a glare's ended winner out. An end that arrives with no
     * session at all is remembered too (its offer may still come), so a replay of more than 32 old ends (a reconnect) can
     * push out an incoming call remembered here; and a start's own end remembered here (the held events full) can be
     * pushed out before the start is answered by about as many more, which then leaves the call to its usual end. Both
     * lists are memory only, and kept across a lock on purpose: a call that ended before a lock is still ended after it.
     * They are forgotten, with [endedOtherEnds], when the phone leaves its vault ([vaultChanged]): the next vault opened
     * here may be another one.
     */
    private val endedOthers = ArrayDeque<String>()

    /**
     * An end remembered among [endedOthers] while a start had no id (its own end may be one of them, when the held events
     * were full): if it is, its reason is what the placed call shows, and a glare's end (`superseded_by`) counts for the
     * winners' rule as a held one does ([onPlaced]). Kept with its id, gone with it.
     */
    private val endedOtherEnds = HashMap<String, SignalEvent.End>()

    private sealed interface Msg {
        data class Call(val connectionId: String) : Msg
        data object Answer : Msg
        data object Decline : Msg
        data object StopRinging : Msg
        data class HangUp(val reason: CallEnd) : Msg
        data class Mute(val muted: Boolean) : Msg
        data object Dismiss : Msg

        /** The phone left its vault: see [vaultChanged]. */
        data object VaultChanged : Msg
        class Signal(val event: SignalEvent) : Msg
        class Media(val seq: Int, val event: MediaEvent) : Msg
        class Placed(val seq: Int, val call: PlacedCall, val atMs: Long) : Msg

        /** The offer for an outgoing call is made: `call.start` goes only if the call is still the current one. */
        class Offered(
            val seq: Int,
            val connectionId: String,
            val sdp: String,
            val suites: List<com.vettid.core.crypto.call.MediaSuite>,
        ) : Msg

        /** A `call.start` sent by [Offered] is done with (after its [Placed] or [Failed]). */
        data object StartDone : Msg
        class Answered(val seq: Int) : Msg
        class RemoteApplied(val seq: Int) : Msg
        class Gathering(val seq: Int) : Msg
        class Failed(val seq: Int, val reason: CallEnd) : Msg
        class Timer(val seq: Int, val kind: TimerKind) : Msg
        class Path(val seq: Int, val relayed: Boolean) : Msg
    }

    private enum class TimerKind { RING_OUT, RING_IN, GATHER, CONNECT, DECRYPT, ICE_FLUSH }

    /** One call's working state. Touched only by the loop, except [lane], which is a queue. */
    private class Session(
        val seq: Int,
        val connectionId: String,
        val direction: CallDirection,
        val job: Job,
        val tappedAtMs: Long,
    ) {
        @Volatile
        var engine: MediaEngine? = null
        var callId: String? = null
        var phase: CallPhase = CallPhase.STARTING
        var offer: SignalEvent.Offer? = null
        var muted = false
        var activeSince: Instant? = null
        var reconnecting = false
        var keysSet = false

        /** (C2) The answering device's outgoing track is held until its receiver first decrypts a frame. */
        var heldForDecrypt = false

        /** (C2) The call is in form 2: placed in form 2, or answering a form 2 offer. */
        var formTwo = false
        var remoteApplied = false
        var canSendIce = false
        var gathered = 0
        var iceMessages = 0
        var flushScheduled = false
        var answeredAtMs: Long? = null

        /**
         * (C2, VAULT-MESSAGING §10.10 "Glare") This outgoing call lost a glare to the connection's call with this id:
         * the vault ended it (nothing more is sent for it) and rings for that call next, which this device answers
         * without asking as soon as its offer is here ([glareOffer]); until then the call screen stays as it was, and
         * the ring timer still bounds the wait (then it shows busy, as before C2).
         */
        var supersededBy: String? = null

        /**
         * This member ended the call here (a hang-up, a decline, a lock): the connection's held offer is then not rung,
         * since a call rings only while the vault is open and the member has just said no.
         */
        var memberEnded = false

        /** The connection's own offer that arrived while this outgoing call rang to it (a glare's winner, perhaps). */
        var glareOffer: SignalEvent.Offer? = null

        /**
         * Candidates of the call this one lost a glare to, or of its held offer, that came while this call was the
         * session: handed to that call when it is answered ([takeGlare]) or rung ([finish]). Bounded as [pendingRemote].
         */
        val glareIce = mutableListOf<CallCandidate>()

        /** The call [glareIce] is for: candidates are kept for one call at a time and handed only to that call. */
        var glareIceFor: String? = null

        /** The last path the stack reported for this call (relayed or direct); null while it has said nothing. */
        var relayed: Boolean? = null

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
        scope.launch {
            // The vault's events are listened to for as long as the process lives: whatever ends the collection
            // (it should be nothing: the signalling refuses and counts what goes wrong), it starts again.
            while (true) {
                try {
                    signalling.events.collect { inbox.send(Msg.Signal(it)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                    restartsFlow.update { it + 1 }
                }
                delay(timings.relisten.toMillis())
            }
        }
    }

    // --- the member ---

    /** The vault is locking or locked on this phone ([vaultOpen] false): nothing of a call starts. */
    private val closed: Boolean get() = !vaultOpen()

    /** Calls [connectionId]. Ignored while a call is live, and while the vault is locking or locked ([closed]). */
    fun call(connectionId: String) = post(Msg.Call(connectionId))

    /** Answers the ringing call. */
    fun answer() = post(Msg.Answer)

    /** Declines the ringing call. */
    fun decline() = post(Msg.Decline)

    /**
     * Stops the ringing call on this phone only, as its ring timer does: nothing is sent, so the member's other devices
     * may still answer, and the call is shown as missed here.
     */
    fun stopRingingHere() = post(Msg.StopRinging)

    /** Ends the call in any phase (a ringing incoming call is declined). */
    fun hangUp() = post(Msg.HangUp(CallEnd.HUNG_UP))

    /**
     * The vault is being locked: a live call ends. Whatever is still waiting in the inbox starts nothing, since the vault is
     * no longer open ([closed]).
     */
    fun vaultLocking() = post(Msg.HangUp(CallEnd.LOCKED))

    /**
     * The phone left the vault it was set up with (a sign-out, a wipe, the start of a new setup): a live call ends as at a
     * lock, and the calls known to have ended ([endedWinners], [endedOthers], [endedOtherEnds]) are forgotten, in this
     * order, so that the next vault opened here, which may be another one, starts with none of this one's. A lock keeps
     * them. A late event of the old vault (still in the inbox, or the echo of the end just sent) can put back a few of its
     * call ids afterwards; call ids are ULIDs, so another vault never shares them, and they cost only memory.
     */
    fun vaultChanged() = post(Msg.VaultChanged)

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
            is Msg.Call -> if (s == null && !closed) startOutgoing(m.connectionId)
            // Not answered while the vault is not open: the lock's hang-up behind this (the hook's, or the one the
            // controller posts at every close, a lock an unlock overtook included) declines the call.
            Msg.Answer -> if (s != null && s.phase == CallPhase.INCOMING && !closed) answerIncoming(s)
            Msg.StopRinging -> if (s != null && s.phase == CallPhase.INCOMING) finish(s, CallEnd.MISSED, null)
            Msg.Decline -> if (s != null && s.phase == CallPhase.INCOMING) {
                s.memberEnded = true
                finish(s, CallEnd.DECLINED_HERE, CallReason.DECLINE)
            }
            is Msg.HangUp -> if (s != null) hangUp(s, m.reason)
            is Msg.Mute -> if (s != null) {
                s.muted = m.muted
                s.engine?.setMuted(m.muted)
                publish(s)
            }
            Msg.Dismiss -> if (s == null) stateFlow.value = CallState.Idle
            Msg.VaultChanged -> {
                if (s != null) hangUp(s, CallEnd.LOCKED)
                endedWinners.clear()
                endedOthers.clear()
                endedOtherEnds.clear()
            }
            is Msg.Signal -> if (!heldEarly(s, m.event)) onSignal(s, m.event)
            is Msg.Placed -> onPlaced(s, m)
            is Msg.Offered -> onOffered(s, m)
            Msg.StartDone -> startsOutFlow.update { (it - 1).coerceAtLeast(0) }
            // A call that lost a glare has let its media go: what its engine still reports is of no use.
            is Msg.Media -> if (s != null && s.seq == m.seq && s.supersededBy == null) onMedia(s, m.event)
            is Msg.Answered -> if (s != null && s.seq == m.seq) onAnswered(s)
            is Msg.RemoteApplied -> if (s != null && s.seq == m.seq) onRemoteApplied(s)
            is Msg.Gathering -> if (s != null && s.seq == m.seq && s.gathered == 0) timer(s, TimerKind.GATHER, timings.gather)
            is Msg.Failed -> if (s != null && s.seq == m.seq && s.supersededBy == null) finish(s, m.reason, CallReason.FAILED)
            is Msg.Timer -> if (s != null && s.seq == m.seq) onTimer(s, m.kind)
            is Msg.Path -> if (s != null && s.seq == m.seq) s.relayed = m.relayed
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
        liveSeq.set(s.seq)
        // However the call's jobs end (also when the machine's scope goes away with a call live), the engine is
        // closed: the microphone and the keys do not outlive the call.
        s.job.invokeOnCompletion { s.engine?.close() }
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
            engines.create(config()).let { it to if (it.suites.isEmpty()) CallEnd.NOT_ENCRYPTED else null }
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

    @Suppress("ReturnCount")
    private fun startOutgoing(connectionId: String) {
        val s = newSession(connectionId, CallDirection.OUTGOING)
        publish(s)
        if (!openEngine(s)) return
        val engine = s.engine ?: return
        // What this start offers (form 1: suite 1). The engine's cryptors are made for its first suite, the only
        // one the call can use: an answer that names another is refused by the engine, never remade for.
        val offered = signalling.offerable(engine.suites)
        if (offered.isEmpty()) {
            finish(s, CallEnd.NOT_ENCRYPTED, null)
            return
        }
        s.lane.trySend {
            val sdp = engine.createOffer(offered.first())
            // The loop decides whether `call.start` goes: not for a call ended while its offer was made (a hang-up, a lock).
            post(Msg.Offered(s.seq, connectionId, sdp, offered))
        }
    }

    /**
     * The offer is made: `call.start` goes only while the call is still the current one, and is counted ([startsOut]) in
     * this same step, so a lock that ends the call from now on waits for its answer (and the hang-up that answer needs).
     */
    private fun onOffered(s: Session?, m: Msg.Offered) {
        // Ended while its offer was made: nothing was placed, so nothing is left to end when no answer comes.
        if (s == null || s.seq != m.seq) return
        // The vault closed while the offer was made: no `call.start` goes (the vault would refuse it during a lock), and the
        // call ends here, as the lock's hang-up would end it.
        if (closed) {
            endForLock(s)
            return
        }
        startsOutFlow.update { it + 1 }
        // In the machine's scope, not the call's, and not cancelled by a hang-up: once `call.start` may have left, its
        // answer is needed to end the call.
        scope.launch {
            withContext(NonCancellable) {
                try {
                    val placed = signalling.start(m.connectionId, m.sdp, m.suites) { liveSeq.get() == m.seq && !closed }
                    post(Msg.Placed(m.seq, placed, elapsedMs()))
                } catch (e: CallRefusedException) {
                    post(Msg.Failed(m.seq, refusal(e.code)))
                } catch (_: CallSecurityException) {
                    post(Msg.Failed(m.seq, CallEnd.NOT_ENCRYPTED))
                } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                    // The relay failed; what failed is never in a log.
                    post(Msg.Failed(m.seq, CallEnd.FAILED))
                } finally {
                    post(Msg.StartDone)
                }
            }
        }
    }

    private fun refusal(code: String): CallEnd = when (code) {
        "busy" -> CallEnd.OWN_VAULT_BUSY
        "calls_off" -> CallEnd.CALLS_OFF
        "not_found", "connection_unavailable" -> CallEnd.UNAVAILABLE
        "owner_check_required" -> CallEnd.OWNER_CHECK_REQUIRED
        else -> CallEnd.FAILED
    }

    @Suppress("ReturnCount") // the start no session shows, the call already ended, no engine
    private fun onPlaced(s: Session?, m: Msg.Placed) {
        if (s == null || s.seq != m.seq) {
            // No session shows the call (the member hung up, or a lock ended it, while the vault was answering): the
            // call exists there, so it is ended there, as hung up, however many such starts there were. Its key goes now.
            signalling.drop(m.call.callId)
            // Not when the vault ended it already: its end was held while the start was out and is remembered ([finish]).
            // Pushed out of that list by other ends, the hang-up goes all the same: a vault ignores a second end of a call.
            if (m.call.callId !in endedOthers) sendEnd(m.call.callId, CallReason.HANGUP)
            return
        }
        val early = s.early.toList()
        s.early.clear()
        s.callId = m.call.callId
        // An offer held before the call had its id, that turns out to carry this very id, is no other call.
        if (s.glareOffer?.callId == m.call.callId) s.glareOffer = null
        forgetEndedGlareOffer(s, early)
        // The vault's end for this very call came while it had no id and was not held (the held events full): the call
        // is over, nothing is sent for it, no address is gathered, and what was held goes where it belongs. That end is
        // the remembered one, not a held one, and counts for the winners' rule all the same.
        val atOnce = ownEndRemembered(m.call.callId, early)
        val carried = if (atOnce) endedOtherEnds[m.call.callId] else null
        val lostToEnded = rememberHeldWinners(early, m.call.callId, carried)
        if (atOnce) {
            endPlacedAtOnce(s, early, carried, lostToEnded)
            return
        }
        s.formTwo = m.call.formTwo
        s.phase = CallPhase.CALLING
        s.canSendIce = true
        diagnosticsFlow.update { it.copy(tapToPlacedMs = m.atMs - s.tappedAtMs) }
        val engine = s.engine ?: return
        // The vault's end for this very call may be among what arrived first: then no address is gathered for it (the
        // ICE servers are not asked with a dead call's credentials). The replay below ends it, or (a glare lost) lets its
        // media go and waits for the winner.
        if (early.none { it is SignalEvent.End && it.callId == m.call.callId }) {
            s.lane.trySend { engine.startGathering(m.call.servers) }
            timer(s, TimerKind.GATHER, timings.gather)
        }
        val ring = Duration.between(now(), m.call.exp).coerceIn(timings.minRing, timings.maxRing)
        timer(s, TimerKind.RING_OUT, ring)
        publish(s)
        // What arrived before this, in its order; each is checked against the call's id as usual. Once the replay has
        // finished the call, or handed the session to a glare's winner, the rest goes through [afterReplay].
        for (e in early) if (session === s) onSignal(s, e) else afterReplay(e)
    }

    /**
     * Before a replay: the winner of a glare this call [callId] lost, when the winner's own end is among the held events too
     * (or already known), is remembered among the winners first, so that the other calls' ends held with it cannot push it
     * out before the glare's end is applied. Only this call's own glare end counts, under the rule [superseded] applies
     * (`busy`, a lower winner): other calls' ends that name a winner do not fill the winners' list. On the at-once path
     * ([endPlacedAtOnce]) the glare's end is the one remembered while the start had no id ([carried]), not a held one: it
     * counts the same, so that a winner known to have ended only among [endedOthers] is not pushed out by the ends held
     * with the start. True when [carried] put its winner among the winners: the call lost a glare to a call that has ended.
     */
    private fun rememberHeldWinners(early: List<SignalEvent>, callId: String, carried: SignalEvent.End? = null): Boolean {
        val ended = early.filterIsInstance<SignalEvent.End>().map { it.callId }.toSet()
        for (e in early) (e as? SignalEvent.End)?.let { endedWinner(it, callId, ended) }?.let(::rememberEnded)
        val carriedWinner = carried?.let { endedWinner(it, callId, ended) } ?: return false
        rememberEnded(carriedWinner)
        return true
    }

    /** The winner [e] names, when it is the call [callId]'s own glare end (`busy`, a lower winner) and the winner has ended. */
    private fun endedWinner(e: SignalEvent.End, callId: String, ended: Set<String>): String? =
        e.supersededBy?.takeIf { e.callId == callId && e.reason == CallReason.BUSY && it < callId && (it in ended || hasEnded(it)) }

    /**
     * A held event left once the replay has finished the call, or handed the session to a glare's winner (or a held offer
     * that [finish] rang): an answer's keys are wiped; an end is applied to the session it is for, or remembered as another
     * call's ([endedOthers]); candidates and a ringing go to the current session, which checks their call id as usual.
     */
    private fun afterReplay(e: SignalEvent) {
        val current = session
        when (e) {
            is SignalEvent.Answer -> e.keys.wipe()
            is SignalEvent.End -> if (current != null && current.callId == e.callId) onSignal(current, e) else rememberEndedOther(e.callId)
            is SignalEvent.Ice, is SignalEvent.Ringing -> if (current != null) onSignal(current, e)
            else -> Unit
        }
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
        // (C2, VAULT-MESSAGING "Key confirmation") A device answering a form 2 offer (it answers in form 2, whatever
        // the `call.list` read has found yet) sends no media, microphone or camera, until its receiver first decrypts a
        // frame: that proves the caller holds the same keys.
        if (offer.formTwo) {
            s.formTwo = true
            s.heldForDecrypt = true
            engine.setHeld(true)
        }
        // The callee gathers no address before the member answers (CALLS-PLAN §2 step 6).
        s.lane.trySend {
            // The cryptors are made for the suite chosen when the offer was checked, the one the keys will be for.
            val sdp = engine.acceptOffer(offer.sdp, offer.servers, offer.suite)
            post(Msg.Gathering(s.seq))
            // Not cancellable between the answer's return and the keys' hand-over: a hang-up at that instant must
            // not leave the keys with nobody to wipe them.
            withContext(NonCancellable) {
                val keys = signalling.answer(offer.callId, sdp, engine.suites)
                try {
                    engine.setKeys(keys.suite, keys.send, keys.receive)
                } finally {
                    keys.wipe()
                }
            }
            post(Msg.Answered(s.seq))
        }
        applyRemote(s)
    }

    private fun onAnswered(s: Session) {
        s.keysSet = true
        publish(s) // the answer is with the vault: keep-alives may go
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

    /**
     * The other side's SDP is in: candidates go straight to the engine from now on, and those that came after
     * [applyRemote] took the waiting ones (while the offer or the answer was being applied) follow them.
     */
    private fun onRemoteApplied(s: Session) {
        s.remoteApplied = true
        val engine = s.engine ?: return
        if (s.pendingRemote.isEmpty()) return
        val late = s.pendingRemote.toList()
        s.pendingRemote.clear()
        s.lane.trySend { engine.addRemoteCandidates(late) }
    }

    private fun hangUp(s: Session, reason: CallEnd) {
        s.memberEnded = true
        when {
            s.phase == CallPhase.INCOMING -> finish(s, if (reason == CallEnd.HUNG_UP) CallEnd.DECLINED_HERE else reason, CallReason.DECLINE)
            s.callId == null || s.supersededBy != null -> finish(s, reason, null)
            else -> finish(s, reason, CallReason.HANGUP)
        }
    }

    // --- the vault ---

    @Suppress("CyclomaticComplexMethod")
    private fun onSignal(s: Session?, e: SignalEvent) {
        when (e) {
            is SignalEvent.Offer -> when {
                // A glare's winner, or another call, that already ended: let go in the signalling too.
                hasEnded(e.callId) -> signalling.drop(e.callId)
                // Never on a phone that is locking: the offer is let go, here and in the signalling.
                s == null -> if (!closed) ring(e) else signalling.drop(e.callId)
                // The winner is not answered on a phone that is locking: its offer is let go and the lost call ends here.
                glareWith(s, e) -> when {
                    s.supersededBy != e.callId -> s.glareOffer = e
                    !closed -> takeGlare(s, e)
                    else -> {
                        signalling.drop(e.callId)
                        endForLock(s)
                    }
                }
                else -> Unit // busy here: the vault rings every device, and this one is in another call
            }
            is SignalEvent.Ringing -> if (s != null && s.callId == e.callId && s.phase == CallPhase.CALLING) {
                s.phase = CallPhase.RINGING
                diagnosticsFlow.update { it.copy(tapToRingingMs = elapsedMs() - s.tappedAtMs) }
                publish(s)
            }
            is SignalEvent.Answer -> {
                val ringing = s != null && s.callId == e.callId && s.direction == CallDirection.OUTGOING &&
                    (s.phase == CallPhase.CALLING || s.phase == CallPhase.RINGING)
                when {
                    s != null && ringing && !closed -> takeAnswer(s, e)
                    s != null && ringing -> {
                        // Not taken on a phone that is locking: the keys go, and the call is ended here and now (a lock
                        // overtaken by an unlock may post no hang-up of its own).
                        e.keys.wipe()
                        endForLock(s)
                    }
                    else -> e.keys.wipe()
                }
            }
            is SignalEvent.AnswerRejected -> if (s != null && s.callId == e.callId && s.direction == CallDirection.OUTGOING) {
                finish(s, CallEnd.NOT_ENCRYPTED, CallReason.FAILED)
            }
            is SignalEvent.Ice -> if (s != null) onIceEvent(s, e)
            // With no session the end is remembered all the same: its offer may still come (VAULT-MESSAGING §8.4 gives no
            // order), and an idle phone must not ring a call that already ended. Ids are unique (§10.10); the list is bounded.
            is SignalEvent.End -> if (s != null) onEnd(s, e) else rememberEndedOther(e.callId)
        }
    }

    /**
     * The call's own candidates, or those of the call it lost a glare to (its offer held, or waited for), kept for that
     * call ([Session.glareIce]) until it is answered or rung.
     */
    private fun onIceEvent(s: Session, e: SignalEvent.Ice) {
        val forWinner = s.glareOffer?.callId == e.callId || (s.glareOffer == null && s.supersededBy == e.callId)
        when {
            s.callId == e.callId -> onIce(s, e.candidates)
            forWinner -> {
                // Another held call than the one kept for (the held offer was replaced, or let go for another winner):
                // what was kept is not this call's.
                if (s.glareIceFor != e.callId) {
                    s.glareIce.clear()
                    s.glareIceFor = e.callId
                }
                if (s.glareIce.size + e.candidates.size <= MAX_PENDING_CANDIDATES) s.glareIce += e.candidates
            }
        }
    }

    /** Candidates for the call here: to the engine once the other side's SDP is applied, else kept until it is. */
    private fun onIce(s: Session, candidates: List<CallCandidate>) {
        if (candidates.isEmpty()) return
        val engine = s.engine
        if (s.remoteApplied && engine != null) {
            s.lane.trySend { engine.addRemoteCandidates(candidates) }
        } else if (s.pendingRemote.size + candidates.size <= MAX_PENDING_CANDIDATES) {
            s.pendingRemote += candidates
        }
    }

    /** The vault's `call.end` for the call here, for its held glare offer, or for neither. */
    private fun onEnd(s: Session, e: SignalEvent.End) {
        if (s.glareOffer?.callId == e.callId) {
            // The connection's held offer ended (its caller hung up): it is no longer one to ring or answer, and an
            // offer for it delivered again is not either. Its candidates go with it.
            s.glareOffer = null
            s.glareIce.clear()
            rememberEnded(e.callId)
        } else if (s.supersededBy != null && s.supersededBy == e.callId) {
            // The winner itself ended before its offer reached this device: there is nothing to answer, and the vault
            // ended this device's own call already. A late offer for it is not answered or rung.
            rememberEnded(e.callId)
            finish(s, CallEnd.MISSED, null)
        } else if (s.callId == e.callId && s.supersededBy == null) {
            // (While a lost glare waits for its winner, X's own end came already: a second one changes nothing.)
            if (superseded(s, e)) {
                val by = e.supersededBy
                // The winner ended already (its end came before the glare's): nothing to answer or wait for. Kept among the
                // winners, where other calls' ends cannot push it out.
                if (by != null && hasEnded(by)) {
                    rememberEnded(by)
                    finish(s, CallEnd.MISSED, null)
                    return
                }
                settleGlare(s, by)
            } else {
                finish(s, ended(s, e.reason), null)
            }
        } else if (s.callId != e.callId) {
            // Another call's end (its offer not here yet, or one turned away as busy): its late offer is not rung. While
            // the call has no id its own end may be among these (the early events full): no offer for this device's own
            // call comes here, and [onPlaced] ends the call at once when its id turns out to be one of them (the end kept
            // for that: its reason, or the glare's winner).
            rememberEndedOther(e.callId, e.takeIf { s.callId == null })
        }
    }

    /**
     * The vault is locking while this call waits on something the lock refuses (an answer, a glare's winner): it ends here
     * and now as the lock's own hang-up would end it, without waiting for that hang-up (the hook of a lock an unlock overtook posts none).
     */
    private fun endForLock(s: Session) = hangUp(s, CallEnd.LOCKED)

    /**
     * This call lost a glare to [by]: its held offer, when that is the winner and still current, is answered; otherwise the
     * call waits as lost. Not answered on a phone that is locking: the winner's offer is let go, and the lost call ends here
     * at once (nothing is sent: the vault ended it already).
     */
    private fun settleGlare(s: Session, by: String?) {
        val winner = s.glareOffer?.takeIf { it.callId == by && now().isBefore(it.exp) }
        when {
            winner != null && !closed -> takeGlare(s, winner)
            winner != null -> {
                // Lost first: the vault ended this call already, so the end here sends nothing.
                signalling.drop(winner.callId)
                lostGlare(s, by)
                endForLock(s)
            }
            else -> lostGlare(s, by)
        }
    }

    /**
     * Other calls' ends held while the start was out are not dropped with the session: an offer that comes after one of
     * them (VAULT-MESSAGING §8.4 gives no order) is not rung.
     */
    private fun rememberEarlyEnds(s: Session) {
        for (e in s.early) if (e is SignalEvent.End) rememberEndedOther(e.callId)
    }

    /**
     * An incoming call that rang here and has ended: its offer delivered again is not rung (chosen; not in the spec). That
     * includes one still live at the vault until its `exp` (this device's ring timer ran out): a redelivered offer for it
     * does not ring here again.
     */
    private fun rememberIncoming(s: Session) {
        val callId = s.callId ?: return
        if (s.direction == CallDirection.INCOMING) rememberEndedOther(callId)
    }

    /** Whether [callId]'s own end is remembered among [endedOthers] and not also among the [early] events held. */
    private fun ownEndRemembered(callId: String, early: List<SignalEvent>): Boolean =
        callId in endedOthers && early.none { it is SignalEvent.End && it.callId == callId }

    /**
     * The placed call ended already ([carried], its remembered end): it shows that end's reason. A glare it lost shows
     * missed when the winner has ended ([lostToEnded]: kept among the winners, its late offer is not rung, as on the live
     * path), else failed (the winner rings: §10.10 allows a device that does not answer it by itself to ring it). What was
     * held goes where it belongs.
     */
    private fun endPlacedAtOnce(s: Session, early: List<SignalEvent>, carried: SignalEvent.End?, lostToEnded: Boolean) {
        val reason = when {
            lostToEnded -> CallEnd.MISSED
            carried == null || carried.supersededBy != null -> CallEnd.FAILED
            else -> ended(s, carried.reason)
        }
        finish(s, reason, null)
        for (e in early) afterReplay(e)
    }

    /**
     * [callId] ended (another call's end, see [endedOthers]; [end] kept with it in [endedOtherEnds]);
     * a repeat moves it to the newest.
     */
    private fun rememberEndedOther(callId: String, end: SignalEvent.End? = null) {
        endedOthers.remove(callId)
        endedOthers.addLast(callId)
        if (end != null) endedOtherEnds[callId] = end
        if (endedOthers.size > MAX_ENDED_OTHERS) endedOtherEnds.remove(endedOthers.removeFirst())
    }

    /** Whether the call [callId] is known to have ended here ([endedWinners], [endedOthers]). */
    private fun hasEnded(callId: String): Boolean = callId in endedWinners || callId in endedOthers

    private fun rememberEnded(callId: String) {
        if (callId in endedWinners) return // once: a repeat would push out another
        endedWinners.addLast(callId)
        if (endedWinners.size > MAX_ENDED_WINNERS) endedWinners.removeFirst()
    }

    /** [e] is the connection's own call while this device's outgoing call to it still rings: a glare, perhaps. */
    private fun glareWith(s: Session, e: SignalEvent.Offer): Boolean =
        s.direction == CallDirection.OUTGOING && s.connectionId == e.connectionId && e.callId != s.callId &&
            s.supersededBy.let { it == null || it == e.callId } &&
            (s.phase == CallPhase.STARTING || s.phase == CallPhase.CALLING || s.phase == CallPhase.RINGING)

    /** The vault ended this device's ringing outgoing call because the connection's own call won a glare. */
    private fun superseded(s: Session, e: SignalEvent.End): Boolean {
        val by = e.supersededBy
        val own = s.callId
        // Both vaults let the lower call id win (its 26 ASCII bytes): a winner that is not lower is no glare's.
        return by != null && own != null && by < own && e.reason == CallReason.BUSY && s.direction == CallDirection.OUTGOING &&
            (s.phase == CallPhase.CALLING || s.phase == CallPhase.RINGING) && s.supersededBy == null
    }

    /**
     * The winner's offer is not here yet: this call's media goes (the microphone is let go), its screen stays, and the
     * offer is answered when it comes. Nothing is sent: the vault already ended this call.
     */
    private fun lostGlare(s: Session, by: String?) {
        s.supersededBy = by
        s.glareOffer = null
        cancelTimer(s, TimerKind.GATHER)
        cancelTimer(s, TimerKind.ICE_FLUSH)
        s.canSendIce = false
        s.pendingLocal.clear()
        s.pendingRemote.clear()
        // The lost call's queued engine work is dropped, not run on a closed engine.
        s.lane.cancel()
        s.engine?.close()
        s.engine = null
    }

    /**
     * (D21 a) The member asked for this call seconds ago: the winning call [offer] from the same connection is answered
     * here without asking, as soon as this device's own lost call is let go (nothing is sent for it). Calls are audio
     * only (C1-C3), so the answer never carries more media than the lost call did (VAULT-MESSAGING 0.88.14). A video
     * offer does not reach the machine at all (`callIncoming` refuses anything but one audio section), so a video
     * winner is not answered: the lost call shows busy at its ring timer. The table's audio-only answer to a video
     * winner comes with video (C4).
     */
    private fun takeGlare(s: Session, offer: SignalEvent.Offer) {
        val ice = s.glareIceFor(offer.callId)
        release(s)
        val y = incoming(offer)
        // The winner's candidates that came while this device's lost call was the session: waiting with the offer, as
        // a ringing call's own are, and applied with its SDP.
        onIce(y, ice)
        answerIncoming(y)
    }

    private fun ring(offer: SignalEvent.Offer) {
        val s = incoming(offer)
        publish(s)
        s.launch { runCatching { signalling.ringing(offer.callId) } }
        // The caller's `call.end{timeout}` normally ends the ringing; this is for a caller that has gone.
        val ringFor = offer.ringFor.plus(timings.incomingGrace).coerceIn(timings.incomingGrace, timings.maxIncomingRing)
        timer(s, TimerKind.RING_IN, ringFor)
    }

    private fun incoming(offer: SignalEvent.Offer): Session {
        val s = newSession(offer.connectionId, CallDirection.INCOMING)
        s.callId = offer.callId
        s.offer = offer
        s.phase = CallPhase.INCOMING
        return s
    }

    private fun takeAnswer(s: Session, e: SignalEvent.Answer) {
        val engine = s.engine
        if (engine == null) {
            e.keys.wipe()
            return
        }
        cancelTimer(s, TimerKind.RING_OUT)
        // Answered: an offer from the connection now is no glare, and is not rung after this call either.
        s.glareOffer = null
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

    /**
     * What the other side's (or this member's other device's) `call.end` means here. (C2) The caller's
     * `call.end{failed}` while this device still holds its track for a first decrypted frame is the caller's wrong
     * `confirm`: the same text as the 20 s rule's, "couldn't be encrypted" (VAULT-MESSAGING "Key confirmation").
     */
    private fun ended(s: Session, reason: String): CallEnd = if (s.heldForDecrypt && reason == CallReason.FAILED) {
        CallEnd.NOT_ENCRYPTED
    } else when (s.phase) {
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
            // Not an end: the stack can finish a round before the phone's network has reported its addresses, and the
            // engine keeps watching the networks (GATHER_CONTINUALLY), so a candidate may still come. The GATHER timer
            // alone decides NO_ADDRESS.
            is MediaEvent.GatheringDone -> Unit
            is MediaEvent.Connection -> onConnection(s, e.state)
            is MediaEvent.Cryptor -> if (cryptorFailed(s, e)) {
                finish(s, CallEnd.NOT_ENCRYPTED, CallReason.FAILED)
            } else if (e.direction == MediaDirection.RECEIVE && e.state == CryptorState.OK && s.heldForDecrypt) {
                // The first frame decrypted: the caller holds the same keys. The member's own mute still applies.
                s.heldForDecrypt = false
                cancelTimer(s, TimerKind.DECRYPT)
                s.engine?.setHeld(false)
            }
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
        if (s.heldForDecrypt && TimerKind.DECRYPT !in s.timers) timer(s, TimerKind.DECRYPT, timings.decrypt)
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
        // A lost glare waits for its winner: only its ring timer still counts (a timer already queued is stale).
        if (s.supersededBy != null && kind != TimerKind.RING_OUT) return
        when (kind) {
            TimerKind.RING_OUT -> ringOutOver(s)
            // Nothing is sent: the missed call is recorded by this member's vault when the caller's timeout arrives.
            TimerKind.RING_IN -> if (s.phase == CallPhase.INCOMING) finish(s, CallEnd.MISSED, null)
            TimerKind.GATHER -> if (s.gathered == 0) finish(s, CallEnd.NO_ADDRESS, CallReason.FAILED)
            TimerKind.CONNECT -> if (s.phase == CallPhase.CONNECTING) finish(s, CallEnd.COULD_NOT_CONNECT, CallReason.FAILED)
            TimerKind.DECRYPT -> if (s.heldForDecrypt) finish(s, CallEnd.NOT_ENCRYPTED, CallReason.FAILED)
            TimerKind.ICE_FLUSH -> flush(s)
        }
    }

    private fun ringOutOver(s: Session) {
        when {
            // The glare's winner never came: busy, as before C2; the vault already ended this call.
            s.supersededBy != null -> finish(s, CallEnd.BUSY, null)
            s.phase == CallPhase.CALLING || s.phase == CallPhase.RINGING -> finish(s, CallEnd.NO_ANSWER, CallReason.TIMEOUT)
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
                media.relayed?.let { r -> post(Msg.Path(s.seq, r)) }
                // A second audio stream is RTP on an SSRC nobody announced, in the one section the call has: its
                // frames go through the same decryption and are dropped, and the call does not go on beside it.
                if (media.inboundAudioStreams > 1) post(Msg.Failed(s.seq, CallEnd.NOT_ENCRYPTED))
            }
        }
    }

    private fun publish(s: Session) {
        stateFlow.value = CallState.Live(
            s.connectionId, s.direction, s.phase, s.callId, s.muted, s.activeSince, s.reconnecting, s.keysSet, s.formTwo,
        )
    }

    /** Ends the call here; [tell] is the `call.end` reason for the vault, or null when nothing is to be sent. */
    private fun finish(s: Session, reason: CallEnd, tell: String?) {
        if (session !== s) return
        // A held offer whose own end is held too (the call ends before it had its id: a refused or failed start) has
        // ended: it is not rung below.
        forgetEndedGlareOffer(s, s.early)
        rememberEarlyEnds(s)
        val held = s.glareOffer
        // Its candidates: those kept with it, and those still among the call's early events.
        val heldIce = held?.let { h ->
            val early = s.early.filterIsInstance<SignalEvent.Ice>().filter { it.callId == h.callId }.flatMap { it.candidates }
            (s.glareIceFor(h.callId) + early).take(MAX_PENDING_CANDIDATES)
        }.orEmpty()
        release(s)
        val callId = s.callId
        if (tell != null && callId != null) sendEnd(callId, tell)
        rememberIncoming(s)
        // `call.start` may still be answered for a call without its id: the call it placed is ended when it is (onPlaced).
        val duration = s.activeSince?.let { Duration.between(it, now()) }
        if (duration != null) countPath(s)
        stateFlow.value = CallState.Ended(s.connectionId, s.direction, reason, callId, duration)
        // The connection's own call that arrived while this one rang to it is not lost when this one ended another way
        // (the vault, the other side, a refusal of `call.start` such as `busy`, a failure): it rings. Never after this
        // member's own end, a lock's above all, and never while the vault is not open on this phone ([closed]): nobody
        // would see it, and a lock would decline it unseen.
        val rings = held?.takeIf { !s.memberEnded && now().isBefore(it.exp) }
        // Not rung: let go in the signalling too (a lock's own hang-up included, which sets memberEnded).
        if (rings != null && !closed) {
            ring(rings)
            // Its candidates that came meanwhile wait for its answer, as a ringing call's own do.
            session?.takeIf { it.callId == rings.callId }?.let { onIce(it, heldIce) }
        } else {
            held?.let { signalling.drop(it.callId) }
        }
    }

    /** The candidates kept for [callId] alone ([Session.glareIceFor]); none when they are another call's. */
    private fun Session.glareIceFor(callId: String): List<CallCandidate> = if (glareIceFor == callId) glareIce.toList() else emptyList()

    /**
     * A held glare offer whose own end is among [early] has ended, as [onEnd] treats one live: it is let go and
     * remembered before they are applied (or dropped, when the call ends before it has its id). So the glare's end does
     * not answer it, the call is missed when the winner's end is applied, and a call that ends otherwise does not ring it.
     */
    private fun forgetEndedGlareOffer(s: Session, early: List<SignalEvent>) {
        val held = s.glareOffer ?: return
        if (early.none { it is SignalEvent.End && it.callId == held.callId }) return
        s.glareOffer = null
        s.glareIce.clear()
        rememberEnded(held.callId)
    }

    /** A connected call ended: its path is counted, by the last one the stack reported (relayed, direct, or not said). */
    private fun countPath(s: Session) = pathsFlow.update {
        when (s.relayed) {
            true -> it.copy(relayed = it.relayed + 1)
            false -> it.copy(direct = it.direct + 1)
            null -> it.copy(unknown = it.unknown + 1)
        }
    }

    /** Lets the call go: its jobs, engine and keys, and what the signalling keeps of it. Publishes nothing and sends nothing. */
    private fun release(s: Session) {
        if (session !== s) return
        session = null
        liveSeq.set(NO_SEQ)
        s.callId?.let { signalling.drop(it) }
        s.lane.close()
        s.job.cancel()
        s.engine?.close()
        s.keys?.wipe()
        s.early.forEach { (it as? SignalEvent.Answer)?.keys?.wipe() }
        s.early.clear()
        s.pendingRemote.clear()
        s.pendingLocal.clear()
        s.glareOffer = null
        s.glareIce.clear()
    }

    /** `call.end` outlives the call: it is durable in the device's outbox once it has been handed over. */
    private fun sendEnd(callId: String, reason: String) {
        pendingEndsFlow.update { it + 1 }
        scope.launch {
            try {
                // A `call.end` is durable (the outbox keeps it until the relay has it): a failure here is not its loss. One
                // held by a mailbox quota goes when the backoff ends; it is logged as held, with no member text (the other
                // side's ring timer bounds the wait). Only the failure's type is logged.
                runCatching { signalling.end(callId, reason) }.onFailure { e ->
                    if (e is CancellationException) return@onFailure
                    if (com.vettid.core.vault.VaultDevice.keptInOutbox(e)) {
                        android.util.Log.i(TAG, "call.end held for the outbox's next flush (${e.javaClass.simpleName})")
                    } else {
                        android.util.Log.w(TAG, "call.end not sent (${e.javaClass.simpleName})")
                    }
                }
            } finally {
                pendingEndsFlow.update { it - 1 }
            }
        }
    }

    private companion object {
        /** No session ([liveSeq]): seqs count up from 0. */
        const val NO_SEQ = -1

        const val TAG = "CallMachine"
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_PENDING_CANDIDATES = 256
        const val MAX_EARLY = 64
        const val MAX_ENDED_WINNERS = 8
        const val MAX_ENDED_OTHERS = 32

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
