package com.vettid.core.push.fcm

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The Firebase options of this build (ANDROID-PLAN 0.1.30, Notification modes 4): the four values a
 * `google-services.json` would carry, read from Gradle properties into the app's `BuildConfig` (README "Google
 * push"). They identify the app to Firebase and are not secrets. Absent by default.
 */
data class FcmConfig(val applicationId: String, val projectId: String, val apiKey: String, val senderId: String) {
    companion object {
        /** The options when all four are there; null when any is blank (the build then has no Google push). */
        fun of(applicationId: String?, projectId: String?, apiKey: String?, senderId: String?): FcmConfig? {
            val c = FcmConfig(applicationId.clean(), projectId.clean(), apiKey.clean(), senderId.clean())
            return c.takeUnless { listOf(c.applicationId, c.projectId, c.apiKey, c.senderId).any { it.isEmpty() } }
        }
    }
}

private fun String?.clean(): String = this?.trim().orEmpty()

/** What Google Play services say of themselves on this phone. */
enum class PlayServices { OK, OUTDATED, MISSING }

/** The Firebase calls the provider makes (a fake in tests). Nothing here throws. */
interface FcmClient {
    /** Initialises the default `FirebaseApp` from [config], once; false when Firebase refused the options. */
    fun init(config: FcmConfig): Boolean

    fun playServices(): PlayServices

    /** The registration token (asked from Google when there is none yet); null when it could not be had. */
    suspend fun token(): String?

    /** Deletes the token on the phone and at Google; false when that failed. */
    suspend fun deleteToken(): Boolean
}

/**
 * [FcmClient] on the Firebase SDK. Manual initialisation only: `FirebaseInitProvider` is removed from the manifest
 * and auto-init is off (the app's manifest), so no token is made, and Google is not contacted, before the member
 * chooses Google push.
 */
internal class FirebaseFcmClient(private val context: Context) : FcmClient {
    override fun init(config: FcmConfig): Boolean = try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApplicationId(config.applicationId)
                .setProjectId(config.projectId)
                .setApiKey(config.apiKey)
                .setGcmSenderId(config.senderId)
                .build()
            FirebaseApp.initializeApp(context, options)
        }
        true
    } catch (_: IllegalStateException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    override fun playServices(): PlayServices =
        when (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)) {
            ConnectionResult.SUCCESS -> PlayServices.OK
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED, ConnectionResult.SERVICE_UPDATING -> PlayServices.OUTDATED
            else -> PlayServices.MISSING
        }

    override suspend fun token(): String? = try {
        suspendCancellableCoroutine { c ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
                if (c.isActive) c.resume(if (t.isSuccessful) t.result?.takeIf { it.isNotEmpty() } else null)
            }
        }
    } catch (_: IllegalStateException) {
        null // Firebase is not initialised
    }

    override suspend fun deleteToken(): Boolean = try {
        suspendCancellableCoroutine { c ->
            FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { t -> if (c.isActive) c.resume(t.isSuccessful) }
        }
    } catch (_: IllegalStateException) {
        false
    }
}
