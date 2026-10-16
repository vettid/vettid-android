package com.vettid.feature.messages

import androidx.annotation.StringRes
import com.vettid.core.data.calls.CallHistoryEntry
import com.vettid.core.data.calls.CallOutcome
import com.vettid.core.data.social.MessageInfo
import java.time.Duration
import java.time.Instant

/** One row of a conversation: a message, or a call as the vault's history has it. */
sealed interface ConversationRow {
    val at: Instant
    val key: String

    data class Message(val message: MessageInfo) : ConversationRow {
        override val at: Instant get() = message.sentAt
        override val key: String get() = message.messageId
    }

    data class Call(val call: CallHistoryEntry) : ConversationRow {
        override val at: Instant get() = call.startedAt
        override val key: String get() = "call-${call.callId}"
    }
}

/** A call's line in a conversation (texts approved 2026-10-12). */
object CallEntries {
    /**
     * The text of [outcome]; null for a call that leaves no entry (answered on another device, or still going on).
     * [R.string.messages_call_talked] takes the duration.
     */
    @StringRes
    fun text(outcome: CallOutcome): Int? = when (outcome) {
        CallOutcome.TALKED -> R.string.messages_call_talked
        CallOutcome.DECLINED -> R.string.messages_call_declined
        CallOutcome.DECLINED_HERE -> R.string.messages_call_declined_here
        CallOutcome.BUSY -> R.string.messages_call_busy
        CallOutcome.NOT_ANSWERED -> R.string.messages_call_not_answered
        CallOutcome.MISSED -> R.string.messages_call_missed
        CallOutcome.FAILED -> R.string.messages_call_failed
        CallOutcome.ANSWERED_ELSEWHERE, CallOutcome.ONGOING -> null
    }

    /** Messages and calls in one list, oldest first; calls without an entry left out. */
    fun rows(messages: List<MessageInfo>, calls: List<CallHistoryEntry>): List<ConversationRow> =
        (messages.map { ConversationRow.Message(it) } + calls.filter { text(it.outcome) != null }.map { ConversationRow.Call(it) })
            .sortedBy { it.at }

    /** A duration as its two largest parts: (hours, minutes), (minutes, seconds) or (seconds) alone. */
    data class Parts(val hours: Long, val minutes: Long, val seconds: Long)

    fun parts(d: Duration): Parts {
        val s = d.seconds.coerceAtLeast(0)
        return Parts(s / SECONDS_PER_HOUR, s % SECONDS_PER_HOUR / SECONDS_PER_MINUTE, s % SECONDS_PER_MINUTE)
    }

    /** The format of [p]: "1 h 4 min", "4 min 12 s", "12 s". */
    @StringRes
    fun durationFormat(p: Parts): Int = when {
        p.hours > 0 -> R.string.messages_call_duration_h_min
        p.minutes > 0 -> R.string.messages_call_duration_min_s
        else -> R.string.messages_call_duration_s
    }

    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
}
