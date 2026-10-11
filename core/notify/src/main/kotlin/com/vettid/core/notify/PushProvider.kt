package com.vettid.core.notify

/**
 * Whether a push service can wake this app (ANDROID-PLAN 0.1.23 D7, Notification modes 4): the "Google push" choice
 * is offered only when [AVAILABLE].
 */
enum class PushAvailability {
    AVAILABLE,

    /** The build has no push configuration (the vault's own `unavailable` is [PushRegState.UNAVAILABLE]). */
    NOT_AVAILABLE_YET,

    /** The push service this provider needs is not on the phone (Google Play services on GrapheneOS, say). */
    MISSING_SERVICES,

    /** The push service is there but needs an update. */
    SERVICES_OUTDATED,
}

/**
 * A push service that wakes the app for its vault (ANDROID-PLAN 0.1.30, Notification modes 4: contentless wakes;
 * the app then collects from its relay and decrypts on the phone as the on-phone service does). Today only the FCM
 * provider of `:core:push-fcm`, which is [PushAvailability.NOT_AVAILABLE_YET] in a build without the Firebase
 * configuration. The token goes to the member's vault only ([PushRegistrar]); the app never talks to the gateway.
 */
interface PushProvider {
    /** `platform` of `push.register` (VAULT-MESSAGING §14.1). */
    val platform: String get() = "fcm"

    fun availability(): PushAvailability

    /** The push token to register with the vault; null while unavailable or when the push service gave none. */
    suspend fun token(): String?

    /** Forgets the token, on the phone and at the push service: the next [token] is a new one. Never throws. */
    suspend fun deleteToken() {}

    /**
     * Called by the provider when a wake arrives: collect from the relay now. False when the collect could not
     * finish (no network); the provider then continues it as expedited work.
     */
    var onWake: (suspend () -> Boolean)?

    /** Called by the provider when the push service rotated the token: register the new one. */
    var onNewToken: (() -> Unit)?
}

/** No push service at all (tests, and a build without one). */
object NoPushProvider : PushProvider {
    override fun availability() = PushAvailability.NOT_AVAILABLE_YET

    override suspend fun token(): String? = null

    override var onWake: (suspend () -> Boolean)? = null

    override var onNewToken: (() -> Unit)? = null
}
