@file:Suppress("MaxLineLength") // The approved texts, one a line.

package com.vettid.feature.calls

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.vettid.core.calls.CallDiagnostics
import com.vettid.core.calls.CallEnd
import com.vettid.core.calls.CallPhase
import com.vettid.core.calls.CallState
import com.vettid.core.calls.CallsDiagnostics
import com.vettid.core.calls.CallDirection
import com.vettid.core.calls.MediaConfig
import com.vettid.core.calls.MediaDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallTextsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun ended(reason: CallEnd) = context.getString(CallTexts.ended(reason), "Ada Lovelace")

    /** The owner's approved texts (2026-10-12), word for word, one for every ending. */
    @Test
    fun everyEndingHasItsApprovedLine() {
        val approved = mapOf(
            CallEnd.HUNG_UP to "Call ended",
            CallEnd.PEER_HUNG_UP to "Ada Lovelace ended the call",
            CallEnd.DECLINED to "Ada Lovelace declined",
            CallEnd.DECLINED_HERE to "Call declined",
            CallEnd.BUSY to "Ada Lovelace is on another call",
            CallEnd.NO_ANSWER to "No answer",
            CallEnd.MISSED to "Missed call from Ada Lovelace",
            CallEnd.ANSWERED_ELSEWHERE to "Answered on another device",
            CallEnd.UNAVAILABLE to "This call is no longer available",
            CallEnd.OWN_VAULT_BUSY to "You are already on a call",
            CallEnd.OWNER_CHECK_REQUIRED to "Confirm it's you before calling. Your daily check is due.",
            CallEnd.NOT_SUPPORTED to "Calls are not available for your vault yet",
            CallEnd.NO_ADDRESS to "VettID cannot reach the network for calls. Check that this phone is online and that VettID is allowed to use the network.",
            CallEnd.COULD_NOT_CONNECT to "Could not connect. For now, both phones need to be on the same network.",
            CallEnd.CONNECTION_LOST to "Connection lost",
            CallEnd.NOT_ENCRYPTED to "This call could not be encrypted, so it was ended. Nothing was played without encryption.",
            CallEnd.LOCKED to "Call ended because your vault was locked",
            CallEnd.FAILED to "The call failed. Try again.",
        )
        assertEquals(CallEnd.entries.toSet(), approved.keys)
        for ((reason, text) in approved) assertEquals(reason.name, text, ended(reason))
        assertEquals("no two endings share a line", approved.size, approved.values.toSet().size)
    }

    @Test
    fun theOtherApprovedTexts() {
        assertEquals("End-to-end encrypted. Only you and Ada Lovelace can hear this call.", context.getString(R.string.calls_encryption, "Ada Lovelace"))
        assertEquals("Ada Lovelace is calling", context.getString(CallTexts.phase(CallPhase.INCOMING, false)!!, "Ada Lovelace"))
        assertEquals("Calling…", context.getString(CallTexts.phase(CallPhase.STARTING, false)!!))
        assertEquals("Calling…", context.getString(CallTexts.phase(CallPhase.CALLING, false)!!))
        assertEquals("Ringing…", context.getString(CallTexts.phase(CallPhase.RINGING, false)!!))
        assertEquals("Connecting…", context.getString(CallTexts.phase(CallPhase.CONNECTING, false)!!))
        assertNull("an active call shows its time", CallTexts.phase(CallPhase.ACTIVE, false))
        assertEquals(
            "VettID needs the microphone so Ada Lovelace can hear you. It is used only during calls.",
            context.getString(R.string.calls_microphone_body, "Ada Lovelace"),
        )
        assertEquals(
            listOf("Answer", "Decline", "Mute", "Speaker", "Hang up"),
            listOf(R.string.calls_answer, R.string.calls_decline, R.string.calls_mute, R.string.calls_speaker, R.string.calls_hang_up).map(context::getString),
        )
    }

    @Test
    fun endingsThatSayWhatToDoStayUntilTheyAreClosed() {
        assertTrue(CallTexts.staysOpen(CallEnd.NOT_ENCRYPTED) && CallTexts.staysOpen(CallEnd.NO_ADDRESS) && CallTexts.staysOpen(CallEnd.COULD_NOT_CONNECT))
        assertFalse(CallTexts.staysOpen(CallEnd.HUNG_UP) || CallTexts.staysOpen(CallEnd.PEER_HUNG_UP) || CallTexts.staysOpen(CallEnd.DECLINED))
    }

    @Test
    fun theClock() {
        assertEquals("0:00", CallTexts.clock(0))
        assertEquals("4:12", CallTexts.clock(252))
        assertEquals("1:04:12", CallTexts.clock(3852))
        assertEquals("0:00", CallTexts.clock(-3))
    }

    /** The test screen's lines: stable labels, and nothing but counts, states and types. */
    @Test
    fun theTestScreensLinesAreStableAndCarryNoIdentifier() {
        val call = CallState.Live("conn-secret-id", CallDirection.OUTGOING, CallPhase.ACTIVE, "01JB2Z6V9K3M4N5P6Q7R8S9T50")
        val media = MediaDiagnostics(inboundAudioStreams = 1, transport = mapOf("tlsVersion" to "FEFC", "dtlsState" to "connected"))
        val lines = CallTest.lines(true, MediaConfig(dtlsPqcTrial = true), call, CallDiagnostics(tapToRingingMs = 420, media = media), CallsDiagnostics(staleEnded = 1))
        val map = lines.toMap()
        assertEquals(lines.size, map.size)
        assertEquals("true", map["testsAllowed"])
        assertEquals("dtlsPqcTrial", map["faults"])
        assertEquals("live ACTIVE", map["call"])
        assertEquals("1", map["inboundAudioStreams"])
        assertEquals("FEFC", map["tlsVersion"])
        assertEquals("420", map["tapToRingingMs"])
        assertEquals("1", map["staleEnded"])
        for (label in listOf("sendCryptor", "receiveCryptor", "cryptorsMoved", "dtlsCipher", "srtpCipher", "path", "droppedForTime", "refused", "errors")) {
            assertTrue(label, map.containsKey(label))
        }
        val text = lines.joinToString("\n") { "${it.first}: ${it.second}" }
        assertFalse(text.contains("conn-secret-id") || text.contains("01JB2Z6V9K3M4N5P6Q7R8S9T50"))
        // Every switch flips its own flag and nothing else.
        for ((label, on) in CallTest.SWITCHES) {
            val c = CallTest.toggled(MediaConfig(), label)
            assertTrue(label, on(c))
            assertEquals(label, 1, CallTest.SWITCHES.count { it.second(c) })
        }
    }

    /** Review P2 of #128: the microphone prompt for answering goes when the call it was for is no longer ringing. */
    @Test
    fun aMicrophonePromptForAnAnswerEndsWithTheRinging() {
        val answer = com.vettid.core.calls.MicrophoneRequest(null)
        val place = com.vettid.core.calls.MicrophoneRequest("c1")
        val ringing = CallState.Live("c1", CallDirection.INCOMING, CallPhase.INCOMING)
        assertTrue(CallViewModel.stillWanted(answer, ringing))
        assertFalse("the caller gave up", CallViewModel.stillWanted(answer, CallState.Ended("c1", CallDirection.INCOMING, CallEnd.MISSED, null)))
        assertFalse(CallViewModel.stillWanted(answer, CallState.Idle))
        assertTrue("a call to place does not depend on another call", CallViewModel.stillWanted(place, CallState.Idle))
    }
}
