package com.vettid.feature.connections

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.location.LocationAvailability
import com.vettid.core.data.location.LocationRepository
import com.vettid.core.data.location.NoLocation
import com.vettid.core.data.location.OutgoingShare
import com.vettid.core.data.location.SendIssue
import com.vettid.core.data.location.ShareInterval
import com.vettid.core.data.location.ShareLength
import com.vettid.core.data.location.SharePrecision
import com.vettid.core.data.location.ShareRequest
import com.vettid.core.data.location.ShareTime
import com.vettid.core.data.location.settingsIntent
import com.vettid.core.data.location.textRes
import com.vettid.core.data.location.ShareTiming
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.messageRes
import com.vettid.core.data.vault.vaultCode
import com.vettid.core.ui.components.DetailCard
import com.vettid.core.ui.components.HoldsInput
import com.vettid.core.ui.components.ModalBottomSheet
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.PrimaryButton
import com.vettid.core.ui.components.SecondaryButton
import com.vettid.core.ui.format.failureText
import com.vettid.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/*
 * The share sheet (ANDROID-PLAN §4 "Location", L1a): **Once** or **For a while**, the precision, how often, whether the connection
 * keeps the trail, and **Start sharing** (`location.share.start`, then the foreground service). Opened from the connection page.
 * It holds no position and nothing about one: the choices, and the permission the phone has given.
 */

/** What the phone has allowed of location (the permission is asked at the first share). */
enum class LocationPermission {
    /** Neither: not asked yet, or refused. */
    NONE,

    /** Approximate only (Android 12 and later let the member give just that): Exact is not offered. */
    APPROXIMATE,

    /** Precise (and approximate). */
    PRECISE,
    ;

    companion object {
        fun of(fine: Boolean, coarse: Boolean): LocationPermission = when {
            fine -> PRECISE
            coarse -> APPROXIMATE
            else -> NONE
        }

        /** The phone's grant now. */
        fun read(context: Context): LocationPermission = of(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        )

        /** What the phone is asked for at the first share. */
        val ASKED = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}

/** Immutable UI state of the share sheet. */
data class ShareLocationUiState(
    val connectionId: String = "",
    /** The sheet was opened and not dismissed. */
    val open: Boolean = false,
    val once: Boolean = false,
    val length: ShareLength = ShareLength.DEFAULT,
    val precision: SharePrecision = SharePrecision.APPROXIMATE,
    val interval: ShareInterval = ShareInterval.DEFAULT,
    val trail: Boolean = false,
    val permission: LocationPermission = LocationPermission.NONE,
    /** The member refused the permission when asked. */
    val permissionDenied: Boolean = false,
    /** `location.share.start` is out. */
    val busy: Boolean = false,
    /** The share that was started from this sheet, as the repository lists it now; null before, and once it is gone. */
    val started: OutgoingShare? = null,
    val startedId: String? = null,
    val error: FailureKind? = null,
    val errorCode: String? = null,
    /** Whether this vault has location; the phone's permission is not asked for until it is known to. */
    val availability: LocationAvailability = LocationAvailability.UNKNOWN,
    /** The start was refused because nothing could be sent (location off for VettID or on the phone, the app not in front). */
    val issue: SendIssue? = null,
    /** Opened from a request while a share to this connection is active: nothing is started (another would replace it). */
    val alreadySharing: Boolean = false,
) {
    /** The vault is known to have location. */
    val available: Boolean get() = availability == LocationAvailability.AVAILABLE

    /** Exact needs precise location: not offered once the member allowed only approximate (before the first ask it is). */
    val exactAvailable: Boolean get() = permission != LocationPermission.APPROXIMATE

    /** The share was started and no position has been sent yet: "Finding your location…". */
    val finding: Boolean get() = started != null && started.lastSentAt == null

    /** The sheet is on screen: opened, and not closed by the first position being sent for the share it started. */
    val visible: Boolean get() = open && (startedId == null || finding)

    /**
     * The member is in the middle of a decision, or the start is out: the daily owner check waits for them (VAULT-MESSAGING §3.6.5;
     * `HoldsInput`). Not while "Finding your location…" is shown after the start went out: nothing is then waiting for the member.
     * The sheet holds choices, not typed text.
     */
    val holdsInput: Boolean get() = visible && (startedId == null || busy)

    /** The ongoing text of a trail and an interval is for a share that lasts. */
    val ongoing: Boolean get() = !once
}

@HiltViewModel
class ShareLocationViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val location: LocationRepository = NoLocation,
) : ViewModel() {
    private val id: String = checkNotNull(savedState[ConnectionDetailRoute.ARG]) { "no connection" }
    /** A request being answered (Approvals): the sheet opens at once and the start names it. */
    private val saved = savedState
    private var requestId: String? = savedState.get<String?>(ConnectionDetailRoute.ARG_REQUEST)?.takeIf { it.isNotEmpty() }

    /** The request was used or the sheet was dismissed: it is not reused, and a recreated screen does not open the sheet again. */
    private fun forgetRequest() {
        requestId = null
        saved[ConnectionDetailRoute.ARG_REQUEST] = null
    }
    private val local = MutableStateFlow(ShareLocationUiState(id))

    val uiState: StateFlow<ShareLocationUiState> = combine(local, location.outgoing, location.availability) { s, shares, a ->
        s.copy(started = s.startedId?.let { sid -> shares.firstOrNull { it.shareId == sid } }, availability = a)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ShareLocationUiState(id))

    init {
        if (requestId != null) open()
    }

    /** An outgoing share to [connection] that has not ended, is not over, and is not a once share already delivered. */
    private fun activeShareTo(connection: String): Boolean = location.outgoing.value.any {
        it.connectionId == connection && it.expiresAt.isAfter(java.time.Instant.now()) && !(it.once && it.lastSentAt != null)
    }

    /** Opens the sheet with the defaults: For a while, 1 hour, Approximate, every minute, no trail. */
    fun open() {
        local.update { ShareLocationUiState(id, open = true, permission = it.permission) }
        // Whether the vault has location is read first when it is not known yet (the permission waits for it).
        if (location.availability.value == LocationAvailability.UNKNOWN) {
            viewModelScope.launch {
                try {
                    location.refresh()
                } catch (e: VaultFailure) {
                    local.update { it.copy(error = e.kind, errorCode = e.shownCode) }
                }
            }
        }
    }

    /** Closes the sheet; a share that was started goes on. */
    fun dismiss() {
        forgetRequest()
        local.update { it.copy(open = false) }
    }

    fun setOnce(once: Boolean) = local.update { it.copy(once = once) }

    fun setLength(length: ShareLength) = local.update { it.copy(length = length) }

    fun setPrecision(precision: SharePrecision) = local.update {
        if (precision == SharePrecision.EXACT && !it.exactAvailable) it else it.copy(precision = precision)
    }

    fun setInterval(interval: ShareInterval) = local.update { it.copy(interval = interval) }

    fun setTrail(trail: Boolean) = local.update { it.copy(trail = trail) }

    /** The phone's grant as it is now (when the screen comes back, e.g. from Android's settings). */
    fun setPermission(p: LocationPermission) = local.update {
        it.copy(permission = p, precision = fitted(it.precision, p), permissionDenied = it.permissionDenied && p == LocationPermission.NONE)
    }

    /**
     * The answer to the permission request. Refused: the sheet says so. Approximate only, with Exact chosen: Exact is taken
     * back to Approximate and nothing is started (the member sees why and decides). Otherwise the share starts.
     */
    fun permissionResult(p: LocationPermission) {
        val before = local.value
        val lostExact = before.precision == SharePrecision.EXACT && p == LocationPermission.APPROXIMATE
        local.update { it.copy(permission = p, precision = fitted(it.precision, p), permissionDenied = p == LocationPermission.NONE) }
        if (p != LocationPermission.NONE && !lostExact) start()
    }

    private fun fitted(precision: SharePrecision, p: LocationPermission) =
        if (precision == SharePrecision.EXACT && p == LocationPermission.APPROXIMATE) SharePrecision.APPROXIMATE else precision

    /** **Start sharing**: `location.share.start`, then the service. Needs the permission (asked first by the screen). */
    fun start() {
        val s = local.value
        val known = location.availability.value == LocationAvailability.AVAILABLE
        val inFlight = s.busy || s.startedId != null
        if (inFlight || s.permission == LocationPermission.NONE || !known) return
        // Answering a request while already sharing with this connection is off (as Share once is): the view model refuses it too.
        if (requestId != null && activeShareTo(id)) {
            local.update { it.copy(alreadySharing = true) }
            return
        }
        val timing = if (s.once) ShareTiming.Once else ShareTiming.ForAWhile(s.length, s.interval)
        local.update { it.copy(busy = true, error = null, errorCode = null, issue = null) }
        viewModelScope.launch {
            try {
                val precision = fitted(s.precision, s.permission)
                val share = location.start(ShareRequest(id, timing, precision, trail = s.trail && !s.once, requestId = requestId))
                forgetRequest()
                local.update { it.copy(busy = false, startedId = share.shareId) }
            } catch (e: VaultFailure) {
                local.update { it.copy(busy = false, error = e.kind, errorCode = e.shownCode, issue = SendIssue.of(e.code)) }
            }
        }
    }
}

/** What the share sheet can ask for. */
data class ShareLocationActions(
    val onOnce: (Boolean) -> Unit = {},
    val onLength: (ShareLength) -> Unit = {},
    val onPrecision: (SharePrecision) -> Unit = {},
    val onInterval: (ShareInterval) -> Unit = {},
    val onTrail: (Boolean) -> Unit = {},
    val onStart: () -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/**
 * The share sheet. [name] is the connection's first name (already isolated for a sentence). It reports unsent input with
 * [HoldsInput] while it is open, and uses the shell's `ModalBottomSheet`, which is not composed while the daily check covers the
 * shell (its state is in the view model and comes back after the check).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareLocationSheet(state: ShareLocationUiState, name: String, actions: ShareLocationActions) {
    HoldsInput(state.holdsInput)
    if (!state.visible) return
    ModalBottomSheet(
        onDismissRequest = actions.onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("share_location_sheet"),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text(
                stringResource(R.string.connections_location_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            val started = state.started
            if (started != null) {
                Finding(started, name)
            } else {
                Form(state, name, actions)
            }
            Spacer(Modifier.size(Spacing.l))
        }
    }
}

/** The share was started: when it ends, and "Finding your location…" until the first position is sent. */
@Composable
private fun Finding(started: OutgoingShare, name: String) {
    val context = LocalContext.current
    val until = remember(started.expiresAt) { ShareTime.format(context, started.expiresAt) }
    Text(
        stringResource(R.string.connections_location_started, name, until),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag("share_location_started"),
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("share_location_finding"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        Spacer(Modifier.size(Spacing.m))
        Text(stringResource(R.string.connections_location_finding), style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun Form(state: ShareLocationUiState, name: String, actions: ShareLocationActions) {
    val busy = state.busy
    Choice(state.once, R.string.connections_location_once, R.string.connections_location_once_note, !busy, "share_location_once") {
        actions.onOnce(true)
    }
    Choice(!state.once, R.string.connections_location_while, null, !busy, "share_location_while") { actions.onOnce(false) }
    if (state.ongoing) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            ShareLength.entries.forEach { l ->
                FilterChip(
                    selected = l == state.length,
                    onClick = { actions.onLength(l) },
                    enabled = !busy,
                    label = { Text(stringResource(lengthLabel(l))) },
                    modifier = Modifier.testTag("share_location_len_${l.name}"),
                )
            }
        }
    }
    Heading(R.string.connections_location_precision)
    SharePrecision.entries.forEach { p ->
        val enabled = !busy && (p != SharePrecision.EXACT || state.exactAvailable)
        Choice(p == state.precision, precisionLabel(p), precisionNote(p), enabled, "share_location_${p.name}") { actions.onPrecision(p) }
    }
    if (!state.exactAvailable) {
        Text(
            stringResource(R.string.connections_location_exact_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("share_location_exact_unavailable"),
        )
    }
    Text(
        stringResource(R.string.connections_location_precision_note, name),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.ongoing) {
        Heading(R.string.connections_location_interval)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            ShareInterval.entries.forEach { i ->
                FilterChip(
                    selected = i == state.interval,
                    onClick = { actions.onInterval(i) },
                    enabled = !busy,
                    label = { Text(stringResource(intervalLabel(i))) },
                    modifier = Modifier.testTag("share_location_int_${i.name}"),
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = state.trail, enabled = !busy, role = Role.Switch, onValueChange = actions.onTrail)
                .testTag("share_location_trail"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.connections_location_trail, name),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = state.trail, onCheckedChange = null, enabled = !busy)
        }
        Text(
            stringResource(R.string.connections_location_trail_note, name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (state.permission == LocationPermission.NONE) {
        Text(
            stringResource(R.string.connections_location_permission),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("share_location_permission"),
        )
        if (state.permissionDenied) {
            Text(
                stringResource(R.string.connections_location_permission_denied),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("share_location_permission_denied"),
            )
        }
    }
    state.issue?.let { issue ->
        val text = issue.textRes()
        if (text != null) {
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("share_location_issue"),
            )
        }
        val context = LocalContext.current
        issue.settingsIntent(context)?.let { intent ->
            androidx.compose.material3.TextButton(
                onClick = { runCatching { context.startActivity(intent) } },
                modifier = Modifier.heightIn(min = 48.dp).testTag("share_location_settings"),
            ) { Text(stringResource(com.vettid.core.data.R.string.data_location_open_settings)) }
        }
    }
    state.error?.takeIf { state.issue == null }?.let { e ->
        NoticeCard(
            kind = NoticeKind.WARNING,
            title = stringResource(R.string.connections_error_title),
            body = failureText(e.messageRes(), e.vaultCode(state.errorCode)),
            modifier = Modifier.testTag("share_location_error"),
        )
    }
    if (state.alreadySharing) {
        Text(
            stringResource(R.string.connections_location_already_sharing, name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("share_location_already_sharing"),
        )
    }
    PrimaryButton(
        stringResource(R.string.connections_location_start),
        actions.onStart,
        enabled = !busy && state.available,
        busy = busy,
        modifier = Modifier.testTag("share_location_start"),
    )
}

@Composable
private fun Heading(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = Spacing.s).semantics { heading() },
    )
}

/** A radio row of 48 dp or more: [label] with an optional [note] under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Choice(selected: Boolean, label: Int, note: Int?, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.size(Spacing.m))
        Column {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
            note?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun lengthLabel(l: ShareLength): Int = when (l) {
    ShareLength.MINUTES_15 -> R.string.connections_location_len_15m
    ShareLength.HOUR_1 -> R.string.connections_location_len_1h
    ShareLength.HOURS_8 -> R.string.connections_location_len_8h
    ShareLength.DAY_1 -> R.string.connections_location_len_1d
    ShareLength.DAYS_7 -> R.string.connections_location_len_7d
}

private fun intervalLabel(i: ShareInterval): Int = when (i) {
    ShareInterval.SECONDS_30 -> R.string.connections_location_int_30s
    ShareInterval.MINUTE_1 -> R.string.connections_location_int_1m
    ShareInterval.MINUTES_5 -> R.string.connections_location_int_5m
    ShareInterval.MINUTES_15 -> R.string.connections_location_int_15m
}

private fun precisionLabel(p: SharePrecision): Int = when (p) {
    SharePrecision.APPROXIMATE -> R.string.connections_location_approximate
    SharePrecision.EXACT -> R.string.connections_location_exact
    SharePrecision.CITY -> R.string.connections_location_city
}

private fun precisionNote(p: SharePrecision): Int = when (p) {
    SharePrecision.APPROXIMATE -> R.string.connections_location_approximate_note
    SharePrecision.EXACT -> R.string.connections_location_exact_note
    SharePrecision.CITY -> R.string.connections_location_city_note
}

/**
 * The connection page's location card: **Share my location** (opens the sheet), or, while the member shares with this connection,
 * "Sharing your location until {time}" with **Stop**; instead of that line "Paused until your daily check." while the check is due,
 * or why nothing is being sent (a stop to wait for, location off for VettID or on the phone) with **Open settings** where Android's
 * settings can put it right. [missed]: the last once share found no position.
 */
@Composable
internal fun LocationCard(location: DetailLocation?, missed: Boolean, busy: Boolean, onShare: () -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    DetailCard(Modifier.testTag("detail_location")) {
        if (location == null) {
            if (missed) {
                Text(
                    stringResource(com.vettid.core.data.R.string.data_location_missed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Spacing.s).testTag("detail_location_missed"),
                )
            }
            SecondaryButton(
                stringResource(R.string.connections_location_title),
                onShare,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("detail_location_share"),
            )
        } else {
            val until = remember(location.until) { ShareTime.format(context, location.until, Instant.now()) }
            val issueText = location.issue.textRes()
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        location.stopping -> stringResource(com.vettid.core.data.location.stoppingTextRes())
                        location.paused -> stringResource(com.vettid.core.data.R.string.data_location_paused)
                        issueText != null -> stringResource(issueText)
                        else -> stringResource(R.string.connections_location_sharing_until, until)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).testTag("detail_location_sharing"),
                )
                androidx.compose.material3.TextButton(
                    onClick = onStop,
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("detail_location_stop"),
                ) { Text(stringResource(R.string.connections_location_stop)) }
            }
            val settings = location.issue.settingsIntent(context)
            if (settings != null) {
                androidx.compose.material3.TextButton(
                    onClick = { runCatching { context.startActivity(settings) } },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("detail_location_settings"),
                ) { Text(stringResource(com.vettid.core.data.R.string.data_location_open_settings)) }
            }
        }
    }
}

/** The connection's name as the sheet says it: its first name, else its display name, else a stand-in; isolated for a sentence. */
internal fun locationName(c: com.vettid.core.data.social.ConnectionInfo?, fallback: String): String =
    AccountNames.isolate(c?.let { sharingName(it) ?: AccountNames.cleaned(it.displayName) } ?: fallback)
