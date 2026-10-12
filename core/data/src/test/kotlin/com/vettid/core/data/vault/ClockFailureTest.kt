package com.vettid.core.data.vault

import com.vettid.core.altchan.MemberApiException
import com.vettid.core.data.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * A6 audit N4/N5: a `401` that the phone's clock explains is its own failure with its own text, never the "this
 * phone was replaced" one; and the two dead ends that are not about the connection (N6, P4) have texts of their own.
 */
class ClockFailureTest {
    @Test
    fun a401TheClockExplainsIsNotUnauthorized() {
        assertEquals(FailureKind.CLOCK, VaultManager.memberFailure(MemberApiException(401, MemberApiException.CLOCK_SKEW)))
        assertEquals(FailureKind.UNAUTHORIZED, VaultManager.memberFailure(MemberApiException(401, MemberApiException.UNAUTHORIZED)))
    }

    @Test
    fun theNewKindsHaveTheirOwnTexts() {
        assertEquals(R.string.data_failure_clock, FailureKind.CLOCK.messageRes())
        assertEquals(R.string.data_failure_no_vault, FailureKind.NO_VAULT.messageRes())
        assertEquals(R.string.data_failure_local_state, FailureKind.LOCAL_STATE.messageRes())
        assertNotEquals(FailureKind.UNAUTHORIZED.messageRes(), FailureKind.CLOCK.messageRes())
    }
}
