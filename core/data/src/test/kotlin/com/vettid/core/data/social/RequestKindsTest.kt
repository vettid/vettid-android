package com.vettid.core.data.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun withFp(fp: String?, state: RequestState = RequestState.PENDING) = req(true, state).copy(peerFingerprint = fp)

    private fun fp(l: List<Approval>) = (l.single() as Approval.OutgoingRequest).peerFingerprint

    @Test
    fun theIkFingerprintSurvivesTheAcceptAnswerAndAListReadWithoutIt() {
        assertEquals("aa", fp(RequestKinds.withAccepted(listOf(withFp("aa")), req(true))))
        assertEquals("aa", fp(RequestKinds.keepServices(listOf(withFp("aa")), listOf(withFp(null)))))
        // A list entry that has it gives it to the screen, event or list alike.
        assertEquals("bb", fp(RequestKinds.keepServices(listOf(withFp(null)), listOf(withFp("bb")))))
        assertEquals("bb", fp(RequestKinds.keepServices(emptyList(), listOf(withFp("bb")))))
        assertNull(fp(RequestKinds.keepServices(listOf(req(false)), listOf(req(false, RequestState.PENDING)))))
    }

    @Test
    fun aDifferentFingerprintReplacesTheOldOneAndAnInvalidIkClearsIt() {
        assertEquals("bb", fp(RequestKinds.keepServices(listOf(withFp("aa")), listOf(withFp("bb")))))
        val invalid = withFp(null).copy(peerIkInvalid = true)
        assertNull(fp(RequestKinds.keepServices(listOf(withFp("aa")), listOf(invalid))))
        // Only an absent `ik` keeps the earlier one, even for a request not yet known as a service.
        assertEquals("aa", fp(RequestKinds.keepServices(listOf(withFp("aa")), listOf(req(false, RequestState.PENDING)))))
        assertEquals("bb", RequestKinds.fingerprint("aa", withFp("bb")))
        assertNull(RequestKinds.fingerprint("aa", invalid))
        assertEquals("aa", RequestKinds.fingerprint("aa", withFp(null)))
    }

    @Test
    fun theAcceptAnswerFirstThenTheEventKeepsTheKind() {
        val l = RequestKinds.withAccepted(emptyList(), req(true))
        assertTrue(service(l))
        assertTrue(service(RequestKinds.keepServices(l, listOf(req(false, RequestState.PENDING)))))
        assertFalse(service(RequestKinds.keepServices(listOf(req(false)), listOf(req(false, RequestState.PENDING)))))
    }
}
