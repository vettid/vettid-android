package com.vettid.core.data.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * (ANDROID-PLAN 0.1.132) When the waiting asks are notified again: a check passed with the vault open (its count rose)
 * ends the held or due state, also one known only from the clock; never the check an unlock makes.
 */
class WaitingAsksReplayTest {
    private val at = Instant.parse("2026-11-15T15:00:00Z")

    private fun view(state: OwnerCheckState, deadline: Instant?, passes: Int) =
        OwnerCheckView(state, deadline, 86_400, 0, hold = true, holdOffUntil = null, passes = passes)

    private val ahead = at.plusSeconds(86_400)
    private val past = at.minusSeconds(60)

    @Test
    fun aPassAfterAHoldOrDueStateReplays() {
        assertTrue(WaitingAsksReplay.due(view(OwnerCheckState.HELD, past, 3), view(OwnerCheckState.OK, ahead, 4), at, unlocked = true))
        assertTrue(WaitingAsksReplay.due(view(OwnerCheckState.DUE, past, 3), view(OwnerCheckState.OK, ahead, 4), at, unlocked = true))
    }

    /** The due state known only from the clock: the last value was OK, its deadline passed, then a check passed. */
    @Test
    fun aPassAfterADueStateKnownOnlyFromTheClockReplays() {
        assertTrue(WaitingAsksReplay.due(view(OwnerCheckState.OK, past, 3), view(OwnerCheckState.OK, ahead, 4), at, unlocked = true))
    }

    /** Conflated: the held value and the pass's OK seen as one step still replay; another device's pass is the same. */
    @Test
    fun aConflatedPassStillReplays() {
        assertTrue(WaitingAsksReplay.due(view(OwnerCheckState.HELD, null, 7), view(OwnerCheckState.OK, ahead, 9), at, unlocked = true))
    }

    /**
     * The review's probe: an earlier pass (count 4), a lock, a status OK whose deadline the phone's clock has passed, then
     * the unlock's own check (count still 4): no replay.
     */
    @Test
    fun theUnlocksOwnCheckAfterAnEarlierPassDoesNotReplay() {
        assertFalse(WaitingAsksReplay.due(view(OwnerCheckState.OK, past, 4), view(OwnerCheckState.OK, ahead, 4), at, unlocked = true))
    }

    @Test
    fun nothingWithoutAnEndOrOpenVault() {
        // Still gated, not gated before, locked, or nothing before.
        assertFalse(WaitingAsksReplay.due(view(OwnerCheckState.HELD, past, 3), view(OwnerCheckState.DUE, past, 4), at, unlocked = true))
        assertFalse(WaitingAsksReplay.due(view(OwnerCheckState.OK, ahead, 3), view(OwnerCheckState.OK, ahead, 4), at, unlocked = true))
        assertFalse(WaitingAsksReplay.due(view(OwnerCheckState.HELD, past, 3), view(OwnerCheckState.OK, ahead, 4), at, unlocked = false))
        assertFalse(WaitingAsksReplay.due(null, view(OwnerCheckState.OK, ahead, 4), at, unlocked = true))
    }
}
