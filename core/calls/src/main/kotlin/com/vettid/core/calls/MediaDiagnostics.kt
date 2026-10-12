package com.vettid.core.calls

/** One entry of the stack's statistics report (`RTCStats`), detached from the native library for tests. */
data class StatEntry(val id: String, val type: String, val members: Map<String, Any?>)

/** One end of the candidate pair in use. Types only: no address is kept, shown or logged. */
data class CandidateInfo(val type: String, val protocol: String, val network: String) {
    /** Through a TURN server. */
    val relayed: Boolean get() = type == "relay"

    override fun toString(): String = listOf(type, protocol, network).filter { it.isNotEmpty() }.joinToString(" / ")
}

/**
 * What a call can say about its media, for the in-call diagnostics and the phone tests. Nothing here is secret and
 * nothing names an address. The levels are the stack's `audioLevel` (0 = silence, 1 = full scale).
 */
data class MediaDiagnostics(
    val micLevel: Double = 0.0,
    val remoteLevel: Double = 0.0,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val sendCodec: String = "",
    val receiveCodec: String = "",
    val local: CandidateInfo? = null,
    val remote: CandidateInfo? = null,
    val roundTripMs: Double? = null,
    /**
     * The transport's own security, as the stack reports it: `dtlsState`, `tlsVersion` (hexadecimal: `FEFD` is
     * DTLS 1.2, `FEFC` DTLS 1.3), `dtlsCipher`, `srtpCipher`, `dtlsRole`, and any other member whose name starts
     * with `dtls`, `tls`, `srtp` or `ssl`. This library version reports no key-exchange group.
     */
    val transport: Map<String, String> = emptyMap(),
    /** Whether the post-quantum DTLS field trial is on in this process ([MediaConfig.dtlsPqcTrial]). */
    val dtlsPqcTrial: Boolean = false,
    val sendCryptor: CryptorState = CryptorState.NEW,
    val receiveCryptor: CryptorState = CryptorState.NEW,
    /**
     * The answering device had to move its frame encryption to another media section after the offer was applied
     * (it was still in place before the answer and before any media). Expected: false.
     */
    val cryptorsMoved: Boolean = false,
) {
    /** Direct between the two phones, or through a TURN server; null before a path exists. */
    val relayed: Boolean? get() = if (local == null || remote == null) null else local.relayed || remote.relayed

    companion object {
        private const val MILLIS = 1000.0
        private val TRANSPORT_PREFIXES = listOf("dtls", "tls", "srtp", "ssl")

        fun from(entries: Collection<StatEntry>): MediaDiagnostics {
            val byId = entries.associateBy { it.id }

            fun audio(type: String) = entries.firstOrNull { it.type == type && it.text("kind") == "audio" }
            val source = audio("media-source")
            val inbound = audio("inbound-rtp")
            val outbound = audio("outbound-rtp")
            val transport = entries.firstOrNull { it.type == "transport" && it.text("selectedCandidatePairId").isNotEmpty() }
                ?: entries.firstOrNull { it.type == "transport" }
            val pair = transport?.let { byId[it.text("selectedCandidatePairId")] }

            fun candidate(key: String) = pair?.let { byId[it.text(key)] }?.let {
                CandidateInfo(it.text("candidateType"), it.text("protocol"), it.text("networkType"))
            }
            return MediaDiagnostics(
                micLevel = source.number("audioLevel"),
                remoteLevel = inbound.number("audioLevel"),
                packetsSent = outbound.number("packetsSent").toLong(),
                packetsReceived = inbound.number("packetsReceived").toLong(),
                sendCodec = outbound?.let { byId[it.text("codecId")] }?.text("mimeType").orEmpty(),
                receiveCodec = inbound?.let { byId[it.text("codecId")] }?.text("mimeType").orEmpty(),
                local = candidate("localCandidateId"),
                remote = candidate("remoteCandidateId"),
                roundTripMs = (pair?.members?.get("currentRoundTripTime") as? Number)?.toDouble()?.times(MILLIS),
                transport = transport?.members.orEmpty()
                    .filter { (k, v) -> v is String && TRANSPORT_PREFIXES.any { k.startsWith(it) } }
                    .mapValues { it.value as String }.toSortedMap(),
            )
        }

        private fun StatEntry.text(key: String): String = members[key] as? String ?: ""

        private fun StatEntry?.number(key: String): Double = (this?.members?.get(key) as? Number)?.toDouble() ?: 0.0
    }
}
