package com.vettid.core.notify

import com.vettid.core.data.prefs.NotificationMode
import com.vettid.core.data.vault.AppPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
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
 *
 * Google push chosen, but the push service not usable on this phone (ANDROID-PLAN 0.1.32, Notification modes 4; owner
 * decision 2026-10-11): Google Play services were disabled, removed or need an update, or the app was updated to a
 * build without Google push. The on-phone service then runs as the fallback, and the vault is told to drop a
 * registration it may still hold (`push.unregister`, at its next open when it cannot be told now), so that it stops
 * waking a phone that cannot be woken. **The member's mode is never changed**: it stays Google push, and the
 * registration starts again by itself once the push service is back (seen at the next [apply]: a phase or
 * registration change, or the app coming to the foreground, [retry]).
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

    /** [ServiceControl.stop] was called for this phone without a vault. */
    private var stoppedWithoutVault = false
    private var pushOn = false

    /** Google push is the mode on a phone with a vault, and the push service is not usable. */
    private var pushUnavailable = false
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
        val pushMode = enrolled && mode == NotificationMode.PUSH
        val wantPush = pushMode && push.availability() == PushAvailability.AVAILABLE
        // The mode is Google push and the push service is not usable: the on-phone service is the fallback.
        val unavailable = pushMode && !wantPush
        val wantService = enrolled && (mode == NotificationMode.SERVICE || unavailable || wantPush && serviceForPush())
        leavePush(wantPush, unavailable, enrolled)
        if (!wantService) stopService(enrolled)
        stoppedWithoutVault = !enrolled
        if (wantService && !serviceOn) serviceOn = service.start()
        if (wantPush && !pushOn) {
            registration.enable()
            pushOn = true
        }
    }

    /**
     * The service is not wanted: it stops if this process started it. Without a vault it is stopped also when this
     * process never started it (once): Android restarts the service by itself in a new process, and a build before
     * 2026-10-11 could leave its notification behind ([ServiceControl.stop] cancels it).
     */
    private fun stopService(enrolled: Boolean) {
        val leftover = !enrolled && !stoppedWithoutVault
        if (!serviceOn && !leftover) return
        service.stop()
        serviceOn = false
    }

    /** The registration that is no longer wanted, or cannot work, is dropped before anything else starts. */
    private fun leavePush(wantPush: Boolean, unavailable: Boolean, enrolled: Boolean) {
        if (!wantPush && pushOn) {
            registration.disable(wiped = !enrolled) // push.unregister, then the token is deleted
            pushOn = false
        } else if (unavailable && !pushUnavailable) {
            // Found so at the start of the process: the vault may still hold a registration from before.
            registration.disable(wiped = false)
        }
        pushUnavailable = unavailable
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

/**
 * A phone without a vault shows no notification of VettID: whenever the phase is [AppPhase.SignedOut] (the welcome
 * screen: at app start with no vault, after a wipe, a deleted vault, a dropped setup code), [cancelAll] runs. This
 * also clears what an earlier build left behind (owner report 2026-10-11: the on-phone service's notification on a
 * phone that had given its vault away). A running foreground service keeps its notification through Android's
 * `cancelAll`; it removes its own when it stops ([ServiceNotice]).
 */
class NoVaultSweep(private val scope: CoroutineScope, private val phase: Flow<AppPhase>, private val cancelAll: () -> Unit) {
    fun start() {
        scope.launch { phase.filter { it == AppPhase.SignedOut }.collect { runCatching { cancelAll() } } }
    }
}
