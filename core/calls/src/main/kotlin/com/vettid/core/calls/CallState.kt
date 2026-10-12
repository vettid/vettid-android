package com.vettid.core.calls

import java.time.Duration
import java.time.Instant

enum class CallDirection { OUTGOING, INCOMING }

/** Where a live call stands. */
enum class CallPhase {
    /** Outgoing: the offer is being made and sent to this member's vault. */
    STARTING,

    /** Outgoing: the vault has sent the offer on. */
    CALLING,

    /** Outgoing: the other member's device is ringing. */
    RINGING,

    /** Incoming: ringing here. */
    INCOMING,

    /** Answered: the two devices are finding a path. */
    CONNECTING,

    /** Media flows, encrypted under the call's keys. */
    ACTIVE,
}

/** Why a call ended. One text per value in the screens. */
enum class CallEnd {
    /** This member hung up. */
    HUNG_UP,

    /** The other member hung up. */
    PEER_HUNG_UP,

    /** The other member declined. */
    DECLINED,

    /** Declined here (on this device or another of the member's). */
    DECLINED_HERE,

    /** The other member is on another call (or both called each other at once). */
    BUSY,

    /** Nobody answered within the ring time. */
    NO_ANSWER,

    /** An incoming call that stopped before it was answered. */
    MISSED,

    /** Answered on another of the member's devices. */
    ANSWERED_ELSEWHERE,

    /** The connection or the call is no longer there. */
    UNAVAILABLE,

    /** This member's vault is already in a call. */
    OWN_VAULT_BUSY,

    /** The daily owner check is due: no calls until it is done. */
    OWNER_CHECK_REQUIRED,

    /** This vault's release has no calls. */
    NOT_SUPPORTED,

    /** This device found no network address to be reached at (for example a blocked network permission). */
    NO_ADDRESS,

    /** The two devices found no path to each other (today: they are not on the same network). */
    COULD_NOT_CONNECT,

    /** The path was lost during the call. */
    CONNECTION_LOST,

    /** The call could not be encrypted: keys, suites or frames did not check out. Nothing unencrypted was played. */
    NOT_ENCRYPTED,

    /** The vault was locked. */
    LOCKED,

    /** Anything else. */
    FAILED,
}

sealed interface CallState {
    data object Idle : CallState

    data class Live(
        val connectionId: String,
        val direction: CallDirection,
        val phase: CallPhase,
        /** Null while an outgoing call is [CallPhase.STARTING]. */
        val callId: String? = null,
        val muted: Boolean = false,
        /** When media first flowed. */
        val activeSince: Instant? = null,
        /** Active, but the path is interrupted; the media stack is trying to get it back. */
        val reconnecting: Boolean = false,
    ) : CallState

    /** The last call, until it is dismissed or another begins. */
    data class Ended(
        val connectionId: String,
        val direction: CallDirection,
        val reason: CallEnd,
        val callId: String? = null,
        /** How long media flowed; null if it never did. */
        val duration: Duration? = null,
    ) : CallState
}

/** Measurements of a call, for the diagnostics view and the phone tests. No identifiers, nothing secret. */
data class CallDiagnostics(
    /** Outgoing: from the tap on Call to the vault's answer to `call.start`. */
    val tapToPlacedMs: Long? = null,
    /** Outgoing: from the tap on Call to `call.ringing` (the other phone rings). CALLS-PLAN §7 measures this. */
    val tapToRingingMs: Long? = null,
    /** From the answer (tapped here, or received) to the first media. */
    val answerToMediaMs: Long? = null,
    val media: MediaDiagnostics? = null,
)

/** The times the state machine keeps. Defaults are the plan's; tests shorten nothing (virtual time). */
@Suppress("MagicNumber")
data class CallTimings(
    /** No address gathered after this long: the call ends [CallEnd.NO_ADDRESS]. */
    val gather: Duration = Duration.ofSeconds(8),
    /** No media this long after the answer: the call ends [CallEnd.COULD_NOT_CONNECT]. */
    val connect: Duration = Duration.ofSeconds(30),
    /** The caller rings for the offer's lifetime, kept within these bounds whatever the two clocks say. */
    val minRing: Duration = Duration.ofSeconds(10),
    val maxRing: Duration = Duration.ofSeconds(60),
    /** A callee stops ringing this long after the offer's `exp` if the caller's `call.end` has not come. */
    val incomingGrace: Duration = Duration.ofSeconds(3),
    val maxIncomingRing: Duration = Duration.ofSeconds(93),
    /** Local candidates wait this long to be sent together. */
    val iceBatch: Duration = Duration.ofMillis(200),
    /** At most one `call.ice` per interval (the relay takes 5 deposits a second from one sender). */
    val iceInterval: Duration = Duration.ofMillis(300),
    /** `call.ice` messages this device sends for one call (a vault forwards 256, both directions together). */
    val maxIceMessages: Int = 100,
    val diagnosticsEvery: Duration = Duration.ofSeconds(1),
    /** The pause before the vault's call events are listened to again after the listening ended. */
    val relisten: Duration = Duration.ofSeconds(1),
)
