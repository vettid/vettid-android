package com.vettid.app.features.calling

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.vettid.app.core.nats.CallSignalingClient
import com.vettid.app.core.network.VaultServiceClient
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.webrtc.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages call lifecycle, WebRTC, and UI coordination.
 *
 * Responsibilities:
 * - Call state machine management
 * - WebRTC peer connection lifecycle
 * - Audio routing (speaker/earpiece)
 * - Ringtone and vibration
 * - Duration tracking
 */
@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callSignalingClient: CallSignalingClient,
    private val vaultServiceClient: VaultServiceClient,
    private val credentialStore: CredentialStore,
    private val feedRepository: com.vettid.app.features.feed.FeedRepository
) : WebRTCListener {

    /** Look up a peer's cached profile photo (base64 JPEG) by their GUID. */
    private fun peerPhotoFor(peerGuid: String): String? {
        if (peerGuid.isEmpty()) return null
        return feedRepository.getCachedConnections()
            .firstOrNull { it.peerGuid == peerGuid }
            ?.peerProfile
            ?.photo
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _showCallUI = MutableSharedFlow<CallUIEvent>(extraBufferCapacity = 1)
    val showCallUI: SharedFlow<CallUIEvent> = _showCallUI.asSharedFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private var webRTCClient: WebRTCClient? = null
    private var frameCryptor: CallFrameCryptor? = null
    private var durationJob: Job? = null
    private var ringtoneJob: Job? = null
    private var ringBackTone: ToneGenerator? = null
    private var ringBackJob: Job? = null

    // TURN credential cache. Parent issues 1h-TTL creds; we cache for 55 min so
    // we always refresh before the server rejects.
    private var cachedIceServers: List<PeerConnection.IceServer>? = null
    private var iceServerCacheExpiry: Long = 0

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    init {
        // Listen for call events from signaling
        scope.launch {
            callSignalingClient.callEvents.collect { event ->
                handleCallEvent(event)
            }
        }
    }

    // MARK: - Public API

    /**
     * Initiate an outgoing call via the user's vault.
     *
     * @param connectionId Connection ID (vault uses this to find the peer)
     * @param peerGuid Peer's GUID (for local state tracking)
     * @param displayName Peer's display name (for local UI)
     * @param callType Voice or video
     */
    suspend fun startCall(connectionId: String, peerGuid: String, displayName: String, callType: CallType): Result<Unit> {
        // Only reject if there's a *live* call (Active). Outgoing/Incoming/Ended
        // can happen to be stuck when the peer crashed, the user backed out of the
        // call screen, or the previous attempt never got a call.rejected/ended signal.
        // Force-clean transient states so a fresh call attempt always works.
        when (val state = _callState.value) {
            is CallState.Active -> {
                return Result.failure(IllegalStateException("Already in a call"))
            }
            is CallState.Outgoing, is CallState.Incoming -> {
                Log.w(TAG, "startCall: clearing stale $state before new call")
                forceResetCallState()
            }
            is CallState.Ended -> {
                _callState.value = CallState.Idle
                _showCallUI.emit(CallUIEvent.DismissCall)
            }
            is CallState.Idle -> { /* proceed */ }
        }

        Log.i(TAG, "Starting $callType call to $displayName (connection=$connectionId)")

        // Send call initiation to own vault — vault handles forwarding to peer
        val result = callSignalingClient.initiateCall(
            connectionId = connectionId,
            displayName = displayName,
            callType = callType,
            peerGuid = peerGuid
        )

        return result.map { rawCall ->
            // Enrich with the locally-cached peer photo so the outgoing call
            // screen matches what the user sees elsewhere in the app.
            val call = rawCall.copy(peerPhotoBase64 = peerPhotoFor(rawCall.peerGuid))
            // Initialize WebRTC after vault confirms call initiation
            initializeWebRTC()
            val iceServers = fetchIceServers()
            if (!webRTCClient!!.createPeerConnection(iceServers)) {
                disposeWebRTC()
                return Result.failure(IllegalStateException("Failed to create peer connection"))
            }

            webRTCClient!!.addAudioTrack()
            if (callType == CallType.VIDEO) {
                val videoTrack = webRTCClient!!.addVideoTrack(null)
                _localVideoTrack.value = videoTrack
            }

            _callState.value = CallState.Outgoing(call = call)
            _showCallUI.emit(CallUIEvent.ShowOutgoing(call))
            startRingBackTone()
            startRingingTimer()

            // Create SDP offer and send to peer via vault so the callee can set remote
            // description before its createAnswer runs.
            scope.launch {
                val offerDeferred = CompletableDeferred<SessionDescription?>()
                webRTCClient?.createOffer(wantsVideo = callType == CallType.VIDEO) { sdp ->
                    offerDeferred.complete(sdp)
                }
                val offer = offerDeferred.await()
                if (offer == null) {
                    Log.e(TAG, "Failed to create SDP offer for call ${call.callId}")
                    return@launch
                }
                callSignalingClient.sendOffer(call.callId, call.peerGuid, offer.description)
                    .onFailure { Log.e(TAG, "Failed to send SDP offer", it) }
            }

            // Collect ICE candidates and send to peer via vault
            scope.launch {
                webRTCClient?.iceCandidates?.collect { candidate ->
                    callSignalingClient.sendIceCandidate(
                        callId = call.callId,
                        peerGuid = call.peerGuid,
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex
                    )
                }
            }
        }
    }

    /**
     * Answer incoming call.
     */
    suspend fun answerCall(): Result<Unit> {
        val state = _callState.value
        if (state !is CallState.Incoming) {
            return Result.failure(IllegalStateException("No incoming call"))
        }

        Log.i(TAG, "Answering call ${state.call.callId}")

        stopRingtone()
        CallForegroundService.dismiss(context)

        // Initialize WebRTC if not already
        if (webRTCClient == null) {
            initializeWebRTC()
            val iceServers = fetchIceServers()
            if (!webRTCClient!!.createPeerConnection(iceServers)) {
                disposeWebRTC()
                return Result.failure(IllegalStateException("Failed to create peer connection"))
            }

            webRTCClient!!.addAudioTrack()
            if (state.call.callType == CallType.VIDEO) {
                val videoTrack = webRTCClient!!.addVideoTrack(null)
                _localVideoTrack.value = videoTrack
            }
        }

        // Wait for the remote SDP offer if it hasn't arrived yet. The caller sends
        // it on call.signal right after call.initiate, but the user may hit Answer
        // faster than the network round-trip.
        var resolvedOffer = (_callState.value as? CallState.Incoming)?.sdpOffer
        if (resolvedOffer == null) {
            Log.d(TAG, "Waiting for remote SDP offer...")
            val waitStart = System.currentTimeMillis()
            while (resolvedOffer == null && System.currentTimeMillis() - waitStart < 5000) {
                delay(100)
                resolvedOffer = (_callState.value as? CallState.Incoming)?.sdpOffer
            }
        }
        if (resolvedOffer == null) {
            // Don't dispose WebRTC — let the user retry once the offer arrives.
            return Result.failure(IllegalStateException("Remote offer not received yet"))
        }

        val setRemoteDeferred = CompletableDeferred<Boolean>()
        webRTCClient!!.setRemoteDescription(
            SessionDescription(SessionDescription.Type.OFFER, resolvedOffer)
        ) { success -> setRemoteDeferred.complete(success) }

        if (!setRemoteDeferred.await()) {
            // setRemoteDescription can fail if already set — that's fine; try to answer anyway.
            Log.w(TAG, "setRemoteDescription returned false (may already be set); continuing")
        }

        // Create SDP answer
        val answerDeferred = CompletableDeferred<SessionDescription?>()
        webRTCClient!!.createAnswer(wantsVideo = state.call.callType == CallType.VIDEO) { sdp ->
            answerDeferred.complete(sdp)
        }

        val sdpAnswer = answerDeferred.await()
        if (sdpAnswer == null) {
            // Leave WebRTC alive; a late offer or retry may recover.
            return Result.failure(IllegalStateException("Failed to create SDP answer"))
        }

        // Send answer via signaling (vault-routed to caller)
        val result = callSignalingClient.answerCall(
            callId = state.call.callId,
            callerGuid = state.call.peerGuid,
            sdpAnswer = sdpAnswer.description
        )

        return result.map { sharedSecret ->
            // Enable E2EE frame encryption with the vault-derived shared secret
            enableFrameEncryption(sharedSecret)

            val answeredCall = state.call.copy(answeredAt = System.currentTimeMillis())
            _callState.value = CallState.Active(
                call = answeredCall,
                isLocalVideoEnabled = state.call.callType == CallType.VIDEO
            )
            _showCallUI.emit(CallUIEvent.ShowActive(answeredCall))
            startDurationTimer(answeredCall)
            setupAudioForCall()

            // Note: the iceCandidates collector was started in
            // handleIncomingCall so candidates gathered while ringing aren't
            // dropped. No second collector here.
        }
    }

    /**
     * Reject incoming call.
     */
    suspend fun rejectCall(): Result<Unit> {
        val state = _callState.value
        if (state !is CallState.Incoming) {
            return Result.failure(IllegalStateException("No incoming call"))
        }

        Log.i(TAG, "Rejecting call ${state.call.callId}")

        stopRingtone()
        CallForegroundService.dismiss(context)
        disposeWebRTC()

        // Best-effort signaling — cleanup must happen even if this fails
        try {
            callSignalingClient.rejectCall(state.call.callId, state.call.peerGuid)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to signal call rejection to peer (cleaning up locally)", e)
        }

        _callState.value = CallState.Ended(state.call, CallEndReason.REJECTED)
        delay(500)
        _callState.value = CallState.Idle
        _showCallUI.emit(CallUIEvent.DismissCall)
        return Result.success(Unit)
    }

    /**
     * Force all call resources back to Idle without sending signaling.
     * Used when starting a new call finds the previous one stuck (peer crash,
     * dropped network, user bypassed the End button, etc.).
     */
    private suspend fun forceResetCallState() {
        stopDurationTimer()
        stopRingtone()
        CallForegroundService.dismiss(context)
        resetAudio()
        disposeWebRTC()
        _callState.value = CallState.Idle
        _showCallUI.emit(CallUIEvent.DismissCall)
    }

    /**
     * End active call.
     */
    suspend fun endCall(): Result<Unit> {
        val state = _callState.value
        val call = when (state) {
            is CallState.Active -> state.call
            is CallState.Outgoing -> state.call
            is CallState.Incoming -> state.call
            else -> {
                // Force reset to Idle if somehow stuck in Ended state
                if (state is CallState.Ended) {
                    _callState.value = CallState.Idle
                    _showCallUI.emit(CallUIEvent.DismissCall)
                }
                return Result.failure(IllegalStateException("No active call"))
            }
        }

        Log.i(TAG, "Ending call ${call.callId}")

        stopDurationTimer()
        stopRingtone()
        CallForegroundService.dismiss(context)

        // Flip to Ended immediately so the UI dismisses without waiting on signaling.
        val duration = when (state) {
            is CallState.Active -> state.duration
            else -> 0
        }
        _callState.value = CallState.Ended(call, CallEndReason.COMPLETED, duration)
        resetAudio()
        disposeWebRTC()

        // Signal end to peer in the background — we don't want the user waiting on it.
        scope.launch {
            try {
                callSignalingClient.endCall(call.callId, call.peerGuid)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to signal call end to peer", e)
            }
        }

        // Brief pause so the "Call ended" state is visible, then Idle.
        delay(500)
        _callState.value = CallState.Idle
        _showCallUI.emit(CallUIEvent.DismissCall)
        return Result.success(Unit)
    }

    /**
     * Toggle audio mute. Works in both Outgoing (pre-connect) and Active states.
     */
    fun toggleMute() {
        val state = _callState.value
        when (state) {
            is CallState.Active -> {
                val newMuted = !state.isMuted
                _callState.value = state.copy(isMuted = newMuted)
                webRTCClient?.setAudioEnabled(!newMuted)
                Log.d(TAG, "Mute toggled: $newMuted")
            }
            is CallState.Outgoing -> {
                // Mute mic before call connects
                val isMuted = audioManager.isMicrophoneMute
                audioManager.isMicrophoneMute = !isMuted
                webRTCClient?.setAudioEnabled(isMuted)
                Log.d(TAG, "Pre-connect mute toggled: ${!isMuted}")
            }
            else -> {}
        }
    }

    /**
     * Toggle speaker. Works in both Outgoing and Active states.
     */
    fun toggleSpeaker() {
        val state = _callState.value
        when (state) {
            is CallState.Active -> {
                val newSpeaker = !state.isSpeakerOn
                _callState.value = state.copy(isSpeakerOn = newSpeaker)
                setSpeakerphone(newSpeaker)
                Log.d(TAG, "Speaker toggled: $newSpeaker")
            }
            is CallState.Outgoing -> {
                // Toggle speaker during ring-back
                val currentSpeaker = audioManager.isSpeakerphoneOn
                setSpeakerphone(!currentSpeaker)
                Log.d(TAG, "Pre-connect speaker toggled: ${!currentSpeaker}")
            }
            else -> {}
        }
    }

    /**
     * Get list of available audio output devices.
     */
    fun getAudioOutputDevices(): List<AudioDeviceInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
    }

    /**
     * Set active audio output device.
     */
    fun setAudioOutputDevice(device: AudioDeviceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.setCommunicationDevice(device)
            Log.d(TAG, "Audio routed to: ${device.type}")
        }
    }

    /**
     * Toggle local video.
     */
    suspend fun toggleVideo() {
        val state = _callState.value
        if (state is CallState.Active) {
            val newEnabled = !state.isLocalVideoEnabled
            _callState.value = state.copy(isLocalVideoEnabled = newEnabled)
            webRTCClient?.setVideoEnabled(newEnabled)

            // Notify peer (vault-routed)
            callSignalingClient.sendVideoState(state.call.callId, state.call.peerGuid, newEnabled)
            Log.d(TAG, "Video toggled: $newEnabled")
        }
    }

    /**
     * Switch camera.
     */
    fun switchCamera() {
        webRTCClient?.switchCamera()
    }

    /**
     * Get EGL context for video rendering.
     */
    fun getEglContext(): EglBase.Context? = webRTCClient?.getEglContext()

    // MARK: - WebRTCListener
    // Note: onConnectionEstablished is defined further down with media-state handling.

    override fun onConnectionDisconnected() {
        Log.d(TAG, "WebRTC connection disconnected")
    }

    override fun onConnectionFailed(error: String) {
        Log.e(TAG, "WebRTC connection failed: $error")
        scope.launch {
            val state = _callState.value
            val call = when (state) {
                is CallState.Active -> state.call
                is CallState.Outgoing -> state.call
                is CallState.Incoming -> state.call
                else -> return@launch
            }

            // Tell the peer the call is over so they don't sit in "Calling…"
            // or a false "Connected" state. Best-effort, fire-and-forget.
            scope.launch {
                try {
                    callSignalingClient.endCall(call.callId, call.peerGuid)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to signal end after ICE failure", e)
                }
            }

            _callState.value = CallState.Ended(call, CallEndReason.FAILED)
            CallForegroundService.dismiss(context)
            resetAudio()
            disposeWebRTC()
            delay(1500)
            _callState.value = CallState.Idle
            _showCallUI.emit(CallUIEvent.DismissCall)
        }
    }

    override fun onConnectionEstablished() {
        Log.d(TAG, "WebRTC connection established — media flowing")
        scope.launch {
            val state = _callState.value
            if (state is CallState.Active && !state.isMediaConnected) {
                _callState.value = state.copy(isMediaConnected = true)
            }
        }
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        // Handled via iceCandidates flow collection
    }

    override fun onRemoteVideoTrack(track: VideoTrack) {
        Log.d(TAG, "Remote video track received")
        _remoteVideoTrack.value = track

        val state = _callState.value
        if (state is CallState.Active) {
            _callState.value = state.copy(isRemoteVideoEnabled = true)
        }
    }

    override fun onRemoteAudioTrack(track: AudioTrack) {
        Log.d(TAG, "Remote audio track received")
    }

    override fun onCameraSwitched(isFrontCamera: Boolean) {
        val state = _callState.value
        if (state is CallState.Active) {
            _callState.value = state.copy(isFrontCamera = isFrontCamera)
        }
    }

    // MARK: - ICE Server Management

    /**
     * Fetch VettID TURN/STUN credentials for WebRTC from the parent (via the
     * vault). Returns cached credentials if still valid.
     *
     * Fallback is STUN on our own relay host. With RELAY-only ICE enabled on
     * the peer connection, a missing TURN credential will fail the call
     * rather than leak host candidates — which is the correct privacy
     * tradeoff here.
     */
    private suspend fun fetchIceServers(): List<PeerConnection.IceServer> {
        // Check cache
        val now = System.currentTimeMillis()
        cachedIceServers?.let { cached ->
            if (now < iceServerCacheExpiry) {
                Log.d(TAG, "Using cached TURN credentials (${(iceServerCacheExpiry - now) / 1000}s remaining)")
                return cached
            }
        }

        val fallback = listOf(
            PeerConnection.IceServer.builder("stun:turn.vettid.dev:3478").createIceServer()
        )

        // Fetch via the vault over NATS (uses the existing authenticated
        // vault session — no Cognito JWT required).
        return try {
            val result = callSignalingClient.getTurnCredentials()
            val response = result.getOrNull() ?: run {
                Log.w(TAG, "TURN credential fetch via vault returned null, using STUN fallback", result.exceptionOrNull())
                return fallback
            }

            val servers = response.iceServers.flatMap { config ->
                config.urls.map { url ->
                    val builder = PeerConnection.IceServer.builder(url)
                    if (config.username != null && config.credential != null) {
                        builder.setUsername(config.username)
                        builder.setPassword(config.credential)
                    }
                    builder.createIceServer()
                }
            }

            if (servers.isNotEmpty()) {
                cachedIceServers = servers
                // 55 min — credentials expire at 60 min.
                iceServerCacheExpiry = now + (55 * 60 * 1000L)
                Log.i(TAG, "Fetched ${servers.size} ICE servers (STUN + TURN)")
                servers
            } else {
                Log.w(TAG, "TURN response had no servers, using STUN fallback")
                fallback
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch TURN credentials, using STUN fallback", e)
            fallback
        }
    }

    // MARK: - Private Methods

    private fun handleCallEvent(event: CallEvent) {
        scope.launch {
            when (event) {
                is CallEvent.IncomingCall -> handleIncomingCall(event)
                is CallEvent.RemoteOffer -> handleRemoteOffer(event)
                is CallEvent.CallAnswered -> handleCallAnswered(event)
                is CallEvent.CallRejected -> handleCallRejected(event)
                is CallEvent.CallEnded -> handleCallEnded(event)
                is CallEvent.CallFailed -> handleCallFailed(event)
                is CallEvent.IceCandidate -> handleRemoteIceCandidate(event)
                is CallEvent.RemoteVideoChanged -> handleRemoteVideoChanged(event)
            }
        }
    }

    private suspend fun handleRemoteOffer(event: CallEvent.RemoteOffer) {
        val state = _callState.value
        if (state !is CallState.Incoming || state.call.callId != event.callId) {
            Log.d(TAG, "Ignoring RemoteOffer for ${event.callId} (state=$state)")
            return
        }
        if (event.sdpOffer.isEmpty()) {
            Log.w(TAG, "RemoteOffer for ${event.callId} has empty SDP; ignoring")
            return
        }
        // Always stash the offer so a later answerCall can apply it even if WebRTC
        // isn't up yet (e.g. a premature Answer tap disposed it).
        _callState.value = state.copy(sdpOffer = event.sdpOffer)

        val pc = webRTCClient
        if (pc == null) {
            Log.d(TAG, "RemoteOffer stashed for ${event.callId}; WebRTC not ready")
            return
        }
        val setDeferred = CompletableDeferred<Boolean>()
        pc.setRemoteDescription(
            SessionDescription(SessionDescription.Type.OFFER, event.sdpOffer)
        ) { success -> setDeferred.complete(success) }
        if (setDeferred.await()) {
            Log.d(TAG, "Remote SDP offer set for ${event.callId}")
        } else {
            Log.e(TAG, "Failed to set remote SDP offer for ${event.callId}")
        }
    }

    private suspend fun handleIncomingCall(event: CallEvent.IncomingCall) {
        when (val cur = _callState.value) {
            is CallState.Incoming -> {
                if (cur.call.callId == event.callId) {
                    Log.d(TAG, "Ignoring duplicate incoming for ${event.callId}")
                    return
                }
                if (cur.call.peerGuid == event.peerGuid) {
                    // Same peer dialed twice in quick succession (UI double-tap,
                    // app retry). Stick with the FIRST callId so the offer +
                    // ICE candidates that arrive for it actually match state.
                    Log.d(TAG, "Ignoring repeat incoming from same peer ${event.peerGuid} (have ${cur.call.callId}, got ${event.callId})")
                    return
                }
                // Different peer trying to call while we're ringing — reject as busy
                callSignalingClient.rejectCall(event.callId, event.peerGuid, "busy")
                return
            }
            is CallState.Outgoing -> {
                if (cur.call.callId == event.callId) return
                callSignalingClient.rejectCall(event.callId, event.peerGuid, "busy")
                return
            }
            is CallState.Active -> {
                if (cur.call.callId == event.callId) return
                callSignalingClient.rejectCall(event.callId, event.peerGuid, "busy")
                return
            }
            is CallState.Ended -> {
                Log.w(TAG, "handleIncomingCall: clearing stale Ended state before ringing")
                forceResetCallState()
            }
            is CallState.Idle -> {}
        }

        Log.i(TAG, "Incoming ${event.callType} call from ${event.peerDisplayName}")

        // Initialize WebRTC early to prepare for answering
        initializeWebRTC()
        val iceServers = fetchIceServers()
        webRTCClient?.createPeerConnection(iceServers)

        val call = Call(
            callId = event.callId,
            connectionId = event.connectionId,
            peerGuid = event.peerGuid,
            peerDisplayName = event.peerDisplayName,
            peerAvatarUrl = event.peerAvatarUrl,
            peerPhotoBase64 = peerPhotoFor(event.peerGuid),
            callType = event.callType,
            direction = CallDirection.INCOMING,
            initiatedAt = System.currentTimeMillis()
        )

        _callState.value = CallState.Incoming(
            call = call,
            sdpOffer = event.sdpOffer
        )
        _showCallUI.emit(CallUIEvent.ShowIncoming(call))
        startRingtone()

        // Start streaming local ICE candidates to the caller IMMEDIATELY.
        // ICE gathering kicks off as soon as createPeerConnection runs above,
        // and MutableSharedFlow has no replay — candidates emitted before any
        // collector subscribes are dropped on the floor. Previously the
        // collect was inside answerCall(), which only ran after the user
        // tapped Accept and the answer round-tripped through the vault, so
        // every host/srflx/relay candidate gathered in those first seconds
        // never reached the caller. ICE then never had enough to pair.
        scope.launch {
            webRTCClient?.iceCandidates?.collect { candidate ->
                callSignalingClient.sendIceCandidate(
                    callId = call.callId,
                    peerGuid = call.peerGuid,
                    candidate = candidate.sdp,
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex,
                )
            }
        }

        // Show foreground service notification for incoming call
        CallForegroundService.showIncomingCall(
            context,
            call.peerDisplayName,
            call.callType.name,
            call.callId
        )
    }

    private suspend fun handleCallAnswered(event: CallEvent.CallAnswered) {
        val state = _callState.value
        if (state !is CallState.Outgoing || state.call.callId != event.callId) {
            return
        }

        Log.i(TAG, "Call answered by peer")

        stopRingtone()
        CallForegroundService.dismiss(context)

        // Set remote SDP answer
        event.sdpAnswer?.let { answer ->
            val setRemoteDeferred = CompletableDeferred<Boolean>()
            webRTCClient?.setRemoteDescription(
                SessionDescription(SessionDescription.Type.ANSWER, answer)
            ) { success ->
                setRemoteDeferred.complete(success)
            }

            if (!setRemoteDeferred.await()) {
                Log.e(TAG, "Failed to set remote SDP answer")
            }
        }

        // Enable E2EE frame encryption if shared secret is available
        enableFrameEncryption(event.sharedSecret)

        val answeredCall = state.call.copy(answeredAt = event.answeredAt)
        _callState.value = CallState.Active(
            call = answeredCall,
            isLocalVideoEnabled = state.call.callType == CallType.VIDEO
        )
        _showCallUI.emit(CallUIEvent.ShowActive(answeredCall))
        startDurationTimer(answeredCall)
        setupAudioForCall()
    }

    private suspend fun handleCallRejected(event: CallEvent.CallRejected) {
        val state = _callState.value
        if (state !is CallState.Outgoing || state.call.callId != event.callId) {
            return
        }

        Log.i(TAG, "Call rejected by peer")

        stopRingtone()
        CallForegroundService.dismiss(context)
        disposeWebRTC()

        _callState.value = CallState.Ended(state.call, CallEndReason.REJECTED)
        delay(1500)
        _callState.value = CallState.Idle
        _showCallUI.emit(CallUIEvent.DismissCall)
    }

    private suspend fun handleCallEnded(event: CallEvent.CallEnded) {
        val state = _callState.value
        val call = when (state) {
            is CallState.Active -> if (state.call.callId == event.callId) state.call else null
            is CallState.Outgoing -> if (state.call.callId == event.callId) state.call else null
            is CallState.Incoming -> if (state.call.callId == event.callId) state.call else null
            else -> null
        } ?: return

        Log.i(TAG, "Call ended: ${event.reason}")

        stopDurationTimer()
        stopRingtone()
        CallForegroundService.dismiss(context)
        resetAudio()
        disposeWebRTC()

        _callState.value = CallState.Ended(call, event.reason, event.duration)
        delay(1500)
        _callState.value = CallState.Idle
        _showCallUI.emit(CallUIEvent.DismissCall)
    }

    private suspend fun handleCallFailed(event: CallEvent.CallFailed) {
        val state = _callState.value
        val call = when (state) {
            is CallState.Active -> if (state.call.callId == event.callId) state.call else null
            is CallState.Outgoing -> if (state.call.callId == event.callId) state.call else null
            else -> null
        } ?: return

        Log.e(TAG, "Call failed: ${event.error}")

        stopDurationTimer()
        stopRingtone()
        CallForegroundService.dismiss(context)
        resetAudio()
        disposeWebRTC()

        _callState.value = CallState.Ended(call, CallEndReason.FAILED)
        delay(1500)
        _callState.value = CallState.Idle
        _showCallUI.emit(CallUIEvent.DismissCall)
    }

    private fun handleRemoteIceCandidate(event: CallEvent.IceCandidate) {
        val candidate = IceCandidate(
            event.sdpMid ?: "",
            event.sdpMLineIndex ?: 0,
            event.candidate
        )
        val added = webRTCClient?.addIceCandidate(candidate) ?: false
        Log.d(TAG, "addRemoteIceCandidate: added=$added sdpMid=${event.sdpMid} sdpMLineIndex=${event.sdpMLineIndex} candidate=${event.candidate.take(80)}")
    }

    private fun handleRemoteVideoChanged(event: CallEvent.RemoteVideoChanged) {
        val state = _callState.value
        if (state is CallState.Active && state.call.callId == event.callId) {
            _callState.value = state.copy(isRemoteVideoEnabled = event.enabled)
        }
    }

    private fun initializeWebRTC() {
        if (webRTCClient == null) {
            webRTCClient = WebRTCClient(context, this)
            webRTCClient?.initialize()
        }
    }

    /**
     * Enable E2EE frame encryption using the vault-derived shared secret.
     * Encrypts all outgoing frames and decrypts all incoming frames with AES-GCM.
     * The shared secret is derived via X25519 + HKDF in the vault, independently on both sides.
     */
    private fun enableFrameEncryption(sharedSecretBase64: String?) {
        if (sharedSecretBase64 == null) {
            Log.w(TAG, "No shared secret available — call will use DTLS-SRTP only (no E2EE frame encryption)")
            return
        }

        val client = webRTCClient ?: return
        val factory = client.getFactory() ?: return

        try {
            val sharedSecret = android.util.Base64.decode(sharedSecretBase64, android.util.Base64.DEFAULT)
            if (sharedSecret.size != 32) {
                Log.e(TAG, "Invalid shared secret size: ${sharedSecret.size} (expected 32)")
                sharedSecret.fill(0)
                return
            }

            frameCryptor = CallFrameCryptor(factory, sharedSecret, "local")
            sharedSecret.fill(0) // Zeroize after passing to cryptor

            // Encrypt outgoing media
            frameCryptor?.enableForSenders(client.getSenders())

            // Decrypt incoming media
            frameCryptor?.enableForReceivers(client.getReceivers())

            Log.i(TAG, "E2EE frame encryption ENABLED — media is end-to-end encrypted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable E2EE frame encryption — falling back to DTLS-SRTP only", e)
            frameCryptor?.dispose()
            frameCryptor = null
        }
    }

    private fun disposeWebRTC() {
        _remoteVideoTrack.value = null
        _localVideoTrack.value = null
        frameCryptor?.dispose()
        frameCryptor = null
        webRTCClient?.dispose()
        webRTCClient = null
    }

    private fun startDurationTimer(call: Call) {
        durationJob?.cancel()
        durationJob = scope.launch {
            var duration = 0L
            while (isActive) {
                delay(1000)
                val state = _callState.value
                // Only count once media is actually connected — the UI shows
                // "Connecting…" until then, and we don't want a 30s "duration"
                // when ICE checks dragged on. Exit when we leave Active.
                when {
                    state !is CallState.Active -> break
                    !state.isMediaConnected -> continue
                    else -> {
                        duration++
                        _callState.value = state.copy(duration = duration)
                    }
                }
            }
        }
    }

    private fun stopDurationTimer() {
        durationJob?.cancel()
        durationJob = null
    }

    private fun startRingingTimer() {
        ringtoneJob = scope.launch {
            var ringDuration = 0L
            while (isActive && ringDuration < 60) { // 60 second timeout
                delay(1000)
                ringDuration++
                val state = _callState.value
                if (state is CallState.Outgoing) {
                    _callState.value = state.copy(ringingDuration = ringDuration)
                } else {
                    break
                }
            }

            // Timeout if still in outgoing state
            val state = _callState.value
            if (state is CallState.Outgoing) {
                Log.i(TAG, "Call timed out")
                callSignalingClient.endCall(state.call.callId, state.call.peerGuid)
                CallForegroundService.dismiss(context)
                disposeWebRTC()
                _callState.value = CallState.Ended(state.call, CallEndReason.TIMEOUT)
                delay(1500)
                _callState.value = CallState.Idle
                _showCallUI.emit(CallUIEvent.DismissCall)
            }
        }
    }

    private fun startRingtone() {
        // Vibrate for incoming call
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 500, 500),
                    0 // repeat indefinitely
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 500, 500), 0)
        }
    }

    /**
     * Play ring-back tone (what the caller hears while waiting for answer).
     * Uses standard telephony ring-back cadence: 2s on, 4s off.
     */
    private fun startRingBackTone() {
        stopRingBackTone()
        try {
            ringBackTone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create ring-back tone generator", e)
            return
        }
        ringBackJob = scope.launch {
            while (isActive) {
                try {
                    ringBackTone?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 2000)
                    delay(6000) // 2s tone + 4s silence
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    private fun stopRingBackTone() {
        ringBackJob?.cancel()
        ringBackJob = null
        try {
            ringBackTone?.stopTone()
            ringBackTone?.release()
        } catch (_: Exception) {}
        ringBackTone = null
    }

    private fun stopRingtone() {
        ringtoneJob?.cancel()
        ringtoneJob = null
        vibrator?.cancel()
        stopRingBackTone()
    }

    private fun setupAudioForCall() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeakerphone(false)
    }

    private fun resetAudio() {
        audioManager.mode = AudioManager.MODE_NORMAL
        setSpeakerphone(false)
        audioManager.isMicrophoneMute = false
    }

    private fun setSpeakerphone(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            if (enabled) {
                val speaker = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) audioManager.setCommunicationDevice(speaker)
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = enabled
        }
    }

    companion object {
        private const val TAG = "CallManager"
    }
}
