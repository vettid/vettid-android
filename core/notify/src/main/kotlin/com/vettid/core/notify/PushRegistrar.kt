package com.vettid.core.notify

import com.vettid.core.data.prefs.NotificationMode
import com.vettid.core.data.prefs.PushMemory
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.PushRegisterOutcome
import com.vettid.core.data.vault.PushStateView
import com.vettid.core.data.vault.PushVault
import com.vettid.core.data.vault.PushWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the Google push registration of this phone stands (ANDROID-PLAN 0.1.30, Notification modes 4). */
enum class PushRegState {
    /** Not in the Google push mode. */
    OFF,

    /** In the mode, the vault not open on this phone yet: `push.register` follows the unlock. */
    WAITING,
    REGISTERING,

    /** The vault holds a registration (`push.state` `active`). */
    ACTIVE,

    /** After `push.token-needed`: a fresh token is being registered. */
    REGISTERING_AGAIN,

    /** `gateway_error` (or no token from the push service): tried again, 1 minute doubling to 1 hour. */
    RETRYING,

    /** `unavailable`: the vault's release has no push gateway. */
    UNAVAILABLE,

    /** `limit` (`push_registrations`): nothing is sent for 24 hours. */
    LIMITED,

    /** Another refusal (`bad_request`, `forbidden`): not retried before the vault next opens. */
    REFUSED,
}

/** [state], and the vault's `last_result` of its last wake while [PushRegState.ACTIVE]. */
data class PushStatus(val state: PushRegState = PushRegState.OFF, val lastResult: String? = null) {
    val active: Boolean get() = state == PushRegState.ACTIVE

    /**
     * Push cannot deliver for now: the on-phone service runs meanwhile (owner decision 2026-10-10 for `limit`; the
     * same for the other answers that leave the phone without a registration).
     */
    val fallback: Boolean
        get() = state == PushRegState.UNAVAILABLE || state == PushRegState.LIMITED || state == PushRegState.REFUSED ||
            state == PushRegState.RETRYING

    /** The vault's last wake did not reach the gateway (`last_result` `gateway_error`). */
    val gatewayUnreachable: Boolean get() = active && lastResult == PushStateView.RESULT_GATEWAY_ERROR
}

/** What [ModeController] asks of the registration. */
interface PushControl {
    val status: StateFlow<PushStatus>

    /** The Google push mode is in effect on a phone with a vault. */
    fun enable()

    /** The mode was left ([wiped]: the phone has no vault any more, so there is no vault to tell). */
    fun disable(wiped: Boolean)

    /** Settings → Notifications is in front: the status follows `push` on the app's `device.list` entry. */
    suspend fun refresh() = Unit
}

/** No registration (tests, a build without push). */
object NoPushControl : PushControl {
    override val status: StateFlow<PushStatus> = MutableStateFlow(PushStatus())

    override fun enable() = Unit

    override fun disable(wiped: Boolean) = Unit
}

/** [PushMemory] in the device preferences. */
interface PushMemoryStore {
    suspend fun load(): PushMemory

    suspend fun save(m: PushMemory)
}

/** The retry schedule after `gateway_error` (ANDROID-PLAN 0.1.30): 1 minute doubling to 1 hour. */
class PushRetryBackoff {
    private var attempt = 0

    /** The next wait: the vault's `retry_after` (1 s to 1 hour) when it gave one, else the schedule's. */
    fun nextDelayMs(retryAfterSeconds: Long? = null): Long {
        val own = if (attempt >= MAX_SHIFT) MAX_MS else minOf(BASE_MS shl attempt, MAX_MS)
        attempt++
        return retryAfterSeconds?.coerceIn(1, MAX_MS / MS_PER_S)?.times(MS_PER_S) ?: own
    }

    fun reset() {
        attempt = 0
    }

    companion object {
        const val BASE_MS = 60_000L
        const val MAX_MS = 3_600_000L
        private const val MAX_SHIFT = 6
        private const val MS_PER_S = 1000L
    }
}

/**
 * The Google push registration of this phone with its vault (ANDROID-PLAN 0.1.30, Notification modes 4;
 * VAULT-MESSAGING 0.24.1 §14; PUSH-GATEWAY 0.4.1 §11.1). The push token goes to the member's own vault over the
 * end-to-end session, never to the gateway or anywhere else, and is not kept by the app.
 *
 * - **In the mode**: `push.register{platform, token, app_id}` whenever the vault opens on this phone (the vault
 *   answers an unchanged token from its state, without the gateway), when the push service rotates the token
 *   ([PushProvider.onNewToken]) and when the vault asks (`push.token-needed`; with a new token unless only the
 *   gateway's blob was invalid).
 * - **Answers**: `unavailable` and other refusals wait for the next open; `gateway_error` is tried again after its
 *   `retry_after`, else 1 minute doubling to 1 hour; `limit` stops everything for 24 hours, across restarts.
 * - **Leaving the mode**: `push.unregister`, then the token is deleted at the push service; when the vault cannot be
 *   told now (locked, no network) the token is deleted all the same and the vault is told at its next open.
 * - **In the mode without a usable push service** (ANDROID-PLAN 0.1.32; Google Play services gone, or a build without
 *   Google push): [disable] as when leaving, so a registration the vault may still hold is dropped at its next open,
 *   and only then (a phone that never registered sends nothing). [enable] again when the push service is back.
 * - **A wake** ([onWake]) collects only in the mode; a wake for a registration this phone no longer wants drops it.
 */
@Suppress("TooManyFunctions")
class PushRegistrar(
    private val scope: CoroutineScope,
    private val provider: PushProvider,
    private val vault: PushVault,
    /** `app_id`: the application id of this build. */
    private val appId: String,
    private val memory: PushMemoryStore,
    private val mode: suspend () -> NotificationMode,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val backoff: PushRetryBackoff = PushRetryBackoff(),
) : PushControl {
    private val mutex = Mutex()
    private val flow = MutableStateFlow(PushStatus())
    override val status: StateFlow<PushStatus> = flow.asStateFlow()

    private var enabled = false
    private var retry: Job? = null

    fun start() {
        provider.onWake = { onWake() }
        provider.onNewToken = { scope.launch { mutex.withLock { if (enabled) register() } } }
        scope.launch { vault.phase.collect { if (it == AppPhase.Unlocked) mutex.withLock { onOpen() } } }
        scope.launch { vault.pushTokenNeeded.collect { reason -> mutex.withLock { onTokenNeeded(reason) } } }
    }

    override fun enable() {
        scope.launch {
            mutex.withLock {
                if (enabled) return@withLock
                enabled = true
                val m = memory.load()
                // Leaving and coming back before the vault was told: there is nothing to drop any more.
                if (m.unregisterPending) memory.save(m.copy(unregisterPending = false))
                set(if (m.registered) PushRegState.ACTIVE else PushRegState.WAITING)
                register()
            }
        }
    }

    override fun disable(wiped: Boolean) {
        scope.launch { mutex.withLock { drop(wiped) } }
    }

    override suspend fun refresh() = mutex.withLock {
        if (!enabled || vault.phase.value != AppPhase.Unlocked) return@withLock
        val push = try {
            vault.pushState()
        } catch (_: com.vettid.core.data.vault.VaultFailure) {
            return@withLock
        }
        when {
            push?.active == true && flow.value.active -> flow.value = PushStatus(PushRegState.ACTIVE, push.lastResult)
            // The vault erased the registration (a dead token; its `push.token-needed` is still on its way).
            push == null && flow.value.active -> {
                remember { it.copy(registered = false) }
                set(PushRegState.REGISTERING_AGAIN)
                register()
            }
        }
    }

    /** A wake: collect, in the mode only. True when done; false when it could not finish (the provider continues it). */
    suspend fun onWake(): Boolean {
        if (mode() != NotificationMode.PUSH) {
            // A registration this phone no longer wants still wakes it: dropped, and nothing is collected for it.
            scope.launch { mutex.withLock { if (!enabled) drop(wiped = false, force = true) } }
            return true
        }
        return vault.collectForWake()
    }

    private suspend fun onOpen() {
        if (enabled) {
            register()
        } else if (memory.load().unregisterPending) {
            drop(wiped = false)
        }
    }

    private suspend fun onTokenNeeded(reason: String) {
        val m = memory.load()
        // The vault holds no registration any more: nothing is left to drop, whatever the mode.
        memory.save(m.copy(registered = false, unregisterPending = false))
        if (!enabled) return
        // A dead token is replaced; with `blob_invalid` the token is fine and only the gateway's blob is renewed.
        if (reason != PushWire.REASON_BLOB_INVALID) provider.deleteToken()
        set(PushRegState.REGISTERING_AGAIN)
        register()
    }

    /** One `push.register` with the current token, under [mutex]. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private suspend fun register() {
        retry?.cancel()
        retry = null
        if (!enabled) return
        val m = memory.load()
        val now = nowMs()
        if (now < m.limitedUntilMs) {
            // `limit`: nothing is sent before the 24 hours are over, even after a restart.
            set(PushRegState.LIMITED)
            retryIn(m.limitedUntilMs - now)
            return
        }
        val again = flow.value.state == PushRegState.REGISTERING_AGAIN
        if (vault.phase.value != AppPhase.Unlocked) {
            if (!again) set(if (m.registered) PushRegState.ACTIVE else PushRegState.WAITING)
            return
        }
        if (!again && !flow.value.active) set(PushRegState.REGISTERING)
        val token = provider.token()
        if (token == null) {
            // The push service gave none (no network, Google Play services busy): tried again like a gateway error.
            // When the push service itself is gone (Google Play services disabled or removed while the app runs),
            // it is said even of a known registration: the mode controller then falls back to the on-phone service
            // and has the registration dropped ([ModeController]).
            if (!m.registered || provider.availability() != PushAvailability.AVAILABLE) set(PushRegState.RETRYING)
            retryIn(backoff.nextDelayMs())
            return
        }
        when (val o = vault.pushRegister(provider.platform, token, appId)) {
            is PushRegisterOutcome.Registered -> {
                backoff.reset()
                remember { it.copy(registered = o.push.active, limitedUntilMs = 0) }
                flow.value = PushStatus(if (o.push.active) PushRegState.ACTIVE else PushRegState.REFUSED, o.push.lastResult)
            }
            PushRegisterOutcome.Unavailable -> {
                remember { it.copy(registered = false) }
                set(PushRegState.UNAVAILABLE)
            }
            is PushRegisterOutcome.GatewayError -> {
                remember { it.copy(registered = false) }
                set(PushRegState.RETRYING)
                retryIn(backoff.nextDelayMs(o.retryAfterSeconds))
            }
            PushRegisterOutcome.Limit -> {
                remember { it.copy(registered = false, limitedUntilMs = now + LIMIT_PAUSE_MS) }
                set(PushRegState.LIMITED)
                retryIn(LIMIT_PAUSE_MS)
            }
            is PushRegisterOutcome.Refused -> {
                // The gateway refused the token itself (`bad_request`): the next attempt brings a new one.
                if (o.code == "bad_request") provider.deleteToken()
                remember { it.copy(registered = false) }
                set(PushRegState.REFUSED)
            }
            // No answer: a failed request changes nothing at the vault (§14.1), so what was registered still is.
            PushRegisterOutcome.NoAnswer -> {
                if (!m.registered || again) set(if (again) PushRegState.REGISTERING_AGAIN else PushRegState.RETRYING)
                retryIn(backoff.nextDelayMs())
            }
        }
    }

    /**
     * Leaves the mode, under [mutex]: `push.unregister` (the old path stops first), then the token is deleted at the
     * push service. [force]: a stray wake showed that the vault still holds a registration.
     */
    private suspend fun drop(wiped: Boolean, force: Boolean = false) {
        retry?.cancel()
        retry = null
        backoff.reset()
        val was = enabled
        enabled = false
        val m = memory.load()
        val had = was || force || m.registered || m.unregisterPending
        if (wiped) {
            // No vault on this phone any more: the vault erased the registration when it removed the app
            // (VAULT-MESSAGING §14.1) or was deleted with it (§12.5). Only the token is left to delete.
            if (m != PushMemory()) memory.save(PushMemory())
            if (had) provider.deleteToken()
        } else if (had) {
            val told = vault.pushUnregister()
            memory.save(m.copy(registered = false, unregisterPending = !told))
            provider.deleteToken()
        }
        set(PushRegState.OFF)
    }

    private fun retryIn(ms: Long) {
        retry = scope.launch {
            delay(ms)
            mutex.withLock {
                retry = null // this job: register() must not cancel it
                register()
            }
        }
    }

    private suspend fun remember(change: (PushMemory) -> PushMemory) {
        val m = memory.load()
        val n = change(m)
        if (n != m) memory.save(n)
    }

    private fun set(s: PushRegState) {
        if (flow.value.state != s) flow.value = PushStatus(s)
    }

    companion object {
        /** After `limit`: no `push.register` for 24 hours (VAULT-MESSAGING §14.1 check 5; owner decision 2026-10-10). */
        const val LIMIT_PAUSE_MS = 24L * 60 * 60 * 1000
    }
}
