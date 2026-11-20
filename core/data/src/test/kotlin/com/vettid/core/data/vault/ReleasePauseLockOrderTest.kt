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

    /**
     * After a missed close, the votes' sends armed for the owner check's lift still go when the lift comes on another thread
     * right after the look found the check due (before the flag was set): the look is made again after arming.
     */
    @Test
    fun aLiftRightAfterTheSendsLookStillRunsThem() {
        val gated = java.util.concurrent.atomic.AtomicBoolean(true)
        val ran = java.util.Collections.synchronizedList(mutableListOf<String>())
        lateinit var steps: ReleasePauseSteps
        var lift: Thread? = null
        steps = ReleasePauseSteps(
            unlocked = { true }, checkGated = { gated.get() }, paused = { false }, required = { false }, stopVotes = {},
            gateChanged = {}, reads = { ran += "reads" }, sends = { ran += "sends" },
        )
        steps.sendsAfterMissedClose(
            gated = {
                val was = gated.get()
                if (was && lift == null) {
                    gated.set(false) // the check passes now, and its lift runs at once on another thread, before the flag
                    lift = Thread { steps.ownerCheckLifted() }.also { it.start() }
                    lift!!.join(JOIN_MS)
                }
                was
            },
            now = { ran += "now" },
        )
        // The lift ended inside the look: the look was made outside the pause's monitor, so the lift was not held up.
        assertFalse("the lift waited for the pause's monitor", lift!!.isAlive)
        assertEquals(listOf("sends"), ran.toList())
    }

    /** Not held: the sends go at once, nothing is armed for a lift. */
    @Test
    fun sendsAfterAMissedCloseGoAtOnceWhenNotHeld() {
        val ran = mutableListOf<String>()
        val steps = ReleasePauseSteps(
            unlocked = { true }, checkGated = { false }, paused = { false }, required = { false }, stopVotes = {},
            gateChanged = {}, reads = { ran += "reads" }, sends = { ran += "sends" },
        )
        steps.sendsAfterMissedClose(gated = { false }, now = { ran += "now" })
        steps.ownerCheckLifted()
        assertEquals(listOf("now"), ran)
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
