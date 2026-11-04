package com.vettid.core.location

import android.content.Context
import com.vettid.core.data.location.LocationPlatform
import com.vettid.core.data.location.LocationRepository
import com.vettid.core.data.location.PositionProvider
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
        }
    }

    override fun startService(): Boolean = LocationService.start(app)

    override fun stopService() = LocationService.stop(app)
}
