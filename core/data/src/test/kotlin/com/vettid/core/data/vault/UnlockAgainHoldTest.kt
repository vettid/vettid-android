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

    /**
     * Only SignedOut (a wipe, a sign-out, a deletion's end) and a setup stage end it. Every other phase keeps it: Locked (the
     * very lock Unlock again publishes), Unreachable, Starting, Unlocked (an opening ends it through [UnlockAgainHold.opened]
     * only), and Deleting (a member API 410, which a later status can take back to Locked).
     */
    @Test
    fun onlySignedOutAndASetupEndIt() {
        val ends = listOf<AppPhase>(AppPhase.SignedOut) + SetupStage.entries.map { AppPhase.Setup(it) }
        val keeps = listOf(
            AppPhase.Locked, AppPhase.Unlocked, AppPhase.Starting, AppPhase.Unreachable(FailureKind.NETWORK),
            AppPhase.Deleting(), AppPhase.Deleting(apiOnly = true),
        )
        for (p in ends) {
            val h = UnlockAgainHold()
            h.ask {}
            h.onPhase(p)
            assertFalse("ends: $p", h.holds)
        }
        for (p in keeps) {
            val h = UnlockAgainHold()
            h.ask {}
            h.onPhase(p)
            assertTrue("keeps: $p", h.holds)
        }
    }

    /** A status that says unlocked opens the app only while nothing is held; one that does not never does. */
    @Test
    fun aStatusOpensTheAppOnlyWhenUnlockedAndNotHeld() {
        assertTrue(UnlockAgainHold.opens(statusUnlocked = true, held = false))
        assertFalse(UnlockAgainHold.opens(statusUnlocked = true, held = true))
        assertFalse(UnlockAgainHold.opens(statusUnlocked = false, held = false))
        assertFalse(UnlockAgainHold.opens(statusUnlocked = false, held = true))
    }

    /** The vault's events are taken only as the lock guard allows, and never while held. */
    @Test
    fun eventsAreTakenOnlyAsTheLockGuardSaysAndNotWhileHeld() {
        assertTrue(UnlockAgainHold.takesEvents(lockTakes = true, held = false))
        assertFalse(UnlockAgainHold.takesEvents(lockTakes = true, held = true))
        assertFalse(UnlockAgainHold.takesEvents(lockTakes = false, held = false))
        assertFalse(UnlockAgainHold.takesEvents(lockTakes = false, held = true))
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
