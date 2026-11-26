package com.vettid.core.data.social

import android.icu.lang.UCharacter
import com.vettid.core.data.account.AccountNames
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId

/**
 * (ANDROID-PLAN 0.1.145, 0.1.146) The connection a desktop's held block or removal names, as `connection.list` has it: listed
 * as active or stale, with names the app holds (a person's "First Last", a service's own name), told apart from a namesake by
 * the day it was connected. Pure.
 */
internal object DesktopTargets {
    /**
     * How the rows show the connection: its [name], and [connectedOn] ("(connected <date>)") only when another active or stale
     * connection has the same shown name.
     */
    data class Shown(val name: String, val connectedOn: LocalDate?)

    /** The connection [h] names in [cs], or null. */
    fun target(h: HeldRequest.DesktopConnection, cs: List<ConnectionInfo>): ConnectionInfo? =
        cs.firstOrNull { it.id == h.connectionId && live(it) }?.takeIf { name(it) != null }

    /** The name a target is shown by, cleaned; null when it has none. */
    fun name(c: ConnectionInfo): String? = AccountNames.cleaned(c.displayName)

    /**
     * How [h]'s connection is shown, or null for the deny-only row: not listed, no name, or (0.1.146) a namesake that the date
     * cannot tell apart (a `created_at` of either that does not read, or the same day in [zone]).
     */
    fun shown(h: HeldRequest.DesktopConnection, cs: List<ConnectionInfo>, zone: ZoneId): Shown? {
        val (t, name) = target(h, cs)?.let { c -> name(c)?.let { c to it } } ?: return null
        val key = key(name)
        val namesakes = cs.filter { it.id != t.id && live(it) && name(it)?.let(::key) == key }
        val on = t.createdAt?.atZone(zone)?.toLocalDate()
        val apart = on != null && namesakes.none { o -> o.createdAt?.atZone(zone)?.toLocalDate().let { it == null || it == on } }
        return when {
            namesakes.isEmpty() -> Shown(name, null)
            apart -> Shown(name, on)
            else -> null
        }
    }

    /**
     * Whether [req] may be approved against [cs] as it is now: true for anything but a desktop's block or removal; for one, it
     * was shown naming its connection, and [cs] still shows it the same way (not gone, renamed, out of active and stale, nor
     * newly or no longer a namesake's).
     */
    fun resolved(req: Approval.DeviceRequest, cs: List<ConnectionInfo>, zone: ZoneId): Boolean {
        val h = req.held as? HeldRequest.DesktopConnection ?: return true
        return req.heldConnection != null && shown(h, cs, zone) == Shown(req.heldConnection, req.heldConnectedOn)
    }

    /**
     * Two shown names are the same (0.1.146) when equal after cleaning (VAULT-MESSAGING §10.8 steps 1-3, one line), each run of
     * White_Space one space ([AccountNames.isWhiteSpace]'s fixed list), NFC and Unicode full case folding (ICU, as
     * [AccountNames.lookalikeKey] does: "ẞ", "ß" and "SS" meet as "ss"; a dotless "ı" stays apart from "i").
     */
    fun key(name: String): String {
        val one = buildString {
            var space = false
            AccountNames.cleaned(name).orEmpty().codePoints().forEach { cp ->
                if (AccountNames.isWhiteSpace(cp)) {
                    space = true
                } else {
                    if (space && isNotEmpty()) append(' ')
                    space = false
                    appendCodePoint(cp)
                }
            }
        }
        return Normalizer.normalize(UCharacter.foldCase(Normalizer.normalize(one, Normalizer.Form.NFC), true), Normalizer.Form.NFC)
    }

    private fun live(c: ConnectionInfo) = c.state == ConnectionState.ACTIVE || c.state == ConnectionState.STALE
}
