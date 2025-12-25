package com.vettid.app.core.nats

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vettid.app.features.calling.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for call signaling via vault NATS handlers.
 *
 * Handles WebRTC signaling for voice/video calls:
 * - call.initiate: Start outgoing call with SDP offer
 * - call.answer: Accept incoming call with SDP answer
 * - call.reject: Decline incoming call
 * - call.end: Terminate active call
 * - call.ice-candidate: Exchange ICE candidates
 * - call.history: Retrieve call history
 *
 * Uses JetStream KV bucket 'calls' (30d TTL) for state persistence.
 */
@Singleton
class CallSignalingClient @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient
) {
    private val gson = Gson()

    private val _callEvents = MutableSharedFlow<CallEvent>(extraBufferCapacity = 64)
    val callEvents: SharedFlow<CallEvent> = _callEvents.asSharedFlow()

    /**
     * Initiate a call to a connection.
     *
     * @param connectionId Connection to call
     * @param callType Voice or video
     * @param sdpOffer WebRTC SDP offer
     * @return Call object with generated callId
     */
    suspend fun initiateCall(
        connectionId: String,
        callType: CallType,
        sdpOffer: String? = null
    ): Result<Call> {
        val callId = "call-${UUID.randomUUID()}"

        val payload = JsonObject().apply {
            addProperty("call_id", callId)
            addProperty("connection_id", connectionId)
            addProperty("call_type", callType.name.lowercase())
            sdpOffer?.let { addProperty("sdp_offer", it) }
        }

        Log.i(TAG, "Initiating ${callType.name} call to connection $connectionId")

        return sendAndAwait("call.initiate", payload) { result ->
            Call(
                callId = callId,
                connectionId = connectionId,
                peerGuid = result.get("peer_guid")?.asString ?: "",
                peerDisplayName = result.get("peer_display_name")?.asString ?: "Unknown",
                peerAvatarUrl = result.get("peer_avatar_url")?.asString,
                callType = callType,
                direction = CallDirection.OUTGOING,
                initiatedAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * Answer an incoming call.
     *
     * @param callId The call to answer
     * @param sdpAnswer WebRTC SDP answer
     */
    suspend fun answerCall(callId: String, sdpAnswer: String? = null): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("call_id", callId)
            sdpAnswer?.let { addProperty("sdp_answer", it) }
        }

        Log.i(TAG, "Answering call $callId")

        return sendAndAwait("call.answer", payload) { Unit }
    }

    /**
     * Reject an incoming call.
     *
     * @param callId The call to reject
     * @param reason Optional reason for rejection
     */
    suspend fun rejectCall(callId: String, reason: String? = null): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("call_id", callId)
            reason?.let { addProperty("reason", it) }
        }

        Log.i(TAG, "Rejecting call $callId")

        return sendAndAwait("call.reject", payload) { Unit }
    }

    /**
     * End an active call.
     *
     * @param callId The call to end
     */
    suspend fun endCall(callId: String): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("call_id", callId)
        }

        Log.i(TAG, "Ending call $callId")

        return sendAndAwait("call.end", payload) { Unit }
    }

    /**
     * Send ICE candidate to peer.
     *
     * @param callId The active call
     * @param candidate ICE candidate string
     * @param sdpMid SDP mid
     * @param sdpMLineIndex SDP m-line index
     */
    suspend fun sendIceCandidate(
        callId: String,
        candidate: String,
        sdpMid: String?,
        sdpMLineIndex: Int?
    ): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("call_id", callId)
            addProperty("candidate", candidate)
            sdpMid?.let { addProperty("sdp_mid", it) }
            sdpMLineIndex?.let { addProperty("sdp_m_line_index", it) }
        }

        // Fire and forget - ICE candidates are best-effort
        val sendResult = ownerSpaceClient.sendToVault("call.ice-candidate", payload)
        return if (sendResult.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(sendResult.exceptionOrNull() ?: NatsException("Failed to send ICE candidate"))
        }
    }

    /**
     * Notify peer of video state change.
     *
     * @param callId The active call
     * @param enabled Whether local video is enabled
     */
    suspend fun sendVideoState(callId: String, enabled: Boolean): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("call_id", callId)
            addProperty("video_enabled", enabled)
        }

        val sendResult = ownerSpaceClient.sendToVault("call.video-state", payload)
        return if (sendResult.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(sendResult.exceptionOrNull() ?: NatsException("Failed to send video state"))
        }
    }

    /**
     * Get call history for a connection.
     *
     * @param connectionId Filter by connection (optional)
     * @param limit Maximum number of results
     */
    suspend fun getCallHistory(
        connectionId: String? = null,
        limit: Int = 50
    ): Result<List<CallHistoryEntry>> {
        val payload = JsonObject().apply {
            connectionId?.let { addProperty("connection_id", it) }
            addProperty("limit", limit)
        }

        return sendAndAwait("call.history", payload) { result ->
            result.getAsJsonArray("history")?.mapNotNull { item ->
                try {
                    val obj = item.asJsonObject
                    CallHistoryEntry(
                        call = parseCall(obj.getAsJsonObject("call")),
                        duration = obj.get("duration")?.asLong ?: 0,
                        endReason = parseEndReason(obj.get("end_reason")?.asString)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse call history entry", e)
                    null
                }
            } ?: emptyList()
        }
    }

    /**
     * Handle incoming call event from vault.
     * Call this when receiving call-related messages on the forApp subject.
     */
    fun handleCallEvent(type: String, data: JsonObject) {
        val callEvent = when (type) {
            "call.incoming" -> parseIncomingCall(data)
            "call.answered" -> parseCallAnswered(data)
            "call.rejected" -> parseCallRejected(data)
            "call.ended" -> parseCallEnded(data)
            "call.ice-candidate" -> parseIceCandidate(data)
            "call.video-state" -> parseVideoState(data)
            "call.failed" -> parseCallFailed(data)
            else -> null
        }

        callEvent?.let { event ->
            Log.d(TAG, "Received call event: $type")
            _callEvents.tryEmit(event)
        }
    }

    // MARK: - Private Helpers

    private suspend fun <T> sendAndAwait(
        messageType: String,
        payload: JsonObject,
        timeoutMs: Long = 30_000,
        transform: (JsonObject) -> T
    ): Result<T> {
        val sendResult = ownerSpaceClient.sendToVault(messageType, payload)
        if (sendResult.isFailure) {
            return Result.failure(sendResult.exceptionOrNull() ?: NatsException("Send failed"))
        }

        val requestId = sendResult.getOrThrow()

        val response = withTimeoutOrNull(timeoutMs) {
            ownerSpaceClient.vaultResponses
                .filter { it.requestId == requestId }
                .first()
        }

        return when (response) {
            null -> Result.failure(NatsException("Request timed out"))
            is VaultResponse.HandlerResult -> {
                if (response.success && response.result != null) {
                    try {
                        Result.success(transform(response.result))
                    } catch (e: Exception) {
                        Result.failure(NatsException("Failed to parse response: ${e.message}"))
                    }
                } else {
                    Result.failure(NatsException(response.error ?: "Handler failed"))
                }
            }
            is VaultResponse.Error -> {
                Result.failure(NatsException("${response.code}: ${response.message}"))
            }
            else -> Result.failure(NatsException("Unexpected response type"))
        }
    }

    private fun parseIncomingCall(data: JsonObject): CallEvent.IncomingCall? {
        return try {
            CallEvent.IncomingCall(
                callId = data.get("call_id")?.asString ?: return null,
                connectionId = data.get("connection_id")?.asString ?: return null,
                peerGuid = data.get("peer_guid")?.asString ?: return null,
                peerDisplayName = data.get("peer_display_name")?.asString ?: "Unknown",
                peerAvatarUrl = data.get("peer_avatar_url")?.asString,
                callType = parseCallType(data.get("call_type")?.asString),
                sdpOffer = data.get("sdp_offer")?.asString
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse incoming call", e)
            null
        }
    }

    private fun parseCallAnswered(data: JsonObject): CallEvent.CallAnswered? {
        return try {
            CallEvent.CallAnswered(
                callId = data.get("call_id")?.asString ?: return null,
                answeredAt = data.get("answered_at")?.asLong ?: System.currentTimeMillis(),
                sdpAnswer = data.get("sdp_answer")?.asString
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse call answered", e)
            null
        }
    }

    private fun parseCallRejected(data: JsonObject): CallEvent.CallRejected? {
        return try {
            CallEvent.CallRejected(
                callId = data.get("call_id")?.asString ?: return null,
                reason = data.get("reason")?.asString
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse call rejected", e)
            null
        }
    }

    private fun parseCallEnded(data: JsonObject): CallEvent.CallEnded? {
        return try {
            CallEvent.CallEnded(
                callId = data.get("call_id")?.asString ?: return null,
                reason = parseEndReason(data.get("reason")?.asString),
                duration = data.get("duration")?.asLong ?: 0
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse call ended", e)
            null
        }
    }

    private fun parseIceCandidate(data: JsonObject): CallEvent.IceCandidate? {
        return try {
            CallEvent.IceCandidate(
                callId = data.get("call_id")?.asString ?: return null,
                candidate = data.get("candidate")?.asString ?: return null,
                sdpMid = data.get("sdp_mid")?.asString,
                sdpMLineIndex = data.get("sdp_m_line_index")?.asInt
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse ICE candidate", e)
            null
        }
    }

    private fun parseVideoState(data: JsonObject): CallEvent.RemoteVideoChanged? {
        return try {
            CallEvent.RemoteVideoChanged(
                callId = data.get("call_id")?.asString ?: return null,
                enabled = data.get("video_enabled")?.asBoolean ?: false
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse video state", e)
            null
        }
    }

    private fun parseCallFailed(data: JsonObject): CallEvent.CallFailed? {
        return try {
            CallEvent.CallFailed(
                callId = data.get("call_id")?.asString ?: return null,
                error = data.get("error")?.asString ?: "Unknown error"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse call failed", e)
            null
        }
    }

    private fun parseCall(json: JsonObject): Call {
        return Call(
            callId = json.get("call_id")?.asString ?: "",
            connectionId = json.get("connection_id")?.asString ?: "",
            peerGuid = json.get("peer_guid")?.asString ?: "",
            peerDisplayName = json.get("peer_display_name")?.asString ?: "Unknown",
            peerAvatarUrl = json.get("peer_avatar_url")?.asString,
            callType = parseCallType(json.get("call_type")?.asString),
            direction = parseDirection(json.get("direction")?.asString),
            initiatedAt = json.get("initiated_at")?.asLong ?: 0,
            answeredAt = json.get("answered_at")?.asLong,
            endedAt = json.get("ended_at")?.asLong
        )
    }

    private fun parseCallType(value: String?): CallType {
        return when (value?.lowercase()) {
            "video" -> CallType.VIDEO
            else -> CallType.VOICE
        }
    }

    private fun parseDirection(value: String?): CallDirection {
        return when (value?.lowercase()) {
            "incoming" -> CallDirection.INCOMING
            else -> CallDirection.OUTGOING
        }
    }

    private fun parseEndReason(value: String?): CallEndReason {
        return when (value?.uppercase()) {
            "COMPLETED" -> CallEndReason.COMPLETED
            "REJECTED" -> CallEndReason.REJECTED
            "BUSY" -> CallEndReason.BUSY
            "TIMEOUT" -> CallEndReason.TIMEOUT
            "FAILED" -> CallEndReason.FAILED
            "MISSED" -> CallEndReason.MISSED
            "CANCELLED" -> CallEndReason.CANCELLED
            else -> CallEndReason.COMPLETED
        }
    }

    companion object {
        private const val TAG = "CallSignalingClient"
    }
}
