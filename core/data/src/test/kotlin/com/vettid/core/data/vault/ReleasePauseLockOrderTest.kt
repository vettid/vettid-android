package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [ReleasePauseSteps]' monitor is the innermost lock: nothing it calls while holding it reaches another lock. The lock clears
 * hold the phase holder's lock when they reset it, so a call into the phase holder under it ([ReleasePauseSteps.unlocked])
 * could deadlock against an owner-check lift.
 */
class ReleasePauseLockOrderTest {
    @Test
    fun noLookIsMadeUnderTheMonitor() {
        lateinit var steps: ReleasePauseSteps
        val looks = mutableListOf<String>()
        fun look(name: String, v: Boolean): Boolean {
            assertFalse("$name under the pause's monitor", Thread.holdsLock(steps))
            looks += name
            return v
        }
        steps = ReleasePauseSteps(
            unlocked = { look("unlocked", true) },
            checkGated = { look("checkGated", false) },
            paused = { false },
            required = { false },
            stopVotes = {},
            gateChanged = {},
            reads = { looks += "reads" },
            sends = { looks += "sends" },
            owed = { looks += "owed" },
        )
        steps.sendsAgainAtLift()
        steps.ownerCheckLifted()
        steps.owedReads()
        assertEquals(listOf("unlocked", "checkGated", "sends", "unlocked", "checkGated", "owed"), looks)
    }
}
