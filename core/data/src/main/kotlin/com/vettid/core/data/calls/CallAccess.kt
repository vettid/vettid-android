package com.vettid.core.data.calls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Duration
import java.time.Instant

/** Whether a conversation can show a call button, and why not. */
enum class CallAvailability {
    /** This build has no calls (every production build until calls are promoted): no button. */
    NOT_IN_BUILD,

    /** The vault has not been asked yet (it is locked, or was just opened): no button. */
    UNKNOWN,

    /** The vault's release has no calls: no button. */
    NOT_IN_VAULT,

    /** The button calls. */
    READY,

    /** A call is going on: the button is disabled. */
    IN_CALL,
}

/** How a past call ended, as the conversation shows it. */
enum class CallOutcome { TALKED, DECLINED, DECLINED_HERE, BUSY, NOT_ANSWERED, MISSED, FAILED, ANSWERED_ELSEWHERE, ONGOING }

/** One call of the vault's history (it keeps the latest 200; no content, no keys). */
data class CallHistoryEntry(
    val callId: String,
    val connectionId: String,
    val outgoing: Boolean,
    val outcome: CallOutcome,
    val startedAt: Instant,
    /** How long the call was answered for; null if it never was. */
    val duration: Duration? = null,
)

/**
 * All that the screens of every build know of calls: whether to show a call button, the tap on it, and the calls a
 * conversation lists. Everything else of calls (the media stack, the state machine, the call screens) is in modules
 * that production builds do not have yet (CALLS-PLAN 0.1.1 phase C1); those builds bind [NoCallAccess].
 */
interface CallAccess {
    val availability: StateFlow<CallAvailability>

    /** Calls [connectionId]; the call screen follows from the call's state. Nothing happens unless [CallAvailability.READY]. */
    fun call(connectionId: String)

    /** The calls with [connectionId], newest first; empty when there are none or they cannot be read. */
    suspend fun history(connectionId: String): List<CallHistoryEntry>
}

/** A build without calls. */
object NoCallAccess : CallAccess {
    override val availability: StateFlow<CallAvailability> = MutableStateFlow(CallAvailability.NOT_IN_BUILD)

    override fun call(connectionId: String) = Unit

    override suspend fun history(connectionId: String): List<CallHistoryEntry> = emptyList()
}
