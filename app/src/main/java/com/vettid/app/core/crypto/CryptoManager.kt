package com.vettid.app.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages all cryptographic operations for VettID
 * Uses X25519 for key exchange and ChaCha20-Poly1305 for authenticated encryption
 *
 * Note: Android's native Keystore doesn't support X25519 directly, so we use
 * software-based X25519 with the keys protected by Android Keystore wrapping.
 * For this implementation, we use ECDH with secp256r1 as a fallback with
 * hardware-backed key storage.
 */
@Singleton
class CryptoManager @Inject constructor() {

    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    companion object {
        private const val KEY_ALIAS_PREFIX = "vettid_"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_NONCE_LENGTH = 12
    }

    // MARK: - Key Generation

    /**
     * Generate a new EC key pair in the Android Keystore
     * Uses hardware-backed keys when available
     */
    fun generateKeyPair(alias: String): KeyPair {
        val keyAlias = "$KEY_ALIAS_PREFIX$alias"

        // Delete existing key if present
        if (keyStore.containsAlias(keyAlias)) {
            keyStore.deleteEntry(keyAlias)
        }

        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )

        val parameterSpec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_AGREE_KEY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false) // Enable for biometric-protected keys
            .build()

        keyPairGenerator.initialize(parameterSpec)
        val javaKeyPair = keyPairGenerator.generateKeyPair()

        return KeyPair(
            privateKey = javaKeyPair.private,
            publicKey = javaKeyPair.public
        )
    }

    /**
     * Generate a biometric-protected key pair
     */
    fun generateBiometricProtectedKeyPair(alias: String): KeyPair {
        val keyAlias = "$KEY_ALIAS_PREFIX$alias"

        if (keyStore.containsAlias(keyAlias)) {
            keyStore.deleteEntry(keyAlias)
        }

        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )

        val parameterSpec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_AGREE_KEY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .build()

        keyPairGenerator.initialize(parameterSpec)
        val javaKeyPair = keyPairGenerator.generateKeyPair()

        return KeyPair(
            privateKey = javaKeyPair.private,
            publicKey = javaKeyPair.public
        )
    }

    // MARK: - Key Agreement (ECDH)

    /**
     * Perform ECDH key agreement to derive a shared secret
     */
    fun deriveSharedSecret(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(publicKey, true)
        return keyAgreement.generateSecret()
    }

    /**
     * Derive a symmetric key from shared secret using HKDF-like derivation
     */
    fun deriveSymmetricKey(
        sharedSecret: ByteArray,
        info: String = "credential-encryption-v1"
    ): SecretKey {
        // Simple HKDF-Extract + Expand (SHA-256)
        val digest = MessageDigest.getInstance("SHA-256")
        val infoBytes = info.toByteArray(Charsets.UTF_8)

        // HKDF-Extract (PRK = HMAC-Hash(salt, IKM))
        // Using empty salt (all zeros), extract phase simplifies to hash
        digest.update(sharedSecret)
        val prk = digest.digest()

        // HKDF-Expand
        digest.reset()
        digest.update(prk)
        digest.update(infoBytes)
        digest.update(0x01.toByte())
        val okm = digest.digest()

        return SecretKeySpec(okm, "AES")
    }

    // MARK: - Encryption/Decryption

    /**
     * Encrypt data using AES-GCM (ChaCha20-Poly1305 alternative for Android)
     */
    fun encrypt(plaintext: ByteArray, key: SecretKey): EncryptedPayload {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(GCM_NONCE_LENGTH)
        SecureRandom().nextBytes(nonce)

        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)

        val ciphertext = cipher.doFinal(plaintext)

        // GCM appends tag to ciphertext, extract it
        val tagStart = ciphertext.size - (GCM_TAG_LENGTH / 8)
        val actualCiphertext = ciphertext.copyOfRange(0, tagStart)
        val tag = ciphertext.copyOfRange(tagStart, ciphertext.size)

        return EncryptedPayload(
            nonce = nonce,
            ciphertext = actualCiphertext,
            tag = tag
        )
    }

    /**
     * Decrypt data using AES-GCM
     */
    fun decrypt(payload: EncryptedPayload, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, payload.nonce)
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

        // GCM expects ciphertext + tag combined
        val combined = payload.ciphertext + payload.tag
        return cipher.doFinal(combined)
    }

    /**
     * Hybrid encryption: ECDH + AES-GCM
     */
    fun hybridEncrypt(
        plaintext: ByteArray,
        recipientPublicKey: PublicKey
    ): HybridEncryptedPayload {
        // Generate ephemeral key pair
        val ephemeralKeyPair = generateEphemeralKeyPair()

        // Derive shared secret
        val sharedSecret = deriveSharedSecret(ephemeralKeyPair.private, recipientPublicKey)

        // Derive symmetric key
        val symmetricKey = deriveSymmetricKey(sharedSecret)

        // Encrypt
        val encrypted = encrypt(plaintext, symmetricKey)

        return HybridEncryptedPayload(
            ephemeralPublicKey = ephemeralKeyPair.public.encoded,
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext,
            tag = encrypted.tag
        )
    }

    private fun generateEphemeralKeyPair(): java.security.KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        return keyPairGenerator.generateKeyPair()
    }

    // MARK: - Signing

    /**
     * Sign data using ECDSA
     */
    fun sign(data: ByteArray, alias: String): ByteArray {
        val keyAlias = "$KEY_ALIAS_PREFIX$alias"
        val privateKey = keyStore.getKey(keyAlias, null) as PrivateKey

        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(data)
        return signature.sign()
    }

    /**
     * Verify ECDSA signature
     */
    fun verify(data: ByteArray, signatureBytes: ByteArray, publicKey: PublicKey): Boolean {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initVerify(publicKey)
        signature.update(data)
        return signature.verify(signatureBytes)
    }

    // MARK: - Secure Random

    /**
     * Generate cryptographically secure random bytes
     */
    fun randomBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    /**
     * Generate a 256-bit random token (for LAT)
     */
    fun generateToken(): ByteArray = randomBytes(32)

    // MARK: - Key Management

    /**
     * Check if a key exists in the keystore
     */
    fun hasKey(alias: String): Boolean {
        return keyStore.containsAlias("$KEY_ALIAS_PREFIX$alias")
    }

    /**
     * Delete a key from the keystore
     */
    fun deleteKey(alias: String) {
        val keyAlias = "$KEY_ALIAS_PREFIX$alias"
        if (keyStore.containsAlias(keyAlias)) {
            keyStore.deleteEntry(keyAlias)
        }
    }

    /**
     * Retrieve a public key from the keystore
     */
    fun getPublicKey(alias: String): PublicKey? {
        val keyAlias = "$KEY_ALIAS_PREFIX$alias"
        return keyStore.getCertificate(keyAlias)?.publicKey
    }
}

// MARK: - Data Classes

data class KeyPair(
    val privateKey: PrivateKey,
    val publicKey: PublicKey
)

data class EncryptedPayload(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val tag: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedPayload
        return nonce.contentEquals(other.nonce) &&
                ciphertext.contentEquals(other.ciphertext) &&
                tag.contentEquals(other.tag)
    }

    override fun hashCode(): Int {
        var result = nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + tag.contentHashCode()
        return result
    }
}

data class HybridEncryptedPayload(
    val ephemeralPublicKey: ByteArray, // Encoded public key
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val tag: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HybridEncryptedPayload
        return ephemeralPublicKey.contentEquals(other.ephemeralPublicKey) &&
                nonce.contentEquals(other.nonce) &&
                ciphertext.contentEquals(other.ciphertext) &&
                tag.contentEquals(other.tag)
    }

    override fun hashCode(): Int {
        var result = ephemeralPublicKey.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + tag.contentHashCode()
        return result
    }
}
