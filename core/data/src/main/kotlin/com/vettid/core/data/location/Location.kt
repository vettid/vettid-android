package com.vettid.core.data.location

import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.LocationLimits
import com.vettid.core.vault.LocationSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant

/*
 * Sharing the member's location (ANDROID-PLAN §4 "Location", L1a; VAULT-MESSAGING §10.16): the types of the sending side. A
 * position is never one of these: [Fix] is held only until it is sent and prints its type name, and nothing here is written
 * anywhere but the share ids of [StoredShare].
 */

/** What a share shows of the position: the vault reduces each one to it (`exact` about 1 m, `approximate` 1 km, `city` 11 km). */
enum class SharePrecision(val wire: String) {
    APPROXIMATE("approximate"),
    EXACT("exact"),
    CITY("city"),
}

/** How long a continuous share lasts: the sheet's five choices (5 minutes to 7 days are the vault's bounds). */
@Suppress("MagicNumber") // seconds of the sheet's choices, in the plan's words
enum class ShareLength(val seconds: Long) {
    MINUTES_15(15 * 60L),
    HOUR_1(60 * 60L),
    HOURS_8(8 * 60 * 60L),
    DAY_1(24 * 60 * 60L),
    DAYS_7(7 * 24 * 60 * 60L),
    ;

    companion object {
        /** The sheet's default (owner: the plan's "1 hour (default)"). */
        val DEFAULT = HOUR_1
    }
}

/** How often a continuous share is sent: the sheet's four choices (10 s to 1 hour are the vault's bounds). */
@Suppress("MagicNumber") // seconds of the sheet's choices, in the plan's words
enum class ShareInterval(val seconds: Long) {
    SECONDS_30(30L),
    MINUTE_1(60L),
    MINUTES_5(5 * 60L),
    MINUTES_15(15 * 60L),
    ;

    companion object {
        val DEFAULT = MINUTE_1
    }
}

/** Once (a single position; no duration or interval) or for a while. */
sealed interface ShareTiming {
    data object Once : ShareTiming

    data class ForAWhile(val length: ShareLength = ShareLength.DEFAULT, val interval: ShareInterval = ShareInterval.DEFAULT) : ShareTiming
}

/** What the share sheet sends as `location.share.start`. [requestId] answers a request (L1b). */
data class ShareRequest(
    val connectionId: String,
    val timing: ShareTiming = ShareTiming.ForAWhile(),
    val precision: SharePrecision = SharePrecision.APPROXIMATE,
    /** "Let {First} keep the trail": the receiving vault may keep a trail of positions (`history`). */
    val trail: Boolean = false,
    val requestId: String? = null,
) {
    /** `duration_seconds`: only a continuous share has one. */
    val durationSeconds: Long? get() = (timing as? ShareTiming.ForAWhile)?.length?.seconds

    /** `interval_seconds`: only a continuous share has one. */
    val intervalSeconds: Long? get() = (timing as? ShareTiming.ForAWhile)?.interval?.seconds

    val once: Boolean get() = timing == ShareTiming.Once

    /** The vault's bounds (§10.16 "Starting"): once has neither duration nor interval, a continuous share both within range. */
    fun withinBounds(): Boolean {
        val d = durationSeconds
        val i = intervalSeconds
        return if (once) {
            d == null && i == null
        } else {
            d != null && i != null &&
                d in LocationLimits.MIN_DURATION_SECONDS..LocationLimits.MAX_DURATION_SECONDS &&
                i in LocationLimits.MIN_INTERVAL_SECONDS..LocationLimits.MAX_INTERVAL_SECONDS
        }
    }
}

/**
 * One of the member's outgoing shares (`location.share.list` `outgoing`, or what `location.share.start` answered). [fromThisPhone]:
 * this phone is its source (`device_id` is this phone's), so only this phone's positions feed it. [lastSentAt] is the time of
 * the last position this phone sent for it (the vault's `last_sent_at`, or this phone's own send), never a position.
 */
data class OutgoingShare(
    val shareId: String,
    val connectionId: String,
    val once: Boolean,
    val precision: SharePrecision,
    val intervalSeconds: Long?,
    val trail: Boolean,
    val expiresAt: Instant,
    val lastSentAt: Instant?,
    val fromThisPhone: Boolean,
)

/** What the foreground service shows: whom this phone is sending to, and whether it is paused. Never a position. */
data class ServiceState(
    val targets: List<Target> = emptyList(),
    /** The daily owner check is due or held: nothing is sent ("Paused until your daily check."). */
    val paused: Boolean = false,
    /** The shares were taken up again after a lock or a restart ("Sharing your location with {First} again, until {time}."). */
    val resumed: Boolean = false,
) {
    data class Target(val connectionId: String, val expiresAt: Instant)

    val active: Boolean get() = targets.isNotEmpty()
}

/** One position of this phone, held only until it is sent. **Prints its type name only.** */
class Fix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double?,
    val altitudeM: Double?,
    val speedMps: Double?,
    val headingDeg: Double?,
    val at: Instant,
    /** The platform's uptime reading for this fix (`elapsedRealtimeNanos`): its age is judged by it, not by [at]. */
    val elapsedNanos: Long? = null,
) {
    /** The sample sent to the vault; the optional members only when [withExtras] (some share is exact). */
    internal fun sample(withExtras: Boolean) = LocationSample(
        lat,
        lon,
        accuracyM,
        altitudeM.takeIf { withExtras },
        speedMps.takeIf { withExtras },
        headingDeg.takeIf { withExtras },
        at,
    )

    override fun toString(): String = "Fix"
}

/** How often the platform is to deliver fixes, and how precisely. */
data class PositionRequest(val intervalMs: Long, val highAccuracy: Boolean)

/** The phone's positions (the platform `LocationManager` in `:core:location`; a fake in tests). */
interface PositionProvider {
    /** Fixes until the collector is cancelled. Ends with an error when the platform refuses (no permission). */
    fun fixes(request: PositionRequest): Flow<Fix>
}

/** What the app attaches to the sharing: the positions and the foreground service. */
interface LocationPlatform {
    val positions: PositionProvider

    /** Starts the foreground service, or keeps it. False when the system refused it (the app is not in front). */
    fun startService(): Boolean

    fun stopService()

    /** Whether precise (fine) location is allowed: with approximate access only, a once share cannot wait for 100 m. */
    fun precise(): Boolean = true
}

/** The vault calls behind [LocationSharing] (§10.16), so that it can be tested without a vault. */
interface LocationOps {
    suspend fun start(r: ShareRequest): JsonObject

    /** The ephemeral `location.update`, `exp` = the vault's now + [ttl]. */
    suspend fun update(sample: LocationSample, ttl: Duration)

    suspend fun stop(shareId: String): JsonObject

    suspend fun list(): JsonObject
}

/**
 * Sharing the member's location (ANDROID-PLAN §4 "Location"). Everything is in memory except the share ids of [StoredShare].
 * Nothing is sent unless this phone is the source of an active share, the vault is open and the daily owner check is neither
 * due nor held.
 */
interface LocationRepository {
    /** The member's active outgoing shares, from this phone and from other devices (memory only; read at unlock). */
    val outgoing: StateFlow<List<OutgoingShare>>

    /** What the foreground service shows. */
    val serviceState: StateFlow<ServiceState>

    /**
     * `location.share.start`, then the foreground service. Only from the member's tap while the app is visible. Throws the
     * vault's [VaultFailure] (`owner_check_required` while the check is due or held).
     */
    suspend fun start(request: ShareRequest): OutgoingShare

    /**
     * Stops sending for [shareId] at once, remembers the stop on disk (ids only), then tells the vault (`location.share.stop`,
     * allowed while the daily check is due or held). A stop the vault did not take (no connection, a locked vault) is thrown and
     * tried again; the phone sends nothing for the share meanwhile, and a lock or a restart never takes it up again.
     */
    suspend fun stop(shareId: String)

    /** Stops every share this phone is sending (the notification's **Stop all**). Never throws. */
    fun stopAll()

    /** Reads `location.share.list` again. */
    suspend fun refresh()

    /** Whether this vault has location: learnt from `location.share.list` (at unlock) or the first start. */
    val availability: StateFlow<LocationAvailability> get() = NO_AVAILABILITY

    /** The app came to the front: a service start the system refused in the background is tried again. */
    fun onForeground() = Unit

    /** The app is in front or not (the process's lifecycle): positions are collected without the service only while it is. */
    fun setInFront(front: Boolean) = Unit

    /** The foreground service ended itself (the system refused it, or it failed): it is started again when the app is in front. */
    fun onServiceEnded() = Unit
}

/** Whether this vault has location: learnt by asking, since no message lists a release's features. */
enum class LocationAvailability { UNKNOWN, AVAILABLE, UNSUPPORTED }

private val NO_AVAILABILITY: StateFlow<LocationAvailability> = MutableStateFlow(LocationAvailability.UNSUPPORTED)

/** Sharing where there is none (a screen's default): nothing is shared or sent. */
object NoLocation : LocationRepository {
    override val outgoing: StateFlow<List<OutgoingShare>> = MutableStateFlow(emptyList())
    override val serviceState: StateFlow<ServiceState> = MutableStateFlow(ServiceState())
    override val availability: StateFlow<LocationAvailability> = MutableStateFlow(LocationAvailability.UNSUPPORTED)

    override suspend fun start(request: ShareRequest): OutgoingShare = throw VaultFailure(FailureKind.NOT_SUPPORTED)

    override suspend fun stop(shareId: String) = Unit

    override fun stopAll() = Unit

    override suspend fun refresh() = Unit
}
