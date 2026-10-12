package com.vettid.core.crypto.call

import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.kdf.Hkdf

/**
 * From `k_call` to the keys that encrypt a call's media frames (docs/calls/CALL-MEDIA.md; CALLS-PLAN 0.1.1 phase C1).
 *
 * The caller lists the [MediaSuite]s it can use in its SDP offer, the preferred one first; the answerer names the
 * one it chose in its SDP answer ([sdpWithOffer], [sdpWithChoice]); the vaults pass the SDP through unchanged. The
 * SDP is not signed, so the choice and the caller's list are bound into the key derivation instead: two devices
 * that do not agree on both derive different keys, and no media plays. Someone who changes the attribute on the way
 * can therefore end a call but cannot move it to a weaker suite.
 *
 * Each direction has its own key ([directionKey]), so that the frame cipher's nonce, which holds no randomness, can
 * never repeat under one key across the two senders.
 */
object CallMedia {
    /** Session-level SDP attribute of an offer: the caller's suites, decimal, separated by one space. */
    const val ATTR_OFFERED = "vettid-media-suites"

    /** Session-level SDP attribute of an answer: the one suite the answerer chose. */
    const val ATTR_CHOSEN = "vettid-media-suite"

    /** Frames the calling device sends. */
    const val DIRECTION_CALLER = "caller"

    /** Frames the answering device sends. */
    const val DIRECTION_CALLEE = "callee"

    /** A direction key: the key material given to the frame cryptor. 32 bytes for every suite. */
    const val DIRECTION_KEY_SIZE = 32
    const val MAX_SUITES = 8
    private const val MAX_SUITE = 65535

    /**
     * What the two devices agreed: [chosen] is one of [offered], the caller's list exactly as it was sent (numbers
     * this build does not know included: they are part of what the keys are bound to).
     */
    data class Negotiated(val chosen: MediaSuite, val offered: List<Int>) {
        init {
            if (!validList(offered) || chosen.id !in offered) throw CryptoException.Suite("media suite not offered")
        }
    }

    private fun validList(l: List<Int>) = l.size in 1..MAX_SUITES && l.all { it in 1..MAX_SUITE } && l.distinct().size == l.size

    /**
     * `HKDF-SHA-256(ikm = k_call, salt = the suite's media label, info = chosen || 0x00 || offered || 0x00 ||
     * direction || 0x00 || call_id, L = 32)`: `chosen` in decimal, `offered` as decimal numbers joined by one
     * space, [direction] [DIRECTION_CALLER] or [DIRECTION_CALLEE] (who sends the frames). All ASCII.
     */
    fun directionKey(kCall: ByteArray, n: Negotiated, direction: String, callId: String): ByteArray {
        if (kCall.size != CallWire.KEY_SIZE) throw CryptoException.Key("k_call size")
        if (direction != DIRECTION_CALLER && direction != DIRECTION_CALLEE) throw CryptoException.Key("direction")
        if (callId.isEmpty()) throw CryptoException.Key("call id")
        val info = "${n.chosen.id}\u0000${n.offered.joinToString(" ")}\u0000$direction\u0000$callId".toByteArray(Charsets.UTF_8)
        return Hkdf.derive(kCall, n.chosen.mediaLabel.toByteArray(Charsets.US_ASCII), info, DIRECTION_KEY_SIZE)
    }

    /** The list a caller sends: [supported] in the registry's order of preference. */
    fun offerOf(supported: Collection<MediaSuite>): List<Int> {
        val list = MediaSuite.ordered(supported).map { it.id }
        if (list.isEmpty()) throw CryptoException.Suite("no media suite")
        return list
    }

    /**
     * The answerer's choice: the first of the caller's suites, in the caller's order, that the answerer supports.
     * Numbers the answerer does not know are skipped. None in common: no call.
     */
    fun choose(offered: List<Int>, supported: Collection<MediaSuite>): Negotiated {
        if (!validList(offered)) throw CryptoException.Suite("media suites")
        val chosen = offered.firstNotNullOfOrNull { id -> MediaSuite.byId(id)?.takeIf { it in supported } }
            ?: throw CryptoException.Suite("no common media suite")
        return Negotiated(chosen, offered)
    }

    /** [sdp] with the caller's suite list as a session-level attribute. */
    fun sdpWithOffer(sdp: String, offered: List<Int>): String {
        if (!validList(offered)) throw CryptoException.Suite("media suites")
        return withAttribute(sdp, ATTR_OFFERED, offered.joinToString(" "))
    }

    /** [sdp] with the answerer's choice as a session-level attribute. */
    fun sdpWithChoice(sdp: String, chosen: MediaSuite): String = withAttribute(sdp, ATTR_CHOSEN, chosen.id.toString())

    /** The caller's list in an SDP offer. Missing, repeated or malformed: no call. */
    fun offeredIn(sdp: String): List<Int> {
        val list = attribute(sdp, ATTR_OFFERED).split(' ').map { number(it) }
        if (!validList(list)) throw CryptoException.Suite("media suites")
        return list
    }

    /**
     * The answerer's choice in an SDP answer, as the caller reads it: it must be a suite the caller [offered] (its
     * own list, as it sent it, never one read back from anywhere) and one this build knows.
     */
    fun chosenIn(sdp: String, offered: List<Int>): Negotiated {
        val id = number(attribute(sdp, ATTR_CHOSEN))
        if (id !in offered) throw CryptoException.Suite("media suite not offered")
        return Negotiated(MediaSuite.byId(id) ?: throw CryptoException.Suite("unknown media suite"), offered)
    }

    /**
     * [sdp] without either attribute: what a device hands to its media stack. The attributes are VettID's, read
     * before this; no media stack needs to see them.
     */
    fun sdpWithout(sdp: String): String {
        val eol = if (sdp.contains("\r\n")) "\r\n" else "\n"
        return lines(sdp).filterNot { it.startsWith("a=$ATTR_OFFERED:") || it.startsWith("a=$ATTR_CHOSEN:") }.joinToString(eol)
    }

    private fun number(s: String): Int {
        if (s.isEmpty() || s.length > 5 || s[0] == '0' || !s.all { it in '0'..'9' }) throw CryptoException.Suite("media suite number")
        return s.toInt()
    }

    private fun lines(sdp: String): List<String> = sdp.split("\r\n", "\n")

    /** The value of the one session-level `a=<name>:` line (before the first `m=`). */
    private fun attribute(sdp: String, name: String): String {
        val session = lines(sdp).takeWhile { !it.startsWith("m=") }
        val prefix = "a=$name:"
        // The attribute anywhere else, or twice, is not something a VettID device wrote.
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
