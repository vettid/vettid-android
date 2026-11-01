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
     * [v], unless a lock, a refusal or a wipe came since [mark] or a lock is pending now: checked and published as one
     * step. False when it was not published (the phase that came between stands).
     */
    fun publishAfter(mark: Int, v: AppPhase): Boolean = phase.setIf(v) { mark == generation() && !lockPending() }

    /** Refused deposits: Locked, whatever the phase, after the generation moved. */
    fun refused() {
        bumpGeneration()
        phase.value = AppPhase.Locked
    }

    /**
     * The vault's own lock (or a status read that finds it locked): the generation moves whatever the phase (an unlock
     * still answering publishes nothing after it), and Locked only over an open or a setup phase, read with the publish
     * (a wipe in between stays SignedOut). True when it published Locked.
     */
    fun lockedByVault(): Boolean {
        bumpGeneration()
        return phase.setIf(AppPhase.Locked) { phase.value.let { it is AppPhase.Unlocked || it is AppPhase.Setup } }
    }
}
