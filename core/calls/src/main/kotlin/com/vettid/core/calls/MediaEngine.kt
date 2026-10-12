package com.vettid.core.calls

import com.vettid.core.crypto.call.CallWire
import com.vettid.core.crypto.call.MediaSuite
import com.vettid.core.vault.CallCandidate
import kotlinx.coroutines.flow.Flow

/** Where the media connection of a call stands. */
enum class MediaConnection { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

/** The frame encryption of one direction, as the media stack reports it. */
enum class CryptorState {
    NEW,

    /** Frames are being encrypted or decrypted under the call's key. */
    OK,

    /** No key is set: a sender sends nothing, a receiver plays nothing. */
    MISSING_KEY,

    /** The receiver could not decrypt: wrong key, or frames that were not encrypted. It plays nothing from now on. */
    DECRYPTION_FAILED,
    ENCRYPTION_FAILED,
    INTERNAL_ERROR,

    /** A state this build does not know: treated as a failure. */
    UNKNOWN,
}

enum class MediaDirection { SEND, RECEIVE }

/** What a [MediaEngine] tells the call. Nothing in an event is secret. */
sealed interface MediaEvent {
    /** One of this device's addresses, to be sent to the other device. */
    data class LocalCandidate(val candidate: CallCandidate) : MediaEvent

    /** Gathering has ended; [found] is how many addresses there were. None: this device cannot be reached. */
    data class GatheringDone(val found: Int) : MediaEvent

    data class Connection(val state: MediaConnection) : MediaEvent

    data class Cryptor(val direction: MediaDirection, val state: CryptorState) : MediaEvent

    /** The engine cannot go on ([what] is for diagnostics; never key material, never an address). */
    data class Failed(val what: String) : MediaEvent
}

/**
 * The media of one call, behind an interface so that the call state machine has no media stack in it (and its tests
 * run on a JVM). One engine is one call: it is created for it and [close]d with it.
 *
 * The rules an implementation keeps (docs/calls/CALL-MEDIA.md §7):
 * - frame encryption is in place and enabled on the sender and on the receiver before any SDP is applied, and is
 *   never switched off;
 * - a sender without a key sends nothing; a frame that does not decrypt is never played;
 * - nothing of the stack's own logging is ever switched on ([MediaLogGuard]);
 * - [setKeys] takes the keys into the stack and wipes the arrays it was given.
 */
interface MediaEngine {
    /** The suites this engine can encrypt with ([MediaSuite.PREFERENCE] order): what the device offers and accepts. */
    val suites: List<MediaSuite>

    /** One collector (the call). Events from the moment the engine exists are kept for it. */
    val events: Flow<MediaEvent>

    /** The caller's SDP offer. Nothing is gathered and nothing is sent yet. */
    suspend fun createOffer(): String

    /** The caller, once its vault has answered `call.start`: start gathering addresses, with [servers] and no others. */
    suspend fun startGathering(servers: List<CallWire.IceServer>)

    /** The caller takes the answer. [setKeys] comes first. */
    suspend fun acceptAnswer(sdp: String)

    /** The callee, when the member answers: takes the offer and returns the SDP answer. Gathering starts. */
    suspend fun acceptOffer(sdp: String, servers: List<CallWire.IceServer>): String

    /** The call's two frame keys for [suite]. Both arrays are wiped before this returns. */
    suspend fun setKeys(suite: MediaSuite, send: ByteArray, receive: ByteArray)

    /** The other device's addresses. Only after its SDP has been applied ([acceptAnswer], [acceptOffer]). */
    suspend fun addRemoteCandidates(candidates: List<CallCandidate>)

    fun setMuted(muted: Boolean)

    /** A reading of the stack's statistics, or null while there are none. */
    suspend fun diagnostics(): MediaDiagnostics?

    /** Ends the media and frees everything. Safe to call twice. */
    fun close()
}

/** How an engine is set up. */
data class MediaConfig(
    /**
     * DEBUG BUILDS ONLY, for measurement: ask the stack for a post-quantum DTLS key exchange (its field trial
     * `WebRTC-EnableDtlsPqc`). Off everywhere else (owner decision 2026-10-12: known-good algorithms only). The
     * stack reads field trials once per process: the first engine of a process decides.
     */
    val dtlsPqcTrial: Boolean = false,
)

fun interface MediaEngineFactory {
    /** A new engine for one call. May load the media stack's native library (first call in the process). */
    fun create(config: MediaConfig): MediaEngine
}
