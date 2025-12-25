package com.vettid.app.core.crypto

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for SessionCrypto E2E encryption.
 *
 * Tests cover:
 * - X25519 keypair generation
 * - ECDH key exchange and session establishment
 * - Session restoration from storage
 * - Session validity/expiration
 * - AES-GCM encryption/decryption (Robolectric uses SDK 28 fallback)
 * - EncryptedSessionMessage serialization
 * - SessionInfo parsing
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionCryptoTest {

    // MARK: - SessionKeyPair Tests

    @Test
    fun `generateKeyPair returns valid keypair`() {
        val keyPair = SessionCrypto.generateKeyPair()

        assertEquals("Private key should be 32 bytes", 32, keyPair.privateKey.size)
        assertEquals("Public key should be 32 bytes", 32, keyPair.publicKey.size)
        assertFalse("Keys should not be equal", keyPair.privateKey.contentEquals(keyPair.publicKey))
    }

    @Test
    fun `generateKeyPair generates unique keys`() {
        val keyPair1 = SessionCrypto.generateKeyPair()
        val keyPair2 = SessionCrypto.generateKeyPair()

        assertFalse("Private keys should be unique", keyPair1.privateKey.contentEquals(keyPair2.privateKey))
        assertFalse("Public keys should be unique", keyPair1.publicKey.contentEquals(keyPair2.publicKey))
    }

    @Test
    fun `publicKeyBase64 returns valid base64`() {
        val keyPair = SessionCrypto.generateKeyPair()
        val base64 = keyPair.publicKeyBase64()

        assertTrue("Base64 should not be empty", base64.isNotEmpty())
        // Base64 of 32 bytes = 44 characters (with padding)
        assertEquals("Base64 length should be 44 chars", 44, base64.length)
    }

    @Test
    fun `keypair clear zeros out private key`() {
        val keyPair = SessionCrypto.generateKeyPair()
        val originalPrivate = keyPair.privateKey.copyOf()

        keyPair.clear()

        // Private key should be zeroed
        assertTrue("Private key should be zeroed", keyPair.privateKey.all { it == 0.toByte() })
        assertFalse("Original should not be all zeros", originalPrivate.all { it == 0.toByte() })
    }

    // MARK: - Key Exchange Tests

    @Test
    fun `fromKeyExchange creates valid session`() {
        val appKeyPair = SessionCrypto.generateKeyPair()
        val vaultKeyPair = SessionCrypto.generateKeyPair()
        val sessionId = "test-session-123"
        val expiresAt = System.currentTimeMillis() + 3600_000 // 1 hour

        val session = SessionCrypto.fromKeyExchange(
            sessionId = sessionId,
            appPrivateKey = appKeyPair.privateKey,
            appPublicKey = appKeyPair.publicKey,
            vaultPublicKey = vaultKeyPair.publicKey,
            expiresAt = expiresAt
        )

        assertEquals("Session ID should match", sessionId, session.sessionId)
        assertEquals("Expiration should match", expiresAt, session.expiresAt)
        assertTrue("Public key should match", session.publicKey.contentEquals(appKeyPair.publicKey))
        assertTrue("Session should be valid", session.isValid)
    }

    @Test
    fun `both parties derive same session key`() {
        // App generates keypair
        val appKeyPair = SessionCrypto.generateKeyPair()
        val appPrivateCopy = appKeyPair.privateKey.copyOf()

        // Vault generates keypair
        val vaultKeyPair = SessionCrypto.generateKeyPair()
        val vaultPrivateCopy = vaultKeyPair.privateKey.copyOf()

        val sessionId = "shared-session"
        val expiresAt = System.currentTimeMillis() + 3600_000

        // App creates session
        val appSession = SessionCrypto.fromKeyExchange(
            sessionId = sessionId,
            appPrivateKey = appPrivateCopy,
            appPublicKey = appKeyPair.publicKey,
            vaultPublicKey = vaultKeyPair.publicKey,
            expiresAt = expiresAt
        )

        // Vault creates session (using its private key and app's public key)
        val vaultSession = SessionCrypto.fromKeyExchange(
            sessionId = sessionId,
            appPrivateKey = vaultPrivateCopy,
            appPublicKey = vaultKeyPair.publicKey,
            vaultPublicKey = appKeyPair.publicKey,
            expiresAt = expiresAt
        )

        // Test that both can decrypt each other's messages
        val testMessage = "Hello from app".toByteArray()
        val encrypted = appSession.encrypt(testMessage)

        // Create the encrypted message with vault's session ID for decryption
        val encryptedForVault = EncryptedSessionMessage(
            sessionId = sessionId,
            ciphertext = encrypted.ciphertext,
            nonce = encrypted.nonce
        )

        val decrypted = vaultSession.decrypt(encryptedForVault)
        assertTrue("Vault should decrypt app's message", testMessage.contentEquals(decrypted))
    }

    // MARK: - Session Restoration Tests

    @Test
    fun `fromStored restores valid session`() {
        val sessionId = "stored-session"
        val sessionKey = ByteArray(32) { it.toByte() }
        val publicKey = ByteArray(32) { (it + 100).toByte() }
        val expiresAt = System.currentTimeMillis() + 3600_000

        val session = SessionCrypto.fromStored(
            sessionId = sessionId,
            sessionKey = sessionKey,
            publicKey = publicKey,
            expiresAt = expiresAt
        )

        assertEquals("Session ID should match", sessionId, session.sessionId)
        assertEquals("Expiration should match", expiresAt, session.expiresAt)
        assertTrue("Public key should match", session.publicKey.contentEquals(publicKey))
        assertTrue("Session key for storage should match", session.getSessionKeyForStorage().contentEquals(sessionKey))
    }

    // MARK: - Session Validity Tests

    @Test
    fun `isValid returns true for future expiration`() {
        val session = createTestSession(expiresAt = System.currentTimeMillis() + 3600_000)

        assertTrue("Session with future expiration should be valid", session.isValid)
    }

    @Test
    fun `isValid returns false for past expiration`() {
        val session = createTestSession(expiresAt = System.currentTimeMillis() - 1000)

        assertFalse("Session with past expiration should be invalid", session.isValid)
    }

    // MARK: - Encryption/Decryption Tests

    @Test
    fun `encrypt and decrypt roundtrip`() {
        val session = createTestSession()
        val plaintext = "Hello, VettID!".toByteArray()

        val encrypted = session.encrypt(plaintext)
        val decrypted = session.decrypt(encrypted)

        assertTrue("Decrypted should match original", plaintext.contentEquals(decrypted))
    }

    @Test
    fun `encrypt produces unique ciphertext each time`() {
        val session = createTestSession()
        val plaintext = "Same message".toByteArray()

        val encrypted1 = session.encrypt(plaintext)
        val encrypted2 = session.encrypt(plaintext)

        assertFalse("Nonces should differ", encrypted1.nonce.contentEquals(encrypted2.nonce))
        assertFalse("Ciphertexts should differ", encrypted1.ciphertext.contentEquals(encrypted2.ciphertext))
    }

    @Test
    fun `encrypt returns 12 byte nonce`() {
        val session = createTestSession()
        val encrypted = session.encrypt("test".toByteArray())

        assertEquals("Nonce should be 12 bytes", 12, encrypted.nonce.size)
    }

    @Test
    fun `encrypt includes session ID`() {
        val sessionId = "my-session-id"
        val session = createTestSession(sessionId = sessionId)
        val encrypted = session.encrypt("test".toByteArray())

        assertEquals("Encrypted message should have session ID", sessionId, encrypted.sessionId)
    }

    @Test
    fun `decrypt with wrong session ID throws exception`() {
        val session = createTestSession(sessionId = "correct-session")
        val encrypted = session.encrypt("test".toByteArray())

        // Create a message with wrong session ID
        val wrongSessionMessage = EncryptedSessionMessage(
            sessionId = "wrong-session",
            ciphertext = encrypted.ciphertext,
            nonce = encrypted.nonce
        )

        assertThrows(SessionCryptoException::class.java) {
            session.decrypt(wrongSessionMessage)
        }
    }

    @Test
    fun `decrypt with tampered ciphertext throws exception`() {
        val session = createTestSession()
        val encrypted = session.encrypt("secret data".toByteArray())

        // Tamper with ciphertext
        val tamperedCiphertext = encrypted.ciphertext.copyOf()
        tamperedCiphertext[0] = (tamperedCiphertext[0].toInt() xor 0xFF).toByte()

        val tamperedMessage = EncryptedSessionMessage(
            sessionId = encrypted.sessionId,
            ciphertext = tamperedCiphertext,
            nonce = encrypted.nonce
        )

        assertThrows(SessionCryptoException::class.java) {
            session.decrypt(tamperedMessage)
        }
    }

    @Test
    fun `encryptJson and decryptJson roundtrip`() {
        val session = createTestSession()
        val payload = JSONObject().apply {
            put("action", "test")
            put("value", 42)
        }

        val encrypted = session.encryptJson(payload)
        val decrypted = session.decryptJson(encrypted)

        assertEquals("action", payload.getString("action"), decrypted.getString("action"))
        assertEquals("value", payload.getInt("value"), decrypted.getInt("value"))
    }

    @Test
    fun `clear zeros out session key`() {
        val session = createTestSession()
        val originalKey = session.getSessionKeyForStorage()

        session.clear()

        val clearedKey = session.getSessionKeyForStorage()
        assertTrue("Session key should be zeroed", clearedKey.all { it == 0.toByte() })
        assertFalse("Original key should not be all zeros", originalKey.all { it == 0.toByte() })
    }

    // MARK: - EncryptedSessionMessage Tests

    @Test
    fun `EncryptedSessionMessage toJson serializes correctly`() {
        val message = EncryptedSessionMessage(
            sessionId = "test-session",
            ciphertext = byteArrayOf(1, 2, 3, 4),
            nonce = byteArrayOf(5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        )

        val json = message.toJson()

        assertEquals("Session ID should serialize", "test-session", json.getString("session_id"))
        assertTrue("Ciphertext should be base64", json.getString("ciphertext").isNotEmpty())
        assertTrue("Nonce should be base64", json.getString("nonce").isNotEmpty())
    }

    @Test
    fun `EncryptedSessionMessage fromJson deserializes correctly`() {
        val json = JSONObject().apply {
            put("session_id", "test-session")
            put("ciphertext", "AQIDBA==") // Base64 of [1,2,3,4]
            put("nonce", "BQYHCAkKCwwNDg8Q") // Base64 of [5..16]
        }

        val message = EncryptedSessionMessage.fromJson(json)

        assertEquals("Session ID should match", "test-session", message.sessionId)
        assertEquals("Ciphertext length", 4, message.ciphertext.size)
        assertEquals("Nonce length", 12, message.nonce.size)
    }

    @Test
    fun `EncryptedSessionMessage toJson and fromJson roundtrip`() {
        val original = EncryptedSessionMessage(
            sessionId = "roundtrip-test",
            ciphertext = ByteArray(32) { it.toByte() },
            nonce = ByteArray(12) { (it + 100).toByte() }
        )

        val json = original.toJson()
        val restored = EncryptedSessionMessage.fromJson(json)

        assertEquals("Session ID should match", original.sessionId, restored.sessionId)
        assertTrue("Ciphertext should match", original.ciphertext.contentEquals(restored.ciphertext))
        assertTrue("Nonce should match", original.nonce.contentEquals(restored.nonce))
    }

    @Test
    fun `EncryptedSessionMessage toBytes and fromBytes roundtrip`() {
        val original = EncryptedSessionMessage(
            sessionId = "bytes-test",
            ciphertext = ByteArray(16) { it.toByte() },
            nonce = ByteArray(12) { (it + 50).toByte() }
        )

        val bytes = original.toBytes()
        val restored = EncryptedSessionMessage.fromBytes(bytes)

        assertEquals("Session ID should match", original.sessionId, restored.sessionId)
        assertTrue("Ciphertext should match", original.ciphertext.contentEquals(restored.ciphertext))
        assertTrue("Nonce should match", original.nonce.contentEquals(restored.nonce))
    }

    // MARK: - SessionInfo Tests

    @Test
    fun `SessionInfo fromJson parses correctly`() {
        val json = JSONObject().apply {
            put("session_id", "sess-abc123")
            put("vault_session_public_key", "dGVzdHB1YmxpY2tleQ==")
            put("session_expires_at", "2025-01-15T12:00:00Z")
            put("encryption_enabled", true)
        }

        val info = SessionInfo.fromJson(json)

        assertEquals("Session ID", "sess-abc123", info.sessionId)
        assertEquals("Vault public key", "dGVzdHB1YmxpY2tleQ==", info.vaultSessionPublicKey)
        assertEquals("Expires at", "2025-01-15T12:00:00Z", info.sessionExpiresAt)
        assertTrue("Encryption enabled", info.encryptionEnabled)
    }

    @Test
    fun `SessionInfo encryptionEnabled defaults to true`() {
        val json = JSONObject().apply {
            put("session_id", "test")
            put("vault_session_public_key", "key")
            put("session_expires_at", "2025-01-15T12:00:00Z")
            // encryption_enabled omitted
        }

        val info = SessionInfo.fromJson(json)

        assertTrue("Encryption should default to true", info.encryptionEnabled)
    }

    @Test
    fun `SessionInfo expiresAtMillis parses ISO 8601`() {
        val info = SessionInfo(
            sessionId = "test",
            vaultSessionPublicKey = "key",
            sessionExpiresAt = "2025-01-15T12:00:00Z",
            encryptionEnabled = true
        )

        val millis = info.expiresAtMillis()

        // 2025-01-15T12:00:00Z = 1736942400000 millis
        assertEquals("Should parse to correct millis", 1736942400000L, millis)
    }

    @Test
    fun `SessionInfo expiresAtMillis returns future time on parse error`() {
        val info = SessionInfo(
            sessionId = "test",
            vaultSessionPublicKey = "key",
            sessionExpiresAt = "invalid-date",
            encryptionEnabled = true
        )

        val millis = info.expiresAtMillis()
        val now = System.currentTimeMillis()

        assertTrue("Should return future time on error", millis > now)
        // Default is 7 days from now
        val sevenDaysInMillis = 7 * 24 * 60 * 60 * 1000L
        assertTrue("Should be approximately 7 days", millis > now + sevenDaysInMillis - 1000)
    }

    // MARK: - Helper Methods

    private fun createTestSession(
        sessionId: String = "test-session",
        expiresAt: Long = System.currentTimeMillis() + 3600_000
    ): SessionCrypto {
        val appKeyPair = SessionCrypto.generateKeyPair()
        val vaultKeyPair = SessionCrypto.generateKeyPair()

        return SessionCrypto.fromKeyExchange(
            sessionId = sessionId,
            appPrivateKey = appKeyPair.privateKey,
            appPublicKey = appKeyPair.publicKey,
            vaultPublicKey = vaultKeyPair.publicKey,
            expiresAt = expiresAt
        )
    }
}
