package com.vettid.core.data.social

import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * The state behind `pending.list` (VAULT-MESSAGING 0.70.0 §10.4.2): the asks that arrive only as events
 * (`connection.authenticate.pending`, `device.session.pending`, `approval.pending`), read back after an unlock and a
 * passed owner check. Events arrive in no particular order (§8.4), so every list is judged against the vault's own
 * clock: [cut] is the `ts` of the first page of the last list merged, and an event whose inner `ts` is not later than
 * it is already reflected by that list and is ignored, creating or closing.
 *
 * Reads may overlap (a quiet read and the screen's, the double read after a passed check): a read takes a ticket when
 * it starts and a list is merged only if no read that started later has merged already ([merge] answers null), and
 * only a merged list moves the cut: a failed read changes nothing. Kinds this app does not show (`action`,
 * `location`) are skipped. [lock] ends all of it: the next unlock starts again.
 */
internal class PendingAsks {
    /** A listed ask the app shows: the event [type] it repeats, its parsed [key] and the requesting device. */
    class Entry(val type: String, val body: JsonObject, val key: String, val deviceId: String?)

    /** What a merge keeps: the listed [entries], and which kept events of the listed types stay ([keepEvent]). */
    class Plan(val entries: List<Entry>, private val newer: Set<String>) {
        /** An event of a listed type that the list does not name stays only if it is newer than the list. */
        fun keepEvent(key: String): Boolean = key in newer
    }

    private val lock = Any()
    private var cut: Instant? = null
    private var issued = 0L
    private var merged = 0L
    private var floor = 0L
    private var unsupported = false

    /** Creation events accepted: key to `ts`; those not later than the cut leave at each merge. */
    private val created = HashMap<String, Instant>()

    /**
     * Closing events accepted that no merged list reflects yet: the list a read brings may still hold what they closed,
     * and a creation event that comes after its own close (§8.4) is refused. Empty after an unlock until events arrive,
     * so a cold map may let a late close drop a newer stored request until the first list merges (the list then
     * settles it).
     */
    private val closedKeys = HashMap<String, Instant>()
    private val closedDevices = HashMap<String, Instant>()

    /** The `ts` of the first page of the last list merged; null before one. */
    val lastCut: Instant? get() = synchronized(lock) { cut }

    /** An older release answered `unsupported_type`: no read until the next unlock. */
    val isUnsupported: Boolean get() = synchronized(lock) { unsupported }

    /** A read starts. */
    fun begin(): Long = synchronized(lock) { ++issued }

    /** Whether the read of [ticket] is still current: no lock since it began and no later-started read has merged. */
    fun current(ticket: Long): Boolean = synchronized(lock) { ticket > floor && ticket >= merged }

    /** The older release's answer: keep the live events as they are until the next [reset]. */
    fun markUnsupported(ticket: Long) = synchronized(lock) {
        if (ticket > floor) unsupported = true
    }

    /** A lock: nothing read before it counts, and an unlock reads again, whatever an older release answered. */
    fun reset() = synchronized(lock) {
        floor = issued
        cut = null
        unsupported = false
        created.clear()
        closedKeys.clear()
        closedDevices.clear()
    }

    private fun reflected(ts: Instant): Boolean = cut?.let { !ts.isAfter(it) } ?: false

    /**
     * A creation event of ask [key] (a session request of [deviceId]): false when the last list already reflects it or a
     * closing event came first. Called inside the store's edit, so that no merge falls between the decision and the write.
     */
    fun acceptCreate(key: String, ts: Instant, deviceId: String? = null, session: Boolean = false): Boolean = synchronized(lock) {
        if (reflected(ts)) return false
        if (closedKeys[key]?.let { !it.isBefore(ts) } == true) return false
        if (session && deviceId != null && closedDevices[deviceId]?.let { !it.isBefore(ts) } == true) return false
        created[key] = ts
        if (created.size > MAX_KEPT) created.entries.minByOrNull { it.value }?.let { created.remove(it.key) }
        true
    }

    /** A closing event of ask [key] (`connection.authenticate.decided`, `approval.decided`): false when reflected. */
    fun acceptClose(key: String, ts: Instant): Boolean = synchronized(lock) {
        if (reflected(ts)) return false
        remember(closedKeys, key, ts)
        true
    }

    /** A `device.session` event for [deviceId]: false when reflected. A device has at most one session request. */
    fun acceptDeviceClose(deviceId: String, ts: Instant): Boolean = synchronized(lock) {
        if (reflected(ts)) return false
        remember(closedDevices, deviceId, ts)
        true
    }

    private fun remember(m: MutableMap<String, Instant>, k: String, ts: Instant) {
        m[k] = m[k]?.takeIf { it.isAfter(ts) } ?: ts
        if (m.size > MAX_KEPT) m.entries.minByOrNull { it.value }?.let { m.remove(it.key) }
    }

    /** Whether a closing event for ask [key] (or for the [session] request's [deviceId]) was accepted and no list reflects it yet. */
    fun wasClosed(key: String, deviceId: String?, session: Boolean): Boolean = synchronized(lock) {
        closedKeys.containsKey(key) || (session && deviceId != null && closedDevices.containsKey(deviceId))
    }

    /** Whether the ask [key] was created after [ts]: a closing event at [ts] is not about it. */
    fun createdAfter(key: String, ts: Instant): Boolean = synchronized(lock) { created[key]?.isAfter(ts) == true }

    /**
     * Takes in the list read by [ticket] whose first page said [ts]: null when it is stale (a later read merged first, or
     * a lock came), else what to keep. Events that closed an ask while the read was on its way, later than the list,
     * leave it out again.
     */
    fun merge(ticket: Long, ts: Instant, listed: List<Entry>): Plan? = synchronized(lock) {
        if (ticket <= floor || ticket < merged) return null
        if (cut?.let { ts.isBefore(it) } == true) return null
        merged = ticket
        cut = ts
        val entries = listed.filter { e ->
            closedKeys[e.key]?.isAfter(ts) != true &&
                (e.type != SESSION || e.deviceId == null || closedDevices[e.deviceId]?.isAfter(ts) != true)
        }
        created.values.removeAll { !it.isAfter(ts) }
        closedKeys.values.removeAll { !it.isAfter(ts) }
        closedDevices.values.removeAll { !it.isAfter(ts) }
        Plan(entries, created.keys.toSet())
    }

    companion object {
        const val SESSION = "device.session.pending"

        /** The kinds of `pending.list` the app shows, by the event each repeats; `action` and `location` are skipped. */
        val TYPES = mapOf(
            "authenticate" to "connection.authenticate.pending",
            "session" to SESSION,
            "approval" to "approval.pending",
        )

        /** The event types whose entries the list replaces. */
        val LISTED_TYPES: Set<String> = TYPES.values.toSet()

        private const val MAX_KEPT = 1_000
    }
}
