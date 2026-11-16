package com.vettid.core.calls

import com.vettid.core.crypto.call.MediaSuite
import com.vettid.core.vault.CallCandidate
import com.vettid.core.vault.CallReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/**
 * The orders in which a glare's events, a held offer's end and the answer to `call.start` can arrive, and the candidates of
 * a glare's winner: what the call shows, what is rung or answered, and what is sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallMachineGlareEndsTest {
    private val signalling = FakeSignalling()
    private val engines = FakeEngines()

    private fun TestScope.machine(s: CallSignalling = signalling) =
        CallMachine(s, engines, backgroundScope, { T0.plusMillis(testScheduler.currentTime) }, { testScheduler.currentTime })

    private fun CallMachine.live() = state.value as CallState.Live

    private fun CallMachine.ended() = state.value as CallState.Ended

    private fun offer(callId: String) =
        SignalEvent.Offer(
            callId, "conn-1", "v=0\r\ns=offer\r\n", T0.plusSeconds(45), Duration.ofSeconds(45), emptyList(),
            MediaSuite.AES_128_GCM, false,
        )

    private fun FakeSignalling.sentAny(prefix: String) = sent.any { it.startsWith(prefix) }

    /** A call to conn-1 whose `call.start` is held until the test lets it go. */
    private fun TestScope.starting(s: FakeSignalling = signalling): CallMachine {
        s.startGate = Channel(Channel.UNLIMITED)
        val m = machine(s)
        m.call("conn-1")
        runCurrent()
        assertEquals(CallPhase.STARTING, m.live().phase)
        return m
    }

    private fun TestScope.release(s: FakeSignalling = signalling) {
        s.startGate!!.trySend(Unit)
        runCurrent()
    }

    /**
     * The winner's offer, the glare's end, then the winner's own end, all while `call.start` is out. Once the start is
     * answered the winner has ended: the call is missed, the winner is never answered, and its offer delivered again is
     * not rung.
     */
    @Test
    fun aGlareWhoseHeldWinnerOfferedThenEndedWhileTheStartWasOutIsMissed() = runTest {
        val m = starting()
        signalling.deliver(offer(GLARE_ID))
        signalling.deliver(SignalEvent.End(CALL_ID, CallReason.BUSY, supersededBy = GLARE_ID))
        signalling.deliver(SignalEvent.End(GLARE_ID, CallReason.HANGUP))
        runCurrent()
        release()
        assertEquals(CallEnd.MISSED, m.ended().reason)
        signalling.deliver(offer(GLARE_ID))
        runCurrent()
        assertEquals("the ended winner's offer is not rung", CallEnd.MISSED, m.ended().reason)
        assertTrue(!signalling.sentAny("answer(") && !signalling.sentAny("end(") && !signalling.sentAny("ringing("))
    }

    /** The held order of the next test: the winner's offer, its end and the glare's end, all while the start is out. */
    @Test
    fun aGlareWhoseWinnerEndedFirstWhileTheStartWasOutIsMissed() = runTest {
        val m = starting()
        signalling.deliver(offer(GLARE_ID))
        signalling.deliver(SignalEvent.End(GLARE_ID, CallReason.HANGUP))
        signalling.deliver(SignalEvent.End(CALL_ID, CallReason.BUSY, supersededBy = GLARE_ID))
        runCurrent()
        release()
        assertEquals(CallEnd.MISSED, m.ended().reason)
        assertTrue(!signalling.sentAny("answer(") && !signalling.sentAny("ringing("))
    }

    /** Live: the winner's offer, its end, then the glare's end. Missed at once, not busy at the ring timer. */
    @Test
    fun aGlareWhoseWinnerEndedBeforeTheGlaresEndIsMissedAtOnce() = runTest {
        val m = machine()
        m.call("conn-1")
        runCurrent()
        assertEquals(CallPhase.CALLING, m.live().phase)
        signalling.deliver(offer(GLARE_ID))
        signalling.deliver(SignalEvent.End(GLARE_ID, CallReason.HANGUP))
        signalling.deliver(SignalEvent.End(CALL_ID, CallReason.BUSY, supersededBy = GLARE_ID))
        runCurrent()
        assertEquals(CallEnd.MISSED, m.ended().reason)
        assertTrue(!signalling.sentAny("answer(") && !signalling.sentAny("end("))
    }

    /**
     * Not a glare: while `call.start` is out, the connection's offer, the vault's decline of this call, then the offer's
     * own end. The call shows declined, and the held offer, which has ended, is not rung after it, nor when delivered again.
     */
    @Test
    fun aHeldOfferThatEndedWhileTheStartWasOutIsNotRungWhenTheCallEnds() = runTest {
        val m = starting()
        signalling.deliver(offer(GLARE_ID))
        signalling.deliver(SignalEvent.End(CALL_ID, CallReason.DECLINE))
        signalling.deliver(SignalEvent.End(GLARE_ID, CallReason.HANGUP))
        runCurrent()
        release()
        assertEquals(CallEnd.DECLINED, m.ended().reason)
        signalling.deliver(offer(GLARE_ID))
        runCurrent()
        assertEquals("the ended offer delivered again is not rung", CallEnd.DECLINED, m.ended().reason)
        assertTrue(!signalling.sentAny("ringing(") && !signalling.sentAny("answer("))
    }

    /** A held offer that ended before `call.start` was refused (`busy`) or failed is not rung, nor when delivered again. */
    @Test
    fun aRefusedOrFailedStartDoesNotRingAHeldOfferThatEnded() = runTest {
        for (refused in listOf(true, false)) {
            val s = FakeSignalling()
            s.startResult = if (refused) ({ throw CallRefusedException("busy") }) else ({ throw CallSecurityException("call.start") })
            val m = starting(s)
            s.deliver(offer(GLARE_ID))
            s.deliver(SignalEvent.End(GLARE_ID, CallReason.HANGUP))
            runCurrent()
            release(s)
            assertEquals(if (refused) CallEnd.OWN_VAULT_BUSY else CallEnd.NOT_ENCRYPTED, m.ended().reason)
            s.deliver(offer(GLARE_ID))
            runCurrent()
            assertTrue(m.state.value is CallState.Ended)
            assertTrue("refused=$refused", !s.sentAny("ringing("))
        }
    }

    /** Only the held offer's own end lets it go: with another call's end held, a refused start still rings it. */
    @Test
    fun aRefusedStartRingsAHeldOfferWhoseEndIsNotHeld() = runTest {
        signalling.startResult = { throw CallRefusedException("busy") }
        val m = starting()
        signalling.deliver(offer(GLARE_ID))
        signalling.deliver(SignalEvent.End("01JB2Z6V9K3M4N5P6Q7R8S9T77", CallReason.HANGUP))
        runCurrent()
        release()
        assertEquals(CallPhase.INCOMING, m.live().phase)
        assertEquals(GLARE_ID, m.live().callId)
    }

    /**
     * A start no session shows is ended `hangup` when the vault answers it, however many there are (before, the ninth
     * and later were never ended).
     */
    @Test
    fun everyStartNoSessionShowsIsEndedWhenItIsAnswered() = runTest {
        val m = machine()
        signalling.startGate = Channel(Channel.UNLIMITED)
        repeat(STARTS) {
            m.call("conn-1")
            runCurrent()
            m.hangUp()
            runCurrent()
            m.dismiss()
            runCurrent()
        }
        repeat(STARTS) { signalling.startGate!!.trySend(Unit) }
        runCurrent()
        assertEquals(STARTS, signalling.sent.count { it == "end($CALL_ID, hangup)" })
    }

    /** The winner's candidates that came while the start was out reach it when the glare's replay answers it. */
    @Test
    fun aHeldWinnersCandidatesReachItWhenTheReplayAnswersIt() = runTest {
        val m = starting()
        signalling.deliver(offer(GLARE_ID))
        signalling.deliver(SignalEvent.Ice(GLARE_ID, listOf(candidate(7))))
        signalling.deliver(SignalEvent.End(CALL_ID, CallReason.BUSY, supersededBy = GLARE_ID))
        signalling.deliver(SignalEvent.Ice(GLARE_ID, listOf(candidate(8))))
        runCurrent()
        release()
        assertEquals(GLARE_ID, m.live().callId)
        assertEquals(listOf(candidate(7), candidate(8)), engines.last.remote)
    }

    /** Live: the winner's candidates that came while this device's call was the session reach it once it is answered. */
    @Test
    fun aLiveWinnersCandidatesReachItWhenItIsAnswered() = runTest {
        for (offerFirst in listOf(true, false)) {
            val s = FakeSignalling()
            val m = machine(s)
            m.call("conn-1")
            runCurrent()
            if (offerFirst) s.deliver(offer(GLARE_ID))
            s.deliver(SignalEvent.End(CALL_ID, CallReason.BUSY, supersededBy = GLARE_ID))
            s.deliver(SignalEvent.Ice(GLARE_ID, listOf(candidate(7))))
            if (!offerFirst) s.deliver(offer(GLARE_ID))
            runCurrent()
            assertEquals(GLARE_ID, m.live().callId)
            assertEquals("offerFirst=$offerFirst", listOf(candidate(7)), engines.last.remote)
        }
    }

    /**
     * Candidates are kept for one call: those of a held offer that the vault's glare let go for another winner (or that
     * another offer replaced) never reach the winner.
     */
    @Test
    fun anotherHeldCallsCandidatesNeverReachTheWinner() = runTest {
        for (replaced in listOf(false, true)) {
            val s = FakeSignalling()
            val m = machine(s)
            m.call("conn-1")
            runCurrent()
            s.deliver(offer(OTHER_ID))
            s.deliver(SignalEvent.Ice(OTHER_ID, listOf(candidate(3))))
            if (replaced) {
                // Another offer from the connection takes the held one's place, with its own candidates.
                s.deliver(offer(GLARE_ID))
                s.deliver(SignalEvent.Ice(GLARE_ID, listOf(candidate(4))))
                s.deliver(SignalEvent.End(CALL_ID, CallReason.BUSY, supersededBy = GLARE_ID))
            } else {
                // The glare's winner is not the held offer: it is let go, and the winner's offer comes after.
                s.deliver(SignalEvent.End(CALL_ID, CallReason.BUSY, supersededBy = GLARE_ID))
                s.deliver(offer(GLARE_ID))
            }
            runCurrent()
            assertEquals(GLARE_ID, m.live().callId)
            val expected = if (replaced) listOf(candidate(4)) else emptyList()
            assertEquals("replaced=$replaced", expected, engines.last.remote)
        }
    }

    /**
     * The call ends another way (declined) and its held offer rings: the candidates of the offer that held offer
     * replaced are not handed to it.
     */
    @Test
    fun aRungHeldOfferTakesNoCandidatesOfTheOfferItReplaced() = runTest {
        val m = machine()
        m.call("conn-1")
        runCurrent()
        signalling.deliver(offer(OTHER_ID))
        signalling.deliver(SignalEvent.Ice(OTHER_ID, listOf(candidate(3))))
        signalling.deliver(offer(GLARE_ID))
        signalling.deliver(SignalEvent.End(CALL_ID, CallReason.DECLINE))
        runCurrent()
        assertEquals(GLARE_ID, m.live().callId)
        m.answer()
        runCurrent()
        assertEquals(emptyList<CallCandidate>(), engines.last.remote)
    }

    /** A held offer rung after a refused start takes the candidates held for it, applied once it is answered. */
    @Test
    fun aHeldOfferRungAfterARefusedStartTakesItsCandidates() = runTest {
        signalling.startResult = { throw CallRefusedException("busy") }
        val m = starting()
        signalling.deliver(offer(GLARE_ID))
        signalling.deliver(SignalEvent.Ice(GLARE_ID, listOf(candidate(7))))
        runCurrent()
        release()
        assertEquals(CallPhase.INCOMING, m.live().phase)
        m.answer()
        runCurrent()
        assertEquals(listOf(candidate(7)), engines.last.remote)
    }

    /** The other side's candidates that come after the answer was taken but before its SDP is applied are not lost. */
    @Test
    fun aCandidateWhileTheAnswerIsAppliedIsAdded() = runTest {
        val m = machine()
        m.call("conn-1")
        runCurrent()
        val keys = MediaKeys(MediaSuite.AES_128_GCM, ByteArray(32) { 5 }, ByteArray(32) { 6 })
        signalling.deliver(SignalEvent.Answer(CALL_ID, "v=0\r\ns=answer\r\n", keys))
        signalling.deliver(SignalEvent.Ice(CALL_ID, listOf(candidate(9))))
        runCurrent()
        assertEquals(CallPhase.CONNECTING, m.live().phase)
        assertEquals(listOf(candidate(9)), engines.last.remote)
    }

    private companion object {
        /** More starts than the old list of abandoned starts held (8). */
        const val STARTS = 9

        /** An offer from the connection that is not the glare's winner. */
        const val OTHER_ID = "01JB2Z6V9K3M4N5P6Q7R8S9T4A"
    }
}
