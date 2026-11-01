package com.vettid.core.data.vault

/**
 * (Release R) The end of a recovery the vault answered `ok`, after the member has read that text ([VaultManager]'s
 * `finishRecoveryOpen`). A recovered phone is a new install (`setupComplete` false), so the vault's unlock ends in
 * [SetupStage.FINISHING]; the `ok` text is that phone's last setup screen, so setup is finished here too. Kept apart from
 * [VaultManager], which needs an Android context, so that its rules can be tested:
 *
 * - a lock asked for since the open began, or pending now, wins: the phase is [AppPhase.Locked] (or, after a wipe,
 *   whatever the wipe said), never the open app, and the caller is told ([MoveRepository.CODE_LOCK_PENDING]);
 * - the open app only through [Ports.finishSetup] (its gate: only from FINISHING, never over a pending lock or a running
 *   "Finish the move");
 * - an open that did not end in the open app fails ([MoveRepository.CODE_NOT_OPENED]), so that the screen says so and
 *   stays, rather than staying silently.
 */
internal object RecoveryOpenFinish {
    /** What stopped this phone from opening since the open began. */
    enum class Overtaken {
        /** Nothing. */
        NO,

        /** A lock is pending: this phone is locked whatever the vault says. */
        LOCK_PENDING,

        /** A lock (already confirmed) or a wipe since the open began: the phase it published stands. */
        OTHER,
    }

    interface Ports {
        /** Checked before each step: a lock or a wipe since the open began, or a lock pending now. */
        fun overtaken(): Overtaken

        /** The phase after the vault's unlock: [AppPhase.Unlocked], or FINISHING on a phone whose setup is not complete. */
        suspend fun afterUnlocked(): AppPhase

        fun publish(phase: AppPhase)

        /** [phase] only if not [overtaken], checked and published as one step (#242); false when overtaken. */
        fun publishUnlessOvertaken(phase: AppPhase): Boolean

        /** Leaves onboarding for the open app under its gate; false when the gate refused. */
        suspend fun finishSetup(): Boolean
    }

    /** Ends in the open app, or throws a [VaultFailure] (the phase left Locked, or as a lock or a wipe set it). */
    suspend fun run(ports: Ports) {
        stopIfOvertaken(ports)
        val phase = ports.afterUnlocked()
        // Before the phase is written: a lock asked for while it was computed is the newer wish. Checked again with the
        // publish, as one step: a lock between the check and the write would otherwise be overwritten.
        stopIfOvertaken(ports)
        if (!ports.publishUnlessOvertaken(phase)) {
            stopIfOvertaken(ports)
            throw VaultFailure(FailureKind.OTHER, MoveRepository.CODE_NOT_OPENED)
        }
        when (phase) {
            AppPhase.Unlocked -> return
            AppPhase.Setup(SetupStage.FINISHING) -> if (ports.finishSetup()) return
            else -> Unit
        }
        // The gate refused (a lock asked for meanwhile, "Finish the move"), or the vault's unlock did not end where an
        // `ok` leaves it: never the open app, and the screen says so.
        stopIfOvertaken(ports)
        throw VaultFailure(FailureKind.OTHER, MoveRepository.CODE_NOT_OPENED)
    }

    private fun stopIfOvertaken(ports: Ports) {
        when (ports.overtaken()) {
            Overtaken.NO -> return
            Overtaken.LOCK_PENDING -> {
                ports.publish(AppPhase.Locked)
                throw VaultFailure(FailureKind.NO_RESPONSE, MoveRepository.CODE_LOCK_PENDING)
            }
            Overtaken.OTHER -> throw VaultFailure(FailureKind.OTHER, MoveRepository.CODE_NOT_OPENED)
        }
    }
}
