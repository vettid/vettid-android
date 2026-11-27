package com.vettid.core.calls

import com.vettid.core.crypto.call.MediaSuite
import com.vettid.core.vault.CallReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/**
 * Calls known to have ended here: an end that comes before its offer (VAULT-MESSAGING §8.4 gives no order), ends held while
 * a start was out, ends a replay leaves behind, and incoming calls that rang and ended. Their offers are not rung or answered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallMachineEndedCallsTest {
    private val signalling = FakeSignalling()
    private val engines = FakeEngines()
    private var open = true

    private fun TestScope.machine(s: FakeSignalling = signalling): CallMachine {
        open = true
        return CallMachine(
            s, engines, backgroundScope, { T0.plusMillis(testScheduler.currentTime) }, { testScheduler.currentTime },
            vaultOpen = { open },
        )
    }

    private fun CallMachine.live() = state.value as CallState.Live

    private fun CallMachine.ended() = state.value as CallState.Ended

    private fun offer(callId: String) =
        SignalEvent.Offer(
            callId, "conn-1", "v=0\r\ns=offer\r\n", T0.plusSeconds(45), Duration.ofSeconds(45), emptyList(),
            MediaSuite.AES_128_GCM, false,
        )

    /** Another call's id, unique per [n] (26 characters, as the vault's). */
    private fun other(n: Int) = "01JB2Z6V9K3M4N5P6Q7R8S" + n.toString().padStart(4, '0')

    private fun FakeSignalling.sentAny(prefix: String) = sent.any { it.startsWith(prefix) }

    private fun TestScope.placed(m: CallMachine) {
        m.call("conn-1")
        runCurrent()
        assertEquals(CallPhase.CALLING, m.live().phase)
    }

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

    /** No call rings or is answered for [callId] from here on. */
    private fun TestScope.notRung(m: CallMachine, s: FakeSignalling, callId: String) {
        m.dismiss()
        runCurrent()
        s.deliver(offer(callId))
        runCurrent()
        assertTrue("$callId rang: ${m.state.value}", m.state.value !is CallState.Live)
        assertTrue(!s.sentAny("ringing($callId") && !s.sentAny("answer($callId"))
    }

    /** The winner's end, then the glare's end, live: missed at once, and the winner's late offer is not answered. */
    @Test
    fun aWinnersEndBeforeItsOfferIsRememberedLive() = runTest {
        val m = machine()
        placed(m)
        signalling.deliver(SignalEvent.End(GLARE_ID, CallReason.HANGUP))
        signalling.deliver(SignalEvent.End(CALL_ID, CallReason.BUSY, supersededBy = GLARE_ID))
        runCurrent()
        assertEquals(CallEnd.MISSED, m.ended().reason)
        notRung(m, signalling, GLARE_ID)
        assertTrue("the ignored offer is let go in the signalling", GLARE_ID in signalling.dropped)
    }

    /** The same order held while `call.start` is out, with no offer held: missed when it is answered, never answered. */
    @Test
    fun aWinnersEndBeforeItsOfferIsRememberedHeld() = runTest {
        val m = starting()
        signalling.deliver(SignalEvent.End(GLARE_ID, CallReason.HANGUP))
        signalling.deliver(SignalEvent.End(CALL_ID, CallReason.BUSY, supersededBy = GLARE_ID))
        runCurrent()
        release()
        assertEquals(CallEnd.MISSED, m.ended().reason)
        notRung(m, signalling, GLARE_ID)
    }

    /** A winner found among other calls' ends is moved among the winners, where more other ends cannot push it out. */
    @Test
    fun aWinnerFoundAmongOtherEndsIsKeptAmongTheWinners() = runTest {
        val m = machine()
        placed(m)
        signalling.deliver(SignalEvent.End(GLARE_ID, CallReason.HANGUP))
        signalling.deliver(SignalEvent.End(CALL_ID, CallReason.BUSY, supersededBy = GLARE_ID))
        runCurrent()
        m.dismiss()
        placed(m)
        repeat(OVER) { signalling.deliver(SignalEvent.End(other(it), CallReason.HANGUP)) }
        runCurrent()
        m.hangUp()
        runCurrent()
        notRung(m, signalling, GLARE_ID)
    }

    /** On the placed path too: the winner's held end survives the other ends held with it (more than [OVER]). */
    @Test
    fun aHeldWinnerStaysAmongTheWinnersThroughOtherHeldEnds() = runTest {
        val m = starting()
        signalling.deliver(SignalEvent.End(GLARE_ID, CallReason.HANGUP))
        repeat(OVER) { signalling.deliver(SignalEvent.End(other(it), CallReason.HANGUP)) }
        signalling.deliver(SignalEvent.End(CALL_ID, CallReason.BUSY, supersededBy = GLARE_ID))
        runCurrent()
        release()
        assertEquals(CallEnd.MISSED, m.ended().reason)
        notRung(m, signalling, GLARE_ID)
    }

    /**
     * Only this call's own glare end (busy, a lower winner) puts a held winner among the winners: with another reason, or
     * a winner that is not lower, the winner's held end is one more other call's and can be pushed out like them.
     */
    @Test
    fun onlyThisCallsOwnGlareEndKeepsAHeldWinner() = runTest {
        val higher = "01JB2Z6V9K3M4N5P6Q7R8S9TZZ"
        for ((reason, by) in listOf(CallReason.DECLINE to GLARE_ID, CallReason.BUSY to higher)) {
            val s = FakeSignalling()
            val m = starting(s)
            s.deliver(SignalEvent.End(by, CallReason.HANGUP))
            repeat(OVER) { s.deliver(SignalEvent.End(other(it), CallReason.HANGUP)) }
            s.deliver(SignalEvent.End(CALL_ID, reason, supersededBy = by))
            runCurrent()
            release(s)
            m.dismiss()
            runCurrent()
            s.deliver(offer(by))
            runCurrent()
            assertEquals("$reason $by: pushed out, so it rings", CallPhase.INCOMING, m.live().phase)
        }
    }

    /** An end held while the start was out is remembered when the call ends before its id: refused, hung up or locked. */
    @Test
    fun anEndHeldDuringAStartThatEndsIsRemembered() = runTest {
        for (how in listOf("refused", "hangup", "lock")) {
            val s = FakeSignalling()
            if (how == "refused") s.startResult = { throw CallRefusedException("busy") }
            val m = starting(s)
            s.deliver(SignalEvent.End(GLARE_ID, CallReason.HANGUP))
            runCurrent()
            when (how) {
                "refused" -> release(s)
                "hangup" -> m.hangUp()
                else -> {
                    open = false
                    m.vaultLocking()
                }
            }
            runCurrent()
            assertTrue(how, m.state.value is CallState.Ended)
            open = true
            notRung(m, s, GLARE_ID)
        }
    }

    /** Ends held behind the one that finishes the call in a replay are remembered: their offers do not ring. */
    @Test
    fun endsHeldBehindAReplaysFinishAreRemembered() = runTest {
        val m = starting()
        signalling.deliver(SignalEvent.End(CALL_ID, CallReason.DECLINE))
        signalling.deliver(SignalEvent.End(GLARE_ID, CallReason.HANGUP))
        runCurrent()
        release()
        assertEquals(CallEnd.DECLINED, m.ended().reason)
        notRung(m, signalling, GLARE_ID)
    }

    /** An incoming call that rang here and ended does not ring again when its offer is delivered again. */
    @Test
    fun anIncomingCallThatEndedDoesNotRingAgain() = runTest {
        val m = machine()
        signalling.deliver(offer(GLARE_ID))
        runCurrent()
        assertEquals(CallPhase.INCOMING, m.live().phase)
        signalling.deliver(SignalEvent.End(GLARE_ID, CallReason.HANGUP))
        runCurrent()
        assertEquals(CallEnd.MISSED, m.ended().reason)
        signalling.sent.clear()
        notRung(m, signalling, GLARE_ID)
    }

    /**
     * (VAULT-MESSAGING 0.97.0 §10.10 "A lost call.offer") An offer delivered again for the call already ringing here (the
     * vault deposits it again from memory) changes nothing: the same call rings, once.
     */
    @Test
    fun anOfferAlreadyHeldIsIgnored() = runTest {
        val m = machine()
        signalling.deliver(offer(GLARE_ID))
        runCurrent()
        val first = m.live()
        assertEquals(CallPhase.INCOMING, first.phase)
        val sent = signalling.sent.toList()
        signalling.deliver(offer(GLARE_ID))
        runCurrent()
        assertEquals(CallPhase.INCOMING, m.live().phase)
        assertEquals(GLARE_ID, m.live().callId)
        assertEquals("nothing sent for the repeat", sent, signalling.sent)
        assertTrue("no engine before the member answers", engines.created.isEmpty())
    }

    /** Other calls' ends are bounded (32, the oldest pushed out), and one delivered again is kept once, as the newest. */
    @Test
    fun otherCallsEndsAreBoundedAndARepeatIsKeptOnce() = runTest {
        val m = machine()
        placed(m)
        signalling.deliver(SignalEvent.End(other(0), CallReason.HANGUP))
        repeat(OVER) { signalling.deliver(SignalEvent.End(other(1), CallReason.HANGUP)) }
        signalling.deliver(SignalEvent.End(other(2), CallReason.HANGUP))
        runCurrent()
        m.hangUp()
        runCurrent()
        // One entry for other(1) however often it came: other(0) is still known.
        notRung(m, signalling, other(0))
        val s2 = FakeSignalling()
        val m2 = machine(s2)
        placed(m2)
        repeat(OVER) { s2.deliver(SignalEvent.End(other(it + 10), CallReason.HANGUP)) }
        runCurrent()
        m2.hangUp()
        runCurrent()
        // Exactly 32 are kept: the second oldest is still known, the oldest is not.
        notRung(m2, s2, other(11))
        m2.dismiss()
        runCurrent()
        s2.deliver(offer(other(10)))
        runCurrent()
        assertEquals("the oldest was pushed out: it rings", CallPhase.INCOMING, m2.live().phase)
    }

    /** A repeated end moves to the newest place: 32 ends after its first arrival do not push it out. */
    @Test
    fun aRepeatedEndMovesToTheNewest() = runTest {
        val m = machine()
        placed(m)
        signalling.deliver(SignalEvent.End(other(0), CallReason.HANGUP))
        repeat(31) { signalling.deliver(SignalEvent.End(other(it + 1), CallReason.HANGUP)) }
        signalling.deliver(SignalEvent.End(other(0), CallReason.HANGUP))
        signalling.deliver(SignalEvent.End(other(40), CallReason.HANGUP))
        runCurrent()
        m.hangUp()
        runCurrent()
        notRung(m, signalling, other(0))
    }

    /** An idle phone remembers an end: the offer that comes after it does not ring, and nothing is sent. */
    @Test
    fun anIdlePhoneDoesNotRingACallThatEnded() = runTest {
        val m = machine()
        runCurrent()
        signalling.deliver(SignalEvent.End(GLARE_ID, CallReason.HANGUP))
        runCurrent()
        signalling.deliver(offer(GLARE_ID))
        runCurrent()
        assertEquals(CallState.Idle, m.state.value)
        assertTrue(signalling.sent.none { it.startsWith("ringing") })
        assertTrue(GLARE_ID in signalling.dropped)
    }

    /** Another call's glare end does not put its winner among the winners: only this call's own does. */
    @Test
    fun anotherCallsGlareEndDoesNotKeepItsWinner() = runTest {
        val m = starting()
        signalling.deliver(SignalEvent.End(GLARE_ID, CallReason.HANGUP))
        repeat(OVER) { signalling.deliver(SignalEvent.End(other(it), CallReason.HANGUP)) }
        signalling.deliver(SignalEvent.End(OTHER_ID, CallReason.BUSY, supersededBy = GLARE_ID))
        runCurrent()
        release()
        assertEquals(CallPhase.CALLING, m.live().phase)
        m.hangUp()
        runCurrent()
        m.dismiss()
        runCurrent()
        signalling.deliver(offer(GLARE_ID))
        runCurrent()
        assertEquals("pushed out with the other ends: it rings", CallPhase.INCOMING, m.live().phase)
    }

    /**
     * The call's own end came while it had no id and was not held (the held events full): when the start is answered the
     * call is over at once, nothing is sent and no address is gathered.
     */
    @Test
    fun aPlacedCallWhoseOwnEndWasRememberedEndsAtOnce() = runTest {
        val m = starting()
        repeat(MAX_EARLY) { signalling.deliver(SignalEvent.Ice(OTHER_ID, listOf(candidate(1)))) }
        signalling.deliver(SignalEvent.End(CALL_ID, CallReason.DECLINE))
        runCurrent()
        release()
        // The vault's reason, kept with the remembered end.
        assertEquals(CallEnd.DECLINED, (m.state.value as CallState.Ended).reason)
        assertTrue(signalling.sent.none { it.startsWith("end(") || it.startsWith("ice(") })
        assertTrue(engines.last.calls.none { it.startsWith("startGathering") })
    }

    /**
     * What was held while the start was out still goes where it belongs when the call ends at once: a held answer's keys
     * are wiped, and a held end of another call is remembered.
     */
    @Test
    fun aPlacedCallThatEndsAtOnceStillSettlesWhatWasHeld() = runTest {
        val m = starting()
        val keys = MediaKeys(MediaSuite.AES_128_GCM, ByteArray(32) { 5 }, ByteArray(32) { 6 })
        signalling.deliver(SignalEvent.Answer(CALL_ID, "v=0\r\ns=answer\r\n", keys))
        signalling.deliver(SignalEvent.End(GLARE_ID, CallReason.HANGUP))
        repeat(MAX_EARLY - 2) { signalling.deliver(SignalEvent.Ice(OTHER_ID, listOf(candidate(1)))) }
        signalling.deliver(SignalEvent.End(CALL_ID, CallReason.DECLINE))
        runCurrent()
        release()
        assertEquals(CallEnd.DECLINED, (m.state.value as CallState.Ended).reason)
        assertTrue("the held answer's keys were not wiped", keys.send.all { it == 0.toByte() } && keys.receive.all { it == 0.toByte() })
        notRung(m, signalling, GLARE_ID)
    }

    /**
     * The call's own end that lost a glare (busy, superseded by the winner) keeps no reason: the call shows failed, and
     * the winner's offer rings (§10.10 allows a device that does not answer it by itself to ring it).
     */
    @Test
    fun aPlacedCallWhoseOwnLostGlareEndWasRememberedShowsFailedAndTheWinnerRings() = runTest {
        val m = starting()
        repeat(MAX_EARLY) { signalling.deliver(SignalEvent.Ice(OTHER_ID, listOf(candidate(1)))) }
        signalling.deliver(SignalEvent.End(CALL_ID, CallReason.BUSY, supersededBy = GLARE_ID))
        runCurrent()
        release()
        assertEquals(CallEnd.FAILED, (m.state.value as CallState.Ended).reason)
        m.dismiss()
        runCurrent()
        signalling.deliver(offer(GLARE_ID))
        runCurrent()
        assertEquals(CallPhase.INCOMING, m.live().phase)
    }

    /** The reasons kept with remembered ends go with their ids: never more of them than ids. */
    @Test
    fun keptReasonsStayWithinTheBound() = runTest {
        val m = starting()
        repeat(MAX_EARLY) { signalling.deliver(SignalEvent.Ice(OTHER_ID, listOf(candidate(1)))) }
        repeat(OVER + 8) { signalling.deliver(SignalEvent.End(other(it), CallReason.DECLINE)) }
        runCurrent()
        val field = CallMachine::class.java.getDeclaredField("endedOtherEnds").apply { isAccessible = true }
        assertEquals(32, (field.get(m) as Map<*, *>).size)
        release()
    }

    /** The same with the call's own end held as well (delivered again): the held one is replayed, as for any start. */
    @Test
    fun aPlacedCallWhoseOwnEndIsAlsoHeldReplaysIt() = runTest {
        val m = starting()
        signalling.deliver(SignalEvent.End(CALL_ID, CallReason.BUSY))
        repeat(MAX_EARLY) { signalling.deliver(SignalEvent.Ice(OTHER_ID, listOf(candidate(1)))) }
        signalling.deliver(SignalEvent.End(CALL_ID, CallReason.DECLINE))
        runCurrent()
        release()
        assertEquals(CallEnd.BUSY, (m.state.value as CallState.Ended).reason)
        assertTrue(signalling.sent.none { it.startsWith("end(") })
    }

    /** A start no session shows, whose end the vault sent while it was out, is not ended `hangup` again. */
    @Test
    fun anUnshownStartTheVaultEndedGetsNoHangUp() = runTest {
        val m = starting()
        signalling.deliver(SignalEvent.End(CALL_ID, CallReason.BUSY))
        runCurrent()
        m.hangUp()
        runCurrent()
        release()
        assertTrue(signalling.sent.none { it.startsWith("end(") })
        assertEquals(listOf(CALL_ID), signalling.dropped)
    }

    /** The vault closed while the offer was made: no `call.start` goes, and the call ends at once. */
    @Test
    fun noStartGoesWhenTheVaultClosedWhileTheOfferWasMade() = runTest {
        val gate = Channel<Unit>(1)
        engines.onCreate = { (it as FakeEngine).offerGate = gate }
        val m = machine()
        m.call("conn-1")
        runCurrent()
        open = false
        gate.trySend(Unit)
        runCurrent()
        assertEquals(CallEnd.LOCKED, m.ended().reason)
        assertTrue(signalling.sent.none { it.startsWith("start") })
        assertEquals(0, m.startsOut.value)
    }

    /** A lock while candidates are kept for a held offer: nothing rings, and the held offer is let go. */
    @Test
    fun aLockLetsAHeldOfferAndItsKeptCandidatesGo() = runTest {
        val m = machine()
        placed(m)
        signalling.deliver(offer(GLARE_ID))
        signalling.deliver(SignalEvent.Ice(GLARE_ID, listOf(candidate(1))))
        runCurrent()
        open = false
        m.vaultLocking()
        runCurrent()
        assertEquals(CallEnd.LOCKED, m.ended().reason)
        assertEquals("no engine for the held offer", 1, engines.created.size)
        assertTrue(GLARE_ID in signalling.dropped)
        assertTrue(!signalling.sentAny("ringing(") && !signalling.sentAny("answer("))
    }

    /** The machine's list [name] of calls known to have ended (memory only, private). */
    private fun CallMachine.known(name: String): Collection<*> {
        val field = CallMachine::class.java.getDeclaredField(name).apply { isAccessible = true }
        return when (val v = field.get(this)) {
            is Map<*, *> -> v.keys
            else -> v as Collection<*>
        }
    }

    /** A start held ([FakeSignalling.startGate]) after [winner] ended while the phone was idle. */
    private fun TestScope.startingAfterAnIdleEnd(s: FakeSignalling, winner: String): CallMachine {
        s.startGate = Channel(Channel.UNLIMITED)
        val m = machine(s)
        runCurrent()
        s.deliver(SignalEvent.End(winner, CallReason.HANGUP))
        runCurrent()
        m.call("conn-1")
        runCurrent()
        assertEquals(CallPhase.STARTING, m.live().phase)
        return m
    }

    /** The placed call ended at once, missed; its winner is kept among the winners only, and its late offer goes unrung. */
    private fun TestScope.missedAtOnceAndTheWinnerNotRung(m: CallMachine) {
        assertEquals(CallEnd.MISSED, m.ended().reason)
        assertTrue(GLARE_ID in m.known("endedWinners"))
        // The case this is about: the other calls' ends replayed have pushed the winner out of the other calls' list.
        assertTrue(GLARE_ID !in m.known("endedOthers"))
        assertTrue(signalling.sent.none { it.startsWith("end(") || it.startsWith("ice(") })
        assertTrue(engines.last.calls.none { it.startsWith("startGathering") })
        notRung(m, signalling, GLARE_ID)
    }

    /**
     * The winner ended while the phone was idle; then a start gets more than 32 other ends held, the held events fill, and
     * its own glare end comes past them (remembered, not held). The placed call ends at once, and that remembered end keeps
     * the winner among the winners before the held ends are replayed: its late offer is neither answered nor rung.
     */
    @Test
    fun aWinnerEndedBeforeACallThatEndsAtOnceIsKeptAmongTheWinners() = runTest {
        val m = startingAfterAnIdleEnd(signalling, GLARE_ID)
        repeat(OVER) { signalling.deliver(SignalEvent.End(other(it), CallReason.HANGUP)) }
        repeat(MAX_EARLY - OVER) { signalling.deliver(SignalEvent.Ice(OTHER_ID, listOf(candidate(1)))) }
        signalling.deliver(SignalEvent.End(CALL_ID, CallReason.BUSY, supersededBy = GLARE_ID))
        runCurrent()
        release()
        missedAtOnceAndTheWinnerNotRung(m)
    }

    /** The same with the held events full of other calls' ends ([MAX_EARLY]), the own glare end coming past them. */
    @Test
    fun aWinnerEndedBeforeACallThatEndsAtOnceIsKeptWithTheHeldEventsFull() = runTest {
        val m = startingAfterAnIdleEnd(signalling, GLARE_ID)
        repeat(MAX_EARLY) { signalling.deliver(SignalEvent.End(other(it), CallReason.HANGUP)) }
        signalling.deliver(SignalEvent.End(CALL_ID, CallReason.BUSY, supersededBy = GLARE_ID))
        runCurrent()
        release()
        missedAtOnceAndTheWinnerNotRung(m)
    }

    /** The remembered own end of another kind, or naming a winner that is not lower, is no glare's: it fills no winners. */
    @Test
    fun aRememberedOwnEndThatIsNoGlaresDoesNotFillTheWinners() = runTest {
        val higher = "01JB2Z6V9K3M4N5P6Q7R8S9TZZ"
        for ((reason, by) in listOf(CallReason.DECLINE to GLARE_ID, CallReason.BUSY to higher)) {
            val s = FakeSignalling()
            val m = startingAfterAnIdleEnd(s, by)
            repeat(MAX_EARLY) { s.deliver(SignalEvent.Ice(OTHER_ID, listOf(candidate(1)))) }
            s.deliver(SignalEvent.End(CALL_ID, reason, supersededBy = by))
            runCurrent()
            release(s)
            assertEquals("$reason $by", CallEnd.FAILED, m.ended().reason)
            assertTrue("$reason $by", by !in m.known("endedWinners"))
        }
    }

    /** Calls known to have ended, among the winners and the others, with a kept end. */
    private fun TestScope.knownEnded(m: CallMachine) {
        placed(m)
        signalling.deliver(SignalEvent.End(GLARE_ID, CallReason.HANGUP))
        signalling.deliver(SignalEvent.End(CALL_ID, CallReason.BUSY, supersededBy = GLARE_ID))
        runCurrent()
        assertEquals(CallEnd.MISSED, m.ended().reason)
        m.dismiss()
        signalling.startGate = Channel(Channel.UNLIMITED)
        m.call("conn-1")
        runCurrent()
        repeat(MAX_EARLY) { signalling.deliver(SignalEvent.Ice(OTHER_ID, listOf(candidate(1)))) }
        signalling.deliver(SignalEvent.End(OTHER_ID, CallReason.DECLINE))
        runCurrent()
        assertTrue(GLARE_ID in m.known("endedWinners") && OTHER_ID in m.known("endedOthers"))
        assertTrue(OTHER_ID in m.known("endedOtherEnds"))
    }

    /** A lock and an unlock of the same vault keep the calls known to have ended: a late offer for one does not ring. */
    @Test
    fun theEndedCallListsSurviveALockAndAnUnlock() = runTest {
        val m = machine()
        knownEnded(m)
        open = false
        m.vaultLocking()
        runCurrent()
        assertEquals(CallEnd.LOCKED, m.ended().reason)
        open = true
        assertTrue(GLARE_ID in m.known("endedWinners") && OTHER_ID in m.known("endedOthers"))
        assertTrue(OTHER_ID in m.known("endedOtherEnds"))
        notRung(m, signalling, GLARE_ID)
        notRung(m, signalling, OTHER_ID)
    }

    /** Leaving the vault ends a live call as a lock does, then forgets every call known to have ended: none reaches the next. */
    @Test
    fun aVaultChangeEmptiesTheEndedCallLists() = runTest {
        val m = machine()
        knownEnded(m)
        open = false
        m.vaultChanged()
        runCurrent()
        assertEquals(CallEnd.LOCKED, m.ended().reason)
        assertTrue(m.known("endedWinners").isEmpty() && m.known("endedOthers").isEmpty() && m.known("endedOtherEnds").isEmpty())
        open = true
        m.dismiss()
        runCurrent()
        signalling.deliver(offer(GLARE_ID))
        runCurrent()
        assertEquals("another vault's ended call is not known here", CallPhase.INCOMING, m.live().phase)
    }

    /**
     * The live call ends before the lists are cleared: what its end remembers (another call's end held by a start, an
     * incoming call that rang here) is of the vault being left, and is gone with the rest.
     */
    @Test
    fun aVaultChangeEndsTheLiveCallBeforeItClears() = runTest {
        for (how in listOf("held end", "ringing")) {
            val s = FakeSignalling()
            val m = if (how == "held end") starting(s) else machine(s)
            s.deliver(if (how == "held end") SignalEvent.End(OTHER_ID, CallReason.HANGUP) else offer(GLARE_ID))
            runCurrent()
            assertTrue(how, m.state.value is CallState.Live)
            open = false
            m.vaultChanged()
            runCurrent()
            assertTrue(how, m.state.value is CallState.Ended)
            val left = listOf("endedWinners", "endedOthers", "endedOtherEnds").associateWith { m.known(it) }
            assertTrue("$how: $left", left.values.all { it.isEmpty() })
        }
    }

    /** [vaultOpenings]: a close is put between two openings the phase alone would not tell apart. */
    @Test
    fun anOpeningAfterAConflatedCloseIsSeenAsACloseAndAnOpen() = runTest {
        val seen = vaultOpenings(flowOf(false to 0, true to 1, true to 1, true to 2, false to 2, true to 3)).toList()
        assertEquals(listOf(false, true, false, true, false, true), seen)
    }

    private companion object {
        /** More than the other calls' ends kept (32). */
        const val OVER = 33

        /** The held events of a start (CallMachine's MAX_EARLY). */
        const val MAX_EARLY = 64
    }
}
