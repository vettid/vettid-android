package com.vettid.app

import android.app.Application
import android.util.Log
import com.vettid.app.core.attestation.PcrConfigManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class VettIDApplication : Application() {

    companion object {
        private const val TAG = "VettIDApplication"
    }

    @Inject
    lateinit var pcrConfigManager: PcrConfigManager

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Fetch latest PCR values from API on startup
        fetchPcrUpdates()
    }

    /**
     * Fetch PCR updates from VettID API in the background.
     * This ensures we have the latest PCR values for attestation verification.
     */
    private fun fetchPcrUpdates() {
        applicationScope.launch {
            try {
                if (pcrConfigManager.shouldCheckForUpdates()) {
                    Log.d(TAG, "Checking for PCR updates...")
                    pcrConfigManager.fetchPcrUpdates().fold(
                        onSuccess = { pcrs ->
                            Log.i(TAG, "PCR values updated to version: ${pcrs.version}")
                        },
                        onFailure = { error ->
                            Log.w(TAG, "Failed to fetch PCR updates, using cached/bundled values", error)
                        }
                    )
                } else {
                    Log.d(TAG, "PCR values are up to date (version: ${pcrConfigManager.getCurrentVersion()})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during PCR update check", e)
            }
        }
    }
}
