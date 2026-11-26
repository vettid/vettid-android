package com.vettid.core.data.social

import com.vettid.core.data.account.AccountNames

/**
 * (ANDROID-PLAN 0.1.145) The connection a desktop's held block or removal names, as `connection.list` has it: listed as active
 * or stale, with names the app holds (a person's "First Last", a service's own name). Pure.
 */
internal object DesktopTargets {
    /** The connection [h] names in [cs], or null. */
    fun target(h: HeldRequest.DesktopConnection, cs: List<ConnectionInfo>): ConnectionInfo? =
        cs.firstOrNull { it.id == h.connectionId && (it.state == ConnectionState.ACTIVE || it.state == ConnectionState.STALE) }
            ?.takeIf { name(it) != null }

    /** The name a target is shown by, cleaned; null when it has none. */
    fun name(c: ConnectionInfo): String? = AccountNames.cleaned(c.displayName)

    /**
     * Whether [req] may be approved against [cs] as it is now: true for anything but a desktop's block or removal; for one, it
     * was shown naming its connection ([Approval.DeviceRequest.heldConnection]) and [cs] still names it the same way (not
     * gone, not renamed, not blocked or otherwise out of active and stale).
     */
    fun resolved(req: Approval.DeviceRequest, cs: List<ConnectionInfo>): Boolean {
        val h = req.held as? HeldRequest.DesktopConnection ?: return true
        return req.heldConnection != null && target(h, cs)?.let(::name) == req.heldConnection
    }
}
