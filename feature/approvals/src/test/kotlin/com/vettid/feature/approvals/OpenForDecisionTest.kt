package com.vettid.feature.approvals

import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.AuthPurpose
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** A request open for a decision counts as in progress, typed or not (VAULT-MESSAGING §3.6.5). */
class OpenForDecisionTest {
    private val at = Instant.parse("2026-10-30T12:00:00Z")
    private val a = Approval.Authentication("a1", "c1", "Bank", at, null, "Sam", AuthPurpose.SIGNIN, null)

    @Test
    fun `an open request is in progress with nothing typed`() {
        assertTrue(ApprovalDetailUiState(key = a.key, approval = a).openForDecision)
    }

    @Test
    fun `a decided, gone, withdrawn or missing request is not`() {
        assertFalse(ApprovalDetailUiState(key = a.key).openForDecision)
        assertFalse(ApprovalDetailUiState(key = a.key, approval = a, done = true).openForDecision)
        assertFalse(ApprovalDetailUiState(key = a.key, approval = a, gone = true).openForDecision)
        assertFalse(ApprovalDetailUiState(key = a.key, approval = a, withdrawn = true).openForDecision)
    }

    /** Its outcome or result on screen is a decision made: the daily check does not wait for it (R33). */
    @Test
    fun `a request whose outcome or result is shown is not`() {
        val d = Approval.DeviceRequest(
            "device.session.pending", "s1", "Laptop", "desktop", null, at, at.plusSeconds(600), deviceId = "D",
        )
        assertTrue(ApprovalDetailUiState(key = d.key, approval = d).openForDecision)
        val allowed = DeviceOutcome.Allowed(at.plusSeconds(3_600))
        assertFalse(ApprovalDetailUiState(key = d.key, approval = d, outcome = allowed).openForDecision)
        assertFalse(ApprovalDetailUiState(key = a.key, approval = a, criticalResult = "ok").openForDecision)
    }
}
