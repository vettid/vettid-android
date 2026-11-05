package com.vettid.core.testing

import com.vettid.core.data.location.AskResult
import com.vettid.core.data.location.IncomingShare
import com.vettid.core.data.location.LocationAvailability
import com.vettid.core.data.location.LocationRepository
import com.vettid.core.data.location.OutgoingShare
import com.vettid.core.data.location.PositionOffset
import com.vettid.core.data.location.ServiceState
import com.vettid.core.data.location.ShareRequest
import com.vettid.core.data.vault.VaultFailure
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/**
 * TEST ONLY. An in-memory Location repository (ANDROID-PLAN §4 "Location") for ViewModel tests: [start] records the request and
 * lists a share (ending [lifetimeSeconds] after [now]); a failure in [fail] is thrown instead. A test sets `lastSentAt` through
 * [sent] to say that the first position went out. No position is involved anywhere.
 */
class FakeLocation(private val now: () -> Instant = Instant::now, private val lifetimeSeconds: Long = 3_600) : LocationRepository {
    val started = mutableListOf<ShareRequest>()
    val stopped = mutableListOf<String>()
    var fail: VaultFailure? = null
    private var n = 0

    override val outgoing = MutableStateFlow<List<OutgoingShare>>(emptyList())
    override val serviceState = MutableStateFlow(ServiceState())

    /** Whether the vault has location; a test sets UNKNOWN to see the sheet read it first ([refresh] then makes it AVAILABLE). */
    override val availability = MutableStateFlow(LocationAvailability.AVAILABLE)

    /** Connections whose once share ended without a position; a test sets it. */
    override val missedOnce = MutableStateFlow<Set<String>>(emptySet())

    /** The shares connections have with the member (L1b). A test sets it; positions are in the shares. */
    override val incoming = MutableStateFlow<List<IncomingShare>>(emptyList())

    /** What the receiving side was asked: the connections [open]ed, the shares [stopReceiving]ed, the asks made. */
    val opened = mutableListOf<String>()
    val stoppedReceiving = mutableListOf<String>()
    val asks = mutableListOf<Pair<String, String?>>()

    /** The answer to [ask]; a test sets [AskResult.Limited]. */
    var askResult: AskResult = AskResult.Sent

    /** The offset [offsetFromHere] answers; null: the member has not allowed location. */
    var offset: PositionOffset? = null

    override suspend fun open(connectionId: String) {
        opened += connectionId
        fail?.let { fail = null; throw it }
    }

    /** When set, [stopReceiving] and [ask] wait for them (a call in flight). */
    var stopGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var askGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun stopReceiving(shareId: String) {
        stopGate?.await()
        fail?.let { fail = null; throw it }
        stoppedReceiving += shareId
        incoming.value = incoming.value.filter { it.shareId != shareId }
    }

    override suspend fun ask(connectionId: String, note: String?): AskResult {
        askGate?.await()
        fail?.let { fail = null; throw it }
        asks += connectionId to note
        return askResult
    }

    /** How many times the phone's own place was asked for. */
    var offsetCalls = 0

    override suspend fun offsetFromHere(connectionId: String): PositionOffset? {
        offsetCalls++
        return offset
    }

    /**
     * A stop the vault has not taken yet: the share stays in [outgoing] while its id (and connection) are marked in [serviceState],
     * as `LocationSharing` does between the member's Stop and the vault's answer.
     */
    fun holdStopping(shareId: String) {
        val conn = outgoing.value.first { it.shareId == shareId }.connectionId
        val s = serviceState.value
        serviceState.value = s.copy(stopping = s.stopping + conn, stoppingShares = s.stoppingShares + shareId)
    }

    /** How many times `location.share.list` was read. */
    var refreshes = 0

    /** When set, [start] waits for it (a start in flight). */
    var startGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun start(request: ShareRequest): OutgoingShare {
        startGate?.await()
        fail?.let { fail = null; throw it }
        started += request
        val share = OutgoingShare(
            shareId = "s${++n}",
            connectionId = request.connectionId,
            once = request.once,
            precision = request.precision,
            intervalSeconds = request.intervalSeconds,
            trail = request.trail,
            expiresAt = now().plusSeconds(if (request.once) ONCE_SECONDS else request.durationSeconds ?: lifetimeSeconds),
            lastSentAt = null,
            fromThisPhone = true,
        )
        outgoing.value = outgoing.value.filter { it.connectionId != request.connectionId } + share
        return share
    }

    override suspend fun stop(shareId: String) {
        fail?.let { fail = null; throw it }
        stopped += shareId
        outgoing.value = outgoing.value.filter { it.shareId != shareId }
    }

    override fun stopAll() {
        outgoing.value = emptyList()
    }

    override suspend fun refresh() {
        refreshes++
        fail?.let { fail = null; throw it }
        if (availability.value == LocationAvailability.UNKNOWN) availability.value = LocationAvailability.AVAILABLE
    }

    /** The first position of [shareId] went out. */
    fun sent(shareId: String, at: Instant = now()) {
        outgoing.value = outgoing.value.map { if (it.shareId == shareId) it.copy(lastSentAt = at) else it }
    }

    private companion object {
        const val ONCE_SECONDS = 900L
    }
}
