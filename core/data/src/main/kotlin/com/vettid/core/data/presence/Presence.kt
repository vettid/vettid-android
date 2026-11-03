package com.vettid.core.data.presence

import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.VaultJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** The member's own state (VAULT-MESSAGING §10.17): `available` is the vault's default. */
enum class PresenceState(val wire: String) {
    AVAILABLE("available"),
    BUSY("busy"),
    AWAY("away"),
    INVISIBLE("invisible"),
    ;

    companion object {
        fun of(wire: String?): PresenceState? = entries.firstOrNull { it.wire == wire }
    }
}

/** Who may see the state: [ALL] (`all`, the vault's default) or [NONE] (`none`); `except` turns it round for some connections. */
enum class PresenceShare(val wire: String) { ALL("all"), NONE("none") }

/**
 * The member's presence policy, one versioned object (§10.17, §10.1). [except] (0 to [MAX_EXCEPT] connection ids, here
 * deduplicated and sorted as the vault keeps them) inverts [share] for those connections. Memory only.
 */
data class PresencePolicy(
    val version: Long,
    val state: PresenceState = PresenceState.AVAILABLE,
    val share: PresenceShare = PresenceShare.ALL,
    val except: List<String> = emptyList(),
) {
    /** Whether the member's status is shared with [connectionId]: [share] turned round for the connections in [except]. */
    fun sharesWith(connectionId: String): Boolean = (share == PresenceShare.ALL) != (connectionId in except)

    /** The policy with [connectionId] shared with, or not (adds or removes it in [except]). Unchanged when it already is so. */
    fun withSharedWith(connectionId: String, shared: Boolean): PresencePolicy {
        if (sharesWith(connectionId) == shared) return this
        val inverted = share == PresenceShare.ALL != shared // the connection is to be an exception
        return copy(except = if (inverted) cleaned(except + connectionId) else except - connectionId)
    }

    /**
     * The policy with [share] chosen. A change starts the exceptions again: they turn the other choice round, so the same ids
     * would mean the opposite (a connection hidden from the member's status would see it).
     */
    fun withShare(share: PresenceShare): PresencePolicy = if (this.share == share) this else copy(share = share, except = emptyList())

    /** The policy with the exceptions [ids] (deduplicated and sorted). */
    fun withExcept(ids: Collection<String>): PresencePolicy = copy(except = cleaned(ids))

    companion object {
        /** `except` holds at most 1,024 connection ids (§10.17). */
        const val MAX_EXCEPT = 1_024

        fun cleaned(ids: Collection<String>): List<String> = ids.filter { it.isNotEmpty() }.distinct().sorted()
    }
}

/** What the app knows of one connection's presence, in memory only. */
sealed interface PresenceReading {
    /** Asked; no `presence.result` yet and `exp` not reached: "Checking…". */
    data object Checking : PresenceReading

    /** The question was sent and no result came by `exp` (30 s): "Status unknown", which the vault cannot tell from a refusal. */
    data object Unknown : PresenceReading

    /** The question could not be sent or was refused on our side (transport, no response, an unreadable answer): the failure's text. */
    data class Failed(val kind: FailureKind, val code: String?) : PresenceReading

    /** The owner check is due or held (or the vault locked): nothing is sent and no line is shown. */
    data object Closed : PresenceReading

    /** The connection answered: [state] is available, busy or away; [lastActive] is rounded down to 5 minutes by the vault. */
    data class Known(val state: PresenceState, val lastActive: Instant?) : PresenceReading
}

/** Whether this vault has presence: learnt by asking (`presence.get`), since no message lists a release's features. */
enum class PresenceAvailability { UNKNOWN, AVAILABLE, UNSUPPORTED }

/** How long ago a connection was last active, as the line shows it (ANDROID-PLAN §4 "Presence"). */
sealed interface PresenceAge {
    /** Under 5 minutes: the vault rounds `last_active` down to 5 minutes, so "0 min ago" would be said. */
    data object JustNow : PresenceAge

    /** "active {n} min ago", up to the hour. */
    data class Minutes(val minutes: Int) : PresenceAge

    /** "active today": an hour or more ago, on this calendar day. */
    data object Today : PresenceAge

    /** An hour or more ago on an earlier calendar day: "last seen 2 Nov"; [withYear] when it is not this year. */
    data class Date(val date: LocalDate, val withYear: Boolean) : PresenceAge

    companion object {
        private const val SMALLEST_MINUTES = 5L
        private const val HOUR_MINUTES = 60L

        /**
         * [lastActive] seen at [now]; null (nothing shown) without it or in the future. An hour or more ago it is today, or the
         * date of an earlier day (in [zone]), with the year when that is not [now]'s year.
         */
        fun of(lastActive: Instant?, now: Instant, zone: ZoneId = ZoneId.systemDefault()): PresenceAge? {
            if (lastActive == null) return null
            val minutes = Duration.between(lastActive, now).toMinutes()
            return when {
                minutes < 0 -> null
                minutes < SMALLEST_MINUTES -> JustNow
                minutes < HOUR_MINUTES -> Minutes(minutes.toInt())
                else -> dayOf(lastActive, now, zone)
            }
        }

        private fun dayOf(lastActive: Instant, now: Instant, zone: ZoneId): PresenceAge {
            val day = lastActive.atZone(zone).toLocalDate()
            val today = now.atZone(zone).toLocalDate()
            return if (day == today) Today else Date(day, withYear = day.year != today.year)
        }
    }
}

/** The bodies of §10.17 as models. A body that does not read yields nothing: the app never shows what it cannot name. */
object PresenceWire {
    /** `presence.get`'s answer. No `version`, or a `state` or `share` the app does not know, is no policy (never a guessed default). */
    fun policy(body: JsonObject): PresencePolicy? {
        val version = version(body)
        // An `except` entry that does not read makes the whole policy unreadable: dropping it would let the next `presence.set`
        // write the list without it, and that person would see the member under `share: all`.
        val except = exceptIds(body)
        val state = PresenceState.of(VaultJson.str(body, "state"))
        val share = PresenceShare.entries.firstOrNull { it.wire == VaultJson.str(body, "share") }
        val head = if (version != null && state != null) version to state else null
        val tail = if (share != null && except != null) share to except else null
        if (head == null || tail == null) return null
        return PresencePolicy(head.first, head.second, tail.first, PresencePolicy.cleaned(tail.second))
    }

    /** The `except` ids, or null when the member does not read as a list of at most 1,024 strings. */
    private fun exceptIds(body: JsonObject): List<String>? {
        val raw = body["except"] ?: return emptyList()
        val list = (raw as? JsonArray)?.takeIf { it.size <= PresencePolicy.MAX_EXCEPT }
        val ids = list?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return ids?.takeIf { it.size == list.size }
    }

    /** `presence.set`'s answer: the new version. */
    fun version(body: JsonObject): Long? = (body["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    /** `presence.query`'s answer. */
    data class Asked(val pingId: String, val exp: Instant?)

    /** The answer, or null when it does not read: no `ping_id`, or an `exp` that is there and does not parse (a failure). */
    fun asked(body: JsonObject): Asked? {
        val ping = VaultJson.str(body, "ping_id")?.takeIf { it.isNotEmpty() }
        val rawExp = VaultJson.str(body, "exp")
        val exp = instant(rawExp)
        return if (ping == null || (rawExp != null && exp == null)) null else Asked(ping, exp)
    }

    /** `presence.result`'s body. */
    data class Result(val connectionId: String, val pingId: String, val state: PresenceState, val lastActive: Instant?)

    /**
     * The event, or null when it does not read: no ids, or a `state` other than available, busy or away (the asking vault
     * accepts nothing else, §10.17). A `last_active` more than 5 minutes ahead of [now] is left out, a smaller skew is [now].
     */
    fun result(body: JsonObject, now: Instant): Result? {
        val connection = VaultJson.str(body, "connection_id")?.takeIf { it.isNotEmpty() }
        val ping = VaultJson.str(body, "ping_id")?.takeIf { it.isNotEmpty() }
        val state = PresenceState.of(VaultJson.str(body, "state"))?.takeIf { it != PresenceState.INVISIBLE }
        val rawActive = VaultJson.str(body, "last_active")
        val parsed = instant(rawActive)
        // A `last_active` that is there and does not parse drops the result: it is not guessed at.
        val unreadableActive = rawActive != null && parsed == null
        val active = parsed?.takeUnless { it.isAfter(now.plus(SKEW)) }?.let { if (it.isAfter(now)) now else it }
        val ids = if (connection != null && ping != null) connection to ping else null
        return if (unreadableActive || ids == null || state == null) null else Result(ids.first, ids.second, state, active)
    }

    private val SKEW: Duration = Duration.ofMinutes(5)

    private fun instant(s: String?): Instant? = try {
        s?.let { Instant.parse(it) }
    } catch (_: DateTimeParseException) {
        null
    }
}

/** The vault calls behind [PresenceManager] (§10.17), so that it can be tested without a vault. */
interface PresenceOps {
    suspend fun get(): JsonObject

    /** `presence.set`: only the members that change are sent. */
    suspend fun set(version: Long, state: String?, share: String?, except: List<String>?): JsonObject

    suspend fun query(connectionId: String): JsonObject
}

/**
 * The member's presence (ANDROID-PLAN §4 "Presence"; VAULT-MESSAGING §10.17). Everything is in memory: nothing is kept
 * past a lock or the owner check's hold, and none of it is sent while the vault is locked, due or held.
 */
interface PresenceRepository {
    /** Whether this vault has presence. Nothing of presence is shown unless [PresenceAvailability.AVAILABLE]. */
    val availability: StateFlow<PresenceAvailability>

    /** The member's policy; null until read, and after a lock. */
    val policy: StateFlow<PresencePolicy?>

    /** What each asked connection answered (or not), by connection id. */
    val readings: StateFlow<Map<String, PresenceReading>>

    /** Reads the policy again (`presence.get`). Throws the vault's failure; `unsupported_type` also ends [availability]. */
    suspend fun refreshPolicy()

    /**
     * Changes the policy: [change] is applied to the current one and the difference is sent with its `version`. On
     * `conflict` the policy is read again and [change] applied once more to the fresh one; a second `conflict` is thrown.
     */
    suspend fun update(change: (PresencePolicy) -> PresencePolicy)

    /**
     * Asks [connectionId]'s vault how it is (`presence.query`), at most once a minute per connection: inside the minute the
     * earlier answer stands. Never throws: whatever goes wrong reads as [PresenceReading.Unknown].
     */
    suspend fun query(connectionId: String)

    /**
     * The connections asked less than a minute ago: asking again would send nothing (the vault answers a second question with
     * the first), so the refresh icon waits until the minute has passed.
     */
    val recent: StateFlow<Set<String>> get() = NO_RECENT
}

private val NO_RECENT: StateFlow<Set<String>> = MutableStateFlow(emptySet())

/**
 * [PresenceRepository.query] each time the vault has presence: when the screen opens before the first `presence.get` is
 * answered, and again when presence comes back after a hold or a failed read. Nothing is sent while there is none. Collects
 * until the screen's scope ends.
 */
suspend fun PresenceRepository.queryWhileAvailable(connectionId: String, visible: Flow<Boolean> = flowOf(true)) {
    // Asks, only while the screen is shown ([visible]: at least STARTED), when presence is there and the connection has no
    // reading (on opening, after a hold took the readings, when a reading went stale) and once each time the screen comes back.
    // While it is not shown nothing is sent: a question counts as the member's activity (§10.17), so a page left open in the
    // background must not keep asking.
    val held = readings.map { connectionId in it }.distinctUntilChanged()
    var shown = false
    combine(availability, held, visible) { a, has, v -> Triple(a == PresenceAvailability.AVAILABLE, has, v) }
        .distinctUntilChanged()
        .collect { (on, has, v) ->
            val appeared = v && !shown
            shown = v
            val wanted = !has || appeared
            if (on && v && wanted) query(connectionId)
        }
}

/** Presence where there is none (a screen's default): never available, nothing sent. */
object NoPresence : PresenceRepository {
    override val availability: StateFlow<PresenceAvailability> =
        MutableStateFlow(PresenceAvailability.UNSUPPORTED)
    override val policy: StateFlow<PresencePolicy?> = MutableStateFlow(null)
    override val readings: StateFlow<Map<String, PresenceReading>> =
        MutableStateFlow(emptyMap())

    override suspend fun refreshPolicy() = throw VaultFailure(FailureKind.NOT_SUPPORTED)

    override suspend fun update(change: (PresencePolicy) -> PresencePolicy) = throw VaultFailure(FailureKind.NOT_SUPPORTED)

    override suspend fun query(connectionId: String) = Unit
}
