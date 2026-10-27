package com.vettid.core.calls

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.call.CallFrames
import com.vettid.core.crypto.call.CallMedia
import com.vettid.core.crypto.call.CallWire
import com.vettid.core.crypto.call.FrameCipher
import com.vettid.core.crypto.call.MediaSuite
import com.vettid.core.vault.CallCandidate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The media stack's process-wide start (`io.github.webrtc-sdk:android`, pinned in this module's build file). The
 * native library is loaded by the first call of a process, never before. Nothing of the stack's logging is switched
 * on, and [MediaLogGuard] is asked first.
 */
internal object WebRtcRuntime {
    /** The stack's field trial for a post-quantum DTLS key exchange (X25519MLKEM768 first, DTLS 1.3). */
    const val DTLS_PQC_TRIAL = "WebRTC-EnableDtlsPqc/Enabled/"

    /** Null until the stack has been started; then whether the trial is on in this process. */
    @Volatile
    var dtlsPqcTrial: Boolean? = null
        private set

    @Synchronized
    fun start(context: Context, config: MediaConfig): Boolean {
        MediaLogGuard.check()
        dtlsPqcTrial?.let { return it }
        val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
        // Deprecated upstream in favour of a per-factory setting this library version does not expose to Java yet.
        @Suppress("DEPRECATION")
        if (config.dtlsPqcTrial) options.setFieldTrials(DTLS_PQC_TRIAL)
        PeerConnectionFactory.initialize(options.createInitializationOptions())
        dtlsPqcTrial = config.dtlsPqcTrial
        return config.dtlsPqcTrial
    }
}

/**
 * The one peer connection factory and audio device module of the process, made on first use and kept.
 *
 * Why not one per call: no factory churn per call, and no two audio modules competing for the microphone while an
 * old call closes and a new one starts. This is NOT what fixes the calls that ended `NO_ADDRESS` on a working Wi-Fi
 * (2 of 5 on the test phone): the stack's network monitor starts and stops with each gathering, not with the factory,
 * so a kept factory can still be asked to gather before the phone's addresses are reported. That is fixed by
 * gathering continually ([WebRtcMediaEngine.configuration]) and by letting only the GATHER timer end a call without an address
 * ([CallMachine]); keep both, whatever happens to this object.
 *
 * What is shared is only the stack's plumbing. Everything that differs between a plain build and a test build is
 * per engine ([MediaFaults] and the checks in [WebRtcMediaEngine.factory]), and the one process-wide option, the DTLS
 * trial, is fixed by [WebRtcRuntime.start] before the first factory exists (the first engine of a process decides it,
 * as before). So there is no second factory to keep: a plain build can never be handed a fault-capable one, because
 * a factory has no fault in it.
 *
 * The audio device module is shared too (the builder takes one module per factory). It opens the microphone and the
 * speaker only while a track is live, which a closing call ends; neither the module nor the factory is disposed
 * (they live as long as the process). [WebRtcMediaEngine.close] disposes everything else of a call.
 */
internal object SharedStack {
    class Stack(val factory: PeerConnectionFactory, val audioDevice: JavaAudioDeviceModule)

    private var stack: Stack? = null

    @Synchronized
    fun get(app: Context): Stack = stack ?: run {
        val audioDevice = JavaAudioDeviceModule.builder(app).createAudioDeviceModule()
        val factory = PeerConnectionFactory.builder().setAudioDeviceModule(audioDevice).createPeerConnectionFactory()
        Stack(factory, audioDevice).also { stack = it }
    }
}

/**
 * [MediaEngine] on libwebrtc with LiveKit's frame cryptor: one audio-only (Opus) peer connection whose sender and
 * receiver each carry a frame cryptor, enabled before any SDP is applied, keyed per direction
 * (docs/calls/CALL-MEDIA.md §4, §7).
 *
 * Everything of the stack is touched on one worker thread. The engine never sees an address it does not pass on
 * and keeps no key: [setKeys] hands the keys to the stack's key provider and wipes them.
 *
 * NOT exercised by unit tests (it needs the native library): the two-phone tests of C1 are its test, and
 * `FrameFormatDeviceTest` (androidTest) runs two engines against each other on one phone and reads every frame they
 * send.
 */
@Suppress("TooManyFunctions")
class WebRtcMediaEngine private constructor(
    private val factory: PeerConnectionFactory,
    private val audioDevice: JavaAudioDeviceModule,
    private val pqcTrial: Boolean,
    private val faults: MediaFaults,
    /** True for the process-wide stack ([SharedStack]): shared by every call, so a call never disposes it. */
    private val sharedStack: Boolean = false,
) : MediaEngine {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "vettid-call-media") }
    private val closed = AtomicBoolean(false)
    private val channel = Channel<MediaEvent>(Channel.UNLIMITED)

    override val suites: List<MediaSuite> = SUITES
    override val events: Flow<MediaEvent> = channel.receiveAsFlow()

    private var connection: PeerConnection? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var provider: FrameCryptorKeyProvider? = null
    private var installed: MediaSuite? = null
    private var senderCryptor: FrameCryptor? = null
    private var receiverCryptor: FrameCryptor? = null

    /**
     * The sender id of the transceiver that carries the cryptors. Never the transceiver object itself: every call of
     * `PeerConnection.getTransceivers` disposes the objects it returned before, so a kept one is dead after the next
     * look (it is found again by this id).
     */
    private var senderId: String? = null
    private var pendingOffer: SessionDescription? = null
    private var found = 0

    @Volatile
    private var moved = false

    /** What the member wants, kept for a track that does not exist yet (muting while it rings must hold). */
    @Volatile
    private var wantMuted = false

    @Volatile
    private var sendState = CryptorState.NEW

    @Volatile
    private var receiveState = CryptorState.NEW

    override suspend fun createOffer(suite: MediaSuite): String = on {
        check(suite in suites) { "suite ${suite.id} is not one this engine can do" }
        val pc = open(emptyList())
        val t = pc.addTransceiver(
            newTrack(),
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf(STREAM_ID)),
        )
        opusOnly(t)
        encrypt(t, suite)
        val created = CompletableDeferred<SessionDescription>()
        pc.createOffer(describing(created), MediaConstraints())
        created
    }.await().also { onWorker { pendingOffer = it } }.description.let(::announced)

    /** The other side's SDP as the stack gets it: without `a=ssrc` lines (rule 2 of CALL-MEDIA.md §7). */
    private fun remote(sdp: String): String = if (faults.keepRemoteSsrc) sdp else CallMedia.sdpWithoutSsrc(sdp)

    /** This device's SDP as it is sent. */
    private fun announced(sdp: String): String = if (faults.misstateOwnSsrc) MediaFaultSdp.misstateSsrc(sdp) else sdp

    override suspend fun startGathering(servers: List<CallWire.IceServer>) {
        on {
            val pc = checkNotNull(connection) { "no offer" }
            val offer = checkNotNull(pendingOffer) { "no offer" }
            if (servers.isNotEmpty()) check(pc.setConfiguration(configuration(servers))) { "servers refused" }
            val set = CompletableDeferred<Unit>()
            pc.setLocalDescription(Sdp(onSet = { set.complete(Unit) }, onError = { set.completeExceptionally(MediaException(it)) }), offer)
            set
        }.await()
    }

    override suspend fun acceptAnswer(sdp: String) {
        on {
            val pc = checkNotNull(connection) { "no offer" }
            val set = CompletableDeferred<Unit>()
            pc.setRemoteDescription(
                Sdp(onSet = { set.complete(Unit) }, onError = { set.completeExceptionally(MediaException(it)) }),
                SessionDescription(SessionDescription.Type.ANSWER, remote(sdp)),
            )
            set
        }.await()
        on { requireAllMediaEncrypted(checkNotNull(connection) { "no offer" }) }
    }

    override suspend fun acceptOffer(sdp: String, servers: List<CallWire.IceServer>, suite: MediaSuite): String {
        val pc = on {
            check(suite in suites) { "suite ${suite.id} is not one this engine can do" }
            val pc = open(servers)
            // A sender with its track and a receiver exist before the offer is applied (the offer's audio section
            // takes this transceiver), so both cryptors are in place and enabled before any SDP.
            pc.addTrack(newTrack(), listOf(STREAM_ID))
            val t = pc.transceivers.first { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
            encrypt(t, suite)
            pc
        }
        on {
            val set = CompletableDeferred<Unit>()
            pc.setRemoteDescription(
                Sdp(onSet = { set.complete(Unit) }, onError = { set.completeExceptionally(MediaException(it)) }),
                SessionDescription(SessionDescription.Type.OFFER, remote(sdp)),
            )
            set
        }.await()
        val answer = on {
            adoptOfferedSection(pc)
            requireAllMediaEncrypted(pc)
            val created = CompletableDeferred<SessionDescription>()
            pc.createAnswer(describing(created), MediaConstraints())
            created
        }.await()
        on {
            val set = CompletableDeferred<Unit>()
            pc.setLocalDescription(Sdp(onSet = { set.complete(Unit) }, onError = { set.completeExceptionally(MediaException(it)) }), answer)
            set
        }.await()
        return announced(answer.description)
    }

    override suspend fun setKeys(suite: MediaSuite, send: ByteArray, receive: ByteArray) {
        try {
            on {
                MediaLogGuard.check()
                // The cryptors were made for the call's suite before any SDP (createOffer, acceptOffer) and are
                // never remade: a new cryptor starts disabled, and a disabled one passes clear frames. Keys for
                // another suite end the call as not encrypted (fail closed); nothing is sent or played under them.
                val made = checkNotNull(installed) { "no media yet" }
                if (suite != made) throw MediaNotEncryptedException("keys for suite ${suite.id}, cryptors made for suite ${made.id}")
                val keys = checkNotNull(provider) { "no media yet" }
                if (faults.wrongSendKey) for (i in send.indices) send[i] = (send[i].toInt() xor BYTE_MASK).toByte()
                val taken = keys.setKey(SEND_ID, CallFrames.KEY_INDEX, send) && keys.setKey(RECEIVE_ID, CallFrames.KEY_INDEX, receive)
                check(taken) { "keys refused" }
            }
        } finally {
            Bytes.wipe(send, receive)
        }
    }

    override suspend fun addRemoteCandidates(candidates: List<CallCandidate>) {
        on {
            val pc = connection ?: return@on
            for (c in candidates) pc.addIceCandidate(IceCandidate(c.sdpMid ?: "", c.sdpMLineIndex ?: 0, c.candidate))
        }
    }

    override fun setMuted(muted: Boolean) {
        wantMuted = muted
        onWorker { track?.setEnabled(!wantMuted) }
    }

    override suspend fun diagnostics(): MediaDiagnostics? {
        val report = on {
            val pc = connection
            val d = CompletableDeferred<List<StatEntry>?>()
            if (pc == null) {
                d.complete(null)
            } else {
                pc.getStats { r -> d.complete(r.statsMap.values.map { StatEntry(it.id, it.type, it.members) }) }
            }
            d
        }.await() ?: return null
        return MediaDiagnostics.from(report)
            .copy(dtlsPqcTrial = pqcTrial, sendCryptor = sendState, receiveCryptor = receiveState, cryptorsMoved = moved)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.execute {
            // Each step on its own: one that throws must not keep the microphone or the keys from being let go.
            val steps = listOf<() -> Unit>(
                { connection?.close() },
                { senderCryptor?.dispose() },
                { receiverCryptor?.dispose() },
                { connection?.dispose() },
                { track?.dispose() },
                { source?.dispose() },
                { provider?.dispose() },
                { if (!sharedStack) factory.dispose() },
                { if (!sharedStack) audioDevice.release() },
            )
            for (step in steps) runCatching(step)
            channel.close()
        }
        worker.shutdown()
    }

    // --- on the worker ---

    private fun open(servers: List<CallWire.IceServer>): PeerConnection {
        check(connection == null) { "this engine already has a call" }
        val pc = checkNotNull(factory.createPeerConnection(configuration(servers), Events())) { "no peer connection" }
        connection = pc
        return pc
    }

    private fun newTrack(): AudioTrack {
        val audioSource = factory.createAudioSource(MediaConstraints())
        source = audioSource
        return factory.createAudioTrack(TRACK_ID, audioSource).also {
            it.setEnabled(!wantMuted)
            track = it
        }
    }

    /**
     * The offer's audio section should have taken the transceiver that already carries the cryptors. If the stack
     * gave it another one, the track and the cryptors move there, still before the answer exists and before any
     * media ([MediaDiagnostics.cryptorsMoved] says so, for the phone tests).
     */
    private fun adoptOfferedSection(pc: PeerConnection) {
        val all = pc.transceivers
        val offered = all.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO && it.mid != null }
        checkNotNull(offered) { "the offer has no audio" }
        if (offered.sender.id() == senderId) return
        val stale = all.firstOrNull { it.sender.id() == senderId }
        check(offered.sender.setTrack(track, false)) { "the audio track was refused" }
        offered.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
        encrypt(offered, checkNotNull(installed) { "no cryptors yet" })
        stale?.stop()
        moved = true
    }

    /**
     * After the other side's SDP has been applied: the connection has exactly one live transceiver, and it is the
     * one whose sender and receiver carry the frame cryptors, both enabled. An SDP with another media section (the
     * signalling refuses those before they get here: this is the second lock) would have given the stack a receiver
     * with no cryptor, whose audio would play under DTLS-SRTP alone.
     */
    private fun requireAllMediaEncrypted(pc: PeerConnection) {
        val live = pc.transceivers.filterNot { it.isStopped }
        val mine = senderId
        if (live.size != 1 || mine == null || live[0].sender.id() != mine) {
            throw MediaNotEncryptedException("${live.size} media sections")
        }
        if ((senderCryptor?.isEnabled != true && !faults.sendUnencrypted) || receiverCryptor?.isEnabled != true) {
            throw MediaNotEncryptedException("frame encryption is off")
        }
    }

    /** Offers Opus alone: nothing else can be negotiated. */
    private fun opusOnly(t: RtpTransceiver) {
        val opus = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO).codecs
            .filter { it.name.equals("opus", ignoreCase = true) }
        check(opus.isNotEmpty() && t.setCodecPreferences(opus).isSuccess) { "Opus is not available" }
    }

    /**
     * A frame cryptor on the sender and on the receiver of [t], both enabled at once (a new cryptor is created
     * disabled, and a disabled cryptor passes frames through in the clear). Without a key the sender sends nothing
     * and the receiver plays nothing.
     */
    private fun encrypt(t: RtpTransceiver, suite: MediaSuite) {
        check(suite.frameCipher == FrameCipher.AES_GCM && suite in suites) { "suite ${suite.id}" }
        senderCryptor?.dispose()
        receiverCryptor?.dispose()
        provider?.dispose()
        val keys = keyProvider(suite)
        provider = keys
        senderId = t.sender.id()
        installed = suite
        val aesGcm = FrameCryptorAlgorithm.AES_GCM
        senderCryptor = FrameCryptorFactory.createFrameCryptorForRtpSender(factory, t.sender, SEND_ID, aesGcm, keys).apply {
            setObserver { _, state -> cryptor(MediaDirection.SEND, state) }
            setEnabled(!faults.sendUnencrypted)
            check(isEnabled || faults.sendUnencrypted) { "the sender's frame encryption is off" }
        }
        receiverCryptor = FrameCryptorFactory.createFrameCryptorForRtpReceiver(factory, t.receiver, RECEIVE_ID, aesGcm, keys).apply {
            setObserver { _, state -> cryptor(MediaDirection.RECEIVE, state) }
            setEnabled(true)
            check(isEnabled) { "the receiver's frame encryption is off" }
        }
    }

    private fun cryptor(direction: MediaDirection, state: FrameCryptor.FrameCryptionState) {
        val mapped = when (state) {
            FrameCryptor.FrameCryptionState.NEW -> CryptorState.NEW
            FrameCryptor.FrameCryptionState.OK -> CryptorState.OK
            FrameCryptor.FrameCryptionState.MISSINGKEY -> CryptorState.MISSING_KEY
            FrameCryptor.FrameCryptionState.DECRYPTIONFAILED -> CryptorState.DECRYPTION_FAILED
            FrameCryptor.FrameCryptionState.ENCRYPTIONFAILED -> CryptorState.ENCRYPTION_FAILED
            FrameCryptor.FrameCryptionState.INTERNALERROR -> CryptorState.INTERNAL_ERROR
            // No ratchet is configured: a ratcheted key is not something this call can have.
            else -> CryptorState.UNKNOWN
        }
        if (direction == MediaDirection.SEND) sendState = mapped else receiveState = mapped
        channel.trySend(MediaEvent.Cryptor(direction, mapped))
    }

    /** Runs [block] on the worker thread without blocking the caller's. */
    private suspend fun <T> on(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        try {
            check(!closed.get()) { "the call's media is closed" }
            worker.execute { continuation.resumeWith(runCatching(block)) }
        } catch (e: IllegalStateException) {
            continuation.resumeWith(Result.failure(e))
        } catch (e: RejectedExecutionException) {
            continuation.resumeWith(Result.failure(IllegalStateException("the call's media is closed", e)))
        }
    }

    private fun onWorker(block: () -> Unit) {
        if (!closed.get()) worker.execute { runCatching(block) }
    }

    private class MediaException(what: String) : IllegalStateException(what)

    private fun describing(created: CompletableDeferred<SessionDescription>) =
        Sdp(onCreated = { created.complete(it) }, onError = { created.completeExceptionally(MediaException(it)) })

    private class Sdp(
        private val onCreated: (SessionDescription) -> Unit = {},
        private val onSet: () -> Unit = {},
        private val onError: (String) -> Unit,
    ) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = onCreated(description)
        override fun onSetSuccess() = onSet()

        // The stack's text may quote the SDP: only that it failed goes on.
        override fun onCreateFailure(error: String) = onError("the description could not be made")
        override fun onSetFailure(error: String) = onError("the description was refused")
    }

    @Suppress("TooManyFunctions") // The stack's observer interface: most of it is not used by an audio-only call.
    private inner class Events : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            val mapped = when (state) {
                PeerConnection.PeerConnectionState.NEW -> MediaConnection.NEW
                PeerConnection.PeerConnectionState.CONNECTING -> MediaConnection.CONNECTING
                PeerConnection.PeerConnectionState.CONNECTED -> MediaConnection.CONNECTED
                PeerConnection.PeerConnectionState.DISCONNECTED -> MediaConnection.DISCONNECTED
                PeerConnection.PeerConnectionState.FAILED -> MediaConnection.FAILED
                PeerConnection.PeerConnectionState.CLOSED -> MediaConnection.CLOSED
            }
            channel.trySend(MediaEvent.Connection(mapped))
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) channel.trySend(MediaEvent.GatheringDone(found))
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            found++
            channel.trySend(MediaEvent.LocalCandidate(CallCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)))
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    companion object {
        /**
         * What this media stack can do, in [MediaSuite.PREFERENCE] order: AES-256-GCM and AES-128-GCM frames. The
         * key provider takes the frame key's length since `io.github.webrtc-sdk:android` 150.7871.04
         * (webrtc-sdk/webrtc PR #302), and [keyProvider] passes each suite's ([frameKeyBits]). Both derivations and
         * the frame format are checked against the native library on phones (`FrameFormatDeviceTest`).
         *
         * What a call **offers** and **chooses** is this list cut down further by the call's form (VAULT-MESSAGING
         * §10.10 "Call forms", rules 1 and 3): every `call.start` that carries form 1 offers suite 1 only
         * (`MediaSuite.formOne`, `CallSignalling.offerable`), until form 1 is retired.
         */
        val SUITES: List<MediaSuite> = listOf(MediaSuite.AES_256_GCM, MediaSuite.AES_128_GCM)

        /** Decryption failures after which the receiver reports DECRYPTION_FAILED (about a fifth of a second of audio). */
        const val FAILURE_TOLERANCE = 10
        private const val KEY_RING = 1
        private const val AES_128_BITS = 128
        private const val AES_256_BITS = 256
        private const val BYTE_MASK = 0xFF
        private const val STREAM_ID = "vettid"
        private const val TRACK_ID = "audio0"
        private const val SEND_ID = "send"
        private const val RECEIVE_ID = "receive"

        /**
         * Per-participant keys (one per direction), HKDF with the suite's frame label as salt, no automatic
         * ratchet, no "unencrypted" magic trailer (a frame that does not decrypt is never played), a key ring of
         * one, and the suite's frame-key length (docs/calls/CALL-MEDIA.md §4).
         */
        internal fun keyProvider(suite: MediaSuite): FrameCryptorKeyProvider {
            check(suite in SUITES) { "suite ${suite.id}" }
            return FrameCryptorFactory.createFrameCryptorKeyProvider(
                false,
                suite.frameLabel.toByteArray(Charsets.US_ASCII),
                0,
                ByteArray(0),
                FAILURE_TOLERANCE,
                KEY_RING,
                true,
                FrameCryptorKeyDerivationAlgorithm.HKDF,
                frameKeyBits(suite),
            )
        }

        /**
         * The key provider's `keySize` for [suite]: its frame key's length in bits. Only 128 and 256 pass: the
         * library takes **any other value as 128 without a word** (`KeyProviderOptions::frame_key_bits`), so no
         * value it would quietly turn into another suite ever reaches it.
         */
        internal fun frameKeyBits(suite: MediaSuite): Int {
            val bits = suite.frameKeySize * Byte.SIZE_BITS
            check(bits == AES_128_BITS || bits == AES_256_BITS) { "suite ${suite.id}: a frame key of $bits bits" }
            return bits
        }

        /**
         * The peer connection's configuration for one call's [servers]. TURN ports are pruned by priority: once a
         * relay allocation on a network is ready, the lower-priority ones on that network (one per TURN URL) are
         * released instead of being kept for the whole call, since the TURN server reserves capacity for each.
         */
        internal fun configuration(servers: List<CallWire.IceServer>) = PeerConnection.RTCConfiguration(
            servers.map { s ->
                PeerConnection.IceServer.builder(s.urls).apply {
                    if (s.username != null && s.credential != null) setUsername(s.username).setPassword(s.credential)
                }.createIceServer()
            },
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // Not GATHER_ONCE: a round that completes before the phone's network has reported its addresses would
            // be the only round and the call would have none. Continual gathering gathers again when a network
            // appears or changes (which is also what a call that moves from Wi-Fi to mobile data needs).
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // ICE over TCP between two phones is of no use; relayed candidates (TURN over TCP or TLS) are not affected.
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            turnPortPrunePolicy = PeerConnection.PortPrunePolicy.PRUNE_BASED_ON_PRIORITY
        }

        /**
         * FOR THE DEVICE TEST ONLY: an engine on a stack the test built itself (one whose packets it can read).
         * Nothing in the app calls it; [factory] is the only way an app gets an engine.
         */
        @VisibleForTesting
        internal fun onStack(stack: PeerConnectionFactory, audioDevice: JavaAudioDeviceModule): WebRtcMediaEngine =
            WebRtcMediaEngine(stack, audioDevice, false, MediaFaults())

        /** A factory of engines for [context]'s process. Creating an engine loads the native library if needed. */
        fun factory(context: Context, testBuild: Boolean = false): MediaEngineFactory = object : MediaEngineFactory {
            override val suites: List<MediaSuite> = SUITES

            override fun create(config: MediaConfig): MediaEngine {
                // The check next to the dangerous code: whatever asked, a build that was not made for the phone
                // tests makes no engine that breaks a rule on purpose or uses the experimental DTLS trial.
                val plain = !config.faults.any && !config.dtlsPqcTrial
                check(testBuild || plain) { "a test configuration in a build that is not a test build" }
                val app = context.applicationContext
                val pqc = WebRtcRuntime.start(app, config)
                val stack = SharedStack.get(app)
                return WebRtcMediaEngine(stack.factory, stack.audioDevice, pqc, config.faults, sharedStack = true)
            }
        }
    }
}
