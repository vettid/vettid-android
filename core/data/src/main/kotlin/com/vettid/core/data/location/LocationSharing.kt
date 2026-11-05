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
    /** The connection declared itself a service (`peer_kind`, ANDROID-PLAN 0.1.90): it can't be sent a location (§10.16). */
    private val isService: (connectionId: String) -> Boolean = { false },
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

        /** A `once` share this phone is to send for that has had no position yet. */
        fun waitingForPosition(): Boolean = share.once && source && share.lastSentAt == null && !delivered && !muted

        /** The order in which shares were added: a list read does not forget one added after the read began. */
        var seq: Long = 0
    }

    private val lock = Any()

    /** The receiving side (L1b): the incoming shares and their positions, memory only. */
    private val receiving = LocationReceiving(
        scope = scope,
        ops = ops,
        unlocked = unlocked,
        gated = due,
        isService = isService,
        here = { platform?.currentFix() },
        now = now,
        relist = { refreshQuietly() },
    )
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

    /** Bumped when a share ends; [endedAt] says when each did, so that a list read begun earlier cannot bring one back. */
    private var epoch = 0L
    private val endedAt = HashMap<String, Long>()

    /** Per share, the stop's tries and when the next may be made (backing off as [retryDelays]). */
    private val stopTries = HashMap<String, Int>()
    private val stopNext = HashMap<String, Instant>()

    /** Connections with a start in flight: a second start for one is refused. */
    private val starting = mutableSetOf<String>()

    /** `once` shares that ended without a position (memory only): "No position was found, so nothing was sent." */
    private val missedFlow = MutableStateFlow<Set<String>>(emptySet())

    /** The platform refused the positions (the permission was taken away) since they were last asked for. */
    private var refused = false
    private val availabilityFlow = MutableStateFlow(LocationAvailability.UNKNOWN)
    private var inFront = false
    private var notRunningShown = false

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
    private var reopener: Job? = null
    private var onceTimer: Job? = null
    private var unsupported = false

    /** The best fix while a `once` share waits (held for at most 60 s), and the time of the last position sent. */
    private var held: Fix? = null
    private var lastSent: Instant? = null

    override val outgoing: StateFlow<List<OutgoingShare>> = outgoingFlow.asStateFlow()
    override val serviceState: StateFlow<ServiceState> = stateFlow.asStateFlow()
    override val availability: StateFlow<LocationAvailability> = availabilityFlow.asStateFlow()
    override val missedOnce: StateFlow<Set<String>> = missedFlow.asStateFlow()
    override val incoming: StateFlow<List<IncomingShare>> get() = receiving.incoming

    override suspend fun open(connectionId: String) = receiving.open(connectionId)

    override suspend fun stopReceiving(shareId: String) = receiving.stopReceiving(shareId)

    override suspend fun ask(connectionId: String, note: String?): AskResult = receiving.ask(connectionId, note)

    override suspend fun offsetFromHere(connectionId: String): PositionOffset? = receiving.offsetFromHere(connectionId)

    /** The daily check came due and is held (§3.6.3), or the member's check hold began: what was received is dropped. */
    fun clearReceived() = receiving.clear()

    /**
     * The check passed or the hold ended: the incoming shares are read again (events were not taken meanwhile). Tried again with the
     * backoff of [resume] until it works or the vault closes.
     */
    fun reopenReceived() {
        synchronized(lock) {
            reopener?.cancel()
            reopener = scope.launch { reopenLoop() }
        }
    }

    private suspend fun reopenLoop() {
        var n = 0
        while (unlocked()) {
            if (due()) {
                delay(watchEvery.toMillis())
                continue
            }
            try {
                read(resume = false)
                return
            } catch (e: VaultFailure) {
                if (e.kind == FailureKind.NOT_SUPPORTED) return
                val wait = if (e.kind == FailureKind.OWNER_CHECK_REQUIRED) watchEvery else retryDelays[minOf(n++, retryDelays.size - 1)]
                delay(wait.toMillis())
            }
        }
    }

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
        // A service can't receive a location (the vault refuses `location.share.start` toward one): nothing is sent.
        if (isService(request.connectionId)) throw VaultFailure(FailureKind.SERVICE_FORBIDDEN)
        requireOpen()
        // One start per connection at a time: a second tap or call cannot start twice.
        val first = synchronized(lock) { starting.add(request.connectionId) }
        if (!first) throw VaultFailure(FailureKind.OTHER, "busy")
        try {
            return startOnce(request)
        } finally {
            synchronized(lock) { starting.remove(request.connectionId) }
        }
    }

    /** A share that cannot send is not started: the app in front, a platform attached, its permission and location switch on. */
    private fun ensureCanSend() {
        val blocked = synchronized(lock) {
            when {
                platform == null || !inFront -> SendIssue.SERVICE
                else -> platform?.issue() ?: SendIssue.NONE
            }
        }
        if (blocked != SendIssue.NONE) throw VaultFailure(FailureKind.OTHER, blocked.code)
    }

    private suspend fun startOnce(request: ShareRequest): OutgoingShare {
        ensureCanSend()
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
                missedFlow.value = missedFlow.value - share.connectionId
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
                if (id !in stopMarks) {
                    stopTries.remove(id)
                    stopNext.remove(id)
                }
                // A stop asked again keeps the mark already made (and on disk): it is not rebuilt.
                stopMarks.getOrPut(id) { StoredShare(id, t?.share?.connectionId.orEmpty(), expires.toEpochMilli(), stopping = true) }
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
            endedAt[shareId] = ++epoch
            stopTries.remove(shareId)
            stopNext.remove(shareId)
            if (endedAt.size > MAX_TOMBS) endedAt.entries.removeAll { it.value < epoch - MAX_TOMBS }
            // Everything the stop held back goes on (a pending stop pauses all positions), or the state is published again.
            recompute()
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
        // A once share that ends without any position: said on the connection's card.
        synchronized(lock) {
            val t = tracked[shareId]
            if (t != null && t.waitingForPosition()) {
                missedFlow.value = missedFlow.value + t.share.connectionId
            }
        }
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
        receiving.clear()
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
            reopener?.cancel()
            reopener = null
            onceTimer?.cancel()
            onceTimer = null
            held = null
            lastSent = null
            resumed = false
            paused = false
            unsupported = false
            refused = false
            starting.clear()
            stopTries.clear()
            stopNext.clear()
            endedAt.clear()
            missedFlow.value = emptySet()
            availabilityFlow.value = LocationAvailability.UNKNOWN
            stopService()
            publish()
            if (notRunningShown) {
                notRunningShown = false
                platform?.showNotRunning(false)
            }
        }
    }

    /**
     * A wipe, a sign-out or a deleted vault: everything is forgotten, the stop marks and the stored settings too (they name
     * shares of a vault this phone no longer belongs to).
     */
    fun forget() {
        clear()
        synchronized(lock) { stopMarks.clear() }
        scope.launch(io) { writes.withLock { runCatching { store.save(emptyList()) } } }
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
        loadMarks()
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

    /**
     * The stop marks on disk are loaded and their stops sent at once, without waiting for the owner check: stopping is allowed
     * while it is due or held (VAULT-MESSAGING 0.37.0 §3.6.3).
     */
    private suspend fun loadMarks() {
        val marks = withContext(io) { store.load() }.filter { it.stopping }
        val ids = synchronized(lock) {
            marks.forEach { stopMarks.putIfAbsent(it.shareId, it) }
            val all = stopMarks.keys.toList()
            pendingStops += all
            if (all.isNotEmpty()) {
                recompute()
                ensureWatcher()
            }
            all
        }
        ids.forEach { stopQuietly(it) }
    }

    /** The check passed or came due: sending pauses or goes on, and a stop the vault refused is tried again. Also on a timer. */
    fun onGateChanged() = tick()

    /** The app came to the front: a service start the system refused in the background is tried again. */
    override fun onForeground() {
        receiving.onForeground()
        synchronized(lock) {
            lookAgain()
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
            lookAgain()
            if (tracked.isEmpty() && pendingStops.isEmpty()) {
                watcher?.cancel()
                watcher = null
            }
            dueStops(now())
        }
        if (retry.isNotEmpty()) scope.launch { retry.forEach { stopQuietly(it) } }
    }

    /** Under [lock]: the state is looked at again (the check, the permission, the platform, the positions' job). */
    private fun lookAgain() {
        val active = sources()
        // The permission was given again: the positions are asked for again.
        if (refused && platform?.issue() == SendIssue.NONE) refused = false
        val changed = (active.isNotEmpty() && due()) != paused || issueOf(active) != stateFlow.value.issue
        if (changed || positionsDown(active)) recompute()
    }

    /** Under [lock]: the pending stops to try now; each is tried again with a growing wait (the backoff of [retryDelays]). */
    private fun dueStops(t: Instant): List<String> {
        val ready = pendingStops.filter { id -> stopNext[id]?.let { !it.isAfter(t) } != false }
        for (id in ready) {
            val n = stopTries.getOrDefault(id, 0)
            stopTries[id] = n + 1
            stopNext[id] = t.plus(retryDelays[minOf(n, retryDelays.size - 1)])
        }
        return ready
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
        val (gen, startSeq, startEpoch) = synchronized(lock) { Triple(generation, seq, epoch) }
        val receiveToken = receiving.begin()
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
        // The incoming side is judged by its own epoch, under its own monitor: an answer across a lock or a hold writes nothing.
        receiving.applyList(body, receiveToken)
        val stored = withContext(io) { store.load() }
        val listed = readable(body, stored)
        val toStop = synchronized(lock) {
            if (gen != generation) return
            availabilityFlow.value = LocationAvailability.AVAILABLE
            val stops = apply(listed, stored, resume, startSeq, startEpoch)
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
        return ShareWire.outgoing(body, deviceId(), mine, now()) ?: throw VaultFailure(FailureKind.OTHER, "unreadable_result")
    }

    /**
     * Under [lock]: the vault's list is the truth for what is active; this phone's own state for a share is kept, and a share
     * added after the read began is not forgotten. Returns the shares to send a stop for.
     */
    private fun apply(
        listedAll: List<OutgoingShare>,
        stored: List<StoredShare>,
        resume: Boolean,
        startSeq: Long,
        startEpoch: Long,
    ): List<String> {
        // A share that ended (a stop landed, it expired) after this read began is not brought back by its answer.
        val listed = listedAll.filter { (endedAt[it.shareId] ?: 0L) <= startEpoch }
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
        receiving.onEvent(m)
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
            val collecting = !paused && !refused && stopMarks.isEmpty() && (serviceStarted || inFront)
            applyRequest(if (collecting) requestFor(active) else null)
            if (paused) held = null
            ensureWatcher()
        }
        publish()
    }

    /** Under [lock]: the platform is asked for [want] (null: nothing), again when the request changed or its job is not there. */
    private fun applyRequest(want: PositionRequest?) {
        val provider = platform?.positions
        val missing = want != null && provider != null && positions?.isActive != true
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
        } catch (_: SecurityException) {
            // The permission was taken away: nothing is sent, and the card says so. Nothing about it is logged.
            synchronized(lock) {
                refused = true
                recompute()
            }
        } catch (_: Exception) {
            // The platform failed otherwise: nothing is sent. Nothing about it is logged.
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
        // While a stop is pending nothing is sent at all: the vault forwards each position to every share this phone is the source of.
        val sending = active.isNotEmpty() && !paused && stopMarks.isEmpty()
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
            val held = paused || stopMarks.isNotEmpty()
            if (active.isEmpty() || held || ageOf(f) > LocationLimits.MAX_FIX_AGE) {
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

    /** Sends the position unless a stop was asked for meanwhile (checked once more, right before it goes); false when not sent. */
    private suspend fun deliver(sample: com.vettid.core.vault.LocationSample, ttl: Duration): Boolean {
        val stillOpen = synchronized(lock) { stopMarks.isEmpty() && !paused && sources().isNotEmpty() }
        return stillOpen && try {
            ops().update(sample, ttl)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false // one position missed; the next fix goes
        }
    }

    private suspend fun send(plan: Plan) {
        val sample = try {
            plan.fix.sample(plan.withExtras)
        } catch (_: com.vettid.core.vault.LocationMessageException) {
            return // a fix out of range is not sent
        }
        if (!deliver(sample, plan.ttl)) return
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

    /** Under [lock]: the positions are wanted and their job is not running (it ended, e.g. refused and given again). */
    private fun positionsDown(active: List<Tracked>): Boolean =
        active.isNotEmpty() && requested != null && positions?.isActive != true && !refused

    /** Under [lock]: why nothing is being sent although a share is active, or [SendIssue.NONE]. */
    private fun issueOf(active: List<Tracked>): SendIssue {
        val platformIssue = platform?.issue() ?: SendIssue.NONE
        return when {
            active.isEmpty() -> SendIssue.NONE
            due() -> SendIssue.NONE // the paused state says it
            stopMarks.isNotEmpty() -> SendIssue.STOP_PENDING
            refused || platformIssue == SendIssue.NO_PERMISSION -> SendIssue.NO_PERMISSION
            platformIssue != SendIssue.NONE -> platformIssue
            serviceWanted && !serviceStarted && !inFront -> SendIssue.SERVICE
            else -> SendIssue.NONE
        }
    }

    /** Under [lock]. */
    private fun publish() {
        outgoingFlow.value = tracked.values.map { it.share }
        val active = sources()
        stateFlow.value = ServiceState(
            targets = active.map { ServiceState.Target(it.share.connectionId, it.share.expiresAt) },
            paused = paused,
            resumed = resumed && active.isNotEmpty(),
            issue = issueOf(active),
            stopping = stopMarks.values.mapNotNull { m -> m.connectionId.takeIf { it.isNotEmpty() } }.toSet(),
            stoppingShares = stopMarks.keys.toSet(),
        )
        val notRunning = stateFlow.value.issue == SendIssue.SERVICE
        if (notRunning != notRunningShown) {
            notRunningShown = notRunning
            platform?.showNotRunning(notRunning)
        }
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
        const val MAX_TOMBS = 1024
    }
}
