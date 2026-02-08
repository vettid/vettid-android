package com.vettid.app.features.calling

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import javax.inject.Inject

/**
 * ViewModel for call screens.
 *
 * Wraps CallManager and provides UI-friendly state and actions.
 */
@HiltViewModel
class CallViewModel @Inject constructor(
    private val callManager: CallManager
) : ViewModel() {

    val callState: StateFlow<CallState> = callManager.callState

    val remoteVideoTrack: StateFlow<VideoTrack?> = callManager.remoteVideoTrack

    val localVideoTrack: StateFlow<VideoTrack?> = callManager.localVideoTrack

    val showCallUI: SharedFlow<CallUIEvent> = callManager.showCallUI

    private val _effects = MutableSharedFlow<CallEffect>()
    val effects: SharedFlow<CallEffect> = _effects.asSharedFlow()

    /**
     * Start a call to a user.
     */
    fun startCall(targetUserGuid: String, displayName: String, callType: CallType) {
        viewModelScope.launch {
            callManager.startCall(targetUserGuid, displayName, callType).onFailure { error ->
                _effects.emit(CallEffect.ShowError(error.message ?: "Failed to start call"))
            }
        }
    }

    /**
     * Answer incoming call.
     */
    fun answerCall() {
        viewModelScope.launch {
            callManager.answerCall().onFailure { error ->
                _effects.emit(CallEffect.ShowError(error.message ?: "Failed to answer call"))
            }
        }
    }

    /**
     * Reject incoming call.
     */
    fun rejectCall() {
        viewModelScope.launch {
            callManager.rejectCall().onFailure { error ->
                _effects.emit(CallEffect.ShowError(error.message ?: "Failed to reject call"))
            }
        }
    }

    /**
     * End active call.
     */
    fun endCall() {
        viewModelScope.launch {
            callManager.endCall().onFailure { error ->
                _effects.emit(CallEffect.ShowError(error.message ?: "Failed to end call"))
            }
        }
    }

    /**
     * Toggle mute.
     */
    fun toggleMute() {
        callManager.toggleMute()
    }

    /**
     * Toggle speaker.
     */
    fun toggleSpeaker() {
        callManager.toggleSpeaker()
    }

    /**
     * Toggle video.
     */
    fun toggleVideo() {
        viewModelScope.launch {
            callManager.toggleVideo()
        }
    }

    /**
     * Switch camera.
     */
    fun switchCamera() {
        callManager.switchCamera()
    }

    /**
     * Get EGL context for video rendering.
     */
    fun getEglContext(): EglBase.Context? = callManager.getEglContext()

    /**
     * Format duration as MM:SS.
     */
    fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", minutes, secs)
    }

    /**
     * Get call state description for UI.
     */
    fun getStateDescription(state: CallState): String {
        return when (state) {
            is CallState.Idle -> ""
            is CallState.Outgoing -> "Calling..."
            is CallState.Incoming -> "Incoming call"
            is CallState.Active -> formatDuration(state.duration)
            is CallState.Ended -> when (state.reason) {
                CallEndReason.COMPLETED -> "Call ended"
                CallEndReason.REJECTED -> "Call declined"
                CallEndReason.BUSY -> "Busy"
                CallEndReason.TIMEOUT -> "No answer"
                CallEndReason.FAILED -> "Call failed"
                CallEndReason.MISSED -> "Missed call"
                CallEndReason.CANCELLED -> "Call cancelled"
            }
        }
    }
}
