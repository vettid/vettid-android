package com.vettid.core.data.vault

/**
 * (Release R, VAULT-MESSAGING §11.14.5, ANDROID-PLAN "Owner check and status") A recovery counter that stays
 * unavailable at every read of `vault.status` for 24 hours is shown with the urgency of a used attempt. Every read that
 * carries `recovery` (the status screen's and the owner check's) moves this clock; one that finds the counter available
 * stops it. Times are this phone's clock (epoch ms).
 */
internal object RecoveryUnknownClock {
    const val URGENT_MS = 24 * 3_600_000L

    /** `recovery.counter` `unavailable`, or the recovery `unknown`. */
    fun unavailable(state: String?, counter: String?): Boolean = counter == "unavailable" || state == "unknown"

    /** Since when the counter has been unavailable after a read at [now]; null when it is available. */
    fun next(since: Long?, unavailable: Boolean, now: Long): Long? = when {
        !unavailable -> null
        since == null || since > now -> now
        else -> since
    }

    fun urgent(since: Long?, now: Long): Boolean = since != null && now - since >= URGENT_MS
}
