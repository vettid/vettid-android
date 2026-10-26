package com.vettid.core.data.vault

/**
 * (Release R, VAULT-MESSAGING §11.14.5, ANDROID-PLAN "Owner check and status") A recovery counter that stays
 * unavailable at every read of `vault.status` for 24 hours is shown with the urgency of a used attempt. Every read that
 * carries `recovery` (the status screen's and the owner check's) moves this clock; one that finds the counter available
 * stops it. Times are this phone's clock (epoch ms).
 *
 * (STAGING-TIME-SCALE S8, ANDROID-PLAN 0.1.49) The 24 hours are divided by the vault's time scale when the clock starts
 * ([urgentMs]: 1 hour at 24), and the period is kept with the start: a clock started under one scale ends at the time
 * it was given.
 */
internal object RecoveryUnknownClock {
    const val URGENT_MS = 24 * 3_600_000L

    /** [URGENT_MS] in seconds, for the text. */
    const val URGENT_S = URGENT_MS / 1_000L

    /** The urgency's period for a clock that starts on a vault of [timeScale]. */
    fun urgentMs(timeScale: Long): Long = TimeScales.divideMs(URGENT_MS, timeScale)

    /** `recovery.counter` `unavailable`, or the recovery `unknown`. */
    fun unavailable(state: String?, counter: String?): Boolean = counter == "unavailable" || state == "unknown"

    /** Since when the counter has been unavailable after a read at [now]; null when it is available. */
    fun next(since: Long?, unavailable: Boolean, now: Long): Long? = when {
        !unavailable -> null
        since == null || since > now -> now
        else -> since
    }

    /**
     * The clock after a read at [now] on a vault of [timeScale]. A clock that starts (or starts again, after the phone's
     * clock was set back) takes its period from [timeScale]; a running one keeps the period it started with.
     */
    fun advance(cur: State, unavailable: Boolean, now: Long, timeScale: Long): State {
        val since = next(cur.since, unavailable, now)
        return when (since) {
            null -> State()
            cur.since -> cur
            else -> State(since, urgentMs(timeScale).takeIf { it != URGENT_MS })
        }
    }

    fun urgent(since: Long?, now: Long, periodMs: Long = URGENT_MS): Boolean = since != null && now - since >= periodMs

    /**
     * The clock as the account record keeps it: [since] ([LocalAccount.recoveryUnknownSinceMs]) and the period stored with
     * it ([LocalAccount.recoveryUnknownForMs]; null: 24 hours).
     */
    data class State(val since: Long? = null, val periodMs: Long? = null) {
        /** The period this clock runs for. */
        val period: Long get() = periodMs ?: URGENT_MS

        fun urgent(now: Long): Boolean = urgent(since, now, period)
    }
}
