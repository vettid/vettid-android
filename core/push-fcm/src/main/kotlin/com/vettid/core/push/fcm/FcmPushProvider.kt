package com.vettid.core.push.fcm

import android.content.Context
import android.content.pm.PackageManager
import com.vettid.core.notify.PushAvailability
import com.vettid.core.notify.PushProvider

/**
 * Google push (FCM) for the "Google push" notification mode (ANDROID-PLAN 0.1.23 D7, Notification modes 4). A stub
 * for now: it says whether Google Play services are on the phone, and otherwise "not available yet", because the
 * vault cannot register a push token before VAULT-MESSAGING specifies `push.register` (N2) and the gateway has FCM
 * credentials (N4). No Firebase library is linked yet (the plan's runtime `FirebaseOptions` from Gradle properties
 * come with N4), so this build behaves exactly as one without push.
 */
class FcmPushProvider(
    context: Context,
    /** Whether the build carries the Firebase configuration (none yet: N4). */
    private val configured: Boolean = false,
    private val installed: () -> Boolean = { playServicesInstalled(context.applicationContext) },
) : PushProvider {
    override fun availability(): PushAvailability = when {
        !installed() -> PushAvailability.MISSING_SERVICES
        !configured -> PushAvailability.NOT_AVAILABLE_YET
        // With the configuration (N4): GoogleApiAvailability decides between AVAILABLE and SERVICES_OUTDATED.
        else -> PushAvailability.NOT_AVAILABLE_YET
    }

    override suspend fun token(): String? = null

    override var onWake: (() -> Unit)? = null

    companion object {
        private const val GMS = "com.google.android.gms"

        /** Google Play services as a package (GrapheneOS without sandboxed Play has none); enabled. */
        fun playServicesInstalled(context: Context): Boolean = try {
            context.packageManager.getApplicationInfo(GMS, 0).enabled
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
