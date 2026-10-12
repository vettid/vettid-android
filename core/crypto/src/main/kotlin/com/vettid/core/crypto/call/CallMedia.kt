package com.vettid.core.crypto.call

import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.kdf.Hkdf

/**
 * From `k_call` to the keys that encrypt a call's media frames (docs/calls/CALL-MEDIA.md; CALLS-PLAN 0.1.1 phase C1).
 *
 * A **media suite** names the frame cipher and its format. The caller lists the suites it can use in its SDP offer,
 * the answerer names the one it chose in its SDP answer ([sdpWithOffer], [sdpWithChoice]); the vaults pass the SDP
 * through unchanged. The SDP is not signed, so the choice and the list are bound into the key derivation instead:
 * two devices that do not agree on both derive different keys, and no media plays (a changed attribute cannot move a
 * call to a weaker suite unnoticed).
 *
 * Each direction has its own key ([directionKey]), so that the frame cipher's nonce, which holds no randomness, can
 * never repeat under one key across the two senders.
 */
object CallMedia {
    /**
     * Suite 1: AES-128-GCM frames in the format of the media stack's frame cryptor ([CallFrames]). The only suite
     * today; a 256-bit suite would be a new number with a longer frame key, and nothing else here changes.
     */
    const val SUITE_AES_128_GCM = 1

    /** The suites this build can use, the preferred one first. */
    val SUPPORTED: List<Int> = listOf(SUITE_AES_128_GCM)

    /** Session-level SDP attribute of an offer: the caller's suites, decimal, separated by one space. */
    const val ATTR_OFFERED = "vettid-media-suites"

    /** Session-level SDP attribute of an answer: the one suite the answerer chose. */
    const val ATTR_CHOSEN = "vettid-media-suite"

    /** HKDF salt of the direction keys. */
    const val LABEL_MEDIA = "vettid/vms/2/call-media"

    /** The frame cryptor's key-derivation salt (its "ratchet salt"), the same on every platform. */
    const val LABEL_FRAME = "vettid/vms/2/call-frame"

    /** Frames the calling device sends. */
    const val DIRECTION_CALLER = "caller"

    /** Frames the answering device sends. */
    const val DIRECTION_CALLEE = "callee"

    /** A direction key: the key material given to the frame cryptor. 32 bytes for every suite. */
    const val DIRECTION_KEY_SIZE = 32
    const val MAX_SUITES = 8
    private const val MAX_SUITE = 65535

    /** What the two devices agreed: [chosen] is one of [offered], the caller's list as it was sent. */
    data class Negotiated(val chosen: Int, val offered: List<Int>) {
        init {
            if (!validList(offered) || chosen !in offered) throw CryptoException.Suite("media suites")
        }
    }

    private fun validList(l: List<Int>) = l.size in 1..MAX_SUITES && l.all { it in 1..MAX_SUITE } && l.distinct().size == l.size

    /**
     * `HKDF-SHA-256(ikm = k_call, salt = "vettid/vms/2/call-media", info = chosen || 0x00 || offered || 0x00 ||
     * direction || 0x00 || call_id, L = 32)`: `chosen` in decimal, `offered` as decimal numbers joined by one
     * space, [direction] [DIRECTION_CALLER] or [DIRECTION_CALLEE] (who sends the frames). All ASCII.
     */
    fun directionKey(kCall: ByteArray, n: Negotiated, direction: String, callId: String): ByteArray {
        if (kCall.size != CallWire.KEY_SIZE) throw CryptoException.Key("k_call size")
        if (direction != DIRECTION_CALLER && direction != DIRECTION_CALLEE) throw CryptoException.Key("direction")
        if (callId.isEmpty()) throw CryptoException.Key("call id")
        val info = "${n.chosen}\u0000${n.offered.joinToString(" ")}\u0000$direction\u0000$callId".toByteArray(Charsets.UTF_8)
        return Hkdf.derive(kCall, LABEL_MEDIA.toByteArray(Charsets.US_ASCII), info, DIRECTION_KEY_SIZE)
    }

    /** The answerer's choice: the first of the caller's suites, in the caller's order, that this build supports. */
    fun choose(offered: List<Int>, supported: List<Int> = SUPPORTED): Negotiated =
        Negotiated(offered.firstOrNull { it in supported } ?: throw CryptoException.Suite("no common media suite"), offered)

    /** [sdp] with the caller's suite list as a session-level attribute. */
    fun sdpWithOffer(sdp: String, offered: List<Int> = SUPPORTED): String {
        if (!validList(offered)) throw CryptoException.Suite("media suites")
        return withAttribute(sdp, ATTR_OFFERED, offered.joinToString(" "))
    }

    /** [sdp] with the answerer's choice as a session-level attribute. */
    fun sdpWithChoice(sdp: String, chosen: Int): String = withAttribute(sdp, ATTR_CHOSEN, chosen.toString())

    /** The caller's list in an SDP offer. Missing, repeated or malformed: no call. */
    fun offeredIn(sdp: String): List<Int> {
        val parts = attribute(sdp, ATTR_OFFERED).split(' ')
        val list = parts.map { number(it) }
        if (!validList(list)) throw CryptoException.Suite("media suites")
        return list
    }

    /** The answerer's choice in an SDP answer, checked against what the caller [offered]. */
    fun chosenIn(sdp: String, offered: List<Int>): Negotiated = Negotiated(number(attribute(sdp, ATTR_CHOSEN)), offered)

    private fun number(s: String): Int {
        if (s.isEmpty() || s.length > 5 || s[0] == '0' || !s.all { it in '0'..'9' }) throw CryptoException.Suite("media suite number")
        return s.toInt()
    }

    private fun lines(sdp: String): List<String> = sdp.split("\r\n", "\n")

    /** The value of the one session-level `a=<name>:` line (before the first `m=`). */
    private fun attribute(sdp: String, name: String): String {
        val session = lines(sdp).takeWhile { !it.startsWith("m=") }
        val prefix = "a=$name:"
        // An attribute of either name anywhere else, or twice, is not something this app wrote.
        val all = lines(sdp).count { it.startsWith(prefix) }
        val found = session.filter { it.startsWith(prefix) }
        if (found.size != 1 || all != 1) throw CryptoException.Suite("media suite attribute")
        return found[0].substring(prefix.length)
    }

    private fun withAttribute(sdp: String, name: String, value: String): String {
        val eol = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val l = lines(sdp).filterNot { it.startsWith("a=$ATTR_OFFERED:") || it.startsWith("a=$ATTR_CHOSEN:") }.toMutableList()
        val at = l.indexOfFirst { it.startsWith("m=") }.let { if (it < 0) l.indexOfLast { s -> s.isNotEmpty() } + 1 else it }
        l.add(at, "a=$name:$value")
        return l.joinToString(eol)
    }
}
