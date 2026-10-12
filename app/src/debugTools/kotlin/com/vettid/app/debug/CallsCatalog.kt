// Debug-only sample data for screenshots: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength", "MagicNumber")

package com.vettid.app.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import com.vettid.core.calls.CallDiagnostics
import com.vettid.core.calls.CallDirection
import com.vettid.core.calls.CallEnd
import com.vettid.core.calls.CallPhase
import com.vettid.core.calls.CallState
import com.vettid.core.calls.CallsDiagnostics
import com.vettid.core.calls.CandidateInfo
import com.vettid.core.calls.CryptorState
import com.vettid.core.calls.MediaConfig
import com.vettid.core.calls.MediaDiagnostics
import com.vettid.core.calls.MediaFaults
import com.vettid.core.data.calls.CallAvailability
import com.vettid.core.data.calls.CallHistoryEntry
import com.vettid.core.data.calls.CallOutcome
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.social.MessageInfo
import com.vettid.feature.calls.CallActions
import com.vettid.feature.calls.CallScreen
import com.vettid.feature.calls.CallTest
import com.vettid.feature.calls.CallTestContent
import com.vettid.feature.calls.CallTestUiState
import com.vettid.feature.calls.CallUiState
import com.vettid.feature.calls.MicrophonePrompt
import com.vettid.feature.connections.ConnectionDetailScreen
import com.vettid.feature.connections.ConnectionDetailUiState
import com.vettid.feature.connections.DetailActions
import com.vettid.feature.messages.ConversationActions
import com.vettid.feature.messages.ConversationScreen
import com.vettid.feature.messages.ConversationUiState
import java.time.Duration
import java.time.Instant

/**
 * The call screens (CALLS-PLAN 0.1.1 phase C1) with sample state, no vault and no call needed:
 * `--es vettid.start screen:calls.in_call`, and so on.
 */
internal object CallsCatalog {
    private const val NAME = "Samira Rivera"
    private val t0: Instant = Instant.parse("2026-10-12T10:00:00Z")
    private val sam = ConnectionInfo("c1", "Sam", ConnectionState.ACTIVE, favorite = true, firstName = "Samira", lastName = "Rivera")
    private val actions = CallActions({}, {}, {}, {}, {}, {})

    private fun live(phase: CallPhase, direction: CallDirection = CallDirection.OUTGOING, muted: Boolean = false, reconnecting: Boolean = false) =
        CallState.Live("c1", direction, phase, "01JB2Z6V9K3M4N5P6Q7R8S9T50", muted, if (phase == CallPhase.ACTIVE) t0 else null, reconnecting)

    @Composable
    private fun Call(call: CallState, speaker: Boolean = false, name: String? = NAME) =
        CallScreen(CallUiState(call, name, speaker, canSpeaker = true), actions, now = { t0.plusSeconds(252) })

    @Composable
    private fun Ended(reason: CallEnd, direction: CallDirection = CallDirection.OUTGOING) =
        Call(CallState.Ended("c1", direction, reason, "01JB2Z6V9K3M4N5P6Q7R8S9T50"))

    private fun msg(id: String, text: String, out: Boolean, min: Long) = MessageInfo("c1", id, out, text, t0.plusSeconds(min * 60), delivered = true, read = true)

    private val thread = listOf(msg("m1", "Are we still on for Saturday?", false, 0), msg("m2", "Yes, 10am at the market.", true, 2))
    private val calls = listOf(
        CallHistoryEntry("k1", "c1", true, CallOutcome.TALKED, t0.plusSeconds(180), Duration.ofSeconds(252)),
        CallHistoryEntry("k2", "c1", false, CallOutcome.MISSED, t0.plusSeconds(900)),
        CallHistoryEntry("k3", "c1", true, CallOutcome.NOT_ANSWERED, t0.plusSeconds(1200)),
        CallHistoryEntry("k4", "c1", true, CallOutcome.BUSY, t0.plusSeconds(1500)),
        CallHistoryEntry("k5", "c1", true, CallOutcome.DECLINED, t0.plusSeconds(1800)),
        CallHistoryEntry("k6", "c1", false, CallOutcome.DECLINED_HERE, t0.plusSeconds(2100)),
        CallHistoryEntry("k7", "c1", true, CallOutcome.FAILED, t0.plusSeconds(2400)),
    )

    private val media = MediaDiagnostics(
        micLevel = 0.21, remoteLevel = 0.18, packetsSent = 1204, packetsReceived = 1198, inboundAudioStreams = 1, sendCodec = "audio/opus",
        receiveCodec = "audio/opus", local = CandidateInfo("host", "udp", "wifi"), remote = CandidateInfo("host", "udp", ""), roundTripMs = 9.0,
        transport = mapOf("dtlsState" to "connected", "tlsVersion" to "FEFD", "dtlsCipher" to "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "srtpCipher" to "AES_CM_128_HMAC_SHA1_80"),
        sendCryptor = CryptorState.OK, receiveCryptor = CryptorState.OK,
    )

    @Composable
    private fun Tests(allowed: Boolean, config: MediaConfig = MediaConfig()) {
        val lines = CallTest.lines(allowed, config, live(CallPhase.ACTIVE), CallDiagnostics(310, 640, 420, media), CallsDiagnostics(staleEnded = 1))
        CallTestContent(CallTestUiState(allowed, config, lines), {}, {}, {})
    }

    val screens: Map<String, @Composable () -> Unit> = linkedMapOf(
        "calls.outgoing_calling" to { Call(live(CallPhase.CALLING)) },
        "calls.outgoing_ringing" to { Call(live(CallPhase.RINGING)) },
        "calls.connecting" to { Call(live(CallPhase.CONNECTING)) },
        "calls.incoming" to { Call(live(CallPhase.INCOMING, CallDirection.INCOMING)) },
        "calls.incoming_no_name" to { Call(live(CallPhase.INCOMING, CallDirection.INCOMING), name = null) },
        "calls.in_call" to { Call(live(CallPhase.ACTIVE)) },
        "calls.in_call_muted_speaker" to { Call(live(CallPhase.ACTIVE, muted = true), speaker = true) },
        "calls.in_call_reconnecting" to { Call(live(CallPhase.ACTIVE, reconnecting = true)) },
        "calls.ended_hung_up" to { Ended(CallEnd.HUNG_UP) },
        "calls.ended_peer_hung_up" to { Ended(CallEnd.PEER_HUNG_UP) },
        "calls.ended_declined" to { Ended(CallEnd.DECLINED) },
        "calls.ended_busy" to { Ended(CallEnd.BUSY) },
        "calls.ended_no_answer" to { Ended(CallEnd.NO_ANSWER) },
        "calls.ended_missed" to { Ended(CallEnd.MISSED, CallDirection.INCOMING) },
        "calls.ended_no_address" to { Ended(CallEnd.NO_ADDRESS) },
        "calls.ended_could_not_connect" to { Ended(CallEnd.COULD_NOT_CONNECT) },
        "calls.ended_connection_lost" to { Ended(CallEnd.CONNECTION_LOST) },
        "calls.ended_not_encrypted" to { Ended(CallEnd.NOT_ENCRYPTED) },
        "calls.ended_owner_check" to { Ended(CallEnd.OWNER_CHECK_REQUIRED) },
        "calls.ended_failed" to { Ended(CallEnd.FAILED) },
        "calls.microphone" to {
            Box {
                Call(live(CallPhase.INCOMING, CallDirection.INCOMING))
                MicrophonePrompt(NAME) {}
            }
        },
        "calls.test" to { Tests(allowed = true, config = MediaConfig(faults = MediaFaults(keepRemoteSsrc = true))) },
        "calls.test_refused" to { Tests(allowed = false) },
        "messages.conversation_calls" to {
            ConversationScreen(ConversationUiState("c1", sam, thread, loading = false, call = CallAvailability.READY, calls = calls), ConversationActions())
        },
        "messages.conversation_in_call" to {
            ConversationScreen(ConversationUiState("c1", sam, thread, loading = false, call = CallAvailability.IN_CALL, calls = calls.take(1)), ConversationActions())
        },
        "messages.conversation_calls_not_available" to {
            ConversationScreen(
                ConversationUiState("c1", sam, thread, loading = false, call = CallAvailability.NOT_IN_VAULT, callsNotAvailable = true),
                ConversationActions(),
            )
        },
        "connections.detail_call" to { ConnectionDetailScreen(ConnectionDetailUiState("c1", sam, loading = false, canCall = true), DetailActions()) },
    )
}
