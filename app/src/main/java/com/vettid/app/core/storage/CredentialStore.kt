package com.vettid.app.core.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import android.util.Base64
import com.vettid.app.core.network.CredentialPackage
import com.vettid.app.core.network.LedgerAuthToken
import com.vettid.app.core.network.LegacyCredentialPackage
import com.vettid.app.core.network.LAT
import com.vettid.app.core.network.LegacyLedgerAuthToken
import com.vettid.app.core.network.NatsConnectionInfo
import com.vettid.app.core.network.NatsTopics
import com.vettid.app.core.network.VaultBootstrap
import com.vettid.app.core.network.TransactionKey
import com.vettid.app.core.network.TransactionKeyInfo
import com.vettid.app.core.network.TransactionKeyPublic
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for VettID credentials using EncryptedSharedPreferences
 *
 * Storage model (per API-STATUS.md):
 * - encrypted_blob: Opaque blob from server (cannot decrypt locally)
 * - UTK pool: X25519 public keys for encrypting passwords
 * - LAT: Ledger Auth Token for verifying server authenticity
 * - Password salt: For Argon2id hashing
 *
 * Note: CEK and LTK are owned by the Ledger, not stored locally
 */
@Singleton
class CredentialStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "vettid_credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_USER_GUID = "user_guid"
        private const val KEY_CREDENTIAL_ID = "credential_id"
        private const val KEY_ENCRYPTED_BLOB = "encrypted_blob"
        private const val KEY_EPHEMERAL_PUBLIC_KEY = "ephemeral_public_key"
        private const val KEY_NONCE = "nonce"
        private const val KEY_CEK_VERSION = "cek_version"
        private const val KEY_LAT_ID = "lat_id"
        private const val KEY_LAT_TOKEN = "lat_token"
        private const val KEY_LAT_VERSION = "lat_version"
        private const val KEY_UTK_POOL = "utk_pool"
        private const val KEY_PASSWORD_SALT = "password_salt"
        private const val KEY_CREATED_AT = "created_at"
        private const val KEY_LAST_USED_AT = "last_used_at"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_CONNECTION_KEYS = "connection_keys"
        // Vault credential keys (separate from vault services)
        private const val KEY_VAULT_CREDENTIAL_SALT = "vault_credential_salt"
        private const val KEY_VAULT_ACTIVE = "vault_active"
        // NATS connection keys
        private const val KEY_NATS_ENDPOINT = "nats_endpoint"
        private const val KEY_NATS_CREDENTIALS = "nats_credentials"
        private const val KEY_NATS_OWNER_SPACE = "nats_owner_space"
        private const val KEY_NATS_MESSAGE_SPACE = "nats_message_space"
        private const val KEY_NATS_TOPIC_SEND = "nats_topic_send"
        private const val KEY_NATS_TOPIC_RECEIVE = "nats_topic_receive"
        private const val KEY_NATS_STORED_AT = "nats_stored_at"
        private const val KEY_NATS_CA_CERT = "nats_ca_cert"
        // E2E session keys
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_SESSION_KEY = "session_key"
        private const val KEY_SESSION_PUBLIC_KEY = "session_public_key"
        private const val KEY_SESSION_EXPIRES_AT = "session_expires_at"
        // Bootstrap credential tracking
        private const val KEY_NATS_BOOTSTRAP_TOPIC = "nats_bootstrap_topic"
        private const val KEY_NATS_CREDENTIAL_ID = "nats_credential_id"
        private const val KEY_NATS_CREDENTIALS_EXPIRY = "nats_credentials_expiry"
        // Nitro Enclave credential keys
        private const val KEY_SEALED_CREDENTIAL = "sealed_credential"
        private const val KEY_ENCLAVE_PUBLIC_KEY = "enclave_public_key"
        private const val KEY_BACKUP_KEY = "backup_key"
        private const val KEY_IS_ENCLAVE_FORMAT = "is_enclave_format"
        // PCR version tracking (for enclave update compatibility)
        private const val KEY_PCR_VERSION = "pcr_version"
        private const val KEY_PCR0_HASH = "pcr0_hash"
        private const val KEY_ENROLLMENT_PCR_VERSION = "enrollment_pcr_version"
        // Emergency recovery (for enclave migration disaster scenarios)
        private const val KEY_EMERGENCY_RECOVERY_PUBLIC_KEY = "emergency_recovery_public_key"
        // Offline mode
        private const val KEY_OFFLINE_MODE = "offline_mode"
        // Credential key pair (for enrollment public key in secrets)
        private const val KEY_CREDENTIAL_PUBLIC_KEY = "credential_public_key"
        private const val KEY_CREDENTIAL_PRIVATE_KEY = "credential_private_key"
    }

    // MARK: - Credential Storage

    /**
     * Store credential package from enrollment or auth response
     */
    fun storeCredentialPackage(
        credentialPackage: LegacyCredentialPackage,
        passwordSalt: String? = null
    ) {
        encryptedPrefs.edit().apply {
            putString(KEY_USER_GUID, credentialPackage.userGuid)
            putString(KEY_ENCRYPTED_BLOB, credentialPackage.encryptedBlob)
            putInt(KEY_CEK_VERSION, credentialPackage.cekVersion)
            putString(KEY_LAT_ID, credentialPackage.ledgerAuthToken.latId)
            putString(KEY_LAT_TOKEN, credentialPackage.ledgerAuthToken.token)
            putInt(KEY_LAT_VERSION, credentialPackage.ledgerAuthToken.version)
            putString(KEY_UTK_POOL, gson.toJson(credentialPackage.transactionKeys))
            putLong(KEY_LAST_USED_AT, System.currentTimeMillis())

            // Only set password salt on initial enrollment
            passwordSalt?.let { putString(KEY_PASSWORD_SALT, it) }

            // Set created_at only if not already set
            if (!encryptedPrefs.contains(KEY_CREATED_AT)) {
                putLong(KEY_CREATED_AT, System.currentTimeMillis())
            }
        }.apply()
    }

    /**
     * Get stored credential for display/use
     */
    fun getStoredCredential(): StoredCredential? {
        val userGuid = encryptedPrefs.getString(KEY_USER_GUID, null) ?: return null

        return StoredCredential(
            userGuid = userGuid,
            encryptedBlob = encryptedPrefs.getString(KEY_ENCRYPTED_BLOB, "") ?: "",
            cekVersion = encryptedPrefs.getInt(KEY_CEK_VERSION, 0),
            latId = encryptedPrefs.getString(KEY_LAT_ID, "") ?: "",
            latToken = encryptedPrefs.getString(KEY_LAT_TOKEN, "") ?: "",
            latVersion = encryptedPrefs.getInt(KEY_LAT_VERSION, 0),
            passwordSalt = encryptedPrefs.getString(KEY_PASSWORD_SALT, "") ?: "",
            createdAt = encryptedPrefs.getLong(KEY_CREATED_AT, 0),
            lastUsedAt = encryptedPrefs.getLong(KEY_LAST_USED_AT, 0),
            pcrVersion = getCurrentPcrVersion(),
            pcr0Hash = encryptedPrefs.getString(KEY_PCR0_HASH, null),
            isNitroCredential = encryptedPrefs.getBoolean("is_nitro_credential", false)
        )
    }

    /**
     * Check if a credential is stored
     */
    fun hasStoredCredential(): Boolean {
        return encryptedPrefs.contains(KEY_USER_GUID)
    }

    /**
     * Get user GUID
     */
    fun getUserGuid(): String? {
        return encryptedPrefs.getString(KEY_USER_GUID, null)
    }

    // MARK: - Encrypted Blob

    /**
     * Get current encrypted blob for auth requests
     */
    fun getEncryptedBlob(): String? {
        return encryptedPrefs.getString(KEY_ENCRYPTED_BLOB, null)
    }

    /**
     * Get current CEK version
     */
    fun getCekVersion(): Int {
        return encryptedPrefs.getInt(KEY_CEK_VERSION, 0)
    }

    // MARK: - LAT (Ledger Auth Token)

    /**
     * Get stored LAT token for verification
     */
    fun getStoredLatToken(): String? {
        return encryptedPrefs.getString(KEY_LAT_TOKEN, null)
    }

    /**
     * Verify received LAT matches stored LAT (phishing protection)
     */
    fun verifyLat(receivedLat: LegacyLedgerAuthToken): Boolean {
        val storedToken = encryptedPrefs.getString(KEY_LAT_TOKEN, null) ?: return false
        val storedLatId = encryptedPrefs.getString(KEY_LAT_ID, null) ?: return false

        // Constant-time comparison
        return receivedLat.latId == storedLatId &&
               receivedLat.token.lowercase() == storedToken.lowercase()
    }

    /**
     * Update LAT after successful auth (LAT rotation)
     */
    fun updateLat(newLat: LegacyLedgerAuthToken) {
        encryptedPrefs.edit().apply {
            putString(KEY_LAT_ID, newLat.latId)
            putString(KEY_LAT_TOKEN, newLat.token)
            putInt(KEY_LAT_VERSION, newLat.version)
            putLong(KEY_LAST_USED_AT, System.currentTimeMillis())
        }.apply()
    }

    // MARK: - UTK Pool (User Transaction Keys)

    /**
     * Get all UTKs in the pool
     */
    fun getUtkPool(): List<TransactionKeyInfo> {
        val json = encryptedPrefs.getString(KEY_UTK_POOL, null) ?: return emptyList()
        return try {
            gson.fromJson(json, Array<TransactionKeyInfo>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get a specific UTK by ID
     */
    fun getUtk(keyId: String): TransactionKeyInfo? {
        return getUtkPool().find { it.keyId == keyId }
    }

    /**
     * Remove a used UTK from the pool
     */
    fun removeUtk(keyId: String) {
        val pool = getUtkPool().toMutableList()
        pool.removeAll { it.keyId == keyId }
        encryptedPrefs.edit()
            .putString(KEY_UTK_POOL, gson.toJson(pool))
            .apply()
    }

    /**
     * Add new UTKs to the pool (after replenishment)
     */
    fun addUtks(newKeys: List<TransactionKeyInfo>) {
        val pool = getUtkPool().toMutableList()
        pool.addAll(newKeys)
        encryptedPrefs.edit()
            .putString(KEY_UTK_POOL, gson.toJson(pool))
            .apply()
    }

    /**
     * Get count of available UTKs
     */
    fun getUtkCount(): Int {
        return getUtkPool().size
    }

    // MARK: - Auth Token (for API calls)

    /**
     * Store auth token for API calls (JWT from Cognito or session token)
     */
    fun setAuthToken(token: String) {
        encryptedPrefs.edit()
            .putString(KEY_AUTH_TOKEN, token)
            .apply()
    }

    /**
     * Get auth token for API calls
     */
    fun getAuthToken(): String? {
        return encryptedPrefs.getString(KEY_AUTH_TOKEN, null)
    }

    /**
     * Clear auth token (on logout)
     */
    fun clearAuthToken() {
        encryptedPrefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .apply()
    }

    // MARK: - Connection Keys (for per-connection encryption)

    /**
     * Store a connection encryption key
     */
    fun storeConnectionKey(connectionId: String, key: ByteArray) {
        val keys = getConnectionKeysMap().toMutableMap()
        keys[connectionId] = Base64.encodeToString(key, Base64.NO_WRAP)
        encryptedPrefs.edit()
            .putString(KEY_CONNECTION_KEYS, gson.toJson(keys))
            .apply()
    }

    /**
     * Get a connection encryption key
     */
    fun getConnectionKey(connectionId: String): ByteArray? {
        val keys = getConnectionKeysMap()
        return keys[connectionId]?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    /**
     * Delete a connection encryption key
     */
    fun deleteConnectionKey(connectionId: String) {
        val keys = getConnectionKeysMap().toMutableMap()
        keys.remove(connectionId)
        encryptedPrefs.edit()
            .putString(KEY_CONNECTION_KEYS, gson.toJson(keys))
            .apply()
    }

    private fun getConnectionKeysMap(): Map<String, String> {
        val json = encryptedPrefs.getString(KEY_CONNECTION_KEYS, null) ?: return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(json, Map::class.java) as Map<String, String>
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // MARK: - Password Salt

    /**
     * Get password salt for Argon2
     */
    fun getPasswordSalt(): String? {
        return encryptedPrefs.getString(KEY_PASSWORD_SALT, null)
    }

    /**
     * Store password salt (set during enrollment)
     */
    fun setPasswordSalt(saltBase64: String) {
        encryptedPrefs.edit()
            .putString(KEY_PASSWORD_SALT, saltBase64)
            .apply()
    }

    // MARK: - New Vault Services API Support

    /**
     * Store credential package from finalize response (Nitro Enclave format).
     */
    fun storeCredentialPackage(
        credentialPackage: CredentialPackage,
        passwordSalt: ByteArray
    ) {
        // Convert TransactionKey to TransactionKeyInfo for backward compatibility
        val keyInfoList = credentialPackage.transactionKeys.map { key ->
            TransactionKeyInfo(
                keyId = key.keyId,
                publicKey = key.publicKey,
                algorithm = key.algorithm
            )
        }

        encryptedPrefs.edit().apply {
            putString(KEY_USER_GUID, credentialPackage.userGuid)
            putString(KEY_CREDENTIAL_ID, credentialPackage.credentialId)

            // Store sealed credential (Nitro Enclave format)
            putString(KEY_SEALED_CREDENTIAL, credentialPackage.sealedCredential)
            putString(KEY_ENCLAVE_PUBLIC_KEY, credentialPackage.enclavePublicKey)
            putString(KEY_BACKUP_KEY, credentialPackage.backupKey)
            putBoolean(KEY_IS_ENCLAVE_FORMAT, true)

            // LedgerAuthToken uses token directly (lat_xxx format), no separate latId
            putString(KEY_LAT_ID, credentialPackage.ledgerAuthToken.token) // Use token as ID
            putString(KEY_LAT_TOKEN, credentialPackage.ledgerAuthToken.token)
            putInt(KEY_LAT_VERSION, credentialPackage.ledgerAuthToken.version)
            putString(KEY_UTK_POOL, gson.toJson(keyInfoList))
            putString(KEY_PASSWORD_SALT, Base64.encodeToString(passwordSalt, Base64.NO_WRAP))
            putLong(KEY_CREATED_AT, System.currentTimeMillis())
            putLong(KEY_LAST_USED_AT, System.currentTimeMillis())
        }.apply()
    }

    /**
     * Store credential blob from new vault services enrollment (legacy signature)
     */
    fun storeCredentialBlob(
        userGuid: String,
        encryptedBlob: String,
        cekVersion: Int,
        lat: LAT,
        transactionKeys: List<TransactionKeyPublic>,
        passwordSalt: ByteArray
    ) {
        // Convert TransactionKeyPublic to TransactionKeyInfo for backward compatibility
        val keyInfoList = transactionKeys.map { key ->
            TransactionKeyInfo(
                keyId = key.keyId,
                publicKey = key.publicKey,
                algorithm = key.algorithm
            )
        }

        encryptedPrefs.edit().apply {
            putString(KEY_USER_GUID, userGuid)
            putString(KEY_ENCRYPTED_BLOB, encryptedBlob)
            putInt(KEY_CEK_VERSION, cekVersion)
            putString(KEY_LAT_ID, lat.latId)
            putString(KEY_LAT_TOKEN, lat.token)
            putInt(KEY_LAT_VERSION, lat.version)
            putString(KEY_UTK_POOL, gson.toJson(keyInfoList))
            putString(KEY_PASSWORD_SALT, Base64.encodeToString(passwordSalt, Base64.NO_WRAP))
            putLong(KEY_CREATED_AT, System.currentTimeMillis())
            putLong(KEY_LAST_USED_AT, System.currentTimeMillis())
        }.apply()
    }

    /**
     * Verify received LAT from new API format
     */
    fun verifyLat(receivedLat: LAT): Boolean {
        val storedToken = encryptedPrefs.getString(KEY_LAT_TOKEN, null) ?: return false
        val storedLatId = encryptedPrefs.getString(KEY_LAT_ID, null) ?: return false

        return receivedLat.latId == storedLatId &&
               receivedLat.token.lowercase() == storedToken.lowercase()
    }

    /**
     * Update LAT after successful auth (new API format)
     */
    fun updateLat(newLat: LAT) {
        encryptedPrefs.edit().apply {
            putString(KEY_LAT_ID, newLat.latId)
            putString(KEY_LAT_TOKEN, newLat.token)
            putInt(KEY_LAT_VERSION, newLat.version)
            putLong(KEY_LAST_USED_AT, System.currentTimeMillis())
        }.apply()
    }

    /**
     * Update credential blob after auth (rotated CEK)
     */
    fun updateCredentialBlob(
        encryptedBlob: String,
        cekVersion: Int,
        newLat: LAT,
        newTransactionKeys: List<TransactionKeyPublic>?
    ) {
        encryptedPrefs.edit().apply {
            putString(KEY_ENCRYPTED_BLOB, encryptedBlob)
            putInt(KEY_CEK_VERSION, cekVersion)
            putString(KEY_LAT_ID, newLat.latId)
            putString(KEY_LAT_TOKEN, newLat.token)
            putInt(KEY_LAT_VERSION, newLat.version)
            putLong(KEY_LAST_USED_AT, System.currentTimeMillis())

            // Add new transaction keys if provided
            if (newTransactionKeys != null) {
                val currentPool = getUtkPool().toMutableList()
                val newKeyInfoList = newTransactionKeys.map { key ->
                    TransactionKeyInfo(
                        keyId = key.keyId,
                        publicKey = key.publicKey,
                        algorithm = key.algorithm
                    )
                }
                currentPool.addAll(newKeyInfoList)
                putString(KEY_UTK_POOL, gson.toJson(currentPool))
            }
        }.apply()
    }

    /**
     * Get password salt as ByteArray
     */
    fun getPasswordSaltBytes(): ByteArray? {
        val saltBase64 = encryptedPrefs.getString(KEY_PASSWORD_SALT, null) ?: return null
        return try {
            Base64.decode(saltBase64, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    // MARK: - Credential Backup/Recovery

    /**
     * Get credential blob as ByteArray for backup
     */
    fun getCredentialBlob(): ByteArray? {
        val blobBase64 = encryptedPrefs.getString(KEY_ENCRYPTED_BLOB, null) ?: return null
        return try {
            Base64.decode(blobBase64, Base64.NO_WRAP)
        } catch (e: Exception) {
            // If not base64, return as UTF-8 bytes
            blobBase64.toByteArray(Charsets.UTF_8)
        }
    }

    /**
     * Store credential blob from recovery (ByteArray version)
     */
    fun storeCredentialBlob(blob: ByteArray) {
        val blobBase64 = Base64.encodeToString(blob, Base64.NO_WRAP)
        encryptedPrefs.edit()
            .putString(KEY_ENCRYPTED_BLOB, blobBase64)
            .putLong(KEY_LAST_USED_AT, System.currentTimeMillis())
            .apply()
    }

    // MARK: - Vault Credential Storage (separate from vault services credential)

    /**
     * Store vault credential salt (for vault-level authentication)
     * This is separate from the vault services password salt.
     */
    fun setVaultCredentialSalt(salt: ByteArray) {
        encryptedPrefs.edit()
            .putString(KEY_VAULT_CREDENTIAL_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .apply()
    }

    /**
     * Get vault credential salt
     */
    fun getVaultCredentialSalt(): ByteArray? {
        val saltBase64 = encryptedPrefs.getString(KEY_VAULT_CREDENTIAL_SALT, null) ?: return null
        return try {
            Base64.decode(saltBase64, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if vault credential is configured
     */
    fun hasVaultCredential(): Boolean {
        return encryptedPrefs.contains(KEY_VAULT_CREDENTIAL_SALT)
    }

    /**
     * Set vault active state (after successful authentication)
     */
    fun setVaultActive(active: Boolean) {
        encryptedPrefs.edit()
            .putBoolean(KEY_VAULT_ACTIVE, active)
            .apply()
    }

    /**
     * Check if vault is active
     */
    fun isVaultActive(): Boolean {
        return encryptedPrefs.getBoolean(KEY_VAULT_ACTIVE, false)
    }

    // MARK: - NATS Connection Storage

    /**
     * Store NATS connection info from enrollment finalize.
     * These credentials are valid for 24 hours.
     */
    fun storeNatsConnection(natsConnection: NatsConnectionInfo) {
        encryptedPrefs.edit().apply {
            putString(KEY_NATS_ENDPOINT, natsConnection.endpoint)
            putString(KEY_NATS_CREDENTIALS, natsConnection.credentials)
            putString(KEY_NATS_OWNER_SPACE, natsConnection.ownerSpace)
            putString(KEY_NATS_MESSAGE_SPACE, natsConnection.messageSpace)
            natsConnection.topics?.let { topics ->
                putString(KEY_NATS_TOPIC_SEND, topics.sendToVault)
                putString(KEY_NATS_TOPIC_RECEIVE, topics.receiveFromVault)
            }
            natsConnection.caCertificate?.let { caCert ->
                putString(KEY_NATS_CA_CERT, caCert)
            }
            putLong(KEY_NATS_STORED_AT, System.currentTimeMillis())
        }.apply()
    }

    /**
     * Store NATS vault bootstrap info from enrollment finalize.
     * VaultBootstrap contains connection info for NATS.
     * Uses commit() (synchronous) to ensure critical data is persisted.
     *
     * Note: As of API change #49, credentials are no longer in finalize response.
     * Use credentials from nats-bootstrap endpoint instead.
     */
    fun storeNatsConnection(vaultBootstrap: VaultBootstrap) {
        val success = encryptedPrefs.edit().apply {
            putString(KEY_NATS_ENDPOINT, vaultBootstrap.endpoint)
            // Only store credentials if present (removed from finalize in API #49)
            vaultBootstrap.credentials?.let { creds ->
                putString(KEY_NATS_CREDENTIALS, creds)
            }
            putString(KEY_NATS_OWNER_SPACE, vaultBootstrap.ownerSpace)
            putString(KEY_NATS_MESSAGE_SPACE, vaultBootstrap.messageSpace)
            vaultBootstrap.caCertificate?.let { caCert ->
                putString(KEY_NATS_CA_CERT, caCert)
            }
            // Store bootstrap topic to mark these as bootstrap (limited) credentials
            vaultBootstrap.bootstrapTopic?.let { topic ->
                putString(KEY_NATS_BOOTSTRAP_TOPIC, topic)
            }
            // Store TTL if provided (removed from finalize in API #49)
            vaultBootstrap.credentialsTtlSeconds?.let { ttl ->
                val expiryMs = System.currentTimeMillis() + (ttl * 1000L)
                putLong(KEY_NATS_CREDENTIALS_EXPIRY, expiryMs)
            }
            putLong(KEY_NATS_STORED_AT, System.currentTimeMillis())
        }.commit()  // Use commit() for critical NATS credentials
        if (success) {
            android.util.Log.i("CredentialStore", "Stored NATS bootstrap info for ${vaultBootstrap.endpoint} (${vaultBootstrap.ownerSpace})")
        } else {
            android.util.Log.e("CredentialStore", "CRITICAL: Failed to persist NATS bootstrap info!")
        }
    }

    /**
     * Store full NATS credentials from app.bootstrap response.
     * This replaces bootstrap credentials with full credentials.
     *
     * @param credentials Full NATS credentials (.creds file content)
     * @param ownerSpace OwnerSpace ID (optional, keeps existing if null)
     * @param messageSpace MessageSpace ID (optional, keeps existing if null)
     * @param credentialId Credential ID for tracking
     * @param ttlSeconds Time-to-live in seconds
     */
    fun storeFullNatsCredentials(
        credentials: String,
        ownerSpace: String? = null,
        messageSpace: String? = null,
        credentialId: String? = null,
        ttlSeconds: Long? = null
    ) {
        encryptedPrefs.edit().apply {
            putString(KEY_NATS_CREDENTIALS, credentials)
            // Update ownerSpace/messageSpace if provided
            ownerSpace?.let { putString(KEY_NATS_OWNER_SPACE, it) }
            messageSpace?.let { putString(KEY_NATS_MESSAGE_SPACE, it) }
            // Store credential ID if provided
            credentialId?.let { putString(KEY_NATS_CREDENTIAL_ID, it) }
            // Store expiry time
            ttlSeconds?.let { ttl ->
                val expiryMs = System.currentTimeMillis() + (ttl * 1000L)
                putLong(KEY_NATS_CREDENTIALS_EXPIRY, expiryMs)
            }
            // Remove bootstrap topic marker - these are full credentials now
            remove(KEY_NATS_BOOTSTRAP_TOPIC)
            putLong(KEY_NATS_STORED_AT, System.currentTimeMillis())
        }.apply()
        android.util.Log.i("CredentialStore", "Stored full NATS credentials (id: $credentialId)")
    }

    /**
     * Store credential from Nitro Enclave enrollment flow.
     * This stores the encrypted credential blob, user info, UTKs, and PCR version.
     * Uses commit() (synchronous) to ensure critical data is persisted before app close.
     *
     * @param encryptedCredential Encrypted credential blob from vault-manager
     * @param credentialGuid Unique identifier for the credential
     * @param userGuid User's GUID
     * @param passwordSalt Salt used for password hashing
     * @param utks User Transaction Keys for future operations
     * @param pcrVersion PCR version used during enrollment (for enclave update compatibility)
     * @param pcr0Hash PCR0 hash at time of enrollment (for verification)
     */
    fun storeNitroCredential(
        encryptedCredential: String,
        credentialGuid: String,
        userGuid: String,
        passwordSalt: ByteArray,
        utks: List<com.vettid.app.core.nats.UtkInfo>,
        pcrVersion: String? = null,
        pcr0Hash: String? = null
    ) {
        // Convert UtkInfo to TransactionKeyInfo for storage
        val keyInfoList = utks.map { utk ->
            TransactionKeyInfo(
                keyId = utk.utkId,  // Use utkId which handles both id and key_id formats
                publicKey = utk.publicKey,
                algorithm = "X25519"
            )
        }

        val success = encryptedPrefs.edit().apply {
            putString(KEY_USER_GUID, userGuid)
            putString(KEY_CREDENTIAL_ID, credentialGuid)
            putString(KEY_ENCRYPTED_BLOB, encryptedCredential)
            putString(KEY_PASSWORD_SALT, Base64.encodeToString(passwordSalt, Base64.NO_WRAP))
            putString(KEY_UTK_POOL, gson.toJson(keyInfoList))
            putLong(KEY_LAST_USED_AT, System.currentTimeMillis())
            // Mark as Nitro enrollment
            putBoolean("is_nitro_credential", true)
            // Store PCR version for enclave update compatibility
            pcrVersion?.let { putString(KEY_ENROLLMENT_PCR_VERSION, it) }
            pcr0Hash?.let { putString(KEY_PCR0_HASH, it) }
        }.commit()  // Use commit() for critical enrollment data

        if (success) {
            android.util.Log.i("CredentialStore", "Stored Nitro credential: $credentialGuid for user $userGuid with ${utks.size} UTKs, PCR version: $pcrVersion")
        } else {
            android.util.Log.e("CredentialStore", "CRITICAL: Failed to persist Nitro credential!")
        }
    }

    /**
     * Store enclave public key for PIN encryption during unlock.
     * This is the X25519 public key from attestation, used to encrypt PINs.
     * Uses commit() (synchronous) instead of apply() to ensure critical data is persisted
     * before the app can be closed/killed.
     */
    fun storeEnclavePublicKey(publicKey: ByteArray) {
        val success = encryptedPrefs.edit()
            .putString(KEY_ENCLAVE_PUBLIC_KEY, Base64.encodeToString(publicKey, Base64.NO_WRAP))
            .commit()  // Use commit() for critical data - must persist before app close
        if (success) {
            android.util.Log.i("CredentialStore", "Stored enclave public key (${publicKey.size} bytes)")
        } else {
            android.util.Log.e("CredentialStore", "CRITICAL: Failed to persist enclave public key!")
        }
    }

    /**
     * Get enclave public key for PIN encryption.
     */
    fun getEnclavePublicKey(): ByteArray? {
        val encoded = encryptedPrefs.getString(KEY_ENCLAVE_PUBLIC_KEY, null) ?: return null
        return try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("CredentialStore", "Failed to decode enclave public key", e)
            null
        }
    }

    /**
     * Check if enclave public key is stored.
     */
    fun hasEnclavePublicKey(): Boolean {
        return encryptedPrefs.contains(KEY_ENCLAVE_PUBLIC_KEY)
    }

    /**
     * Get the bootstrap topic if stored.
     * Presence of this value indicates bootstrap (limited) credentials.
     */
    fun getNatsBootstrapTopic(): String? {
        return encryptedPrefs.getString(KEY_NATS_BOOTSTRAP_TOPIC, null)
    }

    /**
     * Check if current NATS credentials are bootstrap (limited) credentials.
     */
    fun hasBootstrapCredentials(): Boolean {
        return hasNatsConnection() && getNatsBootstrapTopic() != null
    }

    /**
     * Check if current NATS credentials are full credentials.
     */
    fun hasFullNatsCredentials(): Boolean {
        return hasNatsConnection() && getNatsBootstrapTopic() == null
    }

    /**
     * Get NATS credential ID.
     */
    fun getNatsCredentialId(): String? {
        return encryptedPrefs.getString(KEY_NATS_CREDENTIAL_ID, null)
    }

    /**
     * Get stored NATS connection info.
     */
    fun getNatsConnection(): NatsConnectionInfo? {
        val endpoint = encryptedPrefs.getString(KEY_NATS_ENDPOINT, null) ?: return null
        val credentials = encryptedPrefs.getString(KEY_NATS_CREDENTIALS, null) ?: return null
        val ownerSpace = encryptedPrefs.getString(KEY_NATS_OWNER_SPACE, null) ?: return null
        val messageSpace = encryptedPrefs.getString(KEY_NATS_MESSAGE_SPACE, null) ?: return null

        val sendTopic = encryptedPrefs.getString(KEY_NATS_TOPIC_SEND, null)
        val receiveTopic = encryptedPrefs.getString(KEY_NATS_TOPIC_RECEIVE, null)
        val topics = if (sendTopic != null && receiveTopic != null) {
            NatsTopics(sendToVault = sendTopic, receiveFromVault = receiveTopic)
        } else null

        return NatsConnectionInfo(
            endpoint = endpoint,
            credentials = credentials,
            ownerSpace = ownerSpace,
            messageSpace = messageSpace,
            topics = topics
        )
    }

    /**
     * Check if NATS credentials are stored.
     */
    fun hasNatsConnection(): Boolean {
        return encryptedPrefs.contains(KEY_NATS_CREDENTIALS)
    }

    /**
     * Get NATS credentials (the credential file content).
     */
    fun getNatsCredentials(): String? {
        return encryptedPrefs.getString(KEY_NATS_CREDENTIALS, null)
    }

    /**
     * Get NATS endpoint URL.
     */
    fun getNatsEndpoint(): String? {
        return encryptedPrefs.getString(KEY_NATS_ENDPOINT, null)
    }

    /**
     * Get NATS owner space ID.
     */
    fun getNatsOwnerSpace(): String? {
        return encryptedPrefs.getString(KEY_NATS_OWNER_SPACE, null)
    }

    /**
     * Parse NATS credential file to extract JWT and seed.
     * The credential file format contains:
     * - -----BEGIN NATS USER JWT----- / -----END NATS USER JWT-----
     * - -----BEGIN USER NKEY SEED----- / -----END USER NKEY SEED-----
     */
    fun parseNatsCredentialFile(credentialFile: String): Pair<String, String>? {
        try {
            // Extract JWT
            val jwtStart = credentialFile.indexOf("-----BEGIN NATS USER JWT-----")
            val jwtEnd = credentialFile.indexOf("-----END NATS USER JWT-----")
            if (jwtStart == -1 || jwtEnd == -1) return null

            val jwtContent = credentialFile.substring(
                jwtStart + "-----BEGIN NATS USER JWT-----".length,
                jwtEnd
            ).trim()

            // Extract NKEY seed
            val seedStart = credentialFile.indexOf("-----BEGIN USER NKEY SEED-----")
            val seedEnd = credentialFile.indexOf("-----END USER NKEY SEED-----")
            if (seedStart == -1 || seedEnd == -1) return null

            val seedContent = credentialFile.substring(
                seedStart + "-----BEGIN USER NKEY SEED-----".length,
                seedEnd
            ).trim()

            return Pair(jwtContent, seedContent)
        } catch (e: Exception) {
            android.util.Log.e("CredentialStore", "Failed to parse NATS credential file", e)
            return null
        }
    }

    /**
     * Get parsed NATS JWT and seed for NatsClient connection.
     */
    fun getParsedNatsCredentials(): Pair<String, String>? {
        val credentialFile = getNatsCredentials() ?: return null
        return parseNatsCredentialFile(credentialFile)
    }

    /**
     * Check if NATS credentials are still valid (less than 24 hours old).
     */
    fun areNatsCredentialsValid(): Boolean {
        val storedAt = encryptedPrefs.getLong(KEY_NATS_STORED_AT, 0)
        if (storedAt == 0L) return false

        val twentyFourHoursMs = 24 * 60 * 60 * 1000L
        return System.currentTimeMillis() - storedAt < twentyFourHoursMs
    }

    /**
     * Get NATS credentials expiry timestamp.
     * Uses stored expiry time from TTL if available, otherwise defaults to 24 hours after stored.
     * Returns null if no credentials are stored.
     */
    fun getNatsCredentialsExpiryTime(): Long? {
        // First check if explicit expiry is stored
        val explicitExpiry = encryptedPrefs.getLong(KEY_NATS_CREDENTIALS_EXPIRY, 0L)
        if (explicitExpiry > 0) return explicitExpiry

        // Fall back to 24 hours after stored
        val storedAt = encryptedPrefs.getLong(KEY_NATS_STORED_AT, 0)
        if (storedAt == 0L) return null

        val twentyFourHoursMs = 24 * 60 * 60 * 1000L
        return storedAt + twentyFourHoursMs
    }

    /**
     * Clear stored NATS connection info.
     */
    fun clearNatsConnection() {
        encryptedPrefs.edit().apply {
            remove(KEY_NATS_ENDPOINT)
            remove(KEY_NATS_CREDENTIALS)
            remove(KEY_NATS_OWNER_SPACE)
            remove(KEY_NATS_MESSAGE_SPACE)
            remove(KEY_NATS_TOPIC_SEND)
            remove(KEY_NATS_TOPIC_RECEIVE)
            remove(KEY_NATS_STORED_AT)
            remove(KEY_NATS_CA_CERT)
            remove(KEY_NATS_BOOTSTRAP_TOPIC)
            remove(KEY_NATS_CREDENTIAL_ID)
            remove(KEY_NATS_CREDENTIALS_EXPIRY)
        }.apply()
    }

    /**
     * Store NATS CA certificate (PEM format) for TLS trust.
     * This certificate is returned by the enrollment finalize response.
     */
    fun setNatsCaCertificate(caCert: String) {
        encryptedPrefs.edit()
            .putString(KEY_NATS_CA_CERT, caCert)
            .apply()
    }

    /**
     * Get stored NATS CA certificate (PEM format).
     */
    fun getNatsCaCertificate(): String? {
        return encryptedPrefs.getString(KEY_NATS_CA_CERT, null)
    }

    /**
     * Check if NATS CA certificate is stored.
     */
    fun hasNatsCaCertificate(): Boolean {
        return encryptedPrefs.contains(KEY_NATS_CA_CERT)
    }

    // MARK: - E2E Session Storage

    /**
     * Store E2E session data for app-vault encryption.
     *
     * @param sessionId Session ID from vault
     * @param sessionKey Derived session key (32 bytes)
     * @param publicKey App's session public key
     * @param expiresAt Session expiration time (millis)
     */
    fun storeSession(
        sessionId: String,
        sessionKey: ByteArray,
        publicKey: ByteArray,
        expiresAt: Long
    ) {
        encryptedPrefs.edit().apply {
            putString(KEY_SESSION_ID, sessionId)
            putString(KEY_SESSION_KEY, Base64.encodeToString(sessionKey, Base64.NO_WRAP))
            putString(KEY_SESSION_PUBLIC_KEY, Base64.encodeToString(publicKey, Base64.NO_WRAP))
            putLong(KEY_SESSION_EXPIRES_AT, expiresAt)
        }.apply()
        android.util.Log.i("CredentialStore", "E2E session stored: $sessionId, expires: $expiresAt")
    }

    /**
     * Get stored session ID.
     */
    fun getSessionId(): String? {
        return encryptedPrefs.getString(KEY_SESSION_ID, null)
    }

    /**
     * Get stored session key.
     */
    fun getSessionKey(): ByteArray? {
        val keyBase64 = encryptedPrefs.getString(KEY_SESSION_KEY, null) ?: return null
        return try {
            Base64.decode(keyBase64, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get stored session public key.
     */
    fun getSessionPublicKey(): ByteArray? {
        val keyBase64 = encryptedPrefs.getString(KEY_SESSION_PUBLIC_KEY, null) ?: return null
        return try {
            Base64.decode(keyBase64, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get session expiration time (millis).
     */
    fun getSessionExpiresAt(): Long {
        return encryptedPrefs.getLong(KEY_SESSION_EXPIRES_AT, 0)
    }

    /**
     * Check if a valid session exists.
     */
    fun hasValidSession(): Boolean {
        val sessionId = getSessionId() ?: return false
        val expiresAt = getSessionExpiresAt()
        return sessionId.isNotEmpty() && System.currentTimeMillis() < expiresAt
    }

    /**
     * Clear stored session data.
     */
    fun clearSession() {
        encryptedPrefs.edit().apply {
            remove(KEY_SESSION_ID)
            remove(KEY_SESSION_KEY)
            remove(KEY_SESSION_PUBLIC_KEY)
            remove(KEY_SESSION_EXPIRES_AT)
        }.apply()
    }

    // MARK: - PCR Version Tracking

    /**
     * Get the PCR version used during enrollment.
     * This is stored to ensure compatibility during enclave updates.
     */
    fun getEnrollmentPcrVersion(): String? {
        return encryptedPrefs.getString(KEY_ENROLLMENT_PCR_VERSION, null)
    }

    /**
     * Get the PCR0 hash stored during enrollment.
     * This can be used for verification during auth operations.
     */
    fun getEnrollmentPcr0Hash(): String? {
        return encryptedPrefs.getString(KEY_PCR0_HASH, null)
    }

    /**
     * Update PCR version after successful operation with new enclave.
     * Call this when auth succeeds with a newer PCR version.
     *
     * @param pcrVersion New PCR version that was successfully used
     * @param pcr0Hash New PCR0 hash (optional)
     */
    fun updatePcrVersion(pcrVersion: String, pcr0Hash: String? = null) {
        encryptedPrefs.edit().apply {
            putString(KEY_PCR_VERSION, pcrVersion)
            pcr0Hash?.let { putString(KEY_PCR0_HASH, it) }
        }.apply()
        android.util.Log.i("CredentialStore", "Updated PCR version to: $pcrVersion")
    }

    /**
     * Get the current PCR version (may differ from enrollment version after updates).
     * Returns enrollment version if no updates have occurred.
     */
    fun getCurrentPcrVersion(): String? {
        return encryptedPrefs.getString(KEY_PCR_VERSION, null)
            ?: encryptedPrefs.getString(KEY_ENROLLMENT_PCR_VERSION, null)
    }

    /**
     * Check if credential was enrolled with Nitro Enclave.
     */
    fun isNitroCredential(): Boolean {
        return encryptedPrefs.getBoolean("is_nitro_credential", false)
    }

    // MARK: - Emergency Recovery (Enclave Migration)

    /**
     * Get the emergency recovery public key for disaster scenarios.
     * This key is used when both old and new enclaves are unavailable.
     * Returns null if not configured (backend hasn't provided it).
     */
    fun getEmergencyRecoveryPublicKey(): String? {
        return encryptedPrefs.getString(KEY_EMERGENCY_RECOVERY_PUBLIC_KEY, null)
    }

    /**
     * Store the emergency recovery public key.
     * This is typically set during enrollment or backend config update.
     */
    fun setEmergencyRecoveryPublicKey(publicKey: String) {
        encryptedPrefs.edit()
            .putString(KEY_EMERGENCY_RECOVERY_PUBLIC_KEY, publicKey)
            .apply()
    }

    // MARK: - Offline Mode

    /**
     * Set offline mode preference.
     * When offline, the app will not connect to the vault enclave.
     */
    fun setOfflineMode(enabled: Boolean) {
        encryptedPrefs.edit()
            .putBoolean(KEY_OFFLINE_MODE, enabled)
            .apply()
    }

    /**
     * Get offline mode preference.
     */
    fun getOfflineMode(): Boolean {
        return encryptedPrefs.getBoolean(KEY_OFFLINE_MODE, false)
    }

    // MARK: - Credential Key Pair (Enrollment Public Key)

    /**
     * Store credential key pair (X25519) for enrollment public key.
     * The private key is stored locally, public key is shared via secrets.
     * Uses commit() (synchronous) for critical data.
     */
    fun storeCredentialKeyPair(publicKey: ByteArray, privateKey: ByteArray) {
        val success = encryptedPrefs.edit().apply {
            putString(KEY_CREDENTIAL_PUBLIC_KEY, Base64.encodeToString(publicKey, Base64.NO_WRAP))
            putString(KEY_CREDENTIAL_PRIVATE_KEY, Base64.encodeToString(privateKey, Base64.NO_WRAP))
        }.commit()
        if (success) {
            android.util.Log.d("CredentialStore", "Stored credential key pair (${publicKey.size} bytes public, ${privateKey.size} bytes private)")
        } else {
            android.util.Log.e("CredentialStore", "Failed to store credential key pair!")
        }
    }

    /**
     * Get credential public key (X25519).
     * This is the key shared in the user's public profile.
     */
    fun getCredentialPublicKey(): ByteArray? {
        return encryptedPrefs.getString(KEY_CREDENTIAL_PUBLIC_KEY, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    /**
     * Get credential private key (X25519).
     * This key is used to decrypt messages sent to the user.
     */
    fun getCredentialPrivateKey(): ByteArray? {
        return encryptedPrefs.getString(KEY_CREDENTIAL_PRIVATE_KEY, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    /**
     * Check if credential key pair is stored.
     */
    fun hasCredentialKeyPair(): Boolean {
        return encryptedPrefs.contains(KEY_CREDENTIAL_PUBLIC_KEY) &&
               encryptedPrefs.contains(KEY_CREDENTIAL_PRIVATE_KEY)
    }

    // MARK: - Cleanup

    /**
     * Clear all stored credential data (for logout/reset)
     */
    fun clearAll() {
        encryptedPrefs.edit().clear().apply()
    }
}

// MARK: - Data Classes

/**
 * Stored credential data (read-only view)
 */
data class StoredCredential(
    val userGuid: String,
    val encryptedBlob: String,
    val cekVersion: Int,
    val latId: String,
    val latToken: String,
    val latVersion: Int,
    val passwordSalt: String,
    val createdAt: Long,
    val lastUsedAt: Long,
    val pcrVersion: String? = null,
    val pcr0Hash: String? = null,
    val isNitroCredential: Boolean = false
)
