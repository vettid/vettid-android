package com.vettid.feature.approvals

import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.social.Approval
import java.time.Instant

/**
 * A renewal request (VAULT-MESSAGING 0.78.0 §6.8 step 3): an access-session request from a device that has a session
 * that has not expired. The end of that session is the device's `session_expires_at` of `device.list` (§10.3), read
 * when the request is shown and read again after a `sync.event` of kind `device.session` (§10.1);
 * `device.session.pending` does not carry it.
 */
internal object Renewal {
    /**
     * When the session the request would renew ends, or null when it is a first request: not a session request, a
     * device that is not listed or has no session, a session that has passed at [now], or a list that could not be
     * read ([listRead] false: a stale list is never trusted to say there is a session).
     */
    fun expiresAt(request: Approval?, devices: List<DeviceInfo>, listRead: Boolean, now: Instant): Instant? =
        sessionEnd(request, devices, listRead)?.takeIf { it.isAfter(now) }

    /** The device's `session_expires_at` as listed, passed or not (it says when the screen must look again). */
    fun sessionEnd(request: Approval?, devices: List<DeviceInfo>, listRead: Boolean): Instant? {
        val id = (request as? Approval.DeviceRequest)?.takeIf { listRead && it.isSession }?.deviceId
        return devices.firstOrNull { it.id == id && !it.thisPhone }?.sessionExpiresAt
    }
}
