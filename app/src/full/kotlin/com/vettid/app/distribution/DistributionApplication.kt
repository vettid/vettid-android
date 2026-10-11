package com.vettid.app.distribution

import android.app.Application
import androidx.work.Configuration

/**
 * The `full` distribution's application base. WorkManager (a Google push wake's longer collect, `:core:push-fcm`) is
 * initialised on first use, from here: its start-up initialiser is removed from the manifest, so a build without
 * the Firebase options never starts it.
 */
abstract class DistributionApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()
}
