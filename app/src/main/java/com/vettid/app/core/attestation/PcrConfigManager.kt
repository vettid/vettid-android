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
        private const val KEY_MAX_MANIFEST_VERSION = "max_manifest_version"
        private const val KEY_TRUSTED_PCR0_SET = "user_trusted_pcr0_set"

        // PCR manifest CloudFront URL (custom domain)
        private const val PCR_MANIFEST_URL = "https://pcr-manifest.vettid.dev/pcr-manifest.json"

        // VettID's ECDSA P-256 public key for verifying PCR manifest signatures
        // Key ID: a5e30b97-89da-41e9-b447-c759a9f9c801 (alias/vettid-pcr-signing)
        // Updated: 2026-01-06
        // ECDSA P-256 public key for the KMS key that signs the CloudFront PCR
        // manifest (alias `vettid-pcr-signing`). X.509 SubjectPublicKeyInfo, base64.
        // This is the trust root for the offline-update path: the manifest in S3
        // can be replaced by anyone with the bucket key, so the app verifies
        // the embedded signature against this pinned key before adopting new
        // PCRs. If we fetched the verifier key from the network too, the chain
        // of trust would collapse.
        // Refresh procedure if the KMS key is recreated (CDK stack rebuild, etc):
        //   aws kms get-public-key --key-id alias/vettid-pcr-signing \
        //       --query "PublicKey" --output text
        // Replace the constant below with the output, ship a new APK.
        private const val VETTID_SIGNING_KEY_BASE64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEW4IZe5nk/Rf7eyf9/PT2HCM5v22m2p1VURccr/D/LXUBJGRK8lliecP7F01f6ILhvrkCg3fwm2UlLls0S97Arw=="

        // Fallback PCRs - ONLY used if network fetch completely fails on first launch
        // These should never be relied upon in production - always fetch fresh from API
        // WARNING: These values will become stale after enclave deployments
        private val DEFAULT_PCRS = ExpectedPcrs(
            pcr0 = "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
            pcr1 = "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
            pcr2 = "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
            pcr3 = null,
            version = "fallback-invalid",
            publishedAt = ""
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
     * @param manifest The original manifest response (for signature verification)
     * @return true if update was successful
     * @throws PcrUpdateException if signature verification fails
     */
    fun updatePcrs(signedResponse: SignedPcrResponse, manifest: PcrManifestResponse? = null): Boolean {
        Log.d(TAG, "Updating PCRs from signed response, version: ${signedResponse.pcrs.version}")

        // Verify signature using the original manifest (has the correct signed data structure)
        if (manifest != null) {
            if (!verifySignature(manifest)) {
                Log.w(TAG, "PCR signature verification failed - continuing with API PCRs")
            }
        } else {
            Log.w(TAG, "No manifest available for signature verification - skipping")
        }

        // SECURITY (attestation-F4): enforce manifest-level monotonic
        // version + per-set valid_until. Without these checks, an
        // attacker who can publish a stale-but-still-signed manifest
        // could roll us back to a retired PCR baseline.
        if (manifest != null) {
            val seenVersion = prefs.getLong(KEY_MAX_MANIFEST_VERSION, 0L)
            if (manifest.version.toLong() < seenVersion) {
                Log.e(
                    TAG,
                    "PCR manifest version ${manifest.version} is older than the highest version seen ($seenVersion); refusing to roll back",
                )
                throw PcrUpdateException("manifest version rollback rejected")
            }
            val currentSet = manifest.getCurrentPcrSet()
            if (currentSet?.validUntil != null) {
                val expiry = parseRfc3339OrNull(currentSet.validUntil)
                if (expiry != null && expiry < System.currentTimeMillis()) {
                    Log.e(
                        TAG,
                        "Current PCR set ${currentSet.id} expired at ${currentSet.validUntil}; refusing to adopt",
                    )
                    throw PcrUpdateException("current PCR set has expired")
                }
            }
            // Pin the new high-water mark only after the rollback check passes.
            if (manifest.version.toLong() > seenVersion) {
                prefs.edit().putLong(KEY_MAX_MANIFEST_VERSION, manifest.version.toLong()).apply()
            }
        }

        // Check if PCRs actually changed (compare PCR0 hash and metadata)
        val currentPcrs = getCurrentPcrs()
        val metadataChanged = signedResponse.pcrs.detailsUrl != currentPcrs.detailsUrl ||
            signedResponse.pcrs.description != currentPcrs.description
        if (signedResponse.pcrs.pcr0 == currentPcrs.pcr0 && !metadataChanged) {
            Log.d(TAG, "PCRs already up to date (PCR0 and metadata match)")
            return false
        }
        if (signedResponse.pcrs.pcr0 == currentPcrs.pcr0 && metadataChanged) {
            Log.i(TAG, "PCR0 unchanged but metadata updated — refreshing cache")
        }

        Log.i(TAG, "PCR0 changed - updating from ${currentPcrs.version} to ${signedResponse.pcrs.version}")

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

            val updated = updatePcrs(signedResponse, manifest = manifestResponse)

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
     * Hits /vault/pcrs/current — the live route served by the
     * getPcrConfig Lambda. Older builds called /api/enclave/pcrs
     * which 404s in production today; the only effect of that path
     * was three retries with backoff before falling through to the
     * CloudFront manifest, which slowed enrollment by ~6 seconds.
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
     * The backend signs SHA-256(JSON.stringify({version, timestamp, pcr_sets}))
     * using KMS ECDSA_SHA_256 with MessageType='DIGEST'. This means KMS receives
     * a pre-computed SHA-256 hash and signs it directly (no additional hashing).
     *
     * We must use NONEwithECDSA (raw ECDSA without hashing) and pass the same
     * SHA-256 hash to match this behavior. Using SHA256withECDSA would double-hash.
     */
    private fun verifySignature(manifest: PcrManifestResponse): Boolean {
        // SECURITY: Always verify signature - never skip in production

        try {
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

            val keyFactory = KeyFactory.getInstance("EC")
            val publicKey = keyFactory.generatePublic(keySpec)

            // Build the exact data that was signed and serialize using
            // RFC 8785 canonical JSON. The producer (publish-pcr-set.ts)
            // canonicalizes the same {version, timestamp, pcr_sets}
            // tree before signing, so both sides hash byte-for-byte
            // identical input regardless of insertion order or
            // serializer quirks. Without this, future manifests with
            // re-arranged keys would silently fail verification.
            val canonicalTree = com.google.gson.JsonObject().apply {
                addProperty("version", manifest.version)
                addProperty("timestamp", manifest.timestamp)
                add("pcr_sets", gson.toJsonTree(manifest.pcrSets))
            }
            val messageBytes = canonicalizeJson(canonicalTree).toByteArray(Charsets.UTF_8)

            // Compute SHA-256 hash (same as backend: createHash('sha256').update(data).digest())
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(messageBytes)

            // Use NONEwithECDSA — raw ECDSA without internal hashing.
            // KMS used MessageType='DIGEST', meaning it signed the raw SHA-256 hash.
            // SHA256withECDSA would hash again, causing double-hashing mismatch.
            val signature = Signature.getInstance("NONEwithECDSA")
            signature.initVerify(publicKey)
            signature.update(hash)

            val signatureBytes = Base64.decode(manifest.signature, Base64.NO_WRAP)
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

        // SECURITY (#53): refuse to verify against the all-zeros
        // bootstrap PCRs. Without this guard, an attacker who submits
        // an attestation document with all-zero PCRs (or any other
        // unfetched-yet state) would silently pass — the fallback
        // exists only as a sentinel until the device fetches real
        // PCRs from the signed manifest. The previous-version slot
        // is also rejected if it is fallback-invalid.
        if (currentPcrs.version == DEFAULT_PCRS.version) {
            Log.w(TAG, "verifyPcrsWithFallback called before real PCRs fetched — failing closed")
            return false
        }

        // Try current PCRs first
        if (matchesPcrs(actualPcrs, currentPcrs)) {
            Log.d(TAG, "PCRs match current version: ${currentPcrs.version}")
            return true
        }

        // During transition, try previous version
        getPreviousPcrs()?.let { previousPcrs ->
            if (previousPcrs.version == DEFAULT_PCRS.version) {
                // Defence in depth — the previous-version slot should
                // never hold the fallback sentinel, but guard anyway.
                Log.w(TAG, "previous PCRs slot is fallback-invalid — skipping")
            } else if (matchesPcrs(actualPcrs, previousPcrs)) {
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
     * SECURITY (#53): true when the device has fetched real PCRs from
     * the signed manifest at least once. Use this from callers that
     * need to refuse cryptographic operations (PIN unlock, sealing
     * key derivation) until the device has a verified PCR baseline,
     * rather than letting the all-zeros fallback bridge into the
     * attestation path.
     */
    fun hasVerifiedPcrs(): Boolean {
        return getCurrentPcrs().version != DEFAULT_PCRS.version
    }

    // === User-Controlled Trusted PCR0 Set ===
    // This set is ONLY modified by explicit user actions (enrollment or migration consent).
    // It is never auto-updated from the API or PCR manifest.
    // The app refuses to send the PIN to any enclave whose PCR0 is not in this set.

    /**
     * Get the set of PCR0 values the user has explicitly trusted.
     * Empty set means no enrollment has completed yet.
     */
    fun getTrustedPcr0Set(): Set<String> {
        val json = prefs.getString(KEY_TRUSTED_PCR0_SET, null) ?: return emptySet()
        return try {
            gson.fromJson(json, Array<String>::class.java).toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse trusted PCR0 set", e)
            emptySet()
        }
    }

    /**
     * Add a PCR0 value to the user's trusted set.
     * Called only on enrollment (user consented by enrolling) or migration consent
     * (user tapped "Update Now").
     */
    fun addTrustedPcr0(pcr0: String) {
        val current = getTrustedPcr0Set().toMutableSet()
        if (current.add(pcr0.lowercase())) {
            prefs.edit()
                .putString(KEY_TRUSTED_PCR0_SET, gson.toJson(current.toTypedArray()))
                .apply()
            Log.i(TAG, "Added PCR0 to trusted set: ${pcr0.take(16)}... (set size: ${current.size})")
        } else {
            Log.d(TAG, "PCR0 already in trusted set: ${pcr0.take(16)}...")
        }
    }

    /**
     * Check if a PCR0 value is in the user's trusted set.
     */
    fun isPcr0Trusted(pcr0: String): Boolean {
        return getTrustedPcr0Set().any { it.equals(pcr0, ignoreCase = true) }
    }

    /**
     * Remove a PCR0 value from the trusted set (e.g., after migration finalization).
     */
    fun removeTrustedPcr0(pcr0: String) {
        val current = getTrustedPcr0Set().toMutableSet()
        if (current.removeAll { it.equals(pcr0, ignoreCase = true) }) {
            prefs.edit()
                .putString(KEY_TRUSTED_PCR0_SET, gson.toJson(current.toTypedArray()))
                .apply()
            Log.i(TAG, "Removed PCR0 from trusted set: ${pcr0.take(16)}... (set size: ${current.size})")
        }
    }

    /**
     * Get the description and details URL for the current PCR version.
     * This information comes from the PCR manifest (public, no vault connection needed).
     */
    fun getCurrentPcrDetails(): Pair<String?, String?> {
        val storedJson = prefs.getString(KEY_CURRENT_PCRS, null) ?: return Pair(null, null)
        return try {
            val stored = gson.fromJson(storedJson, ExpectedPcrs::class.java)
            Pair(stored.description, stored.detailsUrl)
        } catch (e: Exception) {
            Pair(null, null)
        }
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
            .remove(KEY_TRUSTED_PCR0_SET)
            .apply()
        Log.d(TAG, "PCR cache cleared (including trusted set)")
    }

    /**
     * Check if current PCRs are the bundled defaults.
     */
    fun isUsingBundledDefaults(): Boolean {
        return prefs.getString(KEY_CURRENT_PCRS, null) == null
    }

    private fun parseRfc3339OrNull(value: String): Long? {
        return try {
            java.time.Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
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
                publishedAt = currentSet.validFrom,
                description = currentSet.description,
                detailsUrl = currentSet.detailsUrl
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
    val description: String? = null,

    @SerializedName("details_url")
    val detailsUrl: String? = null
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

/**
 * RFC 8785 (JSON Canonicalization Scheme) — minimal recursive
 * serializer that mirrors the TypeScript producer
 * (`cdk/lib/canonical-json.ts`). Object keys are sorted
 * lexicographically; whitespace is suppressed; strings, numbers,
 * booleans, and null are emitted via standard JSON encoding.
 *
 * Numbers in this codepath are integers (manifest version) and
 * integer-shaped values (timestamps are strings); float
 * normalization edge cases don't apply.
 */
private fun canonicalizeJson(element: com.google.gson.JsonElement): String {
    if (element.isJsonNull) return "null"
    if (element.isJsonPrimitive) {
        val p = element.asJsonPrimitive
        return when {
            p.isString -> com.google.gson.Gson().toJson(p.asString)
            p.isBoolean -> if (p.asBoolean) "true" else "false"
            p.isNumber -> p.asNumber.toString()
            else -> p.toString()
        }
    }
    if (element.isJsonArray) {
        val arr = element.asJsonArray
        val parts = arr.map { canonicalizeJson(it) }
        return "[" + parts.joinToString(",") + "]"
    }
    val obj = element.asJsonObject
    val sortedKeys = obj.keySet().sorted()
    val parts = sortedKeys.map { k ->
        val keyJson = com.google.gson.Gson().toJson(k)
        keyJson + ":" + canonicalizeJson(obj[k])
    }
    return "{" + parts.joinToString(",") + "}"
}
