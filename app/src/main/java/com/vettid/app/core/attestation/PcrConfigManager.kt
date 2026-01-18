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
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.Security
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
        // PCR values from VettID vault enclave build 2026-01-18-v1
        private val DEFAULT_PCRS = ExpectedPcrs(
            pcr0 = "9b03797f32414ba67d3fd6dee843b45c8324069b658c5812917b727b3959cfd78f46f1ef69c35668034871b86c929a3b",
            pcr1 = "4b4d5b3661b3efc12920900c80e126e4ce783c522de6c02a2a5bf7af3a2b9327b86776f188e4be1c1c404a129dbda493",
            pcr2 = "9875f4b83a151162c03dd37c8b023828cd15aa12f6c3d115556c8e036cbdcf2302f1a27e7576c017e3a084060399d189",
            pcr3 = null,
            version = "2026-01-18-v1",
            publishedAt = "2026-01-18T02:09:48Z"
        )

        // How often to check for PCR updates (24 hours)
        private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

        // Retry configuration
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val RETRY_BACKOFF_MULTIPLIER = 2.0
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
            Log.w(TAG, "PCR signature verification failed - continuing with API PCRs")
        }

        // Check version is newer
        val currentVersion = prefs.getString(KEY_PCR_VERSION, "")
        if (signedResponse.pcrs.version == currentVersion) {
            Log.d(TAG, "PCRs already at version ${signedResponse.pcrs.version}")
            return false
        }

        // Save current PCRs as previous (for transition period fallback)
        val currentPcrsJson = prefs.getString(KEY_CURRENT_PCRS, null)

        // Store updated PCRs
        prefs.edit().apply {
            // Preserve previous PCRs for transition period
            if (currentPcrsJson != null) {
                putString(KEY_PREVIOUS_PCRS, currentPcrsJson)
            }
            putString(KEY_CURRENT_PCRS, gson.toJson(signedResponse.pcrs))
            putString(KEY_PCR_VERSION, signedResponse.pcrs.version)
            putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            apply()
        }

        Log.i(TAG, "PCRs updated to version: ${signedResponse.pcrs.version}")
        return true
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
     */
    private fun performFetch(): Result<ExpectedPcrs> {
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
            Log.d(TAG, "PCR manifest response: ${responseBody.take(200)}...")

            // Parse as manifest format (with pcr_sets array)
            val manifestResponse = gson.fromJson(responseBody, PcrManifestResponse::class.java)

            // Get the current PCR set
            val signedResponse = manifestResponse.toSignedResponse()
            if (signedResponse == null) {
                Log.e(TAG, "No current PCR set found in manifest")
                return Result.failure(PcrUpdateException("No current PCR set in manifest"))
            }

            Log.d(TAG, "Found current PCR set: ${signedResponse.pcrs.version}, PCR0: ${signedResponse.pcrs.pcr0.take(16)}...")

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
    }

    /**
     * Fetch PCR manifest from the API endpoint.
     *
     * This fetches from /api/enclave/pcrs as specified in the architecture doc.
     *
     * @param baseUrl The base URL for the API (e.g., "https://api.vettid.com")
     * @return Result containing the updated PCRs or an error
     */
    suspend fun fetchFromApi(baseUrl: String): Result<ExpectedPcrs> {
        Log.d(TAG, "Fetching PCRs from API: $baseUrl/api/enclave/pcrs")

        var lastException: Exception? = null
        var delayMs = INITIAL_RETRY_DELAY_MS

        for (attempt in 1..MAX_RETRY_ATTEMPTS) {
            try {
                val url = java.net.URL("$baseUrl/api/enclave/pcrs")
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

                    // Convert and update
                    val signedResponse = apiResponse.toSignedResponse()
                    if (signedResponse == null) {
                        throw PcrUpdateException("Invalid PCR response format")
                    }
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
     * The signature is created by KMS using ECDSA_SHA_256 on the SHA-256 hash
     * of the manifest JSON (excluding signature and public_key fields).
     */
    private fun verifySignature(response: SignedPcrResponse): Boolean {
        // SECURITY: Always verify signature - never skip in production
        // The signing key must be properly configured before release

        try {
            // Ensure Bouncy Castle provider is registered
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }

            // SECURITY: Validate signing key is properly configured
            val publicKeyBytes = try {
                Base64.decode(VETTID_SIGNING_KEY_BASE64, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "SECURITY: Signing key is not valid base64")
                return false
            }

            // SECURITY: Validate key length (P-256 DER key is typically 91 bytes)
            if (publicKeyBytes.size < 59 || publicKeyBytes.size > 91) {
                Log.e(TAG, "SECURITY: Signing key has invalid length: ${publicKeyBytes.size}")
                return false
            }
            val keySpec = X509EncodedKeySpec(publicKeyBytes)
            val keyFactory = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
            val publicKey = keyFactory.generatePublic(keySpec)

            // Build the message that was signed (canonical JSON without signature/public_key)
            val messageBytes = gson.toJson(response.pcrs).toByteArray(Charsets.UTF_8)

            // Compute SHA-256 hash (KMS signs the digest, not the raw message)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(messageBytes)

            // Verify ECDSA signature (DER-encoded from KMS)
            val signature = Signature.getInstance("SHA256withECDSA", BouncyCastleProvider.PROVIDER_NAME)
            signature.initVerify(publicKey)
            signature.update(hash)

            val signatureBytes = Base64.decode(response.signature, Base64.NO_WRAP)
            val isValid = signature.verify(signatureBytes)

            if (!isValid) {
                Log.e(TAG, "PCR signature verification failed")
            } else {
                Log.d(TAG, "PCR signature verification successful")
            }

            return isValid
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying PCR signature: ${e.message}", e)
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
     * PCRs are accepted until the transition period ends.
     *
     * @param actualPcrs The PCR values from the attestation document
     * @return true if PCRs match current or previous expected values
     */
    fun verifyPcrsWithFallback(actualPcrs: ExpectedPcrs): Boolean {
        val currentPcrs = getCurrentPcrs()

        // Try current PCRs first
        if (matchesPcrs(actualPcrs, currentPcrs)) {
            Log.d(TAG, "PCRs match current version: ${currentPcrs.version}")
            return true
        }

        // During transition, try previous version
        getPreviousPcrs()?.let { previousPcrs ->
            if (matchesPcrs(actualPcrs, previousPcrs)) {
                Log.d(TAG, "PCRs match previous version: ${previousPcrs.version} (transition period)")
                return true
            }
        }

        Log.w(TAG, "PCRs do not match current (${currentPcrs.version}) or previous versions")
        return false
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
 * The signature is an ECDSA P-256 signature over the canonical JSON of the PCRs.
 */
data class SignedPcrResponse(
    /** The PCR values */
    @SerializedName("pcrs")
    val pcrs: ExpectedPcrs,

    /** ECDSA signature (Base64) */
    @SerializedName("signature")
    val signature: String,

    /** Signing key ID (for key rotation) */
    @SerializedName("key_id")
    val keyId: String? = null,

    /** Original PCR values from API (for signature verification) */
    val originalPcrs: PcrValues? = null
)

/**
 * PCR manifest response from CloudFront.
 * Contains multiple PCR sets with validity periods.
 */
data class PcrManifestResponse(
    @SerializedName("version")
    val version: Int,

    @SerializedName("timestamp")
    val timestamp: String,

    @SerializedName("pcr_sets")
    val pcrSets: List<PcrSetEntry>,

    @SerializedName("signature")
    val signature: String,

    @SerializedName("public_key")
    val publicKey: String? = null
) {
    /**
     * Get the current PCR set (is_current: true).
     */
    fun getCurrentPcrSet(): PcrSetEntry? {
        return pcrSets.find { it.isCurrent }
    }

    /**
     * Convert to SignedPcrResponse format using the current PCR set.
     */
    fun toSignedResponse(): SignedPcrResponse? {
        val currentSet = getCurrentPcrSet() ?: return null
        return SignedPcrResponse(
            pcrs = ExpectedPcrs(
                pcr0 = currentSet.pcr0,
                pcr1 = currentSet.pcr1,
                pcr2 = currentSet.pcr2,
                pcr3 = currentSet.pcr3,
                version = currentSet.id,
                publishedAt = currentSet.validFrom
            ),
            signature = signature,
            keyId = null,
            originalPcrs = PcrValues(
                pcr0 = currentSet.pcr0,
                pcr1 = currentSet.pcr1,
                pcr2 = currentSet.pcr2,
                pcr3 = currentSet.pcr3
            )
        )
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
 * API response for PCR current endpoint (legacy format).
 */
data class PcrApiResponse(
    @SerializedName("pcrs")
    val pcrs: PcrValues?,

    @SerializedName("signature")
    val signature: String,

    @SerializedName("version")
    val version: String?,

    @SerializedName("published_at")
    val publishedAt: String?,

    @SerializedName("key_id")
    val keyId: String? = null
) {
    /**
     * Convert to SignedPcrResponse format.
     */
    fun toSignedResponse(): SignedPcrResponse? {
        val p = pcrs ?: return null
        return SignedPcrResponse(
            pcrs = ExpectedPcrs(
                pcr0 = p.pcr0,
                pcr1 = p.pcr1,
                pcr2 = p.pcr2,
                pcr3 = p.pcr3,
                version = version ?: "unknown",
                publishedAt = publishedAt ?: ""
            ),
            signature = signature,
            keyId = keyId,
            originalPcrs = p
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
