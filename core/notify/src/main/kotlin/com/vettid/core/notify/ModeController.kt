package com.vettid.core.notify

import com.vettid.core.data.prefs.NotificationMode
import com.vettid.core.data.vault.AppPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Starts and stops the on-phone service ([VaultConnectionService] on a phone; a fake in tests). */
interface ServiceControl {
    /** Starts it; false when Android does not allow it now (a background start outside the exemptions). */
    fun start(): Boolean

    fun stop()
}

/**
 * Applies the member's notification mode (ANDROID-PLAN 0.1.23, Notification modes 1): the on-phone service runs for
 * [NotificationMode.SERVICE] while the phone has a vault; Google push registers for [NotificationMode.PUSH] once it is
 * available (not yet, N2–N4); [NotificationMode.OFF] stops everything. A change stops the old path first, then starts
 * the new one. Never started on a phone without an enrolled vault, or after a wipe.
 */
class ModeController(
    private val scope: CoroutineScope,
    private val mode: Flow<NotificationMode>,
    private val phase: Flow<AppPhase>,
    private val service: ServiceControl,
    private val push: PushProvider,
) {
    private var serviceOn = false
    private var pushOn = false
    private var want: Pair<NotificationMode, Boolean>? = null

    fun start() {
        scope.launch {
            combine(mode, phase) { m, p -> m to p }
                .distinctUntilChanged()
                .collect { (m, p) -> enrolled(p)?.let { apply(m, it) } }
        }
    }

    /** The app came to the foreground: a start Android refused in the background is tried again. */
    fun retry() {
        val (m, e) = want ?: return
        synchronized(this) { if (m == NotificationMode.SERVICE && e && !serviceOn) serviceOn = service.start() }
    }

    /** [mode] for a phone with ([enrolled]) or without a vault: the old path stops before the new one starts. */
    @Synchronized
    fun apply(mode: NotificationMode, enrolled: Boolean) {
        want = mode to enrolled
        val wantService = enrolled && mode == NotificationMode.SERVICE
        val wantPush = enrolled && mode == NotificationMode.PUSH && push.availability() == PushAvailability.AVAILABLE
        if (!wantPush && pushOn) pushOn = false // push.unregister comes with VAULT-MESSAGING's push.register (N2)
        if (!wantService && serviceOn) {
            service.stop()
            serviceOn = false
        }
        if (wantService && !serviceOn) serviceOn = service.start()
        if (wantPush && !pushOn) pushOn = true
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
