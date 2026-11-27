package com.vettid.core.vault

import java.time.Duration

/**
 * (VAULT-MESSAGING 0.97.0 §10.10 "Placing a call") The times of [VaultDevice.requestOnceRetried], apart from its sends and
 * waits so that they run on any clock (a test's too). From just before the first send ([elapsed] then is the start):
 * - the first deposit ([run]'s `first`) is bounded by [timeout]; not made by then is no answer;
 * - a copy goes again ([run]'s `again`, bounded by what is left of [timeout]) at each time in [resendAfter] with no answer
 *   by then, except a time at or before the end of the last deposit (the first or a re-send): copies never go back to back;
 * - the last wait runs to [timeout], and at least [minAnswerWait] after the first deposit was made, so a request that has
 *   just landed is not given up at once (a departure from §10.10's 30 s, which the spec is to carry as an erratum).
 */
internal class ResendTimes(resendAfter: List<Duration>, timeout: Duration, minAnswerWait: Duration) {
    private val resends = resendAfter.map { it.toMillis() }
    private val end = timeout.toMillis()
    private val floor = minAnswerWait.toMillis()

    init {
        require(resends.zipWithNext().all { (a, b) -> a < b } && resends.all { it in 1 until end })
        require(floor >= 0)
    }

    /**
     * The exchange: [first] makes the first deposit within its bound (false: not made in time), [again] a re-send within
     * its bound, and [answerWithin] waits up to its bound for the answer (null: none by then). The answer, or null for none.
     */
    suspend fun <T : Any> run(
        elapsed: () -> Long,
        first: suspend (boundMs: Long) -> Boolean,
        again: suspend (boundMs: Long) -> Unit,
        answerWithin: suspend (ms: Long) -> T?,
    ): T? {
        val t0 = elapsed()
        val left = { at: Long -> (at - (elapsed() - t0)).coerceAtLeast(0) }
        if (!first(left(end))) return null
        val firstDone = elapsed()
        var lastDone = firstDone
        for (at in resends) {
            if (at <= lastDone - t0) continue // passed while the last copy was being made
            answerWithin(left(at))?.let { return it }
            again(left(end))
            lastDone = elapsed()
        }
        val atLeast = (floor - (elapsed() - firstDone)).coerceAtLeast(0)
        return answerWithin(maxOf(left(end), atLeast))
    }
}
