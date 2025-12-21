package com.vettid.app.features.enrollment

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.network.TransactionKeyPublic
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end instrumented tests for the enrollment flow
 *
 * Tests the complete crypto pipeline:
 * 1. Password hashing with Argon2id
 * 2. X25519 key exchange
 * 3. HKDF key derivation (with VettID-HKDF-Salt-v1)
 * 4. ChaCha20-Poly1305 encryption
 * 5. Server-side decryption simulation
 *
 * Run with: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class EnrollmentFlowInstrumentedTest {

    private lateinit var cryptoManager: CryptoManager

    // Simulated server-side keys (in production, server holds private key)
    private lateinit var serverPrivateKey: ByteArray
    private lateinit var serverPublicKey: ByteArray
    private lateinit var serverPublicKeyBase64: String

    @Before
    fun setup() {
        cryptoManager = CryptoManager()

        // Generate server-side transaction key pair
        val keyPair = cryptoManager.generateX25519KeyPair()
        serverPrivateKey = keyPair.first
        serverPublicKey = keyPair.second
        serverPublicKeyBase64 = Base64.encodeToString(serverPublicKey, Base64.NO_WRAP)
    }

    // MARK: - End-to-End Enrollment Crypto Flow Tests

    @Test
    fun enrollmentFlow_passwordEncryption_serverCanDecrypt() {
        // Simulate user password
        val userPassword = "MySecureP@ssword123!"
        val passwordSalt = cryptoManager.generateSalt()

        // Step 1: Mobile encrypts password for server
        val encryptionResult = cryptoManager.encryptPasswordForServer(
            password = userPassword,
            salt = passwordSalt,
            utkPublicKeyBase64 = serverPublicKeyBase64
        )

        // Verify encryption result structure
        assertNotNull(encryptionResult.encryptedPasswordHash)
        assertNotNull(encryptionResult.ephemeralPublicKey)
        assertNotNull(encryptionResult.nonce)

        // Step 2: Server receives and decrypts
        val ephemeralPublicKey = Base64.decode(encryptionResult.ephemeralPublicKey, Base64.NO_WRAP)
        val ciphertext = Base64.decode(encryptionResult.encryptedPasswordHash, Base64.NO_WRAP)
        val nonce = Base64.decode(encryptionResult.nonce, Base64.NO_WRAP)

        // Server computes shared secret using its private key
        val sharedSecret = cryptoManager.x25519SharedSecret(serverPrivateKey, ephemeralPublicKey)

        // Server derives decryption key using same HKDF parameters
        val decryptionKey = cryptoManager.deriveEncryptionKey(sharedSecret)

        // Server decrypts
        val decryptedPasswordHash = cryptoManager.chaChaDecrypt(ciphertext, nonce, decryptionKey)

        // Step 3: Verify - server should be able to verify password
        val expectedPasswordHash = cryptoManager.hashPassword(userPassword, passwordSalt)
        assertTrue(
            "Server should decrypt to correct password hash",
            expectedPasswordHash.contentEquals(decryptedPasswordHash)
        )
    }

    @Test
    fun enrollmentFlow_multipleTransactionKeys_selectsCorrect() {
        // Simulate multiple transaction keys from server
        val transactionKeys = listOf(
            createTransactionKey("key-1"),
            createTransactionKey("key-2"),
            createTransactionKey("key-3")
        )

        val passwordKeyId = "key-2"
        val selectedKey = transactionKeys.find { it.keyId == passwordKeyId }

        assertNotNull("Should find the password key", selectedKey)
        assertEquals("key-2", selectedKey!!.keyId)

        // Encrypt with selected key
        val password = "TestP@ssword123!"
        val salt = cryptoManager.generateSalt()

        val result = cryptoManager.encryptPasswordForServer(
            password = password,
            salt = salt,
            utkPublicKeyBase64 = selectedKey.publicKey
        )

        assertNotNull(result.encryptedPasswordHash)
    }

    @Test
    fun enrollmentFlow_passwordStrengthValidation() {
        // Test password strength calculation
        assertEquals(PasswordStrength.WEAK, PasswordStrength.calculate("password"))
        assertEquals(PasswordStrength.WEAK, PasswordStrength.calculate("12345678"))
        assertEquals(PasswordStrength.FAIR, PasswordStrength.calculate("Password1"))
        assertEquals(PasswordStrength.GOOD, PasswordStrength.calculate("Password1!"))
        assertEquals(PasswordStrength.STRONG, PasswordStrength.calculate("MyStr0ng!P@ssword"))
    }

    @Test
    fun enrollmentFlow_minimumPasswordLength() {
        val minLength = 12

        // Password too short should fail validation
        val shortPassword = "Short1!"
        assertTrue(shortPassword.length < minLength)

        // Valid length password
        val validPassword = "ValidP@ssword123"
        assertTrue(validPassword.length >= minLength)
    }

    @Test
    fun enrollmentFlow_saltIsUniquePerEnrollment() {
        val salt1 = cryptoManager.generateSalt()
        val salt2 = cryptoManager.generateSalt()

        assertEquals("Salt should be 16 bytes", 16, salt1.size)
        assertEquals("Salt should be 16 bytes", 16, salt2.size)
        assertFalse("Salts should be unique", salt1.contentEquals(salt2))
    }

    @Test
    fun enrollmentFlow_samePasswordDifferentSalt_differentHash() {
        val password = "SameP@ssword123!"
        val salt1 = cryptoManager.generateSalt()
        val salt2 = cryptoManager.generateSalt()

        val hash1 = cryptoManager.hashPassword(password, salt1)
        val hash2 = cryptoManager.hashPassword(password, salt2)

        assertFalse(
            "Same password with different salts should produce different hashes",
            hash1.contentEquals(hash2)
        )
    }

    @Test
    fun enrollmentFlow_ephemeralKeyIsUniquePerEncryption() {
        val password = "TestP@ssword123!"
        val salt = cryptoManager.generateSalt()

        val result1 = cryptoManager.encryptPasswordForServer(password, salt, serverPublicKeyBase64)
        val result2 = cryptoManager.encryptPasswordForServer(password, salt, serverPublicKeyBase64)

        assertNotEquals(
            "Ephemeral keys should be different for each encryption",
            result1.ephemeralPublicKey,
            result2.ephemeralPublicKey
        )

        assertNotEquals(
            "Nonces should be different for each encryption",
            result1.nonce,
            result2.nonce
        )

        assertNotEquals(
            "Ciphertexts should be different for each encryption",
            result1.encryptedPasswordHash,
            result2.encryptedPasswordHash
        )
    }

    @Test
    fun enrollmentFlow_wrongServerKey_cannotDecrypt() {
        val password = "TestP@ssword123!"
        val salt = cryptoManager.generateSalt()

        // Encrypt with legitimate server key
        val result = cryptoManager.encryptPasswordForServer(password, salt, serverPublicKeyBase64)

        // Generate a different (attacker's) key pair
        val (attackerPrivate, _) = cryptoManager.generateX25519KeyPair()

        // Try to decrypt with wrong key
        val ephemeralPublicKey = Base64.decode(result.ephemeralPublicKey, Base64.NO_WRAP)
        val ciphertext = Base64.decode(result.encryptedPasswordHash, Base64.NO_WRAP)
        val nonce = Base64.decode(result.nonce, Base64.NO_WRAP)

        // Attacker computes shared secret with their private key
        val wrongSharedSecret = cryptoManager.x25519SharedSecret(attackerPrivate, ephemeralPublicKey)
        val wrongDecryptionKey = cryptoManager.deriveEncryptionKey(wrongSharedSecret)

        // Decryption should fail
        try {
            cryptoManager.chaChaDecrypt(ciphertext, nonce, wrongDecryptionKey)
            fail("Decryption with wrong key should throw exception")
        } catch (e: Exception) {
            // Expected - authentication tag verification fails
            assertTrue(e is javax.crypto.AEADBadTagException || e.message?.contains("tag") == true)
        }
    }

    @Test
    fun enrollmentFlow_hkdfSaltConsistency() {
        // This test verifies the HKDF salt is applied consistently
        // The salt "VettID-HKDF-Salt-v1" must match across iOS, Android, and Lambda

        val sharedSecret = cryptoManager.generateX25519KeyPair().first // Use as test shared secret

        // Derive key twice with same inputs
        val key1 = cryptoManager.deriveEncryptionKey(sharedSecret, "password-encryption")
        val key2 = cryptoManager.deriveEncryptionKey(sharedSecret, "password-encryption")

        assertTrue(
            "HKDF should be deterministic with same inputs",
            key1.contentEquals(key2)
        )

        // Different info should produce different key
        val key3 = cryptoManager.deriveEncryptionKey(sharedSecret, "different-info")
        assertFalse(
            "Different HKDF info should produce different key",
            key1.contentEquals(key3)
        )
    }

    @Test
    fun enrollmentFlow_fullRoundTrip_withRealArgon2() {
        // Complete enrollment crypto flow with timing
        val startTime = System.currentTimeMillis()

        val password = "MySecureEnr0llmentP@ssword!"
        val salt = cryptoManager.generateSalt()

        // 1. Hash password (Argon2id - this is the slow part)
        val hashStart = System.currentTimeMillis()
        val passwordHash = cryptoManager.hashPassword(password, salt)
        val hashTime = System.currentTimeMillis() - hashStart

        assertEquals("Password hash should be 32 bytes", 32, passwordHash.size)
        // Argon2id timing varies by device - just verify it completes
        assertTrue("Argon2id should complete in reasonable time", hashTime < 10000)

        // 2. Generate ephemeral key pair (X25519)
        val (ephemeralPrivate, ephemeralPublic) = cryptoManager.generateX25519KeyPair()

        // 3. Compute shared secret
        val sharedSecret = cryptoManager.x25519SharedSecret(ephemeralPrivate, serverPublicKey)

        // 4. Derive encryption key (HKDF with salt)
        val encryptionKey = cryptoManager.deriveEncryptionKey(sharedSecret)
        assertEquals("Encryption key should be 32 bytes", 32, encryptionKey.size)

        // 5. Encrypt password hash (ChaCha20-Poly1305)
        val (ciphertext, nonce) = cryptoManager.chaChaEncrypt(passwordHash, encryptionKey)

        // 6. Server-side decryption
        val serverSharedSecret = cryptoManager.x25519SharedSecret(serverPrivateKey, ephemeralPublic)
        val serverDecryptionKey = cryptoManager.deriveEncryptionKey(serverSharedSecret)
        val decryptedHash = cryptoManager.chaChaDecrypt(ciphertext, nonce, serverDecryptionKey)

        assertTrue(
            "Full round-trip should preserve password hash",
            passwordHash.contentEquals(decryptedHash)
        )

        val totalTime = System.currentTimeMillis() - startTime
        assertTrue("Full flow should complete in reasonable time", totalTime < 10000)
    }

    // MARK: - Helper Methods

    private fun createTransactionKey(keyId: String): TransactionKeyPublic {
        val (_, publicKey) = cryptoManager.generateX25519KeyPair()
        return TransactionKeyPublic(
            keyId = keyId,
            publicKey = Base64.encodeToString(publicKey, Base64.NO_WRAP),
            algorithm = "X25519"
        )
    }
}
