package com.vettid.app.features.calling

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.vettid.app.core.nats.CallSignalingClient
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
    private val callSignalingClient: CallSignalingClient
) : WebRTCListener {

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
    private var durationJob: Job? = null
    private var ringtoneJob: Job? = null

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
     * Initiate an outgoing call.
     *
     * @param connectionId Connection to call
     * @param callType Voice or video
     */
    suspend fun startCall(connectionId: String, callType: CallType): Result<Unit> {
        if (_callState.value !is CallState.Idle) {
            return Result.failure(IllegalStateException("Already in a call"))
        }

        Log.i(TAG, "Starting $callType call to $connectionId")

        // Initialize WebRTC
        initializeWebRTC()

        // Create peer connection and add tracks
        if (!webRTCClient!!.createPeerConnection()) {
            disposeWebRTC()
            return Result.failure(IllegalStateException("Failed to create peer connection"))
        }

        webRTCClient!!.addAudioTrack()
        if (callType == CallType.VIDEO) {
            val videoTrack = webRTCClient!!.addVideoTrack(null)
            _localVideoTrack.value = videoTrack
        }

        // Create SDP offer
        val sdpDeferred = CompletableDeferred<SessionDescription?>()
        webRTCClient!!.createOffer { sdp ->
            sdpDeferred.complete(sdp)
        }

        val sdpOffer = sdpDeferred.await()
        if (sdpOffer == null) {
            disposeWebRTC()
            return Result.failure(IllegalStateException("Failed to create SDP offer"))
        }

        // Send call initiation via signaling
        val result = callSignalingClient.initiateCall(
            connectionId = connectionId,
            callType = callType,
            sdpOffer = sdpOffer.description
        )

        return result.map { call ->
            _callState.value = CallState.Outgoing(
                call = call,
                sdpOffer = sdpOffer.description
            )
            _showCallUI.emit(CallUIEvent.ShowOutgoing(call))
            startRingingTimer()

            // Collect ICE candidates and send to peer
            scope.launch {
                webRTCClient?.iceCandidates?.collect { candidate ->
                    callSignalingClient.sendIceCandidate(
                        callId = call.callId,
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

        // Initialize WebRTC if not already
        if (webRTCClient == null) {
            initializeWebRTC()
            if (!webRTCClient!!.createPeerConnection()) {
                disposeWebRTC()
                return Result.failure(IllegalStateException("Failed to create peer connection"))
            }

            webRTCClient!!.addAudioTrack()
            if (state.call.callType == CallType.VIDEO) {
                val videoTrack = webRTCClient!!.addVideoTrack(null)
                _localVideoTrack.value = videoTrack
            }
        }

        // Set remote SDP offer
        state.sdpOffer?.let { offer ->
            val setRemoteDeferred = CompletableDeferred<Boolean>()
            webRTCClient!!.setRemoteDescription(
                SessionDescription(SessionDescription.Type.OFFER, offer)
            ) { success ->
                setRemoteDeferred.complete(success)
            }

            if (!setRemoteDeferred.await()) {
                disposeWebRTC()
                return Result.failure(IllegalStateException("Failed to set remote SDP"))
            }
        }

        // Create SDP answer
        val answerDeferred = CompletableDeferred<SessionDescription?>()
        webRTCClient!!.createAnswer { sdp ->
            answerDeferred.complete(sdp)
        }

        val sdpAnswer = answerDeferred.await()
        if (sdpAnswer == null) {
            disposeWebRTC()
            return Result.failure(IllegalStateException("Failed to create SDP answer"))
        }

        // Send answer via signaling
        val result = callSignalingClient.answerCall(
            callId = state.call.callId,
            sdpAnswer = sdpAnswer.description
        )

        return result.map {
            val answeredCall = state.call.copy(answeredAt = System.currentTimeMillis())
            _callState.value = CallState.Active(
                call = answeredCall,
                isLocalVideoEnabled = state.call.callType == CallType.VIDEO
            )
            _showCallUI.emit(CallUIEvent.ShowActive(answeredCall))
            startDurationTimer(answeredCall)
            setupAudioForCall()

            // Collect ICE candidates
            scope.launch {
                webRTCClient?.iceCandidates?.collect { candidate ->
                    callSignalingClient.sendIceCandidate(
                        callId = answeredCall.callId,
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex
                    )
                }
            }
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
        disposeWebRTC()

        val result = callSignalingClient.rejectCall(state.call.callId)

        return result.map {
            _callState.value = CallState.Ended(state.call, CallEndReason.REJECTED)
            delay(1500)
            _callState.value = CallState.Idle
            _showCallUI.emit(CallUIEvent.DismissCall)
        }
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
            else -> return Result.failure(IllegalStateException("No active call"))
        }

        Log.i(TAG, "Ending call ${call.callId}")

        stopDurationTimer()
        stopRingtone()

        val result = callSignalingClient.endCall(call.callId)

        return result.map {
            val duration = when (state) {
                is CallState.Active -> state.duration
                else -> 0
            }
            _callState.value = CallState.Ended(call, CallEndReason.COMPLETED, duration)
            resetAudio()
            disposeWebRTC()
            delay(1500)
            _callState.value = CallState.Idle
            _showCallUI.emit(CallUIEvent.DismissCall)
        }
    }

    /**
     * Toggle audio mute.
     */
    fun toggleMute() {
        val state = _callState.value
        if (state is CallState.Active) {
            val newMuted = !state.isMuted
            _callState.value = state.copy(isMuted = newMuted)
            webRTCClient?.setAudioEnabled(!newMuted)
            Log.d(TAG, "Mute toggled: $newMuted")
        }
    }

    /**
     * Toggle speaker.
     */
    fun toggleSpeaker() {
        val state = _callState.value
        if (state is CallState.Active) {
            val newSpeaker = !state.isSpeakerOn
            _callState.value = state.copy(isSpeakerOn = newSpeaker)
            audioManager.isSpeakerphoneOn = newSpeaker
            Log.d(TAG, "Speaker toggled: $newSpeaker")
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

            // Notify peer
            callSignalingClient.sendVideoState(state.call.callId, newEnabled)
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

    override fun onConnectionEstablished() {
        Log.d(TAG, "WebRTC connection established")
    }

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
                else -> return@launch
            }

            _callState.value = CallState.Ended(call, CallEndReason.FAILED)
            resetAudio()
            disposeWebRTC()
            delay(1500)
            _callState.value = CallState.Idle
            _showCallUI.emit(CallUIEvent.DismissCall)
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

    // MARK: - Private Methods

    private fun handleCallEvent(event: CallEvent) {
        scope.launch {
            when (event) {
                is CallEvent.IncomingCall -> handleIncomingCall(event)
                is CallEvent.CallAnswered -> handleCallAnswered(event)
                is CallEvent.CallRejected -> handleCallRejected(event)
                is CallEvent.CallEnded -> handleCallEnded(event)
                is CallEvent.CallFailed -> handleCallFailed(event)
                is CallEvent.IceCandidate -> handleRemoteIceCandidate(event)
                is CallEvent.RemoteVideoChanged -> handleRemoteVideoChanged(event)
            }
        }
    }

    private suspend fun handleIncomingCall(event: CallEvent.IncomingCall) {
        if (_callState.value !is CallState.Idle) {
            // Already in a call - auto-reject
            callSignalingClient.rejectCall(event.callId, "busy")
            return
        }

        Log.i(TAG, "Incoming ${event.callType} call from ${event.peerDisplayName}")

        // Initialize WebRTC early to prepare for answering
        initializeWebRTC()
        webRTCClient?.createPeerConnection()

        val call = Call(
            callId = event.callId,
            connectionId = event.connectionId,
            peerGuid = event.peerGuid,
            peerDisplayName = event.peerDisplayName,
            peerAvatarUrl = event.peerAvatarUrl,
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
    }

    private suspend fun handleCallAnswered(event: CallEvent.CallAnswered) {
        val state = _callState.value
        if (state !is CallState.Outgoing || state.call.callId != event.callId) {
            return
        }

        Log.i(TAG, "Call answered by peer")

        stopRingtone()

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
        webRTCClient?.addIceCandidate(candidate)
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

    private fun disposeWebRTC() {
        _remoteVideoTrack.value = null
        _localVideoTrack.value = null
        webRTCClient?.dispose()
        webRTCClient = null
    }

    private fun startDurationTimer(call: Call) {
        durationJob?.cancel()
        durationJob = scope.launch {
            var duration = 0L
            while (isActive) {
                delay(1000)
                duration++
                val state = _callState.value
                if (state is CallState.Active) {
                    _callState.value = state.copy(duration = duration)
                } else {
                    break
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
                callSignalingClient.endCall(state.call.callId)
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

    private fun stopRingtone() {
        ringtoneJob?.cancel()
        ringtoneJob = null
        vibrator?.cancel()
    }

    private fun setupAudioForCall() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
    }

    private fun resetAudio() {
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
        audioManager.isMicrophoneMute = false
    }

    companion object {
        private const val TAG = "CallManager"
    }
}
