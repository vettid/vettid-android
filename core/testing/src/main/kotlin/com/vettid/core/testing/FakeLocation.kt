package com.vettid.core.testing

import com.vettid.core.data.location.LocationAvailability
import com.vettid.core.data.location.LocationRepository
import com.vettid.core.data.location.OutgoingShare
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

    /** How many times `location.share.list` was read. */
    var refreshes = 0

    override suspend fun start(request: ShareRequest): OutgoingShare {
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
