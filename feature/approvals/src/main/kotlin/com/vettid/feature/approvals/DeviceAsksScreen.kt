package com.vettid.feature.approvals

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.DataView
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId

/** The wall clock for "Waiting for 8 more minutes": read again every 20 seconds while shown. */
@Composable
internal fun rememberNow(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(NOW_TICK_MS)
            now = Instant.now()
        }
    }
    return now
}

private const val NOW_TICK_MS = 20_000L

/** "15 minutes", "1 hour", "4 hours", "90 minutes"; whole hours as hours. */
@Composable
internal fun durationText(seconds: Int): String = when {
    seconds < SECONDS_PER_MINUTE -> stringResource(R.string.approvals_duration_seconds, seconds)
    seconds % SECONDS_PER_HOUR == 0 ->
        (seconds / SECONDS_PER_HOUR).let { pluralStringResource(R.plurals.approvals_duration_hours, it, it) }
    else -> (seconds / SECONDS_PER_MINUTE).let { pluralStringResource(R.plurals.approvals_duration_minutes, it, it) }
}

private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3_600

/** "Waiting for 8 more minutes" (to [exp]); the request's `exp`, at most 10 minutes, 5 for a held request. */
@Composable
internal fun waitingText(exp: Instant?, now: Instant): String? {
    if (exp == null) return null
    val m = Times.minutesLeft(exp, now).toInt()
    return if (m <= 0) stringResource(R.string.approvals_waiting_less) else pluralStringResource(R.plurals.approvals_waiting_minutes, m, m)
}

/** The kind of device: Agent, Desktop, else Device. */
@Composable
internal fun kindWord(a: Approval.DeviceRequest): String = when (AccountNames.cleaned(a.role)?.lowercase()) {
    "agent" -> stringResource(R.string.approvals_kind_agent)
    "desktop" -> stringResource(R.string.approvals_kind_desktop)
    else -> stringResource(R.string.approvals_kind_device)
}

/** A device request's title (the row's and the screen's). */
@Composable
@Suppress("CyclomaticComplexMethod")
internal fun deviceTitle(
    a: Approval.DeviceRequest,
    items: List<com.vettid.core.data.items.ItemSummary>,
    itemsLoaded: Boolean = true,
): String {
    val who = deviceSentenceStart(a)
    return when {
        a.isPairing -> a.deviceName?.let { AccountNames.isolateOrNull(it) }
            ?.let { stringResource(R.string.approvals_pair_title_named, it) } ?: stringResource(R.string.approvals_pair_title)
        a.isSession -> stringResource(if (a.fromFeed) R.string.approvals_session_title_feed else R.string.approvals_session_title, who)
        else -> when (val h = DeviceAsks.show(a, items, itemsLoaded)) {
            is HeldShow.Use -> stringResource(R.string.approvals_held_use_title, who, AccountNames.isolate(h.item))
            is HeldShow.Get -> stringResource(R.string.approvals_held_get_title, who, AccountNames.isolate(h.item))
            HeldShow.Catalog -> stringResource(R.string.approvals_held_catalog_title, who)
            is HeldShow.NoItem -> stringResource(
                if (h.use) R.string.approvals_held_use_title else R.string.approvals_held_get_title,
                who,
                stringResource(if (h.loading) R.string.approvals_loading else R.string.approvals_held_no_item),
            )
            is HeldShow.Later -> stringResource(
                R.string.approvals_held_later_title,
                who,
                h.item?.let { AccountNames.isolate(it) }
                    ?: stringResource(if (h.loading) R.string.approvals_loading else R.string.approvals_held_no_item),
            )
            HeldShow.Desktop, HeldShow.Other, HeldShow.Gone -> stringResource(R.string.approvals_held_other_title, who)
        }
    }
}

/** A device request's row line under the title: "Agent · for 1 hour · Waiting for 8 more minutes". */
@Composable
internal fun deviceSupporting(a: Approval.DeviceRequest, now: Instant): String {
    val kind = kindWord(a)
    val first = if (a.isSession && a.seconds != null && !a.fromFeed) {
        stringResource(R.string.approvals_session_for, kind, durationText(a.seconds!!))
    } else {
        kind
    }
    return listOfNotNull(first, waitingText(a.exp, now)).joinToString(" · ")
}

/** The screen of a device request: an access request, a held approval or a pairing known from its feed item. */
@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun DeviceRequestScreen(
    a: Approval.DeviceRequest,
    state: ApprovalDetailUiState,
    actions: DecisionActions,
    modifier: Modifier = Modifier,
) {
    state.outcome?.let { o ->
        DeviceOutcomeScreen(a, o, actions, modifier)
        return
    }
    val now = rememberNow()
    val who = deviceSentenceStart(a)
    val held = state.heldShow
    val body: String? = when {
        a.isPairing -> stringResource(if (state.pairingHeld) R.string.approvals_pair_compare_body else R.string.approvals_pair_body)
        a.isSession -> null
        else -> when (held) {
            is HeldShow.Use -> stringResource(R.string.approvals_held_use_body, AccountNames.isolate(held.field))
            is HeldShow.Get -> null
            HeldShow.Catalog -> stringResource(R.string.approvals_held_catalog_body)
            is HeldShow.NoItem -> stringResource(if (held.loading) R.string.approvals_loading else R.string.approvals_held_no_item_body)
            is HeldShow.Later -> stringResource(R.string.approvals_held_later_body)
            HeldShow.Desktop -> stringResource(R.string.approvals_held_desktop_body)
            HeldShow.Gone -> stringResource(R.string.approvals_held_gone_body, who)
            HeldShow.Other, null -> stringResource(R.string.approvals_held_other_body)
        }
    }
    val pairing = a.isPairing
    FormScaffold(
        title = deviceTitle(if (pairing) a.copy(deviceName = state.pairingName ?: a.deviceName) else a, state.items, state.itemsLoaded),
        body = body,
        primaryLabel = when {
            pairing -> if (state.pairingHeld) stringResource(R.string.approvals_pair_compare) else null
            !state.canApprove -> null
            a.isSession -> stringResource(R.string.approvals_session_allow)
            else -> stringResource(R.string.approvals_approve)
        },
        onPrimary = if (pairing) actions.onOpenPairing else actions.onApprove,
        primaryEnabled = pairing || state.canApprove,
        busy = state.busy,
        secondaryLabel = if (pairing) null else stringResource(R.string.approvals_deny),
        onSecondary = actions.onDeny,
        tertiaryLabel = if (state.renewalUntil != null) stringResource(R.string.approvals_deny_end_access) else null,
        onTertiary = actions.onAskEndAccess,
        onBack = actions.onBack,
        modifier = modifier.testTag("approval_detail"),
    ) {
        waitingText(a.exp, now)?.takeIf { !pairing }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("device_waiting"))
            Spacer(Modifier.height(Spacing.m))
        }
        if (a.isSession) SessionFacts(a, who, state, actions)
        if (a.isHeld) HeldFacts(held)
        RenewalNotices(state, a, actions)
        state.error?.let {
            Spacer(Modifier.height(Spacing.m))
            Text(
                failureText(it, state.retryUntil, state.limit, state.errorCode),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("failure"),
            )
        }
    }
    if (state.confirmEnd) EndAccessDialog(a, actions)
}

@Composable
private fun SessionFacts(a: Approval.DeviceRequest, who: String, state: ApprovalDetailUiState, actions: DecisionActions) {
    Text(kindWord(a), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("device_kind"))
    if (a.fromFeed) {
        Text(
            stringResource(R.string.approvals_session_length_unknown),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("length_unknown"),
        )
    }
    Spacer(Modifier.height(Spacing.l))
    if (!state.canApprove) {
        Text(stringResource(R.string.approvals_session_not_listed, who), style = MaterialTheme.typography.bodyLarge)
        return
    }
    Text(stringResource(R.string.approvals_session_allow_for, who), style = MaterialTheme.typography.bodyLarge)
    Column(Modifier.selectableGroup().testTag("session_choices")) {
        val requested = a.seconds?.takeIf { !a.fromFeed }
        DeviceAsks.choices(requested).forEach { secs ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.touchTarget)
                    .selectable(selected = state.allowSeconds == secs, role = Role.RadioButton, onClick = { actions.onChooseSeconds(secs) })
                    .testTag("session_$secs"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = state.allowSeconds == secs, onClick = null)
                Text(durationText(secs), modifier = Modifier.padding(start = Spacing.m), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    if (AccountNames.cleaned(a.role)?.lowercase() == "agent") {
        Spacer(Modifier.height(Spacing.m))
        Text(stringResource(R.string.approvals_session_agent_note), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HeldFacts(held: HeldShow?) {
    when (held) {
        is HeldShow.Use -> {
            Spacer(Modifier.height(Spacing.m))
            Label(stringResource(R.string.approvals_held_use_data))
            // In a box with its length, so that the agent's text cannot pass for one of the app's own lines.
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                    .padding(Spacing.m)
                    .testTag("use_data_box"),
            ) {
                Text(
                    pluralStringResource(R.plurals.approvals_held_use_bytes, held.bytes, held.bytes),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("use_data_bytes"),
                )
                when (val d = held.data) {
                    is DataView.Text -> Text(
                        d.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("use_data_text"),
                    )
                    is DataView.Hex -> {
                        Text(stringResource(R.string.approvals_held_use_data_hex), style = MaterialTheme.typography.bodySmall)
                        Text(
                            d.hex,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("use_data_hex"),
                        )
                    }
                }
            }
        }
        is HeldShow.Get -> {
            val fields = held.fields?.joinToString(", ") { AccountNames.isolate(it) }
            Text(
                fields?.let { stringResource(R.string.approvals_held_get_fields, it) } ?: stringResource(R.string.approvals_held_get_all),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("get_fields"),
            )
            Spacer(Modifier.height(Spacing.m))
            // Always, with or without the `history` flag (since 0.42.0 every item.get is a reveal).
            Text(
                stringResource(R.string.approvals_held_get_history),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("get_history"),
            )
        }
        else -> Unit
    }
}

/** How a decision on a device request ended; [DeviceOutcome.NoLongerWaiting] is the entry going. */
@Composable
private fun DeviceOutcomeScreen(a: Approval.DeviceRequest, o: DeviceOutcome, actions: DecisionActions, modifier: Modifier) {
    val who = deviceSentenceInline(a)
    val text = when (o) {
        is DeviceOutcome.Allowed ->
            o.until?.let { stringResource(R.string.approvals_session_allowed, untilText(it)) }
                ?: stringResource(R.string.approvals_session_allowed_plain)
        DeviceOutcome.Denied -> stringResource(R.string.approvals_held_denied)
        DeviceOutcome.NoLongerWaiting -> stringResource(R.string.approvals_no_longer_waiting)
        is DeviceOutcome.Approved -> when (val r = o.result) {
            // The vault did not say whether it was done: not "Approved".
            null -> stringResource(R.string.approvals_held_no_result)
            "ok" -> stringResource(R.string.approvals_held_approved)
            "forbidden" -> stringResource(R.string.approvals_held_forbidden, who)
            "session_required", "denied" -> stringResource(R.string.approvals_held_session_ended, who)
            "way_not_allowed" -> stringResource(R.string.approvals_held_way_not_allowed)
            else -> {
                val code = AccountNames.isolateOrNull(AccountNames.cleanedCapped(r, MAX_CODE)) ?: "?"
                stringResource(R.string.approvals_held_other_result, code)
            }
        }
    }
    FormScaffold(
        title = text,
        primaryLabel = stringResource(R.string.approvals_ok),
        onPrimary = actions.onFinish,
        onBack = actions.onFinish,
        modifier = modifier.testTag("device_outcome"),
    ) {}
}

private const val MAX_CODE = 64

/** The time of day when it is today, else the date and time. */
@Composable
private fun untilText(until: Instant): String {
    val zone = ZoneId.systemDefault()
    val sameDay = until.atZone(zone).toLocalDate() == Instant.now().atZone(zone).toLocalDate()
    return if (sameDay) Times.time(until) else Times.full(until)
}
