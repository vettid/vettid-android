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
    fun failureOf(r: UnlockAttempt): FailureKind? = when (r) {
        UnlockAttempt.Success -> null
        is UnlockAttempt.BadPin -> FailureKind.BAD_PIN
        is UnlockAttempt.Backoff -> FailureKind.BACKOFF
        is UnlockAttempt.Failed -> r.kind
        else -> FailureKind.OTHER
    }
}
