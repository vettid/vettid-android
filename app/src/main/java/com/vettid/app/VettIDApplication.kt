package com.vettid.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.vettid.app.core.attestation.PcrInitializationService
import com.vettid.app.core.nats.AppLifecycleObserver
import com.vettid.app.features.feed.FeedNotificationService
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class VettIDApplication : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "VettIDApplication"
    }

    @Inject
    lateinit var workerFactory: androidx.hilt.work.HiltWorkerFactory

    @Inject
    lateinit var pcrInitializationService: PcrInitializationService

    @Inject
    lateinit var appLifecycleObserver: AppLifecycleObserver

    // Eagerly initialize FeedNotificationService so it starts listening
    // to NATS feed events immediately on app startup, not lazily on first injection
    @Inject
    lateinit var feedNotificationService: FeedNotificationService

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Initialize PCR fetching on app startup
        Log.d(TAG, "Starting PCR initialization")
        pcrInitializationService.initialize()

        // Register lifecycle observer for NATS auto-reconnect
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
    }
}
