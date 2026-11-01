package com.vettid.core.data.vault

import com.vettid.core.vault.VaultMessage

/**
 * `vault.locking` received while an unlock is on its way (#244 review B3), judged only after it. An unlock of a running
 * vault locks it first and says so (§11.4). Until the unlock's result, [LockNotices] knows only the previous opening,
 * so that notice is not stale yet, and taken at once it would end the very unlock that caused it. Held, then judged
 * once the result's time is in [LockNotices.opened]: the unlock's own notice is stale then, and a later one still
 * locks. For every vault release (no `unlock_id` needed). Other events are not held.
 */
internal class UnlockNoticeHold {
    private var open = 0
    private val held = mutableListOf<suspend () -> Unit>()

    /** One unlock: [Ticket.end] once it is over (its time in [LockNotices], or it failed), at most once. */
    inner class Ticket internal constructor() {
        private var ended = false

        /** The notices held, to be taken now (in order); none while another unlock is still on its way. */
        fun end(): List<suspend () -> Unit> = synchronized(this@UnlockNoticeHold) {
            if (ended) return emptyList()
            ended = true
            open--
            if (open > 0) return emptyList()
            held.toList().also { held.clear() }
        }
    }

    fun begin(): Ticket = synchronized(this) {
        open++
        Ticket()
    }

    /** True when [m] is a `vault.locking` received during an unlock: [take] runs when the last open unlock ends. */
    fun hold(m: VaultMessage, take: suspend () -> Unit): Boolean = synchronized(this) {
        if (open == 0 || m.type != LockNotices.TYPE) return false
        held += take
        true
    }
}
