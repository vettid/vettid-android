package com.vettid.core.vault

import com.vettid.core.crypto.envelope.Timestamps
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant

/*
 * The requests of "Location" (ANDROID-PLAN §4 "Location"; VAULT-MESSAGING §10.16), beside [VaultApi] rather than in it: the
 * sending side only (L1a): `location.share.start`, the ephemeral `location.update` of a position, `location.share.stop` and
 * `location.share.list`. A position is built here from numbers and goes out at once: nothing in this file stores one, prints
 * one or puts one in an error (a [LocationSample] prints its type name only).
 */

/** The bounds of §10.16 "Starting" and "Positions" that the app keeps to. */
object LocationLimits {
    /** `duration_seconds` of a continuous share: 5 minutes to 7 days. */
    const val MIN_DURATION_SECONDS = 300L
    const val MAX_DURATION_SECONDS = 604_800L

    /** `interval_seconds` of a continuous share: 10 s to 1 hour. */
    const val MIN_INTERVAL_SECONDS = 10L
    const val MAX_INTERVAL_SECONDS = 3_600L

    /** The vault's defaults for a continuous share. */
    const val DEFAULT_DURATION_SECONDS = 3_600L
    const val DEFAULT_INTERVAL_SECONDS = 60L

    /** A `once` share lasts this long: the window in which its single position is delivered (the vault's rule). */
    const val ONCE_SECONDS = 900L

    /** A position's `exp` is at least this far ahead: now + max(2 x interval, 60 s). */
    const val MIN_UPDATE_TTL_SECONDS = 60L

    /** The vault drops a position whose `at` is more than 1 hour behind; the app sends none older than 5 minutes. */
    val MAX_FIX_AGE: Duration = Duration.ofMinutes(5)

    /** `accuracy_m` 0 to 1,000,000; `altitude_m` +-100,000; `speed_mps` 0 to 10,000; `heading_deg` 0 up to 360. */
    const val MAX_ACCURACY_M = 1_000_000.0
    const val MAX_ALTITUDE_M = 100_000.0
    const val MAX_SPEED_MPS = 10_000.0
    const val FULL_TURN_DEG = 360.0
}

/** Wire words of §10.16. */
object LocationWire {
    const val MODE_ONCE = "once"
    const val MODE_CONTINUOUS = "continuous"
    const val PRECISION_APPROXIMATE = "approximate"
    const val PRECISION_EXACT = "exact"
    const val PRECISION_CITY = "city"
}

/**
 * One position of this phone, as `location.update` (D to V) carries it. Latitude and longitude are checked at once
 * ([LocationMessageException] without their values); the optional members are omitted when null or out of range, never
 * clamped into a different position. **Prints its type name only.**
 */
class LocationSample(
    private val lat: Double,
    private val lon: Double,
    private val accuracyM: Double?,
    private val altitudeM: Double?,
    private val speedMps: Double?,
    private val headingDeg: Double?,
    val at: Instant,
) {
    init {
        if (!(lat in -LAT_MAX..LAT_MAX && lon in -LON_MAX..LON_MAX)) throw LocationMessageException("coordinates")
    }

    /** The `location.update` body: the numbers and `at`, nothing else (what goes to the vault). */
    fun body(): JsonObject = buildJsonObject {
        put("lat", JsonPrimitive(lat))
        put("lon", JsonPrimitive(lon))
        accuracyM?.takeIf { it in 0.0..LocationLimits.MAX_ACCURACY_M }?.let { put("accuracy_m", JsonPrimitive(it)) }
        altitudeM?.takeIf { it in -LocationLimits.MAX_ALTITUDE_M..LocationLimits.MAX_ALTITUDE_M }
            ?.let { put("altitude_m", JsonPrimitive(it)) }
        speedMps?.takeIf { it in 0.0..LocationLimits.MAX_SPEED_MPS }?.let { put("speed_mps", JsonPrimitive(it)) }
        headingDeg?.takeIf { it >= 0.0 && it < LocationLimits.FULL_TURN_DEG }?.let { put("heading_deg", JsonPrimitive(it)) }
        put("at", Timestamps.formatMillis(at))
    }

    override fun toString(): String = "LocationSample"

    private companion object {
        const val LAT_MAX = 90.0
        const val LON_MAX = 180.0
    }
}

/** A position or a start the app would not send (an out-of-range value); the text never holds a value. */
class LocationMessageException(message: String) : java.io.IOException("location: $message")

/**
 * `location.share.start{connection_id, mode, precision, duration_seconds?, interval_seconds?, history, request_id?}`:
 * `{share_id, expires_at}`; `not_found`, `connection_unavailable`, `limit`, `bad_request`. A `once` share has neither duration
 * nor interval (`bad_request`); a continuous one has both, within [LocationLimits].
 */
@Suppress("LongParameterList")
suspend fun VaultApi.locationShareStart(
    connectionId: String,
    mode: String,
    precision: String,
    durationSeconds: Long?,
    intervalSeconds: Long?,
    history: Boolean,
    requestId: String? = null,
): JsonObject = device.op(
    "location.share.start",
    locationStartBody(connectionId, mode, precision, durationSeconds, intervalSeconds, history, requestId),
)

/** The request of [locationShareStart], checked against the vault's bounds first ([LocationMessageException] otherwise). */
@Suppress("LongParameterList")
internal fun locationStartBody(
    connectionId: String,
    mode: String,
    precision: String,
    durationSeconds: Long?,
    intervalSeconds: Long?,
    history: Boolean,
    requestId: String?,
): JsonObject {
    val once = mode == LocationWire.MODE_ONCE
    val continuous = mode == LocationWire.MODE_CONTINUOUS
    val ok = (once && durationSeconds == null && intervalSeconds == null) ||
        (
            continuous &&
                durationSeconds in LocationLimits.MIN_DURATION_SECONDS..LocationLimits.MAX_DURATION_SECONDS &&
                intervalSeconds in LocationLimits.MIN_INTERVAL_SECONDS..LocationLimits.MAX_INTERVAL_SECONDS
            )
    val known = precision in setOf(LocationWire.PRECISION_APPROXIMATE, LocationWire.PRECISION_EXACT, LocationWire.PRECISION_CITY)
    if (!ok || !known) throw LocationMessageException("share bounds")
    return buildJsonObject {
        put("connection_id", connectionId)
        put("mode", mode)
        put("precision", precision)
        durationSeconds?.let { put("duration_seconds", it) }
        intervalSeconds?.let { put("interval_seconds", it) }
        put("history", history)
        requestId?.let { put("request_id", it) }
    }
}

/**
 * `location.update` (device to vault, ephemeral: no answer, `exp` required): one position for every outgoing share whose
 * source is this device. Sent once, never through the outbox ([VaultDevice.sendOnce]); `exp` is the vault's clock plus [ttl]
 * (the caller's max(2 x interval, 60 s)).
 */
suspend fun VaultApi.locationUpdate(sample: LocationSample, ttl: Duration) {
    device.sendOnce("location.update", sample.body(), device.clockNow().plus(ttl))
}

/** `location.share.stop{share_id}`: `{}`; `not_found`. Refused with `owner_check_required` while the daily check is due or held. */
suspend fun VaultApi.locationShareStop(shareId: String): JsonObject =
    device.op("location.share.stop", buildJsonObject { put("share_id", shareId) })

/**
 * `location.share.list{}`: `{outgoing: [{share_id, connection_id, mode, precision, interval_seconds?, history, device_id,
 * started_at, expires_at, last_sent_at?}], incoming: [...]}` (active shares).
 */
suspend fun VaultApi.locationShareList(): JsonObject = device.op("location.share.list")
