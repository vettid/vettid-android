package com.vettid.core.data.vault

import com.vettid.core.data.vault.RelaySafetyNet.Scrubbed

/**
 * The process's default uncaught-exception handler (the owner's rule, approved 2026-10-23): a crash anywhere outside
 * the app scope's coroutines ([RelaySafetyNet] covers those) reaches the handler that was the default before, so
 * Android's own (it logs, records the crash and ends the process), with [Scrubbed] in its place: the class names
 * and stack frames of the whole chain, never a message, because a message may carry vault data (a name, an item, a
 * relay answer).
 *
 * - Never swallowed: [previous] is called exactly once for every crash, with the scrubbed copy.
 * - If scrubbing itself fails (a throwable whose frames cannot be read, an error while the copy is made), [previous]
 *   gets the class name alone, with no frames; if even that fails, [ScrubFailed]. Nothing calls back into this
 *   handler, so a failure here cannot recurse.
 * - A [Scrubbed] (RelaySafetyNet's crash path hands one to the thread's handler, which ends here) is passed on as it
 *   is.
 *
 * A thread with an uncaught-exception handler of its own does not reach this one; none of the app's threads has one.
 * Android's logging pre-handler (RuntimeInit's "FATAL EXCEPTION" line in logcat) runs before any default handler and
 * cannot be replaced by an app: it still logs the original throwable.
 */
class CrashScrubber internal constructor(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val scrub: (Throwable) -> Throwable = { Scrubbed.of(it, keepFrames = true) },
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        val s = scrubbed(e)
        val p = previous
        if (p != null) {
            p.uncaughtException(t, s)
        } else {
            // No handler before this one (a plain JVM): what ThreadGroup.uncaughtException would print.
            System.err.print("Exception in thread \"${t.name}\" ")
            s.printStackTrace(System.err)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // the crash itself is passed on, never lost
    private fun scrubbed(e: Throwable): Throwable {
        if (e is Scrubbed) return e
        return try {
            scrub(e)
        } catch (_: Throwable) {
            try {
                ScrubFailed(e.javaClass.name)
            } catch (_: Throwable) {
                NONE
            }
        }
    }

    /** What is passed on when a crash could not be scrubbed: the class name of the throwable only, no frames. */
    class ScrubFailed(className: String) : RuntimeException(className, null, false, false)

    companion object {
        private val lock = Any()

        /** Made with the handler (at [install]): what is passed on when not even a [ScrubFailed] could be made. */
        private val NONE = ScrubFailed("unknown")

        /**
         * Installs the handler, once per process (VettIdApplication.attachBaseContext, before any app code runs). A
         * second call changes nothing: the handler is never chained to itself.
         */
        fun install() {
            synchronized(lock) {
                val current = Thread.getDefaultUncaughtExceptionHandler()
                if (current is CrashScrubber) return
                Thread.setDefaultUncaughtExceptionHandler(CrashScrubber(current))
            }
        }
    }
}
