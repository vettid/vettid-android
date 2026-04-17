package com.vettid.app.features.calling

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * WebRTC client for peer-to-peer audio/video calls.
 *
 * Handles:
 * - PeerConnection lifecycle
 * - Audio/video track management
 * - ICE candidate gathering and exchange
 * - SDP offer/answer creation
 */
class WebRTCClient(
    private val context: Context,
    private val listener: WebRTCListener
) {
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoSource: VideoSource? = null
    private var eglBase: EglBase? = null

    private val _iceCandidates = MutableSharedFlow<IceCandidate>(extraBufferCapacity = 64)
    val iceCandidates: SharedFlow<IceCandidate> = _iceCandidates.asSharedFlow()

    private var isAudioEnabled = true
    private var isVideoEnabled = false
    private var isFrontCamera = true

    /**
     * Initialize the WebRTC peer connection factory.
     * Call this once when starting a call.
     */
    fun initialize() {
        Log.d(TAG, "Initializing WebRTC")

        // Initialize EGL for video rendering
        eglBase = EglBase.create()

        // Initialize PeerConnectionFactory
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        // Create audio device module
        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        // Create factory
        val factoryOptions = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(factoryOptions)
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(
                eglBase?.eglBaseContext,
                true,
                true
            ))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase?.eglBaseContext))
            .createPeerConnectionFactory()

        Log.d(TAG, "WebRTC initialized")
    }

    /**
     * Create a peer connection with ICE servers.
     */
    fun createPeerConnection(iceServers: List<PeerConnection.IceServer> = emptyList()): Boolean {
        val factory = peerConnectionFactory ?: run {
            Log.e(TAG, "PeerConnectionFactory not initialized")
            return false
        }

        val servers = if (iceServers.isNotEmpty()) {
            iceServers
        } else {
            // Fallback: our own STUN on the TURN host. With RELAY-only ICE,
            // STUN alone won't produce usable candidates — the call will fail
            // closed rather than leak to Google/Cloudflare STUN.
            listOf(PeerConnection.IceServer.builder("stun:turn.vettid.dev:3478").createIceServer())
        }

        val rtcConfig = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // PRIVACY: suppress LAN/host candidates so the peer can't see our
            // internal network topology. Server-reflexive (public IP via STUN)
            // and TURN-relay candidates are still allowed — required for calls
            // to connect on most real networks.
            //
            // We previously forced RELAY-only, but with both peers relaying
            // through the same coturn server libwebrtc sends
            // XOR_PEER_ADDRESS = 0.0.0.0:port in its CREATE_PERMISSION (an
            // anonymization side-effect of same-server relay), which coturn
            // rejects as "Forbidden IP" and ICE never completes. NOHOST keeps
            // the meaningful privacy win (LAN topology hidden) without that
            // edge case. Content confidentiality is unchanged — DTLS-SRTP +
            // our frame-level E2EE still protect media end-to-end; peer IPs
            // visible in srflx candidates don't weaken that.
            iceTransportsType = PeerConnection.IceTransportsType.NOHOST
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling state: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE connection state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        listener.onConnectionEstablished()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        listener.onConnectionDisconnected()
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        listener.onConnectionFailed("ICE connection failed")
                    }
                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "ICE receiving: $receiving")
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE gathering state: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    Log.d(TAG, "New ICE candidate: ${it.sdp}")
                    _iceCandidates.tryEmit(it)
                    listener.onIceCandidate(it)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                Log.d(TAG, "ICE candidates removed")
            }

            override fun onAddStream(stream: MediaStream?) {
                Log.d(TAG, "Stream added: ${stream?.id}")
            }

            override fun onRemoveStream(stream: MediaStream?) {
                Log.d(TAG, "Stream removed: ${stream?.id}")
            }

            override fun onDataChannel(channel: DataChannel?) {
                Log.d(TAG, "Data channel: ${channel?.label()}")
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed")
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.d(TAG, "Track added: ${receiver?.track()?.kind()}")
                receiver?.track()?.let { track ->
                    when (track) {
                        is VideoTrack -> listener.onRemoteVideoTrack(track)
                        is AudioTrack -> listener.onRemoteAudioTrack(track)
                    }
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                Log.d(TAG, "Transceiver track: ${transceiver?.receiver?.track()?.kind()}")
            }
        })

        return peerConnection != null
    }

    /**
     * Add local audio track to the connection.
     */
    fun addAudioTrack(): AudioTrack? {
        val factory = peerConnectionFactory ?: return null
        val pc = peerConnection ?: return null

        val audioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack("audio0", audioSource)
        localAudioTrack?.setEnabled(isAudioEnabled)

        pc.addTrack(localAudioTrack, listOf("stream0"))

        Log.d(TAG, "Audio track added")
        return localAudioTrack
    }

    /**
     * Add local video track to the connection.
     *
     * @param localRenderer Surface to render local video preview
     */
    fun addVideoTrack(localRenderer: SurfaceViewRenderer?): VideoTrack? {
        val factory = peerConnectionFactory ?: return null
        val pc = peerConnection ?: return null
        val egl = eglBase ?: return null

        // Create video capturer (camera)
        videoCapturer = createCameraCapturer(isFrontCamera)
        if (videoCapturer == null) {
            Log.e(TAG, "Failed to create camera capturer")
            return null
        }

        // Create video source
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", egl.eglBaseContext)
        localVideoSource = factory.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer?.initialize(surfaceTextureHelper, context, localVideoSource?.capturerObserver)

        // Start capture
        videoCapturer?.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)

        // Create video track
        localVideoTrack = factory.createVideoTrack("video0", localVideoSource)
        localVideoTrack?.setEnabled(isVideoEnabled)

        // Add to peer connection
        pc.addTrack(localVideoTrack, listOf("stream0"))

        // Add renderer for local preview
        localRenderer?.let { renderer ->
            renderer.init(egl.eglBaseContext, null)
            renderer.setMirror(isFrontCamera)
            localVideoTrack?.addSink(renderer)
        }

        Log.d(TAG, "Video track added")
        return localVideoTrack
    }

    /**
     * Create SDP offer (caller). Pass `wantsVideo=false` for voice-only calls
     * so we don't negotiate a video m-section (saves TURN bandwidth and avoids
     * an empty recvonly track on the peer).
     */
    fun createOffer(wantsVideo: Boolean = isVideoEnabled, callback: (SessionDescription?) -> Unit) {
        val pc = peerConnection ?: run {
            callback(null)
            return
        }

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", wantsVideo.toString()))
        }

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                Log.d(TAG, "Offer created")
                sdp?.let {
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local description set")
                            callback(sdp)
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Failed to set local description: $error")
                            callback(null)
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, it)
                }
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create offer: $error")
                callback(null)
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /**
     * Create SDP answer (callee). `wantsVideo` mirrors the caller's choice —
     * voice-only calls skip the video m-section.
     */
    fun createAnswer(wantsVideo: Boolean = isVideoEnabled, callback: (SessionDescription?) -> Unit) {
        val pc = peerConnection ?: run {
            callback(null)
            return
        }

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", wantsVideo.toString()))
        }

        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                Log.d(TAG, "Answer created")
                sdp?.let {
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local description set (answer)")
                            callback(sdp)
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Failed to set local description: $error")
                            callback(null)
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, it)
                }
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create answer: $error")
                callback(null)
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /**
     * Set remote SDP (offer from caller or answer from callee).
     */
    fun setRemoteDescription(sdp: SessionDescription, callback: (Boolean) -> Unit) {
        val pc = peerConnection ?: run {
            callback(false)
            return
        }

        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set")
                callback(true)
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote description: $error")
                callback(false)
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    /**
     * Add ICE candidate from peer.
     */
    fun addIceCandidate(candidate: IceCandidate): Boolean {
        return peerConnection?.addIceCandidate(candidate) ?: false
    }

    /**
     * Toggle audio mute.
     */
    fun setAudioEnabled(enabled: Boolean) {
        isAudioEnabled = enabled
        localAudioTrack?.setEnabled(enabled)
        Log.d(TAG, "Audio enabled: $enabled")
    }

    /**
     * Toggle video.
     */
    fun setVideoEnabled(enabled: Boolean) {
        isVideoEnabled = enabled
        localVideoTrack?.setEnabled(enabled)
        if (enabled) {
            videoCapturer?.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
        } else {
            videoCapturer?.stopCapture()
        }
        Log.d(TAG, "Video enabled: $enabled")
    }

    /**
     * Switch between front and back camera.
     */
    fun switchCamera() {
        videoCapturer?.let { capturer ->
            if (capturer is CameraVideoCapturer) {
                capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                    override fun onCameraSwitchDone(isFront: Boolean) {
                        isFrontCamera = isFront
                        listener.onCameraSwitched(isFront)
                        Log.d(TAG, "Camera switched to: ${if (isFront) "front" else "back"}")
                    }

                    override fun onCameraSwitchError(error: String?) {
                        Log.e(TAG, "Failed to switch camera: $error")
                    }
                })
            }
        }
    }

    /**
     * Get EGL context for video rendering.
     */
    fun getEglContext(): EglBase.Context? = eglBase?.eglBaseContext

    /**
     * Get the PeerConnectionFactory for frame encryption setup.
     */
    fun getFactory(): PeerConnectionFactory? = peerConnectionFactory

    /**
     * Get all RTP senders (for attaching frame encryptors to outgoing media).
     */
    fun getSenders(): List<RtpSender> {
        return peerConnection?.senders?.toList() ?: emptyList()
    }

    /**
     * Get all RTP receivers (for attaching frame decryptors to incoming media).
     */
    fun getReceivers(): List<RtpReceiver> {
        return peerConnection?.receivers?.toList() ?: emptyList()
    }

    /**
     * Clean up all resources.
     */
    fun dispose() {
        Log.d(TAG, "Disposing WebRTC client")

        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping video capturer", e)
        }

        localAudioTrack?.dispose()
        localVideoTrack?.dispose()
        localVideoSource?.dispose()
        surfaceTextureHelper?.dispose()

        peerConnection?.close()
        peerConnection?.dispose()

        peerConnectionFactory?.dispose()

        eglBase?.release()

        localAudioTrack = null
        localVideoTrack = null
        localVideoSource = null
        videoCapturer = null
        surfaceTextureHelper = null
        peerConnection = null
        peerConnectionFactory = null
        eglBase = null

        Log.d(TAG, "WebRTC client disposed")
    }

    private fun createCameraCapturer(useFrontCamera: Boolean): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        // Try to find preferred camera
        for (deviceName in deviceNames) {
            val isFront = enumerator.isFrontFacing(deviceName)
            if (isFront == useFrontCamera) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    return capturer
                }
            }
        }

        // Fallback to any available camera
        for (deviceName in deviceNames) {
            val capturer = enumerator.createCapturer(deviceName, null)
            if (capturer != null) {
                isFrontCamera = enumerator.isFrontFacing(deviceName)
                return capturer
            }
        }

        return null
    }

    companion object {
        private const val TAG = "WebRTCClient"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30
    }
}

/**
 * Listener for WebRTC events.
 */
interface WebRTCListener {
    fun onConnectionEstablished()
    fun onConnectionDisconnected()
    fun onConnectionFailed(error: String)
    fun onIceCandidate(candidate: IceCandidate)
    fun onRemoteVideoTrack(track: VideoTrack)
    fun onRemoteAudioTrack(track: AudioTrack)
    fun onCameraSwitched(isFrontCamera: Boolean)
}
