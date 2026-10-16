package com.vettid.feature.calls

import androidx.annotation.StringRes
import com.vettid.core.calls.CallEnd
import com.vettid.core.calls.CallPhase

/** The member-facing texts of a call (approved 2026-10-12). Every ending has one line; none is shared. */
object CallTexts {
    /** The line of an ended call. Texts with `%1$s` take the connection's name. */
    @StringRes
    fun ended(reason: CallEnd): Int = ENDED.getValue(reason)

    private val ENDED: Map<CallEnd, Int> = mapOf(
        CallEnd.HUNG_UP to R.string.calls_end_hung_up,
        CallEnd.PEER_HUNG_UP to R.string.calls_end_peer_hung_up,
        CallEnd.DECLINED to R.string.calls_end_declined,
        CallEnd.DECLINED_HERE to R.string.calls_end_declined_here,
        CallEnd.BUSY to R.string.calls_end_busy,
        CallEnd.NO_ANSWER to R.string.calls_end_no_answer,
        CallEnd.MISSED to R.string.calls_end_missed,
        CallEnd.ANSWERED_ELSEWHERE to R.string.calls_end_answered_elsewhere,
        CallEnd.UNAVAILABLE to R.string.calls_end_unavailable,
        CallEnd.OWN_VAULT_BUSY to R.string.calls_end_own_vault_busy,
        CallEnd.OWNER_CHECK_REQUIRED to R.string.calls_end_owner_check_required,
        CallEnd.NOT_SUPPORTED to R.string.calls_end_not_supported,
        CallEnd.NO_ADDRESS to R.string.calls_end_no_address,
        CallEnd.COULD_NOT_CONNECT to R.string.calls_end_could_not_connect,
        CallEnd.CONNECTION_LOST to R.string.calls_end_connection_lost,
        CallEnd.NOT_ENCRYPTED to R.string.calls_end_not_encrypted,
        CallEnd.LOCKED to R.string.calls_end_locked,
        CallEnd.FAILED to R.string.calls_end_failed,
    )

    /**
     * Endings the member has to read: they say what to do or that something was wrong, so their screen stays until
     * it is closed. The others close by themselves after a moment.
     */
    fun staysOpen(reason: CallEnd): Boolean = reason in STAYS_OPEN

    private val STAYS_OPEN = setOf(
        CallEnd.OWNER_CHECK_REQUIRED, CallEnd.NOT_SUPPORTED, CallEnd.NO_ADDRESS, CallEnd.COULD_NOT_CONNECT, CallEnd.NOT_ENCRYPTED,
        CallEnd.FAILED, CallEnd.OWN_VAULT_BUSY,
    )

    /** The state line of a live call before media flows; null once it does (the screen then shows the time). */
    @StringRes
    fun phase(phase: CallPhase, reconnecting: Boolean): Int? = when (phase) {
        CallPhase.STARTING, CallPhase.CALLING -> R.string.calls_state_calling
        CallPhase.RINGING -> R.string.calls_state_ringing
        CallPhase.CONNECTING -> R.string.calls_state_connecting
        CallPhase.INCOMING -> R.string.calls_incoming
        CallPhase.ACTIVE -> if (reconnecting) R.string.calls_state_reconnecting else null
    }

    /** `4:12`, `1:04:12`. */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / SECONDS_PER_HOUR
        val m = s % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val sec = s % SECONDS_PER_MINUTE
        return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, sec) else "%d:%02d".format(java.util.Locale.ROOT, m, sec)
    }

    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
}
