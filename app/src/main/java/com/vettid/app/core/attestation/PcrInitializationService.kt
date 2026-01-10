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
        Log.i(TAG, "Starting PCR initialization")

        // Check if we need to update
        if (!pcrConfigManager.shouldCheckForUpdates()) {
            Log.d(TAG, "PCRs are up to date (last update within 24h)")
            val currentPcrs = pcrConfigManager.getCurrentPcrs()
            Log.i(TAG, "Using cached PCRs version: ${currentPcrs.version}")
            return
        }

        Log.d(TAG, "PCRs need to be refreshed")

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
