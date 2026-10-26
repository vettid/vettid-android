package com.vettid.app

import android.app.Activity
import android.content.Context
import android.os.Bundle
import com.vettid.app.di.AppScope
import com.vettid.app.distribution.DistributionApplication
import com.vettid.app.distribution.PushStartup
import com.vettid.app.notify.ReleaseUpdateNotifier
import com.vettid.core.data.calls.CallAccess
import com.vettid.core.data.lock.AppLock
import com.vettid.core.data.lock.ScreenshotAllowance
import com.vettid.core.data.vault.AccountRepository
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.CrashScrubber
import com.vettid.core.data.wipe.AndroidWipeTargets
import com.vettid.core.data.wipe.LocalWipe
import com.vettid.core.notify.Channels
import com.vettid.core.notify.FeedNotifier
import com.vettid.core.notify.ModeController
import com.vettid.core.notify.NoVaultSweep
import com.vettid.core.notify.PushRegistrar
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.vettid.app.net.PoolEviction
import javax.inject.Inject

@HiltAndroidApp
class VettIdApplication : DistributionApplication() {
    @Inject
    lateinit var appLock: AppLock

    @Inject
    lateinit var account: AccountRepository

    @Inject
    lateinit var screenshots: ScreenshotAllowance

    @Inject
    @AppScope
    lateinit var scope: CoroutineScope

    @Inject
    lateinit var connections: PoolEviction

    @Inject
    lateinit var releaseNotifier: ReleaseUpdateNotifier

    @Inject
    lateinit var feedNotifier: FeedNotifier

    @Inject
    lateinit var changeNotifier: com.vettid.app.notify.ChangeDecisionNotifier

    @Inject
    lateinit var pendingLock: com.vettid.app.notify.PendingLockNotifier

    @Inject
    lateinit var modes: ModeController

    @Inject
    lateinit var noVaultSweep: NoVaultSweep

    @Inject
    lateinit var pushStartup: PushStartup

    @Inject
    lateinit var pushRegistrar: PushRegistrar

    /**
     * Injected so that it exists from the start of the process: in a build with calls it is what listens for an
     * incoming call (CALLS-PLAN C1); in a build without, an object that does nothing.
     */
    @Inject
    lateinit var calls: CallAccess

    /**
     * The first app code of every process of the app (before any content provider and before [onCreate]): a crash
     * from here on reaches Android's handler with class names and stack frames only, never a message (the owner's
     * rule, 2026-10-23; [CrashScrubber]).
     */
    override fun attachBaseContext(base: Context) {
        CrashScrubber.install()
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        // Before injection, so before anything reads local state: a wipe of a replaced phone that the process did
        // not live to finish is finished now (owner decision, 2026-10-05; LocalWipe).
        LocalWipe(AndroidWipeTargets(this)).resumeIfPending()
        super.onCreate()
        // Once per process: the app lock starts locked when it is on (D6); the vault's phase is read.
        // The phase is read whatever became of the app lock's start: a failure there (an I/O error) used to end this
        // coroutine before the phase was read, and the app stayed on "Opening VettID" (A6 audit P4). Still one after
        // the other, so that nothing of the open app is composed before the lock has said whether it is on.
        scope.launch {
            try {
                appLock.start()
            } finally {
                withContext(NonCancellable) { account.refresh() }
            }
        }
        // "Allow screenshots" ends when the vault is no longer open (A6 audit S1).
        scope.launch { account.phase.collect { if (it != AppPhase.Unlocked) screenshots.vaultClosed() } }
        registerActivityLifecycleCallbacks(Foreground { onForeground() })
        // The local "Vault updates" notification, once per release (owner decision 2026-10-09).
        releaseNotifier.start()
        // The notification modes (ANDROID-PLAN 0.1.23 D7): the channels, feed items as notifications, and the
        // on-phone service while the mode is the service and the phone has a vault.
        runCatching { Channels.ensure(this) }
        feedNotifier.start()
        // VAULT-MESSAGING 0.45.0 §11.14.9 step 7: a lost answer's decision while the app is in the background.
        changeNotifier.start()
        // The member's pending lock: its job while it is pending (it survives the process), and its notice.
        pendingLock.start()
        // Google push (ANDROID-PLAN 0.1.30; the `full` distribution): in a build with the Firebase options, Firebase
        // is initialised here, before any service runs, so that a wake that started this process is handled; the
        // registration with the vault follows the mode. Without the options, and in the `foss` distribution, neither
        // does anything.
        runCatching { pushStartup.start() }
        pushRegistrar.start()
        modes.start()
        // A phone without a vault (now, or after a wipe) shows no notification of VettID.
        noVaultSweep.start()
    }

    /**
     * Back from the background (after Doze, say): connections pooled before may be dead, so none is reused; a phase
     * read that failed meanwhile ("cannot connect") is read again rather than waiting for "Try again"; the mailbox
     * collection runs again if it ended while the app was frozen, and a due owner check is re-read
     * ([AccountRepository.onForeground]).
     */
    private fun onForeground() {
        // Off the main thread: closing a live TLS connection is a network write (NetworkOnMainThreadException).
        connections.evict()
        screenshots.check() // an allowance whose hour ended while the phone slept
        account.onForeground()
        modes.retry() // a service start Android refused in the background
        if (account.phase.value is AppPhase.Unreachable) scope.launch { account.refresh() }
    }

    /** Calls [onForeground] when the first activity starts after none was started. */
    private class Foreground(private val onForeground: () -> Unit) : ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            com.vettid.app.notify.AppForeground.visible = true
            if (started++ == 0) onForeground()
        }

        override fun onActivityStopped(activity: Activity) {
            if (started > 0) started--
            com.vettid.app.notify.AppForeground.visible = started > 0
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
