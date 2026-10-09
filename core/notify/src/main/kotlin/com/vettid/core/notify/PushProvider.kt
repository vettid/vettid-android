package com.vettid.core.notify

/**
 * Whether a push service can wake this app (ANDROID-PLAN 0.1.23 D7, Notification modes 4): the "Google push" choice
 * is offered only when [AVAILABLE].
 */
enum class PushAvailability {
    AVAILABLE,

    /** The build has no push configuration, or the vault cannot register yet (`push.register` is reserved). */
    NOT_AVAILABLE_YET,

    /** The push service this provider needs is not on the phone (Google Play services on GrapheneOS, say). */
    MISSING_SERVICES,

    /** The push service is there but needs an update. */
    SERVICES_OUTDATED,
}

/**
 * A push service that wakes the app for its vault (ANDROID-PLAN 0.1.23, Notification modes 4: contentless wakes;
 * the app then collects from its relay and decrypts on the phone as the on-phone service does). Today only the FCM
 * provider of `:core:push-fcm`, which reports [PushAvailability.NOT_AVAILABLE_YET] until VAULT-MESSAGING specifies
 * `push.register` (N2) and the gateway has FCM credentials (N4).
 */
interface PushProvider {
    fun availability(): PushAvailability

    /** The push token to register with the vault; null while unavailable. */
    suspend fun token(): String?

    /** Called by the provider when a wake arrives: collect from the relay now. */
    var onWake: (() -> Unit)?
}

/** No push service at all (tests, and a build without one). */
object NoPushProvider : PushProvider {
    override fun availability() = PushAvailability.NOT_AVAILABLE_YET

    override suspend fun token(): String? = null

    override var onWake: (() -> Unit)? = null
}
