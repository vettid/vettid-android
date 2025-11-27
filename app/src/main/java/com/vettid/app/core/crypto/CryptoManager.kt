package com.vettid.app.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import javax.crypto.spec.IvParameterSpec
import org.signal.argon2.Argon2
import org.signal.argon2.MemoryCost
import org.signal.argon2.Type
import org.signal.argon2.Version
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
 *
 * Key ownership model:
 * - Ledger owns: CEK (Credential Encryption Key), LTK (Ledger Transaction Key)
 * - Mobile stores: UTK pool (public keys only), LAT, encrypted blob
 *
 * Crypto operations:
 * - X25519 key exchange for UTK encryption
 * - ChaCha20-Poly1305 for authenticated encryption
 * - Argon2id for password hashing
 * - HKDF-SHA256 for key derivation
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
        private const val CHACHA_NONCE_LENGTH = 12

        // Argon2id parameters (OWASP recommendations)
        private const val ARGON2_ITERATIONS = 3
        private const val ARGON2_MEMORY_KB = 65536  // 64 MB
        private const val ARGON2_PARALLELISM = 4
        private const val ARGON2_HASH_LENGTH = 32
    }

    // MARK: - Password Hashing (Argon2id)

    /**
     * Hash password using Argon2id
     * Returns 32-byte hash suitable for encryption
     */
    fun hashPassword(password: String, salt: ByteArray): ByteArray {
        val argon2 = Argon2.Builder(Version.V13)
            .type(Type.Argon2id)
            .memoryCost(MemoryCost.KiB(ARGON2_MEMORY_KB))
            .parallelism(ARGON2_PARALLELISM)
            .iterations(ARGON2_ITERATIONS)
            .hashLength(ARGON2_HASH_LENGTH)
            .build()

        val result = argon2.hash(password.toByteArray(Charsets.UTF_8), salt)
        return result.hash
    }

    /**
     * Generate a random salt for Argon2
     */
    fun generateSalt(): ByteArray = randomBytes(16)

    // MARK: - X25519 Key Exchange

    /**
     * Generate an ephemeral X25519 key pair for UTK encryption
     * Returns (privateKey, publicKey) as raw byte arrays
     */
    fun generateX25519KeyPair(): Pair<ByteArray, ByteArray> {
        val privateKey = X25519.generatePrivateKey()
        val publicKey = X25519.publicFromPrivate(privateKey)
        return Pair(privateKey, publicKey)
    }

    /**
     * Compute X25519 shared secret
     */
    fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        return X25519.computeSharedSecret(privateKey, publicKey)
    }

    /**
     * Derive encryption key from shared secret using HKDF-SHA256
     */
    fun deriveEncryptionKey(sharedSecret: ByteArray, info: String = "password-encryption"): ByteArray {
        return Hkdf.computeHkdf(
            "HMACSHA256",
            sharedSecret,
            ByteArray(0),  // empty salt
            info.toByteArray(Charsets.UTF_8),
            32  // 256-bit key
        )
    }

    // MARK: - ChaCha20-Poly1305 Encryption

    /**
     * Encrypt data using ChaCha20-Poly1305
     * Returns (ciphertext, nonce) where ciphertext includes the authentication tag
     *
     * Note: Android 28+ supports ChaCha20-Poly1305 natively via javax.crypto
     * For older versions, we fall back to AES-GCM which is also secure
     */
    fun chaChaEncrypt(plaintext: ByteArray, key: ByteArray): Pair<ByteArray, ByteArray> {
        val nonce = randomBytes(CHACHA_NONCE_LENGTH)

        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            // Use ChaCha20-Poly1305 on Android 9+
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            val ciphertext = cipher.doFinal(plaintext)
            Pair(ciphertext, nonce)
        } else {
            // Fallback to AES-GCM on older Android (still secure)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), gcmSpec)
            val ciphertext = cipher.doFinal(plaintext)
            Pair(ciphertext, nonce)
        }
    }

    /**
     * Decrypt data using ChaCha20-Poly1305 (or AES-GCM fallback)
     */
    fun chaChaDecrypt(ciphertext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            cipher.doFinal(ciphertext)
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), gcmSpec)
            cipher.doFinal(ciphertext)
        }
    }

    // MARK: - UTK Password Encryption Flow

    /**
     * Encrypt password hash for sending to server
     *
     * Flow:
     * 1. Hash password with Argon2id
     * 2. Generate ephemeral X25519 keypair
     * 3. Compute shared secret with UTK public key
     * 4. Derive encryption key with HKDF
     * 5. Encrypt password hash with ChaCha20-Poly1305
     *
     * @param password User's password
     * @param salt Salt for Argon2 (should be stored locally)
     * @param utkPublicKeyBase64 UTK public key from server (Base64)
     * @return PasswordEncryptionResult with encrypted data for API
     */
    fun encryptPasswordForServer(
        password: String,
        salt: ByteArray,
        utkPublicKeyBase64: String
    ): PasswordEncryptionResult {
        // 1. Hash password
        val passwordHash = hashPassword(password, salt)

        // 2. Generate ephemeral X25519 keypair
        val (ephemeralPrivate, ephemeralPublic) = generateX25519KeyPair()

        // 3. Decode UTK public key and compute shared secret
        val utkPublicKey = Base64.decode(utkPublicKeyBase64, Base64.NO_WRAP)
        val sharedSecret = x25519SharedSecret(ephemeralPrivate, utkPublicKey)

        // 4. Derive encryption key
        val encryptionKey = deriveEncryptionKey(sharedSecret)

        // 5. Encrypt password hash
        val (ciphertext, nonce) = chaChaEncrypt(passwordHash, encryptionKey)

        // Clear sensitive data
        ephemeralPrivate.fill(0)
        sharedSecret.fill(0)
        encryptionKey.fill(0)
        passwordHash.fill(0)

        return PasswordEncryptionResult(
            encryptedPasswordHash = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            ephemeralPublicKey = Base64.encodeToString(ephemeralPublic, Base64.NO_WRAP),
            nonce = Base64.encodeToString(nonce, Base64.NO_WRAP)
        )
    }

    // MARK: - Legacy EC Key Operations (for attestation)

    /**
     * Generate a new EC key pair in the Android Keystore
     * Uses hardware-backed keys when available
     */
    fun generateKeyPair(alias: String): KeyPair {
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
            .setUserAuthenticationRequired(false)
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

    // MARK: - Signing (for attestation)

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

    // MARK: - LAT Verification

    /**
     * Verify LAT matches stored value (phishing protection)
     */
    fun verifyLat(receivedLatHex: String, storedLatHex: String): Boolean {
        return MessageDigest.isEqual(
            receivedLatHex.lowercase().toByteArray(),
            storedLatHex.lowercase().toByteArray()
        )
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

    // MARK: - Key Management

    fun hasKey(alias: String): Boolean {
        return keyStore.containsAlias("$KEY_ALIAS_PREFIX$alias")
    }

    fun deleteKey(alias: String) {
        val keyAlias = "$KEY_ALIAS_PREFIX$alias"
        if (keyStore.containsAlias(keyAlias)) {
            keyStore.deleteEntry(keyAlias)
        }
    }

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

/**
 * Result of encrypting password for server transmission
 */
data class PasswordEncryptionResult(
    val encryptedPasswordHash: String,  // Base64
    val ephemeralPublicKey: String,     // Base64
    val nonce: String                   // Base64
)
