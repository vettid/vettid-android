package com.vettid.core.crypto

import com.vettid.core.crypto.call.CallFrames
import com.vettid.core.crypto.call.CallMedia
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CallMediaTest {
    private val offer =
        "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n"

    @Test
    fun theOfferCarriesTheListAsASessionAttribute() {
        val sdp = CallMedia.sdpWithOffer(offer)
        assertEquals(
            "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\na=vettid-media-suites:1\r\n" +
                "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n",
            sdp,
        )
        assertEquals(listOf(1), CallMedia.offeredIn(sdp))
        assertEquals(listOf(2, 1), CallMedia.offeredIn(CallMedia.sdpWithOffer(offer, listOf(2, 1))))
        // Written twice: once.
        assertEquals(sdp, CallMedia.sdpWithOffer(sdp))
        // Line feeds alone, and no media section.
        assertEquals("v=0\ns=-\na=vettid-media-suites:1\nm=audio 9 X 0\n", CallMedia.sdpWithOffer("v=0\ns=-\nm=audio 9 X 0\n"))
        assertEquals("v=0\ns=-\na=vettid-media-suites:1\n", CallMedia.sdpWithOffer("v=0\ns=-\n"))
    }

    @Test
    fun theAnswerCarriesTheChoice() {
        val n = CallMedia.choose(listOf(2, 1))
        assertEquals(CallMedia.Negotiated(1, listOf(2, 1)), n)
        val sdp = CallMedia.sdpWithChoice(offer, n.chosen)
        assertEquals(n, CallMedia.chosenIn(sdp, listOf(2, 1)))
        assertEquals(CallMedia.Negotiated(2, listOf(2, 1)), CallMedia.choose(listOf(2, 1), supported = listOf(1, 2)))
    }

    @Test
    fun aMissingRepeatedMisplacedOrMalformedAttributeMeansNoCall() {
        val bad = listOf(
            offer,
            offer.replace("a=group", "a=vettid-media-suites:1\r\na=vettid-media-suites:1\r\na=group"),
            "$offer" + "a=vettid-media-suites:1\r\n",
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
        assertThrows(CryptoException.Suite::class.java) { CallMedia.choose(listOf(7)) }
        // A choice that was not offered, or none.
        assertThrows(CryptoException.Suite::class.java) { CallMedia.chosenIn(CallMedia.sdpWithChoice(offer, 2), listOf(1)) }
        assertThrows(CryptoException.Suite::class.java) { CallMedia.chosenIn(offer, listOf(1)) }
        assertThrows(CryptoException.Suite::class.java) { CallMedia.chosenIn(CallMedia.sdpWithOffer(offer), listOf(1)) }
    }

    @Test
    fun keysOfTheWrongSizeOrDirectionAreRefused() {
        val n = CallMedia.Negotiated(1, listOf(1))
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
        val key = CallFrames.frameKey(ByteArray(32) { 7 })
        val frame = ByteArray(40) { it.toByte() }
        val sealed = CallFrames.seal(key, frame, 1, CallFrames.iv(1, 2, 3))
        assertArrayEquals(frame, CallFrames.open(key, sealed, 1))
        assertNull(CallFrames.open(CallFrames.frameKey(ByteArray(32) { 8 }), sealed, 1))
        assertNull(CallFrames.open(key, sealed.copyOf().also { it[0] = (it[0] + 1).toByte() }, 1))
        assertNull(CallFrames.open(key, sealed.copyOf().also { it[5] = (it[5] + 1).toByte() }, 1))
        assertNull(CallFrames.open(key, frame, 1))
        assertNull(CallFrames.open(key, ByteArray(1), 1))
    }
}
