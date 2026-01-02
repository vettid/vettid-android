package com.vettid.app.core.attestation

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages PCR (Platform Configuration Register) values for Nitro Enclave attestation.
 *
 * PCR values are SHA-384 hashes that cryptographically identify enclave code.
 * They are published by VettID for each enclave release and can be verified
 * against attestation documents to ensure we're communicating with trusted code.
 *
 * Features:
 * - Bundles default PCRs in the app (for offline operation)
 * - Fetches PCR updates from VettID's API
 * - Verifies PCR signatures using VettID's Ed25519 signing key
 * - Stores updated PCRs securely
 */
@Singleton
class PcrConfigManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "PcrConfigManager"
        private const val PREFS_NAME = "vettid_pcr_config"
        private const val KEY_CURRENT_PCRS = "current_pcrs"
        private const val KEY_LAST_UPDATE = "last_update_timestamp"
        private const val KEY_PCR_VERSION = "pcr_version"

        // VettID's Ed25519 public key for verifying PCR signatures
        // This key is embedded in the app and used to verify PCR updates
        // TODO: Replace with actual VettID signing key before production
        private const val VETTID_SIGNING_KEY_BASE64 = "PLACEHOLDER_VETTID_SIGNING_KEY"

        // Default bundled PCRs (updated with each app release)
        // These are used when the app is first installed or if updates fail
        // TODO: Replace with actual PCR values from VettID's enclave build
        private val DEFAULT_PCRS = ExpectedPcrs(
            pcr0 = "0".repeat(96), // Placeholder - 48 bytes = 96 hex chars
            pcr1 = "0".repeat(96),
            pcr2 = "0".repeat(96),
            pcr3 = null,
            version = "bundled-1.0.0",
            publishedAt = "2026-01-01T00:00:00Z"
        )

        // How often to check for PCR updates (24 hours)
        private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }

    private val gson = Gson()
    private val prefs: SharedPreferences by lazy { createSecurePrefs() }

    private fun createSecurePrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Get the current expected PCR values.
     *
     * Returns cached PCRs if available and valid, otherwise returns bundled defaults.
     */
    fun getCurrentPcrs(): ExpectedPcrs {
        val storedJson = prefs.getString(KEY_CURRENT_PCRS, null)

        if (storedJson != null) {
            try {
                val stored = gson.fromJson(storedJson, ExpectedPcrs::class.java)
                Log.d(TAG, "Using stored PCRs version: ${stored.version}")
                return stored
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse stored PCRs, using defaults", e)
            }
        }

        Log.d(TAG, "Using bundled default PCRs version: ${DEFAULT_PCRS.version}")
        return DEFAULT_PCRS
    }

    /**
     * Check if we should fetch PCR updates.
     */
    fun shouldCheckForUpdates(): Boolean {
        val lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0)
        val timeSinceUpdate = System.currentTimeMillis() - lastUpdate
        return timeSinceUpdate > UPDATE_CHECK_INTERVAL_MS
    }

    /**
     * Update PCRs from a signed response.
     *
     * @param signedResponse The signed PCR response from the API
     * @return true if update was successful
     * @throws PcrUpdateException if signature verification fails
     */
    fun updatePcrs(signedResponse: SignedPcrResponse): Boolean {
        Log.d(TAG, "Updating PCRs from signed response, version: ${signedResponse.pcrs.version}")

        // Verify signature
        if (!verifySignature(signedResponse)) {
            throw PcrUpdateException("PCR signature verification failed")
        }

        // Check version is newer
        val currentVersion = prefs.getString(KEY_PCR_VERSION, "")
        if (signedResponse.pcrs.version == currentVersion) {
            Log.d(TAG, "PCRs already at version ${signedResponse.pcrs.version}")
            return false
        }

        // Store updated PCRs
        prefs.edit()
            .putString(KEY_CURRENT_PCRS, gson.toJson(signedResponse.pcrs))
            .putString(KEY_PCR_VERSION, signedResponse.pcrs.version)
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            .apply()

        Log.i(TAG, "PCRs updated to version: ${signedResponse.pcrs.version}")
        return true
    }

    /**
     * Verify the Ed25519 signature on a PCR response.
     */
    private fun verifySignature(response: SignedPcrResponse): Boolean {
        if (VETTID_SIGNING_KEY_BASE64 == "PLACEHOLDER_VETTID_SIGNING_KEY") {
            Log.w(TAG, "PCR signature verification skipped - signing key not configured")
            return true // Skip verification in development
        }

        try {
            // Parse VettID's public key
            val publicKeyBytes = Base64.decode(VETTID_SIGNING_KEY_BASE64, Base64.NO_WRAP)
            val keySpec = X509EncodedKeySpec(publicKeyBytes)
            val keyFactory = KeyFactory.getInstance("Ed25519", "BC")
            val publicKey = keyFactory.generatePublic(keySpec)

            // Build the message that was signed (canonical JSON)
            val messageBytes = gson.toJson(response.pcrs).toByteArray(Charsets.UTF_8)

            // Verify signature
            val signature = Signature.getInstance("Ed25519", "BC")
            signature.initVerify(publicKey)
            signature.update(messageBytes)

            val signatureBytes = Base64.decode(response.signature, Base64.NO_WRAP)
            val isValid = signature.verify(signatureBytes)

            if (!isValid) {
                Log.e(TAG, "PCR signature verification failed")
            }

            return isValid
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying PCR signature", e)
            return false
        }
    }

    /**
     * Get the current PCR version.
     */
    fun getCurrentVersion(): String {
        return prefs.getString(KEY_PCR_VERSION, DEFAULT_PCRS.version) ?: DEFAULT_PCRS.version
    }

    /**
     * Clear cached PCRs (for testing or recovery).
     */
    fun clearCache() {
        prefs.edit()
            .remove(KEY_CURRENT_PCRS)
            .remove(KEY_PCR_VERSION)
            .remove(KEY_LAST_UPDATE)
            .apply()
        Log.d(TAG, "PCR cache cleared")
    }

    /**
     * Check if current PCRs are the bundled defaults.
     */
    fun isUsingBundledDefaults(): Boolean {
        return prefs.getString(KEY_CURRENT_PCRS, null) == null
    }
}

/**
 * Signed PCR response from VettID API.
 *
 * The signature is an Ed25519 signature over the canonical JSON of the PCRs.
 */
data class SignedPcrResponse(
    /** The PCR values */
    @SerializedName("pcrs")
    val pcrs: ExpectedPcrs,

    /** Ed25519 signature (Base64) */
    @SerializedName("signature")
    val signature: String,

    /** Signing key ID (for key rotation) */
    @SerializedName("key_id")
    val keyId: String? = null
)

/**
 * API response for PCR current endpoint.
 */
data class PcrApiResponse(
    @SerializedName("pcrs")
    val pcrs: PcrValues,

    @SerializedName("signature")
    val signature: String,

    @SerializedName("version")
    val version: String,

    @SerializedName("published_at")
    val publishedAt: String
) {
    /**
     * Convert to SignedPcrResponse format.
     */
    fun toSignedResponse(): SignedPcrResponse {
        return SignedPcrResponse(
            pcrs = ExpectedPcrs(
                pcr0 = pcrs.pcr0,
                pcr1 = pcrs.pcr1,
                pcr2 = pcrs.pcr2,
                pcr3 = pcrs.pcr3,
                version = version,
                publishedAt = publishedAt
            ),
            signature = signature
        )
    }
}

/**
 * PCR values from API.
 */
data class PcrValues(
    @SerializedName("PCR0")
    val pcr0: String,

    @SerializedName("PCR1")
    val pcr1: String,

    @SerializedName("PCR2")
    val pcr2: String,

    @SerializedName("PCR3")
    val pcr3: String? = null
)

/**
 * Exception for PCR update failures.
 */
class PcrUpdateException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
