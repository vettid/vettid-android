// Expected values stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.crypto

import com.vettid.core.crypto.altchan.Descriptor
import com.vettid.core.crypto.altchan.ReleaseTraits
import com.vettid.core.crypto.altchan.TimeScale
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

/** The release time scale (STAGING-TIME-SCALE; VAULT-MESSAGING 0.43.0 §11.10.9) in the ETK descriptor and the transfer bundle. */
class TimeScaleTest {
    private val pcr = "a3".repeat(48)
    private val notAfter = Instant.parse("2026-10-19T12:00:00Z")

    @Test
    fun anAbsentScaleIsOneAndAMalformedOneIsRefused() {
        assertEquals(1L, TimeScale.parse(StrictJson.parseObject("{}")))
        assertEquals(24L, TimeScale.parse(StrictJson.parseObject("""{"time_scale":24}""")))
        assertEquals(86_400L, TimeScale.parse(StrictJson.parseObject("""{"time_scale":86400}""")))
        listOf(
            """{"time_scale":0}""",
            """{"time_scale":-1}""",
            """{"time_scale":86401}""",
            """{"time_scale":1.5}""",
            """{"time_scale":"24"}""",
            """{"time_scale":null}""",
        ).forEach { j -> assertThrows(j, CryptoException::class.java) { TimeScale.parse(StrictJson.parseObject(j)) } }
    }

    @Test
    fun theDescriptorCarriesItAndADescriptorBeforeTheScaleSaysOne() {
        val etk = KemPrivateKey.generate().publicKey
        val before = Descriptor.parse(Descriptor.marshal("inst-a", etk, pcr, notAfter, listOf("recovery_r")))
        assertEquals(1L, before.timeScale)
        assertEquals(ReleaseTraits(listOf("recovery_r"), 1), before.traits)
        val staging = Descriptor.parse(Descriptor.marshal("inst-a", etk, pcr, notAfter, listOf("recovery_r"), timeScale = 24))
        assertEquals(24L, staging.timeScale)
        assertEquals(ReleaseTraits(listOf("recovery_r"), 24), staging.traits)
        // The descriptor bytes are the attested ones: the scale is part of them.
        assertArrayEquals(Descriptor.marshal("inst-a", etk, pcr, notAfter, listOf("recovery_r"), timeScale = 24), staging.bytes())
        assertThrows(CryptoException::class.java) { Descriptor.parse(Descriptor.marshal("inst-a", etk, pcr, notAfter, timeScale = 0)) }
    }

    @Test
    fun onlyATransferBundleCarriesIt() {
        val ik = Ed25519PrivateKey.generate()
        val relayKey = Ed25519PrivateKey.generate()
        val vault = Principal(ik.publicKey, KemPrivateKey.generate().publicKey, RelayAddr("https://relay.vettid.org", Mailbox.id(relayKey.publicKey), relayKey.publicKey))
        val exp = Instant.parse("2026-10-01T12:10:00Z")
        val now = Instant.parse("2026-10-01T12:00:00Z")
        val app = InviteBundle(InviteKind.APP, "01JB2Z6V9K3M4N5P6Q7R8S9T0V", false, vault, "v4.public.b3Blbg", exp, capabilities = listOf("recovery_r"), timeScale = 24)
        val (blob, kb, h) = InviteBundle.seal(app.marshal())
        val qr = InviteQr(InviteKind.APP, "https://relay.vettid.org", "abcdefghijklmnopqrstuvwxyz", h, kb, exp.epochSecond)
        val opened = InviteBundle.open(blob, qr, now)
        assertEquals(24L, opened.timeScale)
        assertEquals(ReleaseTraits(listOf("recovery_r"), 24), opened.traits)
        // A bundle from a release before the scale: none, which is 1.
        val plain = InviteBundle.parse(InviteBundle(InviteKind.APP, "01JB2Z6V9K3M4N5P6Q7R8S9T0V", false, vault, "v4.public.b3Blbg", exp).marshal())
        assertNull(plain.timeScale)
        assertEquals(1L, plain.traits.timeScale)
        // Another kind with the member, or a value out of range: refused, as a malformed bundle.
        val connection = String(InviteBundle(InviteKind.CONNECTION, "01JB2Z6V9K3M4N5P6Q7R8S9T0V", false, vault, "v4.public.b3Blbg", exp).marshal())
            .replace("\"exp\":", "\"time_scale\":24,\"exp\":")
        assertThrows(CryptoException::class.java) { InviteBundle.parse(connection.toByteArray()) }
        val zero = String(app.marshal()).replace("\"time_scale\":24", "\"time_scale\":0")
        assertThrows(CryptoException::class.java) { InviteBundle.parse(zero.toByteArray()) }
        assertThrows(CryptoException::class.java) {
            InviteBundle(InviteKind.APP, "01JB2Z6V9K3M4N5P6Q7R8S9T0V", false, vault, "v4.public.b3Blbg", exp, timeScale = 0).marshal()
        }
    }
}
