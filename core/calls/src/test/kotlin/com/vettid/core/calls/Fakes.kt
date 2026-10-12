package com.vettid.core.calls

import com.vettid.core.crypto.call.CallMedia
import com.vettid.core.crypto.call.CallWire
import com.vettid.core.crypto.call.MediaSuite
import com.vettid.core.vault.CallCandidate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.time.Instant

const val CALL_ID = "01JB2Z6V9K3M4N5P6Q7R8S9T50"
val T0: Instant = Instant.parse("2026-10-12T10:00:00Z")

fun candidate(n: Int) = CallCandidate("candidate:$n 1 udp 2122260223 192.0.2.$n 50000 typ host", "0", 0)

/** A media engine that records what it was asked, in order, and says what the test tells it to. */
class FakeEngine(override val suites: List<MediaSuite> = listOf(MediaSuite.AES_128_GCM)) : MediaEngine {
    val calls = mutableListOf<String>()
    val channel = Channel<MediaEvent>(Channel.UNLIMITED)
    override val events: Flow<MediaEvent> = channel.receiveAsFlow()
    var closed = false
    var suite: MediaSuite? = null
    var sendKey: ByteArray? = null
    var receiveKey: ByteArray? = null
    val remote = mutableListOf<CallCandidate>()
    var micOff = false
    var onKeys: () -> Unit = {}

    fun emit(e: MediaEvent) {
        channel.trySend(e)
    }

    override suspend fun createOffer(): String = "v=0\r\ns=offer\r\nm=audio 9 X 111\r\n".also { calls += "createOffer" }

    override suspend fun startGathering(servers: List<CallWire.IceServer>) {
        calls += "startGathering(${servers.size})"
    }

    override suspend fun acceptAnswer(sdp: String) {
        calls += "acceptAnswer"
    }

    override suspend fun acceptOffer(sdp: String, servers: List<CallWire.IceServer>): String {
        calls += "acceptOffer"
        return "v=0\r\ns=answer\r\nm=audio 9 X 111\r\n"
    }

    override suspend fun setKeys(suite: MediaSuite, send: ByteArray, receive: ByteArray) {
        calls += "setKeys"
        this.suite = suite
        sendKey = send.copyOf()
        receiveKey = receive.copyOf()
        send.fill(0)
        receive.fill(0)
        onKeys()
    }

    override suspend fun addRemoteCandidates(candidates: List<CallCandidate>) {
        calls += "addRemoteCandidates(${candidates.size})"
        remote += candidates
    }

    override fun setMuted(muted: Boolean) {
        micOff = muted
    }

    override suspend fun diagnostics(): MediaDiagnostics? = MediaDiagnostics(packetsSent = 1)

    override fun close() {
        closed = true
        calls += "close"
    }
}

/** Engines in the order the machine asked for them. */
class FakeEngines(private val make: () -> MediaEngine = { FakeEngine() }) : MediaEngineFactory {
    val created = mutableListOf<MediaEngine>()
    var config: MediaConfig? = null

    override fun create(config: MediaConfig): MediaEngine {
        this.config = config
        return make().also { created += it }
    }

    val last: FakeEngine get() = created.last() as FakeEngine
}

/** Signalling that records what was sent and delivers what the test gives it. */
open class FakeSignalling : CallSignalling {
    val channel = Channel<SignalEvent>(Channel.UNLIMITED)
    override val events: Flow<SignalEvent> = channel.receiveAsFlow()
    val sent = mutableListOf<String>()
    val ice = mutableListOf<List<CallCandidate>>()
    var startResult: () -> PlacedCall = { PlacedCall(CALL_ID, T0.plusSeconds(45), emptyList()) }
    var startGate: Channel<Unit>? = null
    var answerResult: () -> MediaKeys = { MediaKeys(MediaSuite.AES_128_GCM, ByteArray(32) { 1 }, ByteArray(32) { 2 }) }

    fun deliver(e: SignalEvent) {
        channel.trySend(e)
    }

    override suspend fun start(connectionId: String, sdp: String, suites: List<MediaSuite>): PlacedCall {
        sent += "start($connectionId, ${suites.map { it.id }})"
        startGate?.receive()
        return startResult()
    }

    override suspend fun answer(callId: String, sdp: String, suites: List<MediaSuite>): MediaKeys {
        sent += "answer($callId)"
        return answerResult()
    }

    override suspend fun ringing(callId: String) {
        sent += "ringing($callId)"
    }

    override suspend fun ice(callId: String, candidates: List<CallCandidate>) {
        sent += "ice(${candidates.size})"
        ice += candidates
    }

    override suspend fun end(callId: String, reason: String) {
        sent += "end($callId, $reason)"
    }
}

/**
 * Two devices' signalling joined as two vaults would join them, with the real negotiation and key derivation of
 * `:core:crypto` in between, and hooks for someone on the path who changes the SDP.
 */
class Exchange {
    var onOffer: (String) -> String = { it }
    var onAnswer: (String) -> String = { it }
    private val kCall = ByteArray(32) { (it + 3).toByte() }
    private var offered: List<Int> = emptyList()
    private var offerSdp = ""
    val caller = End(true)
    val callee = End(false)

    inner class End(private val isCaller: Boolean) : FakeSignalling() {
        private val peer get() = if (isCaller) callee else caller

        override suspend fun start(connectionId: String, sdp: String, suites: List<MediaSuite>): PlacedCall {
            sent += "start"
            offered = CallMedia.offerOf(suites)
            offerSdp = onOffer(CallMedia.sdpWithOffer(sdp, offered))
            peer.deliver(SignalEvent.Offer(CALL_ID, "peer", CallMedia.sdpWithout(offerSdp), T0.plusSeconds(45), emptyList()))
            return PlacedCall(CALL_ID, T0.plusSeconds(45), emptyList())
        }

        override suspend fun answer(callId: String, sdp: String, suites: List<MediaSuite>): MediaKeys {
            sent += "answer"
            val chosen = try {
                CallMedia.choose(CallMedia.offeredIn(offerSdp), suites)
            } catch (e: com.vettid.core.crypto.CryptoException) {
                throw CallSecurityException("media suites", e)
            }
            val answerSdp = onAnswer(CallMedia.sdpWithChoice(sdp, chosen.chosen))
            // The caller's device checks the answer against the list it sent.
            val atCaller = runCatching { CallMedia.chosenIn(answerSdp, offered) }.getOrNull()
            if (atCaller == null) {
                peer.deliver(SignalEvent.AnswerRejected(callId))
            } else {
                peer.deliver(SignalEvent.Answer(callId, CallMedia.sdpWithout(answerSdp), keys(atCaller, caller = true)))
            }
            return keys(chosen, caller = false)
        }

        override suspend fun end(callId: String, reason: String) {
            super.end(callId, reason)
            peer.deliver(SignalEvent.End(callId, reason))
        }

        override suspend fun ringing(callId: String) {
            super.ringing(callId)
            peer.deliver(SignalEvent.Ringing(callId))
        }
    }

    private fun keys(n: CallMedia.Negotiated, caller: Boolean): MediaKeys {
        val a = CallMedia.directionKey(kCall, n, CallMedia.DIRECTION_CALLER, CALL_ID)
        val b = CallMedia.directionKey(kCall, n, CallMedia.DIRECTION_CALLEE, CALL_ID)
        return if (caller) MediaKeys(n.chosen, a, b) else MediaKeys(n.chosen, b, a)
    }

    /**
     * What the two media stacks would do once both have their keys: with the same suite and matching keys, media
     * flows; otherwise each receiver fails to decrypt.
     */
    fun connect(a: FakeEngine, b: FakeEngine) {
        val ok = a.suite == b.suite && a.sendKey.contentEquals(b.receiveKey) && a.receiveKey.contentEquals(b.sendKey)
        for (e in listOf(a, b)) {
            e.emit(MediaEvent.Connection(MediaConnection.CONNECTED))
            e.emit(MediaEvent.Cryptor(MediaDirection.RECEIVE, if (ok) CryptorState.OK else CryptorState.DECRYPTION_FAILED))
        }
    }
}
