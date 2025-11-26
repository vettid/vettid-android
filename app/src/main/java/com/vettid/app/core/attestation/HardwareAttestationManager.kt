package com.vettid.app.core.attestation

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hardware Key Attestation for device integrity verification
 *
 * Supports:
 * - Google Android attestation
 * - GrapheneOS attestation
 * - Other ROMs with hardware-backed Keystore
 */
@Singleton
class HardwareAttestationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    companion object {
        private const val ATTESTATION_KEY_ALIAS = "vettid_attestation_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        // ASN.1 OID for Key Attestation Extension
        private const val KEY_ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17"
    }

    // MARK: - Attestation Key Generation

    /**
     * Generate an attestation key with hardware-backed attestation
     */
    fun generateAttestationKey(challenge: ByteArray): AttestationResult {
        // Delete existing attestation key
        if (keyStore.containsAlias(ATTESTATION_KEY_ALIAS)) {
            keyStore.deleteEntry(ATTESTATION_KEY_ALIAS)
        }

        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )

        val builder = KeyGenParameterSpec.Builder(
            ATTESTATION_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN
        )
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge) // This enables attestation
            .setUserAuthenticationRequired(false)

        // Enable StrongBox if available (Pixel 3+ and other high-security devices)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                builder.setIsStrongBoxBacked(true)
            } catch (e: Exception) {
                // StrongBox not available, continue with TEE
            }
        }

        keyPairGenerator.initialize(builder.build())
        keyPairGenerator.generateKeyPair()

        // Get attestation certificate chain
        val certificateChain = keyStore.getCertificateChain(ATTESTATION_KEY_ALIAS)
            ?.map { it as X509Certificate }
            ?: throw AttestationException("Failed to get certificate chain")

        return AttestationResult(
            certificateChain = certificateChain,
            attestationExtension = extractAttestationExtension(certificateChain[0])
        )
    }

    // MARK: - Attestation Verification (Client-side parsing)

    /**
     * Extract the attestation extension from the leaf certificate
     */
    private fun extractAttestationExtension(certificate: X509Certificate): ByteArray? {
        return certificate.getExtensionValue(KEY_ATTESTATION_OID)
    }

    /**
     * Get device security level from attestation
     */
    fun getSecurityLevel(): SecurityLevel {
        return try {
            // Try to generate a StrongBox key to check availability
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val testAlias = "security_level_test"
                try {
                    if (keyStore.containsAlias(testAlias)) {
                        keyStore.deleteEntry(testAlias)
                    }

                    val keyPairGenerator = KeyPairGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_EC,
                        ANDROID_KEYSTORE
                    )

                    keyPairGenerator.initialize(
                        KeyGenParameterSpec.Builder(testAlias, KeyProperties.PURPOSE_SIGN)
                            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .setIsStrongBoxBacked(true)
                            .build()
                    )
                    keyPairGenerator.generateKeyPair()
                    keyStore.deleteEntry(testAlias)
                    SecurityLevel.STRONG_BOX
                } catch (e: Exception) {
                    SecurityLevel.TEE
                }
            } else {
                SecurityLevel.TEE
            }
        } catch (e: Exception) {
            SecurityLevel.SOFTWARE
        }
    }

    /**
     * Check if hardware attestation is available
     */
    fun isAttestationAvailable(): Boolean {
        return try {
            // Generate a test key with attestation challenge
            val testChallenge = ByteArray(32) { it.toByte() }
            generateAttestationKey(testChallenge)
            keyStore.deleteEntry(ATTESTATION_KEY_ALIAS)
            true
        } catch (e: Exception) {
            false
        }
    }

    // MARK: - Prepare Enrollment Attestation

    /**
     * Prepare attestation data for enrollment
     */
    fun prepareEnrollmentAttestation(
        credentialPublicKey: ByteArray,
        deviceId: String
    ): EnrollmentAttestationData {
        // Create challenge that binds attestation to credential
        val challengeData = "$deviceId:${credentialPublicKey.toHexString()}:${System.currentTimeMillis()}"
        val challenge = java.security.MessageDigest.getInstance("SHA-256")
            .digest(challengeData.toByteArray())

        val result = generateAttestationKey(challenge)

        return EnrollmentAttestationData(
            challenge = challenge,
            certificateChain = result.certificateChain.map { it.encoded },
            securityLevel = getSecurityLevel()
        )
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}

// MARK: - Data Classes

data class AttestationResult(
    val certificateChain: List<X509Certificate>,
    val attestationExtension: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AttestationResult
        return certificateChain == other.certificateChain &&
                attestationExtension?.contentEquals(other.attestationExtension ?: byteArrayOf()) ?: (other.attestationExtension == null)
    }

    override fun hashCode(): Int {
        var result = certificateChain.hashCode()
        result = 31 * result + (attestationExtension?.contentHashCode() ?: 0)
        return result
    }
}

data class EnrollmentAttestationData(
    val challenge: ByteArray,
    val certificateChain: List<ByteArray>,
    val securityLevel: SecurityLevel
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EnrollmentAttestationData
        return challenge.contentEquals(other.challenge) &&
                certificateChain.size == other.certificateChain.size &&
                certificateChain.zip(other.certificateChain).all { (a, b) -> a.contentEquals(b) } &&
                securityLevel == other.securityLevel
    }

    override fun hashCode(): Int {
        var result = challenge.contentHashCode()
        result = 31 * result + certificateChain.hashCode()
        result = 31 * result + securityLevel.hashCode()
        return result
    }
}

enum class SecurityLevel {
    SOFTWARE,    // Software-backed keys (lowest security)
    TEE,         // Trusted Execution Environment (good security)
    STRONG_BOX   // Hardware security module (highest security)
}

class AttestationException(message: String) : Exception(message)
