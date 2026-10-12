package com.vettid.core.calls

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpikeCodecsTest {
    private val sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\n" +
        (1..12).joinToString("") { "a=candidate:$it 1 udp 2122260223 192.168.1.$it 5000$it typ host generation 0\r\n" }

    @Test
    fun anOfferSurvivesTheBlobAndFitsAQrCode() {
        val blob = SignalBlob.encode(SignalBlob.Kind.OFFER, sdp)
        assertTrue(blob.startsWith("VC0O2."))
        assertFalse(blob.contains('\n'))
        assertTrue("smaller than the SDP", blob.length < sdp.length)
        assertEquals(SignalBlob.Decoded(SignalBlob.Kind.OFFER, sdp), SignalBlob.decode("  $blob\n"))
        assertEquals(SignalBlob.Kind.ANSWER, SignalBlob.decode(SignalBlob.encode(SignalBlob.Kind.ANSWER, sdp))?.kind)
    }

    @Test
    fun aTypicalOfferIsAModestQrCode() {
        val offer = listOf(
            "v=0", "o=- 4611731400430051336 2 IN IP4 127.0.0.1", "s=-", "t=0 0", "a=group:BUNDLE 0", "a=extmap-allow-mixed",
            "a=msid-semantic: WMS vettid", "m=audio 9 UDP/TLS/RTP/SAVPF 111", "c=IN IP4 0.0.0.0", "a=rtcp:9 IN IP4 0.0.0.0",
            "a=candidate:3127283422 1 udp 2122260223 192.168.86.27 44321 typ host generation 0 network-id 3 network-cost 10",
            "a=candidate:1510613869 1 udp 2122194687 fd3a:91c2:7b0e:1:8c5e:22ff:fe41:9a03 51022 typ host generation 0 network-id 4",
            "a=candidate:559267639 1 udp 2122136831 2001:db8:4c0:91a2:8c5e:22ff:fe41:9a03 39211 typ host generation 0 network-id 5",
            "a=ice-ufrag:Xq7B", "a=ice-pwd:rT5nYk0wq3mUu1b8LrZ2dJpH", "a=ice-options:trickle renomination",
            "a=fingerprint:sha-256 " + (0 until 32).joinToString(":") { "%02X".format((it * 37 + 11) and 0xFF) },
            "a=setup:actpass", "a=mid:0", "a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level",
            "a=extmap:2 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time",
            "a=extmap:3 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01",
            "a=extmap:4 urn:ietf:params:rtp-hdrext:sdes:mid", "a=sendrecv", "a=msid:vettid audio0", "a=rtcp-mux", "a=rtcp-rsize",
            "a=rtpmap:111 opus/48000/2", "a=rtcp-fb:111 transport-cc", "a=fmtp:111 minptime=10;useinbandfec=1",
            "a=ssrc:2915604713 cname:pQ7c1mXw0nK9aZt3", "a=ssrc:2915604713 msid:vettid audio0", "",
        ).joinToString("\r\n")
        val blob = SignalBlob.encode(SignalBlob.Kind.OFFER, offer)
        assertEquals(offer, SignalBlob.decode(blob)?.sdp)
        // A QR code of version 17 at level M holds 504 bytes (85 modules a side): about a third of the SDP's size.
        assertTrue("${blob.length} characters for ${offer.length}", blob.length < 504)
    }

    @Test
    fun whatIsNotABlobIsRefused() {
        assertNull(SignalBlob.decode(""))
        assertNull(SignalBlob.decode("hello"))
        assertNull(SignalBlob.decode("VC0O2.!!!"))
        assertNull(SignalBlob.decode("VC0O2.AAAA"))
        assertNull(SignalBlob.decode(SignalBlob.encode(SignalBlob.Kind.OFFER, "not an sdp")))
        assertNull(SignalBlob.decode(SignalBlob.encode(SignalBlob.Kind.OFFER, sdp).dropLast(8)))
        // A deflate bomb: 1 MiB of zeros.
        assertNull(SignalBlob.decode(SignalBlob.encode(SignalBlob.Kind.OFFER, "v=0" + "0".repeat(1 shl 20))))
    }

    @Test
    fun aKeyIsSixtyFourHexCharacters() {
        val key = CallKey.random()
        assertEquals(32, key.size)
        assertArrayEquals(key, CallKey.parse(CallKey.hex(key)))
        assertArrayEquals(key, CallKey.parse(CallKey.hex(key).uppercase().chunked(4).joinToString(" ")))
        assertNull(CallKey.parse(CallKey.hex(key).dropLast(1)))
        assertNull(CallKey.parse(CallKey.hex(key).dropLast(1) + "g"))
        assertNull(CallKey.parse("+" + CallKey.hex(key).drop(1)))
    }

    @Test
    fun theCheckTellsKeysApartAndTheWrongKeyIsAnother() {
        val key = ByteArray(32) { it.toByte() }
        assertEquals(8, CallKey.check(key).length)
        assertEquals(CallKey.check(key), CallKey.check(key.copyOf()))
        assertNotEquals(CallKey.check(key), CallKey.check(CallKey.wrong(key)))
        assertFalse(CallKey.hex(key).contains(CallKey.check(key)))
    }

    @Test
    fun statsNameTheCandidatePairTheCodecAndTheLevels() {
        val big = java.math.BigInteger::valueOf
        val inbound = mapOf(
            "kind" to "audio", "audioLevel" to 0.5, "packetsReceived" to 120L, "codecId" to "C",
            "concealedSamples" to big(960), "totalSamplesReceived" to big(48000),
        )
        val pair = mapOf("localCandidateId" to "L", "remoteCandidateId" to "R", "state" to "succeeded", "currentRoundTripTime" to 0.004)
        val local = mapOf("candidateType" to "host", "protocol" to "udp", "networkType" to "wifi", "address" to "192.168.1.2")
        val transport = mapOf("selectedCandidatePairId" to "P", "dtlsState" to "connected", "srtpCipher" to "AEAD_AES_128_GCM")
        val stats = CallStats.from(
            listOf(
                StatEntry("S", "media-source", mapOf("kind" to "audio", "audioLevel" to 0.25)),
                StatEntry("V", "media-source", mapOf("kind" to "video")),
                StatEntry("I", "inbound-rtp", inbound),
                StatEntry("O", "outbound-rtp", mapOf("kind" to "audio", "packetsSent" to big(10), "codecId" to "C")),
                StatEntry("C", "codec", mapOf("mimeType" to "audio/opus")),
                StatEntry("T", "transport", transport),
                StatEntry("P", "candidate-pair", pair),
                StatEntry("L", "local-candidate", local),
                StatEntry("R", "remote-candidate", mapOf("candidateType" to "prflx", "protocol" to "udp")),
            ),
        )
        assertEquals(0.25, stats.micLevel, 0.0)
        assertEquals(0.5, stats.remoteLevel, 0.0)
        assertEquals(120, stats.packetsReceived)
        assertEquals(10, stats.packetsSent)
        assertEquals(960, stats.concealedSamples)
        assertEquals("audio/opus", stats.sendCodec)
        assertEquals("audio/opus", stats.receiveCodec)
        assertEquals("host / udp / wifi", stats.local.toString())
        assertEquals("prflx / udp", stats.remote.toString())
        assertEquals(4.0, stats.roundTripMs!!, 1e-9)
        assertEquals("AEAD_AES_128_GCM", stats.srtpCipher)
        // No address anywhere in what is shown.
        assertFalse(stats.toString().contains("192.168"))
    }

    @Test
    fun anEmptyReportIsSilence() {
        assertEquals(CallStats(), CallStats.from(emptyList()))
    }
}
