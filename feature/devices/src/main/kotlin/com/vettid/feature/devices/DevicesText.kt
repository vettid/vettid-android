package com.vettid.feature.devices

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.devices.DeviceWire
import com.vettid.core.ui.format.Times
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** How the Devices & agents screens say a device's facts (ANDROID-PLAN 0.1.53 AA1). */
internal object DeviceText {
    /** The longest "N d ago" before a date is shown instead. */
    private const val DAYS_AS_AGO = 30L

    /** The role as shown: "Agent", "Desktop", or the vault's own `kind` text for a kind this app does not know (14d). */
    @Composable
    fun role(d: DeviceInfo): String = when {
        d.isAgent -> stringResource(R.string.devices_role_agent)
        d.kind == DeviceInfo.KIND_DESKTOP -> stringResource(R.string.devices_role_desktop)
        d.thisPhone -> stringResource(R.string.devices_this_phone)
        d.kind == DeviceInfo.KIND_APP -> stringResource(R.string.devices_role_phone)
        else -> DeviceWire.cleanName(d.kind).ifEmpty { stringResource(R.string.devices_role_unknown) }
    }

    /** The name as shown in a heading or a row: a device with no name is named by its role. */
    @Composable
    fun name(d: DeviceInfo): String {
        val shown = d.name.ifEmpty { return stringResource(R.string.devices_unnamed, role(d).lowercase()) }
        // A self-asserted name that could pass for the phone itself or for VettID is shown with its role.
        val phone = stringResource(R.string.devices_this_phone)
        return if (shown.equals(phone, ignoreCase = true) || shown.equals("VettID", ignoreCase = true)) {
            stringResource(R.string.devices_name_with_role, shown, role(d))
        } else {
            shown
        }
    }

    /** The name inside a sentence: bidirectionally isolated (a self-asserted name may run either way, §10.8). */
    @Composable
    fun sentenceName(d: DeviceInfo): String = AccountNames.isolate(name(d))

    /** "Access until 15:40" (the date with it when not today), or "No access". */
    @Composable
    fun access(d: DeviceInfo, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val until = d.sessionExpiresAt?.takeIf { d.hasAccess(now) } ?: return stringResource(R.string.devices_no_access)
        val sameDay = until.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
        return stringResource(R.string.devices_access_until, if (sameDay) Times.time(until, zone) else Times.full(until, zone))
    }

    /** "Last active 10 min ago", or "Not active yet". */
    @Composable
    fun lastActive(d: DeviceInfo, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = d.lastActiveAt ?: return stringResource(R.string.devices_not_active_yet)
        return stringResource(R.string.devices_last_active, ago(at, now, zone))
    }

    @Composable
    fun ago(at: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val age = Duration.between(at, now)
        return when {
            age.toMinutes() < 1 -> stringResource(R.string.devices_ago_now)
            age.toMinutes() < 60 -> stringResource(R.string.devices_ago_minutes, age.toMinutes().toInt())
            age.toHours() < 24 -> stringResource(R.string.devices_ago_hours, age.toHours().toInt())
            age.toDays() < DAYS_AS_AGO -> stringResource(R.string.devices_ago_days, age.toDays().toInt())
            else -> Times.short(at, now, zone)
        }
    }
}
