package com.vettid.core.data.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.io.IOException

/**
 * One event of a long-lived collector of the process scope that a lock, the hold or an unlock depends on (VaultManager).
 * Without this, one exception ends the collector for the rest of the process: the scope's [RelaySafetyNet] only logs an
 * [IOException], so every later lock would leave the vault's data in memory (fails open).
 *
 * Fail secure:
 * - a [CancellationException] is rethrown (the collector is ending);
 * - an [IOException] (a relay or transport error) is reported to [report], with the collector's name and the
 *   exception's class only (never its message, which may carry vault data), and the collector goes on to the next event;
 * - anything else is rethrown, so that a real bug still crashes the app. A clear runs its steps through [ClearSteps],
 *   which rethrows only after every step ran: nothing is skipped before the crash.
 *
 * An [IOException] that [ClearSteps] rethrows with a later failure attached (suppressed) that is not one is rethrown
 * too: a cancellation as itself, a bug as the [IOException] carrying it.
 */
internal inline fun surviveEvent(what: String, report: (String, IOException) -> Unit, block: () -> Unit) {
    try {
        block()
    } catch (e: IOException) {
        val worse = e.suppressed.firstOrNull { it !is IOException }
        if (worse is CancellationException) throw worse
        if (worse != null) throw e
        report(what, e)
    }
}

/** [Flow.collect], each event run through [surviveEvent]: an [IOException] on one event does not end the collector. */
internal suspend fun <T> Flow<T>.collectSurviving(
    what: String,
    report: (String, IOException) -> Unit = ::logSurvived,
    action: suspend (T) -> Unit,
) = collect { v -> surviveEvent(what, report) { action(v) } }

/** The default report: the collector's name and the exception's class, nothing from the vault. */
internal fun logSurvived(what: String, e: IOException) {
    android.util.Log.w("VaultManager", "$what: an event not handled: ${e.javaClass.simpleName}")
}

/**
 * What a lock (or a sign-out) clears from memory, in order (VaultManager's phase collector): each step runs through
 * [ClearSteps], so a step that throws never skips a later one.
 */
internal class LockClears(private val steps: List<() -> Unit>) {
    /** Locked or signed out (any phase but [AppPhase.Unlocked] and [AppPhase.Starting]): every step, into [into]. */
    fun onPhase(phase: AppPhase, into: ClearSteps) {
        if (phase == AppPhase.Unlocked || phase == AppPhase.Starting) return
        for (s in steps) into.step { s() }
    }
}
