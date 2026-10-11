package com.vettid.core.notify

import com.vettid.core.data.prefs.NotificationMode
import com.vettid.core.data.vault.AppPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Starts and stops the on-phone service ([VaultConnectionService] on a phone; a fake in tests). */
interface ServiceControl {
    /** Starts it; false when Android does not allow it now (a background start outside the exemptions). */
    fun start(): Boolean

    fun stop()
}

/**
 * Applies the member's notification mode (ANDROID-PLAN 0.1.30, Notification modes 1): the on-phone service runs for
 * [NotificationMode.SERVICE] while the phone has a vault; Google push registers with the vault for
 * [NotificationMode.PUSH] ([PushControl]); [NotificationMode.OFF] stops everything. A change stops the old path
 * first, then starts the new one. Never started on a phone without an enrolled vault, or after a wipe.
 *
 * In the Google push mode the on-phone service keeps running until the vault holds the registration (PUSH-GATEWAY
 * §11.1 step 1: register, then stop the service), and runs again as the fallback while push cannot deliver
 * ([PushStatus.fallback]: `limit`, owner decision 2026-10-10; likewise `unavailable`, a refusal, gateway errors).
 */
class ModeController(
    private val scope: CoroutineScope,
    private val mode: Flow<NotificationMode>,
    private val phase: Flow<AppPhase>,
    private val service: ServiceControl,
    private val push: PushProvider,
    private val registration: PushControl = NoPushControl,
) {
    private var serviceOn = false
    private var pushOn = false
    private var want: Pair<NotificationMode, Boolean>? = null

    fun start() {
        scope.launch {
            combine(mode, phase, registration.status) { m, p, _ -> m to p }
                .collect { (m, p) -> enrolled(p)?.let { apply(m, it) } }
        }
    }

    /** The app came to the foreground: a start Android refused in the background is tried again. */
    fun retry() {
        val (m, e) = want ?: return
        apply(m, e)
    }

    /** [mode] for a phone with ([enrolled]) or without a vault: the old path stops before the new one starts. */
    @Synchronized
    fun apply(mode: NotificationMode, enrolled: Boolean) {
        want = mode to enrolled
        val wantPush = enrolled && mode == NotificationMode.PUSH && push.availability() == PushAvailability.AVAILABLE
        val wantService = enrolled && (mode == NotificationMode.SERVICE || wantPush && serviceForPush())
        if (!wantPush && pushOn) {
            registration.disable(wiped = !enrolled) // push.unregister, then the token is deleted
            pushOn = false
        }
        if (!wantService && serviceOn) {
            service.stop()
            serviceOn = false
        }
        if (wantService && !serviceOn) serviceOn = service.start()
        if (wantPush && !pushOn) {
            registration.enable()
            pushOn = true
        }
    }

    /**
     * In the Google push mode: the service is the fallback while push cannot deliver, and when the mode was just
     * chosen it keeps running until the vault holds the registration.
     */
    private fun serviceForPush(): Boolean {
        val reg = registration.status.value
        return reg.fallback || serviceOn && !reg.active
    }

    val running: Boolean @Synchronized get() = serviceOn

    companion object {
        /**
         * Whether [p] means a vault on this phone: true when it is open or locked (or the service is unreachable
         * with one); false when there is none (the welcome screen, a wipe, a setup in progress); null while the
         * phase is not known yet (keep what runs).
         */
        fun enrolled(p: AppPhase): Boolean? = when (p) {
            AppPhase.Starting -> null
            AppPhase.Unlocked, AppPhase.Locked, is AppPhase.Unreachable -> true
            else -> false
        }
    }
}
