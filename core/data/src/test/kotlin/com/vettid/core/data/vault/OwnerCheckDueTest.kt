package com.vettid.core.data.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** (ANDROID-PLAN 0.1.96) What gates the release update: a past deadline, or a lock the check caused (never one of unknown cause). */
class OwnerCheckDueTest {
    private val now = Instant.parse("2026-10-29T12:00:00Z")
    private fun view(deadline: Instant) =
        OwnerCheckView(OwnerCheckState.OK, deadline, 86_400, 0, hold = true, holdOffUntil = null)

    @Test
    fun aPastDeadlineIsDue() = assertTrue(ownerCheckDue(view(now.minusSeconds(1)), false, false, now))

    @Test
    fun aLockTheCheckCausedIsDue() = assertTrue(ownerCheckDue(null, true, false, now))

    @Test
    fun aLockOfUnknownCauseAsksForNothing() = assertFalse(ownerCheckDue(null, true, true, now))

    @Test
    fun nothingIsDueWhenOpenBeforeTheDeadline() = assertFalse(ownerCheckDue(view(now.plusSeconds(60)), false, false, now))
}
