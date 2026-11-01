package com.vettid.core.data.vault

/**
 * How [VaultManager] closes the phase for a lock it did not make, and publishes a phase computed across vault calls
 * that suspend (#242, and its follow-up). A lock that is not this phone's (the vault's `vault.locking`, a status read
 * that finds it locked, refused deposits) moves the generation before it publishes Locked, as a lock here does. Work
 * that took a [mark] before its calls then publishes nothing over it: the end of an unlock, of enrolment, of a
 * recovered credential, of a transfer to this phone. Without that, FINISHING over Locked was opened by
 * [FinishSetupGate] over a locked vault.
 */
internal class PhaseGate(
    private val phase: PhaseHolder,
    private val generation: () -> Int,
    private val bumpGeneration: () -> Unit,
    /** A lock pending here ([LockGuard.lockedWhatever] with `unlocked = true`): takes no lock. */
    private val lockPending: () -> Boolean,
) {
    /** Taken before the vault's calls; [publishAfter] compares with it. */
    fun mark(): Int = generation()

    /**
     * [v], unless a lock, a refusal or a wipe came since [mark], or a lock is pending now and [v] is not Locked (Locked
     * over a pending lock is what that lock wants): checked and published as one step. False when it was not published
     * (the phase that came between stands).
     */
    fun publishAfter(mark: Int, v: AppPhase): Boolean =
        phase.setIf(v) { mark == generation() && (v == AppPhase.Locked || !lockPending()) }

    /**
     * FINISHING at the end of setup steps that suspend (enrolment, a recovered credential, a transfer to this phone): as
     * [publishAfter], and only while the phase is still a setup one (never over Locked, SignedOut, an open app).
     */
    fun publishFinishing(mark: Int): Boolean = phase.setIf(FINISHING) {
        mark == generation() && !lockPending() && phase.value is AppPhase.Setup
    }

    /** Another setup stage during enrolment ([v]): only while the phase is still a setup one. */
    fun setupStage(v: AppPhase.Setup): Boolean = phase.setIf(v) { phase.value is AppPhase.Setup }

    /** Refused deposits: the open app goes to the unlock screen, after the generation moved (a wipe's SignedOut stays). */
    fun refused(): Boolean {
        bumpGeneration()
        return phase.setIf(AppPhase.Locked) { phase.value is AppPhase.Unlocked }
    }

    /**
     * The vault's own lock (or a status read that finds it locked): the generation moves whatever the phase (an unlock
     * still answering publishes nothing after it), and Locked over every phase but SignedOut, Locked and "being
     * deleted", read with the publish (a wipe in between stays SignedOut). Over Starting and Unreachable too: the
     * notice ends the start's own refresh (the generation moved), and nothing else would leave those phases. True when
     * it published Locked.
     */
    fun lockedByVault(): Boolean {
        bumpGeneration()
        return phase.setIf(AppPhase.Locked) { closesOnNotice(phase.value) }
    }

    private companion object {
        val FINISHING = AppPhase.Setup(SetupStage.FINISHING)

        fun closesOnNotice(p: AppPhase): Boolean = when (p) {
            AppPhase.Unlocked, AppPhase.Starting, is AppPhase.Setup, is AppPhase.Unreachable -> true
            AppPhase.Locked, AppPhase.SignedOut, is AppPhase.Deleting -> false
        }
    }
}
