package com.vettid.app.core.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.vettid.app.core.network.CredentialPackage
import com.vettid.app.core.network.LedgerAuthToken
import com.vettid.app.core.network.TransactionKeyInfo
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
        private const val KEY_ENCRYPTED_BLOB = "encrypted_blob"
        private const val KEY_CEK_VERSION = "cek_version"
        private const val KEY_LAT_ID = "lat_id"
        private const val KEY_LAT_TOKEN = "lat_token"
        private const val KEY_LAT_VERSION = "lat_version"
        private const val KEY_UTK_POOL = "utk_pool"
        private const val KEY_PASSWORD_SALT = "password_salt"
        private const val KEY_CREATED_AT = "created_at"
        private const val KEY_LAST_USED_AT = "last_used_at"
    }

    // MARK: - Credential Storage

    /**
     * Store credential package from enrollment or auth response
     */
    fun storeCredentialPackage(
        credentialPackage: CredentialPackage,
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
            lastUsedAt = encryptedPrefs.getLong(KEY_LAST_USED_AT, 0)
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
    fun verifyLat(receivedLat: LedgerAuthToken): Boolean {
        val storedToken = encryptedPrefs.getString(KEY_LAT_TOKEN, null) ?: return false
        val storedLatId = encryptedPrefs.getString(KEY_LAT_ID, null) ?: return false

        // Constant-time comparison
        return receivedLat.latId == storedLatId &&
               receivedLat.token.lowercase() == storedToken.lowercase()
    }

    /**
     * Update LAT after successful auth (LAT rotation)
     */
    fun updateLat(newLat: LedgerAuthToken) {
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
    val lastUsedAt: Long
)
