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
 * (GC #7718) A conversation's calls: [entries], newest first, and [someNotShown] when the vault's list had entries for it
 * (or for a connection it could not tell) that did not read: the list then says "Some calls couldn't be shown.".
 */
data class CallHistory(val entries: List<CallHistoryEntry> = emptyList(), val someNotShown: Boolean = false)

/**
 * (C2, VAULT-MESSAGING §10.10 "Permission and limits") Calls with one connection: [allowed] false when the member turned
 * them off (both ways), [limited] while the vault does not ring for that connection's calls (too many unanswered).
 */
data class ConnectionCalls(val allowed: Boolean = true, val limited: Boolean = false)

/**
 * What the vault said of each connection's calls, read after each unlock and again on each change. [supported]: the vault
 * has the permission (a C2 vault), so the connection page shows the switch; [known]: it has been read since the unlock
 * (until then nothing is said either way). A connection not in [differing] is allowed and not limited.
 */
data class CallPermissions(
    val supported: Boolean = false,
    val known: Boolean = false,
    val differing: Map<String, ConnectionCalls> = emptyMap(),
) {
    fun of(connectionId: String): ConnectionCalls = differing[connectionId] ?: ConnectionCalls()
}

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
    suspend fun history(connectionId: String): CallHistory

    /** (C2) Each connection's calls: allowed or off, limited. */
    val permissions: StateFlow<CallPermissions>

    /**
     * (C2) Turns calls with [connectionId] on or off (both ways). Throws the vault's refusal or a transport failure; on
     * success [permissions] says so at once, and again once the vault's list is read.
     */
    suspend fun setAllowed(connectionId: String, allowed: Boolean)
}

/** A build without calls. */
object NoCallAccess : CallAccess {
    override val availability: StateFlow<CallAvailability> = MutableStateFlow(CallAvailability.NOT_IN_BUILD)

    override fun call(connectionId: String) = Unit

    override suspend fun history(connectionId: String): CallHistory = CallHistory()

    override val permissions: StateFlow<CallPermissions> = MutableStateFlow(CallPermissions())

    override suspend fun setAllowed(connectionId: String, allowed: Boolean) = Unit
}
