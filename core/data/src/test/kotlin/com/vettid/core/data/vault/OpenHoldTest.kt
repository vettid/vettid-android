package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** (ANDROID-PLAN 0.1.93) The open that waits for another try of the daily check. */
class OpenHoldTest {
    @Test
    fun aWaitingOpenIsClaimedInItsOwnGeneration() {
        val h = OpenHold<String>()
        assertFalse(h.holding.value)
        h.hold("session", 4)
        assertTrue(h.holding.value)
        val c = h.claim(4)!!
        assertEquals("session", c.item)
        assertEquals(4, c.generation)
        // Claiming does not end the wait: a wrong password tries again.
        assertTrue(h.holding.value)
    }

    @Test
    fun aLockOrWipeSinceEndsTheWait() {
        val h = OpenHold<String>()
        h.hold("session", 4)
        assertNull(h.claim(5))
        assertFalse(h.holding.value)
        assertNull(h.claim(4))
    }

    @Test
    fun clearingEndsTheWaitAsAnotherUnlockOrAPassDoes() {
        val h = OpenHold<String>()
        h.hold("session", 1)
        h.clear()
        assertFalse(h.holding.value)
        assertNull(h.claim(1))
    }
}
