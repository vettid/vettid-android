package com.vettid.app.core.crypto

import android.util.Base64
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for CryptoManager
 *
 * Tests cover:
 * - X25519 key generation and key exchange
 * - HKDF key derivation
 * - ChaCha20-Poly1305 encryption/decryption
 * - Argon2id password hashing
 * - LAT verification
 * - Secure random generation
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CryptoManagerTest {

    private lateinit var cryptoManager: CryptoManager

    @Before
    fun setup() {
        cryptoManager = CryptoManager()
    }

    // MARK: - X25519 Key Exchange Tests

    @Test
    fun `generateX25519KeyPair returns valid key pair`() {
        val (privateKey, publicKey) = cryptoManager.generateX25519KeyPair()

        assertEquals("Private key should be 32 bytes", 32, privateKey.size)
        assertEquals("Public key should be 32 bytes", 32, publicKey.size)
        assertFalse("Keys should not be equal", privateKey.contentEquals(publicKey))
    }

    @Test
    fun `generateX25519KeyPair generates unique keys`() {
        val (privateKey1, publicKey1) = cryptoManager.generateX25519KeyPair()
        val (privateKey2, publicKey2) = cryptoManager.generateX25519KeyPair()

        assertFalse("Private keys should be unique", privateKey1.contentEquals(privateKey2))
        assertFalse("Public keys should be unique", publicKey1.contentEquals(publicKey2))
    }

    @Test
    fun `x25519SharedSecret computes same secret for both parties`() {
        // Alice generates her key pair
        val (alicePrivate, alicePublic) = cryptoManager.generateX25519KeyPair()

        // Bob generates his key pair
        val (bobPrivate, bobPublic) = cryptoManager.generateX25519KeyPair()

        // Both compute shared secret
        val aliceShared = cryptoManager.x25519SharedSecret(alicePrivate, bobPublic)
        val bobShared = cryptoManager.x25519SharedSecret(bobPrivate, alicePublic)

        assertEquals("Shared secrets should be 32 bytes", 32, aliceShared.size)
        assertTrue("Shared secrets should match", aliceShared.contentEquals(bobShared))
    }

    // MARK: - HKDF Key Derivation Tests

    @Test
    fun `deriveEncryptionKey returns 32 byte key`() {
        val sharedSecret = cryptoManager.randomBytes(32)
        val derivedKey = cryptoManager.deriveEncryptionKey(sharedSecret)

        assertEquals("Derived key should be 32 bytes", 32, derivedKey.size)
    }

    @Test
    fun `deriveEncryptionKey is deterministic`() {
        val sharedSecret = cryptoManager.randomBytes(32)

        val key1 = cryptoManager.deriveEncryptionKey(sharedSecret, "password-encryption")
        val key2 = cryptoManager.deriveEncryptionKey(sharedSecret, "password-encryption")

        assertTrue("Same inputs should produce same key", key1.contentEquals(key2))
    }

    @Test
    fun `deriveEncryptionKey produces different keys for different info`() {
        val sharedSecret = cryptoManager.randomBytes(32)

        val key1 = cryptoManager.deriveEncryptionKey(sharedSecret, "password-encryption")
        val key2 = cryptoManager.deriveEncryptionKey(sharedSecret, "different-info")

        assertFalse("Different info should produce different keys", key1.contentEquals(key2))
    }

    // MARK: - ChaCha20-Poly1305 Encryption Tests

    @Test
    fun `chaChaEncrypt and chaChaDecrypt roundtrip`() {
        val plaintext = "Hello, VettID!".toByteArray()
        val key = cryptoManager.randomBytes(32)

        val (ciphertext, nonce) = cryptoManager.chaChaEncrypt(plaintext, key)
        val decrypted = cryptoManager.chaChaDecrypt(ciphertext, nonce, key)

        assertTrue("Decrypted should match original", plaintext.contentEquals(decrypted))
    }

    @Test
    fun `chaChaEncrypt produces different ciphertext each time`() {
        val plaintext = "Test message".toByteArray()
        val key = cryptoManager.randomBytes(32)

        val (ciphertext1, nonce1) = cryptoManager.chaChaEncrypt(plaintext, key)
        val (ciphertext2, nonce2) = cryptoManager.chaChaEncrypt(plaintext, key)

        assertFalse("Nonces should be different", nonce1.contentEquals(nonce2))
        assertFalse("Ciphertexts should be different", ciphertext1.contentEquals(ciphertext2))
    }

    @Test
    fun `chaChaEncrypt returns 12 byte nonce`() {
        val plaintext = "Test".toByteArray()
        val key = cryptoManager.randomBytes(32)

        val (_, nonce) = cryptoManager.chaChaEncrypt(plaintext, key)

        assertEquals("Nonce should be 12 bytes", 12, nonce.size)
    }

    @Test
    fun `chaChaDecrypt with wrong key throws exception`() {
        val plaintext = "Secret data".toByteArray()
        val correctKey = cryptoManager.randomBytes(32)
        val wrongKey = cryptoManager.randomBytes(32)

        val (ciphertext, nonce) = cryptoManager.chaChaEncrypt(plaintext, correctKey)

        assertThrows(Exception::class.java) {
            cryptoManager.chaChaDecrypt(ciphertext, nonce, wrongKey)
        }
    }

    @Test
    fun `chaChaDecrypt with tampered ciphertext throws exception`() {
        val plaintext = "Secret data".toByteArray()
        val key = cryptoManager.randomBytes(32)

        val (ciphertext, nonce) = cryptoManager.chaChaEncrypt(plaintext, key)

        // Tamper with ciphertext
        val tamperedCiphertext = ciphertext.clone()
        tamperedCiphertext[0] = (tamperedCiphertext[0].toInt() xor 0xFF).toByte()

        assertThrows(Exception::class.java) {
            cryptoManager.chaChaDecrypt(tamperedCiphertext, nonce, key)
        }
    }

    // MARK: - Argon2id Password Hashing Tests

    @Test
    fun `hashPassword returns 32 byte hash`() {
        val password = "MySecurePassword123!"
        val salt = cryptoManager.generateSalt()

        val hash = cryptoManager.hashPassword(password, salt)

        assertEquals("Hash should be 32 bytes", 32, hash.size)
    }

    @Test
    fun `hashPassword is deterministic with same salt`() {
        val password = "TestPassword"
        val salt = cryptoManager.generateSalt()

        val hash1 = cryptoManager.hashPassword(password, salt)
        val hash2 = cryptoManager.hashPassword(password, salt)

        assertTrue("Same password and salt should produce same hash", hash1.contentEquals(hash2))
    }

    @Test
    fun `hashPassword produces different hashes with different salts`() {
        val password = "TestPassword"
        val salt1 = cryptoManager.generateSalt()
        val salt2 = cryptoManager.generateSalt()

        val hash1 = cryptoManager.hashPassword(password, salt1)
        val hash2 = cryptoManager.hashPassword(password, salt2)

        assertFalse("Different salts should produce different hashes", hash1.contentEquals(hash2))
    }

    @Test
    fun `hashPassword produces different hashes for different passwords`() {
        val password1 = "Password1"
        val password2 = "Password2"
        val salt = cryptoManager.generateSalt()

        val hash1 = cryptoManager.hashPassword(password1, salt)
        val hash2 = cryptoManager.hashPassword(password2, salt)

        assertFalse("Different passwords should produce different hashes", hash1.contentEquals(hash2))
    }

    @Test
    fun `generateSalt returns 16 byte salt`() {
        val salt = cryptoManager.generateSalt()

        assertEquals("Salt should be 16 bytes", 16, salt.size)
    }

    // MARK: - LAT Verification Tests

    @Test
    fun `verifyLat returns true for matching tokens`() {
        val token = "a1b2c3d4e5f6"

        assertTrue(cryptoManager.verifyLat(token, token))
    }

    @Test
    fun `verifyLat is case insensitive`() {
        val token1 = "A1B2C3D4E5F6"
        val token2 = "a1b2c3d4e5f6"

        assertTrue(cryptoManager.verifyLat(token1, token2))
    }

    @Test
    fun `verifyLat returns false for different tokens`() {
        val token1 = "a1b2c3d4e5f6"
        val token2 = "ffffffffffffffff"

        assertFalse(cryptoManager.verifyLat(token1, token2))
    }

    // MARK: - Secure Random Tests

    @Test
    fun `randomBytes returns correct length`() {
        val lengths = listOf(16, 32, 64, 128)

        for (length in lengths) {
            val bytes = cryptoManager.randomBytes(length)
            assertEquals("Random bytes should be $length bytes", length, bytes.size)
        }
    }

    @Test
    fun `randomBytes generates unique values`() {
        val random1 = cryptoManager.randomBytes(32)
        val random2 = cryptoManager.randomBytes(32)

        assertFalse("Random bytes should be unique", random1.contentEquals(random2))
    }

    // MARK: - Full Password Encryption Flow Test

    @Test
    fun `encryptPasswordForServer produces valid result`() {
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
    fun `encryptPasswordForServer can be decrypted by UTK holder`() {
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
}
