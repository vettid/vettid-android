package com.vettid.core.calls

import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.call.CallWire
import com.vettid.core.crypto.call.MediaSuite
import com.vettid.core.vault.CallCandidate
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** The two frame keys of a call on their way from the signalling to the media engine, which wipes them. */
class MediaKeys(val suite: MediaSuite, val send: ByteArray, val receive: ByteArray) {
    fun wipe() = Bytes.wipe(send, receive)

    override fun toString(): String = "MediaKeys(suite=${suite.id}, [redacted])"
}

/** The vault's answer to `call.start`. */
data class PlacedCall(
    val callId: String,
    /** When the offer expires, by the vault's clock. */
    val exp: Instant,
    /** The servers the vault signed for this call, and the only ones the device may use. */
    val servers: List<CallWire.IceServer>,
)

/** The vault refused `call.start` with [code] (`busy`, `not_found`, `owner_check_required`, `unsupported_type`, …). */
class CallRefusedException(val code: String, cause: Throwable? = null) : IOException("call refused: $code", cause)

/**
 * The call cannot be made secure: a key-exchange share, an ICE configuration or a media suite that does not check
 * out, or no suite in common. The call ends; nothing of the message is used.
 */
class CallSecurityException(what: String, cause: Throwable? = null) : IOException("call not secure: $what", cause)

/** What reaches the call from the vault. Offers and answers arrive here **already verified** (signatures, suites). */
sealed interface SignalEvent {
    class Offer(
        val callId: String,
        val connectionId: String,
        /** The caller's SDP offer, ready for the media engine. */
        val sdp: String,
        val exp: Instant,
        /** How long to ring: the offer's lifetime by the vault's clock, not this phone's. */
        val ringFor: Duration,
        val servers: List<CallWire.IceServer>,
        /** The suite this device will answer with, chosen when the offer was checked: the engine is made for it. */
        val suite: MediaSuite,
    ) : SignalEvent {
        /** No SDP (it holds addresses) and no server credentials. */
        override fun toString(): String = "Offer($callId)"
    }

    /** The callee's device is ringing. */
    data class Ringing(val callId: String) : SignalEvent

    /** The callee answered: its SDP and this call's keys. */
    class Answer(val callId: String, val sdp: String, val keys: MediaKeys) : SignalEvent

    /** An answer arrived that does not verify: the call must not go on. */
    data class AnswerRejected(val callId: String) : SignalEvent

    data class Ice(val callId: String, val candidates: List<CallCandidate>) : SignalEvent

    data class End(val callId: String, val reason: String) : SignalEvent
}

/**
 * What the call state machine needs of the vault (VAULT-MESSAGING §10.10), so that it can be tested without one.
 * The implementation over `VaultApi` keeps each call's key material, verifies everything before it reaches
 * [events], and negotiates the media suite from the engine's [MediaEngine.suites].
 *
 * Internal: [CallMachine] trusts what this gives it, so there is one implementation outside tests,
 * [VaultCallSignalling], and no other module can add a second.
 */
internal interface CallSignalling {
    val events: Flow<SignalEvent>

    /**
     * What a `call.start` offers of [suites], the preferred one first: the caller's engine is made for the first.
     * Form 1 only: suite 1 ([MediaSuite.formOne]). That holds for **every** `call.start` that carries a form 1
     * signature, a C2 start that also carries form 2 included: no second suite is offered while any `call.start`
     * carries form 1, i.e. until form 1 is retired (VAULT-MESSAGING §10.10 "Call forms" rule 3, "Mixed releases").
     */
    fun offerable(suites: List<MediaSuite>): List<MediaSuite> = MediaSuite.formOne(suites)

    /** `call.start`. Throws [CallRefusedException] with the vault's code, [CallSecurityException], or an [IOException]. */
    suspend fun start(connectionId: String, sdp: String, suites: List<MediaSuite>): PlacedCall

    /** `call.answer` for a verified offer: returns the call's keys. Throws [CallSecurityException] or an [IOException]. */
    suspend fun answer(callId: String, sdp: String, suites: List<MediaSuite>): MediaKeys

    /** `call.ringing`. */
    suspend fun ringing(callId: String)

    /** `call.ice`: at most 16 candidates. */
    suspend fun ice(callId: String, candidates: List<CallCandidate>)

    /** `call.end` (durable). */
    suspend fun end(callId: String, reason: String)
}
