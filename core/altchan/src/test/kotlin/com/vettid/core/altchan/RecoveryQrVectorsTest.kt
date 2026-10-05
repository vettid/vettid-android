package com.vettid.core.altchan

import com.vettid.core.crypto.invite.InviteKind
import com.vettid.core.crypto.invite.InviteQr
import com.vettid.core.crypto.json.JsonObject
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.json.asObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recovery QR payload (§11.11.2) and the transfer QR (§6.7.1) against
 * vectors generated with vettid-vault's Go code (`resources/vectors/SOURCE.md`).
 */
class RecoveryQrVectorsTest {
    private val doc: JsonObject = StrictJson.parseObject(
        RecoveryQrVectorsTest::class.java.getResourceAsStream("/vectors/recovery-qr.json")!!.use { it.readBytes() },
    )

    @Test
    fun validPayloadsParseLikeGo() {
        val valid = doc.array("valid").map { it.asObject() }
        assertEquals(3, valid.size)
        for (v in valid) {
            val c = RecoveryCode.parseQr(v.string("qr").toByteArray())
            assertEquals(v.string("name"), v.string("vault_id"), c.vaultId)
            assertEquals(v.string("recovery_id"), c.recoveryId)
            assertEquals(v.string("code"), c.code)
            assertEquals(c, RecoveryCode.parseScanned("  " + v.string("qr") + "\n"))
            // The portal's text form, typed back.
            assertEquals(v.string("code"), RecoveryCodes.normalize(v.string("grouped")))
            assertEquals(v.string("grouped"), RecoveryCodes.grouped(v.string("code")))
        }
    }

    @Test
    fun invalidPayloadsAreRejectedLikeGo() {
        val invalid = doc.array("invalid").map { it.asObject() }
        assertTrue(invalid.size >= 8)
        for (v in invalid) {
            val qr = v.string("qr")
            assertThrows(v.string("name"), AltResultException::class.java) { RecoveryCode.parseQr(qr.toByteArray()) }
            assertNull(v.string("name"), RecoveryCode.parseScanned(qr))
        }
    }

    @Test
    fun transferQrIsAnAppPairing() {
        val t = doc.obj("transfer")
        val fromJson = InviteQr.parse(t.string("qr").toByteArray())
        val fromLink = InviteQr.parseLink(t.string("link"))
        assertEquals(InviteKind.APP, fromJson.kind)
        assertEquals(t.string("link"), fromJson.link())
        assertEquals(t.string("qr"), String(fromLink.marshal()))
        assertEquals(t.uint("exp", 1, Long.MAX_VALUE), fromLink.exp)
    }

    @Test
    fun typedCodes() {
        val code = "SK01TG8WK2FYJ1Y5MEHJ5R5J7QZKWHX0"
        assertEquals(code, RecoveryCodes.normalize("sk01-tg8w-k2fy-j1y5-mehj-5r5j-7qzk-whx0"))
        assertEquals(code, RecoveryCodes.normalize("SKOI TG8W K2FY JLY5 MEHJ 5R5J 7QZK WHXO"))
        assertNull(RecoveryCodes.normalize(code.dropLast(1)))
        assertNull(RecoveryCodes.normalize(code + "0"))
        assertNull(RecoveryCodes.normalize(code.replace('S', 'U'))) // U is not in the alphabet
        assertNull(RecoveryCodes.normalize(""))
        assertTrue(RecoveryCodes.isValid(code))
    }
}
