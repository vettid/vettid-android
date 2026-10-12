package com.vettid.core.crypto

import com.vettid.core.crypto.call.CallFrames
import com.vettid.core.crypto.call.CallMedia
import com.vettid.core.crypto.call.MediaSuite
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CallMediaTest {
    private val offer =
        "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n"
    private val opus = "a=rtpmap:111 opus/48000/2\r\n"
    private val s1 = MediaSuite.AES_128_GCM
    private val s2 = MediaSuite.AES_256_GCM
    private val kCall = ByteArray(32) { (it * 7).toByte() }
    private val callId = "01JB2Z6V9K3M4N5P6Q7R8S9T50"

    /** One device of a call: what its engine can do, and the keys it ends up with. */
    private class Keys(val suite: MediaSuite, val send: ByteArray, val receive: ByteArray)

    private fun keys(n: CallMedia.Negotiated, caller: Boolean): Keys {
        val a = CallMedia.directionKey(kCall, n, CallMedia.DIRECTION_CALLER, callId)
        val b = CallMedia.directionKey(kCall, n, CallMedia.DIRECTION_CALLEE, callId)
        return if (caller) Keys(n.chosen, a, b) else Keys(n.chosen, b, a)
    }

    /**
     * A whole negotiation through the SDP, as the two devices run it. [onOffer] and [onAnswer] are someone on the
     * path changing the SDP. Returns the caller's and the answerer's keys.
     */
    private fun negotiate(
        caller: List<MediaSuite>,
        answerer: List<MediaSuite>,
        onOffer: (String) -> String = { it },
        onAnswer: (String) -> String = { it },
    ): Pair<Keys, Keys> {
        val sent = CallMedia.offerOf(caller)
        val offerSdp = onOffer(CallMedia.sdpWithOffer(offer, sent))
        val chosen = CallMedia.choose(CallMedia.offeredIn(offerSdp), answerer)
        val answerSdp = onAnswer(CallMedia.sdpWithChoice(offer, chosen.chosen))
        // The caller checks the answer against the list it sent, never against one it reads back.
        val atCaller = CallMedia.chosenIn(answerSdp, sent)
        return keys(atCaller, caller = true) to keys(chosen, caller = false)
    }

    private fun agree(k: Pair<Keys, Keys>) = k.first.suite == k.second.suite &&
        k.first.send.contentEquals(k.second.receive) && k.first.receive.contentEquals(k.second.send)

    @Test
    fun theRegistryIsClosedAndItsNumbersAreFixed() {
        assertEquals(listOf(1, 2), MediaSuite.entries.map { it.id })
        assertEquals(s1, MediaSuite.byId(1))
        assertEquals(s2, MediaSuite.byId(2))
        assertNull(MediaSuite.byId(3))
        assertEquals(16, s1.frameKeySize)
        assertEquals(32, s2.frameKeySize)
        assertEquals(listOf(s2, s1), MediaSuite.PREFERENCE)
        assertEquals("only what the shipped media stack can do is offered by default", listOf(s1), MediaSuite.DEFAULT)
        assertTrue(MediaSuite.entries.none { it.pqDtlsRequired })
        assertEquals(listOf(2, 1), CallMedia.offerOf(setOf(s1, s2)))
        assertEquals(listOf(1), CallMedia.offerOf(listOf(s1)))
        assertThrows(CryptoException.Suite::class.java) { CallMedia.offerOf(emptyList()) }
    }

    @Test
    fun aSuiteOneDeviceAndASuiteOneAndTwoDeviceAgreeOnOne() {
        // Either may be the caller.
        for (k in listOf(negotiate(listOf(s1), listOf(s1, s2)), negotiate(listOf(s1, s2), listOf(s1)))) {
            assertEquals(s1, k.first.suite)
            assertTrue(agree(k))
        }
    }

    @Test
    fun twoSuiteOneAndTwoDevicesAgreeOnTwo() {
        val k = negotiate(listOf(s1, s2), listOf(s1, s2))
        assertEquals(s2, k.first.suite)
        assertTrue(agree(k))
        // Not the keys of a suite 1 call with the same call key.
        val one = negotiate(listOf(s1), listOf(s1))
        assertFalse(k.first.send.contentEquals(one.first.send))
        assertEquals(32, CallFrames.frameKey(k.first.send, s2).size)
        assertEquals(
            "e2637ed9e4ab88d26b3f34869104e58d355e1eb7b6c5b7d3aea521bda4fd83da",
            Bytes.hex(CallFrames.frameKey(Bytes.unhex("129e7ea015351c448613b72d821141780fbc2681c9562f2b47894d999c609617"), s2)),
        )
    }

    @Test
    fun noOneOnThePathCanMoveACallToAWeakerSuite() {
        val both = listOf(s1, s2)
        // The offer's list cut down to suite 1: the answerer picks 1 for the list "1", the caller knows it sent
        // "2 1". Both believe suite 1; the keys differ, so nothing decrypts and the call ends.
        val cut = negotiate(both, both, onOffer = { it.replace("a=vettid-media-suites:2 1", "a=vettid-media-suites:1") })
        assertEquals(s1, cut.first.suite)
        assertEquals(s1, cut.second.suite)
        assertFalse(agree(cut))
        // The list reordered: the same.
        val reordered = negotiate(both, both, onOffer = { it.replace("a=vettid-media-suites:2 1", "a=vettid-media-suites:1 2") })
        assertFalse(agree(reordered))
        // The answer's choice rewritten from 2 to 1: the answerer encrypts for suite 2, the caller derives for 1.
        val rewritten = negotiate(both, both, onAnswer = { it.replace("a=vettid-media-suite:2", "a=vettid-media-suite:1") })
        assertEquals(s1, rewritten.first.suite)
        assertEquals(s2, rewritten.second.suite)
        assertFalse(agree(rewritten))
        // A choice the caller never offered, a number nobody knows, or no attribute at all: refused at once.
        assertThrows(CryptoException.Suite::class.java) {
            negotiate(listOf(s1), both, onAnswer = { it.replace("a=vettid-media-suite:1", "a=vettid-media-suite:2") })
        }
        assertThrows(CryptoException.Suite::class.java) {
            negotiate(both, both, onAnswer = { it.replace("a=vettid-media-suite:2", "a=vettid-media-suite:9") })
        }
        assertThrows(CryptoException.Suite::class.java) { negotiate(both, both, onAnswer = { CallMedia.sdpWithout(it) }) }
        assertThrows(CryptoException.Suite::class.java) { negotiate(both, both, onOffer = { CallMedia.sdpWithout(it) }) }
    }

    @Test
    fun unknownNumbersInAnOfferAreSkippedAndStayInTheBinding() {
        // A newer caller offers a suite 7 this build does not know, then 1.
        val n = CallMedia.choose(listOf(7, 1), listOf(s1, s2))
        assertEquals(CallMedia.Negotiated(s1, listOf(7, 1)), n)
        assertFalse(
            CallMedia.directionKey(kCall, n, CallMedia.DIRECTION_CALLER, callId)
                .contentEquals(CallMedia.directionKey(kCall, CallMedia.Negotiated(s1, listOf(1)), CallMedia.DIRECTION_CALLER, callId)),
        )
        // Nothing in common: no call.
        assertThrows(CryptoException.Suite::class.java) { CallMedia.choose(listOf(7), listOf(s1, s2)) }
        assertThrows(CryptoException.Suite::class.java) { CallMedia.choose(listOf(2), listOf(s1)) }
        assertThrows(CryptoException.Suite::class.java) { CallMedia.Negotiated(s2, listOf(1)) }
    }

    @Test
    fun theOfferCarriesTheListAsASessionAttribute() {
        val sdp = CallMedia.sdpWithOffer(offer, listOf(1))
        assertEquals(
            "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\na=vettid-media-suites:1\r\n" +
                "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n",
            sdp,
        )
        assertEquals(listOf(1), CallMedia.offeredIn(sdp))
        assertEquals(listOf(2, 1), CallMedia.offeredIn(CallMedia.sdpWithOffer(offer, listOf(2, 1))))
        assertEquals(offer, CallMedia.sdpWithout(sdp))
        assertEquals(offer, CallMedia.sdpWithout(CallMedia.sdpWithChoice(offer, s1)))
        assertEquals(offer, CallMedia.sdpWithout(offer))
        // Written twice: once.
        assertEquals(sdp, CallMedia.sdpWithOffer(sdp, listOf(1)))
        // Line feeds alone, and no media section.
        assertEquals("v=0\ns=-\na=vettid-media-suites:1\nm=audio 9 X 0\n", CallMedia.sdpWithOffer("v=0\ns=-\nm=audio 9 X 0\n", listOf(1)))
        assertEquals("v=0\ns=-\na=vettid-media-suites:1\n", CallMedia.sdpWithOffer("v=0\ns=-\n", listOf(1)))
    }

    @Test
    fun aMissingRepeatedMisplacedOrMalformedAttributeMeansNoCall() {
        val bad = listOf(
            offer,
            offer.replace("a=group", "a=vettid-media-suites:1\r\na=vettid-media-suites:1\r\na=group"),
            offer + "a=vettid-media-suites:1\r\n",
            offer.replace("a=group", "a=vettid-media-suites:\r\na=group"),
            offer.replace("a=group", "a=vettid-media-suites:01\r\na=group"),
            offer.replace("a=group", "a=vettid-media-suites:1  2\r\na=group"),
            offer.replace("a=group", "a=vettid-media-suites:1 1\r\na=group"),
            offer.replace("a=group", "a=vettid-media-suites:0\r\na=group"),
            offer.replace("a=group", "a=vettid-media-suites:70000\r\na=group"),
            offer.replace("a=group", "a=vettid-media-suites:1 x\r\na=group"),
            offer.replace("a=group", "a=vettid-media-suites:1 2 3 4 5 6 7 8 9\r\na=group"),
        )
        for (sdp in bad) assertThrows(sdp, CryptoException.Suite::class.java) { CallMedia.offeredIn(sdp) }
        assertThrows(CryptoException.Suite::class.java) { CallMedia.chosenIn(offer, listOf(1)) }
        assertThrows(CryptoException.Suite::class.java) { CallMedia.chosenIn(CallMedia.sdpWithOffer(offer, listOf(1)), listOf(1)) }
    }

    /** Review F1: media in a section the frame cryptors do not cover would play unencrypted. */
    @Test
    fun onlyOneAudioSectionWithOpusAloneIsACall() {
        CallMedia.requireOneOpusAudioSection(offer + opus)
        CallMedia.requireOneOpusAudioSection((offer + opus).replace("\r\n", "\n"))
        CallMedia.requireOneOpusAudioSection(offer.replace(" 111\r\n", " 111 63\r\n") + opus + "a=rtpmap:63 OPUS/48000/2\r\n")
        val bad = listOf(
            "v=0\r\ns=-\r\n", // no media
            offer + opus + "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:1\r\n", // a second audio section
            offer + opus + "m=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtpmap:96 VP8/90000\r\n",
            offer + opus + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n",
            (offer + opus).replace("m=audio", "m=video"),
            offer, // no rtpmap for its payload type
            offer.replace(" 111\r\n", " 111 0\r\n") + opus, // Opus and something else
            offer + "a=rtpmap:111 PCMU/8000\r\n",
            "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF\r\n",
        )
        for (sdp in bad) assertThrows(sdp, CryptoException.Protocol::class.java) { CallMedia.requireOneOpusAudioSection(sdp) }
    }

    /** Review N1: a signalled SSRC would leave every other SSRC of the section outside the frame decryption. */
    @Test
    fun theOtherSidesSdpReachesTheStackWithoutSsrcLines() {
        val withSsrc = offer + opus + "a=msid:vettid audio0\r\na=ssrc:1234 cname:x\r\na=ssrc:1234 msid:vettid audio0\r\n" +
            "a=ssrc-group:FID 1234 5678\r\na=ssrc:5678 cname:x\r\n"
        assertEquals(offer + opus + "a=msid:vettid audio0\r\n", CallMedia.sdpWithoutSsrc(withSsrc))
        assertEquals(offer + opus, CallMedia.sdpWithoutSsrc(offer + opus))
        assertEquals("v=0\na=msid:a b\n", CallMedia.sdpWithoutSsrc("v=0\na=ssrc:1 cname:x\na=msid:a b\n"))
        // Still one Opus audio section.
        CallMedia.requireOneOpusAudioSection(CallMedia.sdpWithoutSsrc(withSsrc))
    }

    @Test
    fun keysOfTheWrongSizeOrDirectionAreRefused() {
        val n = CallMedia.Negotiated(s1, listOf(1))
        assertThrows(CryptoException.Key::class.java) { CallMedia.directionKey(ByteArray(16), n, CallMedia.DIRECTION_CALLER, "c") }
        assertThrows(CryptoException.Key::class.java) { CallMedia.directionKey(ByteArray(32), n, "both", "c") }
        assertThrows(CryptoException.Key::class.java) { CallMedia.directionKey(ByteArray(32), n, CallMedia.DIRECTION_CALLER, "") }
    }

    @Test
    fun ivIsSsrcTimestampAndTimestampMinusCounter() {
        assertArrayEquals(Bytes.unhex("0a0b0c0d" + "00010000" + "0000fffd"), CallFrames.iv(0x0a0b0c0d, 0x10000, 3))
        // The counter wraps at 65535, not 65536.
        assertArrayEquals(CallFrames.iv(1, 500, 0), CallFrames.iv(1, 500, 0xFFFF))
    }

    @Test
    fun aWrongKeyAChangedHeaderOrAPlainFrameDoesNotOpen() {
        for (suite in MediaSuite.entries) {
            val key = CallFrames.frameKey(ByteArray(32) { 7 }, suite)
            val frame = ByteArray(40) { it.toByte() }
            val sealed = CallFrames.seal(key, frame, 1, CallFrames.iv(1, 2, 3))
            assertArrayEquals(frame, CallFrames.open(key, sealed, 1))
            assertNull(CallFrames.open(CallFrames.frameKey(ByteArray(32) { 8 }, suite), sealed, 1))
            assertNull(CallFrames.open(key, sealed.copyOf().also { it[0] = (it[0] + 1).toByte() }, 1))
            assertNull(CallFrames.open(key, sealed.copyOf().also { it[5] = (it[5] + 1).toByte() }, 1))
            assertNull(CallFrames.open(key, frame, 1))
            assertNull(CallFrames.open(key, ByteArray(1), 1))
        }
    }
}
