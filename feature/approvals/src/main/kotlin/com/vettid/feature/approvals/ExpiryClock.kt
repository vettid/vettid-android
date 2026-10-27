package com.vettid.feature.approvals

import com.vettid.core.data.social.Approval
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import java.time.Instant

/**
 * What time it is for the requests' expiry: the repository's list only drops an expired request when the next vault
 * event or read arrives, so a screen that stays open would go on offering Approve and Deny for a request whose
 * "Expires …" has passed. This emits now, and again at each of [expiries] still to come (a sleep until each, never a
 * polling loop: it ends when nothing is left to expire). A sleep does not run while the phone is in deep sleep
 * (the dispatcher's clock stops), so [resumed] (the screen came back to the front) makes it read the time again.
 */
internal fun expiryClock(expiries: Flow<List<Instant>>, resumed: Flow<Unit> = emptyFlow()): Flow<Instant> =
    expiries.distinctUntilChanged().flatMapLatest { list ->
        merge(
            flow {
                emit(Instant.now())
                for (at in list.sorted()) {
                    val wait = at.toEpochMilli() - System.currentTimeMillis()
                    if (wait > 0) delay(wait)
                    // The sleep ended at the expiry: report at least that instant.
                    emit(maxOf(Instant.now(), at))
                }
            },
            resumed.map { Instant.now() },
        )
    }

/** Whether the request has expired at [now]: its `exp` is not in the future (`now >= exp`). */
internal fun Approval.expiredAt(now: Instant): Boolean = exp?.let { !now.isBefore(it) } == true
