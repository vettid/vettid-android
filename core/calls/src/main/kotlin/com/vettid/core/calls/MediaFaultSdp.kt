package com.vettid.core.calls

/** SDP changes for [MediaFaults]: two-phone tests only. */
internal object MediaFaultSdp {
    private val ssrc = Regex("^(a=ssrc:)(\\d+)", RegexOption.MULTILINE)
    private const val MAX_SSRC = 0xFFFFFFFFL

    /** [sdp] with every announced SSRC replaced by the number after it: the audio then arrives on an SSRC nobody announced. */
    fun misstateSsrc(sdp: String): String =
        ssrc.replace(sdp) { m -> m.groupValues[1] + ((m.groupValues[2].toLong() + 1) and MAX_SSRC) }
}
