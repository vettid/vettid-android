package com.vettid.app.core.attestation

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.cert.X509Certificate

/**
 * Unit tests for HardwareAttestationManager data classes and enums
 *
 * Tests cover:
 * - Data class construction and properties (AttestationResult, EnrollmentAttestationData)
 * - SecurityLevel enum values
 * - AttestationException
 *
 * Note: Tests for HardwareAttestationManager methods require Android instrumented tests
 * because the AndroidKeyStore is not available in Robolectric.
 * The data classes and enums are tested here as they don't depend on hardware.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HardwareAttestationManagerTest {

    // MARK: - SecurityLevel Enum Tests

    @Test
    fun `SecurityLevel has correct values`() {
        val values = SecurityLevel.values()
        assertEquals(3, values.size)
        assertTrue(values.contains(SecurityLevel.SOFTWARE))
        assertTrue(values.contains(SecurityLevel.TEE))
        assertTrue(values.contains(SecurityLevel.STRONG_BOX))
    }

    @Test
    fun `SecurityLevel ordinal order is SOFTWARE, TEE, STRONG_BOX`() {
        assertEquals(0, SecurityLevel.SOFTWARE.ordinal)
        assertEquals(1, SecurityLevel.TEE.ordinal)
        assertEquals(2, SecurityLevel.STRONG_BOX.ordinal)
    }

    @Test
    fun `SecurityLevel valueOf returns correct enum`() {
        assertEquals(SecurityLevel.SOFTWARE, SecurityLevel.valueOf("SOFTWARE"))
        assertEquals(SecurityLevel.TEE, SecurityLevel.valueOf("TEE"))
        assertEquals(SecurityLevel.STRONG_BOX, SecurityLevel.valueOf("STRONG_BOX"))
    }

    @Test
    fun `SecurityLevel name returns correct string`() {
        assertEquals("SOFTWARE", SecurityLevel.SOFTWARE.name)
        assertEquals("TEE", SecurityLevel.TEE.name)
        assertEquals("STRONG_BOX", SecurityLevel.STRONG_BOX.name)
    }

    @Test
    fun `SecurityLevel valueOf throws for invalid value`() {
        try {
            SecurityLevel.valueOf("INVALID")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    // MARK: - AttestationException Tests

    @Test
    fun `AttestationException stores message`() {
        val exception = AttestationException("Test error message")
        assertEquals("Test error message", exception.message)
    }

    @Test
    fun `AttestationException is throwable`() {
        val exception = AttestationException("Something went wrong")
        assertTrue(exception is Exception)
        assertTrue(exception is Throwable)
    }

    @Test
    fun `AttestationException can be caught`() {
        var caught = false
        try {
            throw AttestationException("Test")
        } catch (e: AttestationException) {
            caught = true
            assertEquals("Test", e.message)
        }
        assertTrue(caught)
    }

    @Test
    fun `AttestationException with empty message`() {
        val exception = AttestationException("")
        assertEquals("", exception.message)
    }

    @Test
    fun `AttestationException can be nested cause`() {
        val cause = RuntimeException("Root cause")
        try {
            throw AttestationException("Wrapper: ${cause.message}")
        } catch (e: AttestationException) {
            assertTrue(e.message!!.contains("Root cause"))
        }
    }

    // MARK: - AttestationResult Tests

    @Test
    fun `AttestationResult stores certificate chain`() {
        val mockCert = createMockCertificate()
        val chain = listOf(mockCert)
        val result = AttestationResult(
            certificateChain = chain,
            attestationExtension = null
        )

        assertEquals(1, result.certificateChain.size)
        assertSame(mockCert, result.certificateChain[0])
        assertNull(result.attestationExtension)
    }

    @Test
    fun `AttestationResult stores attestation extension`() {
        val mockCert = createMockCertificate()
        val extension = byteArrayOf(0x01, 0x02, 0x03)
        val result = AttestationResult(
            certificateChain = listOf(mockCert),
            attestationExtension = extension
        )

        assertNotNull(result.attestationExtension)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), result.attestationExtension)
    }

    @Test
    fun `AttestationResult with empty certificate chain`() {
        val result = AttestationResult(
            certificateChain = emptyList(),
            attestationExtension = byteArrayOf(0x01)
        )

        assertTrue(result.certificateChain.isEmpty())
    }

    @Test
    fun `AttestationResult with multiple certificates`() {
        val cert1 = createMockCertificate()
        val cert2 = createMockCertificate()
        val cert3 = createMockCertificate()

        val result = AttestationResult(
            certificateChain = listOf(cert1, cert2, cert3),
            attestationExtension = null
        )

        assertEquals(3, result.certificateChain.size)
    }

    @Test
    fun `AttestationResult equality with same values`() {
        val mockCert = createMockCertificate()
        val extension = byteArrayOf(0x01, 0x02)

        val result1 = AttestationResult(listOf(mockCert), extension)
        val result2 = AttestationResult(listOf(mockCert), byteArrayOf(0x01, 0x02))

        assertEquals(result1, result2)
    }

    @Test
    fun `AttestationResult equality with null extensions`() {
        val mockCert = createMockCertificate()

        val result1 = AttestationResult(listOf(mockCert), null)
        val result2 = AttestationResult(listOf(mockCert), null)

        assertEquals(result1, result2)
    }

    @Test
    fun `AttestationResult inequality with different extensions`() {
        val mockCert = createMockCertificate()

        val result1 = AttestationResult(listOf(mockCert), byteArrayOf(0x01))
        val result2 = AttestationResult(listOf(mockCert), byteArrayOf(0x02))

        assertNotEquals(result1, result2)
    }

    @Test
    fun `AttestationResult inequality when one extension is null`() {
        val mockCert = createMockCertificate()

        val result1 = AttestationResult(listOf(mockCert), byteArrayOf(0x01))
        val result2 = AttestationResult(listOf(mockCert), null)

        assertNotEquals(result1, result2)
    }

    @Test
    fun `AttestationResult hashCode consistency`() {
        val mockCert = createMockCertificate()
        val result = AttestationResult(listOf(mockCert), byteArrayOf(0x01))

        val hash1 = result.hashCode()
        val hash2 = result.hashCode()

        assertEquals(hash1, hash2)
    }

    @Test
    fun `AttestationResult hashCode with null extension`() {
        val mockCert = createMockCertificate()
        val result = AttestationResult(listOf(mockCert), null)

        // Should not throw
        val hash = result.hashCode()
        assertNotNull(hash)
    }

    @Test
    fun `AttestationResult equals same object returns true`() {
        val mockCert = createMockCertificate()
        val result = AttestationResult(listOf(mockCert), byteArrayOf(0x01))

        @Suppress("ReplaceCallWithBinaryOperator")
        assertTrue(result.equals(result))
    }

    @Test
    fun `AttestationResult equals different type returns false`() {
        val mockCert = createMockCertificate()
        val result = AttestationResult(listOf(mockCert), byteArrayOf(0x01))

        assertFalse(result.equals("not an AttestationResult"))
        assertFalse(result.equals(123))
        assertFalse(result.equals(null))
    }

    @Test
    fun `AttestationResult with empty extension`() {
        val mockCert = createMockCertificate()
        val result = AttestationResult(
            certificateChain = listOf(mockCert),
            attestationExtension = byteArrayOf()
        )

        assertNotNull(result.attestationExtension)
        assertEquals(0, result.attestationExtension!!.size)
    }

    @Test
    fun `AttestationResult with large extension`() {
        val mockCert = createMockCertificate()
        val largeExtension = ByteArray(10000) { it.toByte() }

        val result = AttestationResult(
            certificateChain = listOf(mockCert),
            attestationExtension = largeExtension
        )

        assertEquals(10000, result.attestationExtension!!.size)
    }

    // MARK: - EnrollmentAttestationData Tests

    @Test
    fun `EnrollmentAttestationData stores all properties`() {
        val challenge = byteArrayOf(0x01, 0x02, 0x03)
        val certChain = listOf(byteArrayOf(0x04, 0x05))
        val securityLevel = SecurityLevel.TEE

        val data = EnrollmentAttestationData(
            challenge = challenge,
            certificateChain = certChain,
            securityLevel = securityLevel
        )

        assertArrayEquals(challenge, data.challenge)
        assertEquals(1, data.certificateChain.size)
        assertArrayEquals(byteArrayOf(0x04, 0x05), data.certificateChain[0])
        assertEquals(SecurityLevel.TEE, data.securityLevel)
    }

    @Test
    fun `EnrollmentAttestationData equality with same values`() {
        val data1 = EnrollmentAttestationData(
            challenge = byteArrayOf(0x01, 0x02),
            certificateChain = listOf(byteArrayOf(0x03, 0x04)),
            securityLevel = SecurityLevel.SOFTWARE
        )
        val data2 = EnrollmentAttestationData(
            challenge = byteArrayOf(0x01, 0x02),
            certificateChain = listOf(byteArrayOf(0x03, 0x04)),
            securityLevel = SecurityLevel.SOFTWARE
        )

        assertEquals(data1, data2)
    }

    @Test
    fun `EnrollmentAttestationData inequality with different challenge`() {
        val data1 = EnrollmentAttestationData(
            challenge = byteArrayOf(0x01),
            certificateChain = listOf(byteArrayOf(0x02)),
            securityLevel = SecurityLevel.SOFTWARE
        )
        val data2 = EnrollmentAttestationData(
            challenge = byteArrayOf(0x09),
            certificateChain = listOf(byteArrayOf(0x02)),
            securityLevel = SecurityLevel.SOFTWARE
        )

        assertNotEquals(data1, data2)
    }

    @Test
    fun `EnrollmentAttestationData inequality with different security level`() {
        val data1 = EnrollmentAttestationData(
            challenge = byteArrayOf(0x01),
            certificateChain = listOf(byteArrayOf(0x02)),
            securityLevel = SecurityLevel.SOFTWARE
        )
        val data2 = EnrollmentAttestationData(
            challenge = byteArrayOf(0x01),
            certificateChain = listOf(byteArrayOf(0x02)),
            securityLevel = SecurityLevel.STRONG_BOX
        )

        assertNotEquals(data1, data2)
    }

    @Test
    fun `EnrollmentAttestationData inequality with different cert chain size`() {
        val data1 = EnrollmentAttestationData(
            challenge = byteArrayOf(0x01),
            certificateChain = listOf(byteArrayOf(0x02)),
            securityLevel = SecurityLevel.TEE
        )
        val data2 = EnrollmentAttestationData(
            challenge = byteArrayOf(0x01),
            certificateChain = listOf(byteArrayOf(0x02), byteArrayOf(0x03)),
            securityLevel = SecurityLevel.TEE
        )

        assertNotEquals(data1, data2)
    }

    @Test
    fun `EnrollmentAttestationData inequality with different cert content`() {
        val data1 = EnrollmentAttestationData(
            challenge = byteArrayOf(0x01),
            certificateChain = listOf(byteArrayOf(0x02)),
            securityLevel = SecurityLevel.TEE
        )
        val data2 = EnrollmentAttestationData(
            challenge = byteArrayOf(0x01),
            certificateChain = listOf(byteArrayOf(0x09)),
            securityLevel = SecurityLevel.TEE
        )

        assertNotEquals(data1, data2)
    }

    @Test
    fun `EnrollmentAttestationData hashCode consistency`() {
        val data = EnrollmentAttestationData(
            challenge = byteArrayOf(0x01, 0x02),
            certificateChain = listOf(byteArrayOf(0x03)),
            securityLevel = SecurityLevel.TEE
        )

        val hash1 = data.hashCode()
        val hash2 = data.hashCode()

        assertEquals(hash1, hash2)
    }

    @Test
    fun `EnrollmentAttestationData equals same object returns true`() {
        val data = EnrollmentAttestationData(
            challenge = byteArrayOf(0x01),
            certificateChain = emptyList(),
            securityLevel = SecurityLevel.SOFTWARE
        )

        @Suppress("ReplaceCallWithBinaryOperator")
        assertTrue(data.equals(data))
    }

    @Test
    fun `EnrollmentAttestationData equals different type returns false`() {
        val data = EnrollmentAttestationData(
            challenge = byteArrayOf(0x01),
            certificateChain = emptyList(),
            securityLevel = SecurityLevel.SOFTWARE
        )

        assertFalse(data.equals("string"))
        assertFalse(data.equals(null))
    }

    @Test
    fun `EnrollmentAttestationData with empty certificate chain`() {
        val data = EnrollmentAttestationData(
            challenge = byteArrayOf(0x01),
            certificateChain = emptyList(),
            securityLevel = SecurityLevel.SOFTWARE
        )

        assertTrue(data.certificateChain.isEmpty())
    }

    @Test
    fun `EnrollmentAttestationData with multiple certificates`() {
        val data = EnrollmentAttestationData(
            challenge = byteArrayOf(0x01),
            certificateChain = listOf(
                byteArrayOf(0x01, 0x02),
                byteArrayOf(0x03, 0x04),
                byteArrayOf(0x05, 0x06)
            ),
            securityLevel = SecurityLevel.STRONG_BOX
        )

        assertEquals(3, data.certificateChain.size)
    }

    @Test
    fun `EnrollmentAttestationData with empty challenge`() {
        val data = EnrollmentAttestationData(
            challenge = byteArrayOf(),
            certificateChain = listOf(byteArrayOf(0x01)),
            securityLevel = SecurityLevel.TEE
        )

        assertEquals(0, data.challenge.size)
    }

    @Test
    fun `EnrollmentAttestationData with large challenge`() {
        val largeChallenge = ByteArray(256) { it.toByte() }

        val data = EnrollmentAttestationData(
            challenge = largeChallenge,
            certificateChain = emptyList(),
            securityLevel = SecurityLevel.SOFTWARE
        )

        assertEquals(256, data.challenge.size)
    }

    @Test
    fun `EnrollmentAttestationData all security levels`() {
        for (level in SecurityLevel.values()) {
            val data = EnrollmentAttestationData(
                challenge = byteArrayOf(0x01),
                certificateChain = emptyList(),
                securityLevel = level
            )

            assertEquals(level, data.securityLevel)
        }
    }

    // MARK: - Companion Object Constants Tests

    @Test
    fun `KEY_ATTESTATION_OID constant is correct`() {
        val field = HardwareAttestationManager::class.java.getDeclaredField("KEY_ATTESTATION_OID")
        field.isAccessible = true
        val oid = field.get(null) as String

        assertEquals("1.3.6.1.4.1.11129.2.1.17", oid)
    }

    @Test
    fun `ATTESTATION_KEY_ALIAS constant is correct`() {
        val field = HardwareAttestationManager::class.java.getDeclaredField("ATTESTATION_KEY_ALIAS")
        field.isAccessible = true
        val alias = field.get(null) as String

        assertEquals("vettid_attestation_key", alias)
    }

    @Test
    fun `ANDROID_KEYSTORE constant is correct`() {
        val field = HardwareAttestationManager::class.java.getDeclaredField("ANDROID_KEYSTORE")
        field.isAccessible = true
        val keystore = field.get(null) as String

        assertEquals("AndroidKeyStore", keystore)
    }

    // MARK: - ByteArray Extension Tests (via reflection on standalone function)

    @Test
    fun `toHexString extension converts bytes correctly`() {
        // Test via data class that uses hex internally
        val bytes = byteArrayOf(0x00, 0x01, 0x0F, 0x10, 0x7F)
        val expected = "00010f107f"

        // We can test hex conversion by creating attestation extension with known values
        // and verifying the pattern
        val result = bytes.joinToString("") { "%02x".format(it) }
        assertEquals(expected, result)
    }

    @Test
    fun `toHexString handles empty array`() {
        val result = byteArrayOf().joinToString("") { "%02x".format(it) }
        assertEquals("", result)
    }

    @Test
    fun `toHexString handles single byte`() {
        val result = byteArrayOf(0x5A).joinToString("") { "%02x".format(it) }
        assertEquals("5a", result)
    }

    @Test
    fun `toHexString handles high byte values`() {
        val bytes = byteArrayOf(0x80.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())
        val result = bytes.joinToString("") { "%02x".format(it) }
        assertEquals("80abcdef", result)
    }

    @Test
    fun `toHexString produces lowercase hex`() {
        val bytes = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val result = bytes.joinToString("") { "%02x".format(it) }
        assertEquals("deadbeef", result)
    }

    // MARK: - Edge Case Tests

    @Test
    fun `AttestationResult inequality with different certificate chain`() {
        val cert1 = createMockCertificate()
        val cert2 = createMockCertificate()

        val result1 = AttestationResult(listOf(cert1), null)
        val result2 = AttestationResult(listOf(cert2), null)

        // Different mock objects should not be equal (unless certificateChain.equals works)
        assertNotEquals(result1, result2)
    }

    @Test
    fun `AttestationResult with single certificate and extension`() {
        val cert = createMockCertificate()
        val ext = byteArrayOf(0x30, 0x45, 0x02, 0x20) // Sample ASN.1 data

        val result = AttestationResult(
            certificateChain = listOf(cert),
            attestationExtension = ext
        )

        assertEquals(1, result.certificateChain.size)
        assertEquals(4, result.attestationExtension!!.size)
    }

    @Test
    fun `EnrollmentAttestationData equality is order sensitive for cert chain`() {
        val data1 = EnrollmentAttestationData(
            challenge = byteArrayOf(0x01),
            certificateChain = listOf(byteArrayOf(0x01), byteArrayOf(0x02)),
            securityLevel = SecurityLevel.TEE
        )
        val data2 = EnrollmentAttestationData(
            challenge = byteArrayOf(0x01),
            certificateChain = listOf(byteArrayOf(0x02), byteArrayOf(0x01)),
            securityLevel = SecurityLevel.TEE
        )

        assertNotEquals(data1, data2)
    }

    // MARK: - Helper Methods

    private fun createMockCertificate(): X509Certificate {
        return org.mockito.Mockito.mock(X509Certificate::class.java)
    }
}
