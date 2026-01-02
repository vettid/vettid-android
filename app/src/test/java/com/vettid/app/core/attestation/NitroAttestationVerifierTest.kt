package com.vettid.app.core.attestation

import android.util.Base64
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Method

/**
 * Unit tests for NitroAttestationVerifier
 *
 * Tests cover:
 * - Data class construction and properties
 * - Helper method functionality (hex conversion, DER encoding)
 * - Timestamp validation logic
 * - PCR verification logic
 * - Nonce verification logic
 * - Error handling and exception types
 *
 * Note: Full attestation document verification requires real AWS Nitro
 * attestation documents which are only available from running enclaves.
 * These tests focus on the verification logic components.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NitroAttestationVerifierTest {

    private lateinit var verifier: NitroAttestationVerifier

    @Before
    fun setup() {
        verifier = NitroAttestationVerifier()
    }

    // MARK: - ExpectedPcrs Tests

    @Test
    fun `ExpectedPcrs stores PCR values correctly`() {
        val pcrs = ExpectedPcrs(
            pcr0 = "aabbccdd",
            pcr1 = "11223344",
            pcr2 = "55667788"
        )

        assertEquals("aabbccdd", pcrs.pcr0)
        assertEquals("11223344", pcrs.pcr1)
        assertEquals("55667788", pcrs.pcr2)
        assertNull(pcrs.pcr3)
    }

    @Test
    fun `ExpectedPcrs with optional PCR3`() {
        val pcrs = ExpectedPcrs(
            pcr0 = "aabbccdd",
            pcr1 = "11223344",
            pcr2 = "55667788",
            pcr3 = "99aabbcc"
        )

        assertEquals("99aabbcc", pcrs.pcr3)
    }

    @Test
    fun `ExpectedPcrs with version and publishedAt`() {
        val pcrs = ExpectedPcrs(
            pcr0 = "aabbccdd",
            pcr1 = "11223344",
            pcr2 = "55667788",
            version = "1.2.3",
            publishedAt = "2026-01-02T12:00:00Z"
        )

        assertEquals("1.2.3", pcrs.version)
        assertEquals("2026-01-02T12:00:00Z", pcrs.publishedAt)
    }

    @Test
    fun `ExpectedPcrs default version is unknown`() {
        val pcrs = ExpectedPcrs(
            pcr0 = "aabbccdd",
            pcr1 = "11223344",
            pcr2 = "55667788"
        )

        assertEquals("unknown", pcrs.version)
    }

    @Test
    fun `ExpectedPcrs data class equality`() {
        val pcrs1 = ExpectedPcrs("aa", "bb", "cc")
        val pcrs2 = ExpectedPcrs("aa", "bb", "cc")
        val pcrs3 = ExpectedPcrs("aa", "bb", "dd")

        assertEquals(pcrs1, pcrs2)
        assertNotEquals(pcrs1, pcrs3)
    }

    // MARK: - VerifiedAttestation Tests

    @Test
    fun `VerifiedAttestation stores data correctly`() {
        val publicKey = byteArrayOf(1, 2, 3, 4)
        val pcrs = mapOf(0 to byteArrayOf(10, 20, 30))

        val attestation = VerifiedAttestation(
            enclavePublicKey = publicKey,
            moduleId = "test-module",
            timestamp = 1234567890L,
            pcrs = pcrs,
            userData = null
        )

        assertTrue(publicKey.contentEquals(attestation.enclavePublicKey))
        assertEquals("test-module", attestation.moduleId)
        assertEquals(1234567890L, attestation.timestamp)
        assertNull(attestation.userData)
    }

    @Test
    fun `VerifiedAttestation with userData`() {
        val userData = byteArrayOf(100, 101, 102)

        val attestation = VerifiedAttestation(
            enclavePublicKey = byteArrayOf(1, 2, 3),
            moduleId = "module",
            timestamp = 123L,
            pcrs = emptyMap(),
            userData = userData
        )

        assertNotNull(attestation.userData)
        assertTrue(userData.contentEquals(attestation.userData))
    }

    @Test
    fun `VerifiedAttestation enclavePublicKeyBase64 encodes correctly`() {
        val publicKey = byteArrayOf(0, 1, 2, 3, 4, 5)

        val attestation = VerifiedAttestation(
            enclavePublicKey = publicKey,
            moduleId = "module",
            timestamp = 123L,
            pcrs = emptyMap(),
            userData = null
        )

        val base64 = attestation.enclavePublicKeyBase64()
        val decoded = Base64.decode(base64, Base64.NO_WRAP)
        assertTrue(publicKey.contentEquals(decoded))
    }

    @Test
    fun `VerifiedAttestation equality based on key content`() {
        val att1 = VerifiedAttestation(
            enclavePublicKey = byteArrayOf(1, 2, 3),
            moduleId = "module",
            timestamp = 123L,
            pcrs = emptyMap(),
            userData = null
        )
        val att2 = VerifiedAttestation(
            enclavePublicKey = byteArrayOf(1, 2, 3),
            moduleId = "module",
            timestamp = 123L,
            pcrs = emptyMap(),
            userData = null
        )
        val att3 = VerifiedAttestation(
            enclavePublicKey = byteArrayOf(4, 5, 6),
            moduleId = "module",
            timestamp = 123L,
            pcrs = emptyMap(),
            userData = null
        )

        assertEquals(att1, att2)
        assertNotEquals(att1, att3)
    }

    @Test
    fun `VerifiedAttestation hashCode consistent with equals`() {
        val att1 = VerifiedAttestation(
            enclavePublicKey = byteArrayOf(1, 2, 3),
            moduleId = "module",
            timestamp = 123L,
            pcrs = emptyMap(),
            userData = null
        )
        val att2 = VerifiedAttestation(
            enclavePublicKey = byteArrayOf(1, 2, 3),
            moduleId = "module",
            timestamp = 123L,
            pcrs = emptyMap(),
            userData = null
        )

        assertEquals(att1.hashCode(), att2.hashCode())
    }

    // MARK: - AttestationVerificationException Tests

    @Test
    fun `AttestationVerificationException with message only`() {
        val exception = AttestationVerificationException("Test error")

        assertEquals("Test error", exception.message)
        assertNull(exception.cause)
    }

    @Test
    fun `AttestationVerificationException with message and cause`() {
        val cause = RuntimeException("Root cause")
        val exception = AttestationVerificationException("Test error", cause)

        assertEquals("Test error", exception.message)
        assertEquals(cause, exception.cause)
    }

    @Test
    fun `AttestationVerificationException is throwable`() {
        assertThrows(AttestationVerificationException::class.java) {
            throw AttestationVerificationException("Test")
        }
    }

    // MARK: - Hex Conversion Tests (using reflection)

    @Test
    fun `hexToBytes converts valid hex string`() {
        val hexToBytes = getPrivateMethod("hexToBytes", String::class.java)

        val result = hexToBytes.invoke(verifier, "aabbccdd") as ByteArray

        assertEquals(4, result.size)
        assertEquals(0xaa.toByte(), result[0])
        assertEquals(0xbb.toByte(), result[1])
        assertEquals(0xcc.toByte(), result[2])
        assertEquals(0xdd.toByte(), result[3])
    }

    @Test
    fun `hexToBytes handles lowercase`() {
        val hexToBytes = getPrivateMethod("hexToBytes", String::class.java)

        val result = hexToBytes.invoke(verifier, "aabbccdd") as ByteArray

        assertEquals(4, result.size)
    }

    @Test
    fun `hexToBytes handles uppercase`() {
        val hexToBytes = getPrivateMethod("hexToBytes", String::class.java)

        val result = hexToBytes.invoke(verifier, "AABBCCDD") as ByteArray

        assertEquals(4, result.size)
        assertEquals(0xaa.toByte(), result[0])
    }

    @Test
    fun `hexToBytes handles mixed case`() {
        val hexToBytes = getPrivateMethod("hexToBytes", String::class.java)

        val result = hexToBytes.invoke(verifier, "AaBbCcDd") as ByteArray

        assertEquals(4, result.size)
    }

    @Test
    fun `hexToBytes removes spaces`() {
        val hexToBytes = getPrivateMethod("hexToBytes", String::class.java)

        val result = hexToBytes.invoke(verifier, "aa bb cc dd") as ByteArray

        assertEquals(4, result.size)
    }

    @Test
    fun `hexToBytes removes colons`() {
        val hexToBytes = getPrivateMethod("hexToBytes", String::class.java)

        val result = hexToBytes.invoke(verifier, "aa:bb:cc:dd") as ByteArray

        assertEquals(4, result.size)
    }

    @Test
    fun `hexToBytes handles empty string`() {
        val hexToBytes = getPrivateMethod("hexToBytes", String::class.java)

        val result = hexToBytes.invoke(verifier, "") as ByteArray

        assertEquals(0, result.size)
    }

    // MARK: - Timestamp Verification Tests (using reflection)

    @Test
    fun `verifyTimestamp accepts recent timestamp`() {
        val verifyTimestamp = getPrivateMethod("verifyTimestamp", Long::class.java)
        val recentTimestamp = System.currentTimeMillis() - 60_000 // 1 minute ago

        // Should not throw
        verifyTimestamp.invoke(verifier, recentTimestamp)
    }

    @Test
    fun `verifyTimestamp rejects future timestamp`() {
        val verifyTimestamp = getPrivateMethod("verifyTimestamp", Long::class.java)
        val futureTimestamp = System.currentTimeMillis() + 60_000 // 1 minute in future

        try {
            verifyTimestamp.invoke(verifier, futureTimestamp)
            fail("Expected exception for future timestamp")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(e.cause is AttestationVerificationException)
            assertTrue(e.cause?.message?.contains("future") == true)
        }
    }

    @Test
    fun `verifyTimestamp rejects old timestamp`() {
        val verifyTimestamp = getPrivateMethod("verifyTimestamp", Long::class.java)
        val oldTimestamp = System.currentTimeMillis() - 10 * 60 * 1000 // 10 minutes ago

        try {
            verifyTimestamp.invoke(verifier, oldTimestamp)
            fail("Expected exception for old timestamp")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(e.cause is AttestationVerificationException)
            assertTrue(e.cause?.message?.contains("too old") == true)
        }
    }

    @Test
    fun `verifyTimestamp accepts timestamp at boundary`() {
        val verifyTimestamp = getPrivateMethod("verifyTimestamp", Long::class.java)
        // Just under 5 minutes (4 minutes 59 seconds)
        val boundaryTimestamp = System.currentTimeMillis() - (4 * 60 * 1000 + 59 * 1000)

        // Should not throw
        verifyTimestamp.invoke(verifier, boundaryTimestamp)
    }

    // MARK: - Nonce Verification Tests (using reflection)

    @Test
    fun `verifyNonce accepts matching nonce`() {
        val verifyNonce = getPrivateMethod("verifyNonce", ByteArray::class.java, ByteArray::class.java)
        val nonce = byteArrayOf(1, 2, 3, 4, 5)

        // Should not throw
        verifyNonce.invoke(verifier, nonce, nonce.clone())
    }

    @Test
    fun `verifyNonce rejects null actual nonce`() {
        val verifyNonce = getPrivateMethod("verifyNonce", ByteArray::class.java, ByteArray::class.java)
        val expected = byteArrayOf(1, 2, 3)

        try {
            verifyNonce.invoke(verifier, null, expected)
            fail("Expected exception for null nonce")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(e.cause is AttestationVerificationException)
            assertTrue(e.cause?.message?.contains("missing nonce") == true)
        }
    }

    @Test
    fun `verifyNonce rejects mismatched nonce`() {
        val verifyNonce = getPrivateMethod("verifyNonce", ByteArray::class.java, ByteArray::class.java)
        val actual = byteArrayOf(1, 2, 3)
        val expected = byteArrayOf(4, 5, 6)

        try {
            verifyNonce.invoke(verifier, actual, expected)
            fail("Expected exception for mismatched nonce")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(e.cause is AttestationVerificationException)
            assertTrue(e.cause?.message?.contains("mismatch") == true)
        }
    }

    @Test
    fun `verifyNonce rejects nonce with different length`() {
        val verifyNonce = getPrivateMethod("verifyNonce", ByteArray::class.java, ByteArray::class.java)
        val actual = byteArrayOf(1, 2, 3)
        val expected = byteArrayOf(1, 2, 3, 4)

        try {
            verifyNonce.invoke(verifier, actual, expected)
            fail("Expected exception for different length nonce")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(e.cause is AttestationVerificationException)
        }
    }

    // MARK: - PCR Verification Tests (using reflection)

    @Test
    fun `verifyPcr accepts matching PCR value`() {
        val verifyPcr = getPrivateMethod(
            "verifyPcr",
            Map::class.java,
            Int::class.java,
            String::class.java,
            String::class.java
        )

        val pcrs = mapOf(0 to byteArrayOf(0xaa.toByte(), 0xbb.toByte()))

        // Should not throw
        verifyPcr.invoke(verifier, pcrs, 0, "aabb", "PCR0")
    }

    @Test
    fun `verifyPcr rejects missing PCR`() {
        val verifyPcr = getPrivateMethod(
            "verifyPcr",
            Map::class.java,
            Int::class.java,
            String::class.java,
            String::class.java
        )

        val pcrs = emptyMap<Int, ByteArray>()

        try {
            verifyPcr.invoke(verifier, pcrs, 0, "aabb", "PCR0")
            fail("Expected exception for missing PCR")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(e.cause is AttestationVerificationException)
            assertTrue(e.cause?.message?.contains("missing") == true)
        }
    }

    @Test
    fun `verifyPcr rejects mismatched PCR value`() {
        val verifyPcr = getPrivateMethod(
            "verifyPcr",
            Map::class.java,
            Int::class.java,
            String::class.java,
            String::class.java
        )

        val pcrs = mapOf(0 to byteArrayOf(0xaa.toByte(), 0xbb.toByte()))

        try {
            verifyPcr.invoke(verifier, pcrs, 0, "ccdd", "PCR0")
            fail("Expected exception for mismatched PCR")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(e.cause is AttestationVerificationException)
            assertTrue(e.cause?.message?.contains("mismatch") == true)
        }
    }

    // MARK: - DER Signature Conversion Tests (using reflection)

    @Test
    fun `convertCoseSignatureToDer handles 96-byte P-384 signature`() {
        val convertMethod = getPrivateMethod("convertCoseSignatureToDer", ByteArray::class.java)

        // Create a 96-byte signature (48 bytes r + 48 bytes s)
        val coseSignature = ByteArray(96) { it.toByte() }

        val derSignature = convertMethod.invoke(verifier, coseSignature) as ByteArray

        // DER signature should start with SEQUENCE tag (0x30)
        assertEquals(0x30.toByte(), derSignature[0])
    }

    @Test
    fun `convertCoseSignatureToDer returns input if not 96 bytes`() {
        val convertMethod = getPrivateMethod("convertCoseSignatureToDer", ByteArray::class.java)

        // Non-96 byte input (might already be DER encoded)
        val input = byteArrayOf(0x30, 0x44, 0x02, 0x20)

        val result = convertMethod.invoke(verifier, input) as ByteArray

        assertTrue(input.contentEquals(result))
    }

    @Test
    fun `convertCoseSignatureToDer handles signature with leading zeros in r`() {
        val convertMethod = getPrivateMethod("convertCoseSignatureToDer", ByteArray::class.java)

        // Create signature with leading zeros in r
        val coseSignature = ByteArray(96)
        coseSignature[0] = 0x00
        coseSignature[1] = 0x00
        coseSignature[2] = 0x7f.toByte()
        // Fill rest with non-zero
        for (i in 3 until 96) {
            coseSignature[i] = 0x42
        }

        val derSignature = convertMethod.invoke(verifier, coseSignature) as ByteArray

        assertEquals(0x30.toByte(), derSignature[0])
    }

    @Test
    fun `convertCoseSignatureToDer adds padding for high bit`() {
        val convertMethod = getPrivateMethod("convertCoseSignatureToDer", ByteArray::class.java)

        // Create signature where first byte of r has high bit set
        val coseSignature = ByteArray(96)
        coseSignature[0] = 0x80.toByte() // High bit set
        for (i in 1 until 96) {
            coseSignature[i] = 0x42
        }

        val derSignature = convertMethod.invoke(verifier, coseSignature) as ByteArray

        // Should have 0x30 SEQUENCE tag
        assertEquals(0x30.toByte(), derSignature[0])
        // Should contain 0x02 INTEGER tags
        assertTrue(derSignature.any { it == 0x02.toByte() })
    }

    // MARK: - Invalid Input Tests

    @Test
    fun `verify throws exception for invalid base64`() {
        val pcrs = ExpectedPcrs("aa", "bb", "cc")

        var threw = false
        try {
            verifier.verify("not-valid-base64!!!", pcrs)
        } catch (e: Exception) {
            threw = true
            // Can be AttestationVerificationException or parsing exception
        }
        assertTrue("Expected exception for invalid base64", threw)
    }

    @Test
    fun `verify throws exception for empty string`() {
        val pcrs = ExpectedPcrs("aa", "bb", "cc")

        var threw = false
        try {
            verifier.verify("", pcrs)
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("Expected exception for empty string", threw)
    }

    @Test
    fun `verify throws exception for non-CBOR data`() {
        val pcrs = ExpectedPcrs("aa", "bb", "cc")
        val invalidData = Base64.encodeToString("not cbor data".toByteArray(), Base64.NO_WRAP)

        var threw = false
        try {
            verifier.verify(invalidData, pcrs)
        } catch (e: Exception) {
            threw = true
            // Can be AttestationVerificationException or Jackson parsing exception
        }
        assertTrue("Expected exception for non-CBOR data", threw)
    }

    @Test
    fun `verify throws exception for truncated data`() {
        val pcrs = ExpectedPcrs("aa", "bb", "cc")
        val truncatedData = Base64.encodeToString(byteArrayOf(0x84.toByte()), Base64.NO_WRAP)

        var threw = false
        try {
            verifier.verify(truncatedData, pcrs)
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("Expected exception for truncated data", threw)
    }

    // MARK: - ByteArray Extension Tests

    @Test
    fun `toHexString converts bytes to hex`() {
        // Access the extension function via reflection
        val bytes = byteArrayOf(0x00, 0x0f, 0xf0.toByte(), 0xff.toByte())

        // Use reflection to call the private extension function
        val toHexStringMethod = NitroAttestationVerifier::class.java.getDeclaredMethod(
            "toHexString",
            ByteArray::class.java
        )
        toHexStringMethod.isAccessible = true

        val result = toHexStringMethod.invoke(verifier, bytes) as String

        assertEquals("000ff0ff", result)
    }

    // MARK: - Edge Cases

    @Test
    fun `ExpectedPcrs with SHA-384 length hex strings`() {
        // SHA-384 produces 48 bytes = 96 hex characters
        val sha384Hex = "a".repeat(96)

        val pcrs = ExpectedPcrs(
            pcr0 = sha384Hex,
            pcr1 = sha384Hex,
            pcr2 = sha384Hex
        )

        assertEquals(96, pcrs.pcr0.length)
    }

    @Test
    fun `VerifiedAttestation with empty PCRs map`() {
        val attestation = VerifiedAttestation(
            enclavePublicKey = byteArrayOf(1),
            moduleId = "module",
            timestamp = 0L,
            pcrs = emptyMap(),
            userData = null
        )

        assertTrue(attestation.pcrs.isEmpty())
    }

    @Test
    fun `VerifiedAttestation with multiple PCRs`() {
        val pcrs = mapOf(
            0 to byteArrayOf(1),
            1 to byteArrayOf(2),
            2 to byteArrayOf(3),
            3 to byteArrayOf(4)
        )

        val attestation = VerifiedAttestation(
            enclavePublicKey = byteArrayOf(1),
            moduleId = "module",
            timestamp = 0L,
            pcrs = pcrs,
            userData = null
        )

        assertEquals(4, attestation.pcrs.size)
    }

    // MARK: - Helper Methods

    private fun getPrivateMethod(name: String, vararg parameterTypes: Class<*>): Method {
        val method = NitroAttestationVerifier::class.java.getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        return method
    }
}
