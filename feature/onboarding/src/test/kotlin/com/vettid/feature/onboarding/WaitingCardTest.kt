package com.vettid.feature.onboarding

import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.WaitingCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * The gated check's waiting card (VAULT-MESSAGING §3.6.5): shown whenever the check gates the app, also when only
 * the phone's clock has passed the deadline; counts the vault has not reported are unknown, never "nothing new".
 */
class WaitingCardTest {
    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val ok = OwnerCheckView(OwnerCheckState.OK, now.minusSeconds(60), 86_400, 0, hold = true, holdOffUntil = null)

    @Test
    fun unknownIsNotZero() {
        val unknown = waitingCardOf(OwnerCheckMode.GATED, ok.copy(state = OwnerCheckState.HELD), now)!!
        assertNull(unknown.counts)
        assertEquals(true, unknown.held)
        val zero = waitingCardOf(OwnerCheckMode.GATED, ok.copy(state = OwnerCheckState.HELD, waiting = WaitingCounts()), now)!!
        assertEquals(WaitingCounts(), zero.counts)
        val some = waitingCardOf(OwnerCheckMode.GATED, ok.copy(waiting = WaitingCounts(3, 1, 0, 2)), now)!!
        assertEquals(WaitingCounts(3, 1, 0, 2), some.counts)
    }

    @Test
    fun shownWheneverTheGateIsUp() {
        // Past the deadline by the clock while the vault still said ok (the app came back after it): shown, unknown.
        val byClock = waitingCardOf(OwnerCheckMode.GATED, ok, now)!!
        assertNull(byClock.counts)
        assertEquals(true, byClock.held)
        // The hold is off: due, with its note.
        assertEquals(false, waitingCardOf(OwnerCheckMode.GATED, ok.copy(state = OwnerCheckState.DUE, hold = false), now)!!.held)
        assertEquals(false, waitingCardOf(OwnerCheckMode.GATED, ok.copy(hold = false), now)!!.held)
        // Nothing known of the vault at all: still the card, without a note.
        assertEquals(WaitingCardModel(null, null), waitingCardOf(OwnerCheckMode.GATED, null, now))
        // Not for a check the member asked for.
        assertNull(waitingCardOf(OwnerCheckMode.VOLUNTARY, ok, now))
        assertNull(waitingCardOf(OwnerCheckMode.HOLD_OFF, ok, now))
    }
}
