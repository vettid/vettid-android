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

        override suspend fun start(connectionId: String, sdp: String, suites: Collection<MediaSuite>): OutgoingCall {
            sent += "start($connectionId)"
            val ice = CallWire.IceConfig(CALL_ID, T0.plusSeconds(600), emptyList())
            return OutgoingCall(CALL_ID, connectionId, "audio", T0.plusSeconds(45), ice, listOf(1), KemPrivateKey.generate())
        }

        override suspend fun incoming(event: VaultMessage, pinnedFingerprint: String, suites: Collection<MediaSuite>): IncomingCall = error("unused")

        override suspend fun answer(call: IncomingCall, sdp: String): CallKeys = error("unused")

        override suspend fun accept(call: OutgoingCall, event: VaultMessage, pinnedFingerprint: String): AcceptedCall =
            AcceptedCall(call.callId, "v=0", CallKeys(MediaSuite.AES_128_GCM, ByteArray(32) { 5 }, ByteArray(32) { 6 }))

        override suspend fun ringing(callId: String) = Unit

        override suspend fun ice(callId: String, candidates: List<CallCandidate>) = Unit

        override suspend fun end(callId: String, reason: String) {
            sent += "end($callId, $reason)"
        }

        override suspend fun list(limit: Int): List<CallRecord> {
            sent += "list($limit)"
            return list()
        }
    }

    private class Vault : CallVault {
        override val unlocked = MutableStateFlow(false)
        override val gateway = Gateway()
        override val droppedForTime = MutableSharedFlow<String>(extraBufferCapacity = 8)

        override suspend fun fingerprint(connectionId: String): String? = "aaaa bbbb"

        override fun displayName(connectionId: String): String? = "Ada Lovelace"
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

    private fun TestScope.controller(config: MediaConfig = MediaConfig(), debuggable: Boolean = false) = CallsController(
        vault, engines, backgroundScope, audio, audio, { microphone }, { services++ }, config, debuggable,
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
        assertEquals(CallEnd.LOCKED, (c.state.value as CallState.Ended).reason)
        assertEquals("end($CALL_ID, hangup)", vault.gateway.sent.last())
        assertTrue(!audio.on && !audio.ringBack)
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
        assertThrows(IllegalStateException::class.java) { controller(MediaConfig(dtlsPqcTrial = true), debuggable = false) }
        controller(MediaConfig(dtlsPqcTrial = true), debuggable = true)
        controller(MediaConfig(), debuggable = false)
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
