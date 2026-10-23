package com.vettid.core.data.vault

/**
 * The steps of a clear on a security path (a lock, the hold or due state, a forget, a deletion, a wipe), run in order
 * whatever one of them throws (the owner's rule: fail secure, every clearing step runs, nothing left live). The first
 * failure is rethrown by [finish], after every step ran, with any later ones attached as suppressed: no failure skips a
 * later step and none goes unseen.
 */
internal class ClearSteps {
    @PublishedApi
    internal var failure: Throwable? = null

    /** Runs [block]; a throwable it throws (a cancellation too) is kept for [finish] and the next step runs. */
    @Suppress("TooGenericExceptionCaught") // whatever a step throws, the next one runs; [finish] rethrows it
    inline fun step(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            noted(t)
        }
    }

    @PublishedApi
    internal fun noted(t: Throwable) {
        val f = failure
        if (f == null) failure = t else if (f !== t) f.addSuppressed(t)
    }

    /** After the last step: rethrows the first failure, if any. */
    fun finish() {
        throw takeFailure() ?: return
    }

    /** After the last step: the first failure, if any (later ones suppressed), for a caller that acts on it first. */
    fun takeFailure(): Throwable? {
        val f = failure
        failure = null
        return f
    }
}
