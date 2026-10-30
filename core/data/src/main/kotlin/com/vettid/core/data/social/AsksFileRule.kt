package com.vettid.core.data.social

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind

/**
 * When the pending-asks file (the owner's narrow exception, 2026-10-27) survives a step that clears the open vault
 * (owner decision 2026-10-30, finding 5: an offline start is not a lock). Fail secure: it is kept only while the process
 * is still on the cold-start offline path, and every other case deletes it. See [Track].
 */
internal object AsksFileRule {
    /** The kinds that mean only "offline or the service is not answering". */
    val OFFLINE_KINDS: Set<FailureKind> = setOf(
        FailureKind.NETWORK,
        FailureKind.NO_RESPONSE,
        FailureKind.SERVICE_PAUSED,
        FailureKind.VAULT_UNAVAILABLE,
        FailureKind.RATE_LIMITED,
    )

    /** A phase that belongs on the cold-start offline path: the start itself, or Unreachable of an offline kind. */
    fun isOffline(phase: AppPhase): Boolean =
        phase == AppPhase.Starting || phase is AppPhase.Unreachable && phase.failure in OFFLINE_KINDS

    /**
     * Whether this process is still on the cold-start offline path. [published] is told of every phase at the moment it is
     * published (so a phase that the collector never sees, because a later one replaced it first, still counts), and a
     * phase that is not offline (Locked, Unlocked, Setup, a signed-out or deleting phone, an Unreachable of another kind)
     * ends the path for good in this process, like a pending or held lock ([keeps]). "Try again" while still offline, with
     * another offline kind or another `longUnreachable`, stays on it.
     */
    class Track {
        @Volatile
        private var ended = false

        fun published(phase: AppPhase) {
            if (!isOffline(phase)) ended = true
        }

        /** Whether handling [phase] keeps the file: an offline Unreachable, still on the path, with no lock pending or held. */
        fun keeps(phase: AppPhase, lockPending: Boolean): Boolean {
            if (lockPending) ended = true
            return !ended && phase is AppPhase.Unreachable && phase.failure in OFFLINE_KINDS
        }
    }
}
