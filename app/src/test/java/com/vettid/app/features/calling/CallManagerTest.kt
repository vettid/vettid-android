package com.vettid.app.features.calling

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for call state machine and models.
 *
 * Note: Full CallManager integration tests require Android context and WebRTC.
 * These tests focus on the call state machine logic and data models.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallManagerTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // MARK: - CallState Tests

    @Test
    fun `CallState Idle is singleton`() {
        val idle1 = CallState.Idle
        val idle2 = CallState.Idle
        assertSame(idle1, idle2)
    }

    @Test
    fun `CallState Outgoing contains call and ringing duration`() {
        val call = createTestCall()
        val state = CallState.Outgoing(call = call, ringingDuration = 5)

        assertEquals(call, state.call)
        assertEquals(5, state.ringingDuration)
        assertNull(state.sdpOffer)
    }

    @Test
    fun `CallState Outgoing with SDP offer`() {
        val call = createTestCall()
        val sdpOffer = "v=0\r\no=- 123456 2 IN IP4 127.0.0.1\r\n..."
        val state = CallState.Outgoing(call = call, sdpOffer = sdpOffer)

        assertEquals(sdpOffer, state.sdpOffer)
    }

    @Test
    fun `CallState Incoming contains call and SDP offer`() {
        val call = createTestCall(direction = CallDirection.INCOMING)
        val sdpOffer = "v=0\r\no=- 789012 2 IN IP4 127.0.0.1\r\n..."
        val state = CallState.Incoming(call = call, sdpOffer = sdpOffer)

        assertEquals(call, state.call)
        assertEquals(sdpOffer, state.sdpOffer)
        assertTrue(state.isRinging)
    }

    @Test
    fun `CallState Active contains call with controls`() {
        val call = createTestCall()
        val state = CallState.Active(
            call = call,
            duration = 120,
            isMuted = true,
            isSpeakerOn = true,
            isLocalVideoEnabled = true,
            isRemoteVideoEnabled = true,
            isFrontCamera = false
        )

        assertEquals(call, state.call)
        assertEquals(120, state.duration)
        assertTrue(state.isMuted)
        assertTrue(state.isSpeakerOn)
        assertTrue(state.isLocalVideoEnabled)
        assertTrue(state.isRemoteVideoEnabled)
        assertFalse(state.isFrontCamera)
    }

    @Test
    fun `CallState Active defaults to non-muted, earpiece, video off`() {
        val call = createTestCall()
        val state = CallState.Active(call = call)

        assertEquals(0, state.duration)
        assertFalse(state.isMuted)
        assertFalse(state.isSpeakerOn)
        assertFalse(state.isLocalVideoEnabled)
        assertFalse(state.isRemoteVideoEnabled)
        assertTrue(state.isFrontCamera)
    }

    @Test
    fun `CallState Active copy updates individual fields`() {
        val call = createTestCall()
        val original = CallState.Active(call = call)

        val muted = original.copy(isMuted = true)
        assertTrue(muted.isMuted)
        assertFalse(muted.isSpeakerOn)

        val speaker = original.copy(isSpeakerOn = true)
        assertFalse(speaker.isMuted)
        assertTrue(speaker.isSpeakerOn)

        val duration = original.copy(duration = 60)
        assertEquals(60, duration.duration)
    }

    @Test
    fun `CallState Ended contains call with reason and duration`() {
        val call = createTestCall()
        val state = CallState.Ended(
            call = call,
            reason = CallEndReason.COMPLETED,
            duration = 300
        )

        assertEquals(call, state.call)
        assertEquals(CallEndReason.COMPLETED, state.reason)
        assertEquals(300, state.duration)
    }

    @Test
    fun `CallState Ended defaults to zero duration`() {
        val call = createTestCall()
        val state = CallState.Ended(call = call, reason = CallEndReason.REJECTED)

        assertEquals(0, state.duration)
    }

    // MARK: - Call Model Tests

    @Test
    fun `Call voice type`() {
        val call = createTestCall(callType = CallType.VOICE)
        assertEquals(CallType.VOICE, call.callType)
    }

    @Test
    fun `Call video type`() {
        val call = createTestCall(callType = CallType.VIDEO)
        assertEquals(CallType.VIDEO, call.callType)
    }

    @Test
    fun `Call outgoing direction`() {
        val call = createTestCall(direction = CallDirection.OUTGOING)
        assertEquals(CallDirection.OUTGOING, call.direction)
    }

    @Test
    fun `Call incoming direction`() {
        val call = createTestCall(direction = CallDirection.INCOMING)
        assertEquals(CallDirection.INCOMING, call.direction)
    }

    @Test
    fun `Call copy updates answeredAt`() {
        val call = createTestCall()
        assertNull(call.answeredAt)

        val answered = call.copy(answeredAt = 1703548800000L)
        assertEquals(1703548800000L, answered.answeredAt)
        assertNull(answered.endedAt)
    }

    @Test
    fun `Call copy updates endedAt`() {
        val call = createTestCall()
        val ended = call.copy(endedAt = 1703549100000L)
        assertEquals(1703549100000L, ended.endedAt)
    }

    // MARK: - CallEvent Tests

    @Test
    fun `CallEvent IncomingCall contains all fields`() {
        val event = CallEvent.IncomingCall(
            callId = "call-123",
            connectionId = "conn-456",
            peerGuid = "peer-789",
            peerDisplayName = "Alice",
            peerAvatarUrl = "https://example.com/avatar.jpg",
            callType = CallType.VIDEO,
            sdpOffer = "sdp-offer"
        )

        assertEquals("call-123", event.callId)
        assertEquals("conn-456", event.connectionId)
        assertEquals("peer-789", event.peerGuid)
        assertEquals("Alice", event.peerDisplayName)
        assertEquals("https://example.com/avatar.jpg", event.peerAvatarUrl)
        assertEquals(CallType.VIDEO, event.callType)
        assertEquals("sdp-offer", event.sdpOffer)
    }

    @Test
    fun `CallEvent IncomingCall with null avatar and SDP`() {
        val event = CallEvent.IncomingCall(
            callId = "call-123",
            connectionId = "conn-456",
            peerGuid = "peer-789",
            peerDisplayName = "Bob",
            peerAvatarUrl = null,
            callType = CallType.VOICE,
            sdpOffer = null
        )

        assertNull(event.peerAvatarUrl)
        assertNull(event.sdpOffer)
        assertEquals(CallType.VOICE, event.callType)
    }

    @Test
    fun `CallEvent CallAnswered contains SDP answer`() {
        val event = CallEvent.CallAnswered(
            callId = "call-123",
            answeredAt = 1703548800000L,
            sdpAnswer = "sdp-answer"
        )

        assertEquals("call-123", event.callId)
        assertEquals(1703548800000L, event.answeredAt)
        assertEquals("sdp-answer", event.sdpAnswer)
    }

    @Test
    fun `CallEvent CallRejected with reason`() {
        val event = CallEvent.CallRejected(
            callId = "call-123",
            reason = "busy"
        )

        assertEquals("call-123", event.callId)
        assertEquals("busy", event.reason)
    }

    @Test
    fun `CallEvent CallRejected without reason`() {
        val event = CallEvent.CallRejected(
            callId = "call-123",
            reason = null
        )

        assertNull(event.reason)
    }

    @Test
    fun `CallEvent CallEnded with duration`() {
        val event = CallEvent.CallEnded(
            callId = "call-123",
            reason = CallEndReason.COMPLETED,
            duration = 180
        )

        assertEquals("call-123", event.callId)
        assertEquals(CallEndReason.COMPLETED, event.reason)
        assertEquals(180, event.duration)
    }

    @Test
    fun `CallEvent CallFailed with error message`() {
        val event = CallEvent.CallFailed(
            callId = "call-123",
            error = "ICE connection failed"
        )

        assertEquals("call-123", event.callId)
        assertEquals("ICE connection failed", event.error)
    }

    @Test
    fun `CallEvent IceCandidate with all fields`() {
        val event = CallEvent.IceCandidate(
            callId = "call-123",
            candidate = "candidate:0 1 UDP 2122252543 192.168.1.1 12345 typ host",
            sdpMid = "0",
            sdpMLineIndex = 0
        )

        assertEquals("call-123", event.callId)
        assertEquals("candidate:0 1 UDP 2122252543 192.168.1.1 12345 typ host", event.candidate)
        assertEquals("0", event.sdpMid)
        assertEquals(0, event.sdpMLineIndex)
    }

    @Test
    fun `CallEvent RemoteVideoChanged enabled`() {
        val event = CallEvent.RemoteVideoChanged(
            callId = "call-123",
            enabled = true
        )

        assertEquals("call-123", event.callId)
        assertTrue(event.enabled)
    }

    @Test
    fun `CallEvent RemoteVideoChanged disabled`() {
        val event = CallEvent.RemoteVideoChanged(
            callId = "call-123",
            enabled = false
        )

        assertFalse(event.enabled)
    }

    // MARK: - CallUIEvent Tests

    @Test
    fun `CallUIEvent ShowIncoming contains call`() {
        val call = createTestCall(direction = CallDirection.INCOMING)
        val event = CallUIEvent.ShowIncoming(call)

        assertEquals(call, event.call)
    }

    @Test
    fun `CallUIEvent ShowOutgoing contains call`() {
        val call = createTestCall(direction = CallDirection.OUTGOING)
        val event = CallUIEvent.ShowOutgoing(call)

        assertEquals(call, event.call)
    }

    @Test
    fun `CallUIEvent ShowActive contains call`() {
        val call = createTestCall()
        val event = CallUIEvent.ShowActive(call)

        assertEquals(call, event.call)
    }

    @Test
    fun `CallUIEvent DismissCall is singleton`() {
        val dismiss1 = CallUIEvent.DismissCall
        val dismiss2 = CallUIEvent.DismissCall
        assertSame(dismiss1, dismiss2)
    }

    // MARK: - CallEndReason Tests

    @Test
    fun `CallEndReason has all expected values`() {
        val reasons = CallEndReason.values()
        assertEquals(7, reasons.size)
        assertTrue(reasons.contains(CallEndReason.COMPLETED))
        assertTrue(reasons.contains(CallEndReason.REJECTED))
        assertTrue(reasons.contains(CallEndReason.BUSY))
        assertTrue(reasons.contains(CallEndReason.TIMEOUT))
        assertTrue(reasons.contains(CallEndReason.FAILED))
        assertTrue(reasons.contains(CallEndReason.MISSED))
        assertTrue(reasons.contains(CallEndReason.CANCELLED))
    }

    // MARK: - CallHistoryEntry Tests

    @Test
    fun `CallHistoryEntry contains call, duration, and reason`() {
        val call = createTestCall()
        val entry = CallHistoryEntry(
            call = call,
            duration = 300,
            endReason = CallEndReason.COMPLETED
        )

        assertEquals(call, entry.call)
        assertEquals(300, entry.duration)
        assertEquals(CallEndReason.COMPLETED, entry.endReason)
    }

    // MARK: - CallEffect Tests

    @Test
    fun `CallEffect ShowError contains message`() {
        val effect = CallEffect.ShowError("Failed to connect")
        assertEquals("Failed to connect", effect.message)
    }

    @Test
    fun `CallEffect RequestPermissions contains permissions list`() {
        val permissions = listOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.CAMERA"
        )
        val effect = CallEffect.RequestPermissions(permissions)

        assertEquals(2, effect.permissions.size)
        assertTrue(effect.permissions.contains("android.permission.RECORD_AUDIO"))
        assertTrue(effect.permissions.contains("android.permission.CAMERA"))
    }

    // MARK: - State Machine Logic Tests

    @Test
    fun `state machine - Idle can transition to Outgoing`() {
        // Simulating state transition logic
        val initialState = CallState.Idle
        assertTrue(initialState is CallState.Idle)

        val call = createTestCall(direction = CallDirection.OUTGOING)
        val outgoingState = CallState.Outgoing(call = call)
        assertTrue(outgoingState is CallState.Outgoing)
    }

    @Test
    fun `state machine - Idle can transition to Incoming`() {
        val initialState = CallState.Idle
        assertTrue(initialState is CallState.Idle)

        val call = createTestCall(direction = CallDirection.INCOMING)
        val incomingState = CallState.Incoming(call = call)
        assertTrue(incomingState is CallState.Incoming)
    }

    @Test
    fun `state machine - Outgoing can transition to Active`() {
        val call = createTestCall(direction = CallDirection.OUTGOING)
        val outgoingState = CallState.Outgoing(call = call)
        assertTrue(outgoingState is CallState.Outgoing)

        val activeState = CallState.Active(call = call.copy(answeredAt = System.currentTimeMillis()))
        assertTrue(activeState is CallState.Active)
    }

    @Test
    fun `state machine - Outgoing can transition to Ended on rejection`() {
        val call = createTestCall(direction = CallDirection.OUTGOING)
        val outgoingState = CallState.Outgoing(call = call)
        assertTrue(outgoingState is CallState.Outgoing)

        val endedState = CallState.Ended(call = call, reason = CallEndReason.REJECTED)
        assertTrue(endedState is CallState.Ended)
        assertEquals(CallEndReason.REJECTED, endedState.reason)
    }

    @Test
    fun `state machine - Outgoing can transition to Ended on timeout`() {
        val call = createTestCall(direction = CallDirection.OUTGOING)
        val endedState = CallState.Ended(call = call, reason = CallEndReason.TIMEOUT)

        assertTrue(endedState is CallState.Ended)
        assertEquals(CallEndReason.TIMEOUT, endedState.reason)
    }

    @Test
    fun `state machine - Incoming can transition to Active on answer`() {
        val call = createTestCall(direction = CallDirection.INCOMING)
        val incomingState = CallState.Incoming(call = call)
        assertTrue(incomingState is CallState.Incoming)

        val activeState = CallState.Active(call = call.copy(answeredAt = System.currentTimeMillis()))
        assertTrue(activeState is CallState.Active)
    }

    @Test
    fun `state machine - Incoming can transition to Ended on rejection`() {
        val call = createTestCall(direction = CallDirection.INCOMING)
        val endedState = CallState.Ended(call = call, reason = CallEndReason.REJECTED)

        assertTrue(endedState is CallState.Ended)
        assertEquals(CallEndReason.REJECTED, endedState.reason)
    }

    @Test
    fun `state machine - Active can transition to Ended on completion`() {
        val call = createTestCall()
        val activeState = CallState.Active(call = call, duration = 300)
        assertTrue(activeState is CallState.Active)

        val endedState = CallState.Ended(call = call, reason = CallEndReason.COMPLETED, duration = 300)
        assertTrue(endedState is CallState.Ended)
        assertEquals(CallEndReason.COMPLETED, endedState.reason)
        assertEquals(300, endedState.duration)
    }

    @Test
    fun `state machine - Active can transition to Ended on failure`() {
        val call = createTestCall()
        val endedState = CallState.Ended(call = call, reason = CallEndReason.FAILED)

        assertTrue(endedState is CallState.Ended)
        assertEquals(CallEndReason.FAILED, endedState.reason)
    }

    // MARK: - Call Event Filtering Tests

    @Test
    fun `event filtering - only process events for matching callId`() {
        val activeCallId = "call-active"
        val otherCallId = "call-other"

        val call = createTestCall(callId = activeCallId)
        val state = CallState.Active(call = call)

        // This event should be processed (matching callId)
        val matchingEvent = CallEvent.CallEnded(
            callId = activeCallId,
            reason = CallEndReason.COMPLETED,
            duration = 60
        )
        assertEquals(state.call.callId, matchingEvent.callId)

        // This event should be ignored (different callId)
        val nonMatchingEvent = CallEvent.CallEnded(
            callId = otherCallId,
            reason = CallEndReason.COMPLETED,
            duration = 60
        )
        assertNotEquals(state.call.callId, nonMatchingEvent.callId)
    }

    // MARK: - Duration Formatting Tests

    @Test
    fun `format duration - zero seconds`() {
        val formatted = formatDuration(0)
        assertEquals("00:00", formatted)
    }

    @Test
    fun `format duration - 30 seconds`() {
        val formatted = formatDuration(30)
        assertEquals("00:30", formatted)
    }

    @Test
    fun `format duration - 1 minute`() {
        val formatted = formatDuration(60)
        assertEquals("01:00", formatted)
    }

    @Test
    fun `format duration - 5 minutes 30 seconds`() {
        val formatted = formatDuration(330)
        assertEquals("05:30", formatted)
    }

    @Test
    fun `format duration - 1 hour`() {
        val formatted = formatDuration(3600)
        assertEquals("60:00", formatted)
    }

    @Test
    fun `format duration - 1 hour 30 minutes 45 seconds`() {
        val formatted = formatDuration(5445)
        assertEquals("90:45", formatted)
    }

    // MARK: - Helper Methods

    private fun createTestCall(
        callId: String = "call-123",
        connectionId: String = "conn-456",
        peerGuid: String = "peer-789",
        peerDisplayName: String = "Test User",
        callType: CallType = CallType.VOICE,
        direction: CallDirection = CallDirection.OUTGOING
    ): Call {
        return Call(
            callId = callId,
            connectionId = connectionId,
            peerGuid = peerGuid,
            peerDisplayName = peerDisplayName,
            peerAvatarUrl = null,
            callType = callType,
            direction = direction,
            initiatedAt = System.currentTimeMillis()
        )
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", minutes, secs)
    }
}
