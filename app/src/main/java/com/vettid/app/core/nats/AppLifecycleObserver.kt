package com.vettid.app.core.nats

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.vettid.app.core.storage.AppPreferencesStore
import com.vettid.app.core.storage.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes app lifecycle to auto-reconnect NATS when the app is foregrounded
 * and enforce session TTL expiry.
 *
 * When the phone locks or the app goes to background, NATS connections drop.
 * This observer reconnects when the app resumes, providing seamless experience.
 * It also tracks background duration and emits a session expired event when
 * the configured session TTL is exceeded.
 */
@Singleton
class AppLifecycleObserver @Inject constructor(
    private val natsAutoConnector: NatsAutoConnector,
    private val credentialStore: CredentialStore,
    private val appPreferencesStore: AppPreferencesStore,
    private val ownerSpaceClient: OwnerSpaceClient,
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "AppLifecycleObserver"
        // Refresh the vault's "app is alive" gate every 30s while the
        // app is foregrounded — the enclave's broadcast loop falls
        // silent if no signal arrives within ~90s, which is how peers
        // see us go offline after a force-stop or OS kill.
        private const val APP_ACTIVE_REFRESH_MS = 30_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var backgroundedAtMillis: Long = 0L

    // Periodic refresh job, only alive while the app is foregrounded.
    private var appActiveJob: kotlinx.coroutines.Job? = null

    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAtMillis = System.currentTimeMillis()
        Log.d(TAG, "App backgrounded at $backgroundedAtMillis")
        // Tell the vault to stop broadcasting our presence — the user
        // backgrounded the app, so peers should see us go offline.
        appActiveJob?.cancel()
        appActiveJob = null
        scope.launch {
            try {
                ownerSpaceClient.setAppActive(false)
            } catch (e: Exception) {
                Log.d(TAG, "setAppActive(false) failed: ${e.message}")
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        // Check session TTL expiry
        if (backgroundedAtMillis > 0) {
            val elapsedSeconds = (System.currentTimeMillis() - backgroundedAtMillis) / 1000
            val ttlSeconds = appPreferencesStore.getSessionTtlSeconds()
            if (elapsedSeconds >= ttlSeconds) {
                Log.i(TAG, "Session TTL expired (${elapsedSeconds}s >= ${ttlSeconds}s), requiring re-authentication")
                _sessionExpired.tryEmit(Unit)
                backgroundedAtMillis = 0L
                return
            }
            Log.d(TAG, "Session still valid (${elapsedSeconds}s < ${ttlSeconds}s)")
        }

        // App has come to foreground — reconnect NATS if needed
        if (credentialStore.getOfflineMode()) {
            Log.d(TAG, "Offline mode enabled, skipping NATS reconnect")
            return
        }

        if (!credentialStore.hasNatsConnection()) {
            Log.d(TAG, "No NATS credentials stored, skipping reconnect")
            return
        }

        // Let autoConnect handle the isConnected check — it holds a mutex
        // to prevent concurrent reconnect races
        Log.i(TAG, "App foregrounded, ensuring NATS connection")
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

        // Start (or restart) the app-active refresh loop so the
        // vault keeps broadcasting heartbeats while we're alive.
        appActiveJob?.cancel()
        appActiveJob = scope.launch {
            while (true) {
                try {
                    ownerSpaceClient.setAppActive(true)
                } catch (e: Exception) {
                    Log.d(TAG, "setAppActive(true) failed: ${e.message}")
                }
                kotlinx.coroutines.delay(APP_ACTIVE_REFRESH_MS)
            }
        }
    }
}
