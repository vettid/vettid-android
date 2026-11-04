package com.vettid.core.data.location

import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.vaultGuard
import com.vettid.core.vault.LocationRequestLimits
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant

/**
 * Receiving a connection's location (ANDROID-PLAN §4 "Location", L1b; VAULT-MESSAGING §10.16): the incoming shares, their latest
 * positions, **Stop receiving**, **Ask for location**. **Memory only.** A position is in no Room table, file, DataStore,
 * `SavedStateHandle`, log or crash report; the types print their names only; [clear] (lock, hold, wipe, sign-out) drops all of it.
 *
 * - **Where it comes from.** `location.share.list` (`incoming`, via [applyList]), `location.event{started | stopped | snapshot,
 *   direction: in}`, the feed item `location.shared`, the ephemeral `location.update` for an incoming share, and `location.get`
 *   when a screen opens ([open]). An update is taken only for a share that is known and names the same connection.
 * - **Ending.** `expires_at` (the app's own timer, and a look at the date at every write: the timer runs on uptime), `stopped`,
 *   [stopReceiving], a `location.get` that finds nothing (`not_found`).
 * - **Locks and holds.** [clear] bumps [lockEpoch] under [lock]; every write from a read or an event checks the epoch it began
 *   under, in the same monitor, so an answer that was in flight across a lock (or a hold) writes nothing. Nothing is read, taken
 *   from an event or asked while the vault is locked or the daily owner check is due or held; a stop is allowed then (§3.6.3).
 */
@Suppress("TooManyFunctions", "LongParameterList", "ReturnCount", "ThrowsCount")
internal class LocationReceiving(
    private val scope: CoroutineScope,
    private val ops: suspend () -> LocationOps,
    private val unlocked: () -> Boolean,
    /** The daily owner check is due or held (§3.6.3). */
    private val gated: () -> Boolean,
    private val isService: (connectionId: String) -> Boolean,
    private val here: suspend () -> Fix?,
    private val now: () -> Instant,
    /** Reads `location.share.list` again (the sending side owns the read). */
    private val relist: () -> Unit,
) {
    private val lock = Any()
    private val shares = LinkedHashMap<String, IncomingShare>()
    private val timers = HashMap<String, Job>()
    private val flow = MutableStateFlow<List<IncomingShare>>(emptyList())

    /** Bumped by every lock and hold ([clear]) and wipe: an answer begun under an older value writes nothing. */
    private var lockEpoch = 0

    /** Bumped when a share is added, replaced or ended; [changedAt] says when each id last was. */
    private var seq = 0L
    private val changedAt = HashMap<String, Long>()
    private val addedAt = HashMap<String, Long>()

    /** When this member last asked each connection (memory): the minutes left of the vault's allowance of one per 10. */
    private val asked = HashMap<String, Instant>()

    val incoming: StateFlow<List<IncomingShare>> = flow.asStateFlow()

    /** A read begins: the epoch and sequence it is judged against when it comes back. */
    class Token(internal val epoch: Int, internal val seq: Long)

    fun begin(): Token = synchronized(lock) { Token(lockEpoch, seq) }

    // --- the vault's list ---

    /**
     * `location.share.list`'s `incoming` ([body] is the whole answer). Strict: an entry that does not read makes the list
     * unreadable, and nothing changes. The list is the truth for what is active, except for a share added or ended after the
     * read began, and an answer begun before a lock or hold writes nothing.
     */
    fun applyList(body: JsonObject, token: Token) {
        if (gated()) return
        val listed = ShareWire.incoming(body, now()) ?: return
        synchronized(lock) {
            if (token.epoch != lockEpoch || !unlocked()) return
            val ids = listed.map { it.shareId }.toSet()
            shares.keys.filter { it !in ids && (addedAt[it] ?: 0L) <= token.seq }.forEach { drop(it) }
            for (l in listed) {
                if ((changedAt[l.shareId] ?: 0L) > token.seq) continue // ended or replaced after this read began
                val held = shares[l.shareId]
                // This member's own newer position is kept when the list's `last` is older.
                put(
                    if (held == null) {
                        l
                    } else {
                        l.copy(last = newer(held.last, l.last), trailPoints = held.trailPoints, snapshot = held.snapshot)
                    },
                )
            }
            publish()
        }
    }

    // --- events ---

    /** `location.event`, the ephemeral `location.update` and the feed item `location.shared`. */
    fun onEvent(m: VaultMessage) {
        if (!unlocked() || gated()) return
        when (m.type) {
            "location.event" -> onLocationEvent(m.body)
            "location.update" -> onUpdate(m.body)
            "feed.event" -> if (VaultJson.str(m.body, "kind") == "location.shared") onShared(m.body)
        }
    }

    private fun onLocationEvent(b: JsonObject) {
        if (VaultJson.str(b, "direction") != "in") return
        val id = VaultJson.str(b, "share_id")?.takeIf { it.isNotEmpty() } ?: return
        when (VaultJson.str(b, "event")) {
            "started" -> {
                val share = ShareWire.startedEvent(b, now())
                if (share != null) synchronized(lock) { replaceFor(share); publish() }
                // The interval, the trail flag and a position that was already there come from the list.
                relist()
            }
            "stopped", "ended" -> synchronized(lock) {
                if (drop(id)) publish()
                changedAt[id] = ++seq
            }
            "snapshot" -> onSnapshot(id, b)
        }
    }

    private fun onSnapshot(id: String, b: JsonObject) {
        val points = ShareWire.points(b["points"], SNAPSHOT_MAX) ?: return
        synchronized(lock) {
            val s = shares[id] ?: return
            if (s.connectionId != VaultJson.str(b, "connection_id")) return
            shares[id] = s.copy(snapshot = points)
            publish()
        }
    }

    private fun onShared(feed: JsonObject) {
        val id = VaultJson.str(feed, "ref")?.takeIf { it.isNotEmpty() } ?: return
        if (synchronized(lock) { id !in shares && (changedAt[id] ?: 0L) == 0L }) relist()
    }

    /** A live position: only for a known incoming share of the connection that is named. Dropped silently otherwise. */
    private fun onUpdate(b: JsonObject) {
        val id = VaultJson.str(b, "share_id")?.takeIf { it.isNotEmpty() } ?: return
        val connection = VaultJson.str(b, "connection_id")?.takeIf { it.isNotEmpty() } ?: return
        val p = ShareWire.position(b) ?: return
        val at = now()
        synchronized(lock) {
            sweep()
            val s = shares[id] ?: return
            if (s.connectionId != connection || !s.expiresAt.isAfter(at)) return
            val position = ReceivedPosition(p.lat, p.lon, p.accuracyM, p.altitudeM, p.speedMps, p.headingDeg, p.at, at)
            val trail = if (s.trail) (s.trailPoints + position).takeLast(TRAIL_MAX) else s.trailPoints
            shares[id] = s.copy(last = newer(s.last, position), trailPoints = trail)
            publish()
        }
    }

    // --- screens ---

    /**
     * A screen of [connectionId] opens: `location.get`. Throws the vault's failure (`owner_check_required` while the check is due or
     * held, a locked vault). `not_found` ends the share here.
     */
    suspend fun open(connectionId: String) {
        requireOpen()
        val known = synchronized(lock) { shares.values.firstOrNull { it.connectionId == connectionId } }
        val token = begin()
        val body = try {
            vaultGuard(resend = true) { ops().get(connectionId, history = known?.trail == true) }
        } catch (e: VaultFailure) {
            if (e.kind == FailureKind.NOT_FOUND) {
                synchronized(lock) {
                    if (token.epoch == lockEpoch) {
                        known?.let { if (drop(it.shareId)) publish() }
                    }
                }
            }
            throw e
        }
        val got = ShareWire.get(connectionId, body, now()) ?: throw VaultFailure(FailureKind.OTHER, "unreadable_result")
        synchronized(lock) {
            if (token.epoch != lockEpoch || !unlocked()) return
            if ((changedAt[got.shareId] ?: 0L) > token.seq) return // ended after the read began
            // The answer must be for the share held: another share_id means the connection's share changed meanwhile.
            val mine = shares.values.firstOrNull { it.connectionId == connectionId }
            if (mine != null && mine.shareId != got.shareId) {
                relist()
                return
            }
            put(mergedWith(got))
            publish()
        }
    }

    /** Under [lock]: [got] (`location.get`) joined to the share held (a new share for the connection replaces the old, §10.16). */
    private fun mergedWith(got: IncomingShare): IncomingShare {
        val held = shares[got.shareId]
        if (held == null) {
            replaceFor(got)
            return got
        }
        return held.copy(
            expiresAt = got.expiresAt,
            last = newer(held.last, got.last),
            trailPoints = got.trailPoints.ifEmpty { held.trailPoints },
            snapshot = got.snapshot.ifEmpty { held.snapshot },
        )
    }

    /** **Stop receiving**: allowed while the daily check is due or held (§3.6.3), as a lock is; not while locked. */
    suspend fun stopReceiving(shareId: String) {
        if (!unlocked()) throw VaultFailure(FailureKind.OWNER_CHECK_REQUIRED, "owner_check_required")
        val token = begin()
        try {
            vaultGuard { ops().stop(shareId) }
        } catch (e: VaultFailure) {
            if (e.kind != FailureKind.NOT_FOUND) throw e
        }
        synchronized(lock) {
            if (token.epoch != lockEpoch) return
            changedAt[shareId] = ++seq
            if (drop(shareId)) publish()
        }
    }

    /** **Ask for location** (§10.16): one per connection per 10 minutes; never for a service connection. */
    suspend fun ask(connectionId: String, note: String?): AskResult {
        if (isService(connectionId)) throw VaultFailure(FailureKind.SERVICE_FORBIDDEN)
        requireOpen()
        val text = note?.takeIf { it.isNotBlank() }
        if (text != null && text.toByteArray(Charsets.UTF_8).size > LocationRequestLimits.NOTE_MAX_BYTES) {
            throw VaultFailure(FailureKind.OTHER, "bad_request")
        }
        val token = begin()
        try {
            vaultGuard { ops().request(connectionId, text) }
        } catch (e: VaultFailure) {
            val limited = e.kind == FailureKind.LIMIT && (e.limit == null || e.limit.name == "location_requests")
            if (limited) return AskResult.Limited(minutesLeft(connectionId))
            throw e
        }
        synchronized(lock) { if (token.epoch == lockEpoch) asked[connectionId] = now() }
        return AskResult.Sent
    }

    /** Minutes (1 to 10) until the next ask is allowed; the whole allowance when this phone did not make the earlier ask. */
    private fun minutesLeft(connectionId: String): Int {
        val allowance = LocationRequestLimits.ALLOWANCE_SECONDS
        val since = synchronized(lock) { asked[connectionId] }?.let { Duration.between(it, now()).seconds } ?: 0L
        val left = (allowance - since).coerceIn(1L, allowance)
        return ((left + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).toInt()
    }

    /** Distance and direction from this phone to the connection's latest position, when the phone's own place is known. */
    suspend fun offsetFromHere(connectionId: String): PositionOffset? {
        val p = synchronized(lock) { shares.values.firstOrNull { it.connectionId == connectionId }?.last } ?: return null
        val me = here() ?: return null
        return Offsets.between(me.lat, me.lon, p.lat, p.lon)
    }

    // --- locks ---

    /** The vault locked, was held, wiped or signed out: everything received is dropped, and a read in flight writes nothing. */
    fun clear() {
        synchronized(lock) {
            lockEpoch++
            timers.values.forEach { it.cancel() }
            timers.clear()
            shares.clear()
            changedAt.clear()
            addedAt.clear()
            asked.clear()
            flow.value = emptyList()
        }
    }

    // --- internals, under [lock] ---

    private fun requireOpen() {
        if (!unlocked() || gated()) throw VaultFailure(FailureKind.OWNER_CHECK_REQUIRED, "owner_check_required")
    }

    /** The newer of two positions by the fix's time; a position is never replaced by an older one. */
    private fun newer(a: ReceivedPosition?, b: ReceivedPosition?): ReceivedPosition? = when {
        a == null -> b
        b == null -> a
        b.at.isBefore(a.at) -> a
        else -> b
    }

    /** A new share of a connection replaces an earlier one (§10.16). */
    private fun replaceFor(share: IncomingShare) {
        shares.values.filter { it.connectionId == share.connectionId && it.shareId != share.shareId }.map { it.shareId }
            .forEach { old ->
                drop(old)
                changedAt[old] = ++seq
            }
        put(shares[share.shareId]?.let { share.copy(last = it.last) } ?: share)
    }

    private fun put(share: IncomingShare) {
        if (share.shareId !in shares) addedAt[share.shareId] = ++seq
        shares[share.shareId] = share
        if (timers[share.shareId]?.isActive != true) timers[share.shareId] = scope.launch { expire(share.shareId) }
    }

    /** True when the share was there. */
    private fun drop(id: String): Boolean {
        timers.remove(id)?.cancel()
        addedAt.remove(id)
        return shares.remove(id) != null
    }

    /** Ends the shares whose `expires_at` has passed by the wall clock (the timer runs on uptime, which stops in deep sleep). */
    private fun sweep() {
        val t = now()
        val over = shares.values.filter { !it.expiresAt.isAfter(t) }.map { it.shareId }
        if (over.isNotEmpty()) {
            over.forEach {
                drop(it)
                changedAt[it] = ++seq
            }
            publish()
        }
    }

    private suspend fun expire(id: String) {
        while (true) {
            val at = synchronized(lock) { shares[id]?.expiresAt } ?: return
            val left = Duration.between(now(), at)
            if (left.isNegative || left.isZero) break
            delay(left.toMillis())
        }
        synchronized(lock) {
            if (drop(id)) {
                changedAt[id] = ++seq
                publish()
            }
        }
    }

    private fun publish() {
        flow.value = shares.values.toList()
        if (changedAt.size > MAX_TOMBS) changedAt.entries.removeAll { it.value < seq - MAX_TOMBS }
    }

    private companion object {
        const val TRAIL_MAX = 1_000
        const val SNAPSHOT_MAX = 500
        const val MAX_TOMBS = 1_024
        const val SECONDS_PER_MINUTE = 60L
    }
}

/** Distance and bearing between two places (spherical earth; metres and degrees clockwise from north). Pure arithmetic. */
internal object Offsets {
    private const val EARTH_M = 6_371_000.0
    private const val FULL_TURN = 360.0

    fun between(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): PositionOffset {
        val p1 = Math.toRadians(fromLat)
        val p2 = Math.toRadians(toLat)
        val dp = p2 - p1
        val dl = Math.toRadians(toLon - fromLon)
        val a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        val meters = 2 * EARTH_M * Math.asin(Math.min(1.0, Math.sqrt(a)))
        val y = Math.sin(dl) * Math.cos(p2)
        val x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)
        val bearing = (Math.toDegrees(Math.atan2(y, x)) + FULL_TURN) % FULL_TURN
        return PositionOffset(meters, if (bearing >= FULL_TURN) 0.0 else bearing)
    }
}
