package com.vettid.app.core.nats

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.vettid.app.core.storage.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes app lifecycle to auto-reconnect NATS when the app is foregrounded.
 *
 * When the phone locks or the app goes to background, NATS connections drop.
 * This observer reconnects when the app resumes, providing seamless experience.
 */
@Singleton
class AppLifecycleObserver @Inject constructor(
    private val natsAutoConnector: NatsAutoConnector,
    private val credentialStore: CredentialStore
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "AppLifecycleObserver"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStart(owner: LifecycleOwner) {
        // App has come to foreground
        if (credentialStore.getOfflineMode()) {
            Log.d(TAG, "Offline mode enabled, skipping NATS reconnect")
            return
        }

        if (!credentialStore.hasNatsConnection()) {
            Log.d(TAG, "No NATS credentials stored, skipping reconnect")
            return
        }

        if (natsAutoConnector.isConnected()) {
            Log.d(TAG, "NATS already connected, no reconnect needed")
            return
        }

        Log.i(TAG, "App foregrounded with NATS disconnected, attempting reconnect")
        scope.launch {
            val result = natsAutoConnector.autoConnect()
            when (result) {
                is NatsAutoConnector.ConnectionResult.Success -> {
                    Log.i(TAG, "NATS reconnected on app resume")
                }
                else -> {
                    Log.w(TAG, "NATS reconnect on resume failed: $result")
                }
            }
        }
    }
}
