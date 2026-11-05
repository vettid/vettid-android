package com.vettid.core.data.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** What `VaultManager`'s owner-check collector tells the location sharing: a pause lifted, not any change of the check. */
class PauseLiftedTest {
    private val now = Instant.parse("2026-11-05T12:00:00Z")

    private fun view(state: OwnerCheckState = OwnerCheckState.OK, deadline: Instant? = now.plusSeconds(3_600), failures: Int = 0) =
        OwnerCheckView(state, deadline, 86_400, failures, hold = false, holdOffUntil = null)

    @Test
    fun aCheckThatPassesAfterBeingDueLifts() =
        assertTrue(pauseLifted(view(OwnerCheckState.OK, now.minusSeconds(60)), view(deadline = now.plusSeconds(86_400)), now))

    @Test
    fun aDeadlineThatPassedWithNoNewValueWasAPauseAndItsLiftCounts() {
        val sameValueSinceBeforeTheDeadline = view(deadline = now.minusSeconds(3_000))
        assertTrue(pauseLifted(sameValueSinceBeforeTheDeadline, view(deadline = now.plusSeconds(86_400)), now))
    }

    @Test
    fun anEarlyPassOrAChangedFailureCountWhileOpenLiftsNothing() {
        assertFalse(pauseLifted(view(), view(deadline = now.plusSeconds(86_400)), now))
        assertFalse(pauseLifted(view(failures = 0), view(failures = 2), now))
    }

    @Test
    fun aCheckStillDueOrNoValueLiftsNothing() {
        assertFalse(pauseLifted(view(deadline = now.minusSeconds(1)), view(deadline = now.minusSeconds(1)), now))
        assertFalse(pauseLifted(null, view(), now))
        assertFalse(pauseLifted(view(deadline = now.minusSeconds(1)), view(deadline = now.minusSeconds(1)).copy(failures = 1), now))
    }
}
