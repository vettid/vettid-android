package com.vettid.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import com.vettid.app.core.attestation.PcrInitializationService
import com.vettid.app.core.nats.AppLifecycleObserver
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class VettIDApplication : Application() {

    companion object {
        private const val TAG = "VettIDApplication"
    }

    @Inject
    lateinit var pcrInitializationService: PcrInitializationService

    @Inject
    lateinit var appLifecycleObserver: AppLifecycleObserver

    override fun onCreate() {
        super.onCreate()

        // Initialize PCR fetching on app startup
        Log.d(TAG, "Starting PCR initialization")
        pcrInitializationService.initialize()

        // Register lifecycle observer for NATS auto-reconnect
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
    }
}
