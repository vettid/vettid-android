package com.vettid.app.core.attestation

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import com.fasterxml.jackson.dataformat.cbor.CBORParser
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.security.Security
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verifies AWS Nitro Enclave attestation documents.
 *
 * AWS Nitro Enclaves provide hardware-backed attestation that cryptographically proves:
 * - Code identity (PCR values are hashes of enclave code)
 * - Isolation (running in genuine Nitro Enclave)
 * - Freshness (timestamp and nonce validation)
 *
 * Attestation Document Structure (COSE_Sign1):
 * - Protected header (algorithm)
 * - Unprotected header (empty)
 * - Payload (CBOR-encoded attestation data)
 * - Signature (signed by AWS Nitro PKI)
 *
 * Payload contains:
 * - module_id: Enclave image ID
 * - digest: Hash algorithm
 * - timestamp: Unix timestamp (ms)
 * - pcrs: Map of PCR index to SHA-384 hash
 * - certificate: DER-encoded signing certificate
 * - cabundle: Certificate chain to AWS root
 * - public_key: Enclave's ephemeral public key
 * - user_data: Optional user-provided data
 * - nonce: Optional nonce for freshness
 */
@Singleton
class NitroAttestationVerifier @Inject constructor() {

    companion object {
        private const val TAG = "NitroAttestation"

        // COSE_Sign1 structure constants
        private const val COSE_SIGN1_TAG = 18L

        // Maximum allowed attestation age (5 minutes)
        private const val MAX_ATTESTATION_AGE_MS = 5 * 60 * 1000L

        // AWS Nitro Enclave root CA subject
        private const val AWS_NITRO_ROOT_CA_CN = "aws.nitro-enclaves"

        init {
            // Register Bouncy Castle provider for certificate verification
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    private val cborFactory = CBORFactory()
    private val certificateFactory = CertificateFactory.getInstance("X.509")

    /**
     * Verify enclave attestation from enrollment start response.
     *
     * This is the primary entry point for enrollment flow attestation verification.
     * It extracts the expected PCRs from the response and verifies the attestation.
     *
     * @param attestation EnclaveAttestation from enrollment start response
     * @return VerifiedAttestation containing enclave public key and metadata
     * @throws AttestationVerificationException if verification fails
     */
    fun verify(attestation: com.vettid.app.core.network.EnclaveAttestation): VerifiedAttestation {
        if (attestation.expectedPcrs.isEmpty()) {
            throw AttestationVerificationException("No expected PCR values provided")
        }

        val expected = attestation.expectedPcrs.first()
        val expectedPcrs = ExpectedPcrs(
            pcr0 = expected.pcr0,
            pcr1 = expected.pcr1,
            pcr2 = expected.pcr2,
            pcr3 = expected.pcr3
        )

        val expectedNonce = attestation.nonce?.let {
            try {
                Base64.decode(it, Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode nonce, skipping nonce verification", e)
                null
            }
        }

        return verify(
            attestationDocBase64 = attestation.attestationDocument,
            expectedPcrs = expectedPcrs,
            expectedNonce = expectedNonce
        )
    }

    /**
     * Verify an attestation document and extract verified data.
     *
     * @param attestationDocBase64 Base64-encoded attestation document from enclave
     * @param expectedPcrs Expected PCR values to verify against (PCR0, PCR1, PCR2)
     * @param expectedNonce Optional nonce that should match (for replay protection)
     * @return VerifiedAttestation containing enclave public key and metadata
     * @throws AttestationVerificationException if verification fails
     */
    fun verify(
        attestationDocBase64: String,
        expectedPcrs: ExpectedPcrs,
        expectedNonce: ByteArray? = null
    ): VerifiedAttestation {
        Log.d(TAG, "Verifying Nitro attestation document")

        // Decode attestation document
        val attestationDoc = try {
            Base64.decode(attestationDocBase64, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw AttestationVerificationException("Invalid base64 encoding", e)
        }

        // Parse COSE_Sign1 structure
        val coseSign1 = parseCoseSign1(attestationDoc)

        // Parse attestation payload
        val attestation = parseAttestationPayload(coseSign1.payload)

        // Verify certificate chain
        verifyCertificateChain(attestation.certificate, attestation.cabundle)

        // Verify COSE signature
        verifyCoseSignature(coseSign1, attestation.certificate)

        // Verify timestamp (freshness)
        verifyTimestamp(attestation.timestamp)

        // Verify PCR values
        verifyPcrs(attestation.pcrs, expectedPcrs)

        // Verify nonce if provided
        if (expectedNonce != null) {
            verifyNonce(attestation.nonce, expectedNonce)
        }

        Log.i(TAG, "Attestation verified successfully")

        return VerifiedAttestation(
            enclavePublicKey = attestation.publicKey,
            moduleId = attestation.moduleId,
            timestamp = attestation.timestamp,
            pcrs = attestation.pcrs,
            userData = attestation.userData
        )
    }

    /**
     * Parse COSE_Sign1 structure from CBOR.
     *
     * COSE_Sign1 = [
     *   protected: bstr,     # Protected header (algorithm)
     *   unprotected: map,    # Unprotected header
     *   payload: bstr,       # CBOR-encoded attestation
     *   signature: bstr      # ECDSA signature
     * ]
     *
     * Note: COSE_Sign1 documents are tagged with CBOR tag 18, but Jackson CBOR
     * parser doesn't expose tags directly. We parse the array structure directly.
     */
    private fun parseCoseSign1(data: ByteArray): CoseSign1 {
        val parser = cborFactory.createParser(data)

        // Move to first token
        var token = parser.nextToken()

        // Skip CBOR tag if present (Jackson handles this transparently for some cases)
        // The COSE_Sign1 structure starts with an array of 4 elements
        // If we don't see an array, try to advance past any tag
        if (token != com.fasterxml.jackson.core.JsonToken.START_ARRAY) {
            // Try next token - might be after a tag
            token = parser.nextToken()
            if (token != com.fasterxml.jackson.core.JsonToken.START_ARRAY) {
                throw AttestationVerificationException("Expected COSE_Sign1 array, got: $token")
            }
        }

        // Protected header (bstr)
        parser.nextToken()
        val protectedHeader = parser.binaryValue

        // Unprotected header (map) - skip
        parser.nextToken()
        parser.skipChildren()

        // Payload (bstr)
        parser.nextToken()
        val payload = parser.binaryValue

        // Signature (bstr)
        parser.nextToken()
        val signature = parser.binaryValue

        parser.close()

        return CoseSign1(
            protectedHeader = protectedHeader,
            payload = payload,
            signature = signature
        )
    }

    /**
     * Parse attestation payload from CBOR.
     *
     * Attestation = {
     *   "module_id": tstr,
     *   "digest": tstr,
     *   "timestamp": uint,
     *   "pcrs": { uint => bstr },
     *   "certificate": bstr,
     *   "cabundle": [+ bstr],
     *   ? "public_key": bstr,
     *   ? "user_data": bstr,
     *   ? "nonce": bstr
     * }
     */
    private fun parseAttestationPayload(payload: ByteArray): AttestationPayload {
        val parser = cborFactory.createParser(payload)

        var moduleId: String? = null
        var digest: String? = null
        var timestamp: Long? = null
        var pcrs: Map<Int, ByteArray> = emptyMap()
        var certificate: ByteArray? = null
        var cabundle: List<ByteArray> = emptyList()
        var publicKey: ByteArray? = null
        var userData: ByteArray? = null
        var nonce: ByteArray? = null

        // Parse map
        if (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
            throw AttestationVerificationException("Expected attestation map")
        }

        while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
            val fieldName = parser.currentName
            parser.nextToken()

            when (fieldName) {
                "module_id" -> moduleId = parser.text
                "digest" -> digest = parser.text
                "timestamp" -> timestamp = parser.longValue
                "pcrs" -> pcrs = parsePcrs(parser)
                "certificate" -> certificate = parser.binaryValue
                "cabundle" -> cabundle = parseCabundle(parser)
                "public_key" -> publicKey = parser.binaryValue
                "user_data" -> userData = parser.binaryValue
                "nonce" -> nonce = parser.binaryValue
            }
        }

        parser.close()

        return AttestationPayload(
            moduleId = moduleId ?: throw AttestationVerificationException("Missing module_id"),
            digest = digest ?: "SHA384",
            timestamp = timestamp ?: throw AttestationVerificationException("Missing timestamp"),
            pcrs = pcrs,
            certificate = certificate ?: throw AttestationVerificationException("Missing certificate"),
            cabundle = cabundle,
            publicKey = publicKey ?: throw AttestationVerificationException("Missing public_key"),
            userData = userData,
            nonce = nonce
        )
    }

    /**
     * Parse PCRs map from CBOR.
     */
    private fun parsePcrs(parser: CBORParser): Map<Int, ByteArray> {
        val pcrs = mutableMapOf<Int, ByteArray>()

        if (parser.currentToken != com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
            throw AttestationVerificationException("Expected PCRs map")
        }

        while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
            val pcrIndex = parser.currentName.toIntOrNull()
                ?: throw AttestationVerificationException("Invalid PCR index: ${parser.currentName}")
            parser.nextToken()
            val pcrValue = parser.binaryValue
            pcrs[pcrIndex] = pcrValue
        }

        return pcrs
    }

    /**
     * Parse CA bundle array from CBOR.
     */
    private fun parseCabundle(parser: CBORParser): List<ByteArray> {
        val certs = mutableListOf<ByteArray>()

        if (parser.currentToken != com.fasterxml.jackson.core.JsonToken.START_ARRAY) {
            throw AttestationVerificationException("Expected cabundle array")
        }

        while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
            certs.add(parser.binaryValue)
        }

        return certs
    }

    /**
     * Verify the certificate chain from signing cert to AWS Nitro root.
     */
    private fun verifyCertificateChain(signingCertDer: ByteArray, cabundle: List<ByteArray>) {
        Log.d(TAG, "Verifying certificate chain (${cabundle.size + 1} certificates)")

        // Parse all certificates
        val signingCert = parseCertificate(signingCertDer)
        val intermediateCerts = cabundle.map { parseCertificate(it) }

        // The root CA should be the last in cabundle
        if (intermediateCerts.isEmpty()) {
            throw AttestationVerificationException("CA bundle is empty")
        }

        val rootCert = intermediateCerts.last()

        // Verify root CA is AWS Nitro Enclaves
        val rootSubject = X500Name(rootCert.subjectX500Principal.name)
        val expectedRoot = X500Name("CN=$AWS_NITRO_ROOT_CA_CN")
        if (rootSubject != expectedRoot) {
            throw AttestationVerificationException(
                "Root CA is not AWS Nitro: expected $expectedRoot, got $rootSubject"
            )
        }

        // Verify root is self-signed
        try {
            rootCert.verify(rootCert.publicKey)
        } catch (e: Exception) {
            throw AttestationVerificationException("Root CA is not self-signed", e)
        }

        // Build and verify certificate path
        val trustAnchor = TrustAnchor(rootCert, null)
        val trustAnchors = setOf(trustAnchor)

        val certPath = certificateFactory.generateCertPath(
            listOf(signingCert) + intermediateCerts.dropLast(1)
        )

        val params = PKIXParameters(trustAnchors).apply {
            isRevocationEnabled = false // No CRL/OCSP for Nitro certs
        }

        try {
            val validator = CertPathValidator.getInstance("PKIX")
            validator.validate(certPath, params)
            Log.d(TAG, "Certificate chain verified successfully")
        } catch (e: Exception) {
            throw AttestationVerificationException("Certificate chain validation failed", e)
        }
    }

    /**
     * Verify the COSE_Sign1 signature.
     */
    private fun verifyCoseSignature(coseSign1: CoseSign1, signingCertDer: ByteArray) {
        Log.d(TAG, "Verifying COSE signature")

        val signingCert = parseCertificate(signingCertDer)
        val publicKey = signingCert.publicKey

        // Build Sig_structure for verification
        // Sig_structure = ["Signature1", protected, external_aad, payload]
        val sigStructure = buildSigStructure(coseSign1)

        // Verify ECDSA signature
        val algorithm = when {
            publicKey.algorithm == "EC" -> "SHA384withECDSA"
            else -> throw AttestationVerificationException("Unsupported key algorithm: ${publicKey.algorithm}")
        }

        try {
            val signature = java.security.Signature.getInstance(algorithm, "BC")
            signature.initVerify(publicKey)
            signature.update(sigStructure)

            // Convert COSE signature (raw r||s) to DER format if needed
            val derSignature = convertCoseSignatureToDer(coseSign1.signature)

            if (!signature.verify(derSignature)) {
                throw AttestationVerificationException("COSE signature verification failed")
            }

            Log.d(TAG, "COSE signature verified successfully")
        } catch (e: AttestationVerificationException) {
            throw e
        } catch (e: Exception) {
            throw AttestationVerificationException("Signature verification error", e)
        }
    }

    /**
     * Build Sig_structure for COSE_Sign1 verification.
     */
    private fun buildSigStructure(coseSign1: CoseSign1): ByteArray {
        // Sig_structure = ["Signature1", protected, external_aad, payload]
        val output = java.io.ByteArrayOutputStream()
        val generator = cborFactory.createGenerator(output)

        generator.writeStartArray(4)
        generator.writeString("Signature1")
        generator.writeBinary(coseSign1.protectedHeader)
        generator.writeBinary(ByteArray(0)) // external_aad
        generator.writeBinary(coseSign1.payload)
        generator.writeEndArray()
        generator.close()

        return output.toByteArray()
    }

    /**
     * Convert COSE signature (raw r||s) to DER format.
     */
    private fun convertCoseSignatureToDer(coseSignature: ByteArray): ByteArray {
        // COSE uses raw concatenation of r and s (each 48 bytes for P-384)
        if (coseSignature.size != 96) {
            // Might already be DER encoded
            return coseSignature
        }

        val r = coseSignature.copyOfRange(0, 48)
        val s = coseSignature.copyOfRange(48, 96)

        // Build DER sequence
        val derBuilder = java.io.ByteArrayOutputStream()

        fun writeInteger(value: ByteArray) {
            // Remove leading zeros but keep one if needed for positive sign
            var startIndex = 0
            while (startIndex < value.size - 1 && value[startIndex] == 0.toByte()) {
                startIndex++
            }
            val trimmed = value.copyOfRange(startIndex, value.size)

            // Add padding byte if high bit is set (would be interpreted as negative)
            val needsPadding = (trimmed[0].toInt() and 0x80) != 0
            val length = trimmed.size + (if (needsPadding) 1 else 0)

            derBuilder.write(0x02) // INTEGER tag
            derBuilder.write(length)
            if (needsPadding) {
                derBuilder.write(0x00)
            }
            derBuilder.write(trimmed)
        }

        @Suppress("UNUSED_VARIABLE")
        val innerContent = java.io.ByteArrayOutputStream()

        // Write r
        writeInteger(r)
        // Write s
        writeInteger(s)

        val innerBytes = derBuilder.toByteArray()

        val result = java.io.ByteArrayOutputStream()
        result.write(0x30) // SEQUENCE tag
        if (innerBytes.size < 128) {
            result.write(innerBytes.size)
        } else {
            result.write(0x81)
            result.write(innerBytes.size)
        }
        result.write(innerBytes)

        return result.toByteArray()
    }

    /**
     * Verify attestation timestamp is recent.
     */
    private fun verifyTimestamp(timestamp: Long) {
        val now = System.currentTimeMillis()
        val age = now - timestamp

        if (age < 0) {
            throw AttestationVerificationException("Attestation timestamp is in the future")
        }

        if (age > MAX_ATTESTATION_AGE_MS) {
            throw AttestationVerificationException(
                "Attestation is too old: ${age / 1000} seconds (max: ${MAX_ATTESTATION_AGE_MS / 1000})"
            )
        }

        Log.d(TAG, "Attestation timestamp verified (age: ${age / 1000}s)")
    }

    /**
     * Verify PCR values match expected values.
     */
    private fun verifyPcrs(actualPcrs: Map<Int, ByteArray>, expected: ExpectedPcrs) {
        Log.d(TAG, "Verifying PCR values")

        // Verify PCR0 (enclave image hash)
        verifyPcr(actualPcrs, 0, expected.pcr0, "PCR0 (enclave image)")

        // Verify PCR1 (kernel hash)
        verifyPcr(actualPcrs, 1, expected.pcr1, "PCR1 (kernel)")

        // Verify PCR2 (application hash)
        verifyPcr(actualPcrs, 2, expected.pcr2, "PCR2 (application)")

        // Optionally verify PCR3 (IAM role) if provided
        if (expected.pcr3 != null) {
            verifyPcr(actualPcrs, 3, expected.pcr3, "PCR3 (IAM role)")
        }

        Log.d(TAG, "All PCR values verified successfully")
    }

    /**
     * Verify a single PCR value.
     */
    private fun verifyPcr(
        actualPcrs: Map<Int, ByteArray>,
        index: Int,
        expectedHex: String,
        description: String
    ) {
        val actual = actualPcrs[index]
            ?: throw AttestationVerificationException("$description missing from attestation")

        val expected = hexToBytes(expectedHex)

        if (!actual.contentEquals(expected)) {
            val actualHex = actual.toHexString()
            throw AttestationVerificationException(
                "$description mismatch:\n  expected: $expectedHex\n  actual:   $actualHex"
            )
        }

        Log.d(TAG, "$description verified")
    }

    /**
     * Verify nonce matches expected value.
     */
    private fun verifyNonce(actualNonce: ByteArray?, expected: ByteArray) {
        if (actualNonce == null) {
            throw AttestationVerificationException("Attestation missing nonce")
        }

        if (!actualNonce.contentEquals(expected)) {
            throw AttestationVerificationException("Nonce mismatch")
        }

        Log.d(TAG, "Nonce verified")
    }

    /**
     * Parse DER-encoded X.509 certificate.
     */
    private fun parseCertificate(der: ByteArray): X509Certificate {
        return certificateFactory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "").replace(":", "")
        return cleanHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    // MARK: - Internal Data Classes

    private data class CoseSign1(
        val protectedHeader: ByteArray,
        val payload: ByteArray,
        val signature: ByteArray
    )

    private data class AttestationPayload(
        val moduleId: String,
        val digest: String,
        val timestamp: Long,
        val pcrs: Map<Int, ByteArray>,
        val certificate: ByteArray,
        val cabundle: List<ByteArray>,
        val publicKey: ByteArray,
        val userData: ByteArray?,
        val nonce: ByteArray?
    )
}

// MARK: - Public Data Classes

/**
 * Expected PCR values for verification.
 *
 * PCR values are SHA-384 hashes (48 bytes, 96 hex chars) that identify:
 * - PCR0: Enclave image file
 * - PCR1: Linux kernel and bootstrap
 * - PCR2: Application
 * - PCR3: IAM role (optional)
 *
 * These values are published by VettID for each enclave release.
 */
data class ExpectedPcrs(
    /** SHA-384 hash of enclave image (hex string) */
    val pcr0: String,
    /** SHA-384 hash of kernel (hex string) */
    val pcr1: String,
    /** SHA-384 hash of application (hex string) */
    val pcr2: String,
    /** SHA-384 hash of IAM role (hex string, optional) */
    val pcr3: String? = null,
    /** Version identifier for these PCRs */
    val version: String = "unknown",
    /** When these PCRs were published */
    val publishedAt: String? = null
)

/**
 * Verified attestation data extracted from a valid attestation document.
 */
data class VerifiedAttestation(
    /** Enclave's ephemeral public key for key exchange */
    val enclavePublicKey: ByteArray,
    /** Enclave module ID */
    val moduleId: String,
    /** Attestation timestamp (Unix milliseconds) */
    val timestamp: Long,
    /** Verified PCR values */
    val pcrs: Map<Int, ByteArray>,
    /** Optional user data included in attestation */
    val userData: ByteArray?
) {
    /**
     * Get enclave public key as Base64 for key exchange.
     */
    fun enclavePublicKeyBase64(): String = Base64.encodeToString(enclavePublicKey, Base64.NO_WRAP)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VerifiedAttestation
        return enclavePublicKey.contentEquals(other.enclavePublicKey) &&
                moduleId == other.moduleId &&
                timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = enclavePublicKey.contentHashCode()
        result = 31 * result + moduleId.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

/**
 * Exception for attestation verification failures.
 */
class AttestationVerificationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
