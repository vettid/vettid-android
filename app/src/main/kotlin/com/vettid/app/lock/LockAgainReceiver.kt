package com.vettid.app.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vettid.app.di.AppScope
import com.vettid.core.data.vault.VaultRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * (Release L, owner decision 2026-10-24) "Lock it now" on the "Your vault was opened again" notification: the same
 * fresh lock as the unlock screen's button ([VaultRepository.lockAgain]). Not exported: only the app's own
 * notification reaches it.
 */
@AndroidEntryPoint
class LockAgainReceiver : BroadcastReceiver() {
    @Inject
    lateinit var vault: VaultRepository

    @Inject
    @AppScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val done = goAsync()
        // The lock is asked for (durable) before the broadcast ends; its retries run in the app's scope.
        scope.launch {
            try {
                vault.lockAgain()
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.vettid.app.action.LOCK_AGAIN"
    }
}
