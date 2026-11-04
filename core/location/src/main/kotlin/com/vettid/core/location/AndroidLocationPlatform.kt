package com.vettid.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.location.LocationManager
import com.vettid.core.data.location.LocationPlatform
import com.vettid.core.data.location.LocationRepository
import com.vettid.core.data.location.PositionProvider
import com.vettid.core.data.location.SendIssue
import kotlinx.coroutines.flow.StateFlow

/**
 * The Android side of [LocationPlatform]: the platform's positions and the foreground service. [names] are the first names that
 * the ongoing notification may say (empty under the app lock's cover).
 */
class AndroidLocationPlatform(
    context: Context,
    repository: LocationRepository,
    names: StateFlow<Map<String, String>>,
    override val positions: PositionProvider = PlatformPositionProvider(context),
) : LocationPlatform {
    private val app = context.applicationContext

    init {
        LocationService.source = object : LocationServiceSource {
            override val state = repository.serviceState
            override val names = names

            override fun stopAll() = repository.stopAll()

            override fun serviceEnded() = repository.onServiceEnded()
        }
    }

    override fun startService(): Boolean = LocationService.start(app)

    override fun stopService() = LocationService.stop(app)

    override fun issue(): SendIssue {
        val fine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val manager = app.getSystemService(LocationManager::class.java)
        return when {
            !fine && !coarse -> SendIssue.NO_PERMISSION
            manager != null && !manager.isLocationEnabled -> SendIssue.LOCATION_OFF
            else -> SendIssue.NONE
        }
    }

    override fun precise(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
