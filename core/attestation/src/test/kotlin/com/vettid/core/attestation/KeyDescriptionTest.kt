package com.vettid.core.attestation

import com.vettid.core.attestation.android.KeyDescription
import com.vettid.core.attestation.android.RootOfTrust
import com.vettid.core.attestation.android.SecurityLevel
import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.DERTaggedObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The KeyDescription parser on synthetic extensions (the real one is checked on the phone). */
class KeyDescriptionTest {
    private fun seq(vararg e: ASN1Encodable) = DERSequence(ASN1EncodableVector().apply { e.forEach { add(it) } })

    private fun tagged(tag: Int, v: ASN1Encodable) = DERTaggedObject(true, tag, v)

    private fun description(
        level: Int = 1,
        challenge: ByteArray = ByteArray(32) { 1 },
        purposes: List<Int> = listOf(2),
        bootState: Int = RootOfTrust.VERIFIED,
        locked: Boolean = true,
    ): ByteArray {
        val hw = seq(
            tagged(1, DERSet(purposes.map { ASN1Integer(it.toLong()) }.toTypedArray())),
            tagged(2, ASN1Integer(3)),
            tagged(10, ASN1Integer(1)),
            tagged(702, ASN1Integer(0)),
            tagged(
                704,
                seq(
                    DEROctetString(ByteArray(32) { 9 }),
                    ASN1Boolean.getInstance(locked),
                    ASN1Enumerated(bootState),
                    DEROctetString(ByteArray(32)),
                ),
            ),
        )
        return seq(
            ASN1Integer(300), ASN1Enumerated(level), ASN1Integer(300), ASN1Enumerated(level),
            DEROctetString(challenge), DEROctetString(ByteArray(0)), seq(), hw,
        ).encoded
    }

    @Test
    fun parsesAndChecks() {
        val k = KeyDescription.parse(description())
        assertEquals(300, k.attestationVersion)
        assertEquals(SecurityLevel.TRUSTED_ENVIRONMENT, k.keyMintSecurityLevel)
        assertEquals(setOf(2), k.purposes)
        assertTrue(k.rootOfTrust!!.deviceLocked)
        assertEquals(emptyList<String>(), k.problems(ByteArray(32) { 1 }))
        assertEquals(listOf("challenge"), k.problems(ByteArray(32)))
        assertEquals(SecurityLevel.STRONG_BOX, KeyDescription.parse(description(level = 2)).attestationSecurityLevel)
        assertTrue("attestation security level" in KeyDescription.parse(description(level = 0)).problems(ByteArray(32) { 1 }))
        assertTrue("purposes" in KeyDescription.parse(description(purposes = listOf(2, 0))).problems(ByteArray(32) { 1 }))
        assertTrue("verified boot" in KeyDescription.parse(description(bootState = RootOfTrust.UNVERIFIED)).problems(ByteArray(32) { 1 }))
        assertTrue("device locked" in KeyDescription.parse(description(locked = false)).problems(ByteArray(32) { 1 }))
        // SelfSigned (GrapheneOS) is a boot state the enclave can accept with a pinned key.
        val selfSigned = KeyDescription.parse(description(bootState = RootOfTrust.SELF_SIGNED))
        assertEquals(emptyList<String>(), selfSigned.problems(ByteArray(32) { 1 }))
        assertThrows(AttestationException.Format::class.java) { KeyDescription.parse(byteArrayOf(0x30, 0x00)) }
        assertThrows(AttestationException.Format::class.java) { KeyDescription.parse(byteArrayOf(1, 2, 3)) }
    }
}
