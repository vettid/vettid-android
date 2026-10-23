package com.vettid.core.data.vault

import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The process scope's last line of defence (W9 staging, 2026-10-06: a relay `token_revoked` that escaped the
 * mailbox collector killed the old phone at every launch). A relay or transport error ([IOException], which
 * includes `RelayException`) that escapes a coroutine of the scope is reported to [onRelayError] and goes no
 * further: no relay answer may ever end the process. Every other throwable goes to [fatal], by default the
 * thread's uncaught-exception handler, so that real bugs still crash as before.
 */
class RelaySafetyNet(
    private val onRelayError: (IOException) -> Unit,
    private val fatal: (Throwable) -> Unit = crash(keepFrames = false),
) : AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {
    override fun handleException(context: CoroutineContext, exception: Throwable) {
        if (exception is IOException) onRelayError(exception) else fatal(exception)
    }

    /**
     * What may be logged of a failure (the owner's rule, approved 2026-10-23): exception class names only. A message may
     * carry vault data (a relay answer, a name, an item), so none is kept: not the exception's, not its causes', not its
     * suppressed ones'.
     */
    class Scrubbed private constructor(className: String) : RuntimeException(className) {
        companion object {
            /**
             * [t] and its whole chain (causes and suppressed) with the class names only; the stack frames (classes,
             * methods, lines: no data) only with [keepFrames].
             */
            fun of(t: Throwable, keepFrames: Boolean): Scrubbed =
                of(t, keepFrames, Collections.newSetFromMap(IdentityHashMap()))

            private fun of(t: Throwable, keepFrames: Boolean, seen: MutableSet<Throwable>): Scrubbed {
                seen += t
                val s = Scrubbed(t.javaClass.name)
                s.stackTrace = if (keepFrames) t.stackTrace else emptyArray()
                t.cause?.takeIf { it !in seen }?.let { s.initCause(of(it, keepFrames, seen)) }
                t.suppressed.filter { it !in seen }.forEach { s.addSuppressed(of(it, keepFrames, seen)) }
                return s
            }
        }
    }

    companion object {
        /** The line logged for a relay error that escaped: its class name only. */
        fun relayLine(e: IOException): String = "relay error not handled: ${e.javaClass.simpleName}"

        /**
         * The fatal handler: [Scrubbed] goes to the thread's uncaught-exception handler (the app crashes as before). The
         * stack frames only with [keepFrames]: the app keeps them in every build (the owner, 2026-10-23), so a crash
         * reaches logcat and any crash report with class names and frames, never a message.
         */
        fun crash(keepFrames: Boolean): (Throwable) -> Unit = { e ->
            val s = Scrubbed.of(e, keepFrames)
            val t = Thread.currentThread()
            t.uncaughtExceptionHandler?.uncaughtException(t, s) ?: throw s
        }
    }
}
