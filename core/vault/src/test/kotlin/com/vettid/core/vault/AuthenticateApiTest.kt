package com.vettid.core.vault

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The wire bodies of member authentication's approval (VAULT-MESSAGING 0.89.0 §10.4 "Approval documents"):
 * `connection.authenticate.approve` carries `document_sha256` only over a fetched document, and
 * `connection.authenticate.document` sends `{request_id}` and reads only a `document` string.
 */
class AuthenticateApiTest {
    private fun obj(s: String) = VaultJson.parseObject(s.toByteArray())

    @Test
    fun anApprovalWithoutADocumentIsTheRequestIdAlone() {
        val b = authenticateApproveBody("r1", null)
        assertEquals(setOf("request_id"), b.keys)
        assertEquals("r1", (b["request_id"] as JsonPrimitive).content)
        assertEquals("""{"request_id":"r1"}""", b.toString())
    }

    @Test
    fun anApprovalOverADocumentAddsItsHash() {
        val sha = "6K+IFYkeQ0s0/j/tr7Jw+N87yuOiIBe5LpIGPhE0C/U="
        val b = authenticateApproveBody("r1", sha)
        assertEquals(setOf("request_id", "document_sha256"), b.keys)
        assertEquals(sha, (b["document_sha256"] as JsonPrimitive).content)
        assertEquals("""{"request_id":"r1","document_sha256":"$sha"}""", b.toString())
    }

    @Test
    fun theFetchSendsTheRequestIdAndReadsOnlyADocumentString() {
        val b = authenticateDocumentBody("r1")
        assertEquals("""{"request_id":"r1"}""", b.toString())
        assertEquals("e30=", authenticateDocumentOf(obj("""{"document":"e30="}""")))
        assertNull(authenticateDocumentOf(obj("""{}""")))
        assertNull(authenticateDocumentOf(obj("""{"doc":"e30="}""")))
        for (other in listOf("1", "null", "true", "[\"e30=\"]", "{\"v\":\"e30=\"}")) {
            assertNull(other, authenticateDocumentOf(obj("""{"document":$other}""")))
        }
    }
}
