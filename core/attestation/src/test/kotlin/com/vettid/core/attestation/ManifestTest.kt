package com.vettid.core.attestation

import com.vettid.core.attestation.manifest.InMemoryReleaseStateStore
import com.vettid.core.attestation.manifest.ManifestKey
import com.vettid.core.attestation.manifest.ManifestKeys
import com.vettid.core.attestation.manifest.ManifestVerifier
import com.vettid.core.attestation.manifest.ReleaseManifest
import com.vettid.core.attestation.manifest.ReleaseManifestManager
import com.vettid.core.attestation.manifest.UnlockDecision
import com.vettid.core.attestation.nitro.NitroVerifier
import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.json.JsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.Signature
import java.time.Duration
import java.time.Instant

/**
 * The manifest parser and the release rules (the successor of the v1
 * PcrConfigManagerTest: signature verification, version/serial rollback,
 * current vs. previous release, refresh interval).
 */
class ManifestTest {
    private val signer: KeyPair = ecKeyPair("secp256r1")
    private val key = ManifestKey(signer.public.encoded)
    private val verifier = ManifestVerifier(listOf(key))
    private val now = Instant.parse("2026-10-04T12:00:00Z")

    private fun entry(n: Int, pcr0: String, status: String, endsAt: String? = null): String {
        val b = JsonBuilder().uint("release", n.toLong()).string("pcr0", pcr0)
            .string("pcr1", "11".repeat(48)).string("pcr2", "22".repeat(48))
            .string("seal_key", "arn:aws:kms:us-east-1:000000000000:key/test-$n").string("status", status)
            .string("published_at", "2026-09-01T00:00:00Z")
        endsAt?.let { b.string("ends_at", it) }
        return b.string("notes", "https://vettid.org/releases/$n").build()
    }

    private fun manifest(serial: Int, vararg entries: String) = JsonBuilder().uint("v", 1).uint("serial", serial.toLong())
        .string("issued_at", "2026-10-02T12:00:00Z").raw("releases", JsonBuilder.array(entries.toList())).bytes()

    private fun serve(m: ByteArray, k: KeyPair = signer, keyId: String = key.keyId): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(k.private)
            update(ReleaseManifest.LABEL.toByteArray())
            update(0)
            update(m)
            sign()
        }
        return JsonBuilder().base64("manifest", m).base64("sig", FakeNsm.derToRaw(der, 32)).string("key_id", keyId).bytes()
    }

    private val r3 = entry(3, "ab".repeat(48), "deprecated", "2026-12-01T00:00:00Z")
    private val r4 = entry(4, "cd".repeat(48), "active")

    @Test
    fun verifiesAndParses() {
        val m = verifier.verify(serve(manifest(7, r3, r4)))
        assertEquals(7L, m.serial)
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), m.byNumber(3)!!.endsAt)
        assertEquals(4L, m.byPcr0("cd".repeat(48))!!.number)
        assertNull(m.byPcr0("ef".repeat(48)))
    }

    @Test
    fun signatureAndKeyRules() {
        val m = manifest(7, r3, r4)
        assertThrows(AttestationException.ManifestKey::class.java) { verifier.verify(serve(m, keyId = "0000000000000000")) }
        assertThrows(AttestationException.Signature::class.java) { verifier.verify(serve(m, k = ecKeyPair("secp256r1"))) }
        val tampered = String(serve(m)).replace(Base64s.encodeStd(m), Base64s.encodeStd(manifest(8, r3, r4))).toByteArray()
        assertThrows(AttestationException.Signature::class.java) { verifier.verify(tampered) }
        // Nothing is pinned in this build until W3/O3: production verification fails closed.
        assertTrue(ManifestKeys.PRODUCTION.isEmpty())
        assertThrows(AttestationException.ManifestKey::class.java) { ManifestVerifier(ManifestKeys.PRODUCTION).verify(serve(m)) }
    }

    @Test
    fun strictFormat() {
        val bad = listOf(
            manifest(7, r4, r3), // not sorted
            manifest(7, r4, entry(5, "cd".repeat(48), "active")), // duplicate pcr0
            manifest(7, entry(4, "00".repeat(48), "active")), // debug pcr
            manifest(7, entry(4, "CD".repeat(48), "active")), // upper-case hex
            manifest(7, entry(4, "cd".repeat(48), "paused")), // unknown status
            manifest(7, entry(4, "cd".repeat(48), "active", "2026-12-01")), // malformed ends_at
            manifest(7, entry(4, "cd".repeat(48), "active").replace("https://", "http://")),
            manifest(7), // no releases
            manifest(0, r4),
            String(manifest(7, r4)).replace(",\"serial\"", ", \"serial\"").toByteArray(), // not compact
            String(manifest(7, r4)).replace("{\"v\":1", "{\"v\":1,\"v\":1").toByteArray(), // duplicate member
        )
        for (m in bad) assertThrows(String(m).take(80), AttestationException::class.java) { verifier.verify(serve(m)) }
        // Unknown members are ignored (additive fields).
        val withFuture = String(manifest(7, r4)).replace("\"v\":1,", "\"v\":1,\"future\":{\"x\":1},").toByteArray()
        assertEquals(7L, ReleaseManifest.parse(withFuture).serial)
    }

    @Test
    fun managerSerialAndReleaseRules() {
        val store = InMemoryReleaseStateStore()
        val mgr = ReleaseManifestManager(verifier, store)
        assertTrue(mgr.refreshDue(now))
        val m7 = mgr.accept(serve(manifest(7, r3, r4)), now)
        assertEquals(7L, store.highestSerial)
        assertEquals(7L, mgr.cached()!!.serial)
        assertTrue(!mgr.refreshDue(now.plus(Duration.ofHours(1))))
        assertTrue(mgr.refreshDue(now.plus(Duration.ofHours(25))))
        // The serial never goes back (v1: manifest version rollback).
        assertThrows(AttestationException.ManifestSerial::class.java) { mgr.accept(serve(manifest(6, r3, r4)), now) }
        assertEquals(7L, store.highestSerial)

        // Enrollment: active only.
        assertEquals(4L, mgr.checkEnroll(m7, "cd".repeat(48)).number)
        assertThrows(AttestationException.Release::class.java) { mgr.checkEnroll(m7, "ab".repeat(48)) }
        assertThrows(AttestationException.Release::class.java) { mgr.checkEnroll(m7, "ef".repeat(48)) }

        // Unlock: first unlock proceeds; deprecated shows end of life.
        assertTrue(mgr.decideUnlock(m7, "cd".repeat(48)) is UnlockDecision.Proceed)
        val eol = mgr.decideUnlock(m7, "ab".repeat(48)) as UnlockDecision.EndOfLife
        assertEquals(4L, eol.newest!!.number)
        mgr.recordUnlocked(m7.byNumber(4)!!)
        // Never a PIN to an older release than the last one unlocked into.
        assertThrows(AttestationException.Release::class.java) { mgr.decideUnlock(m7, "ab".repeat(48)) }
        // A newer listed release: tell the user first.
        val m8 = mgr.accept(serve(manifest(8, r3, r4, entry(5, "ef".repeat(48), "active"))), now)
        val up = mgr.decideUnlock(m8, "ef".repeat(48)) as UnlockDecision.Updated
        assertEquals(4L, up.from.second)
        // Removed: the vault cannot be opened there.
        val m9 = mgr.accept(serve(manifest(9, entry(4, "cd".repeat(48), "removed"), entry(5, "ef".repeat(48), "active"))), now)
        assertTrue(mgr.decideUnlock(m9, "cd".repeat(48)) is UnlockDecision.Removed)
        // A cached document that no longer verifies is dropped.
        store.servedManifest = "{}".toByteArray()
        assertNull(mgr.cached())
    }

    @Test
    fun enclaveChecks() {
        val nsm = FakeNsm(pcr0 = "cd".repeat(48))
        val ev = EnclaveVerifier(NitroVerifier(listOf(nsm.ca.root)))
        val m7 = verifier.verify(serve(manifest(7, r3, r4)))
        val etk = com.vettid.core.crypto.hpke.KemPrivateKey.generate().publicKey
        val t0 = Instant.parse("2026-10-02T12:00:00Z")
        val desc = com.vettid.core.crypto.altchan.Descriptor.marshal("i-test-1", etk, "cd".repeat(48), t0.plus(Duration.ofHours(24)))
        val att = nsm.attest(t0, userData = com.vettid.core.crypto.altchan.AltChannel.etkUserData(desc))
        val e = ev.verifyEnclave(desc, att, m7, enroll = true, now = t0.plusSeconds(60))
        assertEquals(4L, e.release.number)
        assertEquals(etk, e.descriptor.etk)
        // user_data over other bytes, an old attestation, an expired descriptor, a release not in the manifest.
        val other = com.vettid.core.crypto.altchan.Descriptor.marshal("i-test-2", etk, "cd".repeat(48), t0.plus(Duration.ofHours(24)))
        assertThrows(AttestationException.UserData::class.java) { ev.verifyEnclave(other, att, m7, true, t0) }
        assertThrows(AttestationException.Stale::class.java) { ev.verifyEnclave(desc, att, m7, true, t0.plus(Duration.ofHours(27))) }
        val long = com.vettid.core.crypto.altchan.Descriptor.marshal("i-test-1", etk, "cd".repeat(48), t0.plus(Duration.ofHours(25)))
        val attLong = nsm.attest(t0, userData = com.vettid.core.crypto.altchan.AltChannel.etkUserData(long))
        assertThrows(AttestationException.Stale::class.java) { ev.verifyEnclave(long, attLong, m7, true, t0) }
        val nsm3 = FakeNsm(nsm.ca, pcr0 = "ab".repeat(48))
        val desc3 = com.vettid.core.crypto.altchan.Descriptor.marshal("i-test-3", etk, "ab".repeat(48), t0.plus(Duration.ofHours(24)))
        val att3 = nsm3.attest(t0, userData = com.vettid.core.crypto.altchan.AltChannel.etkUserData(desc3))
        assertThrows(AttestationException.Release::class.java) { ev.verifyEnclave(desc3, att3, m7, enroll = true, now = t0) } // deprecated
        assertEquals(3L, ev.verifyEnclave(desc3, att3, m7, enroll = false, now = t0).release.number) // unlock is fine
        val nsmX = FakeNsm(nsm.ca, pcr0 = "cd".repeat(48), pcr1 = "99".repeat(48))
        val attX = nsmX.attest(t0, userData = com.vettid.core.crypto.altchan.AltChannel.etkUserData(desc))
        assertThrows(AttestationException.Pcr::class.java) { ev.verifyEnclave(desc, attX, m7, true, t0) }
        // vault.enrolled: nonce, bundle and the enrollment's measurements.
        val bundle = "{\"v\":1}".toByteArray()
        val nonce = ByteArray(32) { 7 }
        val enrolled = nsm.attest(t0, userData = com.vettid.core.crypto.altchan.AltChannel.vaultUserData(bundle), nonce = nonce)
        ev.verifyEnrolled(enrolled, bundle, nonce, e.measurements, t0)
        assertThrows(AttestationException.Nonce::class.java) { ev.verifyEnrolled(enrolled, bundle, ByteArray(32), e.measurements, t0) }
        val otherMs = NitroVerifier(listOf(nsm.ca.root)).verify(nsm3.attest(t0)).measurements
        assertThrows(AttestationException.Pcr::class.java) { ev.verifyEnrolled(enrolled, bundle, nonce, otherMs, t0) }
    }
}
