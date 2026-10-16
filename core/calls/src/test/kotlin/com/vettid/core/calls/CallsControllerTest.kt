@file:OptIn(MadeByCallApi::class)
@file:Suppress("MaxLineLength") // One record a line.

package com.vettid.core.calls

import com.vettid.core.crypto.call.CallWire
import com.vettid.core.crypto.call.MediaSuite
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.data.calls.CallAvailability
import com.vettid.core.data.calls.CallOutcome
import com.vettid.core.data.calls.NoCallAccess
import com.vettid.core.vault.AcceptedCall
import com.vettid.core.vault.CallCandidate
import com.vettid.core.vault.CallEvent
import com.vettid.core.vault.CallKeys
import com.vettid.core.vault.CallRecord
import com.vettid.core.vault.IncomingCall
import com.vettid.core.vault.MadeByCallApi
import com.vettid.core.vault.OutgoingCall
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CallsControllerTest {
    private class Gateway : CallGateway {
        val channel = Channel<CallEvent>(Channel.UNLIMITED)
        override val events: Flow<CallEvent> = channel.receiveAsFlow()
        val sent = mutableListOf<String>()
        var list: () -> List<CallRecord> = { emptyList() }
        var listGate: suspend () -> Unit = {}

        override suspend fun start(connectionId: String, sdp: String, suites: Collection<MediaSuite>): OutgoingCall {
            sent += "start($connectionId)"
            val ice = CallWire.IceConfig(CALL_ID, T0.plusSeconds(600), emptyList())
            return OutgoingCall(CALL_ID, connectionId, "audio", T0.plusSeconds(45), ice, listOf(1), KemPrivateKey.generate())
        }

        var incoming = false
        var endGate: Channel<Unit>? = null

        override suspend fun incoming(event: VaultMessage, pinnedFingerprint: String, suites: Collection<MediaSuite>): IncomingCall {
            check(incoming) { "unused" }
            val ice = CallWire.IceConfig(CALL_ID, T0.plusSeconds(600), emptyList())
            val n = com.vettid.core.crypto.call.CallMedia.Negotiated(MediaSuite.AES_128_GCM, listOf(1))
            return IncomingCall(CALL_ID, "conn-1", "audio", "v=0", T0.plusSeconds(45), Duration.ofSeconds(45), ice, n, ByteArray(1216))
        }

        override suspend fun answer(call: IncomingCall, sdp: String): CallKeys =
            CallKeys(MediaSuite.AES_128_GCM, ByteArray(32) { 1 }, ByteArray(32) { 2 })

        override suspend fun accept(call: OutgoingCall, event: VaultMessage, pinnedFingerprint: String): AcceptedCall =
            AcceptedCall(call.callId, "v=0", CallKeys(MediaSuite.AES_128_GCM, ByteArray(32) { 5 }, ByteArray(32) { 6 }))

        override suspend fun ringing(callId: String) = Unit

        override suspend fun ice(callId: String, candidates: List<CallCandidate>) = Unit

        override suspend fun end(callId: String, reason: String) {
            endGate?.receive()
            sent += "end($callId, $reason)"
        }

        override suspend fun list(limit: Int): List<CallRecord> {
            sent += "list($limit)"
            listGate()
            return list()
        }
    }

    private class Vault : CallVault {
        override val unlocked = MutableStateFlow(false)
        override val gateway = Gateway()
        override val droppedForTime = MutableSharedFlow<String>(extraBufferCapacity = 8)
        override val recheck = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        var refreshed = 0
        var signer: String? = "vettid"

        override suspend fun signerClass(): String? = signer

        override suspend fun refreshConnections() {
            refreshed++
        }

        override suspend fun fingerprint(connectionId: String): String? = "aaaa bbbb"

        var showNames = true

        override fun name(connectionId: String): String? = "Ada Lovelace"

        override fun notificationName(connectionId: String): String? = if (showNames) name(connectionId) else null
    }

    private class Audio : CallAudio, CallTones {
        override val state: StateFlow<AudioState> = MutableStateFlow(AudioState())
        var on = false
        var ringBack = false
        var ringtone = false
        var chosen: AudioRoute? = null

        override fun start() {
            on = true
        }

        override fun setRoute(route: AudioRoute) {
            chosen = route
        }

        override fun stop() {
            on = false
        }

        override fun ringBack(on: Boolean) {
            ringBack = on
        }

        override fun ringtone(on: Boolean) {
            ringtone = on
        }
    }

    private val vault = Vault()
    private val engines = FakeEngines()
    private val audio = Audio()
    private var microphone = true
    private var services = 0

    private fun TestScope.controller(config: MediaConfig = MediaConfig(), testBuild: Boolean = false) = CallsController(
        vault, engines, backgroundScope, audio, audio, { microphone }, { services++ }, config, testBuild,
        now = { T0.plusMillis(testScheduler.currentTime) },
    )

    private fun record(id: String, state: String, direction: String = "out", started: Instant = T0, reason: String? = null, answered: Instant? = null, ended: Instant? = null) =
        CallRecord(id, "conn-1", direction, "audio", state, reason, started.toString(), answered?.toString(), ended?.toString())

    @Test
    fun aBuildWithoutCallsShowsNoButton() {
        assertEquals(CallAvailability.NOT_IN_BUILD, NoCallAccess.availability.value)
    }

    @Test
    fun theVaultIsAskedAfterEveryUnlockWhetherItHasCalls() = runTest {
        val c = controller()
        runCurrent()
        assertEquals(CallAvailability.UNKNOWN, c.availability.value)
        assertTrue("a locked vault is not asked", vault.gateway.sent.isEmpty())
        c.call("conn-1")
        runCurrent()
        assertEquals(CallState.Idle, c.state.value)

        vault.unlocked.value = true
        runCurrent()
        assertEquals(listOf("list(200)"), vault.gateway.sent)
        assertEquals(CallAvailability.READY, c.availability.value)

        vault.unlocked.value = false
        runCurrent()
        assertEquals(CallAvailability.UNKNOWN, c.availability.value)

        // A release without calls: this is how the app learns it (no message lists a release's features).
        vault.gateway.list = { throw VaultOpException("call.list", "unsupported_type") }
        vault.unlocked.value = true
        runCurrent()
        assertEquals(CallAvailability.NOT_IN_VAULT, c.availability.value)
        c.call("conn-1")
        runCurrent()
        assertEquals(CallState.Idle, c.state.value)
        assertTrue(c.history("conn-1").isEmpty())
    }

    @Test
    fun anUnreachableVaultIsAskedAgainAndAnotherRefusalLeavesItUnknown() = runTest {
        var failures = 2
        vault.gateway.list = { if (failures-- > 0) throw java.io.IOException("relay") else emptyList() }
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        assertEquals(CallAvailability.UNKNOWN, c.availability.value)
        advanceTimeBy(7_000)
        assertEquals(CallAvailability.READY, c.availability.value)

        vault.unlocked.value = false
        runCurrent()
        vault.gateway.list = { throw VaultOpException("call.list", "owner_check_required") }
        vault.unlocked.value = true
        runCurrent()
        assertEquals(CallAvailability.UNKNOWN, c.availability.value)
    }

    @Test
    fun callsLeftLiveInTheVaultByAnEarlierRunAreEndedAtUnlock() = runTest {
        vault.gateway.list = {
            listOf(
                record("A", "active", "in"), // the app died during this call: the vault would stay busy for 12 hours
                record("B", "ringing", "out", T0.minusSeconds(300)), // an outgoing call nobody timed out
                record("C", "ringing", "in", T0.minusSeconds(300)),
                record("D", "ringing", "in", T0.minusSeconds(10)), // may be ringing right now: left alone
                record("E", "ended", "out", reason = "hangup"),
            )
        }
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        assertEquals(listOf("list(200)", "end(A, failed)", "end(B, timeout)", "end(C, failed)"), vault.gateway.sent)
        assertEquals(3, c.diagnostics.value.staleEnded)
    }

    @Test
    fun noCallWithoutTheMicrophone() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        var asked = 0
        backgroundScope.launch { c.microphoneNeeded.collect { asked++ } }
        runCurrent()
        microphone = false
        c.call("conn-1")
        runCurrent()
        assertEquals(1, asked)
        assertEquals(CallState.Idle, c.state.value)
        assertTrue(engines.created.isEmpty())
        microphone = true
        c.call("conn-1")
        runCurrent()
        assertEquals(CallPhase.CALLING, (c.state.value as CallState.Live).phase)
    }

    @Test
    fun aroundACallTheAudioTheServiceAndTheButton() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        runCurrent()
        assertTrue(audio.on)
        assertTrue(services > 0)
        assertEquals(CallAvailability.IN_CALL, c.availability.value)
        assertEquals("Ada Lovelace", c.displayName("conn-1"))
        // "Show in notifications: nothing": the notification has no name; the call screen still does.
        vault.showNames = false
        assertNull(c.displayName("conn-1"))
        assertEquals("Ada Lovelace", c.screenName("conn-1"))
        vault.gateway.channel.send(CallEvent.Ringing(CALL_ID))
        runCurrent()
        assertTrue(audio.ringBack)
        c.setRoute(AudioRoute.SPEAKER)
        assertEquals(AudioRoute.SPEAKER, audio.chosen)
        // A second tap does not start a second call.
        c.call("conn-1")
        runCurrent()
        assertEquals(1, vault.gateway.sent.count { it.startsWith("start") })

        // Locking the vault ends the call.
        vault.unlocked.value = false
        runCurrent()
        // Review P1 of #128: and nothing of it is kept for the next unlock (no ended line from before the lock).
        assertEquals(CallState.Idle, c.state.value)
        assertEquals("end($CALL_ID, hangup)", vault.gateway.sent.last())
        assertTrue(!audio.on && !audio.ringBack)
    }

    /** Review P1 of #128: a call that had ended, its line still on the screen, when the vault locks. */
    @Test
    fun anEndedCallIsNotShownAgainAfterTheNextUnlock() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        runCurrent()
        c.hangUp()
        runCurrent()
        assertTrue(c.state.value is CallState.Ended)
        vault.unlocked.value = false
        runCurrent()
        assertEquals(CallState.Idle, c.state.value)
        vault.unlocked.value = true
        runCurrent()
        assertEquals(CallState.Idle, c.state.value)
    }

    @Test
    fun aCallEndsTwelveHoursAfterItWasAnswered() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        runCurrent()
        engines.last.emit(MediaEvent.LocalCandidate(candidate(1)))
        val answer = Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T61", type = "call.answer", ts = T0, body = """{"call_id":"$CALL_ID"}""".toByteArray())
        vault.gateway.channel.send(CallEvent.Answer(VaultMessage(answer)))
        runCurrent()
        engines.last.emit(MediaEvent.Connection(MediaConnection.CONNECTED))
        runCurrent()
        assertEquals(CallPhase.ACTIVE, (c.state.value as CallState.Live).phase)
        advanceTimeBy(Duration.ofHours(12).toMillis() - 1_000)
        assertEquals(CallPhase.ACTIVE, (c.state.value as CallState.Live).phase)
        advanceTimeBy(2_000)
        // The vault stops counting the call as live then, and the frame cipher's nonce must not wrap.
        assertEquals(CallEnd.HUNG_UP, (c.state.value as CallState.Ended).reason)
        assertEquals("end($CALL_ID, hangup)", vault.gateway.sent.last())
    }

    @Test
    fun thePostQuantumTrialCanBeAskedForInADebugBuildOnly() = runTest {
        val faulty = MediaConfig(faults = MediaFaults(wrongSendKey = true))
        // Never what the app starts with, in any build.
        assertThrows(IllegalStateException::class.java) { controller(MediaConfig(dtlsPqcTrial = true), testBuild = true) }
        assertThrows(IllegalStateException::class.java) { controller(faulty, testBuild = true) }

        // A test build whose vault says "development": refused until the vault has said so, allowed after.
        vault.signer = "development"
        val test = controller(testBuild = true)
        runCurrent()
        assertTrue(!test.testsAllowed.value)
        assertThrows(IllegalStateException::class.java) { test.setMediaConfig(faulty) }
        vault.unlocked.value = true
        runCurrent()
        assertTrue(test.testsAllowed.value)
        test.setMediaConfig(faulty)
        test.call("conn-1")
        runCurrent()
        assertEquals("the next call's engine gets it", faulty, engines.config)
        // A lock: not known any more, so nothing of it applies until the vault has said so again.
        vault.unlocked.value = false
        runCurrent()
        assertTrue(!test.testsAllowed.value)
        assertEquals(MediaConfig(), test.mediaConfig)
        assertThrows(IllegalStateException::class.java) { test.setMediaConfig(MediaConfig(dtlsPqcTrial = true)) }
        test.setMediaConfig(MediaConfig()) // the plain configuration can always be set
    }

    @Test
    fun aTestBuildSignedWithAnyOtherKeyAndAnyBuildThatIsNotATestBuildRefuseTheFaults() = runTest {
        val faulty = MediaConfig(faults = MediaFaults(keepRemoteSsrc = true))
        for (signer in listOf("vettid", "google_play", null, "something new")) {
            val v = Vault().apply { this.signer = signer }
            val c = CallsController(v, engines, backgroundScope, audio, audio, { true }, { }, testBuild = true)
            v.unlocked.value = true
            runCurrent()
            assertTrue("$signer", !c.testsAllowed.value)
            assertThrows(IllegalStateException::class.java) { c.setMediaConfig(faulty) }
            assertEquals(MediaConfig(), c.mediaConfig)
        }
        // Not a test build: whatever the vault says.
        val v = Vault().apply { signer = "development" }
        val c = CallsController(v, engines, backgroundScope, audio, audio, { true }, { }, testBuild = false)
        v.unlocked.value = true
        runCurrent()
        assertTrue(!c.testsAllowed.value)
        assertThrows(IllegalStateException::class.java) { c.setMediaConfig(faulty) }
        assertThrows(IllegalStateException::class.java) { c.setMediaConfig(MediaConfig(dtlsPqcTrial = true)) }
    }

    /**
     * Review D3: the vault's events are listened to from before the unlock is complete, so an offer can arrive in
     * the moments before the app shows the open vault. It rings once it does, never over the unlock screen; and
     * not at all if no unlock comes.
     */
    @Test
    fun anOfferRingsOnlyOnceTheVaultIsOpenOnThisPhone() = runTest {
        val c = controller()
        runCurrent()
        vault.gateway.incoming = true
        fun offer() = CallEvent.Offer(VaultMessage(Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T63", type = "call.offer", ts = T0, body = """{"connection_id":"conn-1"}""".toByteArray())))
        vault.gateway.channel.send(offer())
        advanceTimeBy(3_000)
        assertEquals("not yet: the unlock screen is still in front", CallState.Idle, c.state.value)
        assertTrue(!audio.ringtone)
        vault.unlocked.value = true
        runCurrent()
        assertEquals(CallPhase.INCOMING, (c.state.value as CallState.Live).phase)
        c.decline()
        runCurrent()
        c.dismiss()
        vault.unlocked.value = false
        runCurrent()

        // No unlock within the wait: dropped and counted, and the events behind it are not held for ever.
        vault.gateway.channel.send(offer())
        advanceTimeBy(10_001)
        assertEquals(CallState.Idle, c.state.value)
        assertEquals(1, c.diagnostics.value.refused)
    }

    /** Review D3: a probe that `recheck` starts does not run beside the one the unlock started. */
    @Test
    fun oneProbeAtATime() = runTest {
        val gate = Channel<Unit>(Channel.UNLIMITED)
        var running = 0
        var most = 0
        vault.gateway.listGate = {
            running++
            most = maxOf(most, running)
            gate.receive()
            running--
        }
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        vault.recheck.emit(Unit)
        vault.recheck.emit(Unit)
        runCurrent()
        assertEquals(1, most)
        gate.send(Unit)
        runCurrent()
        assertEquals(CallAvailability.READY, c.availability.value)
        // The rechecks that waited find the answer there and do not ask again.
        assertEquals(1, vault.gateway.sent.count { it.startsWith("list") })
    }

    /** Review N5: removing the gate on Answer used to fail no test. */
    @Test
    fun noAnswerWithoutTheMicrophone() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        var asked = 0
        backgroundScope.launch { c.microphoneNeeded.collect { asked++ } }
        vault.gateway.incoming = true
        val offer = Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T62", type = "call.offer", ts = T0, body = """{"connection_id":"conn-1"}""".toByteArray())
        vault.gateway.channel.send(CallEvent.Offer(VaultMessage(offer)))
        runCurrent()
        assertEquals(CallPhase.INCOMING, (c.state.value as CallState.Live).phase)
        assertTrue("the phone rings", audio.ringtone)
        microphone = false
        c.answer()
        runCurrent()
        assertEquals(1, asked)
        assertEquals("still ringing: nothing was answered", CallPhase.INCOMING, (c.state.value as CallState.Live).phase)
        assertTrue(engines.created.isEmpty())
        microphone = true
        c.answer()
        runCurrent()
        assertEquals(CallPhase.CONNECTING, (c.state.value as CallState.Live).phase)
    }

    /** Review N5: the sweep must never end the call that is going on. */
    @Test
    fun theSweepLeavesALiveCallAlone() = runTest {
        var asks = 0
        vault.gateway.list = {
            // The first unlock finds nothing; the vault is asked again later, during a call it holds as active.
            if (asks++ == 0) emptyList() else listOf(record(CALL_ID, "active", "out"))
        }
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        runCurrent()
        assertTrue(c.state.value is CallState.Live)
        // Asked again (the daily check was done, say) while the call is live.
        c.forgetProbeForTest()
        vault.recheck.emit(Unit)
        runCurrent()
        assertEquals(2, asks)
        assertTrue("the live call was not swept", vault.gateway.sent.none { it.startsWith("end(") })
        assertTrue(c.state.value is CallState.Live)
    }

    /** Review N5: a refusal that is not "no calls" is asked about again when something changes. */
    @Test
    fun aRefusedProbeIsRepeatedWhenTheVaultMayAnswer() = runTest {
        vault.gateway.list = { throw VaultOpException("call.list", "owner_check_required") }
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        assertEquals(CallAvailability.UNKNOWN, c.availability.value)
        vault.gateway.list = { emptyList() }
        vault.recheck.emit(Unit)
        runCurrent()
        assertEquals(CallAvailability.READY, c.availability.value)
        // Once known, it is not asked again for every change.
        val asked = vault.gateway.sent.count { it.startsWith("list") }
        vault.recheck.emit(Unit)
        runCurrent()
        assertEquals(asked, vault.gateway.sent.count { it.startsWith("list") })
    }

    /** Review N5: the lock waits for the hang-up to be with the relay; a locked vault would not collect it. */
    @Test
    fun theCallIsEndedAndItsHangUpDeliveredBeforeTheVaultLocks() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.endCallBeforeLock() // nothing live: returns at once
        c.call("conn-1")
        runCurrent()
        val gate = Channel<Unit>(1)
        vault.gateway.endGate = gate
        var done = false
        backgroundScope.launch {
            c.endCallBeforeLock()
            done = true
        }
        runCurrent()
        assertEquals(CallEnd.LOCKED, (c.state.value as CallState.Ended).reason)
        assertTrue("the lock still waits: the hang-up is not with the relay yet", !done)
        gate.send(Unit)
        runCurrent()
        assertTrue(done)
        assertEquals("end($CALL_ID, hangup)", vault.gateway.sent.last())
    }

    /** Review L8 of #127: hung up, then locked at once. The call is over here, its hang-up is not with the relay yet. */
    @Test
    fun aHangUpStillOnItsWayIsWaitedForBeforeTheVaultLocks() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        runCurrent()
        val gate = Channel<Unit>(1)
        vault.gateway.endGate = gate
        c.hangUp()
        runCurrent()
        assertTrue("the call is over on this phone", c.state.value !is CallState.Live)
        var done = false
        backgroundScope.launch {
            c.endCallBeforeLock()
            done = true
        }
        runCurrent()
        assertTrue("the lock waits for the hang-up that is still being sent", !done)
        gate.send(Unit)
        runCurrent()
        assertTrue(done)
        assertEquals("end($CALL_ID, hangup)", vault.gateway.sent.last())
    }

    @Test
    fun aWrongClockShowsInTheDiagnostics() = runTest {
        val c = controller()
        runCurrent()
        vault.droppedForTime.emit("call.offer")
        vault.droppedForTime.emit("message.new")
        runCurrent()
        assertEquals(1, c.diagnostics.value.droppedForTime)
    }

    @Test
    fun theVaultsCallListAsTheConversationShowsIt() {
        val now = T0.plusSeconds(3600)
        fun outcome(r: CallRecord) = CallsController.entry(r, now)!!
        val talked = outcome(record("A", "ended", "out", reason = "hangup", answered = T0.plusSeconds(5), ended = T0.plusSeconds(257)))
        assertEquals(CallOutcome.TALKED, talked.outcome)
        assertEquals(Duration.ofSeconds(252), talked.duration)
        assertTrue(talked.outgoing)
        val expected = listOf(
            record("B", "ended", "out", reason = "decline") to CallOutcome.DECLINED,
            record("C", "ended", "out", reason = "busy") to CallOutcome.BUSY,
            record("D", "ended", "out", reason = "timeout") to CallOutcome.NOT_ANSWERED,
            record("E", "ended", "out", reason = "failed") to CallOutcome.FAILED,
            record("F", "ended", "in", reason = "timeout") to CallOutcome.MISSED,
            record("G", "ended", "in", reason = "busy") to CallOutcome.MISSED,
            record("H", "ended", "in", reason = "decline") to CallOutcome.DECLINED_HERE,
            record("I", "ended", "in", reason = "answered_elsewhere") to CallOutcome.ANSWERED_ELSEWHERE,
            // The vault of S10 never times a ringing call out by itself: an old one is not ringing any more.
            record("J", "ringing", "in") to CallOutcome.MISSED,
            record("K", "ringing", "out") to CallOutcome.NOT_ANSWERED,
            record("L", "ringing", "in", now.minusSeconds(5)) to CallOutcome.ONGOING,
            record("M", "active", "in", answered = T0.plusSeconds(3)) to CallOutcome.ONGOING,
        )
        for ((r, want) in expected) assertEquals(r.callId, want, outcome(r).outcome)
        assertNull(CallsController.entry(record("N", "ended").copy(startedAt = "yesterday"), now))
    }
}
