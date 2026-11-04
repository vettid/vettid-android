package com.vettid.core.data.location

import com.vettid.core.vault.LocationLimits
import com.vettid.core.vault.LocationSample
import com.vettid.core.vault.LocationWire as Words
import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.locationGet
import com.vettid.core.vault.locationRequest
import com.vettid.core.vault.locationShareList
import com.vettid.core.vault.locationShareStart
import com.vettid.core.vault.locationShareStop
import com.vettid.core.vault.locationUpdate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/** [LocationOps] over the vault client. */
internal class VaultLocationOps(private val api: VaultApi) : LocationOps {
    override suspend fun start(r: ShareRequest): JsonObject = api.locationShareStart(
        r.connectionId,
        if (r.once) Words.MODE_ONCE else Words.MODE_CONTINUOUS,
        r.precision.wire,
        r.durationSeconds,
        r.intervalSeconds,
        r.trail,
        r.requestId,
    )

    override suspend fun update(sample: LocationSample, ttl: Duration) = api.locationUpdate(sample, ttl)

    override suspend fun stop(shareId: String): JsonObject = api.locationShareStop(shareId)

    override suspend fun list(): JsonObject = api.locationShareList()

    override suspend fun get(connectionId: String, history: Boolean): JsonObject = api.locationGet(connectionId, history)

    override suspend fun request(connectionId: String, note: String?): JsonObject = api.locationRequest(connectionId, note)
}

/** Reading the vault's answers of §10.16. An answer that does not read gives null; nothing is guessed at. */
@Suppress("ReturnCount", "ComplexCondition") // readers that return null at the first member that does not read
internal object ShareWire {
    /** `{share_id, expires_at}` of `location.share.start`. */
    class Started(val shareId: String, val expiresAt: Instant)

    fun started(body: JsonObject): Started? {
        val id = VaultJson.str(body, "share_id")?.takeIf { it.isNotEmpty() }
        val expires = instant(VaultJson.str(body, "expires_at"))
        return if (id == null || expires == null) null else Started(id, expires)
    }

    /**
     * The `outgoing` shares of `location.share.list`; [self] is this phone's device id (the source of a share when it matches),
     * [mine] the share ids this phone kept (a source whatever the device id). Null when any entry does not read.
     */
    fun outgoing(body: JsonObject, self: String?, mine: Set<String> = emptySet(), now: Instant = Instant.now()): List<OutgoingShare>? {
        val entries = (body["outgoing"] as? JsonArray)?.map { it as? JsonObject }
        val read = entries?.map { o -> o?.let { share(it, self, mine, now) } }
        return read?.takeIf { r -> r.none { it == null } }?.filterNotNull()
    }

    private fun share(o: JsonObject, self: String?, mine: Set<String>, now: Instant): OutgoingShare? {
        val id = VaultJson.str(o, "share_id")?.takeIf { it.isNotEmpty() }
        val connection = VaultJson.str(o, "connection_id")?.takeIf { it.isNotEmpty() }
        val mode = VaultJson.str(o, "mode")
        val precision = SharePrecision.entries.firstOrNull { it.wire == VaultJson.str(o, "precision") }
        val expires = instant(VaultJson.str(o, "expires_at"))
        val once = mode == Words.MODE_ONCE
        val known = once || mode == Words.MODE_CONTINUOUS
        val whole = id != null && connection != null && precision != null && expires != null
        return if (!whole || !known || !strict(o, once, expires!!, now)) {
            null
        } else {
            OutgoingShare(
                shareId = id!!,
                connectionId = connection!!,
                once = once,
                precision = precision!!,
                intervalSeconds = VaultJson.long(o, "interval_seconds"),
                trail = (o["history"] as kotlinx.serialization.json.JsonPrimitive).content == "true",
                expiresAt = expires!!,
                lastSentAt = instant(VaultJson.str(o, "last_sent_at")),
                // The vault's `device_id` decides when this phone's id is known; the stored ids are the fallback when it is not.
                fromThisPhone = if (self != null) VaultJson.str(o, "device_id") == self else id in mine,
            )
        }
    }

    /**
     * The vault's bounds (§10.16), strictly: a continuous entry has `interval_seconds` from 10 to 3,600, a once entry none,
     * `history` is a boolean, and `expires_at` is at most 7 days ahead (with a minute for the clocks). One entry that is not so
     * makes the whole list unreadable.
     */
    private fun strict(o: JsonObject, once: Boolean, expires: Instant, now: Instant): Boolean {
        val interval = o["interval_seconds"]
        val seconds = VaultJson.long(o, "interval_seconds")
        val intervalOk = if (once) interval == null else seconds != null &&
            seconds in LocationLimits.MIN_INTERVAL_SECONDS..LocationLimits.MAX_INTERVAL_SECONDS
        val history = (o["history"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        val ahead = expires.isAfter(now.plusSeconds(LocationLimits.MAX_DURATION_SECONDS + CLOCK_SLACK_SECONDS))
        return intervalOk && history != null && !ahead
    }

    // --- receiving (L1b) ---

    /**
     * The `incoming` shares of `location.share.list`: `{share_id, connection_id, mode, precision, interval_seconds?, history,
     * started_at, expires_at, last?}`. Strict as [outgoing]: one entry that does not read (or is out of the vault's bounds) makes
     * the whole list null. An entry already over (`expires_at` not in the future) is left out. Positions are read here and
     * nowhere else; they print their type names only.
     */
    fun incoming(body: JsonObject, now: Instant = Instant.now()): List<IncomingShare>? {
        val entries = (body["incoming"] as? JsonArray)?.map { it as? JsonObject } ?: return null
        val read = entries.map { o -> o?.let { incomingShare(it, now) } }
        if (read.any { it == null }) return null
        return read.filterNotNull().filter { it.expiresAt.isAfter(now) }
    }

    private fun incomingShare(o: JsonObject, now: Instant): IncomingShare? {
        val id = VaultJson.str(o, "share_id")?.takeIf { it.isNotEmpty() }
        val connection = VaultJson.str(o, "connection_id")?.takeIf { it.isNotEmpty() }
        val mode = VaultJson.str(o, "mode")
        val precision = SharePrecision.entries.firstOrNull { it.wire == VaultJson.str(o, "precision") }
        val expires = instant(VaultJson.str(o, "expires_at"))
        val once = mode == Words.MODE_ONCE
        val started = if (o["started_at"] == null) null else instant(VaultJson.str(o, "started_at")) ?: return null
        val lastObj = o["last"]
        val last = when (lastObj) {
            null -> null
            is JsonObject -> position(lastObj, receivedKey = true) ?: return null
            else -> return null
        }
        val whole = id != null && connection != null && precision != null && expires != null
        val known = once || mode == Words.MODE_CONTINUOUS
        return if (!whole || !known || !strict(o, once, expires!!, now)) {
            null
        } else {
            IncomingShare(
                shareId = id!!,
                connectionId = connection!!,
                once = once,
                precision = precision!!,
                intervalSeconds = VaultJson.long(o, "interval_seconds"),
                trail = (o["history"] as JsonPrimitive).content == "true",
                startedAt = started,
                expiresAt = expires!!,
                last = last,
            )
        }
    }

    /**
     * One position as the vault sends it (`last`, a trail point, a snapshot point, or a live `location.update`): `lat` -90 to 90,
     * `lon` -180 to 180, `accuracy_m` 0 to 1,000,000, `altitude_m` +-100,000, `speed_mps` 0 to 10,000, `heading_deg` 0 up to 360
     * (all JSON numbers, never strings) and `at`. A member that is absent is null; one that is present and out of range or of
     * the wrong type makes the whole position null (it is dropped, never clamped into a different place). [receivedKey]: `received_at`
     * is read as the vault's time of receipt.
     */
    fun position(o: JsonObject, receivedKey: Boolean = false, requireAccuracy: Boolean = false): ReceivedPosition? {
        val lat = number(o, "lat", -LAT_MAX, LAT_MAX)
        val lon = number(o, "lon", -LON_MAX, LON_MAX)
        val at = instant(VaultJson.str(o, "at"))
        val accuracy = optional(o, "accuracy_m", 0.0, LocationLimits.MAX_ACCURACY_M)
        val altitude = optional(o, "altitude_m", -LocationLimits.MAX_ALTITUDE_M, LocationLimits.MAX_ALTITUDE_M)
        val speed = optional(o, "speed_mps", 0.0, LocationLimits.MAX_SPEED_MPS)
        val heading = optional(o, "heading_deg", 0.0, LocationLimits.FULL_TURN_DEG)
        val received = if (receivedKey && o["received_at"] != null) instant(VaultJson.str(o, "received_at")) ?: return null else null
        val bad = listOf(accuracy, altitude, speed, heading).any { it != null && it.isNaN() }
        val turned = heading != null && heading >= LocationLimits.FULL_TURN_DEG
        if (lat == null || lon == null || at == null || bad || turned || (requireAccuracy && accuracy == null)) return null
        // The vault writes 0 when the sender left the accuracy out: 0 is unknown, never "perfectly accurate".
        return ReceivedPosition(lat, lon, accuracy?.takeIf { it > 0.0 }, altitude, speed, heading, at, received)
    }

    private fun number(o: JsonObject, k: String, lo: Double, hi: Double): Double? =
        (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it in lo..hi }

    /** Absent: null. Present and in range: the number. Present and not: [BAD] (NaN). */
    private fun optional(o: JsonObject, k: String, lo: Double, hi: Double): Double? =
        if (o[k] == null) null else number(o, k, lo, hi) ?: BAD

    /**
     * `location.get` for [connectionId]: `{share_id, mode, precision, expires_at, last?, history?, snapshot?}`. Null when it does
     * not read. `history` and `snapshot` are arrays of positions (at most 1,000 and 500); one point that does not read makes the
     * answer unreadable.
     */
    fun get(connectionId: String, body: JsonObject, now: Instant = Instant.now()): IncomingShare? {
        val id = VaultJson.str(body, "share_id")?.takeIf { it.isNotEmpty() }
        val mode = VaultJson.str(body, "mode")
        val precision = SharePrecision.entries.firstOrNull { it.wire == VaultJson.str(body, "precision") }
        val expires = instant(VaultJson.str(body, "expires_at"))
        val last = when (val l = body["last"]) {
            null -> null
            is JsonObject -> position(l, receivedKey = true, requireAccuracy = true) ?: return null
            else -> return null
        }
        val trail = points(body["history"], MAX_TRAIL, requireAccuracy = true) ?: return null
        val snapshot = points(body["snapshot"], MAX_SNAPSHOT, requireAccuracy = true) ?: return null
        val known = mode == Words.MODE_ONCE || mode == Words.MODE_CONTINUOUS
        val ahead = expires?.isAfter(now.plusSeconds(LocationLimits.MAX_DURATION_SECONDS + CLOCK_SLACK_SECONDS)) != false
        return if (id == null || precision == null || !known || ahead) {
            null
        } else {
            IncomingShare(
                shareId = id,
                connectionId = connectionId,
                once = mode == Words.MODE_ONCE,
                precision = precision,
                intervalSeconds = null,
                trail = body["history"] != null,
                startedAt = null,
                expiresAt = expires!!,
                last = last,
                trailPoints = trail,
                snapshot = snapshot,
            )
        }
    }

    /** An array of positions; absent is empty; null when it is not an array, holds too many, or one point does not read. */
    fun points(e: kotlinx.serialization.json.JsonElement?, max: Int, requireAccuracy: Boolean = false): List<ReceivedPosition>? = when (e) {
        null -> emptyList()
        is JsonArray -> e.takeIf { it.size <= max }
            ?.map { (it as? JsonObject)?.let { p -> position(p, requireAccuracy = requireAccuracy) } }
            ?.takeIf { r -> r.none { it == null } }?.filterNotNull()
        else -> null
    }

    /** What `location.event{started, direction: in}` says of the share: mode, precision and `expires_at`; null when it does not read. */
    fun startedEvent(body: JsonObject, now: Instant = Instant.now()): IncomingShare? {
        val id = VaultJson.str(body, "share_id")?.takeIf { it.isNotEmpty() }
        val connection = VaultJson.str(body, "connection_id")?.takeIf { it.isNotEmpty() }
        val mode = VaultJson.str(body, "mode")
        val precision = SharePrecision.entries.firstOrNull { it.wire == VaultJson.str(body, "precision") }
        val expires = instant(VaultJson.str(body, "expires_at"))
        val known = mode == Words.MODE_ONCE || mode == Words.MODE_CONTINUOUS
        val inRange = expires != null && expires.isAfter(now) &&
            !expires.isAfter(now.plusSeconds(LocationLimits.MAX_DURATION_SECONDS + CLOCK_SLACK_SECONDS))
        return if (id == null || connection == null || precision == null || !known || !inRange) {
            null
        } else {
            IncomingShare(id, connection, mode == Words.MODE_ONCE, precision, null, trail = false, startedAt = null, expiresAt = expires!!)
        }
    }

    private const val LAT_MAX = 90.0
    private const val LON_MAX = 180.0
    private const val MAX_TRAIL = 1_000
    private const val MAX_SNAPSHOT = 500

    /** Marks a member that is present and invalid, apart from absent (null). */
    private val BAD = Double.NaN

    private const val CLOCK_SLACK_SECONDS = 60L

    private fun instant(s: String?): Instant? = try {
        s?.let { Instant.parse(it) }
    } catch (_: DateTimeParseException) {
        null
    }
}
