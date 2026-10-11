package com.vettid.app.distribution

import com.vettid.core.notify.NoGooglePushProvider
import com.vettid.core.notify.PushProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The `foss` distribution (F-Droid; README "F-Droid build"): no Google code in the build, so no Google push. The
 * on-phone service is how notifications reach the phone; Settings shows Google push as not available in this build.
 */
@Module
@InstallIn(SingletonComponent::class)
object DistributionModule {
    @Provides
    fun pushProvider(): PushProvider = NoGooglePushProvider

    @Provides
    fun pushStartup(): PushStartup = PushStartup { }
}
