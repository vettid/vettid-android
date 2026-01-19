package com.vettid.app.core.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for METADATA of critical secrets.
 *
 * IMPORTANT: Critical secrets themselves are NEVER stored on the device.
 * They are stored inside the sealed Protean Credential in the vault and
 * can only be retrieved after password verification.
 *
 * This store only keeps:
 * - Metadata (name, category, description, timestamps)
 * - Access logs
 *
 * Critical secrets include:
 * - Seed phrases (crypto wallets)
 * - Private keys
 * - Signing keys
 * - Master passwords
 *
 * Access flow:
 * 1. User taps "View" on critical secret
 * 2. Password prompt (NO biometrics allowed)
 * 3. Security acknowledgment dialog
 * 4. Request from vault: credential.secret.get
 * 5. Display with 30-second countdown, then auto-clear
 */
@Singleton
class CriticalSecretMetadataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "vettid_critical_secrets_metadata",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_METADATA = "critical_secrets_metadata"
        private const val KEY_ACCESS_LOG = "access_log"
        private const val KEY_LAST_SYNCED_AT = "last_synced_at"
    }

    // MARK: - Metadata Operations

    /**
     * Get all critical secret metadata.
     * Note: This does NOT include the actual secret values.
     */
    fun getAllMetadata(): List<CriticalSecretMetadata> {
        val json = encryptedPrefs.getString(KEY_METADATA, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<CriticalSecretMetadata>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get metadata for a specific secret.
     */
    fun getMetadata(id: String): CriticalSecretMetadata? {
        return getAllMetadata().find { it.id == id }
    }

    /**
     * Store metadata for a critical secret.
     * Called after successfully adding a secret to the vault.
     *
     * @param metadata The metadata to store
     */
    fun storeMetadata(metadata: CriticalSecretMetadata) {
        val allMetadata = getAllMetadata().toMutableList()
        val existingIndex = allMetadata.indexOfFirst { it.id == metadata.id }

        if (existingIndex >= 0) {
            allMetadata[existingIndex] = metadata
        } else {
            allMetadata.add(metadata)
        }

        saveMetadata(allMetadata)
    }

    /**
     * Remove metadata for a secret.
     * Called after successfully deleting a secret from the vault.
     */
    fun removeMetadata(id: String) {
        val allMetadata = getAllMetadata().toMutableList()
        allMetadata.removeAll { it.id == id }
        saveMetadata(allMetadata)
    }

    /**
     * Update metadata after access.
     */
    fun recordAccess(id: String) {
        val allMetadata = getAllMetadata().toMutableList()
        val index = allMetadata.indexOfFirst { it.id == id }

        if (index >= 0) {
            val metadata = allMetadata[index]
            allMetadata[index] = metadata.copy(
                lastAccessedAt = System.currentTimeMillis(),
                accessCount = metadata.accessCount + 1
            )
            saveMetadata(allMetadata)
        }

        // Also add to access log
        logAccess(id)
    }

    private fun saveMetadata(metadata: List<CriticalSecretMetadata>) {
        val json = gson.toJson(metadata)
        encryptedPrefs.edit().putString(KEY_METADATA, json).apply()
    }

    // MARK: - Access Logging

    /**
     * Get access log entries for audit purposes.
     */
    fun getAccessLog(): List<CriticalSecretAccessLog> {
        val json = encryptedPrefs.getString(KEY_ACCESS_LOG, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<CriticalSecretAccessLog>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get access log for a specific secret.
     */
    fun getAccessLogForSecret(secretId: String): List<CriticalSecretAccessLog> {
        return getAccessLog().filter { it.secretId == secretId }
    }

    private fun logAccess(secretId: String) {
        val log = getAccessLog().toMutableList()
        log.add(CriticalSecretAccessLog(
            secretId = secretId,
            accessedAt = System.currentTimeMillis()
        ))

        // Keep only last 100 entries per secret
        val trimmedLog = log
            .groupBy { it.secretId }
            .flatMap { (_, entries) -> entries.takeLast(100) }
            .sortedByDescending { it.accessedAt }

        val json = gson.toJson(trimmedLog)
        encryptedPrefs.edit().putString(KEY_ACCESS_LOG, json).apply()
    }

    // MARK: - Sync

    /**
     * Import metadata from vault sync response.
     * Called when fetching list of critical secrets from vault.
     */
    fun importFromVault(metadataList: List<Map<String, Any?>>) {
        val imported = metadataList.mapNotNull { data ->
            try {
                CriticalSecretMetadata(
                    id = data["id"] as String,
                    name = data["name"] as String,
                    category = try {
                        CriticalSecretCategory.valueOf(data["category"] as String)
                    } catch (e: Exception) {
                        CriticalSecretCategory.OTHER
                    },
                    description = data["description"] as? String,
                    createdAt = (data["createdAt"] as Number).toLong(),
                    lastAccessedAt = (data["lastAccessedAt"] as? Number)?.toLong(),
                    accessCount = (data["accessCount"] as? Number)?.toInt() ?: 0
                )
            } catch (e: Exception) {
                null
            }
        }

        saveMetadata(imported)
        encryptedPrefs.edit()
            .putLong(KEY_LAST_SYNCED_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Get last sync timestamp.
     */
    fun getLastSyncedAt(): Long {
        return encryptedPrefs.getLong(KEY_LAST_SYNCED_AT, 0)
    }

    // MARK: - Clear

    /**
     * Clear all metadata.
     */
    fun clearAll() {
        encryptedPrefs.edit().clear().apply()
    }
}

// MARK: - Data Models

/**
 * Metadata for a critical secret.
 * The actual secret value is NEVER stored on device.
 */
data class CriticalSecretMetadata(
    val id: String,
    val name: String,
    val category: CriticalSecretCategory,
    val description: String?,
    val createdAt: Long,
    val lastAccessedAt: Long?,
    val accessCount: Int
)

/**
 * Categories for critical secrets.
 * These secrets require extra security measures.
 */
enum class CriticalSecretCategory(val displayName: String) {
    SEED_PHRASE("Seed Phrase"),
    PRIVATE_KEY("Private Key"),
    SIGNING_KEY("Signing Key"),
    MASTER_PASSWORD("Master Password"),
    RECOVERY_KEY("Recovery Key"),
    OTHER("Critical Secret")
}

/**
 * Log entry for critical secret access.
 */
data class CriticalSecretAccessLog(
    val secretId: String,
    val accessedAt: Long
)

/**
 * State for viewing a critical secret.
 */
sealed class CriticalSecretViewState {
    object Hidden : CriticalSecretViewState()
    object PasswordPrompt : CriticalSecretViewState()
    data class AcknowledgementRequired(
        val secretId: String,
        val secretName: String
    ) : CriticalSecretViewState()
    data class Retrieving(val progress: Float = 0f) : CriticalSecretViewState()
    data class Revealed(
        val value: String,
        val expiresInSeconds: Int  // 30-second countdown
    ) : CriticalSecretViewState()
    data class Error(val message: String) : CriticalSecretViewState()
}
