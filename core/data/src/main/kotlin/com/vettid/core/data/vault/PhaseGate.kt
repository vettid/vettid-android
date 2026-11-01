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
     * [v], unless a lock, a refusal or a wipe came since [mark], or a lock is pending now and [v] would open the vault
     * here (Unlocked, a setup stage): checked and published as one step. Over a pending lock, Locked, Unreachable and
     * SignedOut still go out (#244 re-review N1: a start whose `evaluate` failed would otherwise stay on Starting, with a
     * pending lock that holds the phone and publishes no phase). False when it was not published.
     */
    fun publishAfter(mark: Int, v: AppPhase): Boolean =
        phase.setIf(v) { mark == generation() && (!opens(v) || !lockPending()) }

    /**
     * FINISHING at the end of setup steps that suspend (enrolment, a recovered credential, a transfer to this phone): as
     * [publishAfter], and only while the phase is still a setup one (never over Locked, SignedOut, an open app).
     */
    fun publishFinishing(mark: Int): Boolean = phase.setIf(FINISHING) {
        mark == generation() && !lockPending() && phase.value is AppPhase.Setup
    }

    /**
     * RECOVERING after a recovery registration's vault calls (#244 nit N3): only while the phase is still the onboarding's
     * (signed out or a setup stage, where the recovery flow runs), no lock is pending, and nothing moved the generation since
     * [mark]. The registration that resets the record takes its mark after that reset, so its own reset never refuses it; a
     * lock, a wipe or another reset during its vault calls does. The flow's answer is not changed: a phase left as the lock or
     * wipe set it is what the screens follow, and the recovery's unlock checks again (`recoveryUnlock`).
     */
    fun publishRecovering(mark: Int): Boolean = publishSetup(mark, RECOVERING)

    /**
     * [v], a setup stage that a flow of the onboarding publishes after its vault calls (RECOVERING, and NEW_VAULT after a setup
     * code's redeem): as [publishRecovering]. [NO_MARK] (the flow's reset found the record shut) never matches.
     */
    fun publishSetup(mark: Int, v: AppPhase.Setup): Boolean = phase.setIf(v) {
        mark == generation() && !lockPending() && onboarding(phase.value)
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

    companion object {
        val FINISHING = AppPhase.Setup(SetupStage.FINISHING)
        val RECOVERING = AppPhase.Setup(SetupStage.RECOVERING)

        /** A mark no generation equals (the generation only counts up from 0): what publishes nothing. */
        const val NO_MARK = -1

        /** Where the onboarding (and so the recovery flow) is shown: signed out, or a setup stage. */
        fun onboarding(p: AppPhase): Boolean = p == AppPhase.SignedOut || p is AppPhase.Setup

        /** A phase that is the vault open here, or on its way to it: never over a pending lock. */
        fun opens(p: AppPhase): Boolean = p is AppPhase.Unlocked || p is AppPhase.Setup

        fun closesOnNotice(p: AppPhase): Boolean = when (p) {
            AppPhase.Unlocked, AppPhase.Starting, is AppPhase.Setup, is AppPhase.Unreachable -> true
            AppPhase.Locked, AppPhase.SignedOut, is AppPhase.Deleting -> false
        }
    }
}

/** What an unlock whose vault call succeeded reports (#244 review B3): never Success when nothing opened here. */
internal object UnlockEnd {
    fun attempt(opened: Boolean, lockPending: Boolean, refusedUpdate: String?): UnlockAttempt = when {
        // A lock (pending, or the vault's own) or a wipe came first: the phase it published stands, and the screen says so.
        !opened -> UnlockAttempt.Failed(
            FailureKind.OTHER,
            if (lockPending) MoveRepository.CODE_LOCK_PENDING else MoveRepository.CODE_NOT_OPENED,
        )
        refusedUpdate != null -> UnlockAttempt.UpdateRefused(refusedUpdate)
        else -> UnlockAttempt.Success
    }
}
