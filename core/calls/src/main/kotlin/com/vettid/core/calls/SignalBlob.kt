package com.vettid.core.calls

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * C0 signalling: an offer or an answer (the whole SDP, candidates included) as one line of text that fits a QR
 * code and the clipboard. No server, no vault, no relay. Nothing in it is secret: the media key is never part of it.
 */
object SignalBlob {
    enum class Kind(val prefix: String) { OFFER("VC0O2."), ANSWER("VC0A2.") }

    data class Decoded(val kind: Kind, val sdp: String)

    /** More than any audio-only SDP; an inflated blob beyond it is refused. */
    private const val MAX_SDP_BYTES = 64 * 1024
    private const val BUFFER_BYTES = 4096

    /**
     * What every audio-only SDP of the media stack says, as a deflate dictionary (both phones run the same build):
     * the QR code then carries little more than the fingerprint, the ICE credentials and the candidates, and is
     * small enough to scan from a phone's screen.
     */
    private val dictionary: ByteArray = listOf(
        "a=ssrc:", " cname:", " msid:vettid audio0", "a=fmtp:111 minptime=10;useinbandfec=1", "a=rtcp-fb:111 transport-cc",
        "a=rtpmap:111 opus/48000/2", "a=rtcp-rsize", "a=rtcp-mux", "a=msid:vettid audio0", "a=recvonly", "a=sendrecv",
        "a=extmap:4 urn:ietf:params:rtp-hdrext:sdes:mid",
        "a=extmap:3 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01",
        "a=extmap:2 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time",
        "a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level", "a=mid:0", "a=setup:active", "a=setup:actpass",
        "a=fingerprint:sha-256 ", "a=ice-options:trickle renomination", "a=ice-pwd:", "a=ice-ufrag:",
        " network-id 1 network-cost 10", " generation 0", " typ host", " udp 2122260223 192.168.", " udp 2122194687 ",
        "a=candidate:", " 1 udp ", "a=rtcp:9 IN IP4 0.0.0.0", "c=IN IP4 0.0.0.0", "m=audio 9 UDP/TLS/RTP/SAVPF 111",
        "a=msid-semantic: WMS vettid", "a=extmap-allow-mixed", "a=group:BUNDLE 0", "t=0 0", "s=-", " 2 IN IP4 127.0.0.1",
        "v=0\r\no=- ",
    ).joinToString("\r\n").toByteArray(Charsets.UTF_8)

    fun encode(kind: Kind, sdp: String): String {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setDictionary(dictionary)
        deflater.setInput(sdp.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return kind.prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    /** The offer or answer in [text] (surrounding whitespace ignored), or null if it is not one. */
    fun decode(text: String): Decoded? {
        val line = text.trim()
        val kind = Kind.entries.firstOrNull { line.startsWith(it.prefix) }
        val packed = kind?.let {
            try {
                Base64.getUrlDecoder().decode(line.removePrefix(it.prefix))
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        val sdp = packed?.let(::inflate)
        return if (kind != null && sdp != null) Decoded(kind, sdp) else null
    }

    /** The text in [packed], or null if it is not deflate data, is cut short, or is larger than any SDP. */
    private fun inflate(packed: ByteArray): String? {
        val inflater = Inflater(true)
        inflater.setDictionary(dictionary)
        inflater.setInput(packed)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var ok = true
        try {
            while (ok && !inflater.finished()) {
                val n = inflater.inflate(buffer)
                out.write(buffer, 0, n)
                ok = out.size() <= MAX_SDP_BYTES && !(n == 0 && (inflater.needsInput() || inflater.needsDictionary()))
            }
        } catch (_: DataFormatException) {
            ok = false
        } finally {
            inflater.end()
        }
        return out.toString(Charsets.UTF_8.name()).takeIf { ok && it.startsWith("v=0") }
    }
}
