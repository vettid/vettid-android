package com.vettid.core.data.presence

import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.vaultGuard
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.presenceGet
import com.vettid.core.vault.presenceQuery
import com.vettid.core.vault.presenceSet
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/** [PresenceOps] over the vault client. */
internal class VaultPresenceOps(private val api: com.vettid.core.vault.VaultApi) : PresenceOps {
    override suspend fun get() = api.presenceGet()

    override suspend fun set(version: Long, state: String?, share: String?, except: List<String>?) =
        api.presenceSet(version, state, share, except)

    override suspend fun query(connectionId: String) = api.presenceQuery(connectionId)
}

/**
 * [PresenceRepository] over [PresenceOps] (ANDROID-PLAN §4 "Presence"; VAULT-MESSAGING §10.17).
 *
 * - **Whether there is presence.** No message lists a vault release's features, so after every unlock the vault is asked
 *   for the policy ([start]): an answer means presence, `unsupported_type` means none until the next unlock.
 * - **Memory only.** The policy and the readings are cleared by [clear] (a lock, the hold, a wipe) and never written.
 * - **The hold.** While [closed] (the vault is not open, or the daily owner check is due or held) nothing is sent: a
 *   query reads as unknown at once, a change is refused with `owner_check_required`.
 * - **Asking.** A query makes the reading [PresenceReading.Checking] until a `presence.result` with the answer's `ping_id`
 *   arrives ([onEvent]) or `exp` passes (then [PresenceReading.Unknown]). A second query for the same connection inside
 *   a minute sends nothing (the vault would answer with the first `ping_id` and repeat its result): the reading stands.
 */
@Suppress("TooManyFunctions")
class PresenceManager(
    private val scope: CoroutineScope,
    private val ops: suspend () -> PresenceOps,
    private val closed: () -> Boolean = { false },
    private val now: () -> Instant = Instant::now,
    /** The waits between the tries of the opening `presence.get`; the last one repeats. */
    private val retryDelays: List<Duration> = RETRY,
) : PresenceRepository {
    private class Ask(val pingId: String, val askedAt: Instant, val expiry: Job) {
        /** Drops a result after a minute (a reading is not shown as current longer). */
        var stale: Job? = null

        /** Ends the minute in which asking again would send nothing. */
        var lapse: Job? = null
    }

    private val lock = Any()
    private val availabilityFlow = MutableStateFlow(PresenceAvailability.UNKNOWN)
    private val policyFlow = MutableStateFlow<PresencePolicy?>(null)
    private val readingFlow = MutableStateFlow<Map<String, PresenceReading>>(emptyMap())
    private val writes = Mutex()
    private val reads = Mutex()

    /** Under [lock]: the latest answered question per connection, the ones being sent, and results that came before their answer. */
    private val asks = mutableMapOf<String, Ask>()
    private val sending = mutableSetOf<String>()
    private val strays = mutableMapOf<String, PresenceWire.Result>()

    /** Bumped by [clear]: an answer to something sent before it is not applied after it. */
    private var generation = 0

    @Volatile
    private var policyQueued = false
    private var starter: Job? = null
    private var watcher: Job? = null
    private val recentFlow = MutableStateFlow<Set<String>>(emptySet())

    override val availability: StateFlow<PresenceAvailability> = availabilityFlow.asStateFlow()
    override val policy: StateFlow<PresencePolicy?> = policyFlow.asStateFlow()
    override val readings: StateFlow<Map<String, PresenceReading>> = readingFlow.asStateFlow()
    override val recent: StateFlow<Set<String>> = recentFlow.asStateFlow()

    /** Forgets everything: the vault locked, was held or wiped. */
    fun clear() {
        synchronized(lock) {
            generation++
            policyQueued = false
            starter?.cancel()
            starter = null
            watcher?.cancel()
            watcher = null
            recentFlow.value = emptySet()
            asks.values.forEach {
                it.expiry.cancel()
                it.stale?.cancel()
                it.lapse?.cancel()
            }
            asks.clear()
            sending.clear()
            strays.clear()
            policyFlow.value = null
            readingFlow.value = emptyMap()
            availabilityFlow.value = PresenceAvailability.UNKNOWN
        }
    }

    /** After an unlock and after the hold: asks whether the vault has presence and reads the policy (quietly). */
    fun start() {
        synchronized(lock) {
            starter?.cancel()
            starter = scope.launch { probe() }
        }
    }

    /**
     * The opening `presence.get`, tried again after a failure that says nothing about the feature (transport, no response, an
     * unreadable answer) while the vault is open; `unsupported_type` ends it as "no presence", a closed vault ends it (the
     * check passing calls [start] again).
     */
    private suspend fun probe() {
        var n = 0
        while (true) {
            try {
                refreshPolicy()
                return
            } catch (e: VaultFailure) {
                if (e.kind == FailureKind.NOT_SUPPORTED || e.kind == FailureKind.OWNER_CHECK_REQUIRED) return
            }
            delay(retryDelays[minOf(n++, retryDelays.size - 1)].toMillis())
        }
    }

    /** `sync.event{presence.changed}` re-reads the policy; `presence.result` answers a question. */
    fun onEvent(m: VaultMessage) {
        when (m.type) {
            "sync.event" -> if (VaultJson.str(m.body, "kind") == CHANGED && changedBeyondHeld(m)) refreshPolicyQuietly()
            "presence.result" -> PresenceWire.result(m.body, now())?.let { onResult(it) }
        }
    }

    /** Presence is on, and the event's `version` (when it names one) is newer than the policy held: our own change echoes back. */
    private fun changedBeyondHeld(m: VaultMessage): Boolean {
        if (availabilityFlow.value != PresenceAvailability.AVAILABLE) return false
        val version = (m.body["version"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
        val held = policyFlow.value?.version
        return version == null || held == null || version > held
    }

    private fun refreshPolicyQuietly() {
        if (policyQueued) return
        policyQueued = true
        scope.launch {
            policyQueued = false
            runCatching { refreshPolicy() }
        }
    }

    override suspend fun refreshPolicy() {
        reads.withLock {
            ensureSupported()
            readPolicy()
        }
    }

    /** Nothing is sent while the vault is not open or the owner check is due or held (§3.6.3): `owner_check_required`, unsent. */
    private fun ensureOpen() {
        if (closed()) {
            watchClosed()
            throw VaultFailure(FailureKind.OWNER_CHECK_REQUIRED, "owner_check_required")
        }
    }

    /**
     * Something was held back because the vault is closed (not open, or the owner check due or held): when [closed] ends, by
     * whatever means (the check passing, or its deadline moving), the probe starts again. [closed] is looked at every few
     * seconds meanwhile; a lock or hold ([clear]) ends the watch.
     */
    private fun watchClosed() {
        synchronized(lock) {
            if (watcher?.isActive == true) return
            watcher = scope.launch {
                while (closed()) delay(WATCH.toMillis())
                start()
            }
        }
    }

    /** A vault whose release has no presence is asked no more until the next unlock. */
    private fun ensureSupported() {
        if (availabilityFlow.value == PresenceAvailability.UNSUPPORTED) throw VaultFailure(FailureKind.NOT_SUPPORTED)
    }

    private fun markUnsupported(gen: Int) {
        synchronized(lock) { if (gen == generation) availabilityFlow.value = PresenceAvailability.UNSUPPORTED }
    }

    /** `presence.get`, applied unless a lock came meanwhile. Not sent while [closed]. */
    private suspend fun readPolicy(): PresencePolicy {
        ensureOpen()
        val gen = synchronized(lock) { generation }
        val body = try {
            vaultGuard(resend = true) { ops().get() }
        } catch (e: VaultFailure) {
            if (e.kind == FailureKind.NOT_SUPPORTED) markUnsupported(gen)
            throw e
        }
        val read = PresenceWire.policy(body) ?: throw VaultFailure(FailureKind.OTHER, "unreadable_result")
        synchronized(lock) {
            if (gen == generation) {
                policyFlow.value = read
                availabilityFlow.value = PresenceAvailability.AVAILABLE
                // Readings that waited for the check are asked again.
                readingFlow.value = readingFlow.value.filterValues { it != PresenceReading.Closed }
            }
        }
        return read
    }

    override suspend fun update(change: (PresencePolicy) -> PresencePolicy) {
        writes.withLock {
            ensureSupported()
            ensureOpen()
            var current = policyFlow.value ?: readPolicy()
            var attempt = 0
            while (true) {
                val next = change(current).let { it.copy(except = PresencePolicy.cleaned(it.except)) }
                if (next.copy(version = current.version) == current) return
                try {
                    send(current, next)
                    return
                } catch (e: VaultFailure) {
                    // A conflict: read the policy again and put the member's change on it, once (ANDROID-PLAN §4 "Presence").
                    if (e.kind != FailureKind.CONFLICT || ++attempt > 1) throw e
                    current = readPolicy()
                }
            }
        }
    }

    /** `presence.set` of what [next] changes from [current], with [current]'s version; the answer's version is [next]'s. */
    private suspend fun send(current: PresencePolicy, next: PresencePolicy) {
        val gen = synchronized(lock) { generation }
        val body = vaultGuard {
            ops().set(
                current.version,
                next.state.wire.takeIf { next.state != current.state },
                next.share.wire.takeIf { next.share != current.share },
                next.except.takeIf { it != current.except },
            )
        }
        val version = PresenceWire.version(body)
        // An answer without a version is not guessed at: the policy is read again.
        if (version == null) {
            readPolicy()
        } else {
            synchronized(lock) { if (gen == generation) policyFlow.value = next.copy(version = version) }
        }
    }

    override suspend fun query(connectionId: String) {
        val gen = synchronized(lock) { generationToAsk(connectionId) } ?: return
        try {
            register(connectionId, gen, ask(connectionId, gen))
        } finally {
            // Whatever happened (a lock, a cancellation), the connection is not left "being sent".
            synchronized(lock) { sending -= connectionId }
        }
    }

    /** Sends `presence.query` ("Checking…" meanwhile); null when it cannot be sent or is refused. */
    private suspend fun ask(connectionId: String, gen: Int): Asking {
        if (closed()) {
            watchClosed()
            return Asking.Not(PresenceReading.Closed)
        }
        synchronized(lock) { sending += connectionId }
        setReading(connectionId, gen, PresenceReading.Checking)
        return try {
            val body = vaultGuard(resend = true) { ops().query(connectionId) }
            PresenceWire.asked(body)?.let { Asking.Sent(it) } ?: Asking.Not(PresenceReading.Failed(FailureKind.OTHER, "unreadable_result"))
        } catch (e: VaultFailure) {
            // The vault itself says the check is due: closed, not a failure of ours.
            val checkDue = e.kind == FailureKind.OWNER_CHECK_REQUIRED
            Asking.Not(if (checkDue) PresenceReading.Closed else PresenceReading.Failed(e.kind, e.shownCode))
        }
    }

    /** What came of sending the question: the vault's answer, or the reading that stands instead. */
    private sealed interface Asking {
        class Sent(val asked: PresenceWire.Asked) : Asking

        class Not(val reading: PresenceReading) : Asking
    }

    /**
     * The answer to the query, in one step under [lock]: the ask is registered before its timer can run, a result that came
     * first is taken, and the connection is no longer "being sent". Without an answer the reading is unknown.
     */
    private fun register(connectionId: String, gen: Int, asked: Asking) {
        synchronized(lock) {
            sending -= connectionId
            if (gen == generation) track(connectionId, asked)
        }
    }

    /** Under [lock]. */
    private fun track(connectionId: String, answer: Asking) {
        val asked = (answer as? Asking.Sent)?.asked
        if (asked == null) {
            readingFlow.value = readingFlow.value + (connectionId to (answer as Asking.Not).reading)
            return
        }
        val at = now()
        val wait = expiryWait(asked.exp, at)
        val expiry = scope.launch {
            delay(wait.toMillis())
            expire(connectionId, asked.pingId)
        }
        val ask = Ask(asked.pingId, at, expiry)
        asks.put(connectionId, ask)?.let {
            it.expiry.cancel()
            it.stale?.cancel()
            it.lapse?.cancel()
        }
        recentFlow.value = recentFlow.value + connectionId
        ask.lapse = scope.launch {
            delay(MINUTE.toMillis())
            synchronized(lock) { if (asks[connectionId] === ask) recentFlow.value = recentFlow.value - connectionId }
        }
        // The result may have arrived before this answer did.
        strays.remove(connectionId)?.takeIf { it.pingId == asked.pingId }?.let { applyResult(it) }
    }

    /**
     * Under [lock]: the generation to ask under, or null when nothing is to be sent (no presence, being sent, or a reading
     * under a minute old).
     */
    private fun generationToAsk(id: String): Int? {
        if (availabilityFlow.value != PresenceAvailability.AVAILABLE || id in sending) return null
        val ask = asks[id]
        val fresh = ask != null && Duration.between(ask.askedAt, now()) < MINUTE && readingFlow.value.containsKey(id)
        return if (fresh) null else generation
    }

    /** How long to wait for the result: until `exp` (the vault says 30 s), never longer than that, whatever the clocks say. */
    private fun expiryWait(exp: Instant?, at: Instant): Duration {
        val left = exp?.let { Duration.between(at, it) } ?: return EXP
        // An `exp` already past, or further than the vault's 30 s, is clock skew (or a reused ping): wait the full time.
        return if (left <= Duration.ZERO || left > EXP) EXP else left
    }

    private fun setReading(id: String, gen: Int, reading: PresenceReading) {
        synchronized(lock) { if (gen == generation) readingFlow.value = readingFlow.value + (id to reading) }
    }

    /** `exp` passed with no result for [pingId]: unknown. */
    private fun expire(id: String, pingId: String) {
        synchronized(lock) {
            if (asks[id]?.pingId == pingId && readingFlow.value[id] == PresenceReading.Checking) {
                readingFlow.value = readingFlow.value + (id to PresenceReading.Unknown)
            }
        }
    }

    private fun onResult(r: PresenceWire.Result) {
        synchronized(lock) {
            val ask = asks[r.connectionId]
            when {
                ask?.pingId == r.pingId -> applyResult(r)
                // Before its answer: kept (the latest one) for that answer, if it is for it.
                r.connectionId in sending -> strays[r.connectionId] = r
            }
        }
    }

    /** Under [lock]: a result for the connection's current ping, while it is waited for (or repeated by the vault). */
    private fun applyResult(r: PresenceWire.Result) {
        val held = readingFlow.value[r.connectionId]
        if (held == PresenceReading.Unknown) return // `exp` passed: the vault would not have sent it
        val known = PresenceReading.Known(r.state, r.lastActive)
        readingFlow.value = readingFlow.value + (r.connectionId to known)
        // A reading older than a minute is not shown as current: it goes, and an open page asks again (Checking meanwhile).
        val ask = asks[r.connectionId] ?: return
        ask.stale?.cancel()
        ask.stale = scope.launch {
            delay(MINUTE.toMillis())
            synchronized(lock) {
                if (asks[r.connectionId] === ask && readingFlow.value[r.connectionId] == known) {
                    readingFlow.value = readingFlow.value - r.connectionId
                }
            }
        }
    }

    private companion object {
        val MINUTE: Duration = Duration.ofMinutes(1)
        val EXP: Duration = Duration.ofSeconds(30)
        const val CHANGED = "presence.changed"
        val WATCH: Duration = Duration.ofSeconds(5)
        val RETRY: List<Duration> = listOf(5L, 15L, 60L).map { Duration.ofSeconds(it) }
    }
}
