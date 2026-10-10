package com.vettid.core.notify

import kotlin.random.Random

/**
 * The on-phone service's reconnect schedule (ANDROID-PLAN 0.1.23, Notification modes 3): exponential backoff with full
 * jitter, 1 s doubling to 5 minutes; reset after 5 minutes connected, and by a validated network ([reset]).
 */
class ReconnectBackoff(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private var attempt = 0
    private var connectedAt: Long? = null

    /** The wait before the next attempt: uniform in [0, min(1 s × 2ⁿ, 5 min)]. */
    fun nextDelayMs(): Long {
        val cap = if (attempt >= MAX_SHIFT) MAX_MS else minOf(BASE_MS shl attempt, MAX_MS)
        attempt++
        return random.nextLong(cap + 1)
    }

    /** The connection is up (the vault open and collected). */
    fun onConnected() {
        if (connectedAt == null) connectedAt = nowMs()
    }

    /** The connection dropped: the schedule starts over if it had been up for 5 minutes. */
    fun onDropped() {
        val since = connectedAt ?: return
        if (nowMs() - since >= RESET_MS) attempt = 0
        connectedAt = null
    }

    fun reset() {
        attempt = 0
    }

    /** The cap of the next wait (tests). */
    val nextCapMs: Long get() = if (attempt >= MAX_SHIFT) MAX_MS else minOf(BASE_MS shl attempt, MAX_MS)

    companion object {
        const val BASE_MS = 1_000L
        const val MAX_MS = 300_000L
        const val RESET_MS = 300_000L
        private const val MAX_SHIFT = 20
    }
}
