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
import com.vettid.core.vault.IncomingCall
import com.vettid.core.vault.MadeByCallApi
import com.vettid.core.vault.OutgoingCall
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        var acceptGate: Channel<Unit>? = null

        override suspend fun start(connectionId: String, sdp: String, suites: Collection<MediaSuite>, peerIk: ByteArray?): OutgoingCall {
            asked += "start($connectionId, ${suites.map { it.id }})" + (peerIk?.let { " form2 ${it.size}" } ?: "")
            startGate?.receive()
            return start()
        }

        override suspend fun incoming(event: VaultMessage, pinnedFingerprint: String, suites: Collection<MediaSuite>): IncomingCall {
            asked += "incoming($pinnedFingerprint, ${suites.map { it.id }})"
            return incoming(event)
        }

        override suspend fun answer(call: IncomingCall, sdp: String, keepalive: Boolean): CallKeys {
            asked += "answer(${call.callId})" + (if (keepalive) " keepalive" else "")
            return answerKeys!!
        }

        override suspend fun accept(call: OutgoingCall, event: VaultMessage, pinnedFingerprint: String): AcceptedCall {
            asked += "accept(${call.callId}, $pinnedFingerprint)"
            acceptGate?.receive()
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

        override suspend fun list(limit: Int): com.vettid.core.vault.CallList = com.vettid.core.vault.CallList(emptyList(), emptyList())
    }

    private val gateway = Gateway()
    private val identities = mutableMapOf("conn-1" to "aaaa bbbb", "conn-2" to "cccc dddd")
    private val out = mutableListOf<SignalEvent>()

    private var refreshes = 0
    private var onRefresh: () -> Unit = {}

    private fun TestScope.signalling(
        suites: List<MediaSuite> = listOf(s1),
        c2: Boolean = false,
        keys: Map<String, ByteArray> = emptyMap(),
    ): VaultCallSignalling {
        val s = VaultCallSignalling(
            gateway,
            { identities[it] },
            suites,
            refreshConnections = {
                refreshes++
                onRefresh()
            },
            peerKey = { keys[it] },
            vaultIsC2 = { c2 },
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

    private fun incomingCall(
        connectionId: String = "conn-1",
        chosen: MediaSuite = s1,
        offered: List<Int> = listOf(1),
        parties: CallWire.Parties? = null,
    ) = IncomingCall(
        CALL_ID, connectionId, "audio", "v=0\r\n", T0.plusSeconds(45), Duration.ofSeconds(45),
        CallWire.IceConfig(CALL_ID, T0.plusSeconds(600), emptyList()), CallMedia.Negotiated(chosen, offered), ByteArray(1216), parties,
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

    /**
     * (C2) A device that knows its vault to be C2 starts in form 2, naming the connection's key, and answers with
     * `keepalive`; with no key for the connection it places no call at all (form 1 alone would leave a relay unseen).
     * Against an S10 vault: form 1, no keep-alive.
     */
    @Test
    fun aC2VaultGetsFormTwoAndKeepAlives() = runTest {
        val s = signalling(c2 = true, keys = mapOf("conn-1" to ByteArray(32) { 7 }))
        gateway.start = { outgoingCall() }
        s.start("conn-1", "v=0", listOf(s1))
        assertTrue(gateway.asked.toString(), gateway.asked.contains("start(conn-1, [1]) form2 32"))
        assertThrows(CallSecurityException::class.java) { kotlinx.coroutines.runBlocking { s.start("conn-2", "v=0", listOf(s1)) } }
        assertFalse(gateway.asked.any { it.startsWith("start(conn-2") })
        gateway.incoming = { incomingCall() }
        gateway.channel.send(offerEvent())
        runCurrent()
        gateway.answerKeys = keys()
        s.answer(CALL_ID, "v=0", listOf(s1))
        assertTrue(gateway.asked.contains("answer($CALL_ID) keepalive"))

        val g1 = Gateway().also { it.start = { outgoingCall() } }
        val s10 = VaultCallSignalling(g1, { identities[it] }, listOf(s1), peerKey = { ByteArray(32) { 7 } }, vaultIsC2 = { false })
        s10.start("conn-1", "v=0", listOf(s1))
        assertEquals(listOf("start(conn-1, [1])"), g1.asked)
    }

    /** A gateway whose starts run [onStart] (with the key each names), the rest as [gateway]'s. */
    private fun startingWith(onStart: (ByteArray?) -> OutgoingCall): CallGateway = object : CallGateway by gateway {
        override suspend fun start(connectionId: String, sdp: String, suites: Collection<MediaSuite>, peerIk: ByteArray?): OutgoingCall =
            onStart(peerIk)

        override suspend fun start(
            connectionId: String,
            sdp: String,
            suites: Collection<MediaSuite>,
            peerIk: ByteArray?,
            onCallId: (String) -> Unit,
        ): OutgoingCall = onStart(peerIk)
    }

    private fun refused(code: String): Nothing = throw VaultOpException("call.start", code)

    /**
     * (C2) A form 2 start answered `bad_request` is placed once more after both keys are refreshed (an identity
     * rotation mid-start, VAULT-MESSAGING §10.10): the connections read again, the connection's key taken again. Only
     * once per tap; never for form 1 or another refusal.
     */
    @Test
    fun aFormTwoStartRefusedBadRequestIsPlacedOnceMoreWithFreshKeys() = runTest {
        var key = ByteArray(32) { 7 }
        val keys = mutableListOf<Int>()
        val g = startingWith { ik ->
            keys += ik!![0].toInt()
            if (keys.size == 1) refused("bad_request") else outgoingCall()
        }
        val refresh = { refreshes++; key = ByteArray(32) { 8 } }
        VaultCallSignalling(g, { identities[it] }, listOf(s1), refreshConnections = refresh, peerKey = { key }, vaultIsC2 = { true })
            .start("conn-1", "v=0", listOf(s1))
        assertEquals("placed again, with the refreshed key", listOf(7, 8), keys)
        assertEquals(1, refreshes)

        // A second refusal ends it as the first would have: no third start.
        var n = 0
        val always = startingWith { n++; refused("bad_request") }
        val twice = VaultCallSignalling(always, { identities[it] }, listOf(s1), peerKey = { key }, vaultIsC2 = { true })
        val e = runCatching { twice.start("conn-1", "v=0", listOf(s1)) }.exceptionOrNull()
        assertEquals("bad_request", (e as? CallRefusedException)?.code)
        assertEquals(2, n)

        // Form 1, or another refusal: no retry.
        for ((c2, code) in listOf(false to "bad_request", true to "busy", true to "calls_off")) {
            n = 0
            val once = startingWith { n++; refused(code) }
            val v = VaultCallSignalling(once, { identities[it] }, listOf(s1), peerKey = { key }, vaultIsC2 = { c2 })
            val x = runCatching { v.start("conn-1", "v=0", listOf(s1)) }.exceptionOrNull()
            assertEquals("$c2 $code", code, (x as? CallRefusedException)?.code)
            assertEquals("$c2 $code", 1, n)
        }
    }

    /**
     * The retry places nothing for a call no longer wanted (hung up or the vault closing), before or after the
     * refresh; it waits for the refresh only so long; it is in form 2 again; and the second refusal is the one said.
     */
    @Test
    fun theRetryIsOnlyForAWantedCallInFormTwo() = runTest {
        val key = ByteArray(32) { 7 }
        var n = 0
        val badThenOk = startingWith { n++; if (n == 1) refused("bad_request") else outgoingCall() }

        // Not wanted when the refusal comes: no refresh, no second start.
        val e1 = runCatching {
            VaultCallSignalling(
                badThenOk, { identities[it] }, listOf(s1), refreshConnections = { refreshes++ }, peerKey = { key }, vaultIsC2 = { true },
            ).start("conn-1", "v=0", listOf(s1)) { false }
        }.exceptionOrNull()
        assertEquals("bad_request", (e1 as? CallRefusedException)?.code)
        assertEquals(1, n)
        assertEquals(0, refreshes)

        // Hung up during the refresh: no second start.
        n = 0
        var wanted = true
        val e2 = runCatching {
            VaultCallSignalling(
                badThenOk, { identities[it] }, listOf(s1), refreshConnections = { wanted = false }, peerKey = { key }, vaultIsC2 = { true },
            ).start("conn-1", "v=0", listOf(s1)) { wanted }
        }.exceptionOrNull()
        assertEquals("bad_request", (e2 as? CallRefusedException)?.code)
        assertEquals(1, n)

        // A refresh that never answers is given 3 s, then the call is placed again.
        n = 0
        val t0 = testScheduler.currentTime
        VaultCallSignalling(
            badThenOk, { identities[it] }, listOf(s1), refreshConnections = { awaitCancellation() }, peerKey = { key },
            vaultIsC2 = { true },
        ).start("conn-1", "v=0", listOf(s1))
        assertEquals(2, n)
        assertEquals(3_000L, testScheduler.currentTime - t0)

        // The key gone after the refresh (or the vault now read as older): never a form 1 retry.
        n = 0
        var k: ByteArray? = key
        val sent = mutableListOf<Boolean>()
        val recording = startingWith { ik ->
            sent += ik != null
            n++
            if (n == 1) refused("bad_request") else outgoingCall()
        }
        val e3 = runCatching {
            VaultCallSignalling(
                recording, { identities[it] }, listOf(s1), refreshConnections = { k = null }, peerKey = { k }, vaultIsC2 = { k != null },
            ).start("conn-1", "v=0", listOf(s1))
        }.exceptionOrNull()
        assertTrue(e3.toString(), e3 is CallSecurityException)
        assertEquals(listOf(true), sent)

        // The second refusal is the one the member is told.
        n = 0
        val badThenBusy = startingWith { n++; if (n == 1) refused("bad_request") else refused("busy") }
        val e4 = runCatching {
            VaultCallSignalling(badThenBusy, { identities[it] }, listOf(s1), peerKey = { key }, vaultIsC2 = { true })
                .start("conn-1", "v=0", listOf(s1))
        }.exceptionOrNull()
        assertEquals("busy", (e4 as? CallRefusedException)?.code)
    }

    /** (C2) Unless told the vault is older, a start is in form 2: without the connection's key no call is placed. */
    @Test
    fun byDefaultAStartIsInFormTwoAndNeedsTheConnectionsKey() = runTest {
        val g = Gateway().also { it.start = { outgoingCall() } }
        val s = VaultCallSignalling(g, { identities[it] }, listOf(s1))
        assertTrue(s.formTwo)
        assertThrows(CallSecurityException::class.java) { kotlinx.coroutines.runBlocking { s.start("conn-1", "v=0", listOf(s1)) } }
        assertTrue("nothing was sent", g.asked.isEmpty())
    }

    /** (C2) A form 2 offer is answered with `keepalive` even where the `call.list` read found the vault older. */
    @Test
    fun aFormTwoOfferIsAnsweredWithKeepAlivesWhateverTheListReadSays() = runTest {
        val s = signalling(c2 = false)
        gateway.incoming = { incomingCall(parties = CallWire.Parties(ByteArray(32) { 1 }, ByteArray(32) { 2 })) }
        gateway.channel.send(offerEvent())
        runCurrent()
        gateway.answerKeys = keys()
        s.answer(CALL_ID, "v=0", listOf(s1))
        assertTrue(gateway.asked.toString(), gateway.asked.contains("answer($CALL_ID) keepalive"))
    }

    /**
     * Two answers for one call, one checked by the start that was overtaken and one from the events, at the same
     * time: only one is checked; the other finds no call and cannot end the one accepted.
     */
    @Test
    fun aDuplicateAnswerCannotEndAnAcceptedCall() = runTest {
        val s = signalling()
        gateway.startGate = Channel(1)
        gateway.acceptGate = Channel(1)
        gateway.start = { outgoingCall() }
        gateway.accept = { AcceptedCall(CALL_ID, "v=0 answer", keys()) }
        backgroundScope.launch { s.start("conn-1", "v=0", listOf(s1)) }
        runCurrent()
        gateway.channel.send(answerEvent())
        runCurrent()
        gateway.startGate!!.send(Unit)
        runCurrent()
        // The held answer is being checked (accept waits); the duplicate arrives now.
        assertEquals(1, gateway.asked.count { it.startsWith("accept") })
        gateway.channel.send(answerEvent())
        runCurrent()
        gateway.acceptGate!!.send(Unit)
        runCurrent()
        assertEquals(1, gateway.asked.count { it.startsWith("accept") })
        assertTrue(out.toString(), out.none { it is SignalEvent.AnswerRejected })
        assertEquals("v=0 answer", (out.single() as SignalEvent.Answer).sdp)
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
        assertEquals(listOf("Offer($CALL_ID)", "End(callId=$CALL_ID, reason=hangup, supersededBy=null)"), out.map { it.toString() })

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

    /**
     * The vault's `call.end` (`busy`) overtakes its answer to `call.start`: the call it then returns is not kept, and its
     * ephemeral key is destroyed at once (rule 3). An end for another call while the start is out changes nothing.
     */
    @Test
    fun anEndThatOvertakesTheAnswerToCallStartLeavesNoKeyBehind() = runTest {
        val s = signalling()
        for (endedId in listOf(CALL_ID, "01JB2Z6V9K3M4N5P6Q7R8S9T51")) {
            out.clear()
            gateway.asked.clear()
            val key = KemPrivateKey.generate()
            gateway.startGate = Channel(1)
            gateway.start = {
                OutgoingCall(
                    CALL_ID, "conn-1", "audio", T0.plusSeconds(45), CallWire.IceConfig(CALL_ID, T0.plusSeconds(600), emptyList()),
                    listOf(1), key,
                )
            }
            val starting = backgroundScope.async { s.start("conn-1", "v=0", listOf(s1)) }
            runCurrent()
            gateway.channel.send(CallEvent.Ended(endedId, "busy"))
            runCurrent()
            gateway.startGate!!.send(Unit)
            runCurrent()
            assertEquals("the start returned the call", CALL_ID, starting.getCompleted().callId)
            val overtaken = endedId == CALL_ID
            assertEquals("ended $endedId", overtaken, runCatching { key.seed() }.isFailure)
            // Kept or not: an answer for it is checked only while the call is kept.
            gateway.accept = { AcceptedCall(CALL_ID, "v=0", keys()) }
            gateway.channel.send(answerEvent())
            runCurrent()
            assertEquals("ended $endedId", !overtaken, gateway.asked.any { it.startsWith("accept") })
            if (!overtaken) assertTrue("an answered call's key goes too", runCatching { key.seed() }.isFailure)
        }
    }

    private fun placedAs(id: String, key: KemPrivateKey = KemPrivateKey.generate()) = OutgoingCall(
        id, "conn-1", "audio", T0.plusSeconds(45), CallWire.IceConfig(id, T0.plusSeconds(600), emptyList()), listOf(1), key,
    )

    /**
     * Two starts out; the first call's end and then its answer arrive before either returns. When the first returns, its
     * call is not kept and its answer is not looked at again (it stays held once, not twice, while the second is out).
     */
    @Test
    fun aHeldAnswerForACallThatEndedFirstIsNotHeldAgainForAnotherStart() = runTest {
        val s = signalling()
        val first = "01JB2Z6V9K3M4N5P6Q7R8S9T61"
        val second = "01JB2Z6V9K3M4N5P6Q7R8S9T62"
        val calls = ArrayDeque(listOf(placedAs(first), placedAs(second)))
        gateway.startGate = Channel(Channel.UNLIMITED)
        gateway.start = { calls.removeFirst() }
        gateway.accept = { AcceptedCall(it.callId, "v=0", keys()) }
        val a = backgroundScope.async { s.start("conn-1", "v=0", listOf(s1)) }
        val b = backgroundScope.async { s.start("conn-1", "v=0", listOf(s1)) }
        runCurrent()
        gateway.channel.send(CallEvent.Ended(first, "busy"))
        gateway.channel.send(answerEvent(first))
        runCurrent()
        assertEquals(1 to 1, s.heldCounts())
        gateway.startGate!!.send(Unit)
        runCurrent()
        assertEquals(first, a.getCompleted().callId)
        assertEquals("held once, not again (the ended id is used up)", 1 to 0, s.heldCounts())
        gateway.startGate!!.send(Unit)
        runCurrent()
        assertEquals(second, b.getCompleted().callId)
        assertEquals(0 to 0, s.heldCounts())
        assertTrue(gateway.asked.none { it.startsWith("accept") })
    }

    /**
     * What is remembered of ended calls: only while a start is out, only for calls not known here, at most `MAX_KEPT` (4),
     * and nothing once the last start has returned.
     */
    @Test
    fun endedCallsAreRememberedOnlyWhileAStartIsOutAndOnlyAFew() = runTest {
        val s = signalling()
        // No start out: an end for an unknown call is not remembered.
        gateway.channel.send(CallEvent.Ended("01JB2Z6V9K3M4N5P6Q7R8S9T70", "busy"))
        runCurrent()
        assertEquals(0 to 0, s.heldCounts())
        // A call that is known (placed) and ends while another start is out: forgotten, not remembered.
        val known = "01JB2Z6V9K3M4N5P6Q7R8S9T71"
        val knownKey = KemPrivateKey.generate()
        val calls = ArrayDeque(listOf(placedAs(known, knownKey), placedAs(CALL_ID)))
        gateway.start = { calls.removeFirst() }
        s.start("conn-1", "v=0", listOf(s1))
        gateway.startGate = Channel(Channel.UNLIMITED)
        val out2 = backgroundScope.async { s.start("conn-1", "v=0", listOf(s1)) }
        runCurrent()
        gateway.channel.send(CallEvent.Ended(known, "hangup"))
        runCurrent()
        assertEquals(0 to 0, s.heldCounts())
        assertTrue("the known call's key went", runCatching { knownKey.seed() }.isFailure)
        // Many unknown ends during one start: at most 4 kept.
        for (i in 0 until 6) gateway.channel.send(CallEvent.Ended("01JB2Z6V9K3M4N5P6Q7R8S9T8$i", "busy"))
        runCurrent()
        assertEquals(0 to 4, s.heldCounts())
        gateway.startGate!!.send(Unit)
        runCurrent()
        assertEquals(CALL_ID, out2.getCompleted().callId)
        assertEquals("emptied after the last start", 0 to 0, s.heldCounts())
    }

    /**
     * The bound's fallback: with more than 4 other unknown ends after the call's own during its start, its end is let go
     * and the call is placed after all. The machine holds that end and lets the call go on it: [VaultCallSignalling.drop]
     * destroys the key (a second end from the vault would too).
     */
    @Test
    fun anOverflowedEarlyEndFallsBackToTheMachineDroppingTheCall() = runTest {
        val s = signalling()
        val key = KemPrivateKey.generate()
        gateway.startGate = Channel(1)
        gateway.start = { placedAs(CALL_ID, key) }
        val starting = backgroundScope.async { s.start("conn-1", "v=0", listOf(s1)) }
        runCurrent()
        gateway.channel.send(CallEvent.Ended(CALL_ID, "busy"))
        for (i in 0 until 4) gateway.channel.send(CallEvent.Ended("01JB2Z6V9K3M4N5P6Q7R8S9T9$i", "busy"))
        runCurrent()
        gateway.startGate!!.send(Unit)
        runCurrent()
        assertEquals(CALL_ID, starting.getCompleted().callId)
        assertTrue("placed: the list lost its end", runCatching { key.seed() }.isSuccess)
        s.drop(CALL_ID)
        assertTrue("the machine let it go: the key with it", runCatching { key.seed() }.isFailure)
        s.drop(CALL_ID) // a second time: nothing left, nothing thrown
    }

    /**
     * Only the vault's ends can overtake a start: this device's own `call.end` and the machine's [VaultCallSignalling.drop]
     * name calls it knew, and are not remembered (they would push a real early end out of the list).
     */
    @Test
    fun ownEndsAndDropsAreNotRememberedAsEarlyEnds() = runTest {
        val s = signalling()
        gateway.startGate = Channel(1)
        gateway.start = { placedAs(CALL_ID, KemPrivateKey.generate()) }
        val starting = backgroundScope.async { s.start("conn-1", "v=0", listOf(s1)) }
        runCurrent()
        s.end("01JB2Z6V9K3M4N5P6Q7R8S9T90", "hangup")
        s.drop("01JB2Z6V9K3M4N5P6Q7R8S9T91")
        assertEquals(0 to 0, s.heldCounts())
        gateway.channel.send(CallEvent.Ended("01JB2Z6V9K3M4N5P6Q7R8S9T92", "busy"))
        runCurrent()
        assertEquals("the vault's end is", 0 to 1, s.heldCounts())
        gateway.startGate!!.send(Unit)
        runCurrent()
        assertEquals(CALL_ID, starting.getCompleted().callId)
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
