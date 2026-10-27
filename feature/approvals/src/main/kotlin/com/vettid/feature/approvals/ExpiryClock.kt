package com.vettid.feature.approvals

import com.vettid.core.data.social.Approval
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import java.time.Instant

/**
 * What time it is for the requests' expiry: the repository's list only drops an expired request when the next vault
 * event or read arrives, so a screen that stays open would go on offering Approve and Deny for a request whose
 * "Expires …" has passed. This emits now, and once more at the next expiry in [nextExpiry] (a single sleep until that
 * instant, never a polling loop: it ends when nothing is left to expire).
 */
internal fun expiryClock(nextExpiry: Flow<Instant?>): Flow<Instant> = nextExpiry.distinctUntilChanged().flatMapLatest { at ->
    flow {
        emit(Instant.now())
        if (at != null) {
            val wait = at.toEpochMilli() - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            // The sleep ended at the expiry: report at least that instant.
            emit(maxOf(Instant.now(), at))
        }
    }
}

/** Whether the request has expired at [now]: its `exp` is not in the future (`now >= exp`). */
internal fun Approval.expiredAt(now: Instant): Boolean = exp?.let { !now.isBefore(it) } == true
