package com.vettid.core.data.vault

import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The vault's push state for this app (VAULT-MESSAGING 0.24.1 §14.1), as `push.register` answers it and
 * `device.list` shows it on the app's entry while it has a registration. [lastResult] is `sent`, `rate_limited` or
 * `gateway_error`, null before the first wake. The vault keeps no token, so none is here.
 */
data class PushStateView(val platform: String, val appId: String, val state: String, val lastResult: String? = null) {
    val active: Boolean get() = state == STATE_ACTIVE

    companion object {
        const val STATE_ACTIVE = "active"
        const val RESULT_GATEWAY_ERROR = "gateway_error"
    }
}

/** How the vault answered `push.register` (§14.1). */
sealed interface PushRegisterOutcome {
    data class Registered(val push: PushStateView) : PushRegisterOutcome

    /** `unavailable`: the vault's release has no push gateway (or does not know the type yet). */
    data object Unavailable : PushRegisterOutcome

    /** `gateway_error`, with the body's `retry_after` (seconds) when it has one: try again later. */
    data class GatewayError(val retryAfterSeconds: Long? = null) : PushRegisterOutcome

    /** `limit` (`push_registrations`): no `push.register` for 24 hours (owner decision 2026-10-10). */
    data object Limit : PushRegisterOutcome

    /** Any other refusal (`bad_request`, `forbidden`, `credential_required`): [code]. */
    data class Refused(val code: String) : PushRegisterOutcome

    /** No answer (no network, a timeout, the vault not open on this phone). */
    data object NoAnswer : PushRegisterOutcome
}

/**
 * What the Google push mode needs of the vault on this phone (ANDROID-PLAN 0.1.30, Notification modes 4;
 * VAULT-MESSAGING §14): the registration ops over the app's end-to-end session (the app never talks to the push
 * gateway), the vault's request for a fresh token, the registration's state, and the collect a wake asks for.
 * Implemented by [VaultManager].
 */
interface PushVault {
    val phase: StateFlow<AppPhase>

    /** `push.token-needed{reason}` from the vault (`token_gone`, `token_invalid`, `blob_invalid`): the reason. */
    val pushTokenNeeded: SharedFlow<String>

    /** `push.register{platform, token, app_id}`; never throws. */
    suspend fun pushRegister(platform: String, token: String, appId: String): PushRegisterOutcome

    /** `push.unregister{}`; false when the vault did not answer `{}` (not open, no network). */
    suspend fun pushUnregister(): Boolean

    /** `push` on this app's `device.list` entry; null without a registration. Throws [VaultFailure] when it cannot be read. */
    suspend fun pushState(): PushStateView?

    /**
     * A push wake arrived: collect from the relay now, with what the app does when open (decrypt, persist, ack,
     * the feed and its notifications). Returns true when nothing more is waiting, false when the vault could not be
     * reached; the caller bounds the time and continues later when it runs out.
     */
    suspend fun collectForWake(): Boolean
}

/** The wire forms of §14 (pure, for tests). */
object PushWire {
    const val TOKEN_NEEDED = "push.token-needed"

    /** A push state object; null when it is not one (`platform`, `app_id` and `state` are required). */
    @Suppress("ReturnCount") // one per required member
    fun state(o: JsonObject?): PushStateView? {
        if (o == null) return null
        val platform = VaultJson.str(o, "platform") ?: return null
        val appId = VaultJson.str(o, "app_id") ?: return null
        val state = VaultJson.str(o, "state") ?: return null
        return PushStateView(platform, appId, state, VaultJson.str(o, "last_result"))
    }

    /**
     * This app's push state in a `device.list` answer: `push` on the entry of [deviceId], or, when the id is not
     * known, on the one entry that has it (only the app's entry ever does, §10.3).
     */
    fun own(list: JsonObject, deviceId: String?): PushStateView? {
        val devices = (list["devices"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        val entry = if (deviceId != null) {
            devices.firstOrNull { VaultJson.str(it, "id") == deviceId }
        } else {
            devices.firstOrNull { it["push"] is JsonObject }
        }
        return state(entry?.get("push") as? JsonObject)
    }

    /** The outcome of a `push.register` that failed with [f]. */
    fun outcome(f: VaultFailure): PushRegisterOutcome = when (f.code) {
        "unavailable", "unsupported_type" -> PushRegisterOutcome.Unavailable
        "gateway_error" -> PushRegisterOutcome.GatewayError(f.retryAfterSeconds.takeIf { it > 0 })
        "limit" -> PushRegisterOutcome.Limit
        null -> PushRegisterOutcome.NoAnswer
        else -> when (f.kind) {
            FailureKind.NETWORK, FailureKind.NO_RESPONSE -> PushRegisterOutcome.NoAnswer
            else -> PushRegisterOutcome.Refused(f.code)
        }
    }

    /** The `reason` of a `push.token-needed`; `token_gone` when it has none. */
    fun reason(m: VaultMessage): String = VaultJson.str(m.body, "reason") ?: REASON_TOKEN_GONE

    const val REASON_TOKEN_GONE = "token_gone"
    const val REASON_BLOB_INVALID = "blob_invalid"
}

/**
 * `feed.event`s that arrive before this phone knows whether its vault is open (a process started by a push wake
 * collects its mailbox while `GET /api/vault/status` is still out). Taken as they come they would not notify: the
 * notifier shows nothing of a vault it does not know to be open, and the full read that follows an open brings no
 * arrivals. They are kept here, in memory only and at most [MAX], and handed back once the vault is known to be
 * open; found locked, signed out or wiped, they are dropped (nothing is kept outside an open vault).
 */
class HeldBackEvents {
    private val held = ArrayDeque<VaultMessage>()

    /** Whether [m] was kept: a `feed.event` while [phase] is not known yet. */
    @Synchronized
    fun hold(m: VaultMessage, phase: AppPhase): Boolean {
        if (m.type != FEED_EVENT || !unknown(phase)) return false
        if (held.size >= MAX) held.removeFirst()
        held.addLast(m)
        return true
    }

    /** The phase is known: what to hand to the feed now (the kept events when [phase] is open, nothing otherwise). */
    @Synchronized
    fun release(phase: AppPhase): List<VaultMessage> {
        if (unknown(phase)) return emptyList()
        val out = if (phase == AppPhase.Unlocked) held.toList() else emptyList()
        held.clear()
        return out
    }

    val waiting: Boolean @Synchronized get() = held.isNotEmpty()

    companion object {
        const val MAX = 64
        private const val FEED_EVENT = "feed.event"

        fun unknown(phase: AppPhase): Boolean = phase == AppPhase.Starting || phase is AppPhase.Unreachable
    }
}

/**
 * When a push wake's collect is done (PUSH-GATEWAY §11.1 step 2). The relay answers a collect at once when messages
 * wait, and each handled batch starts the next request; so a request that has been out for [quietNanos] found the
 * mailbox empty. The wait also covers what the collected events started (the feed's read, the notifications).
 */
class WakeSettle(private val quietNanos: Long = QUIET_NANOS) {
    /**
     * [now], [began] (the wake) and [requestSince] (the collection's request in flight, null while none is) on the
     * same monotonic clock; [busy]: a read the collected events started is still out.
     */
    fun settled(now: Long, began: Long, requestSince: Long?, busy: Boolean): Boolean {
        if (busy || requestSince == null) return false
        return now - maxOf(began, requestSince) >= quietNanos
    }

    companion object {
        const val QUIET_NANOS = 2_500_000_000L
    }
}
