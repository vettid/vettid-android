package com.vettid.core.data.social

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind

/**
 * When the pending-asks file (the owner's narrow exception, 2026-10-27) survives a step that clears the open vault
 * (owner decision 2026-10-30, finding 5: an offline start is not a lock). Fail secure: it is kept only when ALL hold,
 * every other case deletes it.
 * - The phase being handled is [AppPhase.Unreachable] and the one before it was [AppPhase.Starting]: a cold start. A
 *   Locked (or any other) phase that is followed by Unreachable is a lock, and its file goes.
 * - No lock is pending ([lockPending]: a lock the member asked for, or a held phone).
 * - The kind is one that says the phone could not reach the service, not one that says something is wrong with the
 *   account, the vault or this phone.
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

    fun keeps(previous: AppPhase, phase: AppPhase, lockPending: Boolean): Boolean =
        phase is AppPhase.Unreachable && previous == AppPhase.Starting && !lockPending && phase.failure in OFFLINE_KINDS
}
