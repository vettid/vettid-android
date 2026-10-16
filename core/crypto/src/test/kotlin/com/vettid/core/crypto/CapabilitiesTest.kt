// Expected values stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.crypto

import com.vettid.core.crypto.altchan.Capabilities
import com.vettid.core.crypto.altchan.Descriptor
import com.vettid.core.crypto.altchan.RecoveryOpen
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.crypto.invite.InviteBundle
import com.vettid.core.crypto.invite.InviteKind
import com.vettid.core.crypto.invite.InviteQr
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.session.Mailbox
import com.vettid.core.crypto.session.Principal
import com.vettid.core.crypto.session.RelayAddr
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

/** Release capabilities (VAULT-MESSAGING 0.34.0 §11.10.9) in the ETK descriptor (§11.2) and the transfer bundle (§6.4). */
class CapabilitiesTest {
    private val pcr = "a3".repeat(48)
    private val notAfter = Instant.parse("2026-10-17T12:00:00Z")

    @Test
    fun aMissingListIsEmptyAndAMalformedOneIsRefused() {
        assertEquals(emptyList<String>(), Capabilities.parse(StrictJson.parseObject("{}")))
        assertEquals(
            listOf("attachments", "recovery_offered", "recovery_r", "zz_unknown_9"),
            Capabilities.parse(StrictJson.parseObject("""{"capabilities":["attachments","recovery_offered","recovery_r","zz_unknown_9"]}""")),
        )
        listOf(
            """{"capabilities":["recovery_r","attachments"]}""", // not sorted
            """{"capabilities":["recovery_r","recovery_r"]}""", // not unique
            """{"capabilities":["Recovery_R"]}""", // not lowercase
            """{"capabilities":[""]}""",
            """{"capabilities":["${"a".repeat(33)}"]}""",
            """{"capabilities":[1]}""",
            """{"capabilities":"recovery_r"}""",
            """{"capabilities":[${(1..33).joinToString(",") { "\"n%02d\"".format(it) }}]}""",
        ).forEach { j -> assertThrows(j, CryptoException::class.java) { Capabilities.parse(StrictJson.parseObject(j)) } }
    }

    @Test
    fun theDescriptorCarriesThemAndABeforeRDescriptorNone() {
        val etk = KemPrivateKey.generate().publicKey
        val before = Descriptor.parse(Descriptor.marshal("inst-a", etk, pcr, notAfter))
        assertEquals(emptyList<String>(), before.capabilities)
        val r = Descriptor.parse(Descriptor.marshal("inst-a", etk, pcr, notAfter, listOf("recovery_offered", "recovery_r")))
        assertEquals(listOf("recovery_offered", "recovery_r"), r.capabilities)
        // The descriptor bytes are the attested ones: the list is part of them.
        assertArrayEquals(Descriptor.marshal("inst-a", etk, pcr, notAfter, listOf("recovery_offered", "recovery_r")), r.bytes())
        assertThrows(CryptoException::class.java) {
            Descriptor.parse(Descriptor.marshal("inst-a", etk, pcr, notAfter, listOf("recovery_r", "attachments")))
        }
    }

    @Test
    fun onlyATransferBundleCarriesThem() {
        val ik = Ed25519PrivateKey.generate()
        val relayKey = Ed25519PrivateKey.generate()
        val vault = Principal(ik.publicKey, KemPrivateKey.generate().publicKey, RelayAddr("https://relay.vettid.org", Mailbox.id(relayKey.publicKey), relayKey.publicKey))
        val exp = Instant.parse("2026-10-01T12:10:00Z")
        val now = Instant.parse("2026-10-01T12:00:00Z")
        val app = InviteBundle(InviteKind.APP, "01JB2Z6V9K3M4N5P6Q7R8S9T0V", false, vault, "v4.public.b3Blbg", exp, capabilities = listOf("recovery_r"))
        val (blob, kb, h) = InviteBundle.seal(app.marshal())
        val qr = InviteQr(InviteKind.APP, "https://relay.vettid.org", "abcdefghijklmnopqrstuvwxyz", h, kb, exp.epochSecond)
        assertEquals(listOf("recovery_r"), InviteBundle.open(blob, qr, now).capabilities)
        // A transfer bundle from a release before R: none.
        val plain = InviteBundle(InviteKind.APP, "01JB2Z6V9K3M4N5P6Q7R8S9T0V", false, vault, "v4.public.b3Blbg", exp)
        assertNull(InviteBundle.parse(plain.marshal()).capabilities)
        // Another kind with the member: refused, as a malformed bundle.
        val connection = String(InviteBundle(InviteKind.CONNECTION, "01JB2Z6V9K3M4N5P6Q7R8S9T0V", false, vault, "v4.public.b3Blbg", exp).marshal())
            .replace("\"exp\":", "\"capabilities\":[\"recovery_r\"],\"exp\":")
        assertThrows(CryptoException::class.java) { InviteBundle.parse(connection.toByteArray()) }
        assertThrows(CryptoException::class.java) {
            InviteBundle(InviteKind.DESKTOP, "01JB2Z6V9K3M4N5P6Q7R8S9T0V", false, vault, "v4.public.b3Blbg", exp, capabilities = emptyList()).marshal()
        }
    }

    /** `attempt_used` (§11.14.6): read as given, never inferred; absent from an older vault. */
    @Test
    fun theOpenResultSaysWhetherThisRequestUsedAnAttempt() {
        assertEquals(true, RecoveryOpen.Result.parse("""{"ok":false,"code":"quiet","attempt_used":true,"attempts":{"used":4,"of":10}}""".toByteArray()).attemptUsed)
        assertEquals(false, RecoveryOpen.Result.parse("""{"ok":false,"code":"quiet","attempt_used":false,"attempts":{"used":3,"of":10}}""".toByteArray()).attemptUsed)
        assertNull(RecoveryOpen.Result.parse("""{"ok":false,"code":"busy"}""".toByteArray()).attemptUsed)
    }
}
