package com.vettid.core.data.social

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** `peer_kind` survives either order of the accept's answer and the outgoing event, and a list read (ANDROID-PLAN 0.1.90). */
class RequestKindsTest {
    private fun req(service: Boolean, state: RequestState = RequestState.WAITING) = Approval.OutgoingRequest(
        "c1", null, remote = false, name = "Acme", state = state, peerApproved = false, introducedBy = null,
        receivedAt = Instant.EPOCH, exp = null, service = service,
    )

    private fun service(l: List<Approval>) = (l.single() as Approval.OutgoingRequest).service

    @Test
    fun theEventFirstThenTheAcceptAnswerKeepsTheKind() {
        assertTrue(service(RequestKinds.withAccepted(listOf(req(false)), req(true))))
        // An answer without the marker never takes it back.
        assertTrue(service(RequestKinds.withAccepted(listOf(req(true)), req(false))))
    }

    @Test
    fun theAcceptAnswerFirstThenTheEventKeepsTheKind() {
        val l = RequestKinds.withAccepted(emptyList(), req(true))
        assertTrue(service(l))
        assertTrue(service(RequestKinds.keepServices(l, listOf(req(false, RequestState.PENDING)))))
        assertFalse(service(RequestKinds.keepServices(listOf(req(false)), listOf(req(false, RequestState.PENDING)))))
    }
}
