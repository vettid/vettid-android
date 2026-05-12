package com.vettid.app.core.attestation

import android.util.Log
import com.vettid.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service to initialize PCR fetching on app start.
 *
 * This service:
 * 1. Checks if PCRs need to be updated (24h TTL)
 * 2. Fetches PCRs from the API with retry logic
 * 3. Falls back to bundled PCRs if fetch fails
 *
 * Usage:
 * Call `initialize()` from Application.onCreate() or a similar app startup point.
 */
@Singleton
class PcrInitializationService @Inject constructor(
    private val pcrConfigManager: PcrConfigManager
) {
    companion object {
        private const val TAG = "PcrInitService"
        // How stale the cached PCR manifest can get before a foreground transition
        // forces a refresh. 30 min is a compromise: long enough that a backgrounded
        // app doesn't hammer the manifest endpoint on every brief foreground, short
        // enough that an enclave migration completed during background time is
        // detected the next time the user wakes the app.
        private const val FOREGROUND_REFRESH_THRESHOLD_MS = 30 * 60 * 1000L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var isInitialized = false

    @Volatile
    private var initializationInProgress = false

    /**
     * Initialize PCR fetching.
     *
     * This method is safe to call multiple times - it will only run once.
     * The fetch runs in the background and does not block.
     */
    fun initialize() {
        if (isInitialized || initializationInProgress) {
            Log.d(TAG, "PCR initialization already started or completed")
            return
        }

        initializationInProgress = true

        serviceScope.launch {
            try {
                initializeAsync()
            } finally {
                initializationInProgress = false
                isInitialized = true
            }
        }
    }

    /**
     * Initialize PCRs synchronously.
     *
     * Use this when you need to ensure PCRs are fetched before continuing.
     */
    suspend fun initializeSync() {
        if (isInitialized) {
            Log.d(TAG, "PCRs already initialized")
            return
        }

        initializeAsync()
        isInitialized = true
    }

    private suspend fun initializeAsync() {
        Log.i(TAG, "Starting PCR initialization - always fetching fresh PCRs")

        // Always fetch fresh PCRs on startup to handle enclave updates
        // Don't rely on cached values which may be stale after deployments

        // Get the API base URL from build config
        val baseUrl = BuildConfig.API_BASE_URL

        // Try fetching from the API endpoint first
        val apiResult = pcrConfigManager.fetchFromApi(baseUrl)

        if (apiResult.isSuccess) {
            val pcrs = apiResult.getOrNull()!!
            Log.i(TAG, "PCRs fetched from API, version: ${pcrs.version}")
            return
        }

        // Fall back to CloudFront manifest
        Log.w(TAG, "API fetch failed, trying CloudFront manifest")
        val cloudFrontResult = pcrConfigManager.fetchPcrUpdates()

        if (cloudFrontResult.isSuccess) {
            val pcrs = cloudFrontResult.getOrNull()!!
            Log.i(TAG, "PCRs fetched from CloudFront, version: ${pcrs.version}")
            return
        }

        // Both failed - use cached or bundled PCRs
        Log.w(TAG, "All PCR fetch attempts failed, using cached/bundled PCRs")
        val fallbackPcrs = pcrConfigManager.getCurrentPcrs()
        Log.i(TAG, "Using fallback PCRs version: ${fallbackPcrs.version}")

        if (pcrConfigManager.isUsingBundledDefaults()) {
            Log.w(TAG, "WARNING: Using bundled default PCRs - may be outdated")
        }
    }

    /**
     * Refresh PCRs if the cached manifest is older than [FOREGROUND_REFRESH_THRESHOLD_MS].
     * Call this from app foreground transitions so an enclave migration that completed
     * while we were backgrounded is detected before the user's next vault interaction.
     */
    fun maybeRefreshOnForeground() {
        val lastUpdate = pcrConfigManager.getLastUpdateTimestamp()
        val ageMs = System.currentTimeMillis() - lastUpdate
        if (lastUpdate > 0 && ageMs < FOREGROUND_REFRESH_THRESHOLD_MS) {
            Log.d(TAG, "PCR manifest is ${ageMs / 1000}s old, foreground refresh skipped")
            return
        }
        Log.i(TAG, "PCR manifest is ${ageMs / 1000}s old, refreshing on foreground")
        serviceScope.launch {
            try {
                forceRefresh()
            } catch (e: Exception) {
                Log.w(TAG, "Foreground PCR refresh failed: ${e.message}")
            }
        }
    }

    /**
     * Force a refresh of PCRs regardless of TTL.
     */
    suspend fun forceRefresh(): Result<ExpectedPcrs> {
        Log.i(TAG, "Force refreshing PCRs")

        val baseUrl = BuildConfig.API_BASE_URL
        val result = pcrConfigManager.fetchFromApi(baseUrl)

        if (result.isFailure) {
            // Try CloudFront as fallback
            return pcrConfigManager.fetchPcrUpdates()
        }

        return result
    }

    /**
     * Check if PCRs have been initialized.
     */
    fun isReady(): Boolean = isInitialized

    /**
     * Get the current PCR status for diagnostics.
     */
    fun getStatus(): PcrStatus {
        return PcrStatus(
            isInitialized = isInitialized,
            currentVersion = pcrConfigManager.getCurrentVersion(),
            lastUpdateTimestamp = pcrConfigManager.getLastUpdateTimestamp(),
            isUsingBundledDefaults = pcrConfigManager.isUsingBundledDefaults(),
            hasPreviousVersion = pcrConfigManager.getPreviousPcrs() != null
        )
    }
}

/**
 * Status of the PCR configuration.
 */
data class PcrStatus(
    val isInitialized: Boolean,
    val currentVersion: String,
    val lastUpdateTimestamp: Long,
    val isUsingBundledDefaults: Boolean,
    val hasPreviousVersion: Boolean
) {
    val lastUpdateFormatted: String
        get() = if (lastUpdateTimestamp > 0) {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(lastUpdateTimestamp))
        } else {
            "Never"
        }
}
