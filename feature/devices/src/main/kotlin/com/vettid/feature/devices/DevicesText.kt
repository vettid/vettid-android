package com.vettid.feature.devices

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.devices.AgentGrant
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.devices.DeviceWire
import com.vettid.core.data.devices.GrantApproval
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
    fun name(d: DeviceInfo): String = label(d.name, role(d), unnamedRes(d))

    private fun unnamedRes(d: DeviceInfo): Int? = when {
        d.isAgent -> R.string.devices_an_agent_cap
        d.kind == DeviceInfo.KIND_DESKTOP -> R.string.devices_a_desktop_cap
        else -> null
    }

    /** The agent a pairing shows, by the name it gave (already sanitised): as [name], for an agent not yet listed. */
    @Composable
    fun agentName(name: String): String = label(name, stringResource(R.string.devices_role_agent), R.string.devices_an_agent_cap)

    /** The desktop a pairing shows, by the name it gave (already sanitised): as [name], for a desktop not yet listed. */
    @Composable
    fun desktopName(name: String): String =
        label(name, stringResource(R.string.devices_role_desktop), R.string.devices_a_desktop_cap)

    @Composable
    private fun label(name: String, role: String, unnamed: Int?): String {
        val shown = name.ifEmpty {
            return if (unnamed != null) stringResource(unnamed) else stringResource(R.string.devices_unnamed, role.lowercase())
        }
        // A self-asserted name that could pass for the phone itself or for VettID is shown with its role. Compared by a
        // key, so inner spaces, NBSP, ZWJ, fillers or U+2800 cannot slip past.
        val phone = stringResource(R.string.devices_this_phone)
        return if (AccountNames.couldPassFor(shown, phone) || AccountNames.couldPassFor(shown, "VettID")) {
            stringResource(R.string.devices_name_with_role, shown, role)
        } else {
            shown
        }
    }

    /** The name inside a sentence: bidirectionally isolated (a self-asserted name may run either way, §10.8). */
    @Composable
    fun sentenceName(d: DeviceInfo): String = when {
        d.name.isNotEmpty() -> AccountNames.isolate(name(d))
        d.isAgent -> stringResource(R.string.devices_an_agent)
        d.kind == DeviceInfo.KIND_DESKTOP -> stringResource(R.string.devices_a_desktop)
        else -> AccountNames.isolate(name(d))
    }

    /** The agent a pairing shows, inside a sentence: its name isolated, "an agent" when it gave none (§10.8). */
    @Composable
    fun sentenceAgent(name: String): String =
        if (name.isEmpty()) stringResource(R.string.devices_an_agent) else AccountNames.isolate(agentName(name))

    /** The desktop a pairing shows, inside a sentence: its name isolated, "a desktop" when it gave none (§10.8). */
    @Composable
    fun sentenceDesktop(name: String): String =
        if (name.isEmpty()) stringResource(R.string.devices_a_desktop) else AccountNames.isolate(desktopName(name))

    /** The agent a pairing shows at the start of a title: its name isolated, "An agent" (capital) when it gave none. */
    @Composable
    fun titleAgent(name: String): String = AccountNames.isolate(agentName(name))

    /** The desktop a pairing shows at the start of a title: its name isolated, "A desktop" (capital) when it gave none. */
    @Composable
    fun titleDesktop(name: String): String = AccountNames.isolate(desktopName(name))

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

/** How an agent's other permissions are said (ANDROID-PLAN 0.1.116 §4 AA3). */
internal object PermissionText {
    /** The label of one of the nine delegable scopes; null for a scope this app has no label for. */
    fun labelRes(scope: String): Int? = when (scope) {
        "connection.list" -> R.string.devices_scope_connection_list
        "connection.get" -> R.string.devices_scope_connection_get
        "message.send" -> R.string.devices_scope_message_send
        "message.list" -> R.string.devices_scope_message_list
        "message.get" -> R.string.devices_scope_message_get
        "message.read" -> R.string.devices_scope_message_read
        "profile.get" -> R.string.devices_scope_profile_get
        "action.list" -> R.string.devices_scope_action_list
        "action.invoke" -> R.string.devices_scope_action_invoke
        else -> null
    }

    /** What the snackbar says after a change to the permissions. */
    fun noticeRes(n: PermissionNotice): Int = when (n) {
        PermissionNotice.REMOVED -> R.string.devices_perm_removed
        PermissionNotice.ALREADY_REMOVED -> R.string.devices_perm_already_removed
        PermissionNotice.RESUMED -> R.string.devices_resumed
        PermissionNotice.NOT_PAUSED -> R.string.devices_not_paused
    }

    /** The scope's label, or "Another permission" for one without. */
    @Composable
    fun label(scope: String): String = stringResource(labelRes(scope) ?: R.string.devices_scope_unknown)

    /**
     * The row's second line: "Asks you each time" or "Allowed automatically, up to N an hour and M a day without asking"
     * (nothing for an approval this app does not know), then ", until <date>" and ", only with <names>". [names] are the
     * connections' names by id and [namesKnown] says the connections list was read: an id not in it is then "Removed
     * connection", a blank name "Name not shared yet"; before it was read no names are said. Empty when nothing is to be said.
     */
    @Composable
    fun detail(g: AgentGrant, names: Map<String, String>, namesKnown: Boolean): String {
        val parts = buildList {
            when (g.approval) {
                GrantApproval.AUTO -> add(stringResource(R.string.devices_perm_auto, g.perHour, g.perDay))
                GrantApproval.ASK -> add(stringResource(R.string.devices_perm_ask))
                GrantApproval.UNKNOWN -> Unit
            }
            g.expiresAt?.let { add(stringResource(R.string.devices_perm_until, Times.dayLabel(Times.day(it)))) }
            if (g.connections.isNotEmpty() && namesKnown) {
                val removed = stringResource(R.string.devices_perm_removed_connection)
                val unnamed = stringResource(R.string.devices_perm_unnamed_connection)
                val shown = g.connections.joinToString(", ") { id ->
                    when (val n = names[id]) {
                        null -> removed
                        else -> if (n.isBlank()) unnamed else AccountNames.isolate(n)
                    }
                }
                add(stringResource(R.string.devices_perm_only_with, shown))
            }
        }
        // The pieces after the first start with ", ": the first stands without it.
        return parts.joinToString("").removePrefix(", ")
    }
}
