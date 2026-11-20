package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [ReleasePauseSteps]' monitor is the innermost lock. The lock clears hold the phase holder's lock when they reset it, so the
 * look at the phase holder ([ReleasePauseSteps.unlocked]) is never made under it; the owner check's look (a flow read, no
 * lock) is, so that a lift cannot pass between it and the flag it sets.
 */
class ReleasePauseLockOrderTest {
    @Test
    fun thePhaseIsNeverLookedAtUnderTheMonitor() {
        lateinit var steps: ReleasePauseSteps
        val ran = mutableListOf<String>()
        steps = ReleasePauseSteps(
            unlocked = {
                assertFalse("the phase looked at under the pause's monitor", Thread.holdsLock(steps))
                ran += "unlocked"
                true
            },
            checkGated = { false },
            paused = { false },
            required = { false },
            stopVotes = {},
            gateChanged = {},
            reads = { ran += "reads" },
            sends = { ran += "sends" },
            owed = { ran += "owed" },
        )
        steps.sendsAgainAtLift()
        steps.ownerCheckLifted()
        steps.owedReads()
        steps.onGate(GateChange(paused = false, required = false, pausedChanged = true, requiredChanged = true))
        assertEquals(listOf("unlocked", "sends", "unlocked", "owed", "unlocked", "reads", "sends"), ran)
    }

    /** A lift that comes right after the owed reads looked at the owner check still runs them (the look is under the monitor). */
    @Test
    fun aLiftRightAfterTheLookStillRunsTheReads() {
        val gated = java.util.concurrent.atomic.AtomicBoolean(true)
        val ran = java.util.Collections.synchronizedList(mutableListOf<String>())
        lateinit var steps: ReleasePauseSteps
        var lift: Thread? = null
        steps = ReleasePauseSteps(
            unlocked = { true },
            checkGated = {
                val was = gated.get()
                if (was && lift == null) {
                    gated.set(false) // the check passes now, and its lift runs at once on another thread
                    lift = Thread { steps.ownerCheckLifted() }.also { it.start() }
                    Thread.sleep(LIFT_HEAD_START_MS)
                }
                was
            },
            paused = { false },
            required = { false },
            stopVotes = {},
            gateChanged = {},
            reads = { ran += "reads" },
            sends = {},
            owed = { ran += "owed" },
        )
        steps.owedReads()
        lift!!.join(JOIN_MS)
        assertEquals(listOf("reads"), ran.toList())
    }

    /** Sends armed for the lift do not go while the gate pauses (Rule 3): only once it does not. */
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

    private companion object {
        const val LIFT_HEAD_START_MS = 200L
        const val JOIN_MS = 5_000L
    }
}
