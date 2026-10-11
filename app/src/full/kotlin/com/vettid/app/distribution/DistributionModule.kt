package com.vettid.app.distribution

import android.content.Context
import com.vettid.app.BuildConfig
import com.vettid.core.notify.PushProvider
import com.vettid.core.push.fcm.FcmConfig
import com.vettid.core.push.fcm.FcmPushProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The `full` distribution (Google Play, VettID's own releases): Google push through `:core:push-fcm`. */
@Module
@InstallIn(SingletonComponent::class)
object DistributionModule {
    /**
     * Google push (ANDROID-PLAN 0.1.30): available only in a build that carries the Firebase options (the Gradle
     * properties of README "Google push"); without them "Not available yet", and nothing of Firebase runs.
     */
    @Provides
    @Singleton
    fun fcmPushProvider(@ApplicationContext context: Context): FcmPushProvider = FcmPushProvider(
        context,
        FcmConfig.of(BuildConfig.FCM_APPLICATION_ID, BuildConfig.FCM_PROJECT_ID, BuildConfig.FCM_API_KEY, BuildConfig.FCM_SENDER_ID),
    )

    @Provides
    fun pushProvider(fcm: FcmPushProvider): PushProvider = fcm

    /**
     * In a build with the Firebase options, Firebase is initialised at process start, before any service runs, so
     * that a wake that started the process is handled. Without the options this does nothing.
     */
    @Provides
    fun pushStartup(fcm: FcmPushProvider): PushStartup = PushStartup { fcm.start() }
}
