package com.vettid.app.core.crypto

import android.util.Base64
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import com.vettid.app.core.network.EncryptedMessage
import com.vettid.app.core.storage.CredentialStore
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages cryptographic operations for per-connection encryption.
 *
 * Responsibilities:
 * - Generate X25519 key pairs for new connections
 * - Derive shared secrets from X25519 key exchange
 * - Derive per-connection encryption keys using HKDF
 * - Encrypt/decrypt messages with XChaCha20-Poly1305 (or ChaCha20-Poly1305 fallback)
 * - Securely store and retrieve connection keys
 */
@Singleton
class ConnectionCryptoManager @Inject constructor(
    private val credentialStore: CredentialStore
) {

    companion object {
        private const val NONCE_LENGTH = 12  // 96 bits for ChaCha20-Poly1305
        private const val XCHACHA_NONCE_LENGTH = 24  // 192 bits for XChaCha20-Poly1305
        private const val KEY_LENGTH = 32  // 256 bits
        private const val GCM_TAG_LENGTH = 128  // bits

        // HKDF info strings for key derivation
        private const val HKDF_INFO_CONNECTION = "vettid-connection-key"
        private const val HKDF_INFO_MESSAGE = "vettid-message-encryption"
    }

    // MARK: - Key Pair Generation

    /**
     * Generate an X25519 key pair for a new connection.
     *
     * @return ConnectionKeyPair with private and public keys
     */
    fun generateConnectionKeyPair(): ConnectionKeyPair {
        val privateKey = X25519.generatePrivateKey()
        val publicKey = X25519.publicFromPrivate(privateKey)
        return ConnectionKeyPair(privateKey, publicKey)
    }

    // MARK: - Key Exchange

    /**
     * Derive shared secret from X25519 key exchange.
     *
     * @param privateKey Our private key (32 bytes)
     * @param peerPublicKey Peer's public key (32 bytes)
     * @return 32-byte shared secret
     */
    fun deriveSharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        return X25519.computeSharedSecret(privateKey, peerPublicKey)
    }

    /**
     * Derive shared secret from X25519 key exchange with Base64-encoded peer key.
     *
     * @param privateKey Our private key (32 bytes)
     * @param peerPublicKeyBase64 Base64-encoded peer public key
     * @return 32-byte shared secret
     */
    fun deriveSharedSecret(privateKey: ByteArray, peerPublicKeyBase64: String): ByteArray {
        val peerPublicKey = Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP)
        return deriveSharedSecret(privateKey, peerPublicKey)
    }

    // MARK: - Key Derivation

    /**
     * Derive per-connection encryption key using HKDF.
     *
     * @param sharedSecret The shared secret from X25519 key exchange
     * @param connectionId Unique connection identifier
     * @return 32-byte encryption key for this connection
     */
    fun deriveConnectionKey(sharedSecret: ByteArray, connectionId: String): ByteArray {
        val info = "$HKDF_INFO_CONNECTION:$connectionId"
        return Hkdf.computeHkdf(
            "HMACSHA256",
            sharedSecret,
            ByteArray(0),  // empty salt
            info.toByteArray(Charsets.UTF_8),
            KEY_LENGTH
        )
    }

    /**
     * Perform full key derivation from key exchange to connection key.
     *
     * @param privateKey Our private key
     * @param peerPublicKey Peer's public key
     * @param connectionId Connection identifier
     * @return Derived connection encryption key
     */
    fun deriveAndStoreConnectionKey(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
        connectionId: String
    ): ByteArray {
        val sharedSecret = deriveSharedSecret(privateKey, peerPublicKey)
        val connectionKey = deriveConnectionKey(sharedSecret, connectionId)

        // Clear sensitive intermediate value
        sharedSecret.fill(0)

        // Store the key securely
        credentialStore.storeConnectionKey(connectionId, connectionKey)

        return connectionKey
    }

    // MARK: - Message Encryption

    /**
     * Encrypt a message with the connection's key.
     *
     * Uses ChaCha20-Poly1305 on Android 9+ or AES-GCM as fallback.
     *
     * @param plaintext The message to encrypt
     * @param connectionKey 32-byte connection encryption key
     * @return EncryptedMessage with ciphertext and nonce
     */
    fun encryptMessage(plaintext: String, connectionKey: ByteArray): EncryptedMessage {
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        return encryptMessage(plaintextBytes, connectionKey)
    }

    /**
     * Encrypt bytes with the connection's key.
     *
     * @param plaintext Bytes to encrypt
     * @param connectionKey 32-byte connection encryption key
     * @return EncryptedMessage with ciphertext and nonce
     */
    fun encryptMessage(plaintext: ByteArray, connectionKey: ByteArray): EncryptedMessage {
        val nonce = generateNonce()

        val ciphertext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            // Use ChaCha20-Poly1305 on Android 9+
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(connectionKey, "ChaCha20"), IvParameterSpec(nonce))
            cipher.doFinal(plaintext)
        } else {
            // Fallback to AES-GCM on older Android
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(connectionKey, "AES"), gcmSpec)
            cipher.doFinal(plaintext)
        }

        return EncryptedMessage(ciphertext, nonce)
    }

    /**
     * Encrypt a message for a specific connection by ID.
     *
     * @param plaintext The message to encrypt
     * @param connectionId The connection ID (key must be stored)
     * @return EncryptedMessage or null if key not found
     */
    fun encryptMessageForConnection(plaintext: String, connectionId: String): EncryptedMessage? {
        val key = credentialStore.getConnectionKey(connectionId) ?: return null
        return encryptMessage(plaintext, key)
    }

    // MARK: - Message Decryption

    /**
     * Decrypt a message with the connection's key.
     *
     * @param ciphertext The encrypted data
     * @param nonce The nonce used for encryption
     * @param connectionKey 32-byte connection encryption key
     * @return Decrypted plaintext string
     * @throws Exception if decryption fails (e.g., tampered data)
     */
    fun decryptMessage(ciphertext: ByteArray, nonce: ByteArray, connectionKey: ByteArray): String {
        val plaintext = decryptBytes(ciphertext, nonce, connectionKey)
        return String(plaintext, Charsets.UTF_8)
    }

    /**
     * Decrypt bytes with the connection's key.
     *
     * @param ciphertext The encrypted data
     * @param nonce The nonce used for encryption
     * @param connectionKey 32-byte connection encryption key
     * @return Decrypted bytes
     * @throws Exception if decryption fails
     */
    fun decryptBytes(ciphertext: ByteArray, nonce: ByteArray, connectionKey: ByteArray): ByteArray {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            // Use ChaCha20-Poly1305 on Android 9+
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(connectionKey, "ChaCha20"), IvParameterSpec(nonce))
            cipher.doFinal(ciphertext)
        } else {
            // Fallback to AES-GCM on older Android
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(connectionKey, "AES"), gcmSpec)
            cipher.doFinal(ciphertext)
        }
    }

    /**
     * Decrypt a message from a specific connection by ID.
     *
     * @param ciphertext The encrypted data
     * @param nonce The nonce
     * @param connectionId The connection ID
     * @return Decrypted plaintext or null if key not found
     */
    fun decryptMessageFromConnection(
        ciphertext: ByteArray,
        nonce: ByteArray,
        connectionId: String
    ): String? {
        val key = credentialStore.getConnectionKey(connectionId) ?: return null
        return try {
            decryptMessage(ciphertext, nonce, key)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decrypt an EncryptedMessage using the stored key for a connection.
     *
     * @param encryptedMessage The encrypted message
     * @param connectionId The connection ID
     * @return Decrypted plaintext or null if key not found or decryption fails
     */
    fun decryptMessage(encryptedMessage: EncryptedMessage, connectionId: String): String? {
        return decryptMessageFromConnection(
            encryptedMessage.ciphertext,
            encryptedMessage.nonce,
            connectionId
        )
    }

    // MARK: - Key Storage

    /**
     * Store a connection key securely.
     *
     * @param connectionId The connection ID
     * @param key The 32-byte encryption key
     */
    suspend fun storeConnectionKey(connectionId: String, key: ByteArray) {
        credentialStore.storeConnectionKey(connectionId, key)
    }

    /**
     * Retrieve a connection key.
     *
     * @param connectionId The connection ID
     * @return The encryption key or null if not found
     */
    suspend fun getConnectionKey(connectionId: String): ByteArray? {
        return credentialStore.getConnectionKey(connectionId)
    }

    /**
     * Delete a connection key (when connection is revoked).
     *
     * @param connectionId The connection ID
     */
    fun deleteConnectionKey(connectionId: String) {
        credentialStore.deleteConnectionKey(connectionId)
    }

    // MARK: - Utility Functions

    /**
     * Generate a cryptographically secure nonce.
     */
    private fun generateNonce(): ByteArray {
        val nonce = ByteArray(NONCE_LENGTH)
        SecureRandom().nextBytes(nonce)
        return nonce
    }

    /**
     * Encode bytes to Base64.
     */
    fun encodeBase64(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Decode Base64 to bytes.
     */
    fun decodeBase64(base64: String): ByteArray {
        return Base64.decode(base64, Base64.NO_WRAP)
    }
}

// MARK: - Data Classes

/**
 * X25519 key pair for connection establishment.
 */
data class ConnectionKeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray
) {
    /**
     * Get public key as Base64 string for transmission.
     */
    fun publicKeyBase64(): String {
        return Base64.encodeToString(publicKey, Base64.NO_WRAP)
    }

    /**
     * Clear private key from memory.
     */
    fun clearPrivateKey() {
        privateKey.fill(0)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ConnectionKeyPair

        if (!privateKey.contentEquals(other.privateKey)) return false
        if (!publicKey.contentEquals(other.publicKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = privateKey.contentHashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}
