package com.vettid.app.features.calling

import com.google.gson.annotations.SerializedName

/**
 * Represents a call between two users.
 */
data class Call(
    @SerializedName("call_id") val callId: String,
    @SerializedName("connection_id") val connectionId: String,
    @SerializedName("peer_guid") val peerGuid: String,
    @SerializedName("peer_display_name") val peerDisplayName: String,
    @SerializedName("peer_avatar_url") val peerAvatarUrl: String? = null,
    @SerializedName("call_type") val callType: CallType,
    @SerializedName("direction") val direction: CallDirection,
    @SerializedName("initiated_at") val initiatedAt: Long,
    @SerializedName("answered_at") val answeredAt: Long? = null,
    @SerializedName("ended_at") val endedAt: Long? = null
)

/**
 * Type of call.
 */
enum class CallType {
    @SerializedName("voice") VOICE,
    @SerializedName("video") VIDEO
}

/**
 * Direction of call relative to local user.
 */
enum class CallDirection {
    @SerializedName("incoming") INCOMING,
    @SerializedName("outgoing") OUTGOING
}

/**
 * Call state machine.
 *
 * State transitions:
 * - Idle → Outgoing (startCall)
 * - Idle → Incoming (call.incoming event)
 * - Outgoing → Active (call.answered event)
 * - Outgoing → Ended (call.rejected/timeout)
 * - Incoming → Active (answerCall)
 * - Incoming → Ended (rejectCall)
 * - Active → Ended (endCall or call.ended event)
 * - Ended → Idle (after brief delay)
 */
sealed class CallState {
    /**
     * No active call.
     */
    object Idle : CallState()

    /**
     * Outgoing call, waiting for peer to answer.
     */
    data class Outgoing(
        val call: Call,
        val ringingDuration: Long = 0,  // seconds
        val sdpOffer: String? = null    // WebRTC SDP offer
    ) : CallState()

    /**
     * Incoming call, ringing.
     */
    data class Incoming(
        val call: Call,
        val isRinging: Boolean = true,
        val sdpOffer: String? = null    // WebRTC SDP offer from caller
    ) : CallState()

    /**
     * Active call with established connection.
     */
    data class Active(
        val call: Call,
        val duration: Long = 0,         // seconds
        val isMuted: Boolean = false,
        val isSpeakerOn: Boolean = false,
        val isLocalVideoEnabled: Boolean = false,
        val isRemoteVideoEnabled: Boolean = false,
        val isFrontCamera: Boolean = true
    ) : CallState()

    /**
     * Call has ended.
     */
    data class Ended(
        val call: Call,
        val reason: CallEndReason,
        val duration: Long = 0
    ) : CallState()
}

/**
 * Reason for call ending.
 */
enum class CallEndReason {
    COMPLETED,      // Normal hangup
    REJECTED,       // Peer rejected the call
    BUSY,           // Peer is on another call
    TIMEOUT,        // No answer within timeout
    FAILED,         // Technical failure
    MISSED,         // Incoming call not answered
    CANCELLED       // Caller cancelled before answer
}

/**
 * Events from NATS signaling.
 */
sealed class CallEvent {
    /**
     * Incoming call notification.
     */
    data class IncomingCall(
        val callId: String,
        val connectionId: String,
        val peerGuid: String,
        val peerDisplayName: String,
        val peerAvatarUrl: String?,
        val callType: CallType,
        val sdpOffer: String?           // WebRTC SDP offer
    ) : CallEvent()

    /**
     * Call was answered by peer.
     */
    data class CallAnswered(
        val callId: String,
        val answeredAt: Long,
        val sdpAnswer: String?          // WebRTC SDP answer
    ) : CallEvent()

    /**
     * Call was rejected by peer.
     */
    data class CallRejected(
        val callId: String,
        val reason: String?
    ) : CallEvent()

    /**
     * Call ended.
     */
    data class CallEnded(
        val callId: String,
        val reason: CallEndReason,
        val duration: Long
    ) : CallEvent()

    /**
     * Call failed due to error.
     */
    data class CallFailed(
        val callId: String,
        val error: String
    ) : CallEvent()

    /**
     * ICE candidate from peer for WebRTC.
     */
    data class IceCandidate(
        val callId: String,
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int?
    ) : CallEvent()

    /**
     * Remote video enabled/disabled.
     */
    data class RemoteVideoChanged(
        val callId: String,
        val enabled: Boolean
    ) : CallEvent()
}

/**
 * UI navigation events for calls.
 */
sealed class CallUIEvent {
    data class ShowIncoming(val call: Call) : CallUIEvent()
    data class ShowOutgoing(val call: Call) : CallUIEvent()
    data class ShowActive(val call: Call) : CallUIEvent()
    object DismissCall : CallUIEvent()
}

/**
 * Call history entry.
 */
data class CallHistoryEntry(
    val call: Call,
    val duration: Long,
    val endReason: CallEndReason
)

/**
 * UI effects from CallViewModel.
 */
sealed class CallEffect {
    data class ShowError(val message: String) : CallEffect()
    data class RequestPermissions(val permissions: List<String>) : CallEffect()
}
