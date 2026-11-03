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
) : PresenceRepository {
    private class Ask(val pingId: String, val askedAt: Instant, val expiry: Job)

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

    override val availability: StateFlow<PresenceAvailability> = availabilityFlow.asStateFlow()
    override val policy: StateFlow<PresencePolicy?> = policyFlow.asStateFlow()
    override val readings: StateFlow<Map<String, PresenceReading>> = readingFlow.asStateFlow()

    /** Forgets everything: the vault locked, was held or wiped. */
    fun clear() {
        synchronized(lock) {
            generation++
            policyQueued = false
            asks.values.forEach { it.expiry.cancel() }
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
        scope.launch { runCatching { refreshPolicy() } }
    }

    /** `sync.event{presence.changed}` re-reads the policy; `presence.result` answers a question. */
    fun onEvent(m: VaultMessage) {
        when (m.type) {
            "sync.event" -> if (VaultJson.str(m.body, "kind") == CHANGED && availabilityFlow.value == PresenceAvailability.AVAILABLE) {
                refreshPolicyQuietly()
            }
            "presence.result" -> PresenceWire.result(m.body, now())?.let { onResult(it) }
        }
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
        if (closed()) throw VaultFailure(FailureKind.OWNER_CHECK_REQUIRED, "owner_check_required")
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
        val version = PresenceWire.version(body) ?: (current.version + 1)
        synchronized(lock) { if (gen == generation) policyFlow.value = next.copy(version = version) }
    }

    override suspend fun query(connectionId: String) {
        val gen = synchronized(lock) { generationToAsk(connectionId) } ?: return
        val asked = ask(connectionId, gen) ?: return
        val at = now()
        val expiry = scope.launch {
            delay(expiryWait(asked.exp, at).toMillis())
            expire(connectionId, asked.pingId)
        }
        synchronized(lock) {
            if (gen != generation) {
                expiry.cancel()
            } else {
                asks.put(connectionId, Ask(asked.pingId, at, expiry))?.expiry?.cancel()
                // The result may have arrived before this answer did.
                strays.remove(connectionId)?.takeIf { it.pingId == asked.pingId }?.let { applyResult(it) }
            }
        }
    }

    /** Sends `presence.query` ("Checking…" meanwhile); null, with the reading unknown, when it cannot be sent or is refused. */
    private suspend fun ask(connectionId: String, gen: Int): PresenceWire.Asked? {
        if (closed()) return null.also { setReading(connectionId, gen, PresenceReading.Unknown) }
        synchronized(lock) { sending += connectionId }
        setReading(connectionId, gen, PresenceReading.Checking)
        val asked = try {
            PresenceWire.asked(vaultGuard(resend = true) { ops().query(connectionId) })
        } catch (_: VaultFailure) {
            null
        } finally {
            synchronized(lock) { sending -= connectionId }
        }
        if (asked == null) setReading(connectionId, gen, PresenceReading.Unknown)
        return asked
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
        return if (left > EXP) EXP else left.coerceAtLeast(Duration.ZERO)
    }

    private fun Duration.coerceAtLeast(min: Duration) = if (this < min) min else this

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
        readingFlow.value = readingFlow.value + (r.connectionId to PresenceReading.Known(r.state, r.lastActive))
    }

    private companion object {
        val MINUTE: Duration = Duration.ofMinutes(1)
        val EXP: Duration = Duration.ofSeconds(30)
        const val CHANGED = "presence.changed"
    }
}
