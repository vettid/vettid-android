package com.vettid.app.core.nats

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.features.calling.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for vault-routed call signaling via NATS.
 *
 * All call events are routed through the user's OWN vault:
 * - App sends to own vault (call.start, call.accept, call.reject, call.end, call.signal)
 * - Own vault verifies, logs, and forwards to peer's vault via backend NATS account
 * - Peer's vault receives, verifies (block list), logs, and relays to peer's app
 *
 * The app NEVER publishes directly to another user's NATS subjects.
 * Vault-to-vault communication uses the backend NATS account which has cross-space access.
 */
@Singleton
class CallSignalingClient @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val credentialStore: CredentialStore
) {
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _callEvents = MutableSharedFlow<CallEvent>(extraBufferCapacity = 64)
    val callEvents: SharedFlow<CallEvent> = _callEvents.asSharedFlow()

    init {
        // Bridge vault-routed call events to legacy CallEvent flow
        scope.launch {
            ownerSpaceClient.callEvents.collect { event ->
                bridgeCallSignalEvent(event)
            }
        }
    }

    /**
     * Initiate a call to a peer via own vault.
     *
     * Sends call.start to own vault with the connection_id. The vault:
     * - Looks up the connection and peer info
     * - Generates X25519 keypair for E2EE
     * - Forwards call.initiate to the peer's vault (via backend NATS account)
     * - Returns call_id and local public key
     *
     * @param connectionId Connection ID (used by vault to find the peer)
     * @param displayName Peer's display name (for local UI)
     * @param callType Voice or video
     * @return Call object with vault-generated callId
     */
    suspend fun initiateCall(
        connectionId: String,
        displayName: String,
        callType: CallType,
        peerGuid: String
    ): Result<Call> {
        val payload = JsonObject().apply {
            addProperty("connection_id", connectionId)
            val metadata = JsonObject()
            metadata.addProperty("call_type", callType.name.lowercase())
            add("metadata", metadata)
        }

        Log.i(TAG, "Initiating ${callType.name} call via vault for connection $connectionId")

        return sendAndAwait("call.start", payload) { result ->
            val callId = result.get("call_id")?.asString ?: "call-${UUID.randomUUID()}"
            Call(
                callId = callId,
                connectionId = connectionId,
                peerGuid = peerGuid,
                peerDisplayName = displayName,
                peerAvatarUrl = null,
                callType = callType,
                direction = CallDirection.OUTGOING,
                initiatedAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * Legacy method for backward compatibility.
     * Use initiateCall(targetUserGuid, displayName, callType, sdpOffer) for vault-routed calls.
     */
    @Deprecated("Use initiateCall with targetUserGuid instead")
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

        Log.i(TAG, "Initiating ${callType.name} call to connection $connectionId (legacy)")

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
     * Answer an incoming call (vault-routed).
     *
     * @param callId The call to answer
     * @param callerGuid The caller's GUID (to route response back)
     * @param sdpAnswer WebRTC SDP answer
     */
    /**
     * Answer a call. Returns the E2EE shared secret (base64) if the vault derived one.
     */
    suspend fun answerCall(
        callId: String,
        callerGuid: String,
        sdpAnswer: String? = null
    ): Result<String?> {
        val payload = JsonObject().apply {
            addProperty("call_id", callId)
            sdpAnswer?.let { addProperty("sdp_answer", it) }
        }

        Log.i(TAG, "Answering call $callId to user $callerGuid")

        // Send accept to own vault — vault returns shared secret and relays to caller
        return sendAndAwait("call.accept", payload) { result ->
            // Extract shared_secret from vault's AcceptCallResponse
            if (result.has("shared_secret")) result.get("shared_secret").asString else null
        }
    }

    /**
     * Legacy answer method.
     */
    @Deprecated("Use answerCall with callerGuid for vault-routed calls")
    suspend fun answerCall(callId: String, sdpAnswer: String? = null): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("call_id", callId)
            sdpAnswer?.let { addProperty("sdp_answer", it) }
        }

        Log.i(TAG, "Answering call $callId (legacy)")

        return sendAndAwait("call.answer", payload) { Unit }
    }

    /**
     * Reject an incoming call (vault-routed).
     *
     * @param callId The call to reject
     * @param callerGuid The caller's GUID (to route response back)
     * @param reason Optional reason for rejection
     */
    suspend fun rejectCall(
        callId: String,
        callerGuid: String,
        reason: String? = null
    ): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("call_id", callId)
            reason?.let { addProperty("reason", it) }
        }

        Log.i(TAG, "Rejecting call $callId via own vault")

        return sendAndAwait("call.reject", payload) { Unit }
    }

    /**
     * Legacy reject method.
     */
    @Deprecated("Use rejectCall with callerGuid for vault-routed calls")
    suspend fun rejectCall(callId: String, reason: String? = null): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("call_id", callId)
            reason?.let { addProperty("reason", it) }
        }

        Log.i(TAG, "Rejecting call $callId (legacy)")

        return sendAndAwait("call.reject", payload) { Unit }
    }

    /**
     * End an active call (vault-routed).
     *
     * @param callId The call to end
     * @param peerGuid The peer's GUID (to notify them)
     */
    suspend fun endCall(callId: String, peerGuid: String): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("call_id", callId)
        }

        Log.i(TAG, "Ending call $callId via own vault")

        return sendAndAwait("call.end", payload) { Unit }
    }

    /**
     * Legacy end call method.
     */
    @Deprecated("Use endCall with peerGuid for vault-routed calls")
    suspend fun endCall(callId: String): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("call_id", callId)
        }

        Log.i(TAG, "Ending call $callId (legacy)")

        return sendAndAwait("call.end", payload) { Unit }
    }

    /**
     * Send SDP offer to peer (vault-routed).
     *
     * @param callId The call ID
     * @param peerGuid The peer's GUID
     * @param sdpOffer WebRTC SDP offer
     */
    suspend fun sendOffer(callId: String, peerGuid: String, sdpOffer: String): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("call_id", callId)
            addProperty("signal_type", "offer")
            val signalPayload = JsonObject()
            signalPayload.addProperty("sdp_offer", sdpOffer)
            add("payload", signalPayload)
        }

        return sendAndAwait("call.signal", payload) { Unit }
    }

    suspend fun sendAnswer(callId: String, peerGuid: String, sdpAnswer: String): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("call_id", callId)
            addProperty("signal_type", "answer")
            val signalPayload = JsonObject()
            signalPayload.addProperty("sdp_answer", sdpAnswer)
            add("payload", signalPayload)
        }

        return sendAndAwait("call.signal", payload) { Unit }
    }

    suspend fun sendIceCandidate(
        callId: String,
        peerGuid: String,
        candidate: String,
        sdpMid: String?,
        sdpMLineIndex: Int?
    ): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("call_id", callId)
            addProperty("signal_type", "candidate")
            val signalPayload = JsonObject()
            signalPayload.addProperty("candidate", candidate)
            sdpMid?.let { signalPayload.addProperty("sdp_mid", it) }
            sdpMLineIndex?.let { signalPayload.addProperty("sdp_m_line_index", it) }
            add("payload", signalPayload)
        }

        // Fire and forget via vault — don't wait for response
        val sendResult = ownerSpaceClient.sendToVault("call.signal", payload)
        return if (sendResult.isSuccess) Result.success(Unit)
        else Result.failure(sendResult.exceptionOrNull() ?: NatsException("Failed to send ICE candidate"))
    }

    /**
     * Legacy ICE candidate method.
     */
    @Deprecated("Use sendIceCandidate with peerGuid for vault-routed calls")
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
     * @param peerGuid The peer's GUID
     * @param enabled Whether local video is enabled
     */
    suspend fun sendVideoState(callId: String, peerGuid: String, enabled: Boolean): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("call_id", callId)
            addProperty("signal_type", "video-state")
            val signalPayload = JsonObject()
            signalPayload.addProperty("video_enabled", enabled)
            add("payload", signalPayload)
        }

        val sendResult = ownerSpaceClient.sendToVault("call.signal", payload)
        return if (sendResult.isSuccess) Result.success(Unit)
        else Result.failure(sendResult.exceptionOrNull() ?: NatsException("Failed to send video state"))
    }

    /**
     * Legacy video state method.
     */
    @Deprecated("Use sendVideoState with peerGuid for vault-routed calls")
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

    // MARK: - Event Bridging

    /**
     * Bridge vault-routed CallSignalEvent to legacy CallEvent flow.
     * This allows existing UI code to continue working with CallEvent.
     */
    private fun bridgeCallSignalEvent(event: CallSignalEvent) {
        val callEvent: CallEvent? = when (event) {
            is CallSignalEvent.Incoming -> CallEvent.IncomingCall(
                callId = event.callId,
                connectionId = "", // Not used in vault-routed calls
                peerGuid = event.callerGuid,
                peerDisplayName = event.callerDisplayName,
                peerAvatarUrl = null,
                callType = if (event.callType == "video") CallType.VIDEO else CallType.VOICE,
                sdpOffer = event.sdpOffer
            )
            is CallSignalEvent.Accepted -> CallEvent.CallAnswered(
                callId = event.callId,
                answeredAt = System.currentTimeMillis(),
                sdpAnswer = event.sdpAnswer,
                sharedSecret = event.sharedSecret
            )
            is CallSignalEvent.Answer -> CallEvent.CallAnswered(
                callId = event.callId,
                answeredAt = System.currentTimeMillis(),
                sdpAnswer = event.sdpAnswer
            )
            is CallSignalEvent.Rejected -> CallEvent.CallRejected(
                callId = event.callId,
                reason = event.reason
            )
            is CallSignalEvent.Ended -> CallEvent.CallEnded(
                callId = event.callId,
                reason = parseEndReason(event.reason),
                duration = event.duration
            )
            is CallSignalEvent.IceCandidate -> CallEvent.IceCandidate(
                callId = event.callId,
                candidate = event.candidate,
                sdpMid = event.sdpMid,
                sdpMLineIndex = event.sdpMLineIndex
            )
            is CallSignalEvent.Missed -> CallEvent.CallEnded(
                callId = event.callId,
                reason = CallEndReason.MISSED,
                duration = 0
            )
            is CallSignalEvent.Blocked -> CallEvent.CallFailed(
                callId = event.callId,
                error = "You are blocked by this user"
            )
            is CallSignalEvent.Busy -> CallEvent.CallEnded(
                callId = event.callId,
                reason = CallEndReason.BUSY,
                duration = 0
            )
            is CallSignalEvent.Offer -> {
                // SDP offer is typically handled during call setup, not as a separate event
                Log.d(TAG, "Received SDP offer for call ${event.callId}")
                null
            }
        }

        callEvent?.let {
            Log.d(TAG, "Bridged call event: ${it::class.simpleName}")
            _callEvents.tryEmit(it)
        }
    }

    companion object {
        private const val TAG = "CallSignalingClient"
    }
}
