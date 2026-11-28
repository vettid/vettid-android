package com.vettid.core.data.vault

/**
 * (#19721 step 4) "Unlock again" on "Your vault isn't answering": the phone shows the unlock screen while the member API
 * still counts the vault as running (`unlocked`). [holds] keeps it locked ([VaultManager]'s status reading, and nothing the
 * vault sends is taken into the app's lists meanwhile) until this phone's own unlock opens the vault ([unlockOk]), an
 * opening while still open ([opened]), or the vault leaves this phone ([onPhase]: a wipe or a sign-out, then a setup). Nothing
 * else ends it: not the vault answering again (the member's PIN does; owner decision a, #19767: the app never unlocks by
 * itself). Memory only: a new process goes by the status again.
 */
internal class UnlockAgainHold {
    @Volatile
    private var held = false

    /** Whether this phone stays locked whatever the member API says. */
    val holds: Boolean get() = held

    /**
     * The member tapped Unlock again: held, and only then [showLocked] (the lock's publish), so that no status reading
     * between the two can open the app again.
     */
    fun ask(showLocked: () -> Unit) {
        held = true
        showLocked()
    }

    /** An unlock's sealed `ok` result: the vault answered this phone's unlock. */
    fun unlockOk() {
        held = false
    }

    /** An opening's steps ran; only [stillOpen] (the phase now): a late opening of a phase since closed ends nothing. */
    fun opened(stillOpen: Boolean) {
        if (stillOpen) held = false
    }

    /**
     * Each phase the app publishes: [AppPhase.SignedOut] (a wipe, a sign-out, the end of a deletion) or a setup stage (what
     * follows a SignedOut the phase flow may have conflated away) ends it; nothing else does. Not [AppPhase.Deleting]: a
     * member API `410` publishes it, and a later status that lists the vault goes back to Locked, which must still be held
     * (else the next status read, `unlocked`, would open the app without the PIN).
     */
    fun onPhase(p: AppPhase) {
        if (p == AppPhase.SignedOut || p is AppPhase.Setup) held = false
    }

    companion object {
        /** Whether a status that says [statusUnlocked] opens the app: never while [held]. */
        fun opens(statusUnlocked: Boolean, held: Boolean): Boolean = statusUnlocked && !held

        /** Whether what the vault sends is taken into the app's lists: as the lock guard says ([lockTakes]), and never while [held]. */
        fun takesEvents(lockTakes: Boolean, held: Boolean): Boolean = lockTakes && !held
    }
}
