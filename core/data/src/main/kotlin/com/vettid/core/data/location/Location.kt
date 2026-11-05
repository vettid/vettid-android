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
    /** Why nothing is being sent although shares are active (shown on the notification and the card), or [SendIssue.NONE]. */
    val issue: SendIssue = SendIssue.NONE,
    /** Connections with a share whose stop the vault has not taken yet (sending for them has ended): "Stopping…". */
    val stopping: Set<String> = emptySet(),
    /** The ids of the shares whose stop the vault has not taken yet (a stop is addressed by its own share id). */
    val stoppingShares: Set<String> = emptySet(),
    /**
     * Connections whose continuous share has had no position accepted by the vault for its update interval plus [STALL_GRACE]
     * (A6 N21), with the whole minutes since the last accepted position (or since the share started or was taken up). Never
     * while the check is due or held, or the vault is locked: that is a pause, not a stall. A `once` share is not in here
     * ([LocationRepository.missedOnce] says it). A time only, never a position.
     */
    val stalled: Map<String, Int> = emptyMap(),
) {
    data class Target(val connectionId: String, val expiresAt: Instant)

    val active: Boolean get() = targets.isNotEmpty()
}

/**
 * The active outgoing share to [connectionId], or null: the share not past its `expires_at`, not a once share already delivered,
 * and not being stopped (judged per share id: a pending stop never ends a newer share). Of several, the one that lasts longest.
 * The one rule Approvals and the share sheet both use, so that they cannot disagree.
 */
fun activeShareTo(shares: List<OutgoingShare>, service: ServiceState, connectionId: String, now: Instant): OutgoingShare? =
    shares.filter {
        it.connectionId == connectionId && it.expiresAt.isAfter(now) && !(it.once && it.lastSentAt != null) &&
            it.shareId !in service.stoppingShares
    }.maxByOrNull { it.expiresAt }

/** A share is stalled when this long (on top of its update interval) has passed without a position accepted by the vault. */
val STALL_GRACE: Duration = Duration.ofMinutes(15)

/** Why this phone is not sending positions while a share is active. */
enum class SendIssue {
    NONE,

    /** A stop the vault has not taken yet: nothing is sent at all until it has (each position goes to every share). */
    STOP_PENDING,

    /** Location is off for VettID in Android (the permission was taken away). */
    NO_PERMISSION,

    /** Location is switched off on the phone. */
    LOCATION_OFF,

    /** The foreground service is not running and the app is not in front. */
    SERVICE,
    ;

    /** The failure code a refused start carries for this issue, and back ([of]). */
    val code: String get() = "send_issue_" + name.lowercase()

    companion object {
        fun of(code: String?): SendIssue? = entries.firstOrNull { it != NONE && it.code == code }
    }
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

    /**
     * Shows (or takes away) a plain notice that nothing is being sent because the service is not running and the app is not in
     * front ([SendIssue.SERVICE]): "Not sending: open VettID to carry on.", opening the app.
     */
    fun showNotRunning(show: Boolean) = Unit

    /**
     * Posts (or silently updates the minutes of) the notice that [connectionId]'s share is not getting through
     * ([ServiceState.stalled]) on the Location sharing channel; it names nobody when the app lock's cover is up. Opens the app.
     */
    fun showStalled(connectionId: String, minutes: Int) = Unit

    /** Takes that notice away: a position got through, or the share ended or paused. */
    fun clearStalled(connectionId: String) = Unit

    /** Why the platform cannot deliver positions now (the permission, the phone's location switch), or [SendIssue.NONE]. */
    fun issue(): SendIssue = SendIssue.NONE

    /** Whether precise (fine) location is allowed: with approximate access only, a once share cannot wait for 100 m. */
    fun precise(): Boolean = true

    /**
     * Where this phone is now, only when the permission is already granted (never asks for it): the distance and direction of a
     * received position. Null without the permission, with location off, or when no fix comes in a few seconds. Read once and
     * dropped by the caller; a position the system merely remembers is never read.
     */
    suspend fun currentFix(): Fix? = null
}

/** The vault calls behind [LocationSharing] (§10.16), so that it can be tested without a vault. */
interface LocationOps {
    suspend fun start(r: ShareRequest): JsonObject

    /** The ephemeral `location.update`, `exp` = the vault's now + [ttl]. */
    suspend fun update(sample: LocationSample, ttl: Duration)

    suspend fun stop(shareId: String): JsonObject

    suspend fun list(): JsonObject

    /** `location.get{connection_id, history}` (L1b): the latest position, the trail and a snapshot of an incoming share. */
    suspend fun get(connectionId: String, history: Boolean): JsonObject = throw VaultFailure(FailureKind.NOT_SUPPORTED)

    /** `location.request{connection_id, note?}` (L1b): `{request_id}`; `limit` once per connection per 10 minutes. */
    suspend fun request(connectionId: String, note: String?): JsonObject = throw VaultFailure(FailureKind.NOT_SUPPORTED)
}

/**
 * Sharing the member's location (ANDROID-PLAN §4 "Location"). Everything is in memory except the share ids of [StoredShare].
 * Nothing is sent unless this phone is the source of an active share, the vault is open and the daily owner check is neither
 * due nor held.
 */
@Suppress("TooManyFunctions") // sending (L1a) and receiving (L1b) are one repository: one screen default, one fake
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

    /** Connections whose `once` share ended without a position being found (memory only; cleared at the next share). */
    val missedOnce: StateFlow<Set<String>> get() = NO_MISSED

    /** Whether this vault has location: learnt from `location.share.list` (at unlock) or the first start. */
    val availability: StateFlow<LocationAvailability> get() = NO_AVAILABILITY

    /** The app came to the front: a service start the system refused in the background is tried again. */
    fun onForeground() = Unit

    /** The app is in front or not (the process's lifecycle): positions are collected without the service only while it is. */
    fun setInFront(front: Boolean) = Unit

    /** The foreground service ended itself (the system refused it, or it failed): it is started again when the app is in front. */
    fun onServiceEnded() = Unit

    // --- receiving (L1b) ---

    /**
     * The shares connections have with this member (memory only, read at unlock and kept current by `location.event`), each with
     * its latest position once one is known. Cleared at lock, hold and wipe.
     */
    val incoming: StateFlow<List<IncomingShare>> get() = NO_INCOMING

    /**
     * A screen of [connectionId] opens: `location.get` reads the latest position (and the trail, when shared). A connection that
     * shares nothing with this member (`not_found`) ends the share here. Throws the vault's [VaultFailure].
     */
    suspend fun open(connectionId: String) = Unit

    /** **Stop receiving**: `location.share.stop` of an incoming share; the share and its positions are dropped here. */
    suspend fun stopReceiving(shareId: String) = Unit

    /**
     * **Ask for location**: `location.request` with an optional [note] of at most 256 bytes. A second ask within 10 minutes is
     * [AskResult.Limited] with the minutes left. Never for a service connection.
     */
    suspend fun ask(connectionId: String, note: String?): AskResult = throw VaultFailure(FailureKind.NOT_SUPPORTED)

    /**
     * How far, and in which direction, the position of [connectionId]'s share is from this phone: only when the member has
     * already allowed location and the phone knows where it is (never asks for the permission). Null otherwise. The phone's own
     * position is read and dropped inside; only the offset is returned.
     */
    suspend fun offsetFromHere(connectionId: String): PositionOffset? = null
}

/** The answer to **Ask for location**. */
sealed interface AskResult {
    data object Sent : AskResult

    /** One request per connection per 10 minutes (§10.16): [minutes] (1 to 10) until the next. */
    data class Limited(val minutes: Int) : AskResult
}

/** Distance in metres and the bearing in degrees (0 = north, clockwise) from this phone to a position. */
data class PositionOffset(val meters: Double, val bearingDeg: Double) {
    /** Prints its type name only: an offset from this phone says where it is. */
    override fun toString(): String = "PositionOffset"
}

/**
 * One position of a connection's share, held in memory only (never in Room, a file, DataStore, `SavedStateHandle`, a log or a
 * crash report). **Prints its type name only.** [at] is the fix's time, [receivedAt] the vault's (null from a live update:
 * this phone's clock then).
 */
class ReceivedPosition(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double?,
    val altitudeM: Double?,
    val speedMps: Double?,
    val headingDeg: Double?,
    val at: Instant,
    val receivedAt: Instant?,
) {
    override fun toString(): String = "ReceivedPosition"
}

/**
 * A share a connection has with this member (`location.share.list` `incoming`, `location.event{started, in}`). [last], [trailPoints]
 * and [snapshot] are positions (memory only; they print their type names).
 */
data class IncomingShare(
    val shareId: String,
    val connectionId: String,
    val once: Boolean,
    val precision: SharePrecision,
    val intervalSeconds: Long?,
    /** The sharer lets this member keep the trail (`history`). */
    val trail: Boolean,
    val startedAt: Instant?,
    val expiresAt: Instant,
    val last: ReceivedPosition? = null,
    val trailPoints: List<ReceivedPosition> = emptyList(),
    /** The positions the sharer sent from their own log (`location.snapshot`). */
    val snapshot: List<ReceivedPosition> = emptyList(),
) {
    override fun toString(): String = "IncomingShare"
}

/** Whether this vault has location: learnt by asking, since no message lists a release's features. */
enum class LocationAvailability { UNKNOWN, AVAILABLE, UNSUPPORTED }

private val NO_MISSED: StateFlow<Set<String>> = MutableStateFlow(emptySet())

private val NO_INCOMING: StateFlow<List<IncomingShare>> = MutableStateFlow(emptyList())

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
