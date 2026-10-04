package com.vettid.core.crypto

import com.vettid.core.crypto.altchan.AltChannel
import com.vettid.core.crypto.altchan.DeviceAttest
import com.vettid.core.crypto.altchan.Descriptor
import com.vettid.core.crypto.credential.CredentialBlobHeader
import com.vettid.core.crypto.credential.CredentialKeyRotation
import com.vettid.core.crypto.credential.CredentialSeal
import com.vettid.core.crypto.credential.Utk
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.crypto.invite.InviteBundle
import com.vettid.core.crypto.invite.InviteKind
import com.vettid.core.crypto.invite.InviteQr
import com.vettid.core.crypto.item.CriticalValues
import com.vettid.core.crypto.item.ItemContent
import com.vettid.core.crypto.item.ItemField
import com.vettid.core.crypto.item.ItemSpec
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.session.Mailbox
import com.vettid.core.crypto.session.Principal
import com.vettid.core.crypto.session.RelayAddr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CredentialAndItemsTest {
    private val vaultId = "test-vault-0001"
    private val requestId = "01JB2Z6V9K3M4N5P6Q7R8S9T30"

    @Test
    fun utkPayloadIsBoundToTypeAndRequest() {
        val ltk = KemPrivateKey.generate()
        val utk = Utk.parse(
            StrictJson.parseObject(
                JsonBuilder().string("utk_id", "0123456789abcdef").base64("ek", ltk.publicKey.bytes())
                    .string("expires_at", "2026-11-01T12:00:00.000Z").bytes(),
            ),
        )
        val payload = """{"password":"correct horse"}""".toByteArray()
        val sealed = CredentialSeal.sealPayload(utk, vaultId, "credential.unlock", requestId, payload)
        assertEquals(Suite.ENC_SIZE + payload.size + 16, sealed.size)
        val opened = CredentialSeal.openPayload(ltk, vaultId, utk.id, "credential.unlock", requestId, sealed)
        assertEquals(String(payload), String(opened))
        // Another type, request, vault or UTK id does not open it.
        for (c in listOf(
            listOf("credential.rotate", requestId, vaultId, utk.id),
            listOf("credential.unlock", "01JB2Z6V9K3M4N5P6Q7R8S9T31", vaultId, utk.id),
            listOf("credential.unlock", requestId, "other-vault", utk.id),
            listOf("credential.unlock", requestId, vaultId, "fedcba9876543210"),
        )) {
            assertThrows(CryptoException.Decrypt::class.java) { CredentialSeal.openPayload(ltk, c[2], c[3], c[0], c[1], sealed) }
        }
        assertThrows(CryptoException.Format::class.java) {
            CredentialSeal.sealPayload(utk, vaultId, "x", requestId, ByteArray(16 * 1024 + 1))
        }
        assertThrows(CryptoException.Format::class.java) {
            Utk.parse(StrictJson.parseObject("""{"utk_id":"0123456789ABCDEF","ek":"","expires_at":"2026-11-01T12:00:00.000Z"}"""))
        }
    }

    @Test
    fun replyKeyValues() {
        val reply = KemPrivateKey.generate()
        val value = """{"fields":[{"field_id":"f1","value":"abandon abandon"}],"notes":"n"}""".toByteArray()
        val sealed = CredentialSeal.sealValue(reply.publicKey, vaultId, requestId, value)
        val opened = CredentialSeal.openValue(reply, vaultId, requestId, sealed)
        val cv = CriticalValues.parse(opened)
        assertEquals(listOf("f1" to "\"abandon abandon\""), cv.fields)
        assertEquals("n", cv.notes)
        assertThrows(CryptoException.Decrypt::class.java) { CredentialSeal.openValue(reply, vaultId, "01JB2Z6V9K3M4N5P6Q7R8S9T31", sealed) }
        assertThrows(CryptoException.Decrypt::class.java) { CredentialSeal.openValue(KemPrivateKey.generate(), vaultId, requestId, sealed) }
        assertThrows(CryptoException.Decrypt::class.java) { CredentialSeal.openValue(reply, vaultId, requestId, sealed.copyOf(100)) }
    }

    @Test
    fun blobHeader() {
        val kid = rep(7, 8)
        val blob = Bytes.concat(byteArrayOf(1), Bytes.uintBE(3, 8), kid, ByteArray(Suite.ENC_SIZE + 64))
        val h = CredentialBlobHeader.parse(blob)
        assertEquals(3L, h.version)
        assertEquals(Kid(kid), h.cekKid)
        assertThrows(CryptoException.Format::class.java) { CredentialBlobHeader.parse(blob.copyOf().also { it[0] = 2 }) }
        assertThrows(CryptoException.Format::class.java) { CredentialBlobHeader.parse(blob.copyOf(17 + 1120)) }
    }

    @Test
    fun credentialKeyRotationChain() {
        val k1 = Ed25519PrivateKey.generate()
        val k2 = Ed25519PrivateKey.generate()
        val k3 = Ed25519PrivateKey.generate()
        val r1 = CredentialKeyRotation.create(k1, k2)
        val r2 = CredentialKeyRotation.parse(StrictJson.parseObject(CredentialKeyRotation.create(k2, k3).marshal()))
        assertTrue(Bytes.constantTimeEquals(k3.publicKey, CredentialKeyRotation.followChain(k1.publicKey, listOf(r1, r2))))
        assertThrows(CryptoException.Signature::class.java) { CredentialKeyRotation.followChain(k1.publicKey, listOf(r2)) }
        assertThrows(CryptoException.Signature::class.java) { CredentialKeyRotation.followChain(k1.publicKey, emptyList()) }
        assertThrows(CryptoException.Signature::class.java) { CredentialKeyRotation.create(k1, k1) }
    }

    @Test
    fun inviteRules() {
        val ik = Ed25519PrivateKey.generate()
        val relayKey = Ed25519PrivateKey.generate()
        val relay = RelayAddr("https://relay.vettid.org", Mailbox.id(relayKey.publicKey), relayKey.publicKey)
        val vault = Principal(ik.publicKey, KemPrivateKey.generate().publicKey, relay)
        val exp = Instant.parse("2026-10-01T12:10:00Z")
        val bundle = InviteBundle(InviteKind.DESKTOP, "01JB2Z6V9K3M4N5P6Q7R8S9T0V", false, vault, "v4.public.b3Blbg", exp)
        val (blob, kb, h) = InviteBundle.seal(bundle.marshal())
        val qr = InviteQr(InviteKind.DESKTOP, "https://relay.vettid.org", "abcdefghijklmnopqrstuvwxyz", h, kb, exp.epochSecond)
        val now = Instant.parse("2026-10-01T12:00:00Z")
        assertEquals(InviteKind.DESKTOP, InviteBundle.open(blob, InviteQr.parseLink(qr.link()), now).kind)
        // Commitment, kind, exp and expiry.
        assertThrows(CryptoException.Protocol::class.java) { InviteBundle.open(blob.copyOf().also { it[30] = 0 }, qr, now) }
        val wrongKind = InviteQr(InviteKind.CONNECTION, qr.relay, qr.claimId, h, kb, qr.exp)
        assertThrows(CryptoException.Protocol::class.java) { InviteBundle.open(blob, wrongKind, now) }
        val wrongExp = InviteQr(InviteKind.DESKTOP, qr.relay, qr.claimId, h, kb, qr.exp + 1)
        assertThrows(CryptoException.Format::class.java) { InviteBundle.open(blob, wrongExp, now) }
        assertThrows(CryptoException.Time::class.java) { InviteBundle.open(blob, qr, exp) }
        // Remote is for connections only; claim ids are lowercase base32.
        assertThrows(CryptoException.Format::class.java) {
            InviteBundle(InviteKind.APP, "01JB2Z6V9K3M4N5P6Q7R8S9T0V", true, vault, "v4.public.b3Blbg", exp).marshal()
        }
        assertThrows(CryptoException.Format::class.java) {
            InviteQr(InviteKind.APP, "https://relay.vettid.org", "ABCDEFGHIJKLMNOPQRSTUVWXYZ", h, kb, 1).marshal()
        }
    }

    @Test
    fun altchanFieldRules() {
        val ts = "2026-10-01T12:00:00.000Z"
        assertThrows(CryptoException.Format::class.java) { AltChannel.devattChallenge("bad", "", ts) }
        assertThrows(CryptoException.Format::class.java) { AltChannel.devattChallenge(requestId, "a b", ts) }
        assertThrows(CryptoException.Format::class.java) { AltChannel.devattChallenge(requestId, "", "2026-10-01T12:00:00Z") }
        fun fields(pin: String, cancel: Boolean = false) =
            AltChannel.UnlockFields(
                "u", vaultId, requestId, ts, Kid(ByteArray(8)), 1, 2, pin, "v4.public.x", "a".repeat(64), cancelRecovery = cancel,
            )
        val f = fields("123456", cancel = true)
        assertTrue(AltChannel.unlockSigningString(f).endsWith("\n\ncancel_recovery"))
        assertThrows(CryptoException.Format::class.java) {
            AltChannel.unlockSigningString(fields("123\n456"))
        }
        assertTrue(AltChannel.isValidPin("123456"))
        assertFalse(AltChannel.isValidPin("12345"))
        assertFalse(AltChannel.isValidPin("123"))
        assertFalse(AltChannel.isValidPin("12a456"))
        assertThrows(CryptoException.Format::class.java) { DeviceAttest.android(emptyList()) }
        assertThrows(CryptoException.Format::class.java) { DeviceAttest.android(List(11) { byteArrayOf(1) }) }
        val da = DeviceAttest.android(listOf(byteArrayOf(1, 2), byteArrayOf(3)))
        assertEquals("""{"platform":"android","chain":["AQI=","Aw=="]}""", da.marshal())
        assertEquals(2, DeviceAttest.parse(da.marshal().toByteArray()).chain().size)
        // Descriptor strictness: kid must match the ETK, release must be a non-debug PCR.
        val etk = KemPrivateKey.generate().publicKey
        val good = Descriptor.marshal("i-1", etk, "ab".repeat(48), Instant.parse("2026-10-02T12:00:00Z"))
        Descriptor.parse(good)
        assertThrows(CryptoException.Format::class.java) {
            Descriptor.parse(String(good).replace(etk.kid.toString(), "0000000000000000").toByteArray())
        }
        val debug = Descriptor.marshal("i-1", etk, "0".repeat(96), Instant.EPOCH)
        assertThrows(CryptoException.Format::class.java) { Descriptor.parse(debug) }
    }

    @Test
    fun itemShapes() {
        assertEquals("travel plans", ItemSpec.normalizeTag("  Travel   Plans "))
        assertEquals("@profile", ItemSpec.normalizeTag("@profile", reserved = true))
        assertThrows(CryptoException.Format::class.java) { ItemSpec.normalizeTag("@profile") }
        assertThrows(CryptoException.Format::class.java) { ItemSpec.normalizeTag("-x") }
        assertEquals(listOf("a", "b"), ItemSpec.normalizeTags(listOf("B", "a", "b")))
        assertThrows(CryptoException.Format::class.java) { ItemSpec.normalizeTags(List(17) { "t$it" }) }

        val ok = mapOf(
            ItemSpec.KIND_NUMBER to "-12.50", ItemSpec.KIND_DATE to "2031-04-30", ItemSpec.KIND_EMAIL to "a@b",
            ItemSpec.KIND_PHONE to "+1 (555) 010-0000", ItemSpec.KIND_URL to "https://x.org/a",
            ItemSpec.KIND_OTP to "JBSWY3DPEHPK3PXP", ItemSpec.KIND_MULTILINE to "a\nb\tc", ItemSpec.KIND_TEXT to "",
        )
        for ((k, v) in ok) assertTrue("$k $v", ItemSpec.isValidStringValue(k, v))
        assertTrue(ItemSpec.isValidStringValue(ItemSpec.KIND_DATE, "2031-04"))
        val bad = mapOf(
            ItemSpec.KIND_NUMBER to "1e3", ItemSpec.KIND_DATE to "2031-02-30", ItemSpec.KIND_EMAIL to "a@@b",
            ItemSpec.KIND_PHONE to "()", ItemSpec.KIND_URL to "x.org", ItemSpec.KIND_OTP to "SHORT",
            ItemSpec.KIND_TEXT to "a\nb", ItemSpec.KIND_FILE to "x",
        )
        for ((k, v) in bad) assertFalse("$k $v", ItemSpec.isValidStringValue(k, v))
        val addr = ItemSpec.canonicalValue(ItemSpec.KIND_ADDRESS, StrictJson.parseObject("""{"country":"NL","city":"Delft","street":""}"""))
        assertEquals("""{"city":"Delft","country":"NL"}""", addr)
        assertThrows(CryptoException.Format::class.java) {
            ItemSpec.canonicalValue(ItemSpec.KIND_ADDRESS, StrictJson.parseObject("""{"planet":"Mars"}"""))
        }

        val content = ItemContent(
            name = "Passport", category = "identity_document", template = "passport",
            fields = listOf(ItemField.text(null, "Number", "text", "X123"), ItemField.text("f2", "Expires", "date", "2031-04-30")),
            notes = "line1\nline2",
        )
        assertEquals(
            """{"name":"Passport","category":"identity_document","template":"passport",""" +
                """"fields":[{"label":"Number","kind":"text","value":"X123"},""" +
                """{"field_id":"f2","label":"Expires","kind":"date","value":"2031-04-30"}],"notes":"line1\nline2"}""",
            content.marshal(),
        )
        assertThrows(CryptoException.Format::class.java) { ItemContent(name = "").marshal() }
        assertThrows(CryptoException.Format::class.java) { ItemContent(name = "x", category = "Bad").marshal() }
        assertThrows(CryptoException.Format::class.java) { ItemField.text(null, "L", "date", "tomorrow") }
    }
}
