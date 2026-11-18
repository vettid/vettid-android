package com.vettid.core.data.vault

/** The rules around "Finish the move" and leaving onboarding (release R, §11.14.10). */
internal object FinishSetupGate {
    /**
     * Onboarding may give way to the open app only from [SetupStage.FINISHING], with no lock pending and no "Finish the
     * move" running: otherwise the shell would show over a vault that is (or is about to be) locked.
     */
    fun allows(phase: AppPhase, lockPending: Boolean, finishingMove: Boolean): Boolean =
        (phase as? AppPhase.Setup)?.stage == SetupStage.FINISHING && !lockPending && !finishingMove

    /** What the unlock screen says after a "Finish the move" that did not open the vault; null after a success. */
    fun failureOf(r: UnlockAttempt): MoveFailure? = when (r) {
        UnlockAttempt.Success -> null
        is UnlockAttempt.BadPin -> MoveFailure(FailureKind.BAD_PIN)
        is UnlockAttempt.Backoff -> MoveFailure(FailureKind.BACKOFF)
        is UnlockAttempt.Failed -> MoveFailure(r.kind, r.code)
        else -> MoveFailure(FailureKind.OTHER)
    }
}

/**
 * Why a "Finish the move" did not open the vault, for the unlock screen: the kind, and the code where the failure had one
 * (`no_user_guid` from the unlock's own guard is told apart by both).
 */
data class MoveFailure(val kind: FailureKind, val code: String? = null)
