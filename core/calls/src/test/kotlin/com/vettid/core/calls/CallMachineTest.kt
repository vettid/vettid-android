package com.vettid.core.calls

import com.vettid.core.crypto.call.MediaSuite
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

@Suppress("LargeClass") // One class: every test shares the fakes and the helpers below.
@OptIn(ExperimentalCoroutinesApi::class)
class CallMachineTest {
    private val signalling = FakeSignalling()
    private val engines = FakeEngines()

    private fun TestScope.machine(s: CallSignalling = signalling, e: MediaEngineFactory = engines, config: MediaConfig = MediaConfig()) =
        CallMachine(s, e, backgroundScope, { T0.plusMillis(testScheduler.currentTime) }, { testScheduler.currentTime }, config = { config })

    private fun CallMachine.live() = state.value as CallState.Live

    private fun CallMachine.ended() = state.value as CallState.Ended

    private fun offer(callId: String = CALL_ID, connectionId: String = "conn-1") =
        SignalEvent.Offer(
            callId,
            connectionId,
            "v=0\r\ns=offer\r\n",
            T0.plusSeconds(45),
            Duration.ofSeconds(45),
            emptyList(),
            MediaSuite.AES_128_GCM,
        )

    private fun answer() =
        SignalEvent.Answer(CALL_ID, "v=0\r\ns=answer\r\n", MediaKeys(MediaSuite.AES_128_GCM, ByteArray(32) { 5 }, ByteArray(32) { 6 }))

    /** An outgoing call up to the vault's answer to `call.start`. */
    private fun TestScope.placed(m: CallMachine) {
        m.call("conn-1")
        runCurrent()
        assertEquals(CallPhase.CALLING, m.live().phase)
    }

    @Test
    fun anOutgoingCallFromTheTapToTheHangUp() = runTest {
        val m = machine()
        assertEquals(CallState.Idle, m.state.value)
        signalling.startGate = Channel(1)
        m.call("conn-1")
        runCurrent()
        assertEquals(CallState.Live("conn-1", CallDirection.OUTGOING, CallPhase.STARTING), m.state.value)
        assertEquals(listOf("start(conn-1, [1])"), signalling.sent)
        advanceTimeBy(120)
        signalling.startGate!!.send(Unit)
        runCurrent()
        val engine = engines.last
        assertEquals(CallState.Live("conn-1", CallDirection.OUTGOING, CallPhase.CALLING, CALL_ID), m.state.value)
        assertEquals(listOf("createOffer", "startGathering(0)"), engine.calls)
        assertEquals(120L, m.diagnostics.value.tapToPlacedMs)

        // The other phone rings.
        engine.emit(MediaEvent.LocalCandidate(candidate(1)))
        advanceTimeBy(300)
        signalling.deliver(SignalEvent.Ringing(CALL_ID))
        runCurrent()
        assertEquals(CallPhase.RINGING, m.live().phase)
        assertEquals(420L, m.diagnostics.value.tapToRingingMs)

        // Its candidates may come before its answer: kept, and given to the engine only after the answer's SDP.
        signalling.deliver(SignalEvent.Ice(CALL_ID, listOf(candidate(7))))
        runCurrent()
        assertTrue(engine.remote.isEmpty())
        val a = answer()
        signalling.deliver(a)
        runCurrent()
        assertEquals(CallPhase.CONNECTING, m.live().phase)
        // The keys before the answer's SDP: no frame is sent or played without them.
        assertEquals(listOf("createOffer", "startGathering(0)", "setKeys", "acceptAnswer", "addRemoteCandidates(1)"), engine.calls)
        assertTrue("the engine has the keys", engine.sendKey!!.all { it == 5.toByte() } && engine.receiveKey!!.all { it == 6.toByte() })
        val wiped = a.keys.send.all { it == 0.toByte() } && a.keys.receive.all { it == 0.toByte() }
        assertTrue("and nobody else: the arrays were wiped", wiped)
        signalling.deliver(SignalEvent.Ice(CALL_ID, listOf(candidate(8), candidate(9))))
        runCurrent()
        assertEquals(3, engine.remote.size)

        advanceTimeBy(700)
        engine.emit(MediaEvent.Connection(MediaConnection.CONNECTED))
        engine.emit(MediaEvent.Cryptor(MediaDirection.SEND, CryptorState.OK))
        engine.emit(MediaEvent.Cryptor(MediaDirection.RECEIVE, CryptorState.OK))
        runCurrent()
        assertEquals(CallPhase.ACTIVE, m.live().phase)
        assertEquals(T0.plusMillis(1120), m.live().activeSince)
        assertEquals(700L, m.diagnostics.value.answerToMediaMs)

        m.setMuted(true)
        runCurrent()
        assertTrue(engine.micOff && m.live().muted)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(1L, m.diagnostics.value.media?.packetsSent)

        // A long call: neither the ring time nor the connect time ends it.
        advanceTimeBy(120_000)
        assertEquals(CallPhase.ACTIVE, m.live().phase)
        m.hangUp()
        runCurrent()
        assertEquals(CallState.Ended("conn-1", CallDirection.OUTGOING, CallEnd.HUNG_UP, CALL_ID, Duration.ofMillis(121_000)), m.state.value)
        assertEquals("end($CALL_ID, hangup)", signalling.sent.last())
        assertTrue(engine.closed)
        m.dismiss()
        runCurrent()
        assertEquals(CallState.Idle, m.state.value)
    }

    @Test
    fun localCandidatesAreSentInBatchesAndNeverFasterThanTheRelayTakesThem() = runTest {
        val m = machine()
        placed(m)
        val engine = engines.last
        repeat(20) { engine.emit(MediaEvent.LocalCandidate(candidate(it + 1))) }
        runCurrent()
        assertTrue("nothing yet: they wait to be sent together", signalling.ice.isEmpty())
        advanceTimeBy(201)
        assertEquals(listOf(16), signalling.ice.map { it.size })
        advanceTimeBy(299)
        assertEquals(1, signalling.ice.size)
        advanceTimeBy(2)
        assertEquals(listOf(16, 4), signalling.ice.map { it.size })
        assertEquals((1..20).map { candidate(it) }, signalling.ice.flatten())
        // Having an address, the gathering timeout does nothing.
        advanceTimeBy(10_000)
        assertEquals(CallPhase.CALLING, m.live().phase)
    }

    @Test
    fun aCallThatSendsTooManyCandidateMessagesStopsSendingThem() = runTest {
        val m = CallMachine(signalling, engines, backgroundScope, { T0 }, { 0L }, CallTimings(maxIceMessages = 2))
        m.call("conn-1")
        runCurrent()
        repeat(60) { engines.last.emit(MediaEvent.LocalCandidate(candidate(it % 200 + 1))) }
        advanceTimeBy(5_000)
        assertEquals(listOf(16, 16), signalling.ice.map { it.size })
    }

    @Test
    fun nobodyAnswersTheCallerSendsTheTimeout() = runTest {
        val m = machine()
        placed(m)
        engines.last.emit(MediaEvent.LocalCandidate(candidate(1)))
        signalling.deliver(SignalEvent.Ringing(CALL_ID))
        advanceTimeBy(44_999)
        assertEquals(CallPhase.RINGING, m.live().phase)
        advanceTimeBy(2)
        assertEquals(CallEnd.NO_ANSWER, m.ended().reason)
        assertEquals("end($CALL_ID, timeout)", signalling.sent.last())
        assertTrue(engines.last.closed)
        assertNull(m.ended().duration)
    }

    @Test
    fun theRingTimeStaysWithinBoundsWhateverTheClocksSay() = runTest {
        // The vault's clock is far behind this phone's: the offer looks expired already. Ring ten seconds anyway.
        signalling.startResult = { PlacedCall(CALL_ID, T0.minusSeconds(600), emptyList()) }
        val m = machine()
        placed(m)
        engines.last.emit(MediaEvent.LocalCandidate(candidate(1)))
        advanceTimeBy(9_999)
        assertEquals(CallPhase.CALLING, m.live().phase)
        advanceTimeBy(2)
        assertEquals(CallEnd.NO_ANSWER, m.ended().reason)
        m.dismiss()

        // And far ahead: never longer than a minute.
        signalling.startResult = { PlacedCall(CALL_ID, T0.plusSeconds(6_000), emptyList()) }
        m.call("conn-1")
        runCurrent()
        engines.last.emit(MediaEvent.LocalCandidate(candidate(1)))
        advanceTimeBy(59_999)
        assertEquals(CallPhase.CALLING, m.live().phase)
        advanceTimeBy(2)
        assertEquals(CallEnd.NO_ANSWER, m.ended().reason)
    }

    @Test
    fun theOtherSidesEndingsOfAnOutgoingCall() = runTest {
        val expected = mapOf(
            "decline" to CallEnd.DECLINED, "busy" to CallEnd.BUSY, "unavailable" to CallEnd.UNAVAILABLE,
            "failed" to CallEnd.FAILED, "hangup" to CallEnd.PEER_HUNG_UP, "timeout" to CallEnd.NO_ANSWER,
        )
        val m = machine()
        for ((reason, end) in expected) {
            placed(m)
            signalling.sent.clear()
            signalling.deliver(SignalEvent.End(CALL_ID, reason))
            runCurrent()
            assertEquals(reason, end, m.ended().reason)
            assertTrue("nothing is sent back", signalling.sent.none { it.startsWith("end") })
            assertTrue(engines.last.closed)
        }
        // Another call's ending is not this call's.
        placed(m)
        signalling.deliver(SignalEvent.End("01JB2Z6V9K3M4N5P6Q7R8S9T51", "hangup"))
        runCurrent()
        assertEquals(CallPhase.CALLING, m.live().phase)
    }

    @Test
    fun theVaultsRefusalsOfCallStart() = runTest {
        val expected = mapOf(
            "busy" to CallEnd.OWN_VAULT_BUSY, "not_found" to CallEnd.UNAVAILABLE, "connection_unavailable" to CallEnd.UNAVAILABLE,
            "owner_check_required" to CallEnd.OWNER_CHECK_REQUIRED, "unsupported_type" to CallEnd.NOT_SUPPORTED,
            "bad_request" to CallEnd.FAILED,
        )
        val m = machine()
        for ((code, end) in expected) {
            signalling.startResult = { throw CallRefusedException(code) }
            m.call("conn-1")
            runCurrent()
            assertEquals(code, end, m.ended().reason)
            assertNull(m.ended().callId)
            assertTrue(signalling.sent.none { it.startsWith("end") })
        }
        // The relay could not be reached, or the vault's answer did not verify.
        signalling.startResult = { throw java.io.IOException("relay") }
        m.call("conn-1")
        runCurrent()
        assertEquals(CallEnd.FAILED, m.ended().reason)
        signalling.startResult = { throw CallSecurityException("ice configuration") }
        m.call("conn-1")
        runCurrent()
        assertEquals(CallEnd.NOT_ENCRYPTED, m.ended().reason)
    }

    @Test
    fun aCallHungUpBeforeTheVaultAnsweredIsEndedWhenItDoes() = runTest {
        val m = machine()
        signalling.startGate = Channel(1)
        m.call("conn-1")
        runCurrent()
        m.hangUp()
        runCurrent()
        assertEquals(CallEnd.HUNG_UP, m.ended().reason)
        assertTrue(engines.last.closed)
        assertTrue(signalling.sent.none { it.startsWith("end") })
        // The vault had taken the call: it is rung off there, or the other member's phone would ring for nobody.
        signalling.startGate!!.send(Unit)
        runCurrent()
        assertEquals("end($CALL_ID, hangup)", signalling.sent.last())
        assertEquals(CallEnd.HUNG_UP, m.ended().reason)
    }

    @Test
    fun anIncomingCallRingsAndCanBeDeclined() = runTest {
        val m = machine()
        signalling.deliver(offer())
        runCurrent()
        assertEquals(CallState.Live("conn-1", CallDirection.INCOMING, CallPhase.INCOMING, CALL_ID), m.state.value)
        assertEquals(listOf("ringing($CALL_ID)"), signalling.sent)
        assertTrue("no media engine, so no address gathered, before the member answers", engines.created.isEmpty())
        // A second offer while one rings is not shown (the vault answers it busy by itself).
        signalling.deliver(offer("01JB2Z6V9K3M4N5P6Q7R8S9T51", "conn-2"))
        runCurrent()
        assertEquals("conn-1", m.live().connectionId)
        m.decline()
        runCurrent()
        assertEquals(CallEnd.DECLINED_HERE, m.ended().reason)
        assertEquals("end($CALL_ID, decline)", signalling.sent.last())
        assertTrue(engines.created.isEmpty())
    }

    @Test
    fun aRingingCallWhoseCallerHasGoneStopsByItselfAndSendsNothing() = runTest {
        val m = machine()
        signalling.deliver(offer())
        runCurrent()
        advanceTimeBy(47_999)
        assertEquals(CallPhase.INCOMING, m.live().phase)
        advanceTimeBy(2)
        assertEquals(CallEnd.MISSED, m.ended().reason)
        // Not `call.end{timeout}`: sent by the callee it would end the call in its own vault before the caller's
        // timeout arrives, and the vault would never record the missed call.
        assertEquals(listOf("ringing($CALL_ID)"), signalling.sent)
    }

    @Test
    fun theEndingsOfARingingIncomingCall() = runTest {
        val expected = mapOf(
            "timeout" to CallEnd.MISSED, "hangup" to CallEnd.MISSED, "unavailable" to CallEnd.MISSED,
            "answered_elsewhere" to CallEnd.ANSWERED_ELSEWHERE, "decline" to CallEnd.DECLINED_HERE,
        )
        val m = machine()
        for ((reason, end) in expected) {
            signalling.deliver(offer())
            runCurrent()
            signalling.sent.clear()
            signalling.deliver(SignalEvent.End(CALL_ID, reason))
            runCurrent()
            assertEquals(reason, end, m.ended().reason)
            assertTrue(signalling.sent.isEmpty())
        }
    }

    @Test
    fun answeringAnIncomingCall() = runTest {
        val m = machine()
        signalling.deliver(offer())
        runCurrent()
        // The caller's candidates arrive while it rings.
        signalling.deliver(SignalEvent.Ice(CALL_ID, listOf(candidate(1), candidate(2))))
        runCurrent()
        m.answer()
        runCurrent()
        val engine = engines.last
        assertEquals(CallPhase.CONNECTING, m.live().phase)
        assertEquals(listOf("acceptOffer", "setKeys", "addRemoteCandidates(2)"), engine.calls)
        assertEquals(listOf("ringing($CALL_ID)", "answer($CALL_ID)"), signalling.sent)
        assertTrue(engine.sendKey!!.all { it == 1.toByte() } && engine.receiveKey!!.all { it == 2.toByte() })

        engine.emit(MediaEvent.LocalCandidate(candidate(3)))
        advanceTimeBy(250)
        assertEquals(listOf(listOf(candidate(3))), signalling.ice)
        signalling.deliver(SignalEvent.Ice(CALL_ID, listOf(candidate(4))))
        engine.emit(MediaEvent.Connection(MediaConnection.CONNECTED))
        runCurrent()
        assertEquals(CallPhase.ACTIVE, m.live().phase)
        assertEquals(3, engine.remote.size)
        // The ring time is over long before this: it no longer applies.
        advanceTimeBy(60_000)
        assertEquals(CallPhase.ACTIVE, m.live().phase)

        signalling.deliver(SignalEvent.End(CALL_ID, "hangup"))
        runCurrent()
        assertEquals(CallEnd.PEER_HUNG_UP, m.ended().reason)
        assertEquals(Duration.ofMillis(60_000), m.ended().duration)
        assertTrue(engine.closed)
    }

    @Test
    fun aCalleesCandidatesWaitUntilItsAnswerHasGone() = runTest {
        val gate = Channel<Unit>(1)
        val slow = object : FakeSignalling() {
            override suspend fun answer(callId: String, sdp: String, suites: List<MediaSuite>): MediaKeys {
                gate.receive()
                return super.answer(callId, sdp, suites)
            }
        }
        val m = machine(slow)
        slow.deliver(offer())
        runCurrent()
        m.answer()
        runCurrent()
        engines.last.emit(MediaEvent.LocalCandidate(candidate(1)))
        advanceTimeBy(2_000)
        // Only the call's device may send candidates, and this device is that only once its answer is with the vault.
        assertTrue(slow.ice.isEmpty())
        gate.send(Unit)
        advanceTimeBy(250)
        assertEquals(1, slow.ice.size)
    }

    @Test
    fun anAnswerThatCameTooLateEndsUnavailable() = runTest {
        val m = machine()
        signalling.deliver(offer())
        runCurrent()
        m.answer()
        runCurrent()
        signalling.deliver(SignalEvent.End(CALL_ID, "unavailable"))
        runCurrent()
        assertEquals(CallEnd.UNAVAILABLE, m.ended().reason)
        assertTrue(engines.last.closed)
    }

    @Test
    fun noAddressToBeReachedAtEndsTheCallWithItsOwnReason() = runTest {
        // The caller: gathering never produces anything (a blocked network permission): not "Gathering" for ever.
        val m = machine()
        placed(m)
        advanceTimeBy(7_999)
        assertEquals(CallPhase.CALLING, m.live().phase)
        advanceTimeBy(2)
        assertEquals(CallEnd.NO_ADDRESS, m.ended().reason)
        assertEquals("end($CALL_ID, failed)", signalling.sent.last())
        assertTrue(engines.last.closed)
        m.dismiss()

        // Gathering that ends with nothing says so at once.
        placed(m)
        engines.last.emit(MediaEvent.GatheringDone(0))
        runCurrent()
        assertEquals(CallEnd.NO_ADDRESS, m.ended().reason)
        m.dismiss()

        // The callee, after answering.
        signalling.sent.clear()
        signalling.deliver(offer())
        runCurrent()
        m.answer()
        runCurrent()
        advanceTimeBy(8_001)
        assertEquals(CallEnd.NO_ADDRESS, m.ended().reason)
        assertEquals("end($CALL_ID, failed)", signalling.sent.last())
    }

    @Test
    fun noPathBetweenTheTwoDevices() = runTest {
        val m = machine()
        placed(m)
        engines.last.emit(MediaEvent.LocalCandidate(candidate(1)))
        signalling.deliver(answer())
        runCurrent()
        advanceTimeBy(29_999)
        assertEquals(CallPhase.CONNECTING, m.live().phase)
        advanceTimeBy(2)
        assertEquals(CallEnd.COULD_NOT_CONNECT, m.ended().reason)
        assertEquals("end($CALL_ID, failed)", signalling.sent.last())
        m.dismiss()

        // The media stack gives up first.
        placed(m)
        signalling.deliver(answer())
        runCurrent()
        engines.last.emit(MediaEvent.Connection(MediaConnection.FAILED))
        runCurrent()
        assertEquals(CallEnd.COULD_NOT_CONNECT, m.ended().reason)
    }

    @Test
    fun aPathLostDuringTheCall() = runTest {
        val m = machine()
        placed(m)
        signalling.deliver(answer())
        runCurrent()
        val engine = engines.last
        engine.emit(MediaEvent.Connection(MediaConnection.CONNECTED))
        runCurrent()
        engine.emit(MediaEvent.Connection(MediaConnection.DISCONNECTED))
        runCurrent()
        assertTrue(m.live().reconnecting)
        assertEquals(CallPhase.ACTIVE, m.live().phase)
        engine.emit(MediaEvent.Connection(MediaConnection.CONNECTED))
        runCurrent()
        assertFalse(m.live().reconnecting)
        advanceTimeBy(5_000)
        engine.emit(MediaEvent.Connection(MediaConnection.DISCONNECTED))
        engine.emit(MediaEvent.Connection(MediaConnection.FAILED))
        runCurrent()
        assertEquals(CallEnd.CONNECTION_LOST, m.ended().reason)
        assertEquals(Duration.ofMillis(5_000), m.ended().duration)
        assertEquals("end($CALL_ID, failed)", signalling.sent.last())
    }

    @Test
    fun framesThatDoNotDecryptEndTheCall() = runTest {
        for (state in listOf(CryptorState.DECRYPTION_FAILED, CryptorState.INTERNAL_ERROR, CryptorState.UNKNOWN, CryptorState.MISSING_KEY)) {
            val signalling = FakeSignalling()
            val m = machine(signalling)
            placed(m)
            val engine = engines.last
            // Before the answer the caller has no key: its sender says so and sends nothing. Not a failure.
            engine.emit(MediaEvent.Cryptor(MediaDirection.SEND, CryptorState.MISSING_KEY))
            runCurrent()
            assertEquals(CallPhase.CALLING, m.live().phase)
            signalling.deliver(answer())
            runCurrent()
            engine.emit(MediaEvent.Connection(MediaConnection.CONNECTED))
            engine.emit(MediaEvent.Cryptor(MediaDirection.SEND, CryptorState.OK))
            runCurrent()
            assertEquals(CallPhase.ACTIVE, m.live().phase)
            signalling.sent.clear()
            engine.emit(MediaEvent.Cryptor(MediaDirection.RECEIVE, state))
            runCurrent()
            assertEquals(state.name, CallEnd.NOT_ENCRYPTED, m.ended().reason)
            // Ended, not repaired: the key is not set a second time.
            assertEquals(listOf("end($CALL_ID, failed)"), signalling.sent)
            assertEquals(1, engine.calls.count { it == "setKeys" })
            assertTrue(engine.closed)
        }
    }

    /**
     * Review F1: an SDP with a media section the frame cryptors do not cover. The signalling refuses such SDP
     * before it gets here; an engine that still finds one says so, and the call ends: on the caller with the
     * answer, on the callee with the offer, before any answer is sent.
     */
    @Test
    fun mediaOutsideTheFrameEncryptionEndsTheCall() = runTest {
        val strict = FakeEngines(make = { FakeEngine().apply { onRemoteSdp = { throw MediaNotEncryptedException("2 media sections") } } })
        val m = machine(e = strict)
        placed(m)
        signalling.deliver(answer())
        runCurrent()
        assertEquals(CallEnd.NOT_ENCRYPTED, m.ended().reason)
        assertEquals("end($CALL_ID, failed)", signalling.sent.last())
        assertTrue(strict.last.closed)

        val other = FakeSignalling()
        val callee = machine(other, strict)
        other.deliver(offer())
        runCurrent()
        callee.answer()
        runCurrent()
        assertEquals(CallEnd.NOT_ENCRYPTED, callee.ended().reason)
        assertEquals(listOf("ringing($CALL_ID)", "end($CALL_ID, failed)"), other.sent)
        assertTrue("no key was ever set", strict.last.calls.none { it == "setKeys" })
    }

    /**
     * Review N1, the second lock: audio on an SSRC nobody announced shows as a second received stream. Its frames
     * go through the same decryption and are not played; the call ends all the same.
     */
    @Test
    fun aSecondReceivedAudioStreamEndsTheCall() = runTest {
        val m = machine()
        placed(m)
        signalling.deliver(answer())
        runCurrent()
        val engine = engines.last
        engine.emit(MediaEvent.Connection(MediaConnection.CONNECTED))
        advanceTimeBy(3_000)
        assertEquals(CallPhase.ACTIVE, m.live().phase)
        engine.streams = 2
        advanceTimeBy(1_001)
        assertEquals(CallEnd.NOT_ENCRYPTED, m.ended().reason)
        assertEquals("end($CALL_ID, failed)", signalling.sent.last())
        assertTrue(engine.closed)
    }

    /**
     * Review N2: whatever ends the listening to the vault's events, it starts again. (The signalling refuses and
     * counts what goes wrong, so this is the second lock.) Before the fix the phone never rang again until restart.
     */
    @Test
    fun theListeningToTheVaultStartsAgainAfterAFailure() = runTest {
        var collections = 0
        val flaky = object : FakeSignalling() {
            override val events = kotlinx.coroutines.flow.flow {
                if (collections++ == 0) error("no session with the vault")
                channel.receiveAsFlow().collect { emit(it) }
            }
        }
        val m = machine(flaky)
        runCurrent()
        assertEquals(1, m.listenRestarts.value)
        advanceTimeBy(1_001)
        flaky.deliver(offer())
        runCurrent()
        assertEquals(CallPhase.INCOMING, m.live().phase)
    }

    /**
     * Review F4: the vault's answer to `call.start` and the events it passes on reach the machine through two
     * queues, so an event of the call can be handled before the call has its id. It is held, not dropped.
     */
    @Test
    fun whatArrivesBeforeTheVaultsAnswerToCallStartIsNotLost() = runTest {
        // Busy: before the fix the call stayed "calling" and ended "no answer" 45 s later.
        val m = machine()
        signalling.startGate = Channel(1)
        m.call("conn-1")
        runCurrent()
        signalling.deliver(SignalEvent.End(CALL_ID, "busy"))
        runCurrent()
        assertEquals(CallPhase.STARTING, m.live().phase)
        signalling.startGate!!.send(Unit)
        runCurrent()
        assertEquals(CallEnd.BUSY, m.ended().reason)
        assertEquals(CALL_ID, m.ended().callId)
        assertTrue(signalling.sent.none { it.startsWith("end") })

        // Ringing, candidates and the answer, all early, in order: before the fix the keys were wiped and the call
        // could never connect.
        val s2 = FakeSignalling().apply { startGate = Channel(1) }
        val m2 = machine(s2)
        m2.call("conn-1")
        runCurrent()
        val a = answer()
        s2.deliver(SignalEvent.Ringing(CALL_ID))
        s2.deliver(SignalEvent.Ice(CALL_ID, listOf(candidate(7))))
        s2.deliver(a)
        runCurrent()
        s2.startGate!!.send(Unit)
        runCurrent()
        val engine = engines.last
        assertEquals(CallPhase.CONNECTING, m2.live().phase)
        assertEquals(listOf("createOffer", "startGathering(0)", "setKeys", "acceptAnswer", "addRemoteCandidates(1)"), engine.calls)
        assertTrue(engine.sendKey!!.all { it == 5.toByte() })
        assertTrue(m2.diagnostics.value.tapToRingingMs != null)

        // An early event of another call is not this call's; an early answer's keys do not outlive a hang-up or a
        // refused start.
        val s3 = FakeSignalling().apply { startGate = Channel(1) }
        val m3 = machine(s3)
        m3.call("conn-1")
        runCurrent()
        val stray = answer()
        s3.deliver(SignalEvent.End("01JB2Z6V9K3M4N5P6Q7R8S9T51", "busy"))
        s3.deliver(stray)
        runCurrent()
        m3.hangUp()
        runCurrent()
        assertEquals(CallEnd.HUNG_UP, m3.ended().reason)
        assertTrue(stray.keys.send.all { it == 0.toByte() } && stray.keys.receive.all { it == 0.toByte() })
    }

    @Test
    fun anAnswerThatDoesNotVerifyEndsTheCall() = runTest {
        val m = machine()
        placed(m)
        signalling.deliver(SignalEvent.AnswerRejected(CALL_ID))
        runCurrent()
        assertEquals(CallEnd.NOT_ENCRYPTED, m.ended().reason)
        assertEquals("end($CALL_ID, failed)", signalling.sent.last())
        assertTrue(engines.last.calls.none { it == "setKeys" || it == "acceptAnswer" })
    }

    @Test
    fun anAnswerForNoCallLeavesNoKeyBehind() = runTest {
        val m = machine()
        val a = answer()
        signalling.deliver(a)
        runCurrent()
        assertEquals(CallState.Idle, m.state.value)
        assertTrue(a.keys.send.all { it == 0.toByte() } && a.keys.receive.all { it == 0.toByte() })
    }

    @Test
    fun aMediaStackWhoseLoggingIsOnIsNotUsed() = runTest {
        val m = machine(e = FakeEngines(make = { throw MediaLoggingException("debug output is enabled") }))
        m.call("conn-1")
        runCurrent()
        assertEquals(CallEnd.NOT_ENCRYPTED, m.ended().reason)
        assertTrue("nothing left the phone", signalling.sent.isEmpty())

        // The engine refuses the keys (the guard runs again before every key).
        val refusing = FakeEngines(make = { FakeEngine().apply { onKeys = { throw MediaLoggingException("a logger is injected") } } })
        val other = FakeSignalling()
        val m2 = machine(other, refusing)
        m2.call("conn-1")
        runCurrent()
        other.deliver(answer())
        runCurrent()
        assertEquals(CallEnd.NOT_ENCRYPTED, m2.ended().reason)
        assertEquals("end($CALL_ID, failed)", other.sent.last())
    }

    @Test
    fun lockingTheVaultEndsTheCall() = runTest {
        val m = machine()
        placed(m)
        m.vaultLocking()
        runCurrent()
        assertEquals(CallEnd.LOCKED, m.ended().reason)
        assertEquals("end($CALL_ID, hangup)", signalling.sent.last())
        // And a ringing one is declined.
        m.dismiss()
        signalling.deliver(offer())
        runCurrent()
        m.vaultLocking()
        runCurrent()
        assertEquals(CallEnd.LOCKED, m.ended().reason)
        assertEquals("end($CALL_ID, decline)", signalling.sent.last())
    }

    @Test
    fun thePostQuantumDtlsTrialIsOffUnlessADebugBuildAsksForIt() = runTest {
        assertFalse(MediaConfig().dtlsPqcTrial)
        val m = machine()
        placed(m)
        assertEquals(MediaConfig(dtlsPqcTrial = false), engines.config)
        val asked = FakeEngines()
        val m2 = machine(FakeSignalling(), asked, MediaConfig(dtlsPqcTrial = true))
        m2.call("conn-1")
        runCurrent()
        assertEquals(true, asked.config?.dtlsPqcTrial)
    }

    // --- two devices, the real negotiation in between ---

    private val one = listOf(MediaSuite.AES_128_GCM)
    private val both = listOf(MediaSuite.AES_256_GCM, MediaSuite.AES_128_GCM)

    private class Pair(
        val x: Exchange,
        val caller: CallMachine,
        val callee: CallMachine,
        val callerEngines: FakeEngines,
        val calleeEngines: FakeEngines,
    )

    private fun TestScope.pair(callerSuites: List<MediaSuite>, calleeSuites: List<MediaSuite>): Pair {
        val x = Exchange().also { it.calleeSuites = calleeSuites }
        val a = FakeEngines(callerSuites)
        val b = FakeEngines(calleeSuites)
        return Pair(x, machine(x.caller, a), machine(x.callee, b), a, b)
    }

    /** Call, ring, answer, and what the two media stacks then do with the keys they were given. */
    private fun TestScope.talk(p: Pair) {
        p.caller.call("peer")
        runCurrent()
        assertEquals(CallPhase.RINGING, (p.caller.state.value as CallState.Live).phase)
        p.callee.answer()
        runCurrent()
        val bothLive = p.caller.state.value is CallState.Live && p.callee.state.value is CallState.Live
        if (bothLive) p.x.connect(p.callerEngines.last, p.calleeEngines.last)
        runCurrent()
    }

    @Test
    fun aSuiteOneDeviceAndASuiteOneAndTwoDeviceTalkOnSuiteOne() = runTest {
        for ((a, b) in listOf(one to both, both to one)) {
            val p = pair(a, b)
            talk(p)
            assertEquals(CallPhase.ACTIVE, (p.caller.state.value as CallState.Live).phase)
            assertEquals(CallPhase.ACTIVE, (p.callee.state.value as CallState.Live).phase)
            assertEquals(MediaSuite.AES_128_GCM, p.callerEngines.last.suite)
            assertEquals(MediaSuite.AES_128_GCM, p.calleeEngines.last.suite)
        }
    }

    @Test
    fun twoDevicesThatCanDoSuiteTwoTalkOnSuiteOneInFormOneWithCryptorsMadeForIt() = runTest {
        // Form 1 offers and chooses suite 1 only (VAULT-MESSAGING "Call forms", rules 1 and 3).
        val p = pair(both, both)
        talk(p)
        assertEquals(CallPhase.ACTIVE, (p.caller.state.value as CallState.Live).phase)
        assertEquals(CallPhase.ACTIVE, (p.callee.state.value as CallState.Live).phase)
        for (e in listOf(p.callerEngines.last, p.calleeEngines.last)) {
            // Made for the call's suite before any SDP, and keyed for that same suite: nothing is remade.
            assertEquals(MediaSuite.AES_128_GCM, e.madeFor)
            assertEquals(MediaSuite.AES_128_GCM, e.suite)
        }
    }

    @Test
    fun aSuiteListChangedOnTheWayGivesNoCall() = runTest {
        val p = pair(both, both)
        p.x.onOffer = { it.replace("a=vettid-media-suites:1", "a=vettid-media-suites:2 1") }
        talk(p)
        // The callee answers 1 from the changed list, the caller derives with the list it sent: both set keys for
        // suite 1, but not the same keys: nothing decrypts, both end the call.
        assertEquals(MediaSuite.AES_128_GCM, p.callerEngines.last.suite)
        assertEquals(MediaSuite.AES_128_GCM, p.calleeEngines.last.suite)
        assertFalse(p.callerEngines.last.sendKey.contentEquals(p.calleeEngines.last.receiveKey))
        assertEquals(CallEnd.NOT_ENCRYPTED, (p.caller.state.value as CallState.Ended).reason)
        assertEquals(CallEnd.NOT_ENCRYPTED, (p.callee.state.value as CallState.Ended).reason)
        assertTrue(p.callerEngines.last.closed && p.calleeEngines.last.closed)
    }

    @Test
    fun aChoiceRewrittenOnTheWayGivesNoCall() = runTest {
        // A choice that was never offered is refused when it is read: the caller sets no key at all.
        for ((a, b) in listOf(one to both, both to both)) {
            val q = pair(a, b)
            q.x.onAnswer = { it.replace("a=vettid-media-suite:1", "a=vettid-media-suite:2") }
            talk(q)
            assertEquals(CallEnd.NOT_ENCRYPTED, (q.caller.state.value as CallState.Ended).reason)
            assertNull(q.callerEngines.last.suite)
            assertEquals("the callee is told", CallEnd.FAILED, (q.callee.state.value as CallState.Ended).reason)
        }
    }

    @Test
    fun aCallStartNeverOffersAnythingButSuiteOne() {
        // Guard (VAULT-MESSAGING "Call forms" rule 3, "Mixed releases"): whatever the engine can do, what a
        // `call.start` offers is [1] or nothing, until form 1 is retired.
        val s = FakeSignalling()
        for (suites in listOf(one, both, listOf(MediaSuite.AES_256_GCM), MediaSuite.entries.toList(), emptyList())) {
            val offered = s.offerable(suites)
            assertTrue("$suites -> $offered", offered.isEmpty() || offered == one)
            assertEquals(MediaSuite.AES_128_GCM in suites, offered == one)
        }
    }

    @Test
    fun aDeviceWithoutSuiteOnePlacesNoFormOneCall() = runTest {
        val p = pair(listOf(MediaSuite.AES_256_GCM), one)
        p.caller.call("peer")
        runCurrent()
        assertEquals(CallEnd.NOT_ENCRYPTED, (p.caller.state.value as CallState.Ended).reason)
        assertTrue("nothing was sent", p.x.caller.sent.isEmpty())
    }

    @Test
    fun aFormOneOfferWithoutSuiteOneDoesNotRing() = runTest {
        val p = pair(one, both)
        p.x.onOffer = { it.replace("a=vettid-media-suites:1", "a=vettid-media-suites:2") }
        p.caller.call("peer")
        runCurrent()
        assertTrue("the callee's engine was never made", p.calleeEngines.created.isEmpty())
        assertFalse(p.callee.state.value is CallState.Live)
    }

    @Test
    fun keysForAnotherSuiteThanTheCryptorsWereMadeForEndTheCall() = runTest {
        // The engine never remakes its cryptors: keys for another suite are refused, and the call ends as not encrypted.
        val s = FakeSignalling()
        val e = FakeEngines(both)
        val m = machine(s, e)
        s.answerResult = { MediaKeys(MediaSuite.AES_256_GCM, ByteArray(32) { 1 }, ByteArray(32) { 2 }) }
        s.deliver(offer())
        runCurrent()
        m.answer()
        runCurrent()
        assertEquals(MediaSuite.AES_128_GCM, e.last.madeFor)
        assertNull("no key was taken", e.last.suite)
        assertEquals(CallEnd.NOT_ENCRYPTED, m.ended().reason)
        assertTrue(e.last.closed)
    }
}
