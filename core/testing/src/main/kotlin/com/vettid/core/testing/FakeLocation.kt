package com.vettid.core.testing

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

    override suspend fun refresh() = Unit

    /** The first position of [shareId] went out. */
    fun sent(shareId: String, at: Instant = now()) {
        outgoing.value = outgoing.value.map { if (it.shareId == shareId) it.copy(lastSentAt = at) else it }
    }

    private companion object {
        const val ONCE_SECONDS = 900L
    }
}
