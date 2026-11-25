package com.vettid.feature.approvals

import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.deadlineClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.Instant

/**
 * What time it is for the requests' expiry: the repository's list only drops an expired request when the next vault
 * event or read arrives, so a screen that stays open would go on offering Approve and Deny for a request whose
 * "Expires …" has passed. This emits now, and again at each of [expiries] still to come ([deadlineClock], the one clock
 * the approvals indicator uses too). [resumed] (the screen came back to the front) makes it read the time again.
 */
internal fun expiryClock(expiries: Flow<List<Instant>>, resumed: Flow<Unit> = emptyFlow()): Flow<Instant> =
    deadlineClock(expiries, resumed)

/** Whether the request has expired at [now]: its `exp` is not in the future (`now >= exp`). */
internal fun Approval.expiredAt(now: Instant): Boolean = exp?.let { !now.isBefore(it) } == true
