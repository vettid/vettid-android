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
    fun connectionRequestKeepsTheSafetyCodeAndTheRequestersNames() {
        // 0.18.0 §6.2: the hs.init profile is {first_name, last_name, name?}.
        val a = ApprovalParser.parse(
            "connection.request.pending",
            o("""{"pending_id":"P1","invite_id":"I1","sas":"042817","remote":true,"profile":{"first_name":"Morgan","last_name":"Lee","name":"Mo"},"extra":1}"""),
            at,
        ) as Approval.ConnectionRequest
        assertEquals("P1", a.pendingId)
        assertEquals("I1", a.inviteId)
        assertEquals("042817", a.sas)
        assertTrue(a.remote)
        assertEquals("Morgan Lee", a.name)
        assertEquals("Mo", a.displayName)
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
    fun grantEntriesCarryTheMembersItemAndCriticalUsesTheirKind() {
        // 0.21.0 §10.12: an available item entry names the member's item and the fields it would grant.
        val g = ApprovalParser.parse(
            "grant.pending",
            o("""{"request_id":"R1","connection_id":"C1","items":[{"kind":"item","ref":"01A","label":"Your passport","available":true,"name":"Passport","category":"identity_document","labels":[{"field_id":"f1","label":"Number","kind":"text"}]},{"kind":"item","ref":"01B","available":false},{"kind":"category","ref":"insurance","available":false}],"uses":1,"expires_in":604800,"exp":"2026-10-11T12:00:00.000Z"}"""),
            at,
        ) as Approval.GrantRequest
        val e = g.entries[0]
        assertEquals("Passport", e.name)
        assertEquals("identity_document", e.category)
        assertEquals(listOf(com.vettid.core.data.items.FieldLabel("f1", "Number", "text")), e.labels)
        assertNull(g.entries[1].name)
        assertNull(g.entries[1].labels)
        // An older vault: no name, no labels.
        assertNull(g.entries[2].labels)

        // 0.21.0 §10.13: the field's kind, and whether it can hold a seed at all.
        val body = """{"request_id":"U1","connection_id":"C1","item_id":"01K","field_id":"f1","name":"Key","label":"Seed","operation":"sign","payload_sha256":"x","kind":"%s"}"""
        fun use(kind: String) = ApprovalParser.parse("critical-secret-use.pending", o(body.format(kind)), at) as Approval.CriticalUse
        assertEquals("password", use("password").kind)
        assertEquals(true, use("password").suitable)
        assertEquals(true, use("text").suitable)
        assertEquals(true, use("multiline").suitable)
        assertEquals(false, use("url").suitable)
        assertEquals(false, use("otp").suitable)
        val older = ApprovalParser.parse("critical-secret-use.pending", o(body.replace(",\"kind\":\"%s\"", "")), at) as Approval.CriticalUse
        assertNull(older.kind)
        assertNull(older.suitable)
    }

    @Test
    fun aRequestWithoutNamesHasNoTitleAndADisplayNameAloneIsNeverOne() {
        val a = ApprovalParser.incoming(o("""{"pending_id":"P1","sas":"042817","profile":{"name":"Morgan"}}"""), at)!!
        assertNull(a.name)
        assertEquals("Morgan", a.displayName)
        // A display name equal to the title is not repeated.
        val b = ApprovalParser.incoming(
            o("""{"pending_id":"P2","sas":"042817","profile":{"first_name":"Ada","last_name":"King","name":"Ada King"}}"""), at,
        )!!
        assertEquals("Ada King", b.name)
        assertNull(b.displayName)
    }

    @Test
    fun connectionsAreTitledFromTheProfileCore() {
        val ik = Base64s.encodeStd(ByteArray(32) { 0x11 })
        val c = ApprovalParser.connection(
            Connection(
                id = "C1", state = "stale", name = "Sam", ik = ik,
                profile = o(
                    """{"version":3,"first_name":"Samira","last_name":"Rivera","ik":"$ik","name":"Sam","photo":"data",""" +
                        """"items":[{"item_id":"I1","name":"City","category":"other","fields":[{"field_id":"F1","label":"City","kind":"text","value":"Lisbon"},""" +
                        """{"field_id":"F2","label":"Home","kind":"address","value":{"street":"Rua A","city":"Lisboa"}}]}]}""",
                ),
                alias = "", favorite = true, createdAt = "2026-10-01T09:00:00.000Z",
            ),
        )
        assertEquals(ConnectionState.STALE, c.state)
        assertNull(c.alias)
        assertEquals("Samira Rivera", c.accountName)
        assertEquals("Samira Rivera", c.displayName)
        assertEquals("Sam", c.name)
        assertEquals("Sam", c.secondaryName)
        assertTrue(c.hasPhoto)
        assertEquals(listOf(SharedProfileItem("I1", "City", listOf("City" to "Lisbon", "Home" to "Rua A, Lisboa"))), c.sharedItems)
        // §10.8: SHA-256("vettid/vms/2/ik-fp" || ik), first 16 bytes, 8 groups of 4.
        assertEquals(com.vettid.core.crypto.IkFingerprint.format(ByteArray(32) { 0x11 }), c.keyFingerprint)
        assertEquals(39, c.keyFingerprint!!.length)
        assertTrue(c.favorite)
    }

    @Test
    fun anAliasInTheVaultIsNeitherReadNorTheTitle() {
        // Owner decision 2026-10-08: aliases and notes a vault still holds stay there, unshown.
        val c = ApprovalParser.connection(
            Connection(
                id = "C1", state = "active", profile = o("""{"first_name":"Ada","last_name":"King"}"""),
                alias = "Mum", note = "Sunday calls",
            ),
        )
        assertEquals("Ada King", c.displayName)
        assertEquals("Ada King", c.accountName)
        assertNull(c.alias)
        assertNull(c.secondaryName)
    }

    @Test
    fun beforeTheFirstProfileThereIsNoTitle() {
        // The accepter's side between activation and the first profile.update: only the invitation's name.
        val c = ApprovalParser.connection(Connection(id = "C1", state = "active", name = "Jo"))
        assertNull(c.accountName)
        assertEquals("", c.displayName) // the UI shows "Name not shared yet"
        assertEquals("Jo", c.secondaryName)
        assertNull(c.keyFingerprint)
    }

    @Test
    fun aProfileWhoseCoreBreaksTheRulesGivesNoNames() {
        val bad = listOf(
            """{"first_name":"","last_name":"King"}""",
            """{"first_name":"Ada"}""",
            """{"first_name":"Ada","last_name":1}""",
            """{"first_name":"Ada\u0000","last_name":"King"}""",
            """{"first_name":"Ada","last_name":"Ki\u0085ng"}""",
            """{"first_name":"Ada\u2028","last_name":"King"}""",
            """{"first_name":"${"a".repeat(161)}","last_name":"King"}""",
            """{"first_name":"Ada","last_name":"King","ik":"AAAA"}""",
            """{"first_name":"Ada","last_name":"King","ik":7}""",
        )
        for (p in bad) {
            val c = ApprovalParser.connection(Connection(id = "C1", state = "active", profile = o(p)))
            assertNull(p, c.accountName)
            assertEquals(p, "", c.displayName)
        }
        // 160 bytes is still a name (3-byte characters: 53 × 3 = 159, plus one).
        val ok = "\u20ac".repeat(53) + "a"
        val c = ApprovalParser.connection(Connection(id = "C1", state = "active", profile = o("""{"first_name":"$ok","last_name":"K"}""")))
        assertEquals("${"\u20ac".repeat(53)}a K", c.accountName)
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
