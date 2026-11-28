package com.vettid.core.data.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** (#19721 step 4) What holds the phone locked after "Unlock again", and what alone ends it. */
class UnlockAgainHoldTest {
    @Test
    fun nothingIsHeldBeforeUnlockAgain() {
        assertFalse(UnlockAgainHold().holds)
    }

    /** Held before the lock is published, so that a status reading in between cannot open the app. */
    @Test
    fun itHoldsBeforeTheLockIsShown() {
        val h = UnlockAgainHold()
        var heldWhenShown = false
        h.ask { heldWhenShown = h.holds }
        assertTrue(heldWhenShown)
        assertTrue(h.holds)
    }

    @Test
    fun theUnlocksOkEndsIt() {
        val h = UnlockAgainHold()
        h.ask {}
        h.unlockOk()
        assertFalse(h.holds)
    }

    @Test
    fun anOpeningEndsItOnlyWhileStillOpen() {
        val h = UnlockAgainHold()
        h.ask {}
        h.opened(stillOpen = false) // a late opening of a phase that closed since (Unlocked, Unreachable, Unlocked)
        assertTrue(h.holds)
        h.opened(stillOpen = true)
        assertFalse(h.holds)
    }

    /** A wipe, a sign-out or a deletion: the next vault on this phone (a transfer in, a new setup) is not held. */
    @Test
    fun theVaultLeavingThisPhoneEndsIt() {
        val h = UnlockAgainHold()
        h.ask {}
        h.forgotten()
        assertFalse(h.holds)
    }

    @Test
    fun askingAgainHoldsAgain() {
        val h = UnlockAgainHold()
        h.ask {}
        h.unlockOk()
        h.ask {}
        assertTrue(h.holds)
    }
}
