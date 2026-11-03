package com.vettid.core.data.social

import com.vettid.core.vault.FeedItem
import java.time.Duration
import java.time.Instant

/**
 * Asks known only from their feed item (ANDROID-PLAN 0.1.116 §4 AA5, decisions 10 and 14a): `device.session.pending`,
 * `approval.pending` and `device.pair.pending` arrive once as events, so after a restart the feed item (its `ref`, its
 * `device_id`, its `at`) is all the app has until `pending.list` (§10.4.2) is read. Once a list has been merged it is
 * authoritative for sessions and approvals (an ask not in it is gone) and theirs are not made; the vault does not list
 * pairings yet (0.83.0), so a pairing's entry stays. They carry no body: an approval can only be
 * denied, a pairing offers only Reject, a session request offers Allow with a default length when the device is listed.
 */
internal object FeedOnlyAsks {
    /** The vault drops an undecided session request or pairing after 10 minutes (§6.8, §6.7). */
    val SESSION_LIFE: Duration = Duration.ofMinutes(10)

    /** A held request is kept at most 5 minutes (§6.8). */
    val HELD_LIFE: Duration = Duration.ofMinutes(5)

    /** What this run saw close: ask keys, and devices whose session request closed (by the event's `ts`). */
    data class Closed(val keys: Set<String> = emptySet(), val devices: Map<String, Instant> = emptyMap())

    fun entries(items: List<FeedItem>, have: Set<String>, closed: Closed, now: Instant): List<Approval.DeviceRequest> =
        items.mapNotNull { i ->
            val life = when (i.kind) {
                Approval.DeviceRequest.SESSION_PENDING, Approval.DeviceRequest.PAIR_PENDING -> SESSION_LIFE
                Approval.DeviceRequest.APPROVAL_PENDING -> HELD_LIFE
                else -> return@mapNotNull null
            }
            // Read (viewed in Notifications) is not decided; only archived and deleted items are out.
            if (i.status != "active" && i.status != "read") return@mapNotNull null
            val ref = i.ref?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val at = try {
                Instant.parse(i.at)
            } catch (_: java.time.format.DateTimeParseException) {
                return@mapNotNull null
            }
            val exp = at.plus(life)
            val req = Approval.DeviceRequest(
                type = i.kind, id = ref, deviceName = null, role = null, requestType = null, receivedAt = at, exp = exp,
                deviceId = i.deviceId?.takeIf { it.isNotEmpty() }, fromFeed = true,
            )
            val closedDevice = req.sessionDeviceId?.let { d -> closed.devices[d]?.let { !it.isBefore(at) } } == true
            val known = req.key in have || req.key in closed.keys || closedDevice
            if (!exp.isAfter(now) || known) null else req
        }
}
