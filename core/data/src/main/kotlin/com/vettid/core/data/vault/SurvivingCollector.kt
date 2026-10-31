package com.vettid.core.data.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * One event of a long-lived collector of the process scope that a lock, the hold or an unlock depends on (VaultManager).
 * Without this, one exception ends the collector for the rest of the process: the scope's [RelaySafetyNet] only logs an
 * [IOException], so every later lock would leave the vault's data in memory (fails open).
 *
 * Fail secure:
 * - an [IOException] (a relay or transport error) is reported to [report], with the collector's name and the
 *   exception's class only (never its message, which may carry vault data), and the collector goes on to the next event;
 * - a [CancellationException] is rethrown only while the collector's own coroutine is cancelled ([active] false): the
 *   collector is ending. Any other is a bug;
 * - anything else is rethrown, so that a real bug still crashes the app. A clear runs its steps through [ClearSteps],
 *   which rethrows only after every step ran: nothing is skipped before the crash.
 *
 * An [IOException] carrying, anywhere in its suppressed chain, a failure that is not one (what [ClearSteps] attaches
 * when a later step failed too) is not reported as a relay error: the scope's [RelaySafetyNet] would only log it and
 * the collector would end. It is thrown inside a [CollectorFailure], which is not an [IOException], so the app crashes;
 * a cancellation in that chain while the collector is cancelled is rethrown as itself.
 */
internal inline fun surviveEvent(
    what: String,
    report: (String, IOException) -> Unit,
    active: () -> Boolean,
    block: () -> Unit,
) {
    try {
        block()
    } catch (e: CancellationException) {
        throw if (active()) CollectorFailure(what, e) else e
    } catch (e: IOException) {
        val worse = firstNotIo(e) ?: return report(what, e)
        throw if (worse is CancellationException && !active()) worse else CollectorFailure(what, e)
    }
}

/** The first failure in [e]'s suppressed chain (searched all the way down) that is not an [IOException]. */
@PublishedApi
internal fun firstNotIo(e: Throwable): Throwable? {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>()).apply { add(e) }
    val next = ArrayDeque(e.suppressed.asList())
    var found: Throwable? = null
    while (found == null && next.isNotEmpty()) {
        val t = next.removeFirst()
        if (!seen.add(t)) continue
        if (t is IOException) next.addAll(t.suppressed) else found = t
    }
    return found
}

/** A collector's event failed with something that is not a relay error: not an [IOException], so the app crashes. */
@PublishedApi
internal class CollectorFailure(what: String, cause: Throwable) : IllegalStateException("$what: an event failed", cause)

/** [Flow.collect], each event run through [surviveEvent]: an [IOException] on one event does not end the collector. */
internal suspend fun <T> Flow<T>.collectSurviving(
    what: String,
    report: (String, IOException) -> Unit = ::logSurvived,
    action: suspend (T) -> Unit,
) {
    val context = currentCoroutineContext()
    collect { v -> surviveEvent(what, report, { context.isActive }) { action(v) } }
}

/** The default report: the collector's name and the exception's class, nothing from the vault. */
internal fun logSurvived(what: String, e: IOException) {
    android.util.Log.w("VaultManager", "$what: an event not handled: ${e.javaClass.simpleName}")
}

/** A lock's clear failed, and failed again when run once more: not an [IOException], so the process ends (fail closed). */
internal class LockClearFailed(first: Throwable, again: Throwable) : IllegalStateException("a lock's clear failed twice", first) {
    init {
        if (again !== first) addSuppressed(again)
    }
}

/**
 * The steps of VaultManager's phase collector, in order: the held-back events' release, the unlock's refreshes (only
 * at an unlock), the lock's clears (only at a lock or a sign-out: any phase but [AppPhase.Unlocked] and
 * [AppPhase.Starting]) and the own profile's step. Each runs through [ClearSteps], so a step that throws never skips a
 * later one.
 *
 * A lock whose steps failed runs its clears once more (the owner's rule: fail closed, retry until confirmed). Cleared
 * then, the first failure is rethrown as it was ([surviveEvent]: a relay error is reported and the collector goes on, a
 * bug crashes the app). Failing again, [LockClearFailed] ends the process, and with it everything in memory.
 */
internal class PhaseSteps private constructor(
    private val replay: (AppPhase) -> Unit,
    private val lockClears: List<Pair<String, () -> Unit>>,
    private val ownProfile: (AppPhase) -> Unit,
) {
    /** What a lock runs, by name, in order (the tests check VaultManager's list through [of]). */
    val lockOrder: List<String> = listOf(REPLAY) + lockClears.map { it.first } + OWN_PROFILE

    suspend fun onPhase(phase: AppPhase, opened: suspend () -> Unit) {
        val steps = ClearSteps()
        steps.step { replay(phase) }
        if (phase == AppPhase.Unlocked) steps.step { opened() }
        val locks = phase != AppPhase.Unlocked && phase != AppPhase.Starting
        if (locks) clearInto(steps)
        steps.step { ownProfile(phase) }
        val first = steps.takeFailure() ?: return
        val second = if (locks) clearAgain(phase) else null
        throw if (second != null) LockClearFailed(first, second) else first
    }

    /** Once more: the clears and the own profile's step (the release ran; it holds nothing of the vault's). */
    private fun clearAgain(phase: AppPhase): Throwable? {
        val again = ClearSteps()
        clearInto(again)
        again.step { ownProfile(phase) }
        return again.takeFailure()
    }

    private fun clearInto(steps: ClearSteps) {
        for ((_, clear) in lockClears) steps.step { clear() }
    }

    companion object {
        const val REPLAY = "replay held back"
        const val SOCIAL = "social"
        const val ATTACHMENTS = "attachments"
        const val ITEMS = "items"
        const val SHARING = "sharing"
        const val FEED = "feed"
        const val DEVICES = "devices"
        const val VOTES = "votes"
        const val OWN_PROFILE = "own profile"

        /**
         * VaultManager's phase steps. Every clear is a required parameter: one left out does not compile. Locked or
         * signed out, no item metadata and no feed item is kept outside an open vault, and no connection or message in
         * memory either.
         */
        @Suppress("LongParameterList") // one per thing a lock clears, each named
        fun of(
            replay: (AppPhase) -> Unit,
            forgetOpenVault: () -> Unit,
            clearAttachments: () -> Unit,
            clearItems: () -> Unit,
            clearSharing: () -> Unit,
            clearFeed: () -> Unit,
            clearDevices: () -> Unit,
            clearVotes: () -> Unit,
            ownProfile: (AppPhase) -> Unit,
        ) = PhaseSteps(
            replay,
            listOf(
                SOCIAL to forgetOpenVault,
                ATTACHMENTS to clearAttachments,
                ITEMS to clearItems,
                SHARING to clearSharing,
                FEED to clearFeed,
                DEVICES to clearDevices,
                VOTES to clearVotes,
            ),
            ownProfile,
        )
    }
}
