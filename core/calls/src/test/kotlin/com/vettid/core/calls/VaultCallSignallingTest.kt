@file:OptIn(MadeByCallApi::class)

package com.vettid.core.calls

import com.vettid.core.crypto.call.CallMedia
import com.vettid.core.crypto.call.CallWire
import com.vettid.core.crypto.call.MediaSuite
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.vault.AcceptedCall
import com.vettid.core.vault.CallCandidate
import com.vettid.core.vault.CallEvent
import com.vettid.core.vault.CallIce
import com.vettid.core.vault.CallKeys
import com.vettid.core.vault.CallMessageException
import com.vettid.core.vault.CallRecord
import com.vettid.core.vault.IncomingCall
import com.vettid.core.vault.MadeByCallApi
import com.vettid.core.vault.OutgoingCall
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration

/**
 * The adapter is where a call's checks are asked for, so these tests are about what it asks and what it lets
 * through: nothing reaches the state machine that the gateway's checks did not pass, each check is made against
 * the identity of the right connection, and key material does not outlive its use.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultCallSignallingTest {
    private val s1 = MediaSuite.AES_128_GCM

    private class Gateway : CallGateway {
        val channel = Channel<CallEvent>(Channel.UNLIMITED)
        override val events: Flow<CallEvent> = channel.receiveAsFlow()
        val asked = mutableListOf<String>()
        var incoming: (VaultMessage) -> IncomingCall = { throw CallMessageException("not set") }
        var accept: (OutgoingCall) -> AcceptedCall = { throw CallMessageException("not set") }
        var start: () -> OutgoingCall = { throw CallMessageException("not set") }
        var startGate: Channel<Unit>? = null
        var answerKeys: CallKeys? = null

        override suspend fun start(connectionId: String, sdp: String, suites: Collection<MediaSuite>): OutgoingCall {
            asked += "start($connectionId, ${suites.map { it.id }})"
            startGate?.receive()
            return start()
        }

        override suspend fun incoming(event: VaultMessage, pinnedFingerprint: String, suites: Collection<MediaSuite>): IncomingCall {
            asked += "incoming($pinnedFingerprint, ${suites.map { it.id }})"
            return incoming(event)
        }

        override suspend fun answer(call: IncomingCall, sdp: String): CallKeys {
            asked += "answer(${call.callId})"
            return answerKeys!!
        }

        override suspend fun accept(call: OutgoingCall, event: VaultMessage, pinnedFingerprint: String): AcceptedCall {
            asked += "accept(${call.callId}, $pinnedFingerprint)"
            return accept(call)
        }

        override suspend fun ringing(callId: String) {
            asked += "ringing"
        }

        override suspend fun ice(callId: String, candidates: List<CallCandidate>) {
            asked += "ice"
        }

        override suspend fun end(callId: String, reason: String) {
            asked += "end($callId, $reason)"
        }

        override suspend fun list(limit: Int): List<CallRecord> = emptyList()
    }

    private val gateway = Gateway()
    private val identities = mutableMapOf("conn-1" to "aaaa bbbb", "conn-2" to "cccc dddd")
    private val out = mutableListOf<SignalEvent>()

    private var refreshes = 0
    private var onRefresh: () -> Unit = {}

    private fun TestScope.signalling(suites: List<MediaSuite> = listOf(s1)): VaultCallSignalling {
        val s = VaultCallSignalling(
            gateway,
            { identities[it] },
            suites,
            refreshConnections = {
                refreshes++
                onRefresh()
            },
        )
        backgroundScope.launch { s.events.collect { out += it } }
        runCurrent()
        return s
    }

    private fun message(type: String, body: String) =
        VaultMessage(Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T60", type = type, ts = T0, body = body.toByteArray()))

    private fun offerEvent(connectionId: String? = "conn-1"): CallEvent.Offer {
        val body = if (connectionId == null) "{}" else """{"connection_id":"$connectionId","call_id":"$CALL_ID"}"""
        return CallEvent.Offer(message("call.offer", body))
    }

    private fun answerEvent(callId: String = CALL_ID) = CallEvent.Answer(message("call.answer", """{"call_id":"$callId"}"""))

    private fun incomingCall(connectionId: String = "conn-1", chosen: MediaSuite = s1, offered: List<Int> = listOf(1)) = IncomingCall(
        CALL_ID, connectionId, "audio", "v=0\r\n", T0.plusSeconds(45), Duration.ofSeconds(45),
        CallWire.IceConfig(CALL_ID, T0.plusSeconds(600), emptyList()), CallMedia.Negotiated(chosen, offered), ByteArray(1216),
    )

    private fun outgoingCall(connectionId: String = "conn-1") = OutgoingCall(
        CALL_ID, connectionId, "audio", T0.plusSeconds(45), CallWire.IceConfig(CALL_ID, T0.plusSeconds(600), emptyList()),
        listOf(1), KemPrivateKey.generate(),
    )

    private fun keys() = CallKeys(s1, ByteArray(32) { 5 }, ByteArray(32) { 6 })

    @Test
    fun anOfferRingsOnlyAfterItsChecksAgainstItsConnectionsIdentity() = runTest {
        val s = signalling()
        gateway.incoming = { incomingCall() }
        gateway.channel.send(offerEvent())
        runCurrent()
        assertEquals(listOf("incoming(aaaa bbbb, [1])"), gateway.asked)
        val o = out.single() as SignalEvent.Offer
        assertEquals(CALL_ID, o.callId)
        assertEquals("conn-1", o.connectionId)
        assertEquals(Duration.ofSeconds(45), o.ringFor)
        assertTrue("neither SDP nor addresses in a log line", !o.toString().contains("v=0"))
        assertEquals(0, s.refused.value)
    }

    @Test
    fun anOfferThatFailsItsChecksOrNamesNoKnownConnectionNeverReachesTheCall() = runTest {
        val s = signalling()
        // The gateway refuses it (a signature, the identity, the SDP, the suites: any of CallApi's checks).
        gateway.incoming = { throw CallMessageException("key-exchange share") }
        gateway.channel.send(offerEvent())
        // A connection the app does not know: not even asked.
        gateway.channel.send(offerEvent("conn-9"))
        gateway.channel.send(offerEvent(null))
        runCurrent()
        assertTrue(out.isEmpty())
        assertEquals(listOf("incoming(aaaa bbbb, [1])"), gateway.asked)
        assertEquals(3, s.refused.value)
        // And it cannot be answered.
        assertThrows(CallSecurityException::class.java) { kotlinx.coroutines.runBlocking { s.answer(CALL_ID, "v=0", listOf(s1)) } }
        assertTrue(gateway.asked.none { it.startsWith("answer") })
    }

    @Test
    fun answeringHandsTheKeysOverOnceAndDestroysTheOriginals() = runTest {
        val s = signalling()
        gateway.incoming = { incomingCall() }
        gateway.channel.send(offerEvent())
        runCurrent()
        val original = keys()
        gateway.answerKeys = original
        val handed = s.answer(CALL_ID, "v=0", listOf(s1))
        assertTrue(handed.send.all { it == 5.toByte() } && handed.receive.all { it == 6.toByte() })
        assertThrows(IllegalStateException::class.java) { original.send() }
        // A call is answered once.
        assertThrows(CallSecurityException::class.java) { kotlinx.coroutines.runBlocking { s.answer(CALL_ID, "v=0", listOf(s1)) } }
        assertEquals(1, gateway.asked.count { it.startsWith("answer") })
    }

    @Test
    fun anEngineThatCannotDoTheNegotiatedSuiteDoesNotAnswer() = runTest {
        val s = signalling(listOf(MediaSuite.AES_256_GCM, s1))
        gateway.incoming = { incomingCall(chosen = MediaSuite.AES_256_GCM, offered = listOf(2, 1)) }
        gateway.channel.send(offerEvent())
        runCurrent()
        assertEquals("incoming(aaaa bbbb, [2, 1])", gateway.asked.single())
        assertThrows(CallSecurityException::class.java) { kotlinx.coroutines.runBlocking { s.answer(CALL_ID, "v=0", listOf(s1)) } }
        assertTrue(gateway.asked.none { it.startsWith("answer") })
    }

    @Test
    fun anAnswerIsCheckedAgainstTheConnectionTheCallWasPlacedTo() = runTest {
        val s = signalling()
        gateway.start = { outgoingCall("conn-2") }
        val placed = s.start("conn-2", "v=0", listOf(s1))
        assertEquals(CALL_ID, placed.callId)
        val original = keys()
        gateway.accept = { AcceptedCall(CALL_ID, "v=0 answer", original) }
        gateway.channel.send(answerEvent())
        runCurrent()
        assertEquals(listOf("start(conn-2, [1])", "accept($CALL_ID, cccc dddd)"), gateway.asked)
        val a = out.single() as SignalEvent.Answer
        assertEquals("v=0 answer", a.sdp)
        assertTrue(a.keys.send.all { it == 5.toByte() })
        assertThrows(IllegalStateException::class.java) { original.receive() }
        // The call's ephemeral key is gone with its answer: a second answer for it is nobody's.
        gateway.channel.send(answerEvent())
        runCurrent()
        assertEquals(1, out.size)
        assertEquals(1, gateway.asked.count { it.startsWith("accept") })
    }

    @Test
    fun anAnswerThatFailsItsChecksEndsTheCallAndAnAnswerForNoCallIsIgnored() = runTest {
        val s = signalling()
        gateway.channel.send(answerEvent())
        runCurrent()
        assertTrue(out.isEmpty() && gateway.asked.isEmpty())

        gateway.start = { outgoingCall() }
        s.start("conn-1", "v=0", listOf(s1))
        gateway.accept = { throw CallMessageException("key-exchange share") }
        gateway.channel.send(answerEvent())
        runCurrent()
        assertEquals(SignalEvent.AnswerRejected(CALL_ID), out.single())
        assertEquals(1, s.refused.value)
        gateway.channel.send(answerEvent())
        runCurrent()
        assertEquals(1, out.size)

        // The connection was removed while it rang: no identity to check against, so no call.
        gateway.start = { outgoingCall("conn-2") }
        s.start("conn-2", "v=0", listOf(s1))
        identities.remove("conn-2")
        gateway.accept = { AcceptedCall(CALL_ID, "v=0", keys()) }
        gateway.channel.send(answerEvent())
        runCurrent()
        assertEquals(SignalEvent.AnswerRejected(CALL_ID), out.last())
        assertEquals(1, gateway.asked.count { it.startsWith("accept") })
    }

    @Test
    fun theVaultsRefusalsAndUnverifiableAnswersToCallStart() = runTest {
        val s = signalling()
        gateway.start = { throw VaultOpException("call.start", "busy") }
        val refused = runCatching { s.start("conn-1", "v=0", listOf(s1)) }.exceptionOrNull()
        assertEquals("busy", (refused as CallRefusedException).code)
        gateway.start = { throw CallMessageException("ice configuration") }
        assertTrue(runCatching { s.start("conn-1", "v=0", listOf(s1)) }.exceptionOrNull() is CallSecurityException)
    }

    @Test
    fun theOtherEventsPassAndAnEndedCallIsForgotten() = runTest {
        val s = signalling()
        gateway.start = { outgoingCall() }
        s.start("conn-1", "v=0", listOf(s1))
        gateway.channel.send(CallEvent.Ringing(CALL_ID))
        gateway.channel.send(CallEvent.Ice(CallIce(CALL_ID, listOf(candidate(1)))))
        gateway.channel.send(CallEvent.Ended(CALL_ID, "busy"))
        gateway.accept = { AcceptedCall(CALL_ID, "v=0", keys()) }
        gateway.channel.send(answerEvent())
        runCurrent()
        assertEquals(
            listOf(SignalEvent.Ringing(CALL_ID), SignalEvent.Ice(CALL_ID, listOf(candidate(1))), SignalEvent.End(CALL_ID, "busy")),
            out,
        )
        assertTrue(gateway.asked.none { it.startsWith("accept") })
        assertTrue("an address is not printed", !candidate(1).toString().contains("192.0.2"))
        s.end(CALL_ID, "hangup")
        assertEquals("end($CALL_ID, hangup)", gateway.asked.last())
    }

    /**
     * Review N2: one unexpected exception under a check (a vault client that cannot be had around a lock, a relay
     * error, anything) used to end the flow, and with it all call signalling of the process.
     */
    @Test
    fun whateverGoesWrongWithOneEventTheNextIsStillHandled() = runTest {
        val s = signalling()
        gateway.incoming = { throw IllegalStateException("no session with the vault") }
        gateway.channel.send(offerEvent())
        runCurrent()
        assertTrue(out.isEmpty())
        assertEquals(1, s.refused.value)
        assertEquals("counted apart from a failed check", 1, s.errors.value)
        gateway.incoming = { throw java.io.IOException("relay") }
        gateway.channel.send(offerEvent())
        runCurrent()
        gateway.incoming = { incomingCall() }
        gateway.channel.send(offerEvent())
        gateway.channel.send(CallEvent.Ended(CALL_ID, "hangup"))
        runCurrent()
        assertEquals(2, s.errors.value)
        assertEquals(listOf("Offer($CALL_ID)", "End(callId=$CALL_ID, reason=hangup)"), out.map { it.toString() })

        // The same under an answer: the call is over, the listening is not.
        gateway.start = { outgoingCall() }
        s.start("conn-1", "v=0", listOf(s1))
        gateway.accept = { throw IllegalStateException("vault client") }
        gateway.channel.send(answerEvent())
        gateway.channel.send(CallEvent.Ringing(CALL_ID))
        runCurrent()
        assertEquals(listOf(SignalEvent.AnswerRejected(CALL_ID), SignalEvent.Ringing(CALL_ID)), out.takeLast(2))
        assertEquals(3, s.errors.value)
    }

    /**
     * Review N3: the app's connection list is in memory and is read after the vault opens, so the offer that was
     * waiting in the mailbox at unlock can be handled first. It is looked for once more after the list was read.
     */
    @Test
    fun aConnectionNotFoundIsLookedForOnceMoreAfterTheListWasReadAgain() = runTest {
        val s = signalling()
        val known = identities.remove("conn-1")!!
        onRefresh = { identities["conn-1"] = known }
        gateway.incoming = { incomingCall() }
        gateway.channel.send(offerEvent())
        runCurrent()
        assertEquals(1, refreshes)
        assertEquals("incoming(aaaa bbbb, [1])", gateway.asked.single())
        assertEquals(1, out.size)
        // A connection that is not there after it either: refused, and asked about once, not in a loop.
        onRefresh = {}
        gateway.channel.send(offerEvent("conn-9"))
        runCurrent()
        assertEquals(2, refreshes)
        assertEquals(1, out.size)
        assertEquals(1, s.refused.value)
        assertEquals(0, s.errors.value)
        // A known connection costs no reading.
        gateway.channel.send(offerEvent())
        runCurrent()
        assertEquals(2, refreshes)
    }

    /**
     * Review N4: the vault can pass the answer on before its answer to `call.start` has been read here. It used to
     * be dropped as "not a call this device placed".
     */
    @Test
    fun anAnswerThatOvertakesTheAnswerToCallStartIsUsed() = runTest {
        val s = signalling()
        gateway.startGate = Channel(1)
        gateway.start = { outgoingCall() }
        gateway.accept = { AcceptedCall(CALL_ID, "v=0 answer", keys()) }
        val starting = backgroundScope.launch { s.start("conn-1", "v=0", listOf(s1)) }
        runCurrent()
        gateway.channel.send(answerEvent())
        // And one for another call, which is nobody's.
        gateway.channel.send(answerEvent("01JB2Z6V9K3M4N5P6Q7R8S9T51"))
        runCurrent()
        assertTrue(out.isEmpty() && gateway.asked.none { it.startsWith("accept") })
        gateway.startGate!!.send(Unit)
        runCurrent()
        assertTrue(starting.isCompleted)
        assertEquals("v=0 answer", (out.single() as SignalEvent.Answer).sdp)
        assertEquals(1, gateway.asked.count { it.startsWith("accept") })
        // Outside a start, an answer for no call is dropped as before.
        gateway.channel.send(answerEvent("01JB2Z6V9K3M4N5P6Q7R8S9T52"))
        runCurrent()
        assertEquals(1, out.size)
    }

    /** Only `CallApi` may make a verified call object: no production source outside `:core:vault` opts in. */
    @Test
    fun noProductionSourceMakesAVerifiedCallObjectByHand() {
        var root = File("").absoluteFile
        while (!File(root, "settings.gradle.kts").isFile) root = root.parentFile ?: error("repository root not found")
        val offenders = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" && it.name != ".git" && it.name != "local" }
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("MadeByCallApi") }
            .map { it.relativeTo(root).path }
            .filterNot { it.startsWith("core/vault/src/main/") || it.contains("/src/test/") }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
