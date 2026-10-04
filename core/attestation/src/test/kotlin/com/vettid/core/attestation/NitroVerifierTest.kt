package com.vettid.core.attestation

import com.vettid.core.attestation.nitro.CborEncoder
import com.vettid.core.attestation.nitro.CborValue
import com.vettid.core.attestation.nitro.NitroRoot
import com.vettid.core.attestation.nitro.NitroVerifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * The Nitro verifier, ported from the v1 app's NitroAttestationVerifierTest
 * (timestamp, nonce, PCR and DER helper cases) and vettid-vault's nitro tests
 * (round trip, rejections, the real AWS document).
 */
class NitroVerifierTest {
    private val t0 = Instant.parse("2026-10-02T12:00:00Z")
    private val nsm = FakeNsm()
    private val verifier = NitroVerifier(listOf(nsm.ca.root))

    @Test
    fun pinnedRootIsTheAwsFile() {
        assertEquals(NitroRoot.SHA256_HEX, NitroVerifier.fingerprint(NitroRoot.certificate))
        assertEquals("CN=aws.nitro-enclaves,OU=AWS,O=Amazon,C=US", NitroRoot.certificate.subjectX500Principal.name)
    }

    @Test
    fun roundTrip() {
        val doc = nsm.attest(t0, "ud".toByteArray(), "nonce".toByteArray(), "pk".toByteArray())
        val d = verifier.verify(doc)
        assertEquals(t0, d.timestamp)
        assertEquals("i-test-enc0123", d.moduleId)
        assertArrayEquals("ud".toByteArray(), d.userData)
        assertArrayEquals("pk".toByteArray(), d.publicKey)
        assertEquals("ab".repeat(48), d.measurements.pcr0)
        assertFalse(d.measurements.isDebug)
        d.checkUserData("ud".toByteArray())
        d.checkNonce("nonce".toByteArray())
        assertThrows(AttestationException.UserData::class.java) { d.checkUserData("x".toByteArray()) }
        assertThrows(AttestationException.Nonce::class.java) { d.checkNonce("nonce2".toByteArray()) }
        // Freshness (v1: past window and future skew).
        val age = Duration.ofHours(26)
        val skew = Duration.ofMinutes(1)
        d.checkFresh(t0.plus(Duration.ofHours(25)), age, skew)
        assertThrows(AttestationException.Stale::class.java) { d.checkFresh(t0.plus(Duration.ofHours(27)), age, skew) }
        assertThrows(AttestationException.Stale::class.java) { d.checkFresh(t0.minus(Duration.ofMinutes(2)), age, skew) }
        // Null optional fields.
        val bare = verifier.verify(nsm.attest(t0))
        assertNull(bare.userData)
        assertNull(bare.nonce)
        assertThrows(AttestationException.Nonce::class.java) { bare.checkNonce("n".toByteArray()) }
    }

    @Test
    fun rejections() {
        val doc = nsm.attest(t0, "ud".toByteArray())
        // The real AWS root does not anchor the test chain.
        assertThrows(AttestationException.Chain::class.java) { NitroVerifier().verify(doc) }
        assertThrows(AttestationException.Chain::class.java) { NitroVerifier(emptyList()).verify(doc) }
        // Flipping payload bytes fails the signature (or the payload no longer parses).
        var i = 60
        while (i < doc.size - 200) {
            val bad = doc.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertThrows("flip at $i", AttestationException::class.java) { verifier.verify(bad) }
            i += 97
        }
        // Signed by a key outside the chain.
        assertThrows(AttestationException.Signature::class.java) { verifier.verify(nsm.attest(t0, signer = ecKeyPair())) }
        // Another algorithm in the protected header.
        assertThrows(AttestationException.Format::class.java) { verifier.verify(nsm.attest(t0, alg = -7)) }
        // A document from outside the leaf's validity (evaluated at the document's timestamp).
        assertThrows(AttestationException.Chain::class.java) { verifier.verify(nsm.attest(t0.plus(Duration.ofDays(4000)))) }
        // Debug PCRs are reported.
        val dbg = FakeNsm(nsm.ca, pcr0 = "00".repeat(48))
        assertTrue(verifier.verify(dbg.attest(t0)).measurements.isDebug)
        // Garbage, trailing bytes, an oversized document.
        assertThrows(AttestationException.Format::class.java) { verifier.verify(byteArrayOf(1, 2, 3)) }
        assertThrows(AttestationException.Format::class.java) { verifier.verify(doc + byteArrayOf(0)) }
        assertThrows(AttestationException.Format::class.java) { verifier.verify(ByteArray(NitroVerifier.MAX_DOCUMENT + 1)) }
    }

    /**
     * A real NSM document (indefinite-length payload map, untagged, null nonce)
     * verifies against the pinned AWS root at its own timestamp (vettid-vault
     * vms/nitro fixture_test.go).
     */
    @Test
    fun realAwsDocument() {
        val b64 = javaClass.getResourceAsStream("/nitro/aws-attestation-2025-09-16.b64")!!.use { String(it.readBytes()).trim() }
        val doc = Base64.getDecoder().decode(b64)
        assertEquals(0xbf, doc[10].toInt() and 0xff)
        val d = NitroVerifier().verify(doc)
        assertTrue(d.moduleId.startsWith("i-02447a481344e9ec0-enc"))
        assertEquals(16, d.pcrs.size)
        assertFalse(d.measurements.isDebug)
        assertEquals(32, d.userData!!.size)
        assertNull(d.nonce)
        assertTrue(d.publicKey!!.isNotEmpty())
        assertThrows(AttestationException.Stale::class.java) {
            d.checkFresh(d.timestamp.plus(Duration.ofDays(365)), Duration.ofMinutes(5), Duration.ofMinutes(1))
        }
        val sig = doc.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(AttestationException.Signature::class.java) { NitroVerifier().verify(sig) }
        val at = String(doc, Charsets.ISO_8859_1).indexOf("module_id") + 12
        val altered = doc.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
        assertThrows(AttestationException::class.java) { NitroVerifier().verify(altered) }
        assertThrows(AttestationException.Chain::class.java) { NitroVerifier(listOf(nsm.ca.root)).verify(doc) }
    }

    @Test
    fun cborStrictness() {
        assertEquals(CborValue.UInt(500), CborValue.decode(CborEncoder().uint(500).bytes()))
        assertEquals(CborValue.NInt(34), CborValue.decode(CborEncoder().int(-35).bytes()))
        // Duplicate keys, floats, undefined, indefinite strings, trailing data, depth.
        val dup = CborEncoder().map(2).text("a").uint(1).text("a").uint(2).bytes()
        assertThrows(AttestationException.Format::class.java) { CborValue.decode(dup) }
        assertThrows(AttestationException.Format::class.java) { CborValue.decode(byteArrayOf(0xf9.toByte(), 0, 0)) }
        assertThrows(AttestationException.Format::class.java) { CborValue.decode(byteArrayOf(0xf7.toByte())) }
        assertThrows(AttestationException.Format::class.java) { CborValue.decode(byteArrayOf(0x5f, 0xff.toByte())) }
        assertThrows(AttestationException.Format::class.java) { CborValue.decode(byteArrayOf(0x01, 0x02)) }
        assertThrows(AttestationException.Format::class.java) { CborValue.decode(ByteArray(20) { 0x81.toByte() } + byteArrayOf(0)) }
        assertThrows(AttestationException.Format::class.java) { CborValue.decode(byteArrayOf(0x5a, 0x7f, 0, 0, 0)) }
        // Indefinite maps and arrays, as the NSM emits.
        val indef = byteArrayOf(0xbf.toByte(), 0x61, 0x61, 0x9f.toByte(), 0x01, 0xff.toByte(), 0xff.toByte())
        val m = CborValue.decode(indef) as CborValue.Map
        assertEquals(CborValue.Array(listOf(CborValue.UInt(1))), m.lookup("a"))
    }

    @Test
    fun p1363ToDer() {
        // v1 test: leading zeros trimmed, a zero byte added when the high bit is set.
        val raw = ByteArray(48) { 0 }.also { it[47] = 5 } + ByteArray(48) { 0xff.toByte() }
        val der = NitroVerifier.rawToDer(raw)
        assertEquals(0x30, der[0].toInt())
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x05), der.copyOfRange(2, 5))
        assertArrayEquals(byteArrayOf(0x02, 49, 0x00), der.copyOfRange(5, 8))
    }
}
