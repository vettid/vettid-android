package com.vettid.core.data.vault

import kotlinx.coroutines.Job

/**
 * (ANDROID-PLAN 0.1.54) The deletion's bound from this phone's own `vault.delete` requests: when the latest one expires
 * ([expMs], epoch ms, memory only), and how many are still out. "Out" counts the requests and every vault word
 * being decided with a 410 standing ([hold]), whichever route brought it (an answer or the `device.unlinked` event).
 * Every change is made under [lock] (the one the deletion's starts and ends run under), so a clear never takes the bound
 * from under a request whose answer still needs it.
 *
 * A request is out from [begin] to [end]; an answer that hands its decision to another coroutine (the vault's word with a
 * 410 standing) stays out until that coroutine has decided, so the bound is still there if it starts "being deleted".
 */
internal class DeleteBound(private val lock: Any, private val now: () -> Long = System::currentTimeMillis) {
    private var out = 0
    private var exp: Long? = null

    /** When this phone's latest `vault.delete` expires, or null when no bound is kept. */
    val expMs: Long? get() = synchronized(lock) { exp }

    /** How many of this phone's requests, and of the words being decided ([hold]), are out. */
    val requestsOut: Int get() = synchronized(lock) { out }

    /** A request is sent: counted, and its `exp` is the bound (ChangeTypes.EXP after it is sent, on this phone's clock). */
    fun begin(expiresAfterMs: Long) = synchronized(lock) {
        out++
        exp = now() + expiresAfterMs
    }

    /**
     * A request is done with (answered and decided, refused, failed or cancelled). When it was the last one out and no
     * deletion is up ([deleting], read under [lock]), no bound is left to keep.
     */
    fun end(deleting: () -> Boolean) = synchronized(lock) {
        // Never below 0 (an end without its begin would be a bug; it may run in a completion handler, so it does not throw).
        if (out > 0) out--
        if (out == 0 && !deleting()) exp = null
    }

    /**
     * The vault's word with a 410 standing, handed to [job] to decide: counted as a request out until the job ends, so a
     * clear meanwhile keeps the bound for the "being deleted" the word may start, however many times the word is handed
     * on (a 410, a clear, another 410 in the deciding window). Then done with as [end].
     */
    fun hold(job: Job, deleting: () -> Boolean) {
        synchronized(lock) { out++ }
        job.invokeOnCompletion { end(deleting) }
    }

    /** A clear of a 410-only state: the bound goes unless a request is still out (its answer still needs it). */
    fun clearUnlessOut() = synchronized(lock) {
        if (out == 0) exp = null
    }

    /** The vault gone or a wipe: no later deletion's bound comes from what is kept. */
    fun forget() = synchronized(lock) {
        exp = null
    }
}
