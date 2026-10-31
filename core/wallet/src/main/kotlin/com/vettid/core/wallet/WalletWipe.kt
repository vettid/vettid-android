package com.vettid.core.wallet

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The wallet's part of a live wipe (LocalWipe), with no dependency of its own so that the wipe can hold it: [onWipe] runs before
 * anything is erased from disk. It moves [generation], so that a write of the flags or the chain server setting still in flight
 * writes nothing, and it runs the in-memory clears registered with it (the wallets manager's).
 */
class WalletWipe {
    private val gen = AtomicLong()
    private val clears = CopyOnWriteArrayList<() -> Unit>()

    val generation: Long get() = gen.get()

    fun register(clear: () -> Unit) {
        clears += clear
    }

    /**
     * Every registered clear runs, even if one throws. The first [Exception] is rethrown after the last (the wipe goes on past it,
     * LocalWipe.resetMemory); an [Error] is never rethrown, so that it cannot stop the wipe.
     */
    @Suppress("TooGenericExceptionCaught")
    fun onWipe() {
        gen.incrementAndGet()
        var first: Exception? = null
        for (c in clears) {
            try {
                c()
            } catch (e: Exception) {
                if (first == null) first = e
            } catch (_: Throwable) {
                // An Error: the remaining clears still run, and the wipe goes on.
            }
        }
        first?.let { throw it }
    }
}
