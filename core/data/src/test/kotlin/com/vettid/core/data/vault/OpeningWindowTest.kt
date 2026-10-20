package com.vettid.core.data.vault

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The window before the app opens ([OpeningWindow]): the relay requests sent while the phone is still locked (the owner
 * check after a cancelled recovery, §3.6.5's check) are collected even when the notification service paused the relay,
 * and nothing else from the mailbox is handed on while the phone is locked.
 */
class OpeningWindowTest {
    private fun event(type: String) = VaultMessage(Inner(id = Ulid.new(), type = type, ts = Instant.now(), body = "{}".toByteArray()))

    /** The vault manager and the device, as the window sees them. */
    private class Port(var paused: Boolean, override var phase: AppPhase = AppPhase.Locked) : OpeningWindow.Port {
        val log = mutableListOf<String>()
        var holds = 0
        val held = mutableListOf<VaultMessage>()
        val delivered = mutableListOf<VaultMessage>()
        val state = mutableListOf<VaultMessage>()
        var collecting = !paused

        /** A lock is pending (LockKeeper): a pause waits for its confirmation. */
        var lockPending = false

        override val relayPaused: Boolean get() = paused

        override fun holdEvents() {
            holds++
            log += "hold"
        }

        override suspend fun endHold(deliver: Boolean): List<VaultMessage> {
            log += "end($deliver)"
            holds--
            if (holds > 0) return emptyList()
            val all = held.toList()
            held.clear()
            if (deliver) delivered += all
            return if (deliver) emptyList() else all
        }

        override suspend fun startCollecting() {
            log += "start"
            collecting = true
        }

        /** As the vault manager does it: through its pauseRelay ([RelayPause]). */
        override fun pauseAgain() {
            log += "pause"
            paused = false
            if (RelayPause.step(phase == AppPhase.Locked, paused, lockPending) == RelayPause.Step.STOP) {
                collecting = false
                paused = true
            }
        }

        override suspend fun takeState(events: List<VaultMessage>) {
            state += events
        }

        /** The device's collection brings [m]: held while a hold is in force, else handed on. */
        fun collected(m: VaultMessage) {
            if (holds > 0) held += m else delivered += m
        }
    }

    @Test
    fun aPausedRelayCollectsForTheRequestsAndTheOpenHandsTheEventsOn() = runBlocking {
        val port = Port(paused = true)
        val w = OpeningWindow(port)
        val m = event("message.new")
        w.run {
            // The owner check's answer can arrive: the collection runs; events are held, not handed on.
            assertTrue(port.collecting)
            assertTrue(w.open)
            port.collected(m)
            assertEquals(emptyList<VaultMessage>(), port.delivered)
            port.phase = AppPhase.Unlocked // the phase the unlock publishes
        }
        assertFalse(w.open)
        assertEquals(listOf(m), port.delivered)
        assertEquals(emptyList<VaultMessage>(), port.state)
        assertEquals(listOf("hold", "start", "end(true)"), port.log) // never paused again: the open resumes the relay
    }

    @Test
    fun aRunningRelayIsNotStartedAgain() = runBlocking {
        val port = Port(paused = false)
        OpeningWindow(port).run { port.phase = AppPhase.Unlocked }
        assertEquals(listOf("hold", "end(true)"), port.log)
    }

    @Test
    fun aPhoneThatStaysLockedTakesOnlyTheStateAndClosesThePausedRelayAgain() = runBlocking {
        val port = Port(paused = true)
        val w = OpeningWindow(port)
        val locking = event("vault.locking")
        val message = event("message.new")
        w.run {
            port.collected(locking)
            port.collected(message)
            // A lock asked for meanwhile: the phase is not published, the phone stays locked.
        }
        assertEquals(emptyList<VaultMessage>(), port.delivered)
        assertEquals(listOf(locking, message), port.state) // given back for the state (the device holds state types only)
        // The relay first, then the held state events: before anything collected after them.
        assertEquals(listOf("hold", "start", "pause", "end(false)"), port.log)
        assertFalse(port.collecting)
    }

    @Test
    fun aPauseAskedForInTheWindowWaitsForItsEnd() = runBlocking {
        val port = Port(paused = false)
        val w = OpeningWindow(port)
        assertFalse(w.deferPause()) // no window: the pause runs at once
        w.run { assertTrue(w.deferPause()) }
        assertEquals(listOf("hold", "pause", "end(false)"), port.log)

        // The same pause, then an open: no pause after it (the relay stays up while the vault is open).
        val open = Port(paused = false)
        val w2 = OpeningWindow(open)
        w2.run {
            assertTrue(w2.deferPause())
            open.phase = AppPhase.Unlocked
        }
        assertEquals(listOf("hold", "end(true)"), open.log)
        assertFalse(w2.deferPause())
    }

    @Test
    fun aFailedOpenStillEndsTheWindow() = runBlocking {
        val port = Port(paused = true)
        val w = OpeningWindow(port)
        val locking = event("vault.locking")
        port.held += locking
        val thrown = runCatching { w.run { error("the check threw") } }
        assertTrue(thrown.isFailure)
        assertFalse(w.open)
        assertEquals(0, port.holds)
        assertEquals(emptyList<VaultMessage>(), port.delivered)
        assertEquals(listOf(locking), port.state)
        assertEquals(listOf("hold", "start", "pause", "end(false)"), port.log)
    }

    /**
     * A lock asked for in the window, with the relay paused before it: the relay is not closed while the lock is pending
     * (its answer and `vault.locking` come over it); the vault manager pauses it once the lock is confirmed.
     */
    @Test
    fun aLockPendingAtTheEndKeepsTheCollectionUntilItIsConfirmed() = runBlocking {
        val port = Port(paused = true)
        port.lockPending = true
        OpeningWindow(port).run { /* the lock overtook the open: the phase stays Locked */ }
        assertEquals(listOf("hold", "start", "pause", "end(false)"), port.log)
        assertTrue(port.collecting)
        assertFalse(port.paused)
        // Confirmed: pauseRelay runs again (VaultManager's init) and closes it.
        assertEquals(RelayPause.Step.STOP, RelayPause.step(locked = true, paused = port.paused, lockPending = false))
        assertEquals(RelayPause.Step.AFTER_LOCK, RelayPause.step(locked = true, paused = false, lockPending = true))
        assertEquals(RelayPause.Step.NONE, RelayPause.step(locked = false, paused = false, lockPending = false))
        assertEquals(RelayPause.Step.NONE, RelayPause.step(locked = true, paused = true, lockPending = false))
    }

    /**
     * A window that ends neither open nor locked (a refresh found the service unreachable): the held state is taken and
     * the relay is not left running as paused-but-collecting: unpaused, for the notification service to pause once the
     * phase is Locked again.
     */
    @Test
    fun aWindowEndingUnreachableTakesTheStateAndHandsTheRelayBack() = runBlocking {
        val port = Port(paused = true)
        val held = event("vault.held")
        OpeningWindow(port).run {
            port.collected(held)
            port.phase = AppPhase.Unreachable(FailureKind.NETWORK)
        }
        assertEquals(listOf("hold", "start", "pause", "end(false)"), port.log)
        assertEquals(listOf(held), port.state)
        assertEquals(emptyList<VaultMessage>(), port.delivered)
        assertFalse(port.paused) // not marked paused while collecting
    }

    @Test
    fun aWipeDropsWhatWasHeld() = runBlocking {
        val port = Port(paused = true)
        OpeningWindow(port).run {
            port.collected(event("message.new"))
            port.phase = AppPhase.SignedOut
        }
        assertEquals(emptyList<VaultMessage>(), port.delivered)
        assertEquals(emptyList<VaultMessage>(), port.state)
        assertEquals(listOf("hold", "start", "end(false)"), port.log)
    }

    @Test
    fun setupStagesOpenAndOnlyTheLastWindowDecides() = runBlocking {
        assertTrue(OpeningWindow.opens(AppPhase.Unlocked))
        assertTrue(OpeningWindow.opens(AppPhase.Setup(SetupStage.FINISHING)))
        assertFalse(OpeningWindow.opens(AppPhase.Locked))
        assertFalse(OpeningWindow.opens(AppPhase.SignedOut))
        val port = Port(paused = true)
        val w = OpeningWindow(port)
        val m = event("message.new")
        w.run {
            w.run { port.collected(m) } // an inner window that ends while locked: nothing handed on, nothing paused
            assertEquals(emptyList<VaultMessage>(), port.state)
            assertFalse(port.log.contains("pause"))
            assertTrue(w.open)
            port.phase = AppPhase.Unlocked
        }
        assertEquals(listOf(m), port.delivered)
    }
}
