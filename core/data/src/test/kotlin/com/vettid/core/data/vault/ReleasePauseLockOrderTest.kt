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
    /** Sends armed for the lift do not go while the gate pauses (Rule 3): only reads and sends once it does not. */
    @Test
    fun armedSendsWaitWhileTheGatePauses() {
        var paused = true
        val ran = mutableListOf<String>()
        val steps = ReleasePauseSteps(
            unlocked = { true }, checkGated = { false }, paused = { paused }, required = { false }, stopVotes = {},
            gateChanged = {}, reads = { ran += "reads" }, sends = { ran += "sends" },
        )
        steps.sendsAgainAtLift()
        steps.ownerCheckLifted()
        assertEquals(emptyList<String>(), ran)
        paused = false
        steps.ownerCheckLifted()
        assertEquals(listOf("sends"), ran)
    }

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
