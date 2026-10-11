package com.vettid.core.push.fcm

import android.content.Context
import android.content.pm.PackageManager
import com.vettid.core.notify.PushAvailability
import com.vettid.core.notify.PushProvider
import kotlinx.coroutines.withTimeoutOrNull

/** Continues a wake's collect after the push message's own time ran out ([WakeWork]: expedited work). */
fun interface WakeContinuation {
    fun enqueue()
}

/**
 * Google push (FCM) for the "Google push" notification mode (ANDROID-PLAN 0.1.30 D7, Notification modes 4;
 * PUSH-GATEWAY 0.4.1 §11.1). Wakes carry no content: on one, the app collects from its relay and decrypts on the
 * phone as the on-phone service does.
 *
 * Available only when the build carries the Firebase options ([config]; none by default, so every build without
 * them, CI included, behaves as one without push: "Not available yet") and Google Play services are on the phone
 * and current ("Needs Google Play services" on GrapheneOS without them). Firebase is initialised by hand, and Google
 * is first contacted when the member chooses the mode ([token]).
 */
class FcmPushProvider(
    context: Context,
    private val config: FcmConfig? = null,
    private val client: FcmClient = FirebaseFcmClient(context.applicationContext),
    private val installed: () -> Boolean = { playServicesInstalled(context.applicationContext) },
    private val continuation: WakeContinuation = WakeWork(context.applicationContext),
) : PushProvider {
    private var initialised: Boolean? = null

    /**
     * At process start (`Application.onCreate`, before any service of the app runs): with the options and Google
     * Play services, Firebase is initialised so that a wake that started the process finds it. No network.
     */
    fun start() {
        if (config == null || !installed()) return
        init()
    }

    @Synchronized
    private fun init(): Boolean {
        val c = config ?: return false
        return initialised ?: client.init(c).also { initialised = it }
    }

    override fun availability(): PushAvailability = when {
        !installed() -> PushAvailability.MISSING_SERVICES
        config == null -> PushAvailability.NOT_AVAILABLE_YET
        else -> when (client.playServices()) {
            PlayServices.MISSING -> PushAvailability.MISSING_SERVICES
            PlayServices.OUTDATED -> PushAvailability.SERVICES_OUTDATED
            PlayServices.OK -> if (init()) PushAvailability.AVAILABLE else PushAvailability.NOT_AVAILABLE_YET
        }
    }

    override suspend fun token(): String? {
        if (availability() != PushAvailability.AVAILABLE) return null
        return withTimeoutOrNull(TOKEN_TIMEOUT_MS) { client.token() }
    }

    override suspend fun deleteToken() {
        // Without the options nothing of Firebase was ever initialised: there is no token.
        if (!init()) return
        withTimeoutOrNull(TOKEN_TIMEOUT_MS) { client.deleteToken() }
    }

    override var onWake: (suspend () -> Boolean)? = null

    override var onNewToken: (() -> Unit)? = null

    /**
     * A push message arrived ([VettIdMessagingService]; its content is never read): the collect, for at most
     * [budgetMs]. True when it finished; otherwise it goes on as expedited work.
     */
    suspend fun wake(budgetMs: Long = WAKE_BUDGET_MS): Boolean {
        val done = withTimeoutOrNull(budgetMs) { onWake?.invoke() ?: true } ?: false
        if (!done) continuation.enqueue()
        return done
    }

    /** The continued collect ([WakeWorker]); nothing follows it: the app collects at every start anyway. */
    suspend fun continueWake(budgetMs: Long): Boolean = withTimeoutOrNull(budgetMs) { onWake?.invoke() ?: true } ?: false

    /** The push service rotated the token: the registration follows ([PushProvider.onNewToken]). */
    fun tokenRotated() {
        onNewToken?.invoke()
    }

    companion object {
        private const val GMS = "com.google.android.gms"

        /** A push message gives about 20 s (PUSH-GATEWAY §11.1 step 2): the collect stops short of it. */
        const val WAKE_BUDGET_MS = 15_000L
        private const val TOKEN_TIMEOUT_MS = 30_000L

        /** Google Play services as a package (GrapheneOS without sandboxed Play has none); enabled. */
        fun playServicesInstalled(context: Context): Boolean = try {
            context.packageManager.getApplicationInfo(GMS, 0).enabled
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
