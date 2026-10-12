package com.vettid.core.calls

/** One entry of the stack's statistics report (`RTCStats`), detached from the native library for tests. */
data class StatEntry(val id: String, val type: String, val members: Map<String, Any?>)

/** One end of the candidate pair in use. Types only: no address is shown or logged. */
data class CandidateInfo(val type: String, val protocol: String, val network: String) {
    override fun toString(): String = listOf(type, protocol, network).filter { it.isNotEmpty() }.joinToString(" / ")
}

/**
 * What the spike screen shows of a statistics report. The levels are the stack's `audioLevel` (0 = silence,
 * 1 = full scale, linear): [micLevel] is this phone's microphone before encoding, [remoteLevel] is what was
 * received, decrypted and decoded from the other phone.
 */
data class CallStats(
    val micLevel: Double = 0.0,
    val remoteLevel: Double = 0.0,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val bytesReceived: Long = 0,
    val concealedSamples: Long = 0,
    val samplesReceived: Long = 0,
    val sendCodec: String = "",
    val receiveCodec: String = "",
    val local: CandidateInfo? = null,
    val remote: CandidateInfo? = null,
    val pairState: String = "",
    val roundTripMs: Double? = null,
    val dtlsState: String = "",
    val srtpCipher: String = "",
) {
    companion object {
        private const val MILLIS = 1000.0

        fun from(entries: Collection<StatEntry>): CallStats {
            val byId = entries.associateBy { it.id }

            fun audio(type: String) = entries.firstOrNull { it.type == type && it.text("kind") == "audio" }
            val source = audio("media-source")
            val inbound = audio("inbound-rtp")
            val outbound = audio("outbound-rtp")
            val transport = entries.firstOrNull { it.type == "transport" && it.text("selectedCandidatePairId").isNotEmpty() }
            val pair = transport?.let { byId[it.text("selectedCandidatePairId")] }

            fun candidate(key: String) = pair?.let { byId[it.text(key)] }?.let {
                CandidateInfo(it.text("candidateType"), it.text("protocol"), it.text("networkType"))
            }
            return CallStats(
                micLevel = source.number("audioLevel"),
                remoteLevel = inbound.number("audioLevel"),
                packetsSent = outbound.number("packetsSent").toLong(),
                packetsReceived = inbound.number("packetsReceived").toLong(),
                bytesReceived = inbound.number("bytesReceived").toLong(),
                concealedSamples = inbound.number("concealedSamples").toLong(),
                samplesReceived = inbound.number("totalSamplesReceived").toLong(),
                sendCodec = outbound?.let { byId[it.text("codecId")] }?.text("mimeType").orEmpty(),
                receiveCodec = inbound?.let { byId[it.text("codecId")] }?.text("mimeType").orEmpty(),
                local = candidate("localCandidateId"),
                remote = candidate("remoteCandidateId"),
                pairState = pair?.text("state").orEmpty(),
                roundTripMs = pair?.members?.get("currentRoundTripTime")?.let { (it as? Number)?.toDouble()?.times(MILLIS) },
                dtlsState = transport?.text("dtlsState").orEmpty(),
                srtpCipher = transport?.text("srtpCipher").orEmpty(),
            )
        }

        private fun StatEntry.text(key: String): String = members[key] as? String ?: ""

        private fun StatEntry?.number(key: String): Double = (this?.members?.get(key) as? Number)?.toDouble() ?: 0.0
    }
}
