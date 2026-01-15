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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * - Verifies PCR signatures using VettID's ECDSA P-256 signing key
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
        private const val KEY_PREVIOUS_PCRS = "previous_pcrs"
        private const val KEY_LAST_UPDATE = "last_update_timestamp"
        private const val KEY_PCR_VERSION = "pcr_version"

        // PCR manifest CloudFront URL (custom domain)
        private const val PCR_MANIFEST_URL = "https://pcr-manifest.vettid.dev/pcr-manifest.json"

        // VettID's ECDSA P-256 public key for verifying PCR manifest signatures
        // Key ID: a5e30b97-89da-41e9-b447-c759a9f9c801 (alias/vettid-pcr-signing)
        // Updated: 2026-01-06
        private const val VETTID_SIGNING_KEY_BASE64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEzSr2U/RxJRP7dWKMASJSs6fURsEzdn59XSvp3TitMaw3bMBIj8slPXJhJF7d2/DS4UnzMhxEdQHLq2NdoKaVUw=="

        // Default bundled PCRs (updated with each app release)
        // These are used when the app is first installed or if updates fail
        // PCR values from VettID vault enclave build 2026-01-15-v3
        private val DEFAULT_PCRS = ExpectedPcrs(
            pcr0 = "5cbc157248fbf4ead4f793248b403aa637a4a423bf665c1e8fa23cae2dca3f893a5f4e3311e8f46fb8ab36590040a89b",
            pcr1 = "4b4d5b3661b3efc12920900c80e126e4ce783c522de6c02a2a5bf7af3a2b9327b86776f188e4be1c1c404a129dbda493",
            pcr2 = "9e281de6792cb3a3ba56f61989380126fdfe16cf38ebf148b4e762b5e58b130520ac3772b56f103a9afffcaa02310f19",
            pcr3 = null,
            version = "2026-01-15-v3",
            publishedAt = "2026-01-15T16:51:00Z"
        )

        // How often to check for PCR updates (24 hours)
        private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

        // Retry configuration
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val RETRY_BACKOFF_MULTIPLIER = 2.0

        // Manifest freshness validation (reject manifests older than 10 minutes)
        private const val MANIFEST_MAX_AGE_MS = 10 * 60 * 1000L
    }

    private val gson = Gson()
    private val prefs: SharedPreferences by lazy { createSecurePrefs() }

    // Mutex for thread-safe PCR updates
    private val updateMutex = Mutex()

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
     * Thread-safe: Uses mutex to prevent race conditions during concurrent updates.
     * Uses commit() for synchronous writes to ensure data integrity for security-critical data.
     *
     * @param signedResponse The signed PCR response from the API
     * @return true if update was successful
     * @throws PcrSignatureException if signature verification fails
     */
    suspend fun updatePcrs(signedResponse: SignedPcrResponse): Boolean = updateMutex.withLock {
        Log.d(TAG, "Updating PCRs from signed response, version: ${signedResponse.pcrs.version}")

        // CRITICAL: Verify signature - MUST NOT proceed with unverified PCRs
        // See PCR-HANDLING-GUIDE.md: "Always verify signatures - Never trust PCRs without signature verification"
        if (!verifySignature(signedResponse)) {
            Log.e(TAG, "SECURITY: PCR signature verification failed - rejecting update")
            throw PcrSignatureException(
                "PCR manifest signature verification failed. " +
                "This could indicate a man-in-the-middle attack or corrupted data."
            )
        }

        // Check version is newer (within lock to prevent race condition)
        val currentVersion = prefs.getString(KEY_PCR_VERSION, "")
        if (signedResponse.pcrs.version == currentVersion) {
            Log.d(TAG, "PCRs already at version ${signedResponse.pcrs.version}")
            return@withLock false
        }

        // Save current PCRs as previous (for transition period fallback)
        val currentPcrsJson = prefs.getString(KEY_CURRENT_PCRS, null)

        // Store updated PCRs using commit() for synchronous write (security-critical data)
        val success = prefs.edit().apply {
            // Preserve previous PCRs for transition period
            if (currentPcrsJson != null) {
                putString(KEY_PREVIOUS_PCRS, currentPcrsJson)
            }
            putString(KEY_CURRENT_PCRS, gson.toJson(signedResponse.pcrs))
            putString(KEY_PCR_VERSION, signedResponse.pcrs.version)
            putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
        }.commit()

        if (success) {
            Log.i(TAG, "PCRs updated to version: ${signedResponse.pcrs.version}")
        } else {
            Log.e(TAG, "Failed to write PCRs to secure storage")
        }
        success
    }

    /**
     * Fetch PCR manifest from VettID CloudFront with retry logic.
     *
     * This is a suspend function that should be called from a coroutine.
     * Network errors are handled gracefully with exponential backoff retries.
     * If all retries fail, the app will use cached/bundled PCRs.
     *
     * @return Result containing the updated PCRs or an error
     */
    suspend fun fetchPcrUpdates(): Result<ExpectedPcrs> {
        Log.d(TAG, "Fetching PCR manifest from CloudFront")

        var lastException: Exception? = null
        var delayMs = INITIAL_RETRY_DELAY_MS

        for (attempt in 1..MAX_RETRY_ATTEMPTS) {
            try {
                val result = performFetch()
                if (result.isSuccess) {
                    return result
                }
                lastException = result.exceptionOrNull() as? Exception
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "PCR fetch attempt $attempt failed: ${e.message}")
            }

            // Don't delay after the last attempt
            if (attempt < MAX_RETRY_ATTEMPTS) {
                Log.d(TAG, "Retrying in ${delayMs}ms...")
                kotlinx.coroutines.delay(delayMs)
                delayMs = (delayMs * RETRY_BACKOFF_MULTIPLIER).toLong()
            }
        }

        Log.e(TAG, "All PCR fetch attempts failed after $MAX_RETRY_ATTEMPTS retries")
        return Result.failure(
            PcrUpdateException(
                "Failed to fetch PCR updates after $MAX_RETRY_ATTEMPTS attempts",
                lastException
            )
        )
    }

    /**
     * Perform a single fetch attempt.
     *
     * If signature verification fails, this returns a failure result with
     * PcrSignatureException. The caller should fall back to cached/bundled PCRs.
     */
    private suspend fun performFetch(): Result<ExpectedPcrs> {
        val url = java.net.URL(PCR_MANIFEST_URL)
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        try {
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "PCR update API returned $responseCode")
                return Result.failure(PcrUpdateException("API returned $responseCode"))
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }

            // Parse manifest and extract pcr_sets JSON for signature verification
            val jsonObject = com.google.gson.JsonParser.parseString(responseBody).asJsonObject
            val pcrSetsJson = jsonObject.get("pcr_sets")?.toString()

            // Parse full manifest
            val manifest = gson.fromJson(responseBody, PcrManifestResponse::class.java)

            // Validate manifest freshness (#38)
            if (!isManifestFresh(manifest.timestamp)) {
                Log.w(TAG, "PCR manifest is stale (timestamp: ${manifest.timestamp})")
                return Result.failure(PcrUpdateException("Manifest is stale: ${manifest.timestamp}"))
            }

            if (manifest.pcrSets.isEmpty()) {
                Log.e(TAG, "PCR manifest contains no pcr_sets")
                return Result.failure(PcrUpdateException("Empty pcr_sets in manifest"))
            }

            // Convert to SignedPcrResponse with pcr_sets JSON for signature verification
            val signedResponse = manifest.toSignedResponse(pcrSetsJson ?: "")

            try {
                val updated = updatePcrs(signedResponse)

                if (updated) {
                    Log.i(TAG, "PCRs updated to ${signedResponse.pcrs.version}")
                } else {
                    Log.d(TAG, "PCRs already up to date")
                }

                return Result.success(signedResponse.pcrs)
            } catch (e: PcrSignatureException) {
                // Signature verification failed - this is a security issue
                // Return failure so caller falls back to cached/bundled PCRs
                Log.e(TAG, "SECURITY: Signature verification failed, falling back to cached PCRs", e)
                return Result.failure(e)
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Check if manifest timestamp is fresh (not older than MANIFEST_MAX_AGE_MS).
     *
     * This prevents using stale cached responses from CDN or replay attacks.
     */
    private fun isManifestFresh(timestamp: String): Boolean {
        return try {
            val manifestTime = java.time.Instant.parse(timestamp).toEpochMilli()
            val now = System.currentTimeMillis()
            val age = now - manifestTime

            if (age > MANIFEST_MAX_AGE_MS) {
                Log.w(TAG, "Manifest timestamp $timestamp is ${age / 1000}s old (max: ${MANIFEST_MAX_AGE_MS / 1000}s)")
                false
            } else {
                Log.d(TAG, "Manifest timestamp $timestamp is fresh (${age / 1000}s old)")
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse manifest timestamp: $timestamp", e)
            // Be conservative - reject if we can't parse
            false
        }
    }

    /**
     * Fetch PCR manifest from the API endpoint.
     *
     * This fetches from /vault/pcrs/current endpoint.
     * If signature verification fails, returns failure and caller should use cached/bundled PCRs.
     *
     * @param baseUrl The base URL for the API (e.g., "https://api.vettid.com")
     * @return Result containing the updated PCRs or an error
     */
    suspend fun fetchFromApi(baseUrl: String): Result<ExpectedPcrs> {
        Log.d(TAG, "Fetching PCRs from API: $baseUrl/vault/pcrs/current")

        var lastException: Exception? = null
        var delayMs = INITIAL_RETRY_DELAY_MS

        for (attempt in 1..MAX_RETRY_ATTEMPTS) {
            try {
                val url = java.net.URL("$baseUrl/vault/pcrs/current")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                try {
                    val responseCode = connection.responseCode
                    if (responseCode != 200) {
                        throw PcrUpdateException("API returned $responseCode")
                    }

                    val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                    val apiResponse = gson.fromJson(responseBody, PcrApiResponse::class.java)

                    // Convert and update - will throw PcrSignatureException if verification fails
                    val signedResponse = apiResponse.toSignedResponse()
                    val updated = updatePcrs(signedResponse)

                    if (updated) {
                        Log.i(TAG, "PCRs updated to ${signedResponse.pcrs.version}")
                    } else {
                        Log.d(TAG, "PCRs already up to date")
                    }

                    return Result.success(signedResponse.pcrs)
                } finally {
                    connection.disconnect()
                }
            } catch (e: PcrSignatureException) {
                // Signature verification failed - don't retry, this is a security issue
                Log.e(TAG, "SECURITY: PCR signature verification failed - not retrying", e)
                return Result.failure(e)
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "PCR API fetch attempt $attempt failed: ${e.message}")
            }

            if (attempt < MAX_RETRY_ATTEMPTS) {
                Log.d(TAG, "Retrying in ${delayMs}ms...")
                kotlinx.coroutines.delay(delayMs)
                delayMs = (delayMs * RETRY_BACKOFF_MULTIPLIER).toLong()
            }
        }

        Log.e(TAG, "All PCR API fetch attempts failed")
        return Result.failure(
            PcrUpdateException(
                "Failed to fetch PCRs from API after $MAX_RETRY_ATTEMPTS attempts",
                lastException
            )
        )
    }

    /**
     * Get the last update timestamp.
     */
    fun getLastUpdateTimestamp(): Long {
        return prefs.getLong(KEY_LAST_UPDATE, 0)
    }

    /**
     * Record the current time as last update (for manual refresh tracking).
     */
    fun markUpdated() {
        prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
    }

    /**
     * Verify the ECDSA P-256 signature on a PCR manifest.
     *
     * Per PCR-HANDLING-GUIDE.md, the signature is computed over the SHA-256 hash
     * of the JSON-serialized pcr_sets array.
     *
     * Algorithm:
     * 1. Extract pcr_sets array from response
     * 2. Compute SHA-256 hash of JSON-serialized pcr_sets
     * 3. Verify ECDSA signature using VettID public key
     */
    private fun verifySignature(response: SignedPcrResponse): Boolean {
        if (VETTID_SIGNING_KEY_BASE64.contains("PLACEHOLDER")) {
            Log.w(TAG, "PCR signature verification skipped - signing key not configured")
            return true // Skip verification in development
        }

        // Check if we have the pcr_sets JSON for proper verification
        if (response.pcrSetsJson == null) {
            Log.e(TAG, "SECURITY: Cannot verify signature - missing pcr_sets JSON (legacy format?)")
            // For legacy format without pcr_sets, we cannot verify properly
            // This is a security issue - reject the response
            return false
        }

        try {
            // Use Android's built-in crypto provider (more reliable than BouncyCastle on Android)
            val publicKeyBytes = Base64.decode(VETTID_SIGNING_KEY_BASE64, Base64.NO_WRAP)
            val keySpec = X509EncodedKeySpec(publicKeyBytes)
            val keyFactory = KeyFactory.getInstance("EC")
            val publicKey = keyFactory.generatePublic(keySpec)

            // The message that was signed is the pcr_sets array JSON
            // Per spec: "Compute SHA-256 hash of JSON-serialized pcr_sets"
            val messageBytes = response.pcrSetsJson.toByteArray(Charsets.UTF_8)

            // Verify ECDSA signature
            // Note: SHA256withECDSA internally computes SHA-256, so we pass the raw message
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(publicKey)
            signature.update(messageBytes)

            val signatureBytes = Base64.decode(response.signature, Base64.NO_WRAP)
            val isValid = signature.verify(signatureBytes)

            if (!isValid) {
                Log.e(TAG, "SECURITY: PCR signature verification failed - signature mismatch")
            } else {
                Log.d(TAG, "PCR signature verification successful")
            }

            return isValid
        } catch (e: Exception) {
            Log.e(TAG, "SECURITY: Error verifying PCR signature: ${e.message}", e)
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
     * Get previous PCR values (for transition period fallback).
     *
     * During enclave updates, both old and new PCRs may be valid.
     */
    fun getPreviousPcrs(): ExpectedPcrs? {
        val storedJson = prefs.getString(KEY_PREVIOUS_PCRS, null) ?: return null
        return try {
            gson.fromJson(storedJson, ExpectedPcrs::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse previous PCRs", e)
            null
        }
    }

    /**
     * Verify PCRs with fallback to previous version during transition.
     *
     * This allows a grace period when enclave is updated - both old and new
     * PCRs are accepted until the transition period ends (valid_until check).
     *
     * @param actualPcrs The PCR values from the attestation document
     * @return true if PCRs match current or previous expected values within validity window
     */
    fun verifyPcrsWithFallback(actualPcrs: ExpectedPcrs): Boolean {
        val currentPcrs = getCurrentPcrs()

        // Try current PCRs first
        if (matchesPcrs(actualPcrs, currentPcrs)) {
            Log.d(TAG, "PCRs match current version: ${currentPcrs.version}")
            return true
        }

        // During transition, try previous version WITH validity check
        val previousPcrs = getPreviousPcrs()
        if (previousPcrs != null) {
            // Check if previous PCRs are still within their validity window
            val isStillValid = isPcrVersionValid(previousPcrs)

            if (!isStillValid) {
                Log.w(TAG, "Previous PCR version ${previousPcrs.version} has expired (valid_until: ${previousPcrs.validUntil})")
                // Don't accept expired PCRs - this prevents downgrade attacks
            } else if (matchesPcrs(actualPcrs, previousPcrs)) {
                Log.i(TAG, "PCRs match previous version: ${previousPcrs.version} (transition period, expires: ${previousPcrs.validUntil})")
                return true
            }
        }

        Log.w(TAG, "PCRs do not match current (${currentPcrs.version}) or valid previous versions")
        return false
    }

    /**
     * Check if a PCR version is still valid based on its valid_until timestamp.
     *
     * @param pcrs The PCR set to check
     * @return true if the PCRs are still valid (no valid_until or not yet expired)
     */
    private fun isPcrVersionValid(pcrs: ExpectedPcrs): Boolean {
        val validUntil = pcrs.validUntil ?: return true // No expiration = always valid

        return try {
            val expirationTime = java.time.Instant.parse(validUntil).toEpochMilli()
            val now = System.currentTimeMillis()

            if (now >= expirationTime) {
                Log.d(TAG, "PCR version ${pcrs.version} expired at $validUntil")
                false
            } else {
                val remainingMs = expirationTime - now
                val remainingHours = remainingMs / (1000 * 60 * 60)
                Log.d(TAG, "PCR version ${pcrs.version} valid for ~${remainingHours}h more")
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse valid_until timestamp: $validUntil", e)
            // If we can't parse the timestamp, be conservative and reject
            false
        }
    }

    private fun matchesPcrs(actual: ExpectedPcrs, expected: ExpectedPcrs): Boolean {
        return actual.pcr0.equals(expected.pcr0, ignoreCase = true) &&
                actual.pcr1.equals(expected.pcr1, ignoreCase = true) &&
                actual.pcr2.equals(expected.pcr2, ignoreCase = true) &&
                (expected.pcr3 == null || actual.pcr3.equals(expected.pcr3, ignoreCase = true))
    }

    /**
     * Clear cached PCRs (for testing or recovery).
     */
    fun clearCache() {
        prefs.edit()
            .remove(KEY_CURRENT_PCRS)
            .remove(KEY_PREVIOUS_PCRS)
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
 * The signature is an ECDSA P-256 signature over the SHA-256 hash of the
 * canonical JSON serialization of the pcr_sets array.
 */
data class SignedPcrResponse(
    /** The current PCR values to use */
    @SerializedName("pcrs")
    val pcrs: ExpectedPcrs,

    /** ECDSA signature (Base64) over pcr_sets JSON */
    @SerializedName("signature")
    val signature: String,

    /** Signing key ID (for key rotation) */
    @SerializedName("key_id")
    val keyId: String? = null,

    /** Raw JSON of pcr_sets array for signature verification */
    val pcrSetsJson: String? = null
)

/**
 * PCR manifest response (version 2 format per PCR-HANDLING-GUIDE.md).
 *
 * Example:
 * ```json
 * {
 *   "version": 2,
 *   "timestamp": "2026-01-15T11:10:00Z",
 *   "pcr_sets": [
 *     {
 *       "id": "2026-01-15-v1",
 *       "pcr0": "5cbc157248...",
 *       "pcr1": "4b4d5b3661...",
 *       "pcr2": "f7ca84f78d...",
 *       "valid_from": "2026-01-15T11:10:00Z",
 *       "valid_until": null,
 *       "is_current": true,
 *       "description": "ECIES crypto parameter fix"
 *     }
 *   ],
 *   "signature": "MEUCIQC...base64..."
 * }
 * ```
 */
data class PcrManifestResponse(
    @SerializedName("version")
    val version: Int,

    @SerializedName("timestamp")
    val timestamp: String,

    @SerializedName("pcr_sets")
    val pcrSets: List<PcrSetEntry>,

    @SerializedName("signature")
    val signature: String
) {
    /**
     * Convert to SignedPcrResponse, extracting the current PCR set.
     */
    fun toSignedResponse(pcrSetsJson: String): SignedPcrResponse {
        val currentPcrSet = pcrSets.find { it.isCurrent }
            ?: pcrSets.firstOrNull()
            ?: throw PcrUpdateException("No PCR sets in manifest")

        return SignedPcrResponse(
            pcrs = ExpectedPcrs(
                pcr0 = currentPcrSet.pcr0,
                pcr1 = currentPcrSet.pcr1,
                pcr2 = currentPcrSet.pcr2,
                pcr3 = currentPcrSet.pcr3,
                version = currentPcrSet.id,
                publishedAt = currentPcrSet.validFrom,
                validUntil = currentPcrSet.validUntil  // null for current = no expiration
            ),
            signature = signature,
            pcrSetsJson = pcrSetsJson
        )
    }

    /**
     * Get previous PCR set as ExpectedPcrs with validity info.
     */
    fun getPreviousPcrsWithValidity(): ExpectedPcrs? {
        val previousSet = pcrSets.find { !it.isCurrent && it.validUntil != null }
            ?: return null

        return ExpectedPcrs(
            pcr0 = previousSet.pcr0,
            pcr1 = previousSet.pcr1,
            pcr2 = previousSet.pcr2,
            pcr3 = previousSet.pcr3,
            version = previousSet.id,
            publishedAt = previousSet.validFrom,
            validUntil = previousSet.validUntil
        )
    }

    /**
     * Get previous PCR set for transition period support.
     */
    fun getPreviousPcrSet(): PcrSetEntry? {
        return pcrSets.find { !it.isCurrent && it.validUntil != null }
    }
}

/**
 * Individual PCR set entry in the manifest.
 */
data class PcrSetEntry(
    @SerializedName("id")
    val id: String,

    @SerializedName("pcr0")
    val pcr0: String,

    @SerializedName("pcr1")
    val pcr1: String,

    @SerializedName("pcr2")
    val pcr2: String,

    @SerializedName("pcr3")
    val pcr3: String? = null,

    @SerializedName("valid_from")
    val validFrom: String,

    @SerializedName("valid_until")
    val validUntil: String? = null,

    @SerializedName("is_current")
    val isCurrent: Boolean = false,

    @SerializedName("description")
    val description: String? = null
)

/**
 * Legacy API response for PCR current endpoint (pre-manifest format).
 * Kept for backward compatibility during transition.
 */
data class PcrApiResponse(
    @SerializedName("pcrs")
    val pcrs: PcrValues,

    @SerializedName("signature")
    val signature: String,

    @SerializedName("version")
    val version: String,

    @SerializedName("published_at")
    val publishedAt: String,

    @SerializedName("key_id")
    val keyId: String? = null
) {
    /**
     * Convert to SignedPcrResponse format.
     * NOTE: Legacy format doesn't have pcr_sets array, signature verification may fail.
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
            signature = signature,
            keyId = keyId,
            // Legacy format - no pcr_sets array for proper signature verification
            pcrSetsJson = null
        )
    }
}

/**
 * PCR values from legacy API format.
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

/**
 * Security exception for PCR signature verification failures.
 *
 * This is a critical security error - the PCR manifest signature could not be verified,
 * which means we cannot trust the PCR values. This could indicate:
 * - A man-in-the-middle attack substituting malicious PCR values
 * - A corrupted or tampered manifest
 * - A signing key mismatch (key rotation issue)
 *
 * When this exception is thrown, the app should:
 * 1. NOT use the unverified PCR values
 * 2. Fall back to cached or bundled PCRs
 * 3. Log the incident for security monitoring
 */
class PcrSignatureException(
    message: String,
    cause: Throwable? = null
) : SecurityException(message, cause)
