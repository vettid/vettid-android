package com.vettid.core.vault

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Suite
import com.vettid.core.crypto.call.CallMedia
import com.vettid.core.crypto.call.CallWire
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

/** A call message the device cannot use: a share or an ICE configuration that does not verify, or a malformed body. */
class CallMessageException(message: String, cause: Throwable? = null) : java.io.IOException("call: $message", cause)

/**
 * The two frame keys of a call on this device (CallMedia): [send] encrypts the frames this device sends, [receive]
 * decrypts the other device's. They exist only on the two devices of the call. [destroy] wipes them.
 */
class CallKeys internal constructor(val suite: Int, private val sendKey: ByteArray, private val receiveKey: ByteArray) {
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

    override fun toString(): String = "CallKeys(suite=$suite, [redacted])"

    internal companion object {
        /** Derives both direction keys from [kCall] and wipes it. */
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
class OutgoingCall internal constructor(
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
class IncomingCall internal constructor(
    val callId: String,
    val connectionId: String,
    val media: String,
    /** The caller's SDP offer, without the media-suite attribute: ready for the media stack. */
    val sdp: String,
    /** When the offer expires: the device stops ringing then by itself. */
    val exp: Instant,
    val ice: CallWire.IceConfig,
    internal val offered: List<Int>,
    internal val ek: ByteArray,
) {
    override fun toString(): String = "IncomingCall($callId)"
}

/** The callee's verified answer on the calling device: its SDP (without the media-suite attribute) and the call's keys. */
class AcceptedCall internal constructor(val callId: String, val sdp: String, val keys: CallKeys)

/** One ICE candidate of `call.ice`. */
@Serializable
data class CallCandidate(
    val candidate: String,
    @SerialName("sdp_mid") val sdpMid: String? = null,
    @SerialName("sdp_mline_index") val sdpMLineIndex: Int? = null,
)

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

/** The other side's share: its device's signature and its vault's, under the connection's identity key (`peer_ik`). */
private fun verifyPeerShare(o: JsonObject, message: ByteArray) = checked("key-exchange share") {
    CallWire.verifyShare(
        vaultIk = o.b64("peer_ik", Suite.ED25519_PUBLIC_SIZE),
        deviceIk = o.b64("device_ik", Suite.ED25519_PUBLIC_SIZE),
        message = message,
        deviceSig = o.b64("device_sig", Suite.ED25519_SIGNATURE_SIZE),
        vaultSig = o.b64("vault_sig", Suite.ED25519_SIGNATURE_SIZE),
    )
}

private fun sdpOf(o: JsonObject): String = o.need("sdp").also {
    if (it.isEmpty() || it.toByteArray(Charsets.UTF_8).size > CallLimits.MAX_SDP) throw CallMessageException("sdp size")
}

/**
 * `call.start`: places a call to [connectionId] with this device's SDP offer [sdp] (the media suites are added to
 * it here). A fresh `call_id` and ephemeral KEM key, the share signed with the device's identity key. Deposited once
 * and awaited for [CallLimits.START_TIMEOUT]. Errors ([VaultOpException]): `busy` (this vault is already in a call),
 * `not_found`, `connection_unavailable`, `owner_check_required`, `exists`, `bad_request`, `internal`, and
 * `unsupported_type` from a vault release without calls.
 */
suspend fun VaultApi.callStart(connectionId: String, sdp: String, media: String = CallWire.MEDIA_AUDIO): OutgoingCall {
    val callId = device.newId()
    val offered = CallMedia.SUPPORTED
    val offer = CallMedia.sdpWithOffer(sdp, offered)
    if (offer.toByteArray(Charsets.UTF_8).size > CallLimits.MAX_SDP) throw CallMessageException("sdp size")
    val key = CallWire.newOfferKey()
    try {
        val ek = key.publicKey.bytes()
        val o = device.opOnce(
            "call.start",
            buildJsonObject {
                put("connection_id", connectionId)
                put("call_id", callId)
                put("media", media)
                put("sdp", offer)
                put("ek", Base64s.encodeStd(ek))
                put("ek_sig", Base64s.encodeStd(device.signCallShare(CallWire.shareMessage(CallWire.ROLE_OFFER, callId, media, ek))))
            },
            CallLimits.START_TIMEOUT,
        )
        if (o.need("call_id") != callId) throw CallMessageException("call.start answered for another call")
        val exp = checked("exp") { Timestamps.parseMillis(o.need("exp")) }
        return OutgoingCall(callId, connectionId, media, exp, iceOf(o, callId), offered, key)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        key.destroy()
        throw e
    }
}

/**
 * Checks a `call.offer` event: the vault-signed ICE configuration, the caller's share under the connection's
 * identity key, the media suites in the SDP, and that the offer has not expired. Throws [CallMessageException] for
 * an offer that must not ring.
 */
fun VaultApi.callIncoming(event: VaultMessage): IncomingCall {
    val o = event.body
    val callId = o.callId()
    val media = o.need("media")
    if (media != CallWire.MEDIA_AUDIO && media != CallWire.MEDIA_VIDEO) throw CallMessageException("media")
    val sdp = sdpOf(o)
    val ek = o.b64("ek", Suite.EK_SIZE)
    val exp = checked("exp") { Timestamps.parseMillis(o.need("exp")) }
    val now = device.clockNow()
    if (!now.isBefore(exp) || Duration.between(now, exp) > CallLimits.MAX_OFFER_AHEAD) {
        throw CallMessageException("offer expired or too far ahead")
    }
    val ice = iceOf(o, callId)
    verifyPeerShare(o, CallWire.shareMessage(CallWire.ROLE_OFFER, callId, media, ek))
    val offered = checked("media suites") { CallMedia.offeredIn(sdp) }
    return IncomingCall(callId, o.need("connection_id"), media, CallMedia.sdpWithout(sdp), exp, ice, offered, ek)
}

/**
 * `call.answer`: answers [call] with this device's SDP answer [sdp] (the chosen media suite is added to it here).
 * Encapsulates to the caller's key, signs the share, deposits once, and returns the call's keys, which never leave
 * this device. The vault does not answer: a call that was no longer ringing comes back as `call.end{unavailable}`.
 */
suspend fun VaultApi.callAnswer(call: IncomingCall, sdp: String): CallKeys {
    val chosen = checked("media suites") { CallMedia.choose(call.offered) }
    val answer = CallMedia.sdpWithChoice(sdp, chosen.chosen)
    if (answer.toByteArray(Charsets.UTF_8).size > CallLimits.MAX_SDP) throw CallMessageException("sdp size")
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
 * Checks the `call.answer` event for [call] on the calling device: the answerer's share under the connection's
 * identity key and its choice of media suite, then derives the call's keys. The ephemeral KEM key is destroyed.
 */
fun VaultApi.callAccept(call: OutgoingCall, event: VaultMessage): AcceptedCall {
    val o = event.body
    if (o.callId() != call.callId) throw CallMessageException("answer for another call")
    val sdp = sdpOf(o)
    val enc = o.b64("enc", Suite.ENC_SIZE)
    verifyPeerShare(o, CallWire.shareMessage(CallWire.ROLE_ANSWER, call.callId, "", enc))
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

/** `call.offer` events, unchecked: pass each to [callIncoming]. */
val VaultApi.callOffers: Flow<VaultMessage> get() = events("call.offer")

/** `call.answer` events, unchecked: pass the one of this device's call to [callAccept]. */
val VaultApi.callAnswers: Flow<VaultMessage> get() = events("call.answer")

/** `call.ringing`: the callee's device rings. */
val VaultApi.callRingings: Flow<String> get() = events("call.ringing").mapNotNull { VaultJson.str(it.body, "call_id") }

/** `call.end`: hung up, declined, busy, timed out, answered on another device, unavailable or failed. */
val VaultApi.callEnds: Flow<CallEnded>
    get() = events("call.end").mapNotNull { m ->
        val id = VaultJson.str(m.body, "call_id")
        val reason = VaultJson.str(m.body, "reason")
        if (id == null || reason == null) null else CallEnded(id, reason)
    }

/** `call.ice`: the other device's candidates. They may arrive before the answer (caller) or before Answer is tapped (callee). */
val VaultApi.callIce: Flow<CallIce>
    get() = events("call.ice").mapNotNull { m ->
        val id = VaultJson.str(m.body, "call_id") ?: return@mapNotNull null
        val list = (m.body["candidates"] as? JsonArray)?.mapNotNull { e ->
            runCatching { VaultJson.decode(CallCandidate.serializer(), e.jsonObject) }.getOrNull()
        }.orEmpty().filter { it.candidate.length <= CallLimits.MAX_CANDIDATE }.take(CallLimits.MAX_CANDIDATES)
        if (list.isEmpty()) null else CallIce(id, list)
    }
