// Event bodies stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.social.ApprovalDocument.Check
import com.vettid.core.data.social.ApprovalDocument.Failure
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultCodes
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.PendingPage
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * An approval that carries a document (VAULT-MESSAGING 0.89.0 §10.4 "Approval documents"): the pending ask keeps what the
 * vault says of it (event and `pending.list` entry), the app fetches and checks it, and approving sends the hash of exactly
 * the bytes it fetched; on `document_not_fetched` it fetches again and approves again only the same bytes. An approval
 * without the document sends no hash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalDocumentFlowTest {
    private val at = Instant.parse("2026-11-17T12:00:00Z")
    private val exp = "2026-11-17T13:00:00.000Z"

    private val doc = """{"action":"vote.extend","payload":{"closes_at":1796720400,"outage":{"from":1,"source":"relay","to":2},"proposal_id":"p1"},"summary":"Extend"}"""
    private val other = """{"action":"vote.extend","payload":{"closes_at":1796720401,"outage":{"from":1,"source":"relay","to":2},"proposal_id":"p1"},"summary":"Extend"}"""

    private fun b64(s: String) = Base64s.encodeStd(s.toByteArray())
    private fun sha(s: String) = Base64s.encodeStd(Bytes.sha256(s.toByteArray()))
    private fun ref(s: String) = ApprovalDocument.ref(Bytes.sha256(s.toByteArray()))

    /** The vault calls, recorded; each name's errors are thrown in turn, then it answers. */
    private class Ops : AuthDecisionOps {
        val calls = mutableListOf<String>()
        val errors = mutableMapOf<String, ArrayDeque<String>>()

        /** What `connection.authenticate.document` answers, in turn (the last one again once they run out). */
        var documents: List<String?> = emptyList()
        private var fetched = 0

        fun fail(name: String, vararg codes: String) {
            errors.getOrPut(name) { ArrayDeque() }.addAll(codes)
        }

        private fun call(name: String, type: String, line: String) {
            calls += line
            errors[name]?.removeFirstOrNull()?.let { throw VaultOpException(type, it) }
        }

        override suspend fun approve(requestId: String, documentSha256: String?) =
            call("approve", "connection.authenticate.approve", "approve:$requestId" + (documentSha256?.let { ":$it" } ?: ""))

        override suspend fun document(requestId: String): String? {
            call("document", "connection.authenticate.document", "document:$requestId")
            return documents[minOf(fetched++, documents.size - 1)]
        }
    }

    private val ops = Ops()
    private val unlocked = mutableListOf<String>()

    /** The feed items marked read, by kinds and `ref`. */
    private val feedReads = mutableListOf<Pair<Set<String>, String?>>()

    private val credential = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CredentialRepository::class.java)) { _, m, args ->
        if (m.name == "openUnlockWindow") unlocked += args[0] as String else error("unexpected credential call ${m.name}")
        Unit
    } as CredentialRepository

    private fun TestScope.manager() = SocialManager(
        backgroundScope,
        api = { throw VaultFailure(FailureKind.OTHER) },
        credential = credential,
        clock = Clock.fixed(at, ZoneOffset.UTC),
        feedRead = { kinds, ref, _ -> feedReads += kinds to ref },
        authOps = { ops },
    )

    /** Another open ask, of another kind: a grant request of the same connection. */
    private val grant = """{"request_id":"g1","connection_id":"c1","items":[{"kind":"item","ref":"IT1","label":"Passport","available":true}],"uses":1,"expires_in":3600,"exp":"$exp"}"""

    private suspend fun TestScope.grantPending(m: SocialManager) {
        m.onEvent(VaultMessage(Inner(id = Ulid.new(), type = "grant.pending", ts = at, body = grant.toByteArray())))
        runCurrent()
    }

    /** Only the ended ask went: the grant is still listed and its feed item was not marked read. */
    private fun TestScope.onlyTheGrantIsLeft(m: SocialManager) {
        assertEquals(listOf("grant:g1"), open(m).map { it.key })
        assertTrue(feedReads.toString(), feedReads.none { (kinds, ref) -> ref == "g1" || kinds.any { it.startsWith("grant") } })
    }

    private suspend fun TestScope.pending(m: SocialManager, body: String) {
        m.onEvent(VaultMessage(Inner(id = Ulid.new(), type = "connection.authenticate.pending", ts = at, body = body.toByteArray())))
        runCurrent()
    }

    private fun challenge(d: String = doc, required: Boolean = true, size: Int = d.toByteArray().size, id: String = "a1") =
        """{"request_id":"$id","connection_id":"c1","purpose":"approval","context":"Extend a vote — ref ${ref(d)}","document_sha256":"${sha(d)}","document_size":$size""" +
            (if (required) ""","document_required":true""" else "") + ""","exp":"$exp"}"""

    /** The asks after every pending job ran (a decision's drop reaches the list through the manager's flows). */
    private fun TestScope.open(m: SocialManager): List<Approval> {
        runCurrent()
        return m.approvals.value
    }

    private fun SocialManager.auth(id: String = "a1") =
        approvals.value.filterIsInstance<Approval.Authentication>().single { it.requestId == id }

    private suspend fun SocialManager.shown(r: Approval.Authentication) = (authenticationDocument(r) as Check.Shown).document

    private suspend fun failure(block: suspend () -> Unit): VaultFailure {
        try {
            block()
        } catch (e: VaultFailure) {
            return e
        }
        fail("no failure")
        error("unreachable")
    }

    @Test
    fun thePendingAskKeepsWhatTheVaultSaysOfItsDocument() = runTest {
        val m = manager()
        pending(m, challenge())
        val r = m.auth()
        assertTrue(r.hasDocument)
        assertEquals(sha(doc), r.documentSha256)
        assertEquals(doc.toByteArray().size, r.documentSize)
        assertTrue(r.documentRequired)
        pending(m, """{"request_id":"a2","connection_id":"c1","purpose":"approval","exp":"$exp"}""")
        pending(m, """{"request_id":"a3","connection_id":"c1","purpose":"approval","document_sha256":7,"document_size":"x","document_required":"true","exp":"$exp"}""")
        pending(m, """{"request_id":"a4","connection_id":"c1","purpose":"approval","document_required":true,"exp":"$exp"}""")
        pending(m, """{"request_id":"a5","connection_id":"c1","purpose":"approval","document_sha256":"x","document_size":-1,"exp":"$exp"}""")
        assertFalse(m.auth("a2").hasDocument)
        assertFalse(m.auth("a2").documentRequired)
        // A malformed hash still names a document; it can only fail the checks.
        assertTrue(m.auth("a3").hasDocument)
        assertEquals("", m.auth("a3").documentSha256)
        assertNull(m.auth("a3").documentSize)
        assertFalse(m.auth("a3").documentRequired)
        // document_required without a document is ignored.
        assertFalse(m.auth("a4").hasDocument)
        assertFalse(m.auth("a4").documentRequired)
        assertNull(m.auth("a5").documentSize)
    }

    @Test
    fun thePendingListEntryKeepsItToo() = runTest {
        val m = manager()
        m.refreshPendingFrom {
            PendingPage(listOf(VaultJson.parseObject("""{"kind":"authenticate","body":${challenge(id = "l1")}}""".toByteArray())), null, at.plusSeconds(5))
        }
        runCurrent()
        val r = m.auth("l1")
        assertEquals(sha(doc), r.documentSha256)
        assertEquals(doc.toByteArray().size, r.documentSize)
        assertTrue(r.documentRequired)
    }

    @Test
    fun approvingSendsTheHashOfTheFetchedBytes() = runTest {
        val m = manager()
        pending(m, challenge())
        ops.documents = listOf(b64(doc))
        val r = m.auth()
        val d = m.shown(r)
        assertEquals(DocumentApproval.Approved, m.approveAuthentication(r, "pw-fake-1", d))
        assertEquals(listOf("pw-fake-1"), unlocked)
        assertEquals(listOf("document:a1", "approve:a1:${sha(doc)}"), ops.calls)
        assertTrue(open(m).isEmpty())
        // Deciding marks the ask's own feed item read (what the not_found tests below check is not done for the grant).
        assertEquals(listOf(setOf("connection.authenticate.requested") to "a1"), feedReads)
    }

    @Test
    fun aDocumentThatFailsItsChecksIsReturnedNotThrown() = runTest {
        val m = manager()
        pending(m, challenge(size = doc.toByteArray().size + 1))
        ops.documents = listOf(b64(doc))
        assertEquals(Check.Refused(Failure.SIZE), m.authenticationDocument(m.auth()))
        assertEquals(1, open(m).size)
    }

    @Test
    fun aFetchOfAnAskThatEndedDropsIt() = runTest {
        val m = manager()
        pending(m, challenge())
        grantPending(m)
        assertEquals(2, open(m).size)
        ops.fail("document", "not_found")
        val r = m.auth()
        assertEquals(FailureKind.NOT_FOUND, failure { m.authenticationDocument(r) }.kind)
        onlyTheGrantIsLeft(m)
    }

    @Test
    fun anyOtherFetchFailureKeepsTheAsk() = runTest {
        val m = manager()
        pending(m, challenge())
        ops.fail("document", "owner_check_required")
        assertEquals(FailureKind.OWNER_CHECK_REQUIRED, failure { m.authenticationDocument(m.auth()) }.kind)
        assertEquals(1, open(m).size)
    }

    @Test
    fun notFetchedFetchesAgainAndApprovesTheSameBytesOnce() = runTest {
        val m = manager()
        pending(m, challenge())
        ops.documents = listOf(b64(doc))
        val r = m.auth()
        val d = m.shown(r)
        ops.fail("approve", VaultCodes.DOCUMENT_NOT_FETCHED)
        assertEquals(DocumentApproval.Approved, m.approveAuthentication(r, "pw-fake-1", d))
        val approve = "approve:a1:${sha(doc)}"
        assertEquals(listOf("document:a1", approve, "document:a1", approve), ops.calls)
        assertTrue(open(m).isEmpty())
    }

    @Test
    fun notFetchedWithOtherBytesApprovesNothing() = runTest {
        val m = manager()
        pending(m, challenge())
        ops.documents = listOf(b64(doc), b64(other))
        val r = m.auth()
        val d = m.shown(r)
        ops.fail("approve", VaultCodes.DOCUMENT_NOT_FETCHED)
        // The other bytes are not the announced hash: they fail the checks, and nothing more is approved.
        assertEquals(DocumentApproval.Changed(Check.Refused(Failure.HASH)), m.approveAuthentication(r, "pw-fake-1", d))
        assertEquals(1, ops.calls.count { it.startsWith("approve") })
        assertEquals(1, open(m).size)
    }

    @Test
    fun notFetchedWithBytesThatFailTheChecksApprovesNothing() = runTest {
        val m = manager()
        pending(m, challenge())
        ops.documents = listOf(b64(doc), "not base64!")
        val r = m.auth()
        val d = m.shown(r)
        ops.fail("approve", VaultCodes.DOCUMENT_NOT_FETCHED)
        assertEquals(DocumentApproval.Changed(Check.Refused(Failure.ENCODING)), m.approveAuthentication(r, "pw-fake-1", d))
        assertEquals(1, open(m).size)
    }

    @Test
    fun aSecondNotFetchedIsThrownAndKeepsTheAsk() = runTest {
        val m = manager()
        pending(m, challenge())
        ops.documents = listOf(b64(doc))
        val r = m.auth()
        val d = m.shown(r)
        ops.fail("approve", VaultCodes.DOCUMENT_NOT_FETCHED, VaultCodes.DOCUMENT_NOT_FETCHED)
        val f = failure { m.approveAuthentication(r, "pw-fake-1", d) }
        assertEquals(FailureKind.OTHER, f.kind)
        assertEquals(VaultCodes.DOCUMENT_NOT_FETCHED, f.code)
        assertEquals(2, ops.calls.count { it.startsWith("approve") })
        assertEquals(1, open(m).size)
    }

    @Test
    fun anAskThatEndedBeforeTheRefetchIsDropped() = runTest {
        val m = manager()
        pending(m, challenge())
        ops.documents = listOf(b64(doc))
        val r = m.auth()
        val d = m.shown(r)
        grantPending(m)
        ops.fail("approve", VaultCodes.DOCUMENT_NOT_FETCHED)
        ops.fail("document", "not_found")
        assertEquals(FailureKind.NOT_FOUND, failure { m.approveAuthentication(r, "pw-fake-1", d) }.kind)
        assertEquals(1, ops.calls.count { it.startsWith("approve") })
        onlyTheGrantIsLeft(m)
    }

    @Test
    fun aRefusedApprovalLeavesTheAskOpen() = runTest {
        val m = manager()
        pending(m, challenge())
        ops.documents = listOf(b64(doc))
        val r = m.auth()
        val d = m.shown(r)
        for (code in listOf("bad_request", VaultCodes.UPDATE_REQUIRED, "credential_locked", "transfer_incomplete", "owner_check_required")) {
            ops.fail("approve", code)
            assertEquals(code, code, failure { m.approveAuthentication(r, "pw-fake-1", d) }.code)
            assertEquals(code, 1, open(m).size)
        }
        // A not_found to the approval itself drops it, as before.
        ops.fail("approve", "not_found")
        assertEquals(FailureKind.NOT_FOUND, failure { m.approveAuthentication(r, "pw-fake-1", d) }.kind)
        assertTrue(open(m).isEmpty())
    }

    @Test
    fun aMalformedHashStillNamesADocument() = runTest {
        val m = manager()
        pending(m, """{"request_id":"a1","connection_id":"c1","purpose":"approval","context":"x ${ref(doc)}","document_sha256":null,"document_size":${doc.toByteArray().size},"exp":"$exp"}""")
        val r = m.auth()
        assertTrue(r.hasDocument)
        ops.documents = listOf(b64(doc))
        assertEquals(Check.Refused(Failure.HASH), m.authenticationDocument(r))
    }

    @Test
    fun onlyTheAnnouncedDocumentIsApproved() = runTest {
        val m = manager()
        pending(m, challenge(other))
        pending(m, challenge(id = "a2"))
        ops.documents = listOf(b64(doc))
        val d = m.shown(m.auth("a2"))
        ops.calls.clear()
        // The document of another request (another hash) is refused before anything is opened or sent.
        val f = failure { m.approveAuthentication(m.auth("a1"), "pw-fake-1", d) }
        assertEquals(SocialManager.CODE_DOCUMENT_MISMATCH, f.code)
        assertTrue(ops.calls.isEmpty())
        assertTrue(unlocked.isEmpty())
        assertEquals(2, open(m).size)
    }

    @Test
    fun aDocumentIsApprovedOnlyForTheRequestAndContextItWasCheckedFor() = runTest {
        val m = manager()
        pending(m, challenge())
        // The same document under another request, with another line that ends with the same reference.
        pending(m, challenge(id = "a2").replace("Extend a vote — ref", "Remove an admin — ref"))
        ops.documents = listOf(b64(doc))
        val d = m.shown(m.auth("a1"))
        assertEquals("a1" to m.auth("a1").context, d.requestId to d.context)
        ops.calls.clear()
        // Another request's line with this document: refused before anything is opened or sent.
        assertEquals(SocialManager.CODE_DOCUMENT_MISMATCH, failure { m.approveAuthentication(m.auth("a2"), "pw-fake-1", d) }.code)
        // The same request, held with another line than the one checked: refused too.
        val moved = m.auth("a1").copy(context = "Something else — ref ${ref(doc)}")
        assertEquals(SocialManager.CODE_DOCUMENT_MISMATCH, failure { m.approveAuthentication(moved, "pw-fake-1", d) }.code)
        // The same document and the very same line under another request_id: refused too.
        pending(m, challenge(id = "a3"))
        assertEquals(m.auth("a1").context, m.auth("a3").context)
        assertEquals(m.auth("a1").documentSha256, m.auth("a3").documentSha256)
        assertEquals(SocialManager.CODE_DOCUMENT_MISMATCH, failure { m.approveAuthentication(m.auth("a3"), "pw-fake-1", d) }.code)
        assertTrue(ops.calls.isEmpty())
        assertTrue(unlocked.isEmpty())
        assertEquals(3, open(m).size)
        // Its own request approves.
        assertEquals(DocumentApproval.Approved, m.approveAuthentication(m.auth("a1"), "pw-fake-1", d))
        assertEquals(listOf("approve:a1:${sha(doc)}"), ops.calls)
    }

    @Test
    fun anAskWithADocumentIsRefusedWithoutItBeforeTheUnlock() = runTest {
        // Required or not: a challenge held with a document is approved only over it, from the screen that showed it.
        for (required in listOf(true, false)) {
            val m = manager()
            pending(m, challenge(required = required))
            val f = failure { m.approveAuthentication("a1", "pw-fake-1") }
            assertEquals(FailureKind.OTHER, f.kind)
            assertEquals(SocialManager.CODE_DOCUMENT_NOT_SHOWN, f.code)
            assertTrue(ops.calls.isEmpty())
            assertTrue(unlocked.isEmpty())
            assertEquals(1, open(m).size)
        }
    }

    @Test
    fun anAskNoLongerHeldIsNeverApprovedWithoutItsDocument() = runTest {
        // It may carry a document this app can no longer tell of (gone from memory at a lock, or never listed here):
        // refused, nothing opened or sent.
        val m = manager()
        val f = failure { m.approveAuthentication("a1", "pw-fake-1") }
        assertEquals(FailureKind.OTHER, f.kind)
        assertEquals(SocialManager.CODE_DOCUMENT_NOT_SHOWN, f.code)
        assertTrue(ops.calls.isEmpty())
        assertTrue(unlocked.isEmpty())
        // Its document overload still goes by the request it is handed, and only over the document that request names.
        pending(m, challenge())
        ops.documents = listOf(b64(doc))
        val r = m.auth()
        val d = m.shown(r)
        m.forgetPending()
        assertTrue(open(m).isEmpty())
        ops.calls.clear()
        assertEquals(DocumentApproval.Approved, m.approveAuthentication(r, "pw-fake-1", d))
        assertEquals(listOf("approve:a1:${sha(doc)}"), ops.calls)
    }

    @Test
    fun aRequestWithoutADocumentIsNeverAskedForOne() = runTest {
        val m = manager()
        pending(m, """{"request_id":"a1","connection_id":"c1","purpose":"approval","exp":"$exp"}""")
        assertEquals(Check.Refused(Failure.HASH), m.authenticationDocument(m.auth()))
        assertTrue(ops.calls.isEmpty())
        assertEquals(1, open(m).size)
    }

    @Test
    fun anAnswerWithoutADocumentIsNoDocument() = runTest {
        val m = manager()
        pending(m, challenge())
        ops.documents = listOf(null)
        assertEquals(Check.Refused(Failure.ENCODING), m.authenticationDocument(m.auth()))
        assertEquals(1, open(m).size)
    }

    @Test
    fun anApprovalWithoutADocumentSendsNoHash() = runTest {
        val m = manager()
        pending(m, """{"request_id":"a1","connection_id":"c1","purpose":"approval","exp":"$exp"}""")
        m.approveAuthentication("a1", "pw-fake-1")
        assertEquals(listOf("approve:a1"), ops.calls)
        assertEquals(listOf("pw-fake-1"), unlocked)
        assertTrue(open(m).isEmpty())
    }
}
