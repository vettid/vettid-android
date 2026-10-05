// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.vault.Connection
import com.vettid.core.vault.VaultJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ApprovalParserTest {
    private val at = Instant.parse("2026-10-04T12:00:00Z")

    private fun o(s: String): JsonObject = VaultJson.json.parseToJsonElement(s).jsonObject

    @Test
    fun connectionRequestKeepsTheSafetyCodeAndTheSelfAssertedName() {
        val a = ApprovalParser.parse(
            "connection.request.pending",
            o("""{"pending_id":"P1","invite_id":"I1","sas":"042817","remote":true,"profile":{"name":"Morgan"},"extra":1}"""),
            at,
        ) as Approval.ConnectionRequest
        assertEquals("P1", a.pendingId)
        assertEquals("I1", a.inviteId)
        assertEquals("042817", a.sas)
        assertTrue(a.remote)
        assertEquals("Morgan", a.name)
        assertEquals("connection:P1", a.key)
        // §6.4: an undecided request is dropped after 7 days.
        assertEquals(at.plus(CONNECTION_REQUEST_TTL), a.exp)
        // Without the safety code there is nothing to compare: not shown.
        assertNull(ApprovalParser.parse("connection.request.pending", o("""{"pending_id":"P2"}"""), at))
        // A malformed code is not shown either.
        assertNull(ApprovalParser.parse("connection.request.pending", o("""{"pending_id":"P3","sas":"12345"}"""), at))
    }

    @Test
    fun requestListEntriesAndOutgoingRequests() {
        // connection.request.list (0.10.2/0.10.3): state, peer_approved, created_at and exp from the vault.
        val i = ApprovalParser.incoming(
            o("""{"pending_id":"P1","invite_id":"I1","sas":"042817","remote":false,"state":"approved","peer_approved":false,""" +
                """"created_at":"2026-10-04T11:00:00.000Z","exp":"2026-10-20T11:00:00.000Z"}"""),
            at,
        )!!
        assertEquals(RequestState.APPROVED, i.state)
        assertEquals(Instant.parse("2026-10-04T11:00:00Z"), i.receivedAt)
        assertEquals(Instant.parse("2026-10-20T11:00:00Z"), i.exp)
        assertFalse(i.needsDecision)

        val waiting = ApprovalParser.outgoing(o("""{"connection_id":"C1","remote":true,"state":"waiting","peer_approved":false,"name":"Jo"}"""), at)!!
        assertEquals(RequestState.WAITING, waiting.state)
        assertNull(waiting.sas)
        assertEquals("Jo", waiting.name)
        assertEquals("outgoing:C1", waiting.key)
        assertFalse(waiting.needsDecision)
        // connection.request.outgoing has no state: the code is known, so it is pending.
        val ev = ApprovalParser.parse("connection.request.outgoing", o("""{"connection_id":"C1","sas":"315904","remote":true,"exp":"2026-10-12T12:00:00.000Z"}"""), at)
            as Approval.OutgoingRequest
        assertEquals(RequestState.PENDING, ev.state)
        assertEquals("315904", ev.sas)
        assertTrue(ev.needsDecision)
        // A code without a known state is never "waiting".
        assertEquals(RequestState.PENDING, ApprovalParser.outgoing(o("""{"connection_id":"C2","sas":"000001","state":"waiting"}"""), at)!!.state)
    }

    @Test
    fun criticalPayloadIsVerifiedAgainstItsHash() {
        val p = "SGVsbG8sIFZldHRJRCE="
        val ok = ApprovalParser.critical(o("""{"request_id":"R","connection_id":"C","payload":"$p","payload_sha256":"${ApprovalParser.payloadSha256(p)}"}"""), at)!!
        assertTrue(ok.payloadVerified)
        val bad = ok.copy(payload = "QmFk")
        assertFalse(bad.payloadVerified)
        assertFalse(ok.copy(payload = "").payloadVerified)
    }

    @Test
    fun grantCriticalShareAuthAndDeviceRequests() {
        val g = ApprovalParser.parse(
            "grant.pending",
            o(
                """{"request_id":"G1","connection_id":"C1","items":[{"kind":"item","ref":"IT1","label":"Passport","available":true},""" +
                    """{"kind":"category","ref":"insurance","available":false}],"uses":2,"expires_in":3600,"reason":"trip","exp":"2026-10-05T12:00:00.000Z"}""",
            ),
            at,
        ) as Approval.GrantRequest
        assertEquals(listOf(0), g.grantable)
        assertEquals(2, g.uses)
        assertEquals(Instant.parse("2026-10-05T12:00:00Z"), g.exp)

        val payload = Base64s.encodeStd("hello".toByteArray())
        val sha = Base64s.encodeStd(Bytes.sha256("hello".toByteArray()))
        val c = ApprovalParser.parse(
            "critical-secret-use.pending",
            o(
                """{"request_id":"U1","connection_id":"C1","item_id":"IT","field_id":"F","name":"Key","label":"Seed","operation":"sign",""" +
                    """"payload":"$payload","payload_sha256":"$sha","exp":"2026-10-05T12:00:00.000Z"}""",
            ),
            at,
        ) as Approval.CriticalUse
        assertEquals(sha, ApprovalParser.payloadSha256(c.payload))
        assertEquals("critical:U1", c.key)

        val s = ApprovalParser.parse(
            "share.pending",
            o("""{"rule_id":"R1","subject":{"connection_id":"C1"},"items":[{"item_id":"I1","name":"Allergies","category":"health","sensitivity":"data"}],"reason":"rule"}"""),
            at,
        ) as Approval.ShareDecision
        assertEquals("C1", s.subjectConnectionId)
        assertEquals("Allergies", s.items.single().name)

        val auth = ApprovalParser.parse("connection.authenticate.pending", o("""{"connection_id":"C1","request_id":"A1","context":"hi"}"""), at)
        assertEquals("auth:A1", auth?.key)

        val d = ApprovalParser.parse("device.session.pending", o("""{"request_id":"S1","device_id":"D","role":"desktop","name":"Laptop","seconds":60}"""), at)
        assertEquals("device:device.session.pending:S1", d?.key)
        assertNull(ApprovalParser.parse("message.new", o("{}"), at))
    }

    @Test
    fun connectionsShowOnlyTextProfileMembersAndAKeyFingerprint() {
        val c = ApprovalParser.connection(
            Connection(
                id = "C1", state = "stale", name = "Sam", ik = Base64s.encodeStd(ByteArray(32) { 0x11 }),
                profile = o("""{"name":"Sam","photo":"data","city":"Lisbon","nested":{"a":1}}"""),
                alias = "", favorite = true, createdAt = "2026-10-01T09:00:00.000Z",
            ),
        )
        assertEquals(ConnectionState.STALE, c.state)
        assertNull(c.alias)
        assertEquals("Sam", c.displayName)
        assertEquals(listOf("name" to "Sam", "city" to "Lisbon"), c.profile)
        assertEquals("1111 1111 1111 1111", c.keyFingerprint)
        assertTrue(c.favorite)
    }

    @Test
    fun authenticationResults() {
        val ok = ApprovalParser.authResult(o("""{"connection_id":"C1","request_id":"R","authenticated":true,"key_changed":true}"""), at, null)!!
        assertEquals(at, ok.verifiedAt)
        assertEquals("authenticated", ok.lastResult)
        assertTrue(ok.keyChanged)
        val denied = ApprovalParser.authResult(o("""{"connection_id":"C1","request_id":"R","authenticated":false,"reason":"denied"}"""), at, ok)!!
        assertEquals(at, denied.verifiedAt)
        assertEquals("denied", denied.lastResult)
        assertFalse(denied.keyChanged)
    }
}
