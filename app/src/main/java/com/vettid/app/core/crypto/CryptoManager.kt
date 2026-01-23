package com.vettid.app.core.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import com.vettid.app.core.security.SecureByteArray
import com.vettid.app.core.security.secureClear
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
import javax.crypto.KeyGenerator
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
 *
 * Security notes:
 * - Uses SecureRandom for all random number generation
 * - Clears sensitive data from memory after use
 * - Uses hardware-backed keys when available (StrongBox/TEE)
 * - Argon2id parameters follow OWASP 2024 guidelines
 */
@Singleton
class CryptoManager @Inject constructor() {

    // Lazy initialization allows unit tests to run without AndroidKeyStore
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }
    }

    // Thread-safe SecureRandom instance
    private val secureRandom: SecureRandom by lazy {
        // Use getInstanceStrong() on Android 8+ for best entropy source
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                SecureRandom.getInstanceStrong()
            } catch (e: Exception) {
                SecureRandom()
            }
        } else {
            SecureRandom()
        }
    }

    companion object {
        private const val KEY_ALIAS_PREFIX = "vettid_"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_NONCE_LENGTH = 12
        private const val CHACHA_NONCE_LENGTH = 12
        private const val XCHACHA_NONCE_LENGTH = 24

        /** Domain for UTK encryption HKDF (used as salt, no info) */
        const val UTK_DOMAIN = "vettid-utk-v1"

        /**
         * Argon2id parameters (OWASP 2024 recommendations)
         *
         * These parameters provide strong security while being usable on mobile:
         * - Type: Argon2id (hybrid protection against GPU/side-channel attacks)
         * - Memory: 64 MB (balances security with mobile memory constraints)
         * - Iterations: 3 (minimum recommended for interactive use)
         * - Parallelism: 4 (good for multi-core mobile devices)
         * - Hash length: 32 bytes (256-bit output)
         *
         * For first-generation recommendation: m=47104 (46 MiB), t=1, p=1
         * For second-generation: m=19456 (19 MiB), t=2, p=1
         * We use higher memory (64 MiB) for better security on capable devices.
         */
        private const val ARGON2_ITERATIONS = 3
        private const val ARGON2_MEMORY_KB = 65536  // 64 MB
        private const val ARGON2_PARALLELISM = 4
        private const val ARGON2_HASH_LENGTH = 32

        // Salt lengths
        private const val SALT_LENGTH = 16
        private const val KEY_LENGTH = 32  // 256-bit keys

        /** HKDF context for Nitro Enclave password encryption */
        const val ENCLAVE_AUTH_CONTEXT = "enclave-auth-v1"
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
     *
     * Uses a consistent salt across all platforms (iOS, Android, Lambda)
     * for defense-in-depth encryption.
     */
    fun deriveEncryptionKey(sharedSecret: ByteArray, info: String = "transaction-encryption-v1"): ByteArray {
        return Hkdf.computeHkdf(
            "HMACSHA256",
            sharedSecret,
            "VettID-HKDF-Salt-v1".toByteArray(Charsets.UTF_8),  // Cross-platform salt
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

    /**
     * Derive encryption key using HKDF with domain as salt (no info).
     *
     * This matches the vault-manager's HKDF configuration:
     * - Algorithm: HKDF-SHA256
     * - Salt: domain string (e.g., "vettid-utk-v1")
     * - Info: nil/empty
     * - Key length: 32 bytes
     */
    fun deriveKeyWithDomain(sharedSecret: ByteArray, domain: String): ByteArray {
        return Hkdf.computeHkdf(
            "HMACSHA256",
            sharedSecret,
            domain.toByteArray(Charsets.UTF_8),  // Domain as salt
            ByteArray(0),  // No info
            32  // 256-bit key
        )
    }

    // MARK: - PHC String Format

    /**
     * Generate PHC (Password Hashing Competition) format string for Argon2id.
     *
     * Format: $argon2id$v=19$m=65536,t=3,p=4$<base64-salt>$<base64-hash>
     *
     * Note: PHC format uses base64 without padding for salt and hash.
     */
    fun hashPasswordPHC(password: String, salt: ByteArray): String {
        val hash = hashPassword(password, salt)

        // Base64 encode without padding (PHC format requirement)
        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP or Base64.NO_PADDING)
        val hashB64 = Base64.encodeToString(hash, Base64.NO_WRAP or Base64.NO_PADDING)

        return "\$argon2id\$v=19\$m=$ARGON2_MEMORY_KB,t=$ARGON2_ITERATIONS,p=$ARGON2_PARALLELISM\$$saltB64\$$hashB64"
    }

    // MARK: - XChaCha20-Poly1305 Encryption

    /**
     * HChaCha20 - derives a 256-bit subkey from a 256-bit key and 128-bit nonce.
     *
     * This is the first step of XChaCha20 which extends ChaCha20's nonce from 96 to 192 bits.
     * The output is used as the key for regular ChaCha20.
     */
    private fun hChaCha20(key: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size == 32) { "Key must be 32 bytes" }
        require(nonce.size == 16) { "Nonce must be 16 bytes" }

        // ChaCha20 constants: "expand 32-byte k"
        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574

        // Key (8 words)
        for (i in 0..7) {
            state[4 + i] = littleEndianToInt(key, i * 4)
        }

        // Nonce (4 words) - in HChaCha20, nonce goes in positions 12-15
        for (i in 0..3) {
            state[12 + i] = littleEndianToInt(nonce, i * 4)
        }

        // 20 rounds (10 double rounds)
        val working = state.copyOf()
        repeat(10) {
            // Column rounds
            quarterRound(working, 0, 4, 8, 12)
            quarterRound(working, 1, 5, 9, 13)
            quarterRound(working, 2, 6, 10, 14)
            quarterRound(working, 3, 7, 11, 15)
            // Diagonal rounds
            quarterRound(working, 0, 5, 10, 15)
            quarterRound(working, 1, 6, 11, 12)
            quarterRound(working, 2, 7, 8, 13)
            quarterRound(working, 3, 4, 9, 14)
        }

        // Extract subkey: first 4 words and last 4 words of the state
        val subkey = ByteArray(32)
        intToLittleEndian(working[0], subkey, 0)
        intToLittleEndian(working[1], subkey, 4)
        intToLittleEndian(working[2], subkey, 8)
        intToLittleEndian(working[3], subkey, 12)
        intToLittleEndian(working[12], subkey, 16)
        intToLittleEndian(working[13], subkey, 20)
        intToLittleEndian(working[14], subkey, 24)
        intToLittleEndian(working[15], subkey, 28)

        return subkey
    }

    private fun quarterRound(state: IntArray, a: Int, b: Int, c: Int, d: Int) {
        state[a] += state[b]; state[d] = (state[d] xor state[a]).rotateLeft(16)
        state[c] += state[d]; state[b] = (state[b] xor state[c]).rotateLeft(12)
        state[a] += state[b]; state[d] = (state[d] xor state[a]).rotateLeft(8)
        state[c] += state[d]; state[b] = (state[b] xor state[c]).rotateLeft(7)  // ChaCha20 spec: 7, not 4
    }

    private fun littleEndianToInt(bs: ByteArray, off: Int): Int {
        return (bs[off].toInt() and 0xff) or
                ((bs[off + 1].toInt() and 0xff) shl 8) or
                ((bs[off + 2].toInt() and 0xff) shl 16) or
                ((bs[off + 3].toInt() and 0xff) shl 24)
    }

    private fun intToLittleEndian(n: Int, bs: ByteArray, off: Int) {
        bs[off] = n.toByte()
        bs[off + 1] = (n ushr 8).toByte()
        bs[off + 2] = (n ushr 16).toByte()
        bs[off + 3] = (n ushr 24).toByte()
    }

    /**
     * Encrypt data using XChaCha20-Poly1305 (24-byte nonce).
     *
     * XChaCha20 extends ChaCha20's nonce from 96 to 192 bits:
     * 1. Use HChaCha20 to derive subkey from key and first 16 bytes of nonce
     * 2. Use ChaCha20-Poly1305 with subkey and nonce = [0,0,0,0] + last 8 bytes of original nonce
     *
     * @return (ciphertext with auth tag, 24-byte nonce)
     */
    fun xChaChaEncrypt(plaintext: ByteArray, key: ByteArray): Pair<ByteArray, ByteArray> {
        val nonce = randomBytes(XCHACHA_NONCE_LENGTH)

        // Derive subkey using HChaCha20 with first 16 bytes of nonce
        val subkey = hChaCha20(key, nonce.copyOfRange(0, 16))

        // Create ChaCha20-Poly1305 nonce: 4 zero bytes + last 8 bytes of XChaCha20 nonce
        val chaChaNonce = ByteArray(12)
        System.arraycopy(nonce, 16, chaChaNonce, 4, 8)

        // Encrypt with standard ChaCha20-Poly1305 using derived subkey
        val ciphertext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(subkey, "ChaCha20"), IvParameterSpec(chaChaNonce))
            cipher.doFinal(plaintext)
        } else {
            // Fallback to AES-GCM on older Android
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, chaChaNonce)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(subkey, "AES"), gcmSpec)
            cipher.doFinal(plaintext)
        }

        // Clear subkey
        subkey.fill(0)

        return Pair(ciphertext, nonce)
    }

    /**
     * Decrypt data using XChaCha20-Poly1305 (24-byte nonce).
     *
     * Reverses xChaChaEncrypt:
     * 1. Use HChaCha20 to derive subkey from key and first 16 bytes of nonce
     * 2. Use ChaCha20-Poly1305 with subkey and constructed nonce
     *
     * @param ciphertext Encrypted data with auth tag
     * @param nonce 24-byte XChaCha20 nonce
     * @param key 32-byte encryption key
     * @return Decrypted plaintext
     */
    fun xChaChaDecrypt(ciphertext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        require(nonce.size == XCHACHA_NONCE_LENGTH) { "Nonce must be 24 bytes" }
        require(key.size == 32) { "Key must be 32 bytes" }

        // Derive subkey using HChaCha20 with first 16 bytes of nonce
        val subkey = hChaCha20(key, nonce.copyOfRange(0, 16))

        // Create ChaCha20-Poly1305 nonce: 4 zero bytes + last 8 bytes of XChaCha20 nonce
        val chaChaNonce = ByteArray(12)
        System.arraycopy(nonce, 16, chaChaNonce, 4, 8)

        // Decrypt with standard ChaCha20-Poly1305 using derived subkey
        val plaintext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(subkey, "ChaCha20"), IvParameterSpec(chaChaNonce))
            cipher.doFinal(ciphertext)
        } else {
            // Fallback to AES-GCM on older Android
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, chaChaNonce)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(subkey, "AES"), gcmSpec)
            cipher.doFinal(ciphertext)
        }

        // Clear subkey
        subkey.fill(0)

        return plaintext
    }

    /**
     * Encrypt data to UTK public key using XChaCha20-Poly1305 with domain-based HKDF.
     *
     * This method matches the vault-manager's expected format:
     * - X25519 key exchange
     * - HKDF with domain as salt, no info
     * - XChaCha20-Poly1305 encryption (24-byte nonce)
     *
     * @param plaintext Data to encrypt
     * @param utkPublicKeyBase64 UTK public key (Base64)
     * @param domain HKDF domain string (default: "vettid-utk-v1")
     * @return Concatenated blob: ephemeral_pubkey (32) + nonce (24) + ciphertext
     */
    fun encryptToUTK(
        plaintext: ByteArray,
        utkPublicKeyBase64: String,
        domain: String = UTK_DOMAIN
    ): ByteArray {
        // 1. Generate ephemeral X25519 keypair
        val (ephemeralPrivate, ephemeralPublic) = generateX25519KeyPair()

        // 2. Compute shared secret with UTK public key
        val utkPublicKey = Base64.decode(utkPublicKeyBase64, Base64.NO_WRAP)
        val sharedSecret = x25519SharedSecret(ephemeralPrivate, utkPublicKey)

        // 3. Derive encryption key using domain-based HKDF
        val encryptionKey = deriveKeyWithDomain(sharedSecret, domain)

        // 4. Encrypt with XChaCha20-Poly1305 (24-byte nonce)
        val (ciphertext, nonce) = xChaChaEncrypt(plaintext, encryptionKey)

        // 5. Concatenate: ephemeral_pubkey (32) + nonce (24) + ciphertext
        val result = ByteArray(ephemeralPublic.size + nonce.size + ciphertext.size)
        System.arraycopy(ephemeralPublic, 0, result, 0, ephemeralPublic.size)
        System.arraycopy(nonce, 0, result, ephemeralPublic.size, nonce.size)
        System.arraycopy(ciphertext, 0, result, ephemeralPublic.size + nonce.size, ciphertext.size)

        // Clear sensitive data
        ephemeralPrivate.fill(0)
        sharedSecret.fill(0)
        encryptionKey.fill(0)

        return result
    }

    // MARK: - Password Encryption Flow

    /**
     * Encrypt password hash for sending to server (legacy UTK flow).
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

        // 4. Derive encryption key (legacy context)
        val encryptionKey = deriveEncryptionKey(sharedSecret, "transaction-encryption-v1")

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

    /**
     * Encrypt password hash for Nitro Enclave (new architecture).
     *
     * Flow:
     * 1. Hash password with Argon2id
     * 2. Generate ephemeral X25519 keypair
     * 3. Compute shared secret with ENCLAVE public key (from attestation)
     * 4. Derive encryption key with HKDF using "enclave-auth-v1" context
     * 5. Encrypt password hash with ChaCha20-Poly1305
     *
     * @param password User's password
     * @param salt Salt for Argon2 (should be stored locally)
     * @param enclavePublicKeyBase64 Enclave public key from attestation (Base64)
     * @return PasswordEncryptionResult with encrypted data for API
     */
    fun encryptPasswordForEnclave(
        password: String,
        salt: ByteArray,
        enclavePublicKeyBase64: String
    ): PasswordEncryptionResult {
        // 1. Hash password
        val passwordHash = hashPassword(password, salt)

        // 2. Generate ephemeral X25519 keypair
        val (ephemeralPrivate, ephemeralPublic) = generateX25519KeyPair()

        // 3. Decode ENCLAVE public key and compute shared secret
        val enclavePublicKey = Base64.decode(enclavePublicKeyBase64, Base64.NO_WRAP)
        val sharedSecret = x25519SharedSecret(ephemeralPrivate, enclavePublicKey)

        // 4. Derive encryption key with NEW context for enclave
        val encryptionKey = deriveEncryptionKey(sharedSecret, ENCLAVE_AUTH_CONTEXT)

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

    /**
     * Encrypt arbitrary data to an X25519 public key.
     *
     * Uses X25519 key exchange + HKDF + ChaCha20-Poly1305 for authenticated encryption.
     * This is used to encrypt data to an attested enclave public key (e.g., PIN during enrollment).
     *
     * @param plaintext Data to encrypt
     * @param publicKeyBase64 Target X25519 public key (Base64)
     * @param context HKDF context string for key derivation (default: "enclave-encryption-v1")
     * @return EncryptedData containing ciphertext, ephemeral public key, and nonce
     */
    fun encryptToPublicKey(
        plaintext: ByteArray,
        publicKeyBase64: String,
        context: String = "enclave-encryption-v1"
    ): EncryptedData {
        // 1. Generate ephemeral X25519 keypair
        val (ephemeralPrivate, ephemeralPublic) = generateX25519KeyPair()

        // 2. Decode target public key and compute shared secret
        val targetPublicKey = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
        val sharedSecret = x25519SharedSecret(ephemeralPrivate, targetPublicKey)

        // 3. Derive encryption key
        val encryptionKey = deriveEncryptionKey(sharedSecret, context)

        // 4. Encrypt plaintext
        val (ciphertext, nonce) = chaChaEncrypt(plaintext, encryptionKey)

        // Clear sensitive data
        ephemeralPrivate.fill(0)
        sharedSecret.fill(0)
        encryptionKey.fill(0)

        return EncryptedData(
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
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

        // PURPOSE_AGREE_KEY requires API 31+
        val purposes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_AGREE_KEY
        } else {
            KeyProperties.PURPOSE_SIGN
        }

        val parameterSpec = KeyGenParameterSpec.Builder(keyAlias, purposes)
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

        // PURPOSE_AGREE_KEY requires API 31+
        val purposes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_AGREE_KEY
        } else {
            KeyProperties.PURPOSE_SIGN
        }

        val builder = KeyGenParameterSpec.Builder(keyAlias, purposes)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        // setUserAuthenticationParameters requires API 30+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        val parameterSpec = builder.build()

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
     * Uses the system's best available entropy source
     */
    fun randomBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    /**
     * Generate random bytes as SecureByteArray (auto-clearing)
     */
    fun secureRandomBytes(count: Int): SecureByteArray {
        return SecureByteArray.random(count)
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

/**
 * Result of encrypting arbitrary data to a public key
 */
data class EncryptedData(
    val ciphertext: String,             // Base64
    val ephemeralPublicKey: String,     // Base64
    val nonce: String                   // Base64
)
