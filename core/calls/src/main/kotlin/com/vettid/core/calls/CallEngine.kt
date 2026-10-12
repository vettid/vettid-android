package com.vettid.core.calls

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DataPacketCryptorFactory
import org.webrtc.FrameCryptor
import org.webrtc.FrameCryptorAlgorithm
import org.webrtc.FrameCryptorFactory
import org.webrtc.FrameCryptorKeyDerivationAlgorithm
import org.webrtc.FrameCryptorKeyProvider
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Everything the spike screen shows of a call. Nothing here is secret. */
data class CallSnapshot(
    val role: String = "",
    val signaling: String = "",
    val connection: String = "NEW",
    val ice: String = "NEW",
    val gathering: String = "NEW",
    /** This phone's offer or answer, complete with candidates, ready to show ([SignalBlob]). */
    val localBlob: String? = null,
    val senderCryptor: String = "NEW",
    val receiverCryptor: String = "NEW",
    val opusOnly: String = "",
    val stats: CallStats = CallStats(),
    val wrongKey: Boolean = false,
    val sendPlain: Boolean = false,
    val micMuted: Boolean = false,
    val selfCheck: String = "",
    val error: String? = null,
    /** Milliseconds: `PeerConnectionFactory.initialize` (loads the native library), then the factory. */
    val initMs: Long = 0,
    val factoryMs: Long = 0,
)

/**
 * C0 SPIKE: one audio-only (Opus) peer connection whose sender and receiver each carry the media stack's
 * `FrameCryptor`, keyed from a 32-byte key that never leaves this object except into the native key provider.
 * Signalling is whole offers and answers ([SignalBlob]), no trickle, no ICE servers: two phones on one network.
 *
 * The native library is loaded by [create], never before (CALLS-PLAN §4 proof 5). Nothing of the stack's own
 * logging is switched on: at severity INFO the library logs key material (docs/calls/C0-FRAME-FORMAT.md §9).
 */
class CallEngine private constructor(
    private val factory: PeerConnectionFactory,
    private val audioDevice: JavaAudioDeviceModule,
    private val key: ByteArray,
    initMs: Long,
    factoryMs: Long,
) {
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "calls-spike") }
    private val snapshot = MutableStateFlow(CallSnapshot(initMs = initMs, factoryMs = factoryMs))
    val state: StateFlow<CallSnapshot> = snapshot.asStateFlow()

    private val keys: FrameCryptorKeyProvider = newKeyProvider().also { it.setSharedKey(KEY_INDEX, key) }
    private var connection: PeerConnection? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var senderCryptor: FrameCryptor? = null
    private var receiverCryptor: FrameCryptor? = null

    @Volatile
    private var closed = false

    /** Makes the offer. [CallSnapshot.localBlob] follows when the candidates are gathered. */
    fun startAsCaller() = run("offer") {
        val pc = open("caller")
        val audio = newTrack()
        val transceiver = pc.addTransceiver(
            audio,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf(STREAM_ID)),
        )
        preferOpus(transceiver)
        encrypt(transceiver)
        pc.createOffer(
            Sdp("create offer") { offer -> pc.setLocalDescription(Sdp("set local offer"), offer) },
            MediaConstraints(),
        )
    }

    /** Takes the other phone's offer and makes the answer. */
    fun acceptOffer(sdp: String) = run("answer") {
        val pc = open("callee")
        pc.setRemoteDescription(
            Sdp("set remote offer", onSet = {
                pc.addTrack(newTrack(), listOf(STREAM_ID))
                val transceiver = pc.transceivers.first { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
                encrypt(transceiver)
                pc.createAnswer(
                    Sdp("create answer") { answer -> pc.setLocalDescription(Sdp("set local answer"), answer) },
                    MediaConstraints(),
                )
            }),
            SessionDescription(SessionDescription.Type.OFFER, sdp),
        )
    }

    /** The caller takes the answer; media follows. */
    fun acceptAnswer(sdp: String) = run("take answer") {
        val pc = checkNotNull(connection) { "no offer was made" }
        pc.setRemoteDescription(Sdp("set remote answer"), SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    /**
     * The wrong-key test: this phone encrypts and decrypts under another key. Expected on both phones: no sound
     * from the other side (level 0) and the receiving cryptor in DECRYPTIONFAILED.
     */
    fun setWrongKey(wrong: Boolean) = run("key") {
        keys.setSharedKey(KEY_INDEX, if (wrong) CallKey.wrong(key) else key)
        snapshot.update { it.copy(wrongKey = wrong) }
    }

    /**
     * Sets the (right or wrong) key again. A receiver that gave up after [FAILURE_TOLERANCE] failures tries no
     * further frame until its key is set again, even when the other phone has gone back to the right key.
     */
    fun reapplyKey() = setWrongKey(snapshot.value.wrongKey)

    /** The downgrade test: this phone sends frames unencrypted. Expected: the other phone plays nothing. */
    fun setSendPlain(plain: Boolean) = run("plain") {
        senderCryptor?.setEnabled(!plain)
        snapshot.update { it.copy(sendPlain = plain) }
    }

    fun setMicMuted(muted: Boolean) = run("mute") {
        track?.setEnabled(!muted)
        snapshot.update { it.copy(micMuted = muted) }
    }

    /**
     * Checks on this phone that the native library derives and encrypts as docs/calls/C0-FRAME-FORMAT.md says,
     * with a throwaway key and a key provider of its own: a packet sealed by the library's `DataPacketCryptor`
     * must open under [FrameFormat.frameKey], and a ratchet step must give [FrameFormat.ratchet].
     */
    fun selfCheck() = run("self-check") {
        val material = CallKey.random()
        val message = "VettID C0 frame format".toByteArray()
        val provider = newKeyProvider()
        provider.setSharedKey(KEY_INDEX, material)
        val cryptor = DataPacketCryptorFactory.createDataPacketCryptor(FrameCryptorAlgorithm.AES_GCM, provider)
        val packet = cryptor.encrypt("self-check", KEY_INDEX, message)
        val opened = FrameFormat.openPacket(FrameFormat.frameKey(material, SALT), packet.iv, packet.payload)
        val cipherOk = opened?.contentEquals(message) == true
        val ratchetOk = provider.ratchetSharedKey(KEY_INDEX).contentEquals(FrameFormat.ratchet(material, SALT))
        cryptor.dispose()
        provider.dispose()
        val result = "HKDF-SHA256 → AES-128-GCM, 16-byte tag, ${packet.iv.size}-byte IV: ${verdict(cipherOk)}; " +
            "ratchet step: ${verdict(ratchetOk)}"
        snapshot.update { it.copy(selfCheck = result) }
    }

    /** Ends the call and frees the native objects. The engine cannot be used again. */
    fun close() {
        if (closed) return
        closed = true
        worker.execute {
            runCatching {
                connection?.close()
                senderCryptor?.dispose()
                receiverCryptor?.dispose()
                connection?.dispose()
                track?.dispose()
                source?.dispose()
                keys.dispose()
                factory.dispose()
                audioDevice.release()
            }
            key.fill(0)
        }
        worker.shutdown()
    }

    private fun open(role: String): PeerConnection {
        check(connection == null) { "this engine already has a call" }
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            // Fewer candidates in the QR code; two phones on one network reach each other by UDP.
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }
        val pc = checkNotNull(factory.createPeerConnection(config, Events())) { "no peer connection" }
        connection = pc
        snapshot.update { it.copy(role = role) }
        worker.scheduleWithFixedDelay({ poll(pc) }, STATS_MS, STATS_MS, TimeUnit.MILLISECONDS)
        return pc
    }

    private fun newTrack(): AudioTrack {
        val audioSource = factory.createAudioSource(MediaConstraints())
        source = audioSource
        return factory.createAudioTrack(TRACK_ID, audioSource).also { track = it }
    }

    /** Offers Opus alone, so that the SDP is small and nothing else can be negotiated. */
    private fun preferOpus(transceiver: RtpTransceiver) {
        val opus = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO).codecs
            .filter { it.name.equals("opus", ignoreCase = true) }
        val result = if (opus.isEmpty()) "no Opus capability" else transceiver.setCodecPreferences(opus).let {
            if (it.isSuccess) "offered alone" else "preference refused"
        }
        snapshot.update { it.copy(opusOnly = result) }
    }

    /**
     * A frame cryptor on the sender and on the receiver, both enabled before any media flows (a new cryptor is
     * created disabled, and a disabled cryptor passes frames through in the clear).
     */
    private fun encrypt(transceiver: RtpTransceiver) {
        senderCryptor = FrameCryptorFactory.createFrameCryptorForRtpSender(
            factory, transceiver.sender, LOCAL_ID, FrameCryptorAlgorithm.AES_GCM, keys,
        ).apply {
            setObserver { _, state -> snapshot.update { it.copy(senderCryptor = state.name) } }
            setEnabled(true)
        }
        receiverCryptor = FrameCryptorFactory.createFrameCryptorForRtpReceiver(
            factory, transceiver.receiver, REMOTE_ID, FrameCryptorAlgorithm.AES_GCM, keys,
        ).apply {
            setObserver { _, state -> snapshot.update { it.copy(receiverCryptor = state.name) } }
            setEnabled(true)
        }
    }

    private fun poll(pc: PeerConnection) {
        if (closed) return
        runCatching {
            pc.getStats { report ->
                val entries = report.statsMap.values.map { StatEntry(it.id, it.type, it.members) }
                snapshot.update { it.copy(stats = CallStats.from(entries)) }
            }
        }
    }

    private fun run(what: String, block: () -> Unit) {
        if (closed) return
        worker.execute {
            runCatching(block).onFailure { failure -> fail("$what: ${failure.message ?: failure.javaClass.simpleName}") }
        }
    }

    private fun fail(message: String) = snapshot.update { it.copy(error = message) }

    private fun verdict(ok: Boolean) = if (ok) "MATCH" else "MISMATCH"

    /** SDP callbacks, moved off the stack's signalling thread onto the engine's worker. */
    private inner class Sdp(
        private val what: String,
        private val onSet: () -> Unit = {},
        private val onCreated: (SessionDescription) -> Unit = {},
    ) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = run(what) { onCreated(description) }
        override fun onSetSuccess() = run(what) { onSet() }
        override fun onCreateFailure(error: String) = fail("$what: $error")
        override fun onSetFailure(error: String) = fail("$what: $error")
    }

    @Suppress("TooManyFunctions") // The stack's observer interface: most of it is not used by an audio-only call.
    private inner class Events : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = snapshot.update { it.copy(signaling = state.name) }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = snapshot.update { it.copy(ice = state.name) }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = snapshot.update { it.copy(connection = state.name) }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            snapshot.update { it.copy(gathering = state.name) }
            if (state == PeerConnection.IceGatheringState.COMPLETE) run("local description") { publish() }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    /** The complete local description (every candidate inside), as the text to carry to the other phone. */
    private fun publish() {
        val local = connection?.localDescription ?: return
        val kind = if (local.type == SessionDescription.Type.OFFER) SignalBlob.Kind.OFFER else SignalBlob.Kind.ANSWER
        snapshot.update { it.copy(localBlob = SignalBlob.encode(kind, local.description)) }
    }

    companion object {
        /** The key provider's "ratchet salt": the HKDF salt of the frame key. Fixed for the spike. */
        val SALT: ByteArray = "vettid-calls-c0".toByteArray()

        /** Decryption failures after which the receiver reports DECRYPTIONFAILED and waits for a new key. */
        const val FAILURE_TOLERANCE = 10
        private const val KEY_INDEX = 0
        private const val KEY_RING = 1
        private const val STATS_MS = 300L
        private const val STREAM_ID = "vettid"
        private const val TRACK_ID = "audio0"
        private const val LOCAL_ID = "local"
        private const val REMOTE_ID = "remote"

        /**
         * One shared key for both directions, HKDF, no automatic ratchet, no "unencrypted" magic trailer (a frame
         * that does not decrypt is never played), a key ring of one.
         */
        private fun newKeyProvider(): FrameCryptorKeyProvider = FrameCryptorFactory.createFrameCryptorKeyProvider(
            true,
            SALT,
            0,
            ByteArray(0),
            FAILURE_TOLERANCE,
            KEY_RING,
            true,
            FrameCryptorKeyDerivationAlgorithm.HKDF,
        )

        /** Whether the media stack's native library is in this process (by the stack's own loader's account). */
        fun nativeLibraryLoaded(): Boolean = runCatching {
            Class.forName("org.webrtc.NativeLibrary").getDeclaredMethod("isLoaded").apply { isAccessible = true }.invoke(null) as Boolean
        }.getOrDefault(false)

        /** Loads the native library (first call in the process) and builds the engine. [key] is copied. */
        fun create(context: Context, key: ByteArray): CallEngine {
            require(key.size == FrameFormat.MATERIAL_BYTES) { "the key is 32 bytes" }
            val app = context.applicationContext
            val t0 = SystemClock.elapsedRealtime()
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
            val t1 = SystemClock.elapsedRealtime()
            val audioDevice = JavaAudioDeviceModule.builder(app).createAudioDeviceModule()
            val factory = PeerConnectionFactory.builder().setAudioDeviceModule(audioDevice).createPeerConnectionFactory()
            val t2 = SystemClock.elapsedRealtime()
            return CallEngine(factory, audioDevice, key.copyOf(), t1 - t0, t2 - t1)
        }
    }
}
