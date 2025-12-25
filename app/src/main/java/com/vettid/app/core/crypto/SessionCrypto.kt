package com.vettid.app.core.crypto

import android.util.Base64
import android.util.Log
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * E2E session encryption for app-vault communication.
 *
 * Provides defense-in-depth encryption on top of TLS:
 * - X25519 ECDH key exchange
 * - HKDF-SHA256 key derivation
 * - ChaCha20-Poly1305 authenticated encryption
 *
 * Protocol:
 * 1. App generates session keypair
 * 2. App sends public key in bootstrap request
 * 3. Vault responds with its public key
 * 4. Both derive shared session key via ECDH + HKDF
 * 5. All messages encrypted with session key
 */
class SessionCrypto private constructor(
    val sessionId: String,
    private val sessionKey: ByteArray,
    val expiresAt: Long,
    private val privateKey: ByteArray? = null,
    val publicKey: ByteArray
) {
    companion object {
        private const val TAG = "SessionCrypto"
        private const val KEY_SIZE = 32
        private const val NONCE_SIZE = 12
        private const val HKDF_INFO = "app-vault-session-v1"
        private const val HKDF_SALT = "VettID-HKDF-Salt-v1"
        private const val GCM_TAG_LENGTH = 128

        private val secureRandom = SecureRandom()

        /**
         * Generate a new session keypair for initiating key exchange.
         * The private key is stored temporarily until key exchange completes.
         */
        fun generateKeyPair(): SessionKeyPair {
            val privateKey = X25519.generatePrivateKey()
            val publicKey = X25519.publicFromPrivate(privateKey)
            return SessionKeyPair(privateKey, publicKey)
        }

        /**
         * Create a session from completed key exchange.
         *
         * @param sessionId Session ID from vault response
         * @param appPrivateKey App's X25519 private key
         * @param vaultPublicKey Vault's X25519 public key (from response)
         * @param expiresAt Session expiration timestamp (millis)
         */
        fun fromKeyExchange(
            sessionId: String,
            appPrivateKey: ByteArray,
            appPublicKey: ByteArray,
            vaultPublicKey: ByteArray,
            expiresAt: Long
        ): SessionCrypto {
            // Compute shared secret via ECDH
            val sharedSecret = X25519.computeSharedSecret(appPrivateKey, vaultPublicKey)

            // Derive session key via HKDF
            val sessionKey = Hkdf.computeHkdf(
                "HMACSHA256",
                sharedSecret,
                HKDF_SALT.toByteArray(Charsets.UTF_8),
                HKDF_INFO.toByteArray(Charsets.UTF_8),
                KEY_SIZE
            )

            // Clear sensitive data
            sharedSecret.fill(0)
            appPrivateKey.fill(0)

            Log.d(TAG, "Session established: $sessionId, expires: $expiresAt")

            return SessionCrypto(
                sessionId = sessionId,
                sessionKey = sessionKey,
                expiresAt = expiresAt,
                privateKey = null, // No longer needed
                publicKey = appPublicKey
            )
        }

        /**
         * Restore a session from stored data.
         */
        fun fromStored(
            sessionId: String,
            sessionKey: ByteArray,
            publicKey: ByteArray,
            expiresAt: Long
        ): SessionCrypto {
            return SessionCrypto(
                sessionId = sessionId,
                sessionKey = sessionKey,
                expiresAt = expiresAt,
                privateKey = null,
                publicKey = publicKey
            )
        }
    }

    /**
     * Check if the session is still valid.
     */
    val isValid: Boolean
        get() = System.currentTimeMillis() < expiresAt

    /**
     * Encrypt a message payload for sending to vault.
     *
     * @param plaintext The message payload bytes
     * @return EncryptedSessionMessage with ciphertext, nonce, and session ID
     */
    fun encrypt(plaintext: ByteArray): EncryptedSessionMessage {
        val nonce = ByteArray(NONCE_SIZE)
        secureRandom.nextBytes(nonce)

        val ciphertext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            // ChaCha20-Poly1305 on Android 9+
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "ChaCha20"), IvParameterSpec(nonce))
            cipher.doFinal(plaintext)
        } else {
            // AES-GCM fallback on older Android
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), gcmSpec)
            cipher.doFinal(plaintext)
        }

        return EncryptedSessionMessage(
            sessionId = sessionId,
            ciphertext = ciphertext,
            nonce = nonce
        )
    }

    /**
     * Encrypt a JSON payload for sending to vault.
     */
    fun encryptJson(payload: JSONObject): EncryptedSessionMessage {
        return encrypt(payload.toString().toByteArray(Charsets.UTF_8))
    }

    /**
     * Decrypt a message received from vault.
     *
     * @param message The encrypted message
     * @return Decrypted plaintext bytes
     * @throws SessionCryptoException if decryption fails
     */
    fun decrypt(message: EncryptedSessionMessage): ByteArray {
        if (message.sessionId != sessionId) {
            throw SessionCryptoException("Session ID mismatch: expected $sessionId, got ${message.sessionId}")
        }

        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val cipher = Cipher.getInstance("ChaCha20-Poly1305")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "ChaCha20"), IvParameterSpec(message.nonce))
                cipher.doFinal(message.ciphertext)
            } else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, message.nonce)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), gcmSpec)
                cipher.doFinal(message.ciphertext)
            }
        } catch (e: Exception) {
            throw SessionCryptoException("Decryption failed: ${e.message}", e)
        }
    }

    /**
     * Decrypt a message and parse as JSON.
     */
    fun decryptJson(message: EncryptedSessionMessage): JSONObject {
        val plaintext = decrypt(message)
        return JSONObject(String(plaintext, Charsets.UTF_8))
    }

    /**
     * Get session key for storage (should be encrypted at rest).
     */
    fun getSessionKeyForStorage(): ByteArray = sessionKey.copyOf()

    /**
     * Clear sensitive data from memory.
     */
    fun clear() {
        sessionKey.fill(0)
        privateKey?.fill(0)
    }
}

/**
 * X25519 keypair for session establishment.
 */
data class SessionKeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray
) {
    /**
     * Get public key as Base64 for sending in bootstrap request.
     */
    fun publicKeyBase64(): String = Base64.encodeToString(publicKey, Base64.NO_WRAP)

    /**
     * Clear private key from memory.
     */
    fun clear() {
        privateKey.fill(0)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SessionKeyPair
        return privateKey.contentEquals(other.privateKey) && publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int {
        var result = privateKey.contentHashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}

/**
 * Encrypted message with session context.
 */
data class EncryptedSessionMessage(
    val sessionId: String,
    val ciphertext: ByteArray,
    val nonce: ByteArray
) {
    /**
     * Serialize to JSON for NATS transport.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("session_id", sessionId)
            put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
        }
    }

    /**
     * Serialize to bytes for NATS transport.
     */
    fun toBytes(): ByteArray = toJson().toString().toByteArray(Charsets.UTF_8)

    companion object {
        /**
         * Parse from JSON (received from vault).
         */
        fun fromJson(json: JSONObject): EncryptedSessionMessage {
            return EncryptedSessionMessage(
                sessionId = json.getString("session_id"),
                ciphertext = Base64.decode(json.getString("ciphertext"), Base64.NO_WRAP),
                nonce = Base64.decode(json.getString("nonce"), Base64.NO_WRAP)
            )
        }

        /**
         * Parse from bytes (received from NATS).
         */
        fun fromBytes(data: ByteArray): EncryptedSessionMessage {
            return fromJson(JSONObject(String(data, Charsets.UTF_8)))
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedSessionMessage
        return sessionId == other.sessionId &&
                ciphertext.contentEquals(other.ciphertext) &&
                nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        return result
    }
}

/**
 * Session info from vault bootstrap response.
 */
data class SessionInfo(
    val sessionId: String,
    val vaultSessionPublicKey: String, // Base64
    val sessionExpiresAt: String, // ISO 8601
    val encryptionEnabled: Boolean
) {
    companion object {
        fun fromJson(json: JSONObject): SessionInfo {
            return SessionInfo(
                sessionId = json.getString("session_id"),
                vaultSessionPublicKey = json.getString("vault_session_public_key"),
                sessionExpiresAt = json.getString("session_expires_at"),
                encryptionEnabled = json.optBoolean("encryption_enabled", true)
            )
        }
    }

    /**
     * Parse expiration time to millis.
     */
    fun expiresAtMillis(): Long {
        return try {
            java.time.Instant.parse(sessionExpiresAt).toEpochMilli()
        } catch (e: Exception) {
            // Default to 7 days from now if parsing fails
            System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000L
        }
    }
}

/**
 * Exception for session crypto errors.
 */
class SessionCryptoException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
