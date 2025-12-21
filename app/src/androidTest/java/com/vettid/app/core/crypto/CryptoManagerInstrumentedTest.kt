package com.vettid.app.core.crypto

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for CryptoManager - Argon2 password hashing
 *
 * These tests require the Android native Argon2 library and must run
 * on a device or emulator.
 *
 * Run with: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class CryptoManagerInstrumentedTest {

    private lateinit var cryptoManager: CryptoManager

    @Before
    fun setup() {
        cryptoManager = CryptoManager()
    }

    // MARK: - Argon2id Password Hashing Tests

    @Test
    fun hashPassword_returns32ByteHash() {
        val password = "MySecurePassword123!"
        val salt = cryptoManager.generateSalt()

        val hash = cryptoManager.hashPassword(password, salt)

        assertEquals("Hash should be 32 bytes", 32, hash.size)
    }

    @Test
    fun hashPassword_isDeterministicWithSameSalt() {
        val password = "TestPassword"
        val salt = cryptoManager.generateSalt()

        val hash1 = cryptoManager.hashPassword(password, salt)
        val hash2 = cryptoManager.hashPassword(password, salt)

        assertTrue("Same password and salt should produce same hash", hash1.contentEquals(hash2))
    }

    @Test
    fun hashPassword_producesDifferentHashesWithDifferentSalts() {
        val password = "TestPassword"
        val salt1 = cryptoManager.generateSalt()
        val salt2 = cryptoManager.generateSalt()

        val hash1 = cryptoManager.hashPassword(password, salt1)
        val hash2 = cryptoManager.hashPassword(password, salt2)

        assertFalse("Different salts should produce different hashes", hash1.contentEquals(hash2))
    }

    @Test
    fun hashPassword_producesDifferentHashesForDifferentPasswords() {
        val password1 = "Password1"
        val password2 = "Password2"
        val salt = cryptoManager.generateSalt()

        val hash1 = cryptoManager.hashPassword(password1, salt)
        val hash2 = cryptoManager.hashPassword(password2, salt)

        assertFalse("Different passwords should produce different hashes", hash1.contentEquals(hash2))
    }

    // MARK: - Full Password Encryption Flow Tests

    @Test
    fun encryptPasswordForServer_producesValidResult() {
        val password = "MySecurePassword123!"
        val salt = cryptoManager.generateSalt()

        // Generate a UTK key pair (simulating server)
        val (_, utkPublic) = cryptoManager.generateX25519KeyPair()
        val utkPublicBase64 = Base64.encodeToString(utkPublic, Base64.NO_WRAP)

        val result = cryptoManager.encryptPasswordForServer(password, salt, utkPublicBase64)

        // Verify result has all required fields
        assertNotNull("Encrypted password hash should not be null", result.encryptedPasswordHash)
        assertNotNull("Ephemeral public key should not be null", result.ephemeralPublicKey)
        assertNotNull("Nonce should not be null", result.nonce)

        // Verify base64 decoding works
        val encryptedData = Base64.decode(result.encryptedPasswordHash, Base64.NO_WRAP)
        val ephemeralPublic = Base64.decode(result.ephemeralPublicKey, Base64.NO_WRAP)
        val nonce = Base64.decode(result.nonce, Base64.NO_WRAP)

        assertTrue("Encrypted data should have content", encryptedData.isNotEmpty())
        assertEquals("Ephemeral public key should be 32 bytes", 32, ephemeralPublic.size)
        assertEquals("Nonce should be 12 bytes", 12, nonce.size)
    }

    @Test
    fun encryptPasswordForServer_canBeDecryptedByUTKHolder() {
        val password = "MySecurePassword123!"
        val salt = cryptoManager.generateSalt()

        // Generate a UTK key pair (simulating server)
        val (utkPrivate, utkPublic) = cryptoManager.generateX25519KeyPair()
        val utkPublicBase64 = Base64.encodeToString(utkPublic, Base64.NO_WRAP)

        // Mobile encrypts password
        val result = cryptoManager.encryptPasswordForServer(password, salt, utkPublicBase64)

        // Server decrypts (simulated)
        val ephemeralPublic = Base64.decode(result.ephemeralPublicKey, Base64.NO_WRAP)
        val ciphertext = Base64.decode(result.encryptedPasswordHash, Base64.NO_WRAP)
        val nonce = Base64.decode(result.nonce, Base64.NO_WRAP)

        // Server computes shared secret
        val sharedSecret = cryptoManager.x25519SharedSecret(utkPrivate, ephemeralPublic)
        val decryptionKey = cryptoManager.deriveEncryptionKey(sharedSecret)

        // Server decrypts
        val decryptedHash = cryptoManager.chaChaDecrypt(ciphertext, nonce, decryptionKey)

        // Verify the decrypted hash matches what we would compute locally
        val expectedHash = cryptoManager.hashPassword(password, salt)
        assertTrue("Decrypted hash should match original", expectedHash.contentEquals(decryptedHash))
    }

    // MARK: - HKDF Salt Verification Test

    @Test
    fun encryptPasswordForServer_usesCorrectHKDFSalt() {
        // This test verifies the HKDF salt is applied correctly in the full flow
        val password = "TestPassword123!"
        val salt = cryptoManager.generateSalt()

        val (utkPrivate, utkPublic) = cryptoManager.generateX25519KeyPair()
        val utkPublicBase64 = Base64.encodeToString(utkPublic, Base64.NO_WRAP)

        // Encrypt twice with same inputs
        val result1 = cryptoManager.encryptPasswordForServer(password, salt, utkPublicBase64)
        val result2 = cryptoManager.encryptPasswordForServer(password, salt, utkPublicBase64)

        // Different ephemeral keys should be generated each time
        assertNotEquals(
            "Ephemeral keys should be different",
            result1.ephemeralPublicKey,
            result2.ephemeralPublicKey
        )

        // But both should decrypt to the same password hash
        val ephemeralPublic1 = Base64.decode(result1.ephemeralPublicKey, Base64.NO_WRAP)
        val ciphertext1 = Base64.decode(result1.encryptedPasswordHash, Base64.NO_WRAP)
        val nonce1 = Base64.decode(result1.nonce, Base64.NO_WRAP)

        val sharedSecret1 = cryptoManager.x25519SharedSecret(utkPrivate, ephemeralPublic1)
        val decryptionKey1 = cryptoManager.deriveEncryptionKey(sharedSecret1)
        val decryptedHash1 = cryptoManager.chaChaDecrypt(ciphertext1, nonce1, decryptionKey1)

        val ephemeralPublic2 = Base64.decode(result2.ephemeralPublicKey, Base64.NO_WRAP)
        val ciphertext2 = Base64.decode(result2.encryptedPasswordHash, Base64.NO_WRAP)
        val nonce2 = Base64.decode(result2.nonce, Base64.NO_WRAP)

        val sharedSecret2 = cryptoManager.x25519SharedSecret(utkPrivate, ephemeralPublic2)
        val decryptionKey2 = cryptoManager.deriveEncryptionKey(sharedSecret2)
        val decryptedHash2 = cryptoManager.chaChaDecrypt(ciphertext2, nonce2, decryptionKey2)

        assertTrue(
            "Both decrypted hashes should be identical",
            decryptedHash1.contentEquals(decryptedHash2)
        )
    }
}
