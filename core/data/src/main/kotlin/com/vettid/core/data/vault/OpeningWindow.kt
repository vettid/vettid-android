package com.vettid.core.data.vault

import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The time between an unlock's result and the phase it publishes, when the app sends relay requests in it: the owner
 * check after an unlock that cancelled a recovery (release R), and the status read and check of a vault past its
 * owner-check deadline (§3.6.5). The phase is still [AppPhase.Locked] then, and the on-phone notification service
 * closes the relay connection while the vault is locked ([VaultManager.pauseRelay]); without the collection, those
 * requests waited for answers nobody read, three times the request timeout (90 s).
 *
 * In the window:
 * - the mailbox collection runs, started if the relay was paused, and a pause asked for meanwhile waits for its end
 *   ([deferPause]);
 * - every event the vault sends is held back by the device ([Port.holdEvents]): only answers to requests are handled,
 *   so nothing of the vault reaches the app's lists, its notifications or the calls while the phone is locked.
 *
 * At its end, by the phase then:
 * - open ([AppPhase.Unlocked], or a [AppPhase.Setup] stage): the held events are handed on as if they came now;
 * - still locked (a lock asked for in the window): only the vault's state is taken from them ([Port.takeState]: a lock
 *   notice, the owner check, a replacement), as while a lock is pending; the lists are read again at the next unlock.
 *   The relay is closed again if it was paused, or if a pause was asked for in the window;
 * - anything else (signed out, a wipe): they are dropped.
 */
internal class OpeningWindow(private val port: Port) {
    /** What the window needs of the vault manager and the device. */
    interface Port {
        /** The phase now. */
        val phase: AppPhase

        /** The notification service closed the relay while the vault was locked. */
        val relayPaused: Boolean

        fun holdEvents()

        /** Ends the hold; the held events when not [deliver]ed ([com.vettid.core.vault.VaultDevice.endHold]). */
        suspend fun endHold(deliver: Boolean): List<VaultMessage>

        /** Starts the collection for the window (the relay stays marked paused: the open resumes it). */
        suspend fun startCollecting()

        /** Closes the relay again after a window that did not open, when it was paused or a pause was asked for in it. */
        fun pauseAgain()

        /** What the vault's state needs of events held while the phone stayed locked. */
        suspend fun takeState(events: List<VaultMessage>)
    }

    private var depth = 0
    private var pauseAsked = false

    /** Whether a window is open. */
    val open: Boolean @Synchronized get() = depth > 0

    /** A pause of the relay asked for now: true when it waits for the end of the window (and must not run now). */
    @Synchronized
    fun deferPause(): Boolean {
        if (depth == 0) return false
        pauseAsked = true
        return true
    }

    /** Runs [block] (the requests before the open, and the phase it publishes) in the window. */
    suspend fun <T> run(block: suspend () -> T): T {
        synchronized(this) { depth++ }
        port.holdEvents()
        val wasPaused = port.relayPaused
        try {
            if (wasPaused) port.startCollecting()
            return block()
        } finally {
            withContext(NonCancellable) { finish(wasPaused) }
        }
    }

    private suspend fun finish(wasPaused: Boolean) {
        // The last window to end decides; an inner one leaves the hold and the relay to the outer one.
        val (last, asked) = synchronized(this) {
            depth--
            if (depth > 0) false to false else true to pauseAsked.also { pauseAsked = false }
        }
        val p = port.phase
        val opened = opens(p)
        val held = port.endHold(deliver = opened)
        if (!last || opened || p != AppPhase.Locked) return
        port.takeState(held)
        if (wasPaused || asked) port.pauseAgain()
    }

    companion object {
        /** Whether events may be handed on in [phase]: the vault is open on this phone. */
        fun opens(phase: AppPhase): Boolean = phase == AppPhase.Unlocked || phase is AppPhase.Setup
    }
}
