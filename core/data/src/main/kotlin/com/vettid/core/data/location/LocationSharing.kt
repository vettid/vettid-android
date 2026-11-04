package com.vettid.core.data.location

import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.vaultGuard
import com.vettid.core.vault.LocationLimits
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.os.SystemClock
import java.time.Duration
import java.time.Instant

/**
 * [LocationRepository] over [LocationOps] (ANDROID-PLAN §4 "Location", L1a; VAULT-MESSAGING §10.16): the sending side.
 *
 * - **Who sends.** This phone, for the active shares it is the source of (it started them, or `location.share.list` names its
 *   `device_id`). Nothing is sent when it is the source of none, while the vault is locked, or while the daily owner check is
 *   due or held ("paused"; the vault would drop the positions; a stop is still allowed, §3.6.3).
 * - **Cadence.** The platform is asked for the shortest interval among the continuous shares. Each fix is one ephemeral
 *   `location.update`, with altitude, speed and heading only when some share is exact; a fix older than 5 minutes is dropped.
 *   A `once` share waits up to 60 s for 100 m or better, then sends the best it has; the first position delivers it.
 * - **Ending.** At the share's `expires_at` by this class's own timer, on a stop, on `location.event{stopped, out}` and on
 *   `sync.event{location.share.changed, ended}`. A lock ([clear]) ends all sending and the service; the stored share ids stay,
 *   and the next unlock ([resume]) takes up again what `location.share.list` still names with this phone's device id.
 * - **Memory.** A [Fix] is held only until it is sent (a `once` share's best fix at most 60 s); nothing but [StoredShare] is
 *   written, and nothing is logged.
 */
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class LocationSharing(
    private val scope: CoroutineScope,
    private val ops: suspend () -> LocationOps,
    private val store: ShareStore,
    /** The vault is open on this phone. */
    private val unlocked: () -> Boolean,
    /** The daily owner check is due or held (§3.6.3). */
    private val due: () -> Boolean,
    private val deviceId: () -> String?,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val watchEvery: Duration = WATCH,
    private val retryDelays: List<Duration> = RETRY,
    /** The uptime clock a fix's age is judged by (a fix carries the same clock's reading); the wall clock may jump. */
    private val elapsedNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : LocationRepository {
    private class Tracked(var share: OutgoingShare, var timer: Job?) {
        /** This phone feeds the share with positions. */
        var source: Boolean = share.fromThisPhone

        /** Sending for it was stopped; the vault may not know yet. */
        var muted: Boolean = false

        /** A `once` share whose position was sent: it needs nothing more from this phone. */
        var delivered: Boolean = false

        /** When this phone began waiting for a `once` share's position. */
        var waitingSince: Instant? = null

        /** The order in which shares were added: a list read does not forget one added after the read began. */
        var seq: Long = 0
    }

    private val lock = Any()
    private val tracked = LinkedHashMap<String, Tracked>()
    private val outgoingFlow = MutableStateFlow<List<OutgoingShare>>(emptyList())
    private val stateFlow = MutableStateFlow(ServiceState())
    private val writes = Mutex()
    private val pendingStops = mutableSetOf<String>()

    /**
     * The shares the member stopped (ids only, with `stopping` set in the stored settings), kept across [clear]: a stop the vault
     * has not taken must never be undone by a lock or a restart. A share in here is never sent for; the stop is sent for it.
     */
    private val stopMarks = LinkedHashMap<String, StoredShare>()
    private var seq = 0L
    private val availabilityFlow = MutableStateFlow(LocationAvailability.UNKNOWN)
    private var inFront = false

    @Volatile
    private var platform: LocationPlatform? = null
    private var serviceWanted = false
    private var serviceStarted = false
    private var resumed = false
    private var paused = false

    /** Bumped by [clear]: an answer to something sent before it is not applied after it. */
    private var generation = 0
    private var requested: PositionRequest? = null
    private var positions: Job? = null
    private var watcher: Job? = null
    private var resumer: Job? = null
    private var onceTimer: Job? = null
    private var unsupported = false

    /** The best fix while a `once` share waits (held for at most 60 s), and the time of the last position sent. */
    private var held: Fix? = null
    private var lastSent: Instant? = null

    override val outgoing: StateFlow<List<OutgoingShare>> = outgoingFlow.asStateFlow()
    override val serviceState: StateFlow<ServiceState> = stateFlow.asStateFlow()
    override val availability: StateFlow<LocationAvailability> = availabilityFlow.asStateFlow()

    /** Attaches the platform's positions and service. Before the first [start] or [resume]. */
    fun attach(p: LocationPlatform) {
        synchronized(lock) {
            platform = p
            // Shares taken up before the platform was there start their service and positions now.
            serviceWanted = false
            serviceStarted = false
            positions?.cancel()
            positions = null
            requested = null
            recompute()
        }
    }

    // --- starting ---

    override suspend fun start(request: ShareRequest): OutgoingShare {
        if (!request.withinBounds()) throw VaultFailure(FailureKind.OTHER, "bad_request")
        requireOpen()
        val gen = synchronized(lock) { generation }
        val body = try {
            vaultGuard { ops().start(request) }
        } catch (e: VaultFailure) {
            if (e.kind == FailureKind.NOT_SUPPORTED) availabilityFlow.value = LocationAvailability.UNSUPPORTED
            throw e
        }
        availabilityFlow.value = LocationAvailability.AVAILABLE
        val started = ShareWire.started(body) ?: throw VaultFailure(FailureKind.OTHER, "unreadable_result")
        val share = OutgoingShare(
            shareId = started.shareId,
            connectionId = request.connectionId,
            once = request.once,
            precision = request.precision,
            intervalSeconds = request.intervalSeconds,
            trail = request.trail,
            expiresAt = started.expiresAt,
            lastSentAt = null,
            fromThisPhone = true,
        )
        synchronized(lock) {
            // The vault keeps one outgoing share per connection: a new start ended the old one.
            if (gen == generation) {
                tracked.values.filter { it.share.connectionId == share.connectionId && it.share.shareId != share.shareId }
                    .forEach { forget(it.share.shareId) }
                add(share, source = true, since = now())
                resumed = false
                recompute()
            }
        }
        persist()
        return share
    }

    private fun requireOpen() {
        if (!unlocked() || due()) throw VaultFailure(FailureKind.OWNER_CHECK_REQUIRED, "owner_check_required")
    }

    // --- stopping ---

    override suspend fun stop(shareId: String) {
        markStopping(listOf(shareId))
        // Written before anything is sent: a lock or the process ending cannot bring the share back.
        save()
        try {
            // Stopping is allowed while the check is due or held (VAULT-MESSAGING 0.37.0 §3.6.3), as locking is: only a locked
            // vault cannot take it.
            if (!unlocked()) throw VaultFailure(FailureKind.OWNER_CHECK_REQUIRED, "owner_check_required")
            vaultGuard { ops().stop(shareId) }
            end(shareId)
        } catch (e: VaultFailure) {
            if (e.kind == FailureKind.NOT_FOUND) {
                end(shareId)
                return
            }
            synchronized(lock) {
                pendingStops += shareId
                ensureWatcher()
            }
            throw e
        }
    }

    override fun stopAll() {
        val ids = synchronized(lock) { tracked.filterValues { it.source && !it.muted && !it.delivered }.keys.toList() }
        markStopping(ids)
        scope.launch {
            save()
            ids.forEach { id -> stopQuietly(id) }
        }
    }

    /** Sending stops for [ids] at once, and the stop is remembered (memory now, disk at [save]). */
    private fun markStopping(ids: List<String>) {
        synchronized(lock) {
            for (id in ids) {
                val t = tracked[id]
                t?.muted = true
                val expires = t?.share?.expiresAt ?: now()
                stopMarks[id] = StoredShare(id, t?.share?.connectionId.orEmpty(), expires.toEpochMilli(), stopping = true)
            }
            recompute()
        }
    }

    private suspend fun stopQuietly(id: String) {
        try {
            stop(id)
        } catch (_: VaultFailure) {
            // Kept in the pending stops and on disk, tried again when the vault can take it.
        }
    }

    /** The share is over (expired, stopped, ended by the other side or the vault): forgotten here, nothing sent. */
    private fun end(shareId: String) {
        synchronized(lock) {
            val had = forget(shareId) != null
            val marked = stopMarks.remove(shareId) != null
            if (!had && !marked) return
            if (had) recompute()
        }
        persist()
    }

    /** Under [lock]: takes the share out; its timer stops. */
    private fun forget(shareId: String): Tracked? {
        val t = tracked.remove(shareId) ?: return null
        t.timer?.cancel()
        pendingStops -= shareId
        return t
    }

    /** Under [lock]. */
    private fun add(share: OutgoingShare, source: Boolean, since: Instant?) {
        tracked.remove(share.shareId)?.timer?.cancel()
        val t = Tracked(share, null)
        t.seq = ++seq
        t.source = source && share.shareId !in stopMarks
        t.waitingSince = since.takeIf { source && share.once && share.lastSentAt == null }
        t.delivered = share.once && share.lastSentAt != null
        t.timer = scope.launch { expire(share.shareId) }
        tracked[share.shareId] = t
    }

    /** The app's own timer: ends the share at its `expires_at`, whatever the vault does (it tells nobody at that moment). */
    private suspend fun expire(shareId: String) {
        val at = synchronized(lock) { tracked[shareId]?.share?.expiresAt } ?: return
        val left = Duration.between(now(), at)
        if (!left.isNegative && !left.isZero) delay(left.toMillis())
        end(shareId)
    }

    /**
     * Ends the shares whose `expires_at` has passed by the wall clock. The timer ([expire]) runs on the uptime clock, which stops
     * in deep sleep, so a position about to be sent looks at the date as well.
     */
    private fun sweep() {
        val over = synchronized(lock) { tracked.values.filter { !it.share.expiresAt.isAfter(now()) }.map { it.share.shareId } }
        over.forEach { end(it) }
    }

    // --- locks, the check, unlocks ---

    /** The vault locked, was wiped or signed out: sending ends, the service stops, memory is cleared. The stored ids stay. */
    fun clear() {
        synchronized(lock) {
            generation++
            tracked.values.forEach { it.timer?.cancel() }
            tracked.clear()
            pendingStops.clear() // the stop marks stay: they are on disk too
            positions?.cancel()
            positions = null
            requested = null
            watcher?.cancel()
            watcher = null
            resumer?.cancel()
            resumer = null
            onceTimer?.cancel()
            onceTimer = null
            held = null
            lastSent = null
            resumed = false
            paused = false
            unsupported = false
            availabilityFlow.value = LocationAvailability.UNKNOWN
            stopService()
            publish()
        }
    }

    /**
     * After an unlock (and at the start of the app with the vault open): `location.share.list`, and the shares that name this
     * phone as their source are taken up again, with the notice that they are. Waits while the check is due; retries after a
     * failure that says nothing about the feature.
     */
    fun resume() {
        synchronized(lock) {
            resumer?.cancel()
            resumer = scope.launch { resumeLoop() }
        }
    }

    private suspend fun resumeLoop() {
        var n = 0
        while (unlocked()) {
            if (due()) {
                delay(watchEvery.toMillis())
                continue
            }
            try {
                read(resume = true)
                return
            } catch (e: VaultFailure) {
                if (e.kind == FailureKind.NOT_SUPPORTED) return
                // Never a tight loop: at least the watch interval, or the backoff for a failure that says nothing about the feature.
                val wait = if (e.kind == FailureKind.OWNER_CHECK_REQUIRED) watchEvery else retryDelays[minOf(n++, retryDelays.size - 1)]
                delay(wait.toMillis())
            }
        }
    }

    /** The check passed or came due: sending pauses or goes on, and a stop the vault refused is tried again. Also on a timer. */
    fun onGateChanged() = tick()

    /** The app came to the front: a service start the system refused in the background is tried again. */
    override fun onForeground() {
        synchronized(lock) {
            if (serviceWanted && !serviceStarted) {
                serviceStarted = platform?.startService() == true
                recompute()
            }
        }
    }

    /** The app is in front or not: positions are collected without the service only while it is. */
    override fun setInFront(front: Boolean) {
        synchronized(lock) {
            if (inFront == front) return
            inFront = front
            if (front) onForeground()
            recompute()
        }
    }

    /** The service ended itself (the system refused it, or it failed): it is started again when the app is in front. */
    override fun onServiceEnded() {
        synchronized(lock) {
            if (!serviceWanted) return
            serviceStarted = false
            recompute()
        }
    }

    private fun tick() {
        val retry = synchronized(lock) {
            if (!unlocked()) return
            if ((sources().isNotEmpty() && due()) != paused) recompute()
            if (tracked.isEmpty() && pendingStops.isEmpty()) {
                watcher?.cancel()
                watcher = null
            }
            pendingStops.toList()
        }
        if (retry.isNotEmpty()) scope.launch { retry.forEach { stopQuietly(it) } }
    }

    /** Under [lock]. */
    private fun ensureWatcher() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            while (isActive) {
                delay(watchEvery.toMillis())
                tick()
            }
        }
    }

    // --- the vault's list and events ---

    override suspend fun refresh() {
        read(resume = false)
    }

    private suspend fun read(resume: Boolean) {
        if (unsupported) throw VaultFailure(FailureKind.NOT_SUPPORTED)
        requireOpen()
        val (gen, startSeq) = synchronized(lock) { generation to seq }
        val body = try {
            vaultGuard(resend = true) { ops().list() }
        } catch (e: VaultFailure) {
            if (e.kind == FailureKind.NOT_SUPPORTED) {
                synchronized(lock) {
                    if (gen == generation) {
                        unsupported = true
                        availabilityFlow.value = LocationAvailability.UNSUPPORTED
                    }
                }
            }
            throw e
        }
        val stored = withContext(io) { store.load() }
        val listed = readable(body, stored)
        val toStop = synchronized(lock) {
            if (gen != generation) return
            availabilityFlow.value = LocationAvailability.AVAILABLE
            val stops = apply(listed, stored, resume, startSeq)
            recompute()
            stops
        }
        persist()
        // A share the member stopped is never taken up: the stop is sent for it, and the mark goes when the vault took it.
        if (toStop.isNotEmpty()) scope.launch { toStop.forEach { stopQuietly(it) } }
    }

    /** The vault's outgoing shares; the stored ids name this phone as a source only when its device id is not known. */
    private fun readable(body: kotlinx.serialization.json.JsonObject, stored: List<StoredShare>): List<OutgoingShare> {
        val mine = stored.filter { !it.stopping }.map { it.shareId }.toSet()
        return ShareWire.outgoing(body, deviceId(), mine) ?: throw VaultFailure(FailureKind.OTHER, "unreadable_result")
    }

    /**
     * Under [lock]: the vault's list is the truth for what is active; this phone's own state for a share is kept, and a share
     * added after the read began is not forgotten. Returns the shares to send a stop for.
     */
    private fun apply(listed: List<OutgoingShare>, stored: List<StoredShare>, resume: Boolean, startSeq: Long): List<String> {
        stored.filter { it.stopping }.forEach { stopMarks.putIfAbsent(it.shareId, it) }
        val ids = listed.map { it.shareId }.toSet()
        tracked.entries.filter { it.key !in ids && it.value.seq <= startSeq }.map { it.key }.forEach { forget(it) }
        // A mark for a share the vault no longer lists is over.
        stopMarks.keys.filter { it !in ids }.forEach { stopMarks.remove(it) }
        if (takeUp(listed, resume)) resumed = true
        val stops = stopMarks.keys.toList()
        pendingStops += stops
        if (stops.isNotEmpty()) ensureWatcher()
        return stops
    }

    /** Under [lock]: adds the listed shares not tracked yet (a stopped one is never a source); true when one was taken up at a resume. */
    private fun takeUp(listed: List<OutgoingShare>, resume: Boolean): Boolean {
        var taken = false
        for (s in listed) {
            val held = tracked[s.shareId]
            if (held != null) {
                held.share = s.copy(lastSentAt = held.share.lastSentAt ?: s.lastSentAt)
                continue
            }
            val stopping = s.shareId in stopMarks
            val needs = s.fromThisPhone && !stopping && !(s.once && s.lastSentAt != null)
            add(s, source = needs, since = now())
            if (stopping) tracked[s.shareId]?.muted = true
            if (needs && resume) taken = true
        }
        return taken
    }

    /** `location.event` and `sync.event{location.share.changed}` from the vault. */
    fun onEvent(m: VaultMessage) {
        when (m.type) {
            "location.event" -> onLocationEvent(m)
            "sync.event" -> if (VaultJson.str(m.body, "kind") == CHANGED) onChanged(m)
        }
    }

    private fun onLocationEvent(m: VaultMessage) {
        val id = VaultJson.str(m.body, "share_id")?.takeIf { it.isNotEmpty() } ?: return
        if (VaultJson.str(m.body, "direction") != "out") return
        when (VaultJson.str(m.body, "event")) {
            // The receiver stopped it, or a new share for the connection replaced it.
            "stopped" -> end(id)
            "started" -> if (synchronized(lock) { id !in tracked }) refreshQuietly()
        }
    }

    private fun onChanged(m: VaultMessage) {
        val id = VaultJson.str(m.body, "share_id")?.takeIf { it.isNotEmpty() } ?: return
        when (VaultJson.str(m.body, "state")) {
            "ended" -> end(id)
            "active" -> if (synchronized(lock) { id !in tracked }) refreshQuietly()
        }
    }

    private fun refreshQuietly() {
        scope.launch {
            try {
                refresh()
            } catch (_: VaultFailure) {
                // Read again at the next unlock or event.
            }
        }
    }

    // --- sending ---

    /** Under [lock]: the shares this phone is to send for now. */
    private fun sources(): List<Tracked> =
        tracked.values.filter { it.source && !it.muted && !it.delivered && it.share.expiresAt.isAfter(now()) }

    /**
     * Under [lock]: brings the platform's request, the service and the published state in line with the shares. The platform is
     * asked for the shortest interval among the continuous shares (a `once` share that waits asks for a fix a second).
     */
    private fun recompute() {
        val active = sources()
        paused = active.isNotEmpty() && due()
        if (active.isEmpty()) {
            positions?.cancel()
            positions = null
            requested = null
            onceTimer?.cancel()
            onceTimer = null
            held = null
            stopService()
        } else {
            if (!serviceWanted) {
                serviceWanted = true
                serviceStarted = platform?.startService() == true
            }
            // Positions are collected while the service runs, or while the app is in front (a refused service start).
            val collecting = !paused && (serviceStarted || inFront)
            applyRequest(if (collecting) requestFor(active) else null)
            if (paused) held = null
            ensureWatcher()
        }
        publish()
    }

    /** Under [lock]: the platform is asked for [want] (null: nothing), again when the request changed or its job is not there. */
    private fun applyRequest(want: PositionRequest?) {
        val provider = platform?.positions
        val missing = want != null && provider != null && positions == null
        if (want == requested && !missing) return
        positions?.cancel()
        requested = want
        positions = if (want != null && provider != null) scope.launch { collect(provider, want) } else null
    }

    private fun requestFor(active: List<Tracked>): PositionRequest {
        val waiting = active.any { it.share.once }
        val continuous = active.filter { !it.share.once }.mapNotNull { it.share.intervalSeconds }
        val seconds = if (waiting || continuous.isEmpty()) ONCE_POLL_SECONDS else continuous.min()
        return PositionRequest(seconds * MILLIS, highAccuracy = waiting || active.any { it.share.precision == SharePrecision.EXACT })
    }

    private suspend fun collect(provider: PositionProvider, request: PositionRequest) {
        try {
            provider.fixes(request).collect { onFix(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The platform refused (the permission was taken away): nothing is sent. Nothing about it is logged.
        }
    }

    /** One fix from the platform: sent, held for a `once` share, or dropped. */
    internal suspend fun onFix(fix: Fix) {
        sweep()
        val plan = synchronized(lock) { decide(fix) } ?: return
        send(plan)
    }

    private class Plan(val fix: Fix, val withExtras: Boolean, val ttl: Duration)

    /** Under [lock]. */
    private fun decide(fix: Fix): Plan? {
        val active = sources()
        val t = now()
        val sending = active.isNotEmpty() && !paused
        val open = sending && unlocked() && !due()
        if (!open || ageOf(fix) > LocationLimits.MAX_FIX_AGE) return null
        val waiting = active.filter { it.share.once }
        val send = if (waiting.isEmpty()) {
            fix.takeIf { dueForCadence(active, t) }
        } else {
            holdForOnce(fix, waiting, t)
        }
        return send?.let { Plan(it, active.any { s -> s.share.precision == SharePrecision.EXACT }, ttlFor(active)) }
    }

    /** A fix's age by the uptime clock when the fix carries its reading (the wall clock may have been set), else by the date. */
    private fun ageOf(fix: Fix): Duration {
        val at = fix.elapsedNanos
        return if (at != null) Duration.ofNanos(elapsedNanos() - at) else Duration.between(fix.at, now())
    }

    /** Under [lock]: one position per 0.9 x the shortest interval (the vault drops those in between). */
    private fun dueForCadence(active: List<Tracked>, t: Instant): Boolean {
        val last = lastSent
        val interval = active.mapNotNull { it.share.intervalSeconds }.minOrNull()
        return last == null || interval == null || Duration.between(last, t).toMillis() >= interval * MILLIS * CADENCE_NUM / CADENCE_DEN
    }

    /**
     * Under [lock]: a `once` share waits up to 60 s for 100 m or better, then sends the best it has (or the first that
     * arrives). Its position also feeds the other shares, so nothing worse is sent meanwhile.
     */
    private fun holdForOnce(fix: Fix, waiting: List<Tracked>, t: Instant): Fix? {
        val best = better(held, fix)
        val since = waiting.mapNotNull { it.waitingSince }.minOrNull() ?: t
        val deadline = since.plus(ONCE_WAIT)
        // With approximate access only 100 m cannot be reached: the first fix is the answer.
        val good = platform?.precise() == false || (best.accuracyM ?: Double.MAX_VALUE) <= ONCE_GOOD_M
        if (good || !t.isBefore(deadline)) {
            held = null
            return best
        }
        held = best
        onceTimer?.cancel()
        onceTimer = scope.launch {
            delay(Duration.between(t, deadline).toMillis().coerceAtLeast(0))
            flushHeld()
        }
        return null
    }

    private fun better(a: Fix?, b: Fix): Fix {
        if (a == null) return b
        val x = a.accuracyM ?: Double.MAX_VALUE
        val y = b.accuracyM ?: Double.MAX_VALUE
        return if (y < x || (y == x && b.at.isAfter(a.at))) b else a
    }

    /** The 60 s passed: the best fix so far is sent. */
    private suspend fun flushHeld() {
        val plan = synchronized(lock) {
            val f = held ?: return
            held = null
            val active = sources()
            if (active.isEmpty() || paused || ageOf(f) > LocationLimits.MAX_FIX_AGE) {
                null
            } else {
                Plan(f, active.any { it.share.precision == SharePrecision.EXACT }, ttlFor(active))
            }
        } ?: return
        send(plan)
    }

    /** max(2 x interval, 60 s), with the shortest interval of the continuous shares. */
    private fun ttlFor(active: List<Tracked>): Duration {
        val interval = active.mapNotNull { it.share.intervalSeconds }.minOrNull() ?: 0L
        return Duration.ofSeconds(maxOf(2 * interval, LocationLimits.MIN_UPDATE_TTL_SECONDS))
    }

    private suspend fun send(plan: Plan) {
        val sample = try {
            plan.fix.sample(plan.withExtras)
        } catch (_: com.vettid.core.vault.LocationMessageException) {
            return // a fix out of range is not sent
        }
        try {
            ops().update(sample, plan.ttl)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return // one position missed; the next fix goes
        }
        val delivered = synchronized(lock) {
            val t = now()
            lastSent = t
            var done = false
            for (s in sources()) {
                s.share = s.share.copy(lastSentAt = t)
                // The single position of a `once` share is delivered: this phone has nothing more to send for it.
                if (s.share.once) {
                    s.delivered = true
                    done = true
                }
            }
            recompute()
            done
        }
        if (delivered) persist()
    }

    // --- state out ---

    /** Under [lock]. */
    private fun publish() {
        outgoingFlow.value = tracked.values.map { it.share }
        val active = sources()
        stateFlow.value = ServiceState(
            targets = active.map { ServiceState.Target(it.share.connectionId, it.share.expiresAt) },
            paused = paused,
            resumed = resumed && active.isNotEmpty(),
        )
    }

    /** Under [lock]. */
    private fun stopService() {
        if (serviceWanted) {
            // Only a service that runs is told to go (it may have ended itself, or never started).
            val running = serviceStarted
            serviceWanted = false
            serviceStarted = false
            if (running) platform?.stopService()
        }
    }

    /** Under [lock]: what is kept on disk: the shares sent for, and the stops not yet taken. Ids and expiries only. */
    private fun snapshot(): List<StoredShare> = tracked.values
        .filter { it.source && !it.delivered && it.share.shareId !in stopMarks }
        .map { StoredShare(it.share.shareId, it.share.connectionId, it.share.expiresAt.toEpochMilli()) } + stopMarks.values

    /** Writes the latest state, now. A write that fails leaves the old file (ids of shares that are over go at the next list). */
    private suspend fun save() {
        withContext(io) { writes.withLock { runCatching { store.save(synchronized(lock) { snapshot() }) } } }
    }

    /** The same, in the background. */
    private fun persist() {
        scope.launch(io) { writes.withLock { runCatching { store.save(synchronized(lock) { snapshot() }) } } }
    }

    private companion object {
        val WATCH: Duration = Duration.ofSeconds(5)
        val RETRY: List<Duration> = listOf(5L, 15L, 60L).map { Duration.ofSeconds(it) }
        val ONCE_WAIT: Duration = Duration.ofSeconds(60)
        const val ONCE_GOOD_M = 100.0
        const val ONCE_POLL_SECONDS = 1L
        const val MILLIS = 1_000L
        const val CADENCE_NUM = 9L
        const val CADENCE_DEN = 10L
        const val CHANGED = "location.share.changed"
    }
}
