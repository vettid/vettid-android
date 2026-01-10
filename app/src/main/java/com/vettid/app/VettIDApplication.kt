package com.vettid.app

import android.app.Application
import android.util.Log
import com.vettid.app.core.attestation.PcrInitializationService
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class VettIDApplication : Application() {

    companion object {
        private const val TAG = "VettIDApplication"
    }

    @Inject
    lateinit var pcrInitializationService: PcrInitializationService

    override fun onCreate() {
        super.onCreate()

        // Initialize PCR fetching on app startup
        Log.d(TAG, "Starting PCR initialization")
        pcrInitializationService.initialize()
    }
}
