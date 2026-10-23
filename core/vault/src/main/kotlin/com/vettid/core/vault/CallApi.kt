package com.vettid.core.vault

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.IkFingerprint
import com.vettid.core.crypto.Suite
import com.vettid.core.crypto.call.CallMedia
import com.vettid.core.crypto.call.CallWire
import com.vettid.core.crypto.call.MediaSuite
import com.vettid.core.crypto.envelope.Timestamps
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.crypto.hpke.KemPrivateKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Duration
import java.time.Instant

// Calls (VAULT-MESSAGING §10.10; CALLS-PLAN 0.1.1 phase C1): the seven `call.*` types as an app sends and receives
// them. Signalling goes device → its vault → the peer's vault → the peer's devices. The media key is agreed between
// the two devices (CallWire); this file signs and checks the key-exchange shares, checks the vault-signed ICE
// configuration, negotiates the media suite inside the SDP and derives the two direction keys (CallMedia).
//
// Sending: `call.start`, `call.answer`, `call.ice` and `call.ringing` are deposited once and never kept or re-sent
// (VaultDevice.sendOnce): none of them means anything later. `call.end` is an ordinary durable send, so that a hang-up
// that could not be delivered at once still reaches the vault and frees it.

/** Limits and times of §10.10, as vettid-vault's `features/calls` enforces them. */
object CallLimits {
    const val MAX_SDP = 32 * 1024
    const val MAX_CANDIDATE = 1024
    const val MAX_SDP_MID = 64
    const val MAX_MLINE_INDEX = 1023

    /** Candidates in one `call.ice`. */
    const val MAX_CANDIDATES = 16

    /** `call.ice` messages a vault forwards for one call, both directions counted together. */
    const val MAX_ICE_MESSAGES = 256

    /** A callee's vault drops an offer whose `exp` is further ahead than this. */
    val MAX_OFFER_AHEAD: Duration = Duration.ofSeconds(90)

    /** `exp` of the ephemeral types (`call.ice`, `call.ringing`). */
    val EPHEMERAL_TTL: Duration = Duration.ofSeconds(30)

    /** How long the app waits for the answer to `call.start` before it gives the call up. */
    val START_TIMEOUT: Duration = Duration.ofSeconds(15)
}

/** `call.end` reasons (§10.10). */
object CallReason {
    const val HANGUP = "hangup"
    const val DECLINE = "decline"
    const val BUSY = "busy"
    const val TIMEOUT = "timeout"
    const val ANSWERED_ELSEWHERE = "answered_elsewhere"
    const val UNAVAILABLE = "unavailable"
    const val FAILED = "failed"
    val ALL = setOf(HANGUP, DECLINE, BUSY, TIMEOUT, ANSWERED_ELSEWHERE, UNAVAILABLE, FAILED)
}

/**
 * Marks the constructors of the objects that only this file's functions should make: an [IncomingCall] or an
 * [AcceptedCall] says "every signature was checked", and nothing but [callIncoming] and [callAccept] can say that.
 * The constructors are reachable so that code built on these types can be tested with fakes; a test opts in.
 * No production source outside `:core:vault` may (`VaultCallSignallingTest` in `:core:calls` reads the sources for it).
 */
@RequiresOptIn(message = "Only CallApi makes these: a hand-made one has had nothing verified.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class MadeByCallApi

/** A call message the device cannot use: a share or an ICE configuration that does not verify, or a malformed body. */
class CallMessageException(message: String, cause: Throwable? = null) : java.io.IOException("call: $message", cause)

/**
 * The two frame keys of a call on this device (CallMedia): [send] encrypts the frames this device sends, [receive]
 * decrypts the other device's. They exist only on the two devices of the call. [destroy] wipes them.
 */
class CallKeys @MadeByCallApi constructor(val suite: MediaSuite, private val sendKey: ByteArray, private val receiveKey: ByteArray) {
    @Volatile
    private var live = true

    /** A copy of the key for this device's sender; the caller wipes it after handing it to the media stack. */
    fun send(): ByteArray {
        check(live) { "call keys destroyed" }
        return sendKey.copyOf()
    }

    /** A copy of the key for this device's receiver. */
    fun receive(): ByteArray {
        check(live) { "call keys destroyed" }
        return receiveKey.copyOf()
    }

    fun destroy() {
        live = false
        Bytes.wipe(sendKey, receiveKey)
    }

    override fun toString(): String = "CallKeys(suite=${suite.id}, [redacted])"

    internal companion object {
        /** Derives both direction keys from [kCall] and wipes it. */
        @OptIn(MadeByCallApi::class)
        fun derive(kCall: ByteArray, n: CallMedia.Negotiated, callId: String, caller: Boolean): CallKeys = try {
            val fromCaller = CallMedia.directionKey(kCall, n, CallMedia.DIRECTION_CALLER, callId)
            val fromCallee = CallMedia.directionKey(kCall, n, CallMedia.DIRECTION_CALLEE, callId)
            if (caller) CallKeys(n.chosen, fromCaller, fromCallee) else CallKeys(n.chosen, fromCallee, fromCaller)
        } finally {
            Bytes.wipe(kCall)
        }
    }
}

/** A call this device placed, while it waits for the answer. Holds the ephemeral KEM key: [destroy] when the call is over. */
class OutgoingCall @MadeByCallApi constructor(
    val callId: String,
    val connectionId: String,
    val media: String,
    /** When the offer expires (the vault's clock): the caller sends `call.end{timeout}` then. */
    val exp: Instant,
    /** The servers this device may use, and no others (empty until a calling service exists). */
    val ice: CallWire.IceConfig,
    internal val offered: List<Int>,
    private val offerKey: KemPrivateKey,
) {
    internal fun key(): KemPrivateKey = offerKey

    fun destroy() = offerKey.destroy()

    override fun toString(): String = "OutgoingCall($callId)"
}

/** A verified `call.offer`: a connection is calling. Nothing of it is used before every signature has checked out. */
class IncomingCall @MadeByCallApi constructor(
    val callId: String,
    val connectionId: String,
    val media: String,
    /** The caller's SDP offer, without the media-suite attribute: ready for the media stack. */
    val sdp: String,
    /** When the offer expires, by the vault's clock. */
    val exp: Instant,
    /**
     * How long the offer had to live when the vault sent it (`exp` minus the event's `ts`, both the vault's clock):
     * how long the device rings, whatever its own clock says.
     */
    val ringFor: Duration,
    val ice: CallWire.IceConfig,
    /** The suite this device will answer with, and the caller's list as sent: fixed before the phone rings. */
    val negotiated: CallMedia.Negotiated,
    internal val ek: ByteArray,
) {
    /** The caller's media suites, as sent (numbers this build does not know included). */
    val offered: List<Int> get() = negotiated.offered

    override fun toString(): String = "IncomingCall($callId)"
}

/** The callee's verified answer on the calling device: its SDP (without the media-suite attribute) and the call's keys. */
class AcceptedCall @MadeByCallApi constructor(val callId: String, val sdp: String, val keys: CallKeys)

/** One ICE candidate of `call.ice`. It holds a network address: [toString] does not print it. */
@Serializable
data class CallCandidate(
    val candidate: String,
    @SerialName("sdp_mid") val sdpMid: String? = null,
    @SerialName("sdp_mline_index") val sdpMLineIndex: Int? = null,
) {
    override fun toString(): String = "CallCandidate([redacted])"
}

/** `call.ice` as received. */
data class CallIce(val callId: String, val candidates: List<CallCandidate>)

/** `call.end` as received. */
data class CallEnded(val callId: String, val reason: String)

/** One entry of `call.list` (the vault keeps the latest 200 calls; no SDP, no keys). */
@Serializable
data class CallRecord(
    @SerialName("call_id") val callId: String,
    @SerialName("connection_id") val connectionId: String,
    /** `in` or `out`. */
    val direction: String,
    val media: String,
    /** `ringing`, `active` or `ended`. */
    val state: String,
    val reason: String? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("answered_at") val answeredAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
)

private fun JsonObject.need(k: String): String = VaultJson.str(this, k) ?: throw CallMessageException("missing $k")

private fun JsonObject.b64(k: String, n: Int = -1): ByteArray = try {
    Base64s.decodeStd(need(k), n)
} catch (e: CryptoException) {
    throw CallMessageException("malformed $k", e)
}

private fun JsonObject.callId(): String = need("call_id").also { if (!Ulid.isValid(it)) throw CallMessageException("call_id") }

private fun <T> checked(what: String, block: () -> T): T = try {
    block()
} catch (e: CryptoException) {
    throw CallMessageException(what, e)
} catch (e: java.time.DateTimeException) {
    throw CallMessageException(what, e)
}

private fun VaultApi.iceOf(o: JsonObject, callId: String): CallWire.IceConfig = checked("ice configuration") {
    val sig = o.b64("ice_sig", Suite.ED25519_SIGNATURE_SIZE)
    CallWire.verifyIce(device.vaultIdentityKey(), o.b64("ice_config"), sig, callId, device.clockNow())
}

/**
 * The other side's share: its device's signature and its vault's, under the connection's identity key (`peer_ik`),
 * which must be the key the app already knows the connection by: [pinnedFingerprint] is the fingerprint the
 * connection's details show the member (VAULT-MESSAGING §10.8). A call is thereby bound to the identity the member
 * can see, not only to what the event says.
 */
private fun verifyPeerShare(o: JsonObject, message: ByteArray, pinnedFingerprint: String) = checked("key-exchange share") {
    val peerIk = o.b64("peer_ik", Suite.ED25519_PUBLIC_SIZE)
    val shown = IkFingerprint.format(peerIk)
    if (pinnedFingerprint.isEmpty() || !Bytes.constantTimeEquals(shown.toByteArray(), pinnedFingerprint.toByteArray())) {
        throw CryptoException.Signature("peer_ik is not the connection's identity key")
    }
    CallWire.verifyShare(
        vaultIk = peerIk,
        deviceIk = o.b64("device_ik", Suite.ED25519_PUBLIC_SIZE),
        message = message,
        deviceSig = o.b64("device_sig", Suite.ED25519_SIGNATURE_SIZE),
        vaultSig = o.b64("vault_sig", Suite.ED25519_SIGNATURE_SIZE),
    )
}

private fun sdpOf(o: JsonObject): String = o.need("sdp").also(::checkSdp)

/**
 * The size of §10.10 and the one shape a VettID call has: exactly one media section, audio, Opus alone. Anything
 * else would be media outside the frame encryption (CALL-MEDIA.md §7), so it never reaches the media stack.
 */
private fun checkSdp(sdp: String) {
    if (sdp.isEmpty() || sdp.toByteArray(Charsets.UTF_8).size > CallLimits.MAX_SDP) throw CallMessageException("sdp size")
    checked("sdp") { CallMedia.requireOneOpusAudioSection(sdp) }
}

/**
 * `call.start`: places a call to [connectionId] with this device's SDP offer [sdp]; of [suites], what this device's
 * media engine can do, those form 1 may offer ([MediaSuite.formOne]: suite 1) are added to it here, the preferred
 * one first. A fresh `call_id` and ephemeral KEM key, the
 * share signed with the device's identity key. Deposited once and awaited for [timeout]. Errors
 * ([VaultOpException]): `busy` (this vault is already in a call), `not_found`, `connection_unavailable`,
 * `owner_check_required`, `exists`, `bad_request`, `internal`, and `unsupported_type` from a vault release without
 * calls. When it fails in any other way after the request may have left (no answer in time, a lost response, an
 * answer that does not verify), a durable `call.end{hangup}` for the same `call_id` follows, so that a call the
 * vault did place does not ring on for nobody.
 */
@OptIn(MadeByCallApi::class)
suspend fun VaultApi.callStart(
    connectionId: String,
    sdp: String,
    suites: Collection<MediaSuite> = MediaSuite.DEFAULT,
    media: String = CallWire.MEDIA_AUDIO,
    timeout: Duration = CallLimits.START_TIMEOUT,
): OutgoingCall {
    val callId = device.newId()
    val offered = checked("media suites") { CallMedia.offerOf(MediaSuite.formOne(suites)) }
    val offer = CallMedia.sdpWithOffer(sdp, offered)
    checkSdp(offer)
    val key = CallWire.newOfferKey()
    var attempted = false
    try {
        val ek = key.publicKey.bytes()
        val share = device.signCallShare(CallWire.shareMessage(CallWire.ROLE_OFFER, callId, media, ek))
        attempted = true
        val o = device.opOnce(
            "call.start",
            buildJsonObject {
                put("connection_id", connectionId)
                put("call_id", callId)
                put("media", media)
                put("sdp", offer)
                put("ek", Base64s.encodeStd(ek))
                put("ek_sig", Base64s.encodeStd(share))
            },
            timeout,
        )
        if (o.need("call_id") != callId) throw CallMessageException("call.start answered for another call")
        val exp = checked("exp") { Timestamps.parseMillis(o.need("exp")) }
        return OutgoingCall(callId, connectionId, media, exp, iceOf(o, callId), offered, key)
    } catch (e: VaultOpException) {
        // The vault's own refusal: there is no call.
        key.destroy()
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        key.destroy()
        // No answer in time, an HTTP response that was lost, or an answer that does not verify: the vault may have
        // taken the call and sent the offer on, and nothing here could end it later. The durable hang-up does (a
        // vault that never saw the call ignores it).
        if (attempted) runCatching { callEnd(callId, CallReason.HANGUP) }
        throw e
    }
}

/**
 * Checks a `call.offer` event, in this order: the shape and sizes; that the offer has not expired; the vault-signed
 * ICE configuration; `peer_ik` against [pinnedFingerprint] (the connection's identity as the app shows it); the
 * caller's share under `peer_ik`; the SDP's one Opus audio section; the media suites, of which one must be among
 * [suites] (what this device's media engine can do) as form 1 allows ([MediaSuite.formOne]: suite 1, so a form 1
 * offer without suite 1 does not ring). Throws [CallMessageException] for an offer that must not
 * ring: nothing of it is used, and nothing is sent.
 */
@OptIn(MadeByCallApi::class)
fun VaultApi.callIncoming(
    event: VaultMessage,
    pinnedFingerprint: String,
    suites: Collection<MediaSuite> = MediaSuite.DEFAULT,
): IncomingCall {
    val o = event.body
    val callId = o.callId()
    val media = o.need("media")
    if (media != CallWire.MEDIA_AUDIO && media != CallWire.MEDIA_VIDEO) throw CallMessageException("media")
    val sdp = sdpOf(o)
    val ek = o.b64("ek", Suite.EK_SIZE)
    val exp = checked("exp") { Timestamps.parseMillis(o.need("exp")) }
    // Both times are the vault's: a phone whose clock is behind still rings for the right time. (An offer that
    // has expired by the phone's clock never gets here: the session layer drops it, VaultDevice.droppedForTime.)
    val ringFor = Duration.between(event.ts, exp)
    if (ringFor <= Duration.ZERO || ringFor > CallLimits.MAX_OFFER_AHEAD || !device.clockNow().isBefore(exp)) {
        throw CallMessageException("offer expired or too far ahead")
    }
    val ice = iceOf(o, callId)
    verifyPeerShare(o, CallWire.shareMessage(CallWire.ROLE_OFFER, callId, media, ek), pinnedFingerprint)
    val negotiated = checked("media suites") { CallMedia.choose(CallMedia.offeredIn(sdp), MediaSuite.formOne(suites)) }
    return IncomingCall(callId, o.need("connection_id"), media, CallMedia.sdpWithout(sdp), exp, ringFor, ice, negotiated, ek)
}

/**
 * `call.answer`: answers [call] with this device's SDP answer [sdp]; the media suite chosen when the offer was
 * checked ([IncomingCall.negotiated]) is added to it here.
 * Encapsulates to the caller's key, signs the share, deposits once, and returns the call's keys, which never leave
 * this device. The vault does not answer: a call that was no longer ringing comes back as `call.end{unavailable}`.
 */
suspend fun VaultApi.callAnswer(call: IncomingCall, sdp: String): CallKeys {
    val chosen = call.negotiated
    val answer = CallMedia.sdpWithChoice(sdp, chosen.chosen)
    checkSdp(answer)
    val a = checked("key agreement") { CallWire.answer(call.ek, call.callId) }
    val keys = CallKeys.derive(a.kCall, chosen, call.callId, caller = false)
    try {
        device.sendOnce(
            "call.answer",
            buildJsonObject {
                put("call_id", call.callId)
                put("sdp", answer)
                put("enc", Base64s.encodeStd(a.enc))
                put("enc_sig", Base64s.encodeStd(device.signCallShare(CallWire.shareMessage(CallWire.ROLE_ANSWER, call.callId, "", a.enc))))
            },
        )
        return keys
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        keys.destroy()
        throw e
    }
}

/**
 * Checks the `call.answer` event for [call] on the calling device: `peer_ik` against [pinnedFingerprint], the
 * answerer's share under it, the SDP's one Opus audio section and the choice of media suite; then derives the
 * call's keys. The ephemeral KEM key is destroyed when the answer is accepted; after a [CallMessageException] the
 * caller of this function destroys [call] (the call is over).
 */
@OptIn(MadeByCallApi::class)
fun VaultApi.callAccept(call: OutgoingCall, event: VaultMessage, pinnedFingerprint: String): AcceptedCall {
    val o = event.body
    if (o.callId() != call.callId) throw CallMessageException("answer for another call")
    val sdp = sdpOf(o)
    val enc = o.b64("enc", Suite.ENC_SIZE)
    verifyPeerShare(o, CallWire.shareMessage(CallWire.ROLE_ANSWER, call.callId, "", enc), pinnedFingerprint)
    val chosen = checked("media suites") { CallMedia.chosenIn(sdp, call.offered) }
    val kCall = checked("key agreement") { CallWire.accept(call.key(), enc, call.callId) }
    call.destroy()
    return AcceptedCall(call.callId, CallMedia.sdpWithout(sdp), CallKeys.derive(kCall, chosen, call.callId, caller = true))
}

/** `call.ringing` (ephemeral): this device is ringing; the caller hears a ring-back tone. Forwarded once per call. */
suspend fun VaultApi.callRinging(callId: String) {
    device.sendOnce("call.ringing", buildJsonObject { put("call_id", callId) }, device.clockNow().plus(CallLimits.EPHEMERAL_TTL))
}

/**
 * `call.ice` (ephemeral): 1 to [CallLimits.MAX_CANDIDATES] candidates of the call's device. The caller of this
 * function batches: a vault forwards at most [CallLimits.MAX_ICE_MESSAGES] per call, and the relay takes 5 deposits
 * a second from one sender.
 */
suspend fun VaultApi.callIce(callId: String, candidates: List<CallCandidate>) {
    val ok = candidates.size in 1..CallLimits.MAX_CANDIDATES && candidates.all {
        it.candidate.toByteArray(Charsets.UTF_8).size <= CallLimits.MAX_CANDIDATE &&
            (it.sdpMid?.toByteArray(Charsets.UTF_8)?.size ?: 0) <= CallLimits.MAX_SDP_MID &&
            (it.sdpMLineIndex ?: 0) in 0..CallLimits.MAX_MLINE_INDEX
    }
    if (!ok) throw CallMessageException("candidates")
    device.sendOnce(
        "call.ice",
        buildJsonObject {
            put("call_id", callId)
            putJsonArray("candidates") {
                for (c in candidates) {
                    add(
                        buildJsonObject {
                            put("candidate", c.candidate)
                            c.sdpMid?.let { put("sdp_mid", it) }
                            c.sdpMLineIndex?.let { put("sdp_mline_index", it) }
                        },
                    )
                }
            }
        },
        device.clockNow().plus(CallLimits.EPHEMERAL_TTL),
    )
}

/**
 * `call.end`: hang up ([CallReason.HANGUP]), decline a ringing call, give up an unanswered one
 * ([CallReason.TIMEOUT], sent by the **caller** only: the callee's vault records the missed call when it arrives)
 * or report a failure. Durable: kept until the relay has it.
 */
suspend fun VaultApi.callEnd(callId: String, reason: String) {
    require(reason in CallReason.ALL) { "reason" }
    device.send(
        "call.end",
        buildJsonObject {
            put("call_id", callId)
            put("reason", reason)
        },
    )
}

/**
 * `call.list`: the vault's call history, newest first ([limit] 1–200). A vault release without calls answers
 * `unsupported_type`: that answer is how the app learns that the vault has no calls (§11.10.8: no message lists a
 * release's features).
 */
suspend fun VaultApi.callList(limit: Int = 50): List<CallRecord> {
    val o = device.op("call.list", buildJsonObject { put("limit", limit) })
    return VaultJson.decode(ListSerializer(CallRecord.serializer()), o["calls"] ?: JsonArray(emptyList()))
}

/** One call event from the vault, in the order the vault's events arrived. */
sealed interface CallEvent {
    /** `call.offer`, **unchecked**: pass [message] to [callIncoming]. */
    class Offer(val message: VaultMessage) : CallEvent {
        /** The connection the event names, to look its identity up before the check; not yet verified. */
        val connectionId: String? get() = VaultJson.str(message.body, "connection_id")
    }

    /** `call.answer`, **unchecked**: pass [message] to [callAccept] with the call it is for. */
    class Answer(val message: VaultMessage) : CallEvent {
        val callId: String? get() = VaultJson.str(message.body, "call_id")
    }

    data class Ringing(val callId: String) : CallEvent

    data class Ended(val callId: String, val reason: String) : CallEvent

    data class Ice(val ice: CallIce) : CallEvent
}

private val CALL_EVENT_TYPES = setOf("call.offer", "call.answer", "call.ringing", "call.end", "call.ice")

private fun parseIce(m: VaultMessage): CallIce? {
    val id = VaultJson.str(m.body, "call_id") ?: return null
    val list = (m.body["candidates"] as? JsonArray)?.mapNotNull { e ->
        runCatching { VaultJson.decode(CallCandidate.serializer(), e.jsonObject) }.getOrNull()
    }.orEmpty().filter { it.candidate.length <= CallLimits.MAX_CANDIDATE }.take(CallLimits.MAX_CANDIDATES)
    return if (list.isEmpty()) null else CallIce(id, list)
}

private fun parseEnd(m: VaultMessage): CallEnded? {
    val id = VaultJson.str(m.body, "call_id")
    val reason = VaultJson.str(m.body, "reason")
    return if (id == null || reason == null) null else CallEnded(id, reason)
}

/** Every `call.*` event, one flow, so that their order is the vault's (an answer before the candidates after it). */
val VaultApi.callEvents: Flow<CallEvent>
    get() = callEventsIn(device.events)

/** [callEvents] of a flow of the vault device's events (for a listener that holds the events and not the client). */
fun callEventsIn(events: Flow<VaultMessage>): Flow<CallEvent> =
    events.mapNotNull { m ->
        when (m.type.takeIf { it in CALL_EVENT_TYPES }) {
            "call.offer" -> CallEvent.Offer(m)
            "call.answer" -> CallEvent.Answer(m)
            "call.ringing" -> VaultJson.str(m.body, "call_id")?.let { CallEvent.Ringing(it) }
            "call.end" -> parseEnd(m)?.let { CallEvent.Ended(it.callId, it.reason) }
            "call.ice" -> parseIce(m)?.let { CallEvent.Ice(it) }
            else -> null
        }
    }
