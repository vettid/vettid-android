package com.vettid.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.vettid.app.di.AppScope
import com.vettid.app.notify.ReleaseUpdateNotifier
import com.vettid.core.data.lock.AppLock
import com.vettid.core.data.vault.AccountRepository
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.wipe.AndroidWipeTargets
import com.vettid.core.data.wipe.LocalWipe
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import javax.inject.Inject

@HiltAndroidApp
class VettIdApplication : Application() {
    @Inject
    lateinit var appLock: AppLock

    @Inject
    lateinit var account: AccountRepository

    @Inject
    @AppScope
    lateinit var scope: CoroutineScope

    @Inject
    lateinit var connections: ConnectionPool

    @Inject
    lateinit var releaseNotifier: ReleaseUpdateNotifier

    override fun onCreate() {
        // Before injection, so before anything reads local state: a wipe of a replaced phone that the process did
        // not live to finish is finished now (owner decision, 2026-10-05; LocalWipe).
        LocalWipe(AndroidWipeTargets(this)).resumeIfPending()
        super.onCreate()
        // Once per process: the app lock starts locked when it is on (D6); the vault's phase is read.
        scope.launch {
            appLock.start()
            account.refresh()
        }
        registerActivityLifecycleCallbacks(Foreground { onForeground() })
        // The local "Vault updates" notification, once per release (owner decision 2026-10-09).
        releaseNotifier.start()
    }

    /**
     * Back from the background (after Doze, say): connections pooled before may be dead, so none is reused; a phase
     * read that failed meanwhile ("cannot connect") is read again rather than waiting for "Try again"; the mailbox
     * collection runs again if it ended while the app was frozen, and a due owner check is re-read
     * ([AccountRepository.onForeground]).
     */
    private fun onForeground() {
        connections.evictAll()
        account.onForeground()
        if (account.phase.value is AppPhase.Unreachable) scope.launch { account.refresh() }
    }

    /** Calls [onForeground] when the first activity starts after none was started. */
    private class Foreground(private val onForeground: () -> Unit) : ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            if (started++ == 0) onForeground()
        }

        override fun onActivityStopped(activity: Activity) {
            if (started > 0) started--
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
